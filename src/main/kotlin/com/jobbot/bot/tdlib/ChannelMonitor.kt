// File: src/main/kotlin/com/jobbot/bot/tdlib/ChannelMonitor.kt

package com.jobbot.bot.tdlib

import com.jobbot.bot.TelegramBot
import com.jobbot.core.MessageProcessor
import com.jobbot.data.Database
import com.jobbot.data.models.*
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * UPDATED: ChannelMonitor with simple media support
 * Extracts media info and passes it through for notifications
 */
class ChannelMonitor(
    private val database: Database,
    private val messageProcessor: MessageProcessor,
    private var bot: TelegramBot?
) {
    private val logger = getLogger("ChannelMonitor")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Bounded cache with automatic cleanup
    private val channelIdCache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 500 // Keep max 500 entries
        }
    }
    
    fun updateBotReference(botInstance: TelegramBot) {
        this.bot = botInstance
    }
    
    fun handleNewMessage(update: TdApi.UpdateNewMessage, client: Client?) {
        try {
            val message = update.message
            val chatId = message.chatId
            val isChannelPost = message.isChannelPost
            
            logger.debug { "Processing message from chat $chatId, isChannelPost: $isChannelPost" }
            
            val isMonitoredChat = isChannelPost || isMonitoredGroupChat(chatId)
            
            if (isMonitoredChat) {
                val channelId = getChannelIdentifier(chatId)
                logger.debug { "Message from channelId: $channelId (isChannelPost: $isChannelPost)" }
                
                if (channelId == null) {
                    logger.debug { "Could not determine channel identifier for chat $chatId" }
                    return
                }
                
                if (!database.channelExists(channelId)) {
                    logger.debug { "Ignoring message from unmonitored chat: $channelId" }
                    return
                }
                
                logger.info { "Processing message from monitored chat: $channelId" }
                
                // Extract message content and media
                val (plainText, formattedText, mediaGroup) = extractMessageContent(message.content)
                
                // Process if there's text OR media content
                if (plainText.isBlank() && mediaGroup.isEmpty()) {
                    logger.debug { "Message has no text or media content, skipping" }
                    return
                }
                
                logger.debug { "Processing: text=${plainText.length} chars, media=${mediaGroup.size} items" }
                
                scope.launch {
                    try {
                        // Get channel details
                        val channelDetails = database.getAllChannelsWithDetails().find { it.channelId == channelId }
                        val displayName = when {
                            !channelDetails?.channelTag.isNullOrBlank() -> channelDetails!!.channelTag!!
                            !channelDetails?.channelName.isNullOrBlank() -> channelDetails!!.channelName!!
                            else -> if (isChannelPost) "Channel" else "Group"
                        }
                        
                        // Generate message link
                        val messageLink = generateMessageLink(channelDetails, message.id)
                        
                        // Create ChannelMessage with media support
                        val channelMessage = ChannelMessage(
                            channelId = channelId,
                            channelName = displayName,
                            messageId = message.id,
                            text = plainText, // For keyword matching
                            formattedText = formattedText, // For display
                            senderUsername = null,
                            messageLink = messageLink,
                            mediaGroup = mediaGroup // NEW: Include media items
                        )
                        
                        logger.debug { "Calling messageProcessor.processChannelMessage..." }
                        val notifications = messageProcessor.processChannelMessage(channelMessage)
                        
                        logger.info { "Message processing complete. Generated ${notifications.size} notifications" }
                        
                        // Queue notifications with media
                        for (notification in notifications) {
                            logger.debug { "Queueing notification for user ${notification.userId} with ${notification.mediaGroup.size} media items" }
                            bot?.queueNotification(notification)
                        }
                        
                        logger.debug { "Processed message from $channelId: ${notifications.size} notifications, ${mediaGroup.size} media items" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing new message" }
                        ErrorTracker.logError("ERROR", "Message processing error: ${e.message}", e)
                    }
                }
            } else {
                logger.debug { "Ignoring message from unmonitored chat $chatId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in handleNewMessage" }
            ErrorTracker.logError("ERROR", "New message handler error: ${e.message}", e)
        }
    }
    
    /**
     * UPDATED: Extract message content and media information
     * Returns Triple<plainText, formattedText, mediaItems>
     */
    private fun extractMessageContent(content: TdApi.MessageContent): Triple<String, String, List<MediaItem>> {
        return when (content) {
            is TdApi.MessageText -> {
                val plainText = content.text.text
                val formattedText = convertFormattedTextToMarkdown(content.text)
                logger.debug { "Text message - ${plainText.length} chars" }
                Triple(plainText, formattedText, emptyList())
            }
            
            is TdApi.MessagePhoto -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                
                // Extract photo info - get the largest size
                val photo = content.photo
                val largestPhoto = photo.sizes.maxByOrNull { it.width * it.height }
                
                val mediaItems = if (largestPhoto != null) {
                    listOf(MediaItem(
                        type = MediaType.PHOTO,
                        fileId = largestPhoto.photo.remote.id,
                        fileUniqueId = largestPhoto.photo.remote.uniqueId,
                        width = largestPhoto.width,
                        height = largestPhoto.height
                    ))
                } else emptyList()
                
                logger.debug { "Photo message - caption: ${plainText.length} chars, photo: ${largestPhoto?.width}x${largestPhoto?.height}" }
                Triple(plainText, formattedText, mediaItems)
            }
            
            is TdApi.MessageVideo -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                
                val video = content.video
                val mediaItems = listOf(MediaItem(
                    type = MediaType.VIDEO,
                    fileId = video.video.remote.id,
                    fileUniqueId = video.video.remote.uniqueId,
                    width = video.width,
                    height = video.height,
                    duration = video.duration,
                    fileName = video.fileName,
                    mimeType = video.mimeType,
                    thumbnailFileId = video.thumbnail?.file?.remote?.id
                ))
                
                logger.debug { "Video message - caption: ${plainText.length} chars, video: ${video.width}x${video.height}" }
                Triple(plainText, formattedText, mediaItems)
            }
            
            is TdApi.MessageDocument -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                
                val document = content.document
                val mediaItems = listOf(MediaItem(
                    type = MediaType.DOCUMENT,
                    fileId = document.document.remote.id,
                    fileUniqueId = document.document.remote.uniqueId,
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                    thumbnailFileId = document.thumbnail?.file?.remote?.id
                ))
                
                logger.debug { "Document message - caption: ${plainText.length} chars, file: ${document.fileName}" }
                Triple(plainText, formattedText, mediaItems)
            }
            
            is TdApi.MessageAnimation -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                
                val animation = content.animation
                val mediaItems = listOf(MediaItem(
                    type = MediaType.ANIMATION,
                    fileId = animation.animation.remote.id,
                    fileUniqueId = animation.animation.remote.uniqueId,
                    width = animation.width,
                    height = animation.height,
                    duration = animation.duration,
                    fileName = animation.fileName,
                    mimeType = animation.mimeType,
                    thumbnailFileId = animation.thumbnail?.file?.remote?.id
                ))
                
                logger.debug { "Animation message - caption: ${plainText.length} chars" }
                Triple(plainText, formattedText, mediaItems)
            }
            
            is TdApi.MessageAudio -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                
                val audio = content.audio
                val mediaItems = listOf(MediaItem(
                    type = MediaType.AUDIO,
                    fileId = audio.audio.remote.id,
                    fileUniqueId = audio.audio.remote.uniqueId,
                    duration = audio.duration,
                    fileName = audio.fileName,
                    mimeType = audio.mimeType
                ))
                
                logger.debug { "Audio message - caption: ${plainText.length} chars" }
                Triple(plainText, formattedText, mediaItems)
            }
            
            is TdApi.MessageVoiceNote -> {
                val voice = content.voiceNote
                val mediaItems = listOf(MediaItem(
                    type = MediaType.VOICE,
                    fileId = voice.voice.remote.id,
                    fileUniqueId = voice.voice.remote.uniqueId,
                    duration = voice.duration,
                    mimeType = voice.mimeType
                ))
                
                logger.debug { "Voice note message - ${voice.duration}s" }
                Triple("", "", mediaItems)
            }
            
            is TdApi.MessageVideoNote -> {
                val videoNote = content.videoNote
                val mediaItems = listOf(MediaItem(
                    type = MediaType.VIDEO_NOTE,
                    fileId = videoNote.video.remote.id,
                    fileUniqueId = videoNote.video.remote.uniqueId,
                    duration = videoNote.duration,
                    thumbnailFileId = videoNote.thumbnail?.file?.remote?.id
                ))
                
                logger.debug { "Video note message - ${videoNote.duration}s" }
                Triple("", "", mediaItems)
            }
            
            is TdApi.MessageSticker -> {
                val sticker = content.sticker
                val mediaItems = listOf(MediaItem(
                    type = MediaType.STICKER,
                    fileId = sticker.sticker.remote.id,
                    fileUniqueId = sticker.sticker.remote.uniqueId,
                    width = sticker.width,
                    height = sticker.height,
                    thumbnailFileId = sticker.thumbnail?.file?.remote?.id
                ))
                
                logger.debug { "Sticker message" }
                Triple("", "", mediaItems)
            }
            
            else -> {
                logger.debug { "Unsupported message type: ${content.javaClass.simpleName}" }
                Triple("", "", emptyList())
            }
        }
    }
    
    /**
     * UPDATED: MarkdownV2 conversion (unchanged, working well)
     */
    private fun convertFormattedTextToMarkdown(formattedText: TdApi.FormattedText): String {
        if (formattedText.entities.isEmpty()) {
            return TelegramMarkdownConverter.escapeMarkdownV2(formattedText.text)
        }
        
        try {
            val text = formattedText.text
            val entities = formattedText.entities.sortedBy { it.offset }
            val result = StringBuilder()
            var currentPos = 0
            
            for (entity in entities) {
                // Add text before this entity (properly escaped)
                if (entity.offset > currentPos) {
                    result.append(TelegramMarkdownConverter.escapeMarkdownV2(
                        text.substring(currentPos, entity.offset)
                    ))
                }
                
                // Get the entity text
                val entityText = text.substring(entity.offset, entity.offset + entity.length)
                
                // Handle different entity types
                when (entity.type) {
                    is TdApi.TextEntityTypeBold -> {
                        result.append("*${TelegramMarkdownConverter.escapeForFormatting(entityText)}*")
                    }
                    
                    is TdApi.TextEntityTypeItalic -> {
                        result.append("_${TelegramMarkdownConverter.escapeForFormatting(entityText)}_")
                    }
                    
                    is TdApi.TextEntityTypeUnderline -> {
                        result.append("__${TelegramMarkdownConverter.escapeForFormatting(entityText)}__")
                    }
                    
                    is TdApi.TextEntityTypeStrikethrough -> {
                        result.append("~${TelegramMarkdownConverter.escapeForFormatting(entityText)}~")
                    }
                    
                    is TdApi.TextEntityTypeCode -> {
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("`$escapedCode`")
                    }
                    
                    is TdApi.TextEntityTypePre -> {
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("```\n$escapedCode\n```")
                    }
                    
                    is TdApi.TextEntityTypePreCode -> {
                        val preCode = entity.type as TdApi.TextEntityTypePreCode
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("```${preCode.language}\n$escapedCode\n```")
                    }
                    
                    is TdApi.TextEntityTypeSpoiler -> {
                        result.append("||${TelegramMarkdownConverter.escapeForFormatting(entityText)}||")
                    }
                    
                    is TdApi.TextEntityTypeBlockQuote -> {
                        val lines = entityText.split("\n")
                        val quotedLines = lines.map { line -> 
                            ">${TelegramMarkdownConverter.escapeForFormatting(line)}" 
                        }.joinToString("\n")
                        result.append(quotedLines)
                    }
                    
                    is TdApi.TextEntityTypeTextUrl -> {
                        val textUrl = entity.type as TdApi.TextEntityTypeTextUrl
                        val escapedText = TelegramMarkdownConverter.escapeForFormatting(entityText)
                        val escapedUrl = TelegramMarkdownConverter.escapeUrlInLink(textUrl.url)
                        result.append("[$escapedText]($escapedUrl)")
                    }
                    
                    is TdApi.TextEntityTypeUrl,
                    is TdApi.TextEntityTypeMention,
                    is TdApi.TextEntityTypeHashtag,
                    is TdApi.TextEntityTypeCashtag,
                    is TdApi.TextEntityTypeBotCommand,
                    is TdApi.TextEntityTypePhoneNumber,
                    is TdApi.TextEntityTypeEmailAddress -> {
                        result.append(TelegramMarkdownConverter.escapeMarkdownV2(entityText))
                    }
                    
                    is TdApi.TextEntityTypeMentionName -> {
                        val mentionName = entity.type as TdApi.TextEntityTypeMentionName
                        val escapedText = TelegramMarkdownConverter.escapeForFormatting(entityText)
                        result.append("[$escapedText](tg://user?id=${mentionName.userId})")
                    }
                    
                    else -> {
                        logger.debug { "Unknown entity type: ${entity.type.javaClass.simpleName}" }
                        result.append(TelegramMarkdownConverter.escapeMarkdownV2(entityText))
                    }
                }
                
                currentPos = entity.offset + entity.length
            }
            
            // Add remaining text
            if (currentPos < text.length) {
                result.append(TelegramMarkdownConverter.escapeMarkdownV2(text.substring(currentPos)))
            }
            
            val finalResult = result.toString()
            logger.debug { "Generated MarkdownV2 (${finalResult.length} chars)" }
            
            return finalResult
            
        } catch (e: Exception) {
            logger.warn(e) { "Error converting formatted text, using escaped plain text" }
            return TelegramMarkdownConverter.escapeMarkdownV2(formattedText.text)
        }
    }
    
    // Rest of the class methods unchanged...
    
    fun extractChannelTag(chat: TdApi.Chat, client: Client?): String? {
        return try {
            when (val chatType = chat.type) {
                is TdApi.ChatTypeSupergroup -> {
                    val deferred = CompletableDeferred<String?>()
                    
                    client?.send(TdApi.GetSupergroup(chatType.supergroupId)) { result ->
                        when (result) {
                            is TdApi.Supergroup -> {
                                val username = try {
                                    val usernameField = result.javaClass.getDeclaredField("username")
                                    usernameField.isAccessible = true
                                    val value = usernameField.get(result) as? String
                                    if (!value.isNullOrEmpty()) "@$value" else null
                                } catch (e: Exception) {
                                    logger.debug { "Could not extract username: ${e.message}" }
                                    null
                                }
                                deferred.complete(username)
                            }
                            is TdApi.Error -> {
                                logger.debug { "Could not get supergroup info: ${result.message}" }
                                deferred.complete(null)
                            }
                        }
                    }
                    
                    try {
                        runBlocking {
                            withTimeout(2000) { deferred.await() }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Timeout getting supergroup username" }
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "Error extracting channel tag: ${e.message}" }
            null
        }
    }
    
    private fun isMonitoredGroupChat(chatId: Long): Boolean {
        val channelId = getChannelIdentifier(chatId)
        return channelId != null && database.channelExists(channelId)
    }
    
    private fun getChannelIdentifier(chatId: Long): String? {
        return try {
            val identifier = chatId.toString()
            logger.debug { "Chat ID $chatId mapped to identifier: $identifier" }
            identifier
        } catch (e: Exception) {
            logger.error(e) { "Failed to get channel identifier for chat $chatId" }
            null
        }
    }
    
    private fun generateMessageLink(channelDetails: ChannelDetails?, tdlibMessageId: Long): String? {
        return try {
            if (channelDetails == null) return null
            
            val publicMessageId = convertTdlibToPublicMessageId(tdlibMessageId)
            if (publicMessageId == null) {
                logger.debug { "Could not convert TDLib message ID $tdlibMessageId to public ID" }
                return null
            }
            
            logger.debug { "Converted TDLib ID $tdlibMessageId to public ID $publicMessageId" }
            
            when {
                !channelDetails.channelTag.isNullOrBlank() -> {
                    val cleanTag = channelDetails.channelTag.removePrefix("@")
                    if (cleanTag.isNotEmpty()) {
                        "https://t.me/$cleanTag/$publicMessageId"
                    } else null
                }
                
                channelDetails.channelId.startsWith("-100") -> {
                    val chatId = channelDetails.channelId.substring(4)
                    "https://t.me/c/$chatId/$publicMessageId"
                }
                
                else -> null
            }
            
        } catch (e: Exception) {
            logger.debug { "Failed to generate message link: ${e.message}" }
            null
        }
    }
    
    private fun convertTdlibToPublicMessageId(tdlibMessageId: Long): Long? {
        val SHIFT_FACTOR = 1048576L // 2^20
        
        return if (tdlibMessageId % SHIFT_FACTOR == 0L) {
            val publicMessageId = tdlibMessageId / SHIFT_FACTOR
            if (publicMessageId > 0) publicMessageId else null
        } else {
            null
        }
    }
    
    fun cacheChannelMapping(channelId: String, chatId: Long) {
        channelIdCache[channelId] = chatId
    }
    
    fun getCachedChatId(channelId: String): Long? {
        return channelIdCache[channelId]
    }
    
    fun cleanupCache() {
        if (channelIdCache.size > 400) {
            logger.debug { "Cleaning up channel ID cache (${channelIdCache.size} entries)" }
        }
    }
    
    fun shutdown() {
        logger.info { "Shutting down ChannelMonitor..." }
        scope.cancel()
    }
}
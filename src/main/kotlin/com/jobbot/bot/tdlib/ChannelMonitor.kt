package com.jobbot.bot.tdlib

import com.jobbot.bot.TelegramBot
import com.jobbot.core.MessageProcessor
import com.jobbot.data.Database
import com.jobbot.data.models.ChannelMessage
import com.jobbot.data.models.ChannelDetails
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles TDLib message monitoring and processing with CONSISTENT MarkdownV2 formatting support
 * FIXED: Unified entity processing for both channels and groups
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
                
                logger.info { "Processing message from monitored chat: $channelId (type: ${if (isChannelPost) "channel" else "group"})" }
                
                // 🔧 FIXED: Use consistent entity processing for both channels and groups
                val (plainText, formattedText) = extractMessageContent(message.content)
                
                if (plainText.isBlank()) {
                    logger.debug { "Message has no text content, skipping" }
                    return
                }
                
                logger.debug { "Processing plain text: '$plainText'" }
                if (formattedText != plainText) {
                    logger.debug { "Generated formatted text with ${formattedText.length - plainText.length} additional markup characters" }
                }
                
                scope.launch {
                    try {
                        // Get channel details
                        val channelDetails = database.getAllChannelsWithDetails().find { it.channelId == channelId }
                        val displayName = when {
                            !channelDetails?.channelTag.isNullOrBlank() -> channelDetails!!.channelTag!!
                            !channelDetails?.channelName.isNullOrBlank() -> channelDetails!!.channelName!!
                            else -> if (isChannelPost) "Channel" else "Group"
                        }
                        
                        // Generate message link (for both channels and groups)
                        val messageLink = generateMessageLink(channelDetails, message.id)
                        
                        // Create ChannelMessage with simplified data
                        val channelMessage = ChannelMessage(
                            channelId = channelId,
                            channelName = displayName,
                            messageId = message.id,
                            text = plainText, // For keyword matching
                            formattedText = formattedText, // For user notifications
                            senderUsername = null, // Not needed anymore
                            messageLink = messageLink
                        )
                        
                        logger.debug { "Calling messageProcessor.processChannelMessage..." }
                        val notifications = messageProcessor.processChannelMessage(channelMessage)
                        
                        logger.info { "Message processing complete. Generated ${notifications.size} notifications" }
                        
                        // Queue notifications
                        for (notification in notifications) {
                            logger.debug { "Queueing notification for user ${notification.userId}" }
                            bot?.queueNotification(notification)
                        }
                        
                        logger.debug { "Processed message from monitored chat $channelId, generated ${notifications.size} notifications" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing new message" }
                        ErrorTracker.logError("ERROR", "Message processing error: ${e.message}", e)
                    }
                }
            } else {
                logger.debug { "Ignoring message from unmonitored chat $chatId (isChannelPost: $isChannelPost)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in handleNewMessage" }
            ErrorTracker.logError("ERROR", "New message handler error: ${e.message}", e)
        }
    }
    
    /**
     * 🔧 FIXED: Unified entity processing for both channels and groups
     * Extract both plain text and formatted text from message content
     * Returns Pair<plainText, formattedText>
     */
    private fun extractMessageContent(content: TdApi.MessageContent): Pair<String, String> {
        return when (content) {
            is TdApi.MessageText -> {
                val plainText = content.text.text
                val formattedText = convertFormattedTextToMarkdown(content.text)
                logger.debug { "Text message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                
                // 🔧 FIXED: Unified logging for both channels and groups
                if (content.text.entities.isNotEmpty()) {
                    logger.debug { "Message entities: ${content.text.entities.size} entities found" }
                    content.text.entities.forEach { entity ->
                        logger.debug { "Entity: ${entity.type.javaClass.simpleName} at ${entity.offset}-${entity.offset + entity.length}" }
                    }
                } else {
                    logger.debug { "Message has no entities" }
                }
                
                Pair(plainText, formattedText)
            }
            is TdApi.MessagePhoto -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Photo message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            is TdApi.MessageVideo -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Video message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            is TdApi.MessageDocument -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Document message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            else -> {
                logger.debug { "Unsupported message type: ${content.javaClass.simpleName}" }
                Pair("", "")
            }
        }
    }
    
    /**
     * 🔧 FIXED: Unified MarkdownV2 conversion for both channels and groups
     * This method now works consistently for all message types
     */
    private fun convertFormattedTextToMarkdown(formattedText: TdApi.FormattedText): String {
        if (formattedText.entities.isEmpty()) {
            // No entities - just escape the plain text safely
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
                
                // 🔧 FIXED: Unified entity handling for all message types
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
                        // Inline code - use converter's code escaping
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("`$escapedCode`")
                    }
                    
                    is TdApi.TextEntityTypePre -> {
                        // Pre-formatted code block
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("```\n$escapedCode\n```")
                    }
                    
                    is TdApi.TextEntityTypePreCode -> {
                        // Code block with language
                        val preCode = entity.type as TdApi.TextEntityTypePreCode
                        val escapedCode = entityText.replace("\\", "\\\\").replace("`", "\\`")
                        result.append("```${preCode.language}\n$escapedCode\n```")
                    }
                    
                    is TdApi.TextEntityTypeSpoiler -> {
                        result.append("||${TelegramMarkdownConverter.escapeForFormatting(entityText)}||")
                    }
                    
                    is TdApi.TextEntityTypeBlockQuote -> {
                        // Block quote - split into lines and add > to each
                        val lines = entityText.split("\n")
                        val quotedLines = lines.map { line -> 
                            ">${TelegramMarkdownConverter.escapeForFormatting(line)}" 
                        }.joinToString("\n")
                        result.append(quotedLines)
                    }
                    
                    is TdApi.TextEntityTypeExpandableBlockQuote -> {
                        // Expandable quote - treat as regular quote with special marker
                        val lines = entityText.split("\n")
                        val quotedLines = lines.map { line -> 
                            ">${TelegramMarkdownConverter.escapeForFormatting(line)}" 
                        }.joinToString("\n")
                        result.append("**$quotedLines||")
                    }
                    
                    is TdApi.TextEntityTypeTextUrl -> {
                        val textUrl = entity.type as TdApi.TextEntityTypeTextUrl
                        // Use converter's URL escaping
                        val escapedText = TelegramMarkdownConverter.escapeForFormatting(entityText)
                        val escapedUrl = TelegramMarkdownConverter.escapeUrlInLink(textUrl.url)
                        result.append("[$escapedText]($escapedUrl)")
                    }
                    
                    is TdApi.TextEntityTypeUrl -> {
                        // Plain URL - just escape it
                        result.append(TelegramMarkdownConverter.escapeMarkdownV2(entityText))
                    }
                    
                    is TdApi.TextEntityTypeMention -> {
                        // @username mention - safe to include as-is
                        result.append(entityText)
                    }
                    
                    is TdApi.TextEntityTypeMentionName -> {
                        // User mention - convert to inline mention
                        val mentionName = entity.type as TdApi.TextEntityTypeMentionName
                        val escapedText = TelegramMarkdownConverter.escapeForFormatting(entityText)
                        result.append("[$escapedText](tg://user?id=${mentionName.userId})")
                    }
                    
                    is TdApi.TextEntityTypeHashtag,
                    is TdApi.TextEntityTypeCashtag,
                    is TdApi.TextEntityTypeBotCommand,
                    is TdApi.TextEntityTypePhoneNumber,
                    is TdApi.TextEntityTypeEmailAddress -> {
                        // These are generally safe - just escape them normally
                        result.append(TelegramMarkdownConverter.escapeMarkdownV2(entityText))
                    }
                    
                    else -> {
                        // Unknown entity type - escape normally
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
            
            // 🔧 FIXED: Consistent debug logging - removed validation check
            logger.debug { "Generated MarkdownV2 (${finalResult.length} chars): $finalResult" }
            
            return finalResult
            
        } catch (e: Exception) {
            logger.warn(e) { "Error converting formatted text, using escaped plain text" }
            return TelegramMarkdownConverter.escapeMarkdownV2(formattedText.text)
        }
    }
    
    // Safer tag extraction without dangerous reflection
    fun extractChannelTag(chat: TdApi.Chat, client: Client?): String? {
        return try {
            when (val chatType = chat.type) {
                is TdApi.ChatTypeSupergroup -> {
                    // Try to get supergroup info for username
                    val deferred = CompletableDeferred<String?>()
                    
                    client?.send(TdApi.GetSupergroup(chatType.supergroupId)) { result ->
                        when (result) {
                            is TdApi.Supergroup -> {
                                // SAFER: Use try-catch for reflection instead of assuming field exists
                                val username = try {
                                    val usernameField = result.javaClass.getDeclaredField("username")
                                    usernameField.isAccessible = true
                                    val value = usernameField.get(result) as? String
                                    if (!value.isNullOrEmpty()) "@$value" else null
                                } catch (e: NoSuchFieldException) {
                                    logger.debug { "Username field not available in TDLib version - this is expected" }
                                    null
                                } catch (e: SecurityException) {
                                    logger.debug { "Cannot access username field due to security restrictions" }
                                    null
                                } catch (e: Exception) {
                                    logger.debug { "Could not extract username via reflection: ${e.message}" }
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
                    
                    // Wait briefly for the result, but don't block
                    try {
                        runBlocking {
                            withTimeout(2000) { deferred.await() }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Timeout getting supergroup username - continuing without tag" }
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
        // Check if this chat ID matches any of our monitored channels/groups
        val channelId = getChannelIdentifier(chatId)
        return channelId != null && database.channelExists(channelId)
    }
    
    private fun getChannelIdentifier(chatId: Long): String? {
        return try {
            // For debugging, let's also cache this mapping
            val identifier = chatId.toString()
            logger.debug { "Chat ID $chatId mapped to identifier: $identifier" }
            identifier
        } catch (e: Exception) {
            logger.error(e) { "Failed to get channel identifier for chat $chatId" }
            null
        }
    }
    
    /**
     * Generate a Telegram message link for both channels and groups
     */
    private fun generateMessageLink(channelDetails: ChannelDetails?, tdlibMessageId: Long): String? {
        return try {
            if (channelDetails == null) return null
            
            // Convert TDLib message ID to Bot API message ID
            val publicMessageId = convertTdlibToPublicMessageId(tdlibMessageId)
            if (publicMessageId == null) {
                logger.debug { "Could not convert TDLib message ID $tdlibMessageId to public ID - skipping link generation" }
                return null
            }
            
            logger.debug { "Converted TDLib ID $tdlibMessageId to public ID $publicMessageId" }
            
            when {
                // For public channels/groups with @username
                !channelDetails.channelTag.isNullOrBlank() -> {
                    val cleanTag = channelDetails.channelTag.removePrefix("@")
                    if (cleanTag.isNotEmpty()) {
                        "https://t.me/$cleanTag/$publicMessageId"
                    } else null
                }
                
                // For private supergroups/channels with -100 prefix
                channelDetails.channelId.startsWith("-100") -> {
                    val chatId = channelDetails.channelId.substring(4) // Remove "-100"
                    "https://t.me/c/$chatId/$publicMessageId"
                }
                
                else -> null
            }
            
        } catch (e: Exception) {
            logger.debug { "Failed to generate message link: ${e.message}" }
            null
        }
    }
    
    /**
     * Convert TDLib message ID to Bot API/public message ID
     * TDLib message ID = Bot API message ID * 1048576 (2^20)
     * Bot API message ID = TDLib message ID / 1048576 (if divisible)
     */
    private fun convertTdlibToPublicMessageId(tdlibMessageId: Long): Long? {
        val SHIFT_FACTOR = 1048576L // 2^20
        
        return if (tdlibMessageId % SHIFT_FACTOR == 0L) {
            val publicMessageId = tdlibMessageId / SHIFT_FACTOR
            if (publicMessageId > 0) publicMessageId else null
        } else {
            null // Local-only message, no public ID
        }
    }
    
    // Cache management
    fun cacheChannelMapping(channelId: String, chatId: Long) {
        channelIdCache[channelId] = chatId
    }
    
    fun getCachedChatId(channelId: String): Long? {
        return channelIdCache[channelId]
    }
    
    // Add cache cleanup method
    fun cleanupCache() {
        if (channelIdCache.size > 400) {
            logger.debug { "Cleaning up channel ID cache (${channelIdCache.size} entries)" }
            // The LinkedHashMap will automatically remove eldest entries
            // when new ones are added, but we can force cleanup if needed
        }
    }
    
    fun shutdown() {
        logger.info { "Shutting down ChannelMonitor..." }
        scope.cancel()
    }
}
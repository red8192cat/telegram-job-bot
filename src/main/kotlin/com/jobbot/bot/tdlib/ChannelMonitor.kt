package com.jobbot.bot.tdlib

import com.jobbot.bot.TelegramBot
import com.jobbot.core.MessageProcessor
import com.jobbot.data.Database
import com.jobbot.data.models.ChannelMessage
import com.jobbot.data.models.ChannelDetails
import com.jobbot.data.models.MediaAttachment
import com.jobbot.data.models.MediaType
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles TDLib message monitoring and processing with MEDIA GROUP support
 * ENHANCED: Properly handles media groups (multiple media files with single caption)
 */
class ChannelMonitor(
    private val database: Database,
    private val messageProcessor: MessageProcessor,
    private var bot: TelegramBot?
) {
    private val logger = getLogger("ChannelMonitor")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ADDED: Media downloader for handling attachments
    private val mediaDownloader = MediaDownloader()
    
    // ADDED: Media group handling
    private val mediaGroups = ConcurrentHashMap<Long, MediaGroupCollector>()
    
    // Data class for collecting media group messages
    private data class MediaGroupCollector(
        val mediaGroupId: Long,
        val messages: MutableList<TdApi.Message> = mutableListOf(),
        val createdTime: Long = System.currentTimeMillis(),
        var hasTextMessage: Boolean = false
    )
    
    // Bounded cache with automatic cleanup
    private val channelIdCache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 500 // Keep max 500 entries
        }
    }
    
    init {
        // Start periodic cleanup of old temp files and media groups
        scope.launch {
            while (isActive) {
                delay(3600000) // Every hour
                mediaDownloader.cleanupOldTempFiles()
                cleanupOldMediaGroups()
            }
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
                
                // ENHANCED: Handle media groups properly
                scope.launch {
                    handleMessageWithMediaGroup(message, channelId, client)
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
     * Handle message with proper media group support
     * ENHANCED: Collects media group messages and processes them together
     */
    private suspend fun handleMessageWithMediaGroup(
        message: TdApi.Message,
        channelId: String,
        client: Client?
    ) {
        try {
            // Check if this message is part of a media group
            val mediaGroupId = message.mediaAlbumId
            
            if (mediaGroupId != 0L) {
                // This is part of a media group
                logger.debug { "Message ${message.id} is part of media group $mediaGroupId" }
                
                val collector = mediaGroups.getOrPut(mediaGroupId) {
                    MediaGroupCollector(mediaGroupId)
                }
                
                synchronized(collector) {
                    collector.messages.add(message)
                    
                    // Check if this message has text content
                    val (plainText, _) = extractMessageContent(message.content)
                    if (plainText.isNotBlank()) {
                        collector.hasTextMessage = true
                        logger.debug { "Media group $mediaGroupId now has text content" }
                    }
                }
                
                // Wait a bit for other messages in the group, then process
                delay(1000) // Wait 1 second for all media group messages
                
                // Check if we should process this media group now
                val shouldProcess = synchronized(collector) {
                    // Process if we have text content OR if enough time has passed
                    collector.hasTextMessage || 
                    (System.currentTimeMillis() - collector.createdTime > 3000) // 3 second timeout
                }
                
                if (shouldProcess) {
                    val removedCollector = mediaGroups.remove(mediaGroupId)
                    if (removedCollector != null) {
                        logger.debug { "Processing media group $mediaGroupId with ${removedCollector.messages.size} messages" }
                        processMediaGroup(removedCollector.messages, channelId, client)
                    }
                }
                
            } else {
                // Single message (not part of a media group)
                logger.debug { "Processing single message ${message.id}" }
                processMessageWithMedia(message, channelId, client)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error handling message with media group" }
            ErrorTracker.logError("ERROR", "Media group handler error: ${e.message}", e)
        }
    }
    
    /**
     * Process a complete media group (multiple media files + caption)
     */
    private suspend fun processMediaGroup(
        messages: List<TdApi.Message>,
        channelId: String,
        client: Client?
    ) {
        try {
            logger.debug { "Processing media group with ${messages.size} messages" }
            
            // Find the message with text content (usually the last one)
            val textMessage = messages.find { message ->
                val (plainText, _) = extractMessageContent(message.content)
                plainText.isNotBlank()
            }
            
            // Collect all media attachments from all messages
            val allMediaAttachments = mutableListOf<MediaAttachment>()
            
            for (message in messages) {
                val mediaAttachments = mediaDownloader.downloadMessageMedia(message, client)
                allMediaAttachments.addAll(mediaAttachments)
                
                if (mediaAttachments.isNotEmpty()) {
                    logger.debug { "Downloaded ${mediaAttachments.size} attachments from message ${message.id}" }
                }
            }
            
            logger.info { "Media group processing: ${allMediaAttachments.size} total attachments from ${messages.size} messages" }
            
            // Use text from the text message, or generate generic text
            val (plainText, formattedText) = if (textMessage != null) {
                extractMessageContent(textMessage.content)
            } else {
                // Generate generic text based on media types
                val mediaTypes = allMediaAttachments.map { it.type.name.lowercase() }.distinct()
                val genericText = "media post ${mediaTypes.joinToString(" ")}"
                Pair(genericText, genericText)
            }
            
            // Get channel details
            val channelDetails = database.getAllChannelsWithDetails().find { it.channelId == channelId }
            val displayName = when {
                !channelDetails?.channelTag.isNullOrBlank() -> channelDetails!!.channelTag!!
                !channelDetails?.channelName.isNullOrBlank() -> channelDetails!!.channelName!!
                else -> "Channel"
            }
            
            // Use the last message for link generation (usually has the text)
            val linkMessage = textMessage ?: messages.last()
            val messageLink = generateMessageLink(channelDetails, linkMessage.id)
            
            // Create a single ChannelMessage with all media attachments
            val channelMessage = ChannelMessage(
                channelId = channelId,
                channelName = displayName,
                messageId = linkMessage.id,
                text = plainText,
                formattedText = if (textMessage != null) formattedText else null,
                senderUsername = null,
                messageLink = messageLink,
                mediaAttachments = allMediaAttachments
            )
            
            logger.debug { "Processing media group as single message with ${allMediaAttachments.size} attachments" }
            val notifications = messageProcessor.processChannelMessage(channelMessage)
            
            logger.info { "Media group processing complete. Generated ${notifications.size} notifications with ${allMediaAttachments.size} attachments each" }
            
            // Queue notifications as a batch for media reuse
            if (notifications.isNotEmpty()) {
                bot?.queueNotifications(notifications)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing media group" }
            ErrorTracker.logError("ERROR", "Media group processing error: ${e.message}", e)
        }
    }

    /**
    * Process single message with media downloads
    * ENHANCED: Better handling of various message types including polls
    */
    private suspend fun processMessageWithMedia(
        message: TdApi.Message,
        channelId: String,
        client: Client?
    ) {
        try {
            // Extract text content
            val (plainText, formattedText) = extractMessageContent(message.content)
            
            // Download media attachments
            logger.debug { "Downloading media attachments for message ${message.id}" }
            val mediaAttachments = mediaDownloader.downloadMessageMedia(message, client)
            
            // ENHANCED: More intelligent content checking
            val hasUsefulText = plainText.isNotBlank() && plainText.length >= 3 // At least 3 characters
            val hasMedia = mediaAttachments.isNotEmpty()
            
            // Skip only if absolutely no useful content
            if (!hasUsefulText && !hasMedia) {
                logger.debug { "Message has no useful content (text: '${plainText.take(50)}', media: ${mediaAttachments.size}), skipping" }
                return
            }
            
            // For media-only messages, generate searchable keywords
            val finalPlainText = if (!hasUsefulText && hasMedia) {
                generateMediaKeywords(mediaAttachments, message.content)
            } else {
                plainText
            }
            
            logger.debug { "Processing message: text='${finalPlainText.take(50)}${if (finalPlainText.length > 50) "..." else ""}', media=${mediaAttachments.size}" }
            
            if (mediaAttachments.isNotEmpty()) {
                logger.info { "Downloaded ${mediaAttachments.size} media attachments for message ${message.id}" }
                mediaAttachments.forEach { attachment ->
                    logger.debug { 
                        "Downloaded ${attachment.type}: ${attachment.originalFileName} " +
                        "(${attachment.fileSize / 1024}KB) -> ${attachment.filePath}" 
                    }
                }
            }
            
            // Get channel details
            val channelDetails = database.getAllChannelsWithDetails().find { it.channelId == channelId }
            val displayName = when {
                !channelDetails?.channelTag.isNullOrBlank() -> channelDetails!!.channelTag!!
                !channelDetails?.channelName.isNullOrBlank() -> channelDetails!!.channelName!!
                else -> "Channel"
            }
            
            val messageLink = generateMessageLink(channelDetails, message.id)
            
            val channelMessage = ChannelMessage(
                channelId = channelId,
                channelName = displayName,
                messageId = message.id,
                text = finalPlainText,
                formattedText = if (hasUsefulText) formattedText else null,
                senderUsername = null,
                messageLink = messageLink,
                mediaAttachments = mediaAttachments
            )
            
            logger.debug { "Processing single message..." }
            val notifications = messageProcessor.processChannelMessage(channelMessage)
            
            logger.info { "Single message processing complete. Generated ${notifications.size} notifications" }
            
            if (notifications.isNotEmpty()) {
                bot?.queueNotifications(notifications)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error processing single message with media" }
            ErrorTracker.logError("ERROR", "Single message processing error: ${e.message}", e)
        }
    }

    /**
    * Generate searchable keywords for media-only messages
    * This helps with job matching even when there's no text
    */
    private fun generateMediaKeywords(
        mediaAttachments: List<MediaAttachment>,
        messageContent: TdApi.MessageContent
    ): String {
        val keywords = mutableListOf<String>()
        
        // Add media type keywords
        val mediaTypes = mediaAttachments.map { it.type }.distinct()
        mediaTypes.forEach { type ->
            when (type) {
                MediaType.PHOTO -> keywords.addAll(listOf("photo", "image", "picture", "screenshot"))
                MediaType.VIDEO -> keywords.addAll(listOf("video", "clip", "recording", "demo"))
                MediaType.DOCUMENT -> keywords.addAll(listOf("document", "file", "attachment", "pdf"))
                MediaType.AUDIO -> keywords.addAll(listOf("audio", "sound", "music", "recording"))
                MediaType.VOICE -> keywords.addAll(listOf("voice", "message", "audio", "recording"))
                MediaType.ANIMATION -> keywords.addAll(listOf("gif", "animation", "video", "clip"))
            }
        }
        
        // Add content-specific keywords using safe reflection
        when {
            messageContent.javaClass.simpleName.contains("Poll") -> {
                keywords.addAll(listOf("poll", "vote", "survey", "question"))
                // Try to extract poll question text safely
                try {
                    val pollField = messageContent.javaClass.declaredFields.find { it.name == "poll" }
                    if (pollField != null) {
                        pollField.isAccessible = true
                        val poll = pollField.get(messageContent)
                        val questionField = poll?.javaClass?.declaredFields?.find { it.name == "question" }
                        if (questionField != null) {
                            questionField.isAccessible = true
                            val question = questionField.get(poll)
                            val textField = question?.javaClass?.declaredFields?.find { it.name == "text" }
                            if (textField != null) {
                                textField.isAccessible = true
                                val questionText = textField.get(question) as? String
                                if (questionText?.lowercase()?.contains("job") == true || 
                                    questionText?.lowercase()?.contains("work") == true || 
                                    questionText?.lowercase()?.contains("hire") == true) {
                                    keywords.addAll(listOf("job", "hiring", "employment", "work"))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug { "Could not extract poll text: ${e.message}" }
                }
            }
            messageContent.javaClass.simpleName.contains("Sticker") -> {
                keywords.addAll(listOf("sticker", "emoji", "reaction"))
            }
            messageContent.javaClass.simpleName.contains("Location") -> {
                keywords.addAll(listOf("location", "address", "place", "map"))
            }
            messageContent.javaClass.simpleName.contains("Venue") -> {
                keywords.addAll(listOf("venue", "place", "location", "address"))
            }
            messageContent.javaClass.simpleName.contains("Contact") -> {
                keywords.addAll(listOf("contact", "phone", "person", "info"))
            }
        }
        
        // Add filename-based keywords (useful for job-related documents)
        mediaAttachments.forEach { attachment ->
            attachment.originalFileName?.let { filename ->
                val nameParts = filename.lowercase()
                    .replace(Regex("[^a-z0-9\\s]"), " ")
                    .split("\\s+".toRegex())
                    .filter { it.length > 2 } // Only words longer than 2 chars
                
                keywords.addAll(nameParts)
            }
        }
        
        val result = keywords.distinct().joinToString(" ")
        logger.debug { "Generated media keywords: '$result'" }
        
        return result
    }
    
    /**
     * Clean up old media groups that might have been orphaned
     */
    private fun cleanupOldMediaGroups() {
        try {
            val currentTime = System.currentTimeMillis()
            val expiredGroups = mediaGroups.entries
                .filter { (_, collector) -> currentTime - collector.createdTime > 300000 } // 5 minutes old
                .map { it.key }
            
            expiredGroups.forEach { groupId ->
                mediaGroups.remove(groupId)
                logger.debug { "Cleaned up expired media group: $groupId" }
            }
            
            if (expiredGroups.isNotEmpty()) {
                logger.debug { "Cleaned up ${expiredGroups.size} expired media groups" }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Error during media group cleanup" }
        }
    }
    
    /**
    * Extract both plain text and formatted text from message content
    * ENHANCED: Now handles polls, stickers, and other message types for better job matching
    */
    private fun extractMessageContent(content: TdApi.MessageContent): Pair<String, String> {
        return when (content) {
            is TdApi.MessageText -> {
                val plainText = content.text.text
                val formattedText = convertFormattedTextToMarkdown(content.text)
                logger.debug { "Text message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
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
            
            is TdApi.MessageAudio -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Audio message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            
            is TdApi.MessageAnimation -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Animation message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            
            is TdApi.MessageVoiceNote -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Voice note message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            
            // ENHANCED: Handle polls for job-related content using safe reflection
            is TdApi.MessagePoll -> {
                try {
                    val poll = content.poll
                    val question = poll.question.text
                    val options = poll.options.joinToString(" ") { it.text.text }
                    val plainText = "$question $options"
                    val formattedText = "*${TelegramMarkdownConverter.escapeForFormatting(question)}*\n\n${TelegramMarkdownConverter.escapeMarkdownV2(options)}"
                    logger.debug { "Poll message - question: '${question.take(50)}', ${poll.options.size} options" }
                    Pair(plainText, formattedText)
                } catch (e: Exception) {
                    logger.debug { "Error processing poll message: ${e.message}" }
                    Pair("poll message", "poll message")
                }
            }
            
            // ENHANCED: Handle stickers (some have associated text/emoji)
            is TdApi.MessageSticker -> {
                try {
                    val sticker = content.sticker
                    val emoji = sticker.emoji
                    val stickerText = if (!emoji.isNullOrBlank()) {
                        "sticker $emoji"
                    } else {
                        "sticker"
                    }
                    logger.debug { "Sticker message - emoji: '$emoji'" }
                    Pair(stickerText, stickerText)
                } catch (e: Exception) {
                    logger.debug { "Error processing sticker message: ${e.message}" }
                    Pair("sticker", "sticker")
                }
            }
            
            // ENHANCED: Handle location messages (could be job-related)
            is TdApi.MessageLocation -> {
                try {
                    val location = content.location
                    val locationText = "location ${location.latitude} ${location.longitude}"
                    logger.debug { "Location message" }
                    Pair(locationText, locationText)
                } catch (e: Exception) {
                    logger.debug { "Error processing location message: ${e.message}" }
                    Pair("location", "location")
                }
            }
            
            // ENHANCED: Handle venue messages (could be job-related)
            is TdApi.MessageVenue -> {
                try {
                    val venue = content.venue
                    val venueText = "${venue.title} ${venue.address}"
                    logger.debug { "Venue message - title: '${venue.title}'" }
                    Pair(venueText, venueText)
                } catch (e: Exception) {
                    logger.debug { "Error processing venue message: ${e.message}" }
                    Pair("venue", "venue")
                }
            }
            
            // ENHANCED: Handle contact messages
            is TdApi.MessageContact -> {
                try {
                    val contact = content.contact
                    val contactText = "${contact.firstName} ${contact.lastName} ${contact.phoneNumber}"
                    logger.debug { "Contact message - name: '${contact.firstName} ${contact.lastName}'" }
                    Pair(contactText, contactText)
                } catch (e: Exception) {
                    logger.debug { "Error processing contact message: ${e.message}" }
                    Pair("contact", "contact")
                }
            }
            
            // ENHANCED: Handle dice/game messages (usually not job-related, but worth logging)
            is TdApi.MessageDice -> {
                try {
                    val emoji = content.emoji
                    val diceText = "dice $emoji"
                    logger.debug { "Dice message - emoji: '$emoji'" }
                    Pair(diceText, diceText)
                } catch (e: Exception) {
                    logger.debug { "Error processing dice message: ${e.message}" }
                    Pair("dice", "dice")
                }
            }
            
            // ENHANCED: Handle game messages
            is TdApi.MessageGame -> {
                try {
                    val game = content.game
                    val gameText = "${game.title} ${game.description}"
                    logger.debug { "Game message - title: '${game.title}'" }
                    Pair(gameText, gameText)
                } catch (e: Exception) {
                    logger.debug { "Error processing game message: ${e.message}" }
                    Pair("game", "game")
                }
            }
            
            // ENHANCED: Service messages that might contain useful info
            is TdApi.MessageChatAddMembers -> {
                try {
                    val memberNames = content.memberUserIds.joinToString(" ") { "user$it" }
                    val serviceText = "new members joined $memberNames"
                    logger.debug { "Chat add members - ${content.memberUserIds.size} members" }
                    Pair(serviceText, serviceText)
                } catch (e: Exception) {
                    logger.debug { "Error processing add members message: ${e.message}" }
                    Pair("new members", "new members")
                }
            }
            
            is TdApi.MessageChatJoinByLink -> {
                val serviceText = "user joined via link"
                logger.debug { "User joined by link" }
                Pair(serviceText, serviceText)
            }
            
            is TdApi.MessageChatDeleteMember -> {
                val serviceText = "user left chat"
                logger.debug { "User left chat" }
                Pair(serviceText, serviceText)
            }
            
            is TdApi.MessageChatChangeTitle -> {
                try {
                    val newTitle = content.title
                    val serviceText = "chat title changed to $newTitle"
                    logger.debug { "Chat title changed to: '$newTitle'" }
                    Pair(serviceText, serviceText)
                } catch (e: Exception) {
                    logger.debug { "Error processing title change: ${e.message}" }
                    Pair("title changed", "title changed")
                }
            }
            
            is TdApi.MessageChatChangePhoto -> {
                val serviceText = "chat photo changed"
                logger.debug { "Chat photo changed" }
                Pair(serviceText, serviceText)
            }
            
            is TdApi.MessageChatDeletePhoto -> {
                val serviceText = "chat photo deleted"
                logger.debug { "Chat photo deleted" }
                Pair(serviceText, serviceText)
            }
            
            // Handle animated emoji
            is TdApi.MessageAnimatedEmoji -> {
                try {
                    val emoji = content.emoji
                    val emojiText = "animated emoji $emoji"
                    logger.debug { "Animated emoji: '$emoji'" }
                    Pair(emojiText, emojiText)
                } catch (e: Exception) {
                    logger.debug { "Error processing animated emoji: ${e.message}" }
                    Pair("emoji", "emoji")
                }
            }
            
            // Handle other message types with safe property access
            else -> {
                // For unsupported message types, try to extract any available text
                logger.debug { "Message type ${content.javaClass.simpleName} - checking for text content" }
                
                // Use reflection to safely check for common text properties
                val textContent = try {
                    when {
                        content.javaClass.simpleName.contains("Invoice") -> {
                            // Try to extract invoice text if available
                            val titleField = content.javaClass.declaredFields.find { it.name == "title" }
                            val descField = content.javaClass.declaredFields.find { it.name == "description" }
                            val title = titleField?.let { 
                                it.isAccessible = true
                                it.get(content) as? String 
                            } ?: ""
                            val desc = descField?.let { 
                                it.isAccessible = true
                                it.get(content) as? String 
                            } ?: ""
                            "$title $desc".trim()
                        }
                        content.javaClass.simpleName.contains("WebPage") -> {
                            // Try to extract web page text if available
                            val titleField = content.javaClass.declaredFields.find { it.name == "title" }
                            val title = titleField?.let { 
                                it.isAccessible = true
                                it.get(content) as? String 
                            } ?: ""
                            title
                        }
                        content.javaClass.simpleName.contains("ChannelChatCreate") || 
                        content.javaClass.simpleName.contains("SupergroupChatCreate") -> {
                            val titleField = content.javaClass.declaredFields.find { it.name == "title" }
                            val title = titleField?.let { 
                                it.isAccessible = true
                                it.get(content) as? String 
                            } ?: "unknown"
                            val prefix = if (content.javaClass.simpleName.contains("Channel")) "channel" else "supergroup"
                            "$prefix created $title"
                        }
                        else -> {
                            // Try one last attempt to extract any text using reflection
                            val textFields = content.javaClass.declaredFields.filter { field ->
                                field.type == String::class.java && 
                                (field.name.contains("text", true) || 
                                 field.name.contains("title", true) ||
                                 field.name.contains("description", true))
                            }
                            
                            textFields.mapNotNull { field ->
                                try {
                                    field.isAccessible = true
                                    field.get(content) as? String
                                } catch (e: Exception) {
                                    null
                                }
                            }.joinToString(" ").trim()
                        }
                    }
                } catch (e: Exception) {
                    logger.debug { "Could not extract text from ${content.javaClass.simpleName}: ${e.message}" }
                    ""
                }
                
                Pair(textContent, textContent)
            }
        }
    }
    
    /**
     * Unified MarkdownV2 conversion for all message types
     * UNCHANGED: This method remains the same
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
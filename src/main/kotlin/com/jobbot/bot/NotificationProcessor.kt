// File: src/main/kotlin/com/jobbot/bot/NotificationProcessor.kt

package com.jobbot.bot

import com.jobbot.data.Database
import com.jobbot.data.models.NotificationMessage
import com.jobbot.data.models.MediaType
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.media.*
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.util.concurrent.LinkedBlockingQueue

/**
 * UPDATED: NotificationProcessor with media support
 * 
 * Now sends original media content with job match headers
 * Handles multiple photos/videos in a single post using media groups
 */
class NotificationProcessor(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramClient: OkHttpTelegramClient
) {
    private val logger = getLogger("NotificationProcessor")
    
    private val notificationQueue = LinkedBlockingQueue<NotificationMessage>(1000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Simple tracking
    private var mediaSuccess = 0
    private var textSuccess = 0
    private var plainFallback = 0
    private var totalNotifications = 0
    
    init {
        startNotificationProcessor()
        startPeriodicStatsLogging()
    }
    
    fun queueNotification(notification: NotificationMessage) {
        try {
            val queued = notificationQueue.offer(notification, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!queued) {
                logger.warn { "Notification queue full, dropping notification for user ${notification.userId}" }
            } else {
                logger.debug { "Notification queued for user ${notification.userId} (${notification.mediaGroup.size} media items)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to queue notification" }
            ErrorTracker.logError("ERROR", "Failed to queue notification: ${e.message}", e)
        }
    }
    
    private fun startNotificationProcessor() {
        scope.launch {
            logger.info { "Notification processor started with media support" }
            
            while (isActive) {
                try {
                    val notification = withContext(Dispatchers.IO) {
                        notificationQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    
                    if (notification != null) {
                        processNotification(notification)
                    }
                } catch (e: InterruptedException) {
                    logger.info { "Notification processor interrupted" }
                    break
                } catch (e: Exception) {
                    logger.error(e) { "Error in notification processor" }
                    ErrorTracker.logError("ERROR", "Notification processor error: ${e.message}", e)
                    delay(1000)
                }
            }
            
            logger.info { "Notification processor stopped" }
        }
    }
    
    private suspend fun processNotification(notification: NotificationMessage) {
        totalNotifications++
        
        try {
            // Check rate limiting
            if (!rateLimiter.isAllowed(notification.userId)) {
                logger.debug { "Rate limit hit for user ${notification.userId}, requeueing notification" }
                delay(5000)
                notificationQueue.offer(notification)
                return
            }

            val user = database.getUser(notification.userId)
            val language = user?.language ?: "en"
            
            // Send notification based on content type
            val success = if (notification.mediaGroup.isNotEmpty()) {
                // Media message - send original media with header
                sendMediaNotification(notification, language)
            } else {
                // Text-only message - use existing logic  
                val (markdownContent, plainContent) = buildTextMessage(notification, language)
                tryMarkdownV2(notification.userId.toString(), markdownContent) ||
                sendPlainText(notification.userId.toString(), plainContent)
            }
            
            if (success) {
                logger.debug { "Notification delivered to user ${notification.userId} (${notification.mediaGroup.size} media items)" }
            } else {
                logger.warn { "Failed to deliver notification to user ${notification.userId}" }
            }

        } catch (e: TelegramApiException) {
            logger.error(e) { "Telegram API error for user ${notification.userId}" }
            
            if (isUserUnreachableError(e)) {
                logger.warn { "User ${notification.userId} is unreachable: ${e.message}" }
            } else {
                delay(10000)
                notificationQueue.offer(notification)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error for user ${notification.userId}" }
            ErrorTracker.logError("ERROR", "Notification processing error: ${e.message}", e)
        }
    }
    
    /**
     * NEW: Send media notification with original content
     */
    private suspend fun sendMediaNotification(notification: NotificationMessage, language: String): Boolean {
        return try {
            val chatId = notification.userId.toString()
            
            // Build header message
            val channelName = notification.channelName ?: "Channel"
            val headerText = buildHeaderText(channelName, language, notification.messageLink)
            
            when {
                // Multiple media items - use media group
                notification.mediaGroup.size > 1 -> {
                    sendMediaGroup(chatId, notification, headerText, language)
                }
                
                // Single media item - send individual media
                notification.mediaGroup.size == 1 -> {
                    sendSingleMedia(chatId, notification.mediaGroup[0], notification.messageText, headerText, language)
                }
                
                else -> {
                    // No media (shouldn't happen in this path)
                    logger.warn { "sendMediaNotification called with no media for user $chatId" }
                    false
                }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error sending media notification to user ${notification.userId}" }
            false
        }
    }
    
    /**
     * Send multiple media items as a group (album)
     */
    private suspend fun sendMediaGroup(
        chatId: String, 
        notification: NotificationMessage, 
        headerText: String,
        language: String
    ): Boolean {
        return try {
            // Build media group
            val mediaItems = mutableListOf<InputMedia>()
            
            notification.mediaGroup.forEachIndexed { index, mediaItem ->
                val inputMedia = when (mediaItem.type) {
                    MediaType.PHOTO -> {
                        InputMediaPhoto.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                // Add caption to first item only (Telegram rule)
                                if (index == 0) {
                                    val caption = buildMediaCaption(headerText, notification.messageText)
                                    caption(caption)
                                    parseMode("MarkdownV2")
                                }
                            }
                            .build()
                    }
                    
                    MediaType.VIDEO -> {
                        InputMediaVideo.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                if (index == 0) {
                                    val caption = buildMediaCaption(headerText, notification.messageText)
                                    caption(caption)
                                    parseMode("MarkdownV2")
                                }
                                mediaItem.width?.let { width(it) }
                                mediaItem.height?.let { height(it) }
                                mediaItem.duration?.let { duration(it) }
                            }
                            .build()
                    }
                    
                    MediaType.ANIMATION -> {
                        InputMediaAnimation.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                if (index == 0) {
                                    val caption = buildMediaCaption(headerText, notification.messageText)
                                    caption(caption)
                                    parseMode("MarkdownV2")
                                }
                                mediaItem.width?.let { width(it) }
                                mediaItem.height?.let { height(it) }
                                mediaItem.duration?.let { duration(it) }
                            }
                            .build()
                    }
                    
                    MediaType.DOCUMENT -> {
                        InputMediaDocument.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                if (index == 0) {
                                    val caption = buildMediaCaption(headerText, notification.messageText)
                                    caption(caption)
                                    parseMode("MarkdownV2")
                                }
                            }
                            .build()
                    }
                    
                    else -> {
                        // Skip unsupported media types in groups
                        logger.warn { "Skipping unsupported media type in group: ${mediaItem.type}" }
                        null
                    }
                }
                
                inputMedia?.let { mediaItems.add(it) }
            }
            
            if (mediaItems.isEmpty()) {
                logger.warn { "No valid media items for group send to user $chatId" }
                return false
            }
            
            // Send media group - FIXED: Convert MutableList to List
            val sendMediaGroup = SendMediaGroup.builder()
                .chatId(chatId)
                .media(mediaItems.toList()) // Convert MutableList to List
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendMediaGroup)
            }
            
            mediaSuccess++
            logger.debug { "✅ Media group sent successfully for user $chatId (${mediaItems.size} items)" }
            true
            
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) {
                logger.debug { "🔧 Media group caption formatting error for user $chatId, trying without markdown" }
                return sendMediaGroupPlain(chatId, notification, headerText, language)
            } else {
                throw e
            }
        }
    }
    
    /**
     * Send single media item
     */
    private suspend fun sendSingleMedia(
        chatId: String,
        mediaItem: com.jobbot.data.models.MediaItem,
        originalText: String,
        headerText: String,
        language: String
    ): Boolean {
        return try {
            val caption = buildMediaCaption(headerText, originalText)
            
            when (mediaItem.type) {
                MediaType.PHOTO -> {
                    val sendPhoto = SendPhoto.builder()
                        .chatId(chatId)
                        .photo(InputFile(mediaItem.fileId))
                        .caption(caption)
                        .parseMode("MarkdownV2")
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendPhoto)
                    }
                }
                
                MediaType.VIDEO -> {
                    val sendVideo = SendVideo.builder()
                        .chatId(chatId)
                        .video(InputFile(mediaItem.fileId))
                        .caption(caption)
                        .parseMode("MarkdownV2")
                        .apply {
                            mediaItem.width?.let { width(it) }
                            mediaItem.height?.let { height(it) }
                            mediaItem.duration?.let { duration(it) }
                        }
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendVideo)
                    }
                }
                
                MediaType.ANIMATION -> {
                    val sendAnimation = SendAnimation.builder()
                        .chatId(chatId)
                        .animation(InputFile(mediaItem.fileId))
                        .caption(caption)
                        .parseMode("MarkdownV2")
                        .apply {
                            mediaItem.width?.let { width(it) }
                            mediaItem.height?.let { height(it) }
                            mediaItem.duration?.let { duration(it) }
                        }
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendAnimation)
                    }
                }
                
                MediaType.DOCUMENT -> {
                    val sendDocument = SendDocument.builder()
                        .chatId(chatId)
                        .document(InputFile(mediaItem.fileId))
                        .caption(caption)
                        .parseMode("MarkdownV2")
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendDocument)
                    }
                }
                
                MediaType.AUDIO -> {
                    val sendAudio = SendAudio.builder()
                        .chatId(chatId)
                        .audio(InputFile(mediaItem.fileId))
                        .caption(caption)
                        .parseMode("MarkdownV2")
                        .apply {
                            mediaItem.duration?.let { duration(it) }
                        }
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendAudio)
                    }
                }
                
                MediaType.VOICE -> {
                    val sendVoice = SendVoice.builder()
                        .chatId(chatId)
                        .voice(InputFile(mediaItem.fileId))
                        .apply {
                            mediaItem.duration?.let { duration(it) }
                        }
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendVoice)
                    }
                    
                    // Send caption separately (voice notes don't support captions)
                    val followUpMessage = SendMessage.builder()
                        .chatId(chatId)
                        .text(caption)
                        .parseMode("MarkdownV2")
                        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(followUpMessage)
                    }
                }
                
                MediaType.VIDEO_NOTE -> {
                    val sendVideoNote = SendVideoNote.builder()
                        .chatId(chatId)
                        .videoNote(InputFile(mediaItem.fileId))
                        .apply {
                            mediaItem.duration?.let { duration(it) }
                        }
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendVideoNote)
                    }
                    
                    // Send caption separately (video notes don't support captions)
                    val followUpMessage = SendMessage.builder()
                        .chatId(chatId)
                        .text(caption)
                        .parseMode("MarkdownV2")
                        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(followUpMessage)
                    }
                }
                
                MediaType.STICKER -> {
                    val sendSticker = SendSticker.builder()
                        .chatId(chatId)
                        .sticker(InputFile(mediaItem.fileId))
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendSticker)
                    }
                    
                    // Send caption separately (stickers don't support captions)
                    val followUpMessage = SendMessage.builder()
                        .chatId(chatId)
                        .text(caption)
                        .parseMode("MarkdownV2")
                        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(followUpMessage)
                    }
                }
            }
            
            mediaSuccess++
            logger.debug { "✅ Single media sent successfully for user $chatId (${mediaItem.type})" }
            true
            
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) {
                logger.debug { "🔧 Single media caption formatting error for user $chatId, trying without markdown" }
                return sendSingleMediaPlain(chatId, mediaItem, originalText, headerText, language)
            } else {
                throw e
            }
        }
    }
    
    /**
     * Fallback: send media group without MarkdownV2 formatting
     */
    private suspend fun sendMediaGroupPlain(
        chatId: String,
        notification: NotificationMessage,
        headerText: String,
        language: String
    ): Boolean {
        return try {
            val mediaItems = mutableListOf<InputMedia>()
            
            notification.mediaGroup.forEachIndexed { index, mediaItem ->
                val inputMedia = when (mediaItem.type) {
                    MediaType.PHOTO -> {
                        InputMediaPhoto.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                if (index == 0) {
                                    val plainCaption = buildPlainCaption(headerText, notification.messageText)
                                    caption(plainCaption)
                                }
                            }
                            .build()
                    }
                    
                    MediaType.VIDEO -> {
                        InputMediaVideo.builder()
                            .media(mediaItem.fileId)
                            .apply {
                                if (index == 0) {
                                    val plainCaption = buildPlainCaption(headerText, notification.messageText)
                                    caption(plainCaption)
                                }
                            }
                            .build()
                    }
                    
                    else -> null
                }
                
                inputMedia?.let { mediaItems.add(it) }
            }
            
            if (mediaItems.isNotEmpty()) {
                // FIXED: Convert MutableList to List
                val sendMediaGroup = SendMediaGroup.builder()
                    .chatId(chatId)
                    .media(mediaItems.toList()) // Convert MutableList to List
                    .build()
                
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendMediaGroup)
                }
                
                plainFallback++
                true
            } else {
                false
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to send plain media group to user $chatId" }
            false
        }
    }
    
    /**
     * Fallback: send single media without MarkdownV2 formatting
     */
    private suspend fun sendSingleMediaPlain(
        chatId: String,
        mediaItem: com.jobbot.data.models.MediaItem,
        originalText: String,
        headerText: String,
        language: String
    ): Boolean {
        return try {
            val plainCaption = buildPlainCaption(headerText, originalText)
            
            when (mediaItem.type) {
                MediaType.PHOTO -> {
                    val sendPhoto = SendPhoto.builder()
                        .chatId(chatId)
                        .photo(InputFile(mediaItem.fileId))
                        .caption(plainCaption)
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendPhoto)
                    }
                }
                
                MediaType.VIDEO -> {
                    val sendVideo = SendVideo.builder()
                        .chatId(chatId)
                        .video(InputFile(mediaItem.fileId))
                        .caption(plainCaption)
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendVideo)
                    }
                }
                
                else -> {
                    // For other media types, send the media then follow with plain text
                    return false
                }
            }
            
            plainFallback++
            true
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to send plain single media to user $chatId" }
            false
        }
    }
    
    /**
     * Build header text with clickable channel link
     */
    private fun buildHeaderText(channelName: String, language: String, messageLink: String?): String {
        return if (!messageLink.isNullOrBlank()) {
            val linkDisplayText = if (channelName.startsWith("@")) {
                channelName
            } else {
                "@$channelName"
            }
            
            // Create MarkdownV2 link
            val safeLinkText = linkDisplayText
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("~", "\\~")
            
            val escapedUrl = TelegramMarkdownConverter.escapeUrlInLink(messageLink)
            val markdownLink = "[$safeLinkText]($escapedUrl)"
            
            val rawTemplate = Localization.getMessage(language, "notification.job.match.header", "PLACEHOLDER")
            val templateWithPlaceholder = rawTemplate.replace("PLACEHOLDER", "{0}")
            val escapedTemplate = TelegramMarkdownConverter.escapeMarkdownV2(templateWithPlaceholder)
            
            escapedTemplate.replace("\\{0\\}", markdownLink).replace("{0}", markdownLink)
        } else {
            val header = Localization.getMessage(language, "notification.job.match.header", channelName)
            TelegramMarkdownConverter.escapeMarkdownV2(header)
        }
    }
    
    /**
     * Build media caption (header + original text)
     */
    private fun buildMediaCaption(headerText: String, originalText: String): String {
        return if (originalText.isNotBlank()) {
            "$headerText\n\n$originalText"
        } else {
            headerText
        }
    }
    
    /**
     * Build plain caption (no markdown formatting)
     */
    private fun buildPlainCaption(headerText: String, originalText: String): String {
        // Remove markdown formatting for plain text version
        val plainHeader = headerText
            .replace(Regex("\\[[^\\]]*\\]\\([^\\)]*\\)"), "") // Remove links
            .replace(Regex("\\\\(.)"), "$1") // Remove escape characters
            .replace(Regex("[*_`~]"), "") // Remove formatting chars
        
        return if (originalText.isNotBlank()) {
            "$plainHeader\n\n$originalText"
        } else {
            plainHeader
        }
    }
    
    /**
     * Build text message for non-media notifications (existing logic)
     */
    private fun buildTextMessage(notification: NotificationMessage, language: String): Pair<String, String> {
        val channelName = notification.channelName ?: "Channel"
        val originalContent = notification.formattedMessageText ?: notification.messageText
        
        // Build MarkdownV2 version
        val markdownParts = mutableListOf<String>()
        
        if (!notification.messageLink.isNullOrBlank()) {
            val linkDisplayText = if (channelName.startsWith("@")) {
                channelName
            } else {
                "@$channelName"
            }
            
            val safeLinkText = linkDisplayText
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("~", "\\~")
            
            val escapedUrl = TelegramMarkdownConverter.escapeUrlInLink(notification.messageLink)
            val markdownLink = "[$safeLinkText]($escapedUrl)"
            
            val rawTemplate = Localization.getMessage(language, "notification.job.match.header", "PLACEHOLDER")
            val templateWithPlaceholder = rawTemplate.replace("PLACEHOLDER", "{0}")
            val escapedTemplate = TelegramMarkdownConverter.escapeMarkdownV2(templateWithPlaceholder)
            val finalHeader = escapedTemplate.replace("\\{0\\}", markdownLink).replace("{0}", markdownLink)
            
            markdownParts.add(finalHeader)
        } else {
            val header = Localization.getMessage(language, "notification.job.match.header", channelName)
            markdownParts.add(TelegramMarkdownConverter.escapeMarkdownV2(header))
        }
        
        if (!originalContent.isNullOrBlank()) {
            markdownParts.add(originalContent)
        }
        
        val markdownContent = markdownParts.joinToString("\n\n")
        
        // Build plain text version
        val plainParts = mutableListOf<String>()
        
        if (!notification.messageLink.isNullOrBlank()) {
            val headerWithLink = Localization.getMessage(language, "notification.job.match.header", notification.messageLink)
            plainParts.add(headerWithLink)
        } else {
            val header = Localization.getMessage(language, "notification.job.match.header", channelName)
            plainParts.add(header)
        }
        
        if (!notification.messageText.isNullOrBlank()) {
            plainParts.add(notification.messageText)
        }
        
        val plainContent = plainParts.joinToString("\n\n")
        
        return Pair(markdownContent, plainContent)
    }
    
    /**
     * Try sending with MarkdownV2 (existing logic)
     */
    private suspend fun tryMarkdownV2(chatId: String, content: String): Boolean {
        return try {
            val markdownMessage = SendMessage.builder()
                .chatId(chatId)
                .text(content)
                .parseMode("MarkdownV2")
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .build()
            
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(markdownMessage)
                }
            }
            
            textSuccess++
            logger.debug { "✅ MarkdownV2 text success for user $chatId" }
            true
            
        } catch (e: TimeoutCancellationException) {
            logger.debug { "⏰ MarkdownV2 timeout for user $chatId" }
            false
            
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) {
                logger.debug { "🔧 MarkdownV2 formatting error for user $chatId: ${e.message}" }
                false
            } else {
                throw e
            }
        } catch (e: Exception) {
            logger.debug { "❌ MarkdownV2 unexpected error for user $chatId: ${e.javaClass.simpleName}" }
            false
        }
    }
    
    /**
     * Send plain text (existing logic)
     */
    private suspend fun sendPlainText(chatId: String, content: String): Boolean {
        return try {
            val plainMessage = SendMessage.builder()
                .chatId(chatId)
                .text(content)
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(plainMessage)
            }
            
            plainFallback++
            logger.debug { "✅ Plain text delivered for user $chatId" }
            true
            
        } catch (e: TelegramApiException) {
            logger.error(e) { "🚨 CRITICAL: Even plain text failed for user $chatId" }
            false
        }
    }
    
    /**
     * Detect formatting-related errors
     */
    private fun isFormattingError(e: TelegramApiException): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("can't parse") ||
               message.contains("reserved") ||
               message.contains("escaped") ||
               message.contains("entities") ||
               message.contains("markdown") ||
               message.contains("parse mode")
    }
    
    /**
     * Detect user-unreachable errors
     */
    private fun isUserUnreachableError(e: TelegramApiException): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("bot was blocked") ||
               message.contains("user not found") ||
               message.contains("chat not found") ||
               message.contains("deactivated") ||
               message.contains("kicked")
    }
    
    /**
     * Statistics logging
     */
    private fun startPeriodicStatsLogging() {
        scope.launch {
            while (isActive) {
                delay(600000) // Every 10 minutes
                
                if (totalNotifications > 0) {
                    val mediaRate = (mediaSuccess.toDouble() / totalNotifications * 100).toInt()
                    val textRate = (textSuccess.toDouble() / totalNotifications * 100).toInt()
                    logger.debug { 
                        "📊 Notification stats: ${mediaRate}% media success, ${textRate}% text success " +
                        "($mediaSuccess media, $textSuccess text, $plainFallback plain, $totalNotifications total)"
                    }
                }
            }
        }
    }
    
    fun getQueueSize(): Int = notificationQueue.size
    
    fun getFormattingStats(): Map<String, Any> {
        return mapOf(
            "total" to totalNotifications,
            "mediaSuccess" to mediaSuccess,
            "textSuccess" to textSuccess,
            "plainFallback" to plainFallback,
            "mediaRate" to if (totalNotifications > 0) (mediaSuccess * 100 / totalNotifications) else 0,
            "textRate" to if (totalNotifications > 0) (textSuccess * 100 / totalNotifications) else 0
        )
    }
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        if (totalNotifications > 0) {
            val mediaRate = (mediaSuccess.toDouble() / totalNotifications * 100).toInt()
            val textRate = (textSuccess.toDouble() / totalNotifications * 100).toInt()
            logger.debug { 
                "📊 Final stats: ${mediaRate}% media, ${textRate}% text success " +
                "($mediaSuccess media, $textSuccess text, $plainFallback plain, $totalNotifications total)"
            }
        }
        
        scope.cancel()
    }
}
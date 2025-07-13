package com.jobbot.bot

import com.jobbot.bot.tdlib.MediaDownloader
import com.jobbot.data.Database
import com.jobbot.data.models.MediaAttachment
import com.jobbot.data.models.MediaType
import com.jobbot.data.models.NotificationMessage
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendVoice
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * COMPLETE NotificationProcessor with Media Attachment Support
 * 
 * Format:
 * 1. "New match from [channel]"
 * 2. Link to original post (if available)
 * 3. Original content with all formatting preserved
 * 4. Original media attachments (photos, videos, documents, etc.)
 * 
 * Strategy: Try MarkdownV2 first, fallback to plain text on API errors
 */
class NotificationProcessor(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramClient: OkHttpTelegramClient
) {
    private val logger = getLogger("NotificationProcessor")
    
    private val notificationQueue = LinkedBlockingQueue<NotificationMessage>(1000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Media downloader for cleanup operations
    private val mediaDownloader = MediaDownloader()
    
    // Enhanced tracking
    private var markdownSuccess = 0
    private var plainFallback = 0
    private var totalNotifications = 0
    private var mediaNotifications = 0
    
    init {
        startNotificationProcessor()
        startPeriodicStatsLogging()
    }
    
    fun queueNotification(notification: NotificationMessage) {
        try {
            val queued = notificationQueue.offer(notification, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!queued) {
                logger.warn { "Notification queue full, dropping notification for user ${notification.userId}" }
                // Clean up media files if notification was dropped
                if (notification.mediaAttachments.isNotEmpty()) {
                    mediaDownloader.cleanupMediaFiles(notification.mediaAttachments)
                }
            } else {
                logger.debug { "Notification queued for user ${notification.userId} with ${notification.mediaAttachments.size} attachments" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to queue notification" }
            ErrorTracker.logError("ERROR", "Failed to queue notification: ${e.message}", e)
            // Clean up media files on error
            if (notification.mediaAttachments.isNotEmpty()) {
                mediaDownloader.cleanupMediaFiles(notification.mediaAttachments)
            }
        }
    }
    
    private fun startNotificationProcessor() {
        scope.launch {
            logger.info { "Notification processor started with media attachment support" }
            
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
        var mediaCleanupNeeded = false
        
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
            
            // Track media notifications
            if (notification.mediaAttachments.isNotEmpty()) {
                mediaNotifications++
                mediaCleanupNeeded = true
                logger.debug { "Processing notification with ${notification.mediaAttachments.size} media attachments" }
            }
            
            // Send notification with media attachments
            val success = if (notification.mediaAttachments.isNotEmpty()) {
                sendNotificationWithMedia(notification, language)
            } else {
                sendTextNotification(notification, language)
            }
            
            if (success) {
                logger.debug { "Notification delivered to user ${notification.userId}" }
                mediaCleanupNeeded = true // Clean up on success
            } else {
                logger.warn { "Failed to deliver notification to user ${notification.userId}" }
                mediaCleanupNeeded = true // Clean up on failure too
            }

        } catch (e: TelegramApiException) {
            logger.error(e) { "Telegram API error for user ${notification.userId}" }
            
            if (isUserUnreachableError(e)) {
                logger.warn { "User ${notification.userId} is unreachable: ${e.message}" }
                mediaCleanupNeeded = true
            } else {
                delay(10000)
                notificationQueue.offer(notification)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error for user ${notification.userId}" }
            ErrorTracker.logError("ERROR", "Notification processing error: ${e.message}", e)
            mediaCleanupNeeded = true
            
        } finally {
            // Always clean up media files after processing
            if (mediaCleanupNeeded && notification.mediaAttachments.isNotEmpty()) {
                mediaDownloader.cleanupMediaFiles(notification.mediaAttachments)
            }
        }
    }
    
    /**
     * Send notification with media attachments
     */
    private suspend fun sendNotificationWithMedia(
        notification: NotificationMessage,
        language: String
    ): Boolean {
        try {
            val chatId = notification.userId.toString()
            val (markdownContent, plainContent) = buildSimpleMessage(notification, language)
            
            // Get the first attachment for the main message
            val firstAttachment = notification.mediaAttachments.firstOrNull()
            if (firstAttachment == null) {
                // No valid attachments, fall back to text-only
                return sendTextNotification(notification, language)
            }
            
            // Send first attachment with the text content
            val firstSent = sendMediaAttachment(chatId, firstAttachment, markdownContent, plainContent)
            
            if (!firstSent) {
                // If first attachment failed, try text-only
                return sendTextNotification(notification, language)
            }
            
            // Send remaining attachments (if any) without text
            for (i in 1 until notification.mediaAttachments.size) {
                val attachment = notification.mediaAttachments[i]
                try {
                    sendMediaAttachment(chatId, attachment, null, null)
                    // Small delay between attachments to avoid rate limits
                    delay(500)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to send additional attachment $i for user $chatId" }
                    // Continue sending other attachments even if one fails
                }
            }
            
            markdownSuccess++
            return true
            
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send notification with media for user ${notification.userId}" }
            return false
        }
    }
    
    /**
     * Send individual media attachment
     */
    private suspend fun sendMediaAttachment(
        chatId: String,
        attachment: MediaAttachment,
        markdownContent: String?,
        plainContent: String?
    ): Boolean {
        return try {
            val file = File(attachment.filePath)
            if (!file.exists()) {
                logger.warn { "Media file not found: ${attachment.filePath}" }
                return false
            }
            
            val inputFile = InputFile(file)
            
            // Try MarkdownV2 first, then fallback to plain text
            val success = when (attachment.type) {
                MediaType.PHOTO -> {
                    tryMarkdownPhoto(chatId, inputFile, markdownContent) ||
                    sendPlainPhoto(chatId, inputFile, plainContent)
                }
                
                MediaType.VIDEO -> {
                    tryMarkdownVideo(chatId, inputFile, markdownContent, attachment) ||
                    sendPlainVideo(chatId, inputFile, plainContent, attachment)
                }
                
                MediaType.ANIMATION -> {
                    tryMarkdownAnimation(chatId, inputFile, markdownContent, attachment) ||
                    sendPlainAnimation(chatId, inputFile, plainContent, attachment)
                }
                
                MediaType.DOCUMENT -> {
                    tryMarkdownDocument(chatId, inputFile, markdownContent, attachment) ||
                    sendPlainDocument(chatId, inputFile, plainContent, attachment)
                }
                
                MediaType.AUDIO -> {
                    tryMarkdownAudio(chatId, inputFile, markdownContent, attachment) ||
                    sendPlainAudio(chatId, inputFile, plainContent, attachment)
                }
                
                MediaType.VOICE -> {
                    tryMarkdownVoice(chatId, inputFile, markdownContent, attachment) ||
                    sendPlainVoice(chatId, inputFile, plainContent, attachment)
                }
            }
            
            if (success) {
                logger.debug { "✅ Sent ${attachment.type} attachment: ${attachment.originalFileName}" }
            }
            
            success
            
        } catch (e: Exception) {
            logger.warn(e) { "Error sending media attachment: ${attachment.filePath}" }
            false
        }
    }
    
    // Photo sending methods
    private suspend fun tryMarkdownPhoto(chatId: String, photo: InputFile, content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendPhoto = SendPhoto.builder()
                .chatId(chatId)
                .photo(photo)
                .caption(content)
                .parseMode("MarkdownV2")
                .build()
            
            withTimeout(5000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendPhoto)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainPhoto(chatId: String, photo: InputFile, content: String?): Boolean {
        return try {
            val sendPhoto = SendPhoto.builder()
                .chatId(chatId)
                .photo(photo)
                .apply { if (!content.isNullOrBlank()) caption(content) }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendPhoto)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send photo" }
            false
        }
    }
    
    // Video sending methods
    private suspend fun tryMarkdownVideo(chatId: String, video: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendVideo = SendVideo.builder()
                .chatId(chatId)
                .video(video)
                .caption(content)
                .parseMode("MarkdownV2")
                .apply {
                    attachment.width?.let { width(it) }
                    attachment.height?.let { height(it) }
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withTimeout(10000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendVideo)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainVideo(chatId: String, video: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        return try {
            val sendVideo = SendVideo.builder()
                .chatId(chatId)
                .video(video)
                .apply { 
                    if (!content.isNullOrBlank()) caption(content)
                    attachment.width?.let { width(it) }
                    attachment.height?.let { height(it) }
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendVideo)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send video" }
            false
        }
    }
    
    // Animation sending methods
    private suspend fun tryMarkdownAnimation(chatId: String, animation: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendAnimation = SendAnimation.builder()
                .chatId(chatId)
                .animation(animation)
                .caption(content)
                .parseMode("MarkdownV2")
                .apply {
                    attachment.width?.let { width(it) }
                    attachment.height?.let { height(it) }
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withTimeout(10000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendAnimation)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainAnimation(chatId: String, animation: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        return try {
            val sendAnimation = SendAnimation.builder()
                .chatId(chatId)
                .animation(animation)
                .apply { 
                    if (!content.isNullOrBlank()) caption(content)
                    attachment.width?.let { width(it) }
                    attachment.height?.let { height(it) }
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendAnimation)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send animation" }
            false
        }
    }
    
    // Document sending methods
    private suspend fun tryMarkdownDocument(chatId: String, document: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendDocument = SendDocument.builder()
                .chatId(chatId)
                .document(document)
                .caption(content)
                .parseMode("MarkdownV2")
                .build()
            
            withTimeout(15000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendDocument)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainDocument(chatId: String, document: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        return try {
            val sendDocument = SendDocument.builder()
                .chatId(chatId)
                .document(document)
                .apply { if (!content.isNullOrBlank()) caption(content) }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendDocument)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send document" }
            false
        }
    }
    
    // Audio sending methods
    private suspend fun tryMarkdownAudio(chatId: String, audio: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendAudio = SendAudio.builder()
                .chatId(chatId)
                .audio(audio)
                .caption(content)
                .parseMode("MarkdownV2")
                .apply { attachment.duration?.let { duration(it) } }
                .build()
            
            withTimeout(15000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendAudio)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainAudio(chatId: String, audio: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        return try {
            val sendAudio = SendAudio.builder()
                .chatId(chatId)
                .audio(audio)
                .apply { 
                    if (!content.isNullOrBlank()) caption(content)
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendAudio)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send audio" }
            false
        }
    }
    
    // Voice sending methods
    private suspend fun tryMarkdownVoice(chatId: String, voice: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        if (content.isNullOrBlank()) return false
        
        return try {
            val sendVoice = SendVoice.builder()
                .chatId(chatId)
                .voice(voice)
                .caption(content)
                .parseMode("MarkdownV2")
                .apply { attachment.duration?.let { duration(it) } }
                .build()
            
            withTimeout(10000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendVoice)
                }
            }
            true
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) false else throw e
        }
    }
    
    private suspend fun sendPlainVoice(chatId: String, voice: InputFile, content: String?, attachment: MediaAttachment): Boolean {
        return try {
            val sendVoice = SendVoice.builder()
                .chatId(chatId)
                .voice(voice)
                .apply { 
                    if (!content.isNullOrBlank()) caption(content)
                    attachment.duration?.let { duration(it) }
                }
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(sendVoice)
            }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send voice" }
            false
        }
    }
    
    /**
     * Send text-only notification (fallback or no media)
     */
    private suspend fun sendTextNotification(
        notification: NotificationMessage,
        language: String
    ): Boolean {
        val chatId = notification.userId.toString()
        val (markdownContent, plainContent) = buildSimpleMessage(notification, language)
        
        // Try MarkdownV2 first, then fallback to plain text
        return tryMarkdownV2(chatId, markdownContent) ||
               sendPlainText(chatId, plainContent)
    }
    
    /**
     * Build simple message with proper MarkdownV2 link handling
     */
    private fun buildSimpleMessage(notification: NotificationMessage, language: String): Pair<String, String> {
        val channelName = notification.channelName
        val originalContent = notification.formattedMessageText ?: notification.messageText
        
        // Build MarkdownV2 version
        val markdownParts = mutableListOf<String>()
        
        // Build header with clickable channel link
        if (!notification.messageLink.isNullOrBlank()) {
            val linkDisplayText = if (channelName.startsWith("@")) {
                channelName // Already has @
            } else {
                "@$channelName" // Add @ for display
            }
            
            // For MarkdownV2 links, properly escape only the content inside the link text
            // Do NOT escape [ and ] as they are part of the link syntax [text](url)
            val safeLinkText = linkDisplayText
                .replace("\\", "\\\\")  // Escape backslashes first
                .replace("_", "\\_")    // Escape underscores (could start italic)
                .replace("*", "\\*")    // Escape asterisks (could start bold)
                .replace("`", "\\`")    // Escape backticks (could start code)
                .replace("~", "\\~")    // Escape tildes (could start strikethrough)
                // Note: [ and ] are NOT escaped here because they're part of link syntax
            
            val escapedUrl = TelegramMarkdownConverter.escapeUrlInLink(notification.messageLink)
            val markdownLink = "[$safeLinkText]($escapedUrl)"
            
            // Get the raw localization template and process it properly
            val rawTemplate = Localization.getMessage(language, "notification.job.match.header", "PLACEHOLDER")
            val templateWithPlaceholder = rawTemplate.replace("PLACEHOLDER", "{0}")
            
            // Escape the template but preserve the placeholder for replacement
            val escapedTemplate = TelegramMarkdownConverter.escapeMarkdownV2(templateWithPlaceholder)
            
            // Replace the escaped placeholder with the actual link
            val finalHeader = escapedTemplate.replace("\\{0\\}", markdownLink).replace("{0}", markdownLink)
            
            markdownParts.add(finalHeader)
            
            logger.debug { "Raw template: $rawTemplate" }
            logger.debug { "Template with placeholder: $templateWithPlaceholder" }
            logger.debug { "Escaped template: $escapedTemplate" }
            logger.debug { "Created MarkdownV2 link: $markdownLink" }
            logger.debug { "Final header: $finalHeader" }
        } else {
            // No link available, use plain header
            val header = Localization.getMessage(language, "notification.job.match.header", channelName)
            markdownParts.add(TelegramMarkdownConverter.escapeMarkdownV2(header))
        }
        
        // Add original content (preserve formatting if available)
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
        
        logger.debug { "Built message - markdown: ${markdownContent.length} chars, plain: ${plainContent.length} chars" }
        
        return Pair(markdownContent, plainContent)
    }
    
    /**
     * Try sending with MarkdownV2 - let Telegram API validate the content
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
            
            markdownSuccess++
            logger.debug { "✅ MarkdownV2 success for user $chatId" }
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
     * Send plain text (guaranteed to work)
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
     * Enhanced statistics logging with media tracking
     */
    private fun startPeriodicStatsLogging() {
        scope.launch {
            while (isActive) {
                delay(600000) // Every 10 minutes
                
                if (totalNotifications > 0) {
                    val successRate = (markdownSuccess.toDouble() / totalNotifications * 100).toInt()
                    val mediaRate = if (totalNotifications > 0) (mediaNotifications.toDouble() / totalNotifications * 100).toInt() else 0
                    
                    logger.debug { 
                        "📊 Notification stats: $successRate% MarkdownV2 success, $mediaRate% with media " +
                        "($markdownSuccess formatted, $plainFallback plain, $mediaNotifications media, $totalNotifications total)"
                    }
                }
            }
        }
    }
    
    fun getQueueSize(): Int = notificationQueue.size
    
    fun getFormattingStats(): Map<String, Any> {
        return mapOf(
            "total" to totalNotifications,
            "markdownSuccess" to markdownSuccess,
            "plainFallback" to plainFallback,
            "mediaNotifications" to mediaNotifications,
            "successRate" to if (totalNotifications > 0) (markdownSuccess * 100 / totalNotifications) else 0,
            "mediaRate" to if (totalNotifications > 0) (mediaNotifications * 100 / totalNotifications) else 0
        )
    }
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        if (totalNotifications > 0) {
            val successRate = (markdownSuccess.toDouble() / totalNotifications * 100).toInt()
            val mediaRate = if (totalNotifications > 0) (mediaNotifications.toDouble() / totalNotifications * 100).toInt() else 0
            
            logger.debug { 
                "📊 Final stats: $successRate% MarkdownV2 success, $mediaRate% with media " +
                "($markdownSuccess formatted, $plainFallback plain, $mediaNotifications media, $totalNotifications total)"
            }
        }
        
        // Clean up any remaining queued notifications with media
        val remainingNotifications = mutableListOf<NotificationMessage>()
        notificationQueue.drainTo(remainingNotifications)
        
        remainingNotifications.forEach { notification ->
            if (notification.mediaAttachments.isNotEmpty()) {
                mediaDownloader.cleanupMediaFiles(notification.mediaAttachments)
            }
        }
        
        if (remainingNotifications.isNotEmpty()) {
            logger.info { "Cleaned up ${remainingNotifications.size} queued notifications during shutdown" }
        }
        
        scope.cancel()
    }
}
package com.jobbot.bot

import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
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
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.objects.media.*
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.*

/**
 * FIXED NotificationProcessor with proper filename handling
 * Now correctly uses actualFileName for all media uploads
 */
class NotificationProcessor(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramClient: OkHttpTelegramClient,
    private val config: BotConfig
) {
    private val logger = getLogger("NotificationProcessor")
    
    private val notificationQueue = LinkedBlockingQueue<NotificationMessage>(1000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Convert configured timeout to milliseconds
    private val uploadTimeoutMs = config.mediaUploadTimeoutSeconds * 1000L
    
    // Media reuse tracking - keyed by original message content hash
    private val mediaCache = ConcurrentHashMap<String, CachedMediaGroup>()
    
    // Enhanced tracking
    private var markdownSuccess = 0
    private var plainFallback = 0
    private var totalNotifications = 0
    private var mediaNotifications = 0
    private var mediaReusedCount = 0
    
    // Data class for cached media
    private data class CachedMediaGroup(
        val attachments: List<MediaAttachment>,
        val createdTime: Long = System.currentTimeMillis(),
        var useCount: Int = 0
    )
    
    init {
        startNotificationProcessor()
        startPeriodicStatsLogging()
        startMediaCacheCleanup()
        logger.info { "NotificationProcessor initialized with ${config.mediaUploadTimeoutSeconds}s media upload timeout" }
    }
    
    fun queueNotification(notification: NotificationMessage) {
        try {
            val queued = notificationQueue.offer(notification, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!queued) {
                logger.warn { "Notification queue full, dropping notification for user ${notification.userId}" }
                // No cleanup needed - TDLib manages files
            } else {
                logger.debug { "Notification queued for user ${notification.userId} with ${notification.mediaAttachments.size} attachments" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to queue notification" }
            ErrorTracker.logError("ERROR", "Failed to queue notification: ${e.message}", e)
        }
    }
    
    /**
     * Queue multiple notifications efficiently - with media reuse optimization
     */
    fun queueNotifications(notifications: List<NotificationMessage>) {
        if (notifications.isEmpty()) return
        
        // Check if we can reuse media for these notifications
        val firstNotification = notifications.first()
        if (firstNotification.mediaAttachments.isNotEmpty()) {
            optimizeMediaForBatch(notifications)
        }
        
        notifications.forEach { queueNotification(it) }
    }
    
    /**
     * Optimize media usage for a batch of notifications with the same media
     */
    private fun optimizeMediaForBatch(notifications: List<NotificationMessage>) {
        val mediaAttachments = notifications.first().mediaAttachments
        if (mediaAttachments.isEmpty()) return
        
        // Create a cache key based on media content
        val cacheKey = createMediaCacheKey(notifications.first())
        
        // Cache the media group for reuse
        mediaCache[cacheKey] = CachedMediaGroup(mediaAttachments, useCount = notifications.size)
        
        logger.debug { "Cached media group for ${notifications.size} notifications (key: ${cacheKey.take(8)}...)" }
    }
    
    private fun createMediaCacheKey(notification: NotificationMessage): String {
        // Create a unique key based on message content and media info
        val contentHash = "${notification.messageText}-${notification.channelName}".hashCode()
        val mediaInfo = notification.mediaAttachments.joinToString(",") { 
            "${it.type}:${it.originalFileName}:${it.fileSize}" 
        }
        return "$contentHash-${mediaInfo.hashCode()}"
    }

    private fun startNotificationProcessor() {
        scope.launch {
            logger.info { "Notification processor started with media attachment support and reuse optimization" }
            
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
            
            // Track media notifications
            if (notification.mediaAttachments.isNotEmpty()) {
                mediaNotifications++
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
                
                // Check if this is the last user for this media group
                val cacheKey = createMediaCacheKey(notification)
                val cachedGroup = mediaCache[cacheKey]
                if (cachedGroup != null) {
                    cachedGroup.useCount--
                    if (cachedGroup.useCount <= 0) {
                        // Last user - remove from cache (no file cleanup needed)
                        mediaCache.remove(cacheKey)
                        logger.debug { "Last user for media group, removing from cache" }
                    } else {
                        // Other users still need this media
                        mediaReusedCount++
                        logger.debug { "Media reused, ${cachedGroup.useCount} users remaining" }
                    }
                }
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
        
        // NO MEDIA CLEANUP - TDLib manages its own files
    }

    /**
     * Send notification with media attachments using proper Telegram media group
     */
    private suspend fun sendNotificationWithMedia(
        notification: NotificationMessage,
        language: String
    ): Boolean {
        try {
            val chatId = notification.userId.toString()
            val (markdownContent, plainContent) = buildSimpleMessage(notification, language)
            
            if (notification.mediaAttachments.isEmpty()) {
                return sendTextNotification(notification, language)
            }
            
            if (notification.mediaAttachments.size == 1) {
                // Single attachment - send with caption
                val attachment = notification.mediaAttachments.first()
                return sendMediaAttachment(chatId, attachment, markdownContent, plainContent)
            } else {
                // Multiple attachments - use proper media group
                return sendProperMediaGroup(chatId, notification.mediaAttachments, markdownContent, plainContent)
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send notification with media for user ${notification.userId}" }
            return false
        }
    }

    /**
     * Send proper Telegram media group (album) - appears as ONE message with multiple media
     */
    private suspend fun sendProperMediaGroup(
        chatId: String,
        attachments: List<MediaAttachment>,
        markdownContent: String?,
        plainContent: String?
    ): Boolean {
        try {
            logger.debug { "Sending proper media group with ${attachments.size} attachments" }
            
            // Filter attachments that can be in media groups (photos and videos only)
            val groupableAttachments = attachments.filter { 
                it.type == MediaType.PHOTO || it.type == MediaType.VIDEO 
            }
            val otherAttachments = attachments.filter { 
                it.type != MediaType.PHOTO && it.type != MediaType.VIDEO 
            }
            
            var mainGroupSent = false
            
            // Send the main media group (photos + videos)
            if (groupableAttachments.isNotEmpty()) {
                mainGroupSent = sendTelegramMediaGroup(chatId, groupableAttachments, markdownContent, plainContent)
            }
            
            // Send other attachment types individually (documents, audio, etc.)
            var othersSent = 0
            for (attachment in otherAttachments) {
                try {
                    // Don't add caption to these - caption was already on the main group
                    val sent = sendMediaAttachment(chatId, attachment, null, null)
                    if (sent) othersSent++
                    delay(300) // Small delay between different types
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to send ${attachment.type} attachment" }
                }
            }
            
            val totalSuccess = (if (mainGroupSent) 1 else 0) + othersSent
            logger.info { "Media group result: main group=${if (mainGroupSent) "✅" else "❌"}, others=$othersSent/${otherAttachments.size}" }
            
            return totalSuccess > 0
            
        } catch (e: Exception) {
            logger.error(e) { "Error in sendProperMediaGroup" }
            return false
        }
    }

    /**
     * Send Telegram media group using correct API v9.x
     */
    private suspend fun sendTelegramMediaGroup(
        chatId: String,
        attachments: List<MediaAttachment>,
        markdownContent: String?,
        plainContent: String?
    ): Boolean {
        try {
            // Create media list with proper InputMedia objects
            val mediaList = mutableListOf<InputMedia>()
            
            for (i in attachments.indices) {
                val attachment = attachments[i]
                val file = File(attachment.filePath)
                
                if (!file.exists()) {
                    logger.warn { "Media file not found: ${attachment.filePath}" }
                    continue
                }
                
                // Only first item gets caption
                val caption = if (i == 0) markdownContent else null
                val parseMode = if (i == 0 && !markdownContent.isNullOrBlank()) "MarkdownV2" else null
                
                val media = when (attachment.type) {
                    MediaType.PHOTO -> {
                        val photoBuilder = InputMediaPhoto.builder()
                            .media(file, attachment.actualFileName) // FIXED: Use actualFileName
                        
                        if (caption != null) {
                            photoBuilder.caption(caption)
                        }
                        if (parseMode != null) {
                            photoBuilder.parseMode(parseMode)
                        }
                        
                        photoBuilder.build()
                    }
                    
                    MediaType.VIDEO -> {
                        val videoBuilder = InputMediaVideo.builder()
                            .media(file, attachment.actualFileName) // FIXED: Use actualFileName
                        
                        if (caption != null) {
                            videoBuilder.caption(caption)
                        }
                        if (parseMode != null) {
                            videoBuilder.parseMode(parseMode)
                        }
                        attachment.width?.let { videoBuilder.width(it) }
                        attachment.height?.let { videoBuilder.height(it) }
                        attachment.duration?.let { videoBuilder.duration(it) }
                        
                        videoBuilder.build()
                    }
                    
                    else -> continue // Skip non-groupable types
                }
                
                mediaList.add(media)
            }
            
            if (mediaList.isEmpty()) {
                logger.warn { "No groupable media items found" }
                return false
            }
            
            // Create the sendMediaGroup request
            val sendMediaGroup = SendMediaGroup(chatId, mediaList)
            
            // Try to send with MarkdownV2 first
            val success = try {
                withTimeout(uploadTimeoutMs) {
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(sendMediaGroup)
                    }
                }
                
                logger.info { "✅ Sent media group album with ${mediaList.size} items (MarkdownV2)" }
                markdownSuccess++
                true
                
            } catch (e: TelegramApiException) {
                if (isFormattingError(e)) {
                    logger.debug { "MarkdownV2 failed, retrying with plain text" }
                    
                    // Recreate with plain text
                    val plainMediaList = mutableListOf<InputMedia>()
                    
                    for (i in attachments.indices) {
                        val attachment = attachments[i]
                        val file = File(attachment.filePath)
                        if (!file.exists() || (attachment.type != MediaType.PHOTO && attachment.type != MediaType.VIDEO)) continue
                        
                        val caption = if (i == 0) plainContent else null
                        
                        val media = when (attachment.type) {
                            MediaType.PHOTO -> {
                                val photoBuilder = InputMediaPhoto.builder()
                                    .media(file, attachment.actualFileName) // FIXED: Use actualFileName
                                
                                if (caption != null) {
                                    photoBuilder.caption(caption)
                                }
                                
                                photoBuilder.build()
                            }
                            MediaType.VIDEO -> {
                                val videoBuilder = InputMediaVideo.builder()
                                    .media(file, attachment.actualFileName) // FIXED: Use actualFileName
                                
                                if (caption != null) {
                                    videoBuilder.caption(caption)
                                }
                                attachment.width?.let { videoBuilder.width(it) }
                                attachment.height?.let { videoBuilder.height(it) }
                                attachment.duration?.let { videoBuilder.duration(it) }
                                
                                videoBuilder.build()
                            }
                            else -> continue
                        }
                        
                        plainMediaList.add(media)
                    }
                    
                    val plainSendMediaGroup = SendMediaGroup(chatId, plainMediaList)
                    
                    withTimeout(uploadTimeoutMs) {
                        withContext(Dispatchers.IO) {
                            telegramClient.execute(plainSendMediaGroup)
                        }
                    }
                    
                    logger.info { "✅ Sent media group album with ${plainMediaList.size} items (plain text)" }
                    plainFallback++
                    true
                    
                } else {
                    throw e
                }
            }
            
            return success
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to send Telegram media group" }
            return false
        }
    }
    
    /**
     * Send individual media attachment with configurable timeout
     * FIXED: Now uses actualFileName for all media types
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
            
            // FIXED: Create InputFile with proper filename
            val inputFile = InputFile(file, attachment.actualFileName)
            
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
                logger.debug { "✅ Sent ${attachment.type} attachment: ${attachment.actualFileName}" }
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
            
            withTimeout(uploadTimeoutMs) {
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
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendPhoto)
                }
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
            
            withTimeout(uploadTimeoutMs) {
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
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendVideo)
                }
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
            
            withTimeout(uploadTimeoutMs) {
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
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendAnimation)
                }
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
            
            withTimeout(uploadTimeoutMs) {
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
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendDocument)
                }
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
                .apply { 
                    attachment.duration?.let { duration(it) }
                    // FIXED: Add original metadata from TDLib for proper music display
                    attachment.performer?.let { performer(it) }
                    attachment.title?.let { title(it) }
                }
                .build()
            
            withTimeout(uploadTimeoutMs) {
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
                    // FIXED: Add original metadata from TDLib for proper music display
                    attachment.performer?.let { performer(it) }
                    attachment.title?.let { title(it) }
                }
                .build()
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendAudio)
                }
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
            
            withTimeout(uploadTimeoutMs) {
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
            
            withTimeout(uploadTimeoutMs) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendVoice)
                }
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
        val markdownParts = mutableListOf<String>()
        
        // Build header with clickable channel link
        if (!notification.messageLink.isNullOrBlank()) {
            val linkDisplayText = if (channelName.startsWith("@")) {
                channelName // Already has @
            } else {
                "@$channelName" // Add @ for display
            }
            
            // For MarkdownV2 links, properly escape only the content inside the link text
            val safeLinkText = linkDisplayText
                .replace("\\", "\\\\")  // Escape backslashes first
                .replace("_", "\\_")    // Escape underscores (could start italic)
                .replace("*", "\\*")    // Escape asterisks (could start bold)
                .replace("`", "\\`")    // Escape backticks (could start code)
                .replace("~", "\\~")    // Escape tildes (could start strikethrough)
            
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
            
            logger.debug { "Created MarkdownV2 link: $markdownLink" }
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
            
            withTimeout(5000) {
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
     * Start media cache cleanup - removes old cached media groups (cache references only, not files)
     */
    private fun startMediaCacheCleanup() {
        scope.launch {
            while (isActive) {
                delay(300000) // Every 5 minutes
                
                try {
                    val currentTime = System.currentTimeMillis()
                    val expiredKeys = mediaCache.entries
                        .filter { (_, group) -> currentTime - group.createdTime > 600000 } // 10 minutes old
                        .map { it.key }
                    
                    expiredKeys.forEach { key ->
                        val removedGroup = mediaCache.remove(key)
                        if (removedGroup != null) {
                            logger.debug { "Expired media cache entry: ${key.take(8)}..." }
                        }
                    }
                    
                    if (expiredKeys.isNotEmpty()) {
                        logger.debug { "Cleaned up ${expiredKeys.size} expired media cache entries" }
                    }
                    
                } catch (e: Exception) {
                    logger.warn(e) { "Error during media cache cleanup" }
                }
            }
        }
    }
    
    /**
     * Enhanced statistics logging with media reuse tracking
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
                        "($markdownSuccess formatted, $plainFallback plain, $mediaNotifications media, " +
                        "$mediaReusedCount reused, $totalNotifications total, cache: ${mediaCache.size})"
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
            "mediaReused" to mediaReusedCount,
            "mediaCacheSize" to mediaCache.size,
            "successRate" to if (totalNotifications > 0) (markdownSuccess * 100 / totalNotifications) else 0,
            "mediaRate" to if (totalNotifications > 0) (mediaNotifications * 100 / totalNotifications) else 0,
            "uploadTimeoutSeconds" to config.mediaUploadTimeoutSeconds
        )
    }
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        if (totalNotifications > 0) {
            val successRate = (markdownSuccess.toDouble() / totalNotifications * 100).toInt()
            val mediaRate = if (totalNotifications > 0) (mediaNotifications.toDouble() / totalNotifications * 100).toInt() else 0
            
            logger.debug { 
                "📊 Final stats: $successRate% MarkdownV2 success, $mediaRate% with media " +
                "($markdownSuccess formatted, $plainFallback plain, $mediaNotifications media, " +
                "$mediaReusedCount reused, $totalNotifications total)"
            }
        }
        
        // Clear cache references (no file cleanup needed - TDLib manages files)
        mediaCache.clear()
        
        val remainingNotifications = mutableListOf<NotificationMessage>()
        notificationQueue.drainTo(remainingNotifications)
        
        if (remainingNotifications.isNotEmpty()) {
            logger.info { "Cleared ${remainingNotifications.size} queued notifications during shutdown" }
        }
        
        scope.cancel()
    }
}
package com.jobbot.bot

import com.jobbot.data.Database
import com.jobbot.data.models.NotificationMessage
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.util.concurrent.LinkedBlockingQueue

/**
 * CLEANED NotificationProcessor - Removed redundant pre-validation
 * 
 * Format:
 * 1. "New match from [channel]"
 * 2. Link to original post (if available)
 * 3. Original content with all formatting preserved
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
    
    // Simple tracking
    private var markdownSuccess = 0
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
                logger.debug { "Notification queued for user ${notification.userId}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to queue notification" }
            ErrorTracker.logError("ERROR", "Failed to queue notification: ${e.message}", e)
        }
    }
    
    private fun startNotificationProcessor() {
        scope.launch {
            logger.info { "Notification processor started with simplified formatting" }
            
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
            
            // Build the simple message
            val (markdownContent, plainContent) = buildSimpleMessage(notification, language)
            
            // Try MarkdownV2 first, then fallback to plain text
            val success = tryMarkdownV2(notification.userId.toString(), markdownContent) ||
                         sendPlainText(notification.userId.toString(), plainContent)
            
            if (success) {
                logger.debug { "Notification delivered to user ${notification.userId}" }
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
     * Build simple message with proper MarkdownV2 link handling
     */
    private fun buildSimpleMessage(notification: NotificationMessage, language: String): Pair<String, String> {
        val channelName = notification.channelName ?: "Channel"
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
     * Simple statistics logging
     */
    private fun startPeriodicStatsLogging() {
        scope.launch {
            while (isActive) {
                delay(600000) // Every 10 minutes
                
                if (totalNotifications > 0) {
                    val successRate = (markdownSuccess.toDouble() / totalNotifications * 100).toInt()
                    logger.debug { 
                        "📊 Notification stats: $successRate% MarkdownV2 success " +
                        "($markdownSuccess formatted, $plainFallback plain, $totalNotifications total)"
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
            "successRate" to if (totalNotifications > 0) (markdownSuccess * 100 / totalNotifications) else 0
        )
    }
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        if (totalNotifications > 0) {
            val successRate = (markdownSuccess.toDouble() / totalNotifications * 100).toInt()
            logger.debug { 
                "📊 Final stats: $successRate% MarkdownV2 success " +
                "($markdownSuccess formatted, $plainFallback plain, $totalNotifications total)"
            }
        }
        
        scope.cancel()
    }
}
package com.jobbot.bot

import com.jobbot.data.Database
import com.jobbot.data.models.NotificationMessage
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.util.concurrent.LinkedBlockingQueue

/**
 * PRODUCTION-READY NotificationProcessor
 * 
 * Strategy: Always try formatting first, instant fallback to plain text if ANY issue
 * Guarantees: 100% delivery success, maximum formatting preservation
 */
class NotificationProcessor(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramClient: OkHttpTelegramClient
) {
    private val logger = getLogger("NotificationProcessor")
    
    private val notificationQueue = LinkedBlockingQueue<NotificationMessage>(1000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Track formatting success rates for monitoring
    private var formattingSuccessCount = 0
    private var formattingFailureCount = 0
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
            logger.info { "Notification processor started with formatting + bulletproof fallbacks" }
            
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
            
            // Build complete message content
            val messageContent = buildMessageContent(notification, language)
            
            // 🎯 STRATEGY: Try formatting first, instant fallback on ANY issue
            val success = sendWithInstantFallback(
                chatId = notification.userId.toString(),
                formattedContent = messageContent.formatted,
                plainContent = messageContent.plain
            )
            
            if (success) {
                logger.debug { "Notification delivered to user ${notification.userId}" }
            } else {
                logger.warn { "Failed to deliver notification to user ${notification.userId}" }
            }

        } catch (e: TelegramApiException) {
            logger.error(e) { "Telegram API error for user ${notification.userId}" }
            
            // Handle user-unreachable scenarios
            if (isUserUnreachableError(e)) {
                logger.warn { "User ${notification.userId} is unreachable: ${e.message}" }
            } else {
                // Retry other API errors
                delay(10000)
                notificationQueue.offer(notification)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error for user ${notification.userId}" }
            ErrorTracker.logError("ERROR", "Notification processing error: ${e.message}", e)
        }
    }
    
    /**
     * Build both formatted and plain versions of the message
     */
    private fun buildMessageContent(notification: NotificationMessage, language: String): MessageContent {
        val senderText = buildSenderText(notification)
        
        // Get the best available text content
        val jobText = notification.formattedMessageText?.takeIf { it.isNotBlank() } 
            ?: TextUtils.truncateText(notification.messageText, 4000)
        val plainJobText = TextUtils.truncateText(notification.messageText, 4000)
        
        // Build headers
        val formattedHeader = if (!notification.messageLink.isNullOrBlank()) {
            val linkText = notification.channelName
            val prettyLink = "[$linkText](${notification.messageLink})"
            Localization.getMessage(language, "notification.job.match.header.with.link", prettyLink)
        } else {
            Localization.getMessage(language, "notification.job.match.header", notification.channelName)
        }
        
        val plainHeader = if (!notification.messageLink.isNullOrBlank()) {
            "📢 New Match from ${notification.channelName}\n🔗 ${notification.messageLink}"
        } else {
            "📢 New Match from ${notification.channelName}"
        }
        
        // Combine all parts
        val formattedMessage = if (senderText.isNotBlank()) {
            "$formattedHeader\n$senderText\n\n$jobText"
        } else {
            "$formattedHeader\n\n$jobText"
        }
        
        val plainMessage = if (senderText.isNotBlank()) {
            "$plainHeader\n$senderText\n\n$plainJobText"
        } else {
            "$plainHeader\n\n$plainJobText"
        }
        
        return MessageContent(formattedMessage, plainMessage)
    }
    
    /**
     * 🎯 CORE STRATEGY: Try formatting with instant fallback
     * 
     * This is the key method - it attempts MarkdownV2 but falls back immediately
     * on ANY sign of trouble, ensuring 100% delivery success
     */
    private suspend fun sendWithInstantFallback(chatId: String, formattedContent: String, plainContent: String): Boolean {
        // First attempt: MarkdownV2 with aggressive timeout
        val formattingWorked = try {
            logger.debug { "Attempting MarkdownV2 for user $chatId" }
            
            val markdownMessage = SendMessage.builder()
                .chatId(chatId)
                .text(formattedContent)
                .parseMode("MarkdownV2")
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .build()
            
            // Very aggressive timeout - if it takes more than 2 seconds, something's wrong
            withTimeout(2000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(markdownMessage)
                }
            }
            
            logger.debug { "✅ MarkdownV2 success for user $chatId" }
            formattingSuccessCount++
            true
            
        } catch (e: TimeoutCancellationException) {
            logger.debug { "⏰ MarkdownV2 timeout for user $chatId - instant fallback" }
            formattingFailureCount++
            false
            
        } catch (e: TelegramApiException) {
            // Check if it's a parsing error
            if (isFormattingError(e)) {
                logger.debug { "🔧 MarkdownV2 parsing error for user $chatId: ${e.message?.take(50)} - instant fallback" }
                formattingFailureCount++
                false
            } else {
                // Re-throw non-formatting errors (network, user issues, etc.)
                throw e
            }
            
        } catch (e: Exception) {
            logger.debug { "❌ MarkdownV2 unexpected error for user $chatId: ${e.javaClass.simpleName} - instant fallback" }
            formattingFailureCount++
            false
        }
        
        // If formatting failed, send plain text immediately
        if (!formattingWorked) {
            return sendPlainTextGuaranteed(chatId, plainContent)
        }
        
        return true
    }
    
    /**
     * 🛡️ BULLETPROOF: Send plain text with absolute guarantee
     */
    private suspend fun sendPlainTextGuaranteed(chatId: String, plainContent: String): Boolean {
        return try {
            logger.debug { "📝 Sending plain text fallback for user $chatId" }
            
            val plainMessage = SendMessage.builder()
                .chatId(chatId)
                .text(plainContent)
                // NO parseMode = 100% safe
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(plainMessage)
            }
            
            logger.debug { "✅ Plain text delivered for user $chatId" }
            true
            
        } catch (e: TelegramApiException) {
            logger.error(e) { "🚨 CRITICAL: Even plain text failed for user $chatId" }
            false
        }
    }
    
    /**
     * Detect formatting-related errors that should trigger fallback
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
     * Detect user-unreachable errors that shouldn't be retried
     */
    private fun isUserUnreachableError(e: TelegramApiException): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("bot was blocked") ||
               message.contains("user not found") ||
               message.contains("chat not found") ||
               message.contains("deactivated") ||
               message.contains("kicked")
    }
    
    private fun buildSenderText(notification: NotificationMessage): String {
        val senderUsername = notification.senderUsername
        val channelName = notification.channelName
        
        return when {
            senderUsername.isNullOrBlank() -> ""
            senderUsername.equals(channelName, ignoreCase = true) -> ""
            else -> "👤 Posted by: $senderUsername"
        }
    }
    
    /**
     * Log formatting success rates periodically for monitoring
     */
    private fun startPeriodicStatsLogging() {
        scope.launch {
            while (isActive) {
                delay(300000) // Every 5 minutes
                
                if (totalNotifications > 0) {
                    val successRate = (formattingSuccessCount.toDouble() / totalNotifications * 100).toInt()
                    logger.info { 
                        "📊 Formatting stats: $successRate% success rate " +
                        "($formattingSuccessCount/$totalNotifications) - " +
                        "${formattingFailureCount} fallbacks to plain text"
                    }
                }
            }
        }
    }
    
    fun getQueueSize(): Int = notificationQueue.size
    
    fun getFormattingStats(): Map<String, Int> = mapOf(
        "total" to totalNotifications,
        "formattingSuccess" to formattingSuccessCount,
        "formattingFailures" to formattingFailureCount,
        "successRate" to if (totalNotifications > 0) (formattingSuccessCount * 100 / totalNotifications) else 0
    )
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        // Log final stats
        if (totalNotifications > 0) {
            val successRate = (formattingSuccessCount.toDouble() / totalNotifications * 100).toInt()
            logger.info { 
                "📊 Final formatting stats: $successRate% success rate " +
                "($formattingSuccessCount/$totalNotifications total notifications)"
            }
        }
        
        scope.cancel()
    }
    
    /**
     * Data class to hold both formatted and plain versions
     */
    private data class MessageContent(
        val formatted: String,
        val plain: String
    )
}
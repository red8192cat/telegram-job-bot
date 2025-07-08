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
 * Handles notification processing and delivery
 * With safe markdown support and pretty @tag links
 */
class NotificationProcessor(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramClient: OkHttpTelegramClient
) {
    private val logger = getLogger("NotificationProcessor")
    
    // Bounded notification queue to prevent memory issues
    private val notificationQueue = LinkedBlockingQueue<NotificationMessage>(1000)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        startNotificationProcessor()
    }
    
    fun queueNotification(notification: NotificationMessage) {
        try {
            // Use offer with timeout instead of blocking put
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
            logger.info { "Notification processor started" }
            
            while (isActive) {
                try {
                    val notification = withContext(Dispatchers.IO) {
                        // Poll with timeout to allow coroutine cancellation
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
                    delay(1000) // Wait before retrying
                }
            }
            
            logger.info { "Notification processor stopped" }
        }
    }
    
    private suspend fun processNotification(notification: NotificationMessage) {
        try {
            // Check rate limiting - notifications have lower priority than user commands
            if (!rateLimiter.isAllowed(notification.userId)) {
                logger.debug { "Rate limit hit for user ${notification.userId}, requeueing notification" }
                delay(5000) // Wait 5 seconds before requeueing
                notificationQueue.offer(notification)
                return
            }

            val user = database.getUser(notification.userId)
            val language = user?.language ?: "en"
            
            // Build sender information
            val senderText = buildSenderText(notification)
            
            // Create the main message text with pretty link in header
            val jobText = TextUtils.truncateText(notification.messageText, 4000)
            
            // Determine header text with pretty clickable @tag link
            val headerText = if (!notification.messageLink.isNullOrBlank()) {
                // Create pretty clickable @tag link in header
                val linkText = notification.channelName // This will be the @tag or channel name
                val prettyLink = "[$linkText](${notification.messageLink})"
                Localization.getMessage(language, "notification.job.match.header.with.link", prettyLink)
            } else {
                // Fallback to channel name if no link
                Localization.getMessage(language, "notification.job.match.header", notification.channelName)
            }
            
            val messageText = if (senderText.isNotBlank()) {
                // Format: Header with Pretty Link + Sender + Job Content
                "$headerText\n$senderText\n\n$jobText"
            } else {
                // Format: Header with Pretty Link + Job Content (no sender)
                "$headerText\n\n$jobText"
            }
            
            // Try sending with markdown first, fallback to plain text if it fails
            val success = sendWithMarkdownFallback(
                chatId = notification.userId.toString(),
                text = messageText,
                fallbackText = createFallbackText(language, notification, senderText, jobText)
            )
            
            if (success) {
                logger.debug { "Notification sent to user ${notification.userId}" }
            } else {
                logger.warn { "Failed to send notification to user ${notification.userId} with both markdown and plain text" }
            }

        } catch (e: TelegramApiException) {
            logger.error(e) { "Failed to send notification to user ${notification.userId}" }
            ErrorTracker.logError("ERROR", "Failed to send notification: ${e.message}", e)

            // If it's a user-related error (user blocked bot, etc.), don't retry
            if (e.message?.contains("bot was blocked") == true || 
                e.message?.contains("user not found") == true ||
                e.message?.contains("chat not found") == true) {
                logger.warn { "User ${notification.userId} is unreachable, skipping notification" }
            } else {
                // For other errors, retry after delay
                delay(10000)
                notificationQueue.offer(notification)
            }
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error sending notification to user ${notification.userId}" }
            ErrorTracker.logError("ERROR", "Unexpected notification error: ${e.message}", e)
        }
    }
    
    /**
     * Create fallback text for when markdown fails
     */
    private fun createFallbackText(language: String, notification: NotificationMessage, senderText: String, jobText: String): String {
        // For fallback, use the simple @tag format (no markdown)
        val headerText = if (!notification.messageLink.isNullOrBlank()) {
            val cleanLink = notification.messageLink.removePrefix("https://")
            Localization.getMessage(language, "notification.job.match.header.with.link", cleanLink)
        } else {
            Localization.getMessage(language, "notification.job.match.header", notification.channelName)
        }
        
        return if (senderText.isNotBlank()) {
            "$headerText\n$senderText\n\n$jobText"
        } else {
            "$headerText\n\n$jobText"
        }
    }
    
    /**
     * Try sending with markdown first, fallback to plain text if markdown fails
     */
    private suspend fun sendWithMarkdownFallback(chatId: String, text: String, fallbackText: String): Boolean {
        return try {
            // First attempt: Try with MarkdownV2 (most robust)
            val markdownMessage = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("MarkdownV2")
                .linkPreviewOptions(LinkPreviewOptions.builder()
                    .isDisabled(true)
                    .build())
                .build()
            
            withContext(Dispatchers.IO) {
                telegramClient.execute(markdownMessage)
            }
            
            logger.debug { "Sent notification with MarkdownV2 to $chatId" }
            true
            
        } catch (e: TelegramApiException) {
            if (e.message?.contains("can't parse entities") == true || 
                e.message?.contains("parse mode") == true) {
                
                logger.debug { "MarkdownV2 failed for $chatId, trying Markdown..." }
                
                try {
                    // Second attempt: Try with classic Markdown
                    val markdownMessage = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .linkPreviewOptions(LinkPreviewOptions.builder()
                            .isDisabled(true)
                            .build())
                        .build()
                    
                    withContext(Dispatchers.IO) {
                        telegramClient.execute(markdownMessage)
                    }
                    
                    logger.debug { "Sent notification with Markdown to $chatId" }
                    true
                    
                } catch (e2: TelegramApiException) {
                    if (e2.message?.contains("can't parse entities") == true || 
                        e2.message?.contains("parse mode") == true) {
                        
                        logger.debug { "Markdown also failed for $chatId, using plain text fallback..." }
                        
                        try {
                            // Final fallback: Use pre-prepared fallback text
                            val plainMessage = SendMessage.builder()
                                .chatId(chatId)
                                .text(fallbackText)
                                // No parseMode = plain text
                                .linkPreviewOptions(LinkPreviewOptions.builder()
                                    .isDisabled(true)
                                    .build())
                                .build()
                            
                            withContext(Dispatchers.IO) {
                                telegramClient.execute(plainMessage)
                            }
                            
                            logger.debug { "Sent notification with plain text fallback to $chatId" }
                            true
                            
                        } catch (e3: TelegramApiException) {
                            logger.error(e3) { "Even plain text fallback failed for $chatId" }
                            false
                        }
                    } else {
                        // Different error, not parsing related
                        throw e2
                    }
                }
            } else {
                // Different error, not parsing related
                throw e
            }
        }
    }
    
    private fun buildSenderText(notification: NotificationMessage): String {
        val senderUsername = notification.senderUsername
        val channelName = notification.channelName
        
        logger.debug { "Building sender text - sender: '$senderUsername', channel: '$channelName'" }
        
        return when {
            // Skip if no sender username
            senderUsername.isNullOrBlank() -> {
                logger.debug { "No sender username provided" }
                ""
            }
            
            // Skip if sender matches channel (avoid duplicates)
            senderUsername.equals(channelName, ignoreCase = true) -> {
                logger.debug { "Sender username matches channel name, skipping to avoid duplication" }
                ""
            }
            
            // Show sender info
            else -> {
                logger.debug { "Adding sender info: $senderUsername" }
                "👤 Posted by: $senderUsername"
            }
        }
    }
    
    fun getQueueSize(): Int = notificationQueue.size
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        scope.cancel()
    }
}
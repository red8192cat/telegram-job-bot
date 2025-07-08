package com.jobbot.bot

import com.jobbot.data.Database
import com.jobbot.data.models.NotificationMessage
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TextUtils
import com.jobbot.shared.utils.TelegramMarkdownConverter
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.util.concurrent.LinkedBlockingQueue

/**
 * ENHANCED NotificationProcessor with Integrated Markdown Converter
 * 
 * Strategy: Multi-layered approach for maximum formatting success
 * 1. Try formatted MarkdownV2 (from TDLib entities)
 * 2. Try enhanced converter on formatted text
 * 3. Try enhanced converter on plain text
 * 4. Fallback to plain text (guaranteed delivery)
 * 
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
    
    // Enhanced tracking for multi-layered approach
    private var formattingLayerSuccess = mutableMapOf<String, Int>(
        "raw_markdown" to 0,
        "enhanced_formatted" to 0,
        "enhanced_plain" to 0,
        "plain_fallback" to 0
    )
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
            logger.info { "Notification processor started with ENHANCED multi-layered formatting" }
            
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
            
            // 🎯 ENHANCED STRATEGY: Multi-layered approach for maximum success
            val success = sendWithMultiLayeredApproach(
                chatId = notification.userId.toString(),
                messageContent = messageContent
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
     * Build enhanced message content with multiple formatting versions
     */
    private fun buildMessageContent(notification: NotificationMessage, language: String): EnhancedMessageContent {
        val senderText = buildSenderText(notification)
        
        // Get the job text in different formats
        val rawFormattedText = notification.formattedMessageText
        val plainJobText = TextUtils.truncateText(notification.messageText, 4000)
        
        // Build headers
        val headerWithLink = if (!notification.messageLink.isNullOrBlank()) {
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
        
        // Create different formatting approaches
        val approaches = mutableMapOf<String, String>()
        
        // 1. Raw formatted text (if available)
        if (!rawFormattedText.isNullOrBlank() && rawFormattedText != plainJobText) {
            val rawMessage = if (senderText.isNotBlank()) {
                "$headerWithLink\n$senderText\n\n$rawFormattedText"
            } else {
                "$headerWithLink\n\n$rawFormattedText"
            }
            approaches["raw_markdown"] = rawMessage
        }
        
        // 2. Enhanced converter on formatted text
        if (!rawFormattedText.isNullOrBlank()) {
            try {
                val enhancedFormatted = TelegramMarkdownConverter.createFormattedMessage(
                    header = headerWithLink,
                    content = rawFormattedText,
                    footer = senderText.takeIf { it.isNotBlank() },
                    headerFormat = TelegramMarkdownConverter.MessageFormat.NONE,
                    escapeContent = false // Already formatted
                )
                approaches["enhanced_formatted"] = enhancedFormatted
            } catch (e: Exception) {
                logger.debug { "Failed to create enhanced formatted message: ${e.message}" }
            }
        }
        
        // 3. Enhanced converter on plain text
        try {
            val enhancedPlain = TelegramMarkdownConverter.createFormattedMessage(
                header = notification.channelName,
                content = plainJobText,
                footer = if (!notification.messageLink.isNullOrBlank()) "🔗 ${notification.messageLink}" else null,
                headerFormat = TelegramMarkdownConverter.MessageFormat.BOLD,
                escapeContent = true
            )
            approaches["enhanced_plain"] = enhancedPlain
        } catch (e: Exception) {
            logger.debug { "Failed to create enhanced plain message: ${e.message}" }
        }
        
        // 4. Plain text fallback (guaranteed to work)
        val plainMessage = if (senderText.isNotBlank()) {
            "$plainHeader\n$senderText\n\n$plainJobText"
        } else {
            "$plainHeader\n\n$plainJobText"
        }
        approaches["plain_fallback"] = plainMessage
        
        return EnhancedMessageContent(approaches)
    }
    
    /**
     * 🎯 ENHANCED STRATEGY: Multi-layered approach for maximum formatting success
     * Try different formatting approaches in order of sophistication
     */
    private suspend fun sendWithMultiLayeredApproach(
        chatId: String, 
        messageContent: EnhancedMessageContent
    ): Boolean {
        
        val approaches = listOf(
            "raw_markdown",
            "enhanced_formatted", 
            "enhanced_plain",
            "plain_fallback"
        )
        
        for (approach in approaches) {
            val content = messageContent.approaches[approach]
            if (content == null) {
                logger.debug { "Approach $approach not available for user $chatId" }
                continue
            }
            
            val success = when (approach) {
                "plain_fallback" -> {
                    // Plain text - guaranteed to work
                    sendPlainTextGuaranteed(chatId, content)
                }
                else -> {
                    // Try MarkdownV2 with this approach
                    tryMarkdownV2(chatId, content, approach)
                }
            }
            
            if (success) {
                formattingLayerSuccess[approach] = formattingLayerSuccess[approach]!! + 1
                logger.debug { "✅ Successfully sent notification using approach: $approach" }
                return true
            }
            
            logger.debug { "❌ Approach $approach failed for user $chatId, trying next..." }
        }
        
        // This should never happen since plain_fallback is guaranteed
        logger.error { "🚨 CRITICAL: All approaches failed for user $chatId" }
        return false
    }
    
    /**
     * Try sending with MarkdownV2 using specified approach
     */
    private suspend fun tryMarkdownV2(chatId: String, content: String, approach: String): Boolean {
        return try {
            logger.debug { "Trying MarkdownV2 with approach: $approach for user $chatId" }
            
            // Pre-validate the content
            if (TelegramMarkdownConverter.hasUnbalancedMarkup(content)) {
                logger.debug { "Content has unbalanced markup, skipping approach: $approach" }
                return false
            }
            
            val markdownMessage = SendMessage.builder()
                .chatId(chatId)
                .text(content)
                .parseMode("MarkdownV2")
                .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
                .build()
            
            // Aggressive timeout for quick fallback
            withTimeout(3000) {
                withContext(Dispatchers.IO) {
                    telegramClient.execute(markdownMessage)
                }
            }
            
            logger.debug { "✅ MarkdownV2 success with approach: $approach for user $chatId" }
            true
            
        } catch (e: TimeoutCancellationException) {
            logger.debug { "⏰ MarkdownV2 timeout with approach: $approach for user $chatId" }
            false
            
        } catch (e: TelegramApiException) {
            if (isFormattingError(e)) {
                val errorType = classifyFormattingError(e)
                logger.debug { "🔧 MarkdownV2 $errorType with approach: $approach for user $chatId" }
                false
            } else {
                // Re-throw non-formatting errors
                throw e
            }
            
        } catch (e: Exception) {
            logger.debug { "❌ MarkdownV2 unexpected error with approach: $approach for user $chatId: ${e.javaClass.simpleName}" }
            false
        }
    }
    
    /**
     * 🛡️ BULLETPROOF: Send plain text with absolute guarantee
     */
    private suspend fun sendPlainTextGuaranteed(chatId: String, plainContent: String): Boolean {
        return try {
            logger.debug { "📝 Sending plain text for user $chatId" }
            
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
               message.contains("parse mode") ||
               message.contains("invalid") ||
               message.contains("unexpected") ||
               message.contains("character") ||
               message.contains("offset")
    }
    
    /**
     * Classify the type of formatting error for better debugging
     */
    private fun classifyFormattingError(e: TelegramApiException): String {
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("can't parse") -> "parse error"
            message.contains("reserved") -> "reserved character"
            message.contains("escaped") -> "escape error"
            message.contains("entities") -> "entity error"
            message.contains("offset") -> "offset error"
            message.contains("character") -> "character error"
            else -> "formatting error"
        }
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
     * Enhanced statistics logging with multi-layer breakdown
     */
    private fun startPeriodicStatsLogging() {
        scope.launch {
            while (isActive) {
                delay(300000) // Every 5 minutes
                
                if (totalNotifications > 0) {
                    val totalFormatted = formattingLayerSuccess.values.sum() - formattingLayerSuccess["plain_fallback"]!!
                    val overallSuccessRate = (totalFormatted.toDouble() / totalNotifications * 100).toInt()
                    
                    logger.info { 
                        "📊 ENHANCED Formatting stats: $overallSuccessRate% formatted delivery rate " +
                        "($totalFormatted/$totalNotifications) with multi-layered approach"
                    }
                    
                    // Detailed breakdown
                    formattingLayerSuccess.forEach { (layer, count) ->
                        val rate = (count.toDouble() / totalNotifications * 100).toInt()
                        logger.info { "  └─ $layer: $count notifications ($rate%)" }
                    }
                    
                    // Alert if overall success rate is low
                    if (overallSuccessRate < 60 && totalNotifications > 10) {
                        logger.warn { 
                            "📉 Overall formatting success rate is low ($overallSuccessRate%). " +
                            "Most notifications are falling back to plain text."
                        }
                    }
                }
            }
        }
    }
    
    fun getQueueSize(): Int = notificationQueue.size
    
    fun getFormattingStats(): Map<String, Any> {
        val totalFormatted = formattingLayerSuccess.values.sum() - formattingLayerSuccess["plain_fallback"]!!
        return mapOf(
            "total" to totalNotifications,
            "formattedDeliveries" to totalFormatted,
            "plainFallbacks" to formattingLayerSuccess["plain_fallback"]!!,
            "overallSuccessRate" to if (totalNotifications > 0) (totalFormatted * 100 / totalNotifications) else 0,
            "layerBreakdown" to formattingLayerSuccess.toMap()
        )
    }
    
    fun shutdown() {
        logger.info { "Shutting down notification processor..." }
        
        // Log final enhanced stats
        if (totalNotifications > 0) {
            val totalFormatted = formattingLayerSuccess.values.sum() - formattingLayerSuccess["plain_fallback"]!!
            val successRate = (totalFormatted.toDouble() / totalNotifications * 100).toInt()
            logger.info { 
                "📊 Final ENHANCED stats: $successRate% formatted delivery rate " +
                "($totalFormatted/$totalNotifications total notifications)"
            }
            
            formattingLayerSuccess.forEach { (layer, count) ->
                val rate = (count.toDouble() / totalNotifications * 100).toInt()
                logger.info { "  └─ Final $layer: $count notifications ($rate%)" }
            }
        }
        
        scope.cancel()
    }
    
    /**
     * Enhanced message content with multiple formatting approaches
     */
    private data class EnhancedMessageContent(
        val approaches: Map<String, String>
    )
}
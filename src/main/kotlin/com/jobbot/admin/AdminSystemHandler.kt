package com.jobbot.admin

import com.jobbot.bot.TelegramBot
import com.jobbot.data.Database
import com.jobbot.data.models.UserInfo
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.monitoring.SystemMonitor
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.infrastructure.shutdown.BotShutdownManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.LogManager
import com.jobbot.shared.utils.TextUtils
import com.jobbot.shared.utils.ValidationUtils
import com.jobbot.bot.tdlib.TelegramUser
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import java.time.format.DateTimeFormatter

/**
 * Handles system monitoring and control operations
 * BULLETPROOF: NO MARKDOWN - Works with any system data, error messages, logs
 */
class AdminSystemHandler(
    private val database: Database,
    private val rateLimiter: RateLimiter,
    private val telegramUser: TelegramUser?
) {
    private val logger = getLogger("AdminSystemHandler")
    
    private var bot: TelegramBot? = null
    
    fun setBotInstance(botInstance: TelegramBot) {
        this.bot = botInstance
    }
    
    // SYSTEM HEALTH - NO MARKDOWN (contains dynamic system data)
    fun handleHealth(chatId: String): SendMessage {
        val activeUsers = database.getActiveUsersCount()
        val channels = database.getAllChannels()
        val health = SystemMonitor.getSystemHealth(activeUsers, channels.size)
        val rateLimitStatus = rateLimiter.getRateLimitStatus()
        
        val tdlibStatus = if (telegramUser?.isConnected() == true) "✅ Connected" else "❌ Disconnected"
        val lastErrorText = if (health.lastError != null) {
            Localization.getAdminMessage("admin.health.last.error", TextUtils.truncateText(health.lastError, 100))
        } else ""
        
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val healthText = "${Localization.getAdminMessage("admin.health.title")}\n" +
                        "${Localization.getAdminMessage("admin.health.timestamp", timestamp)}\n\n" +
                        Localization.getAdminMessage(
                            "admin.health.report",
                            health.uptime,
                            health.activeUsers,
                            health.monitoredChannels,
                            health.messagesProcessed,
                            health.errorCount,
                            lastErrorText,
                            tdlibStatus,
                            rateLimitStatus["maxTokens"] ?: 0,
                            rateLimitStatus["refillRatePerMinute"] ?: 0,
                            rateLimitStatus["activeUsers"] ?: 0,
                            rateLimitStatus["overloadedUsers"] ?: 0
                        )
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(healthText)
            // NO parseMode - system data can contain any characters
            .build()
    }
    
    // ERROR REPORTS - NO MARKDOWN (contains error messages with special chars)
    fun handleErrors(chatId: String): SendMessage {
        val recentErrors = ErrorTracker.getRecentErrors(10)
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        val errorsText = if (recentErrors.isEmpty()) {
            "${Localization.getAdminMessage("admin.errors.title")}\n" +
            "${Localization.getAdminMessage("admin.errors.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.errors.empty")
        } else {
            val errorList = recentErrors.joinToString("\n\n") { error ->
                val errorTimestamp = error.timestamp.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                val exceptionText = if (error.exception != null) {
                    "\n${TextUtils.truncateText(error.exception, 300)}"
                } else ""
                
                Localization.getAdminMessage(
                    "admin.errors.item",
                    errorTimestamp,
                    error.level,
                    TextUtils.truncateText(error.message, 200),
                    exceptionText
                )
            }
            
            "${Localization.getAdminMessage("admin.errors.title")}\n" +
            "${Localization.getAdminMessage("admin.errors.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.errors.summary", errorList, ErrorTracker.getErrorCount())
        }
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(errorsText)
            // NO parseMode - error messages can contain any special characters
            .build()
    }
    
    // RATE LIMITS - NO MARKDOWN (contains usernames with special chars)
    fun handleRateLimits(chatId: String): SendMessage {
        val rateLimitStatus = rateLimiter.getRateLimitStatus()
        val overloadedUsers = rateLimiter.getOverloadedUsersWithTokens()
        val recentErrors = ErrorTracker.getRecentErrors(20)
        val rateLimitEvents = recentErrors.filter { it.message.contains("Rate limit exceeded") }
        
        val overloadedText = if (overloadedUsers.isEmpty()) {
            Localization.getAdminMessage("admin.rate.limits.overloaded.none")
        } else {
            val userList = overloadedUsers.entries.joinToString("\n") { (userId, tokens) ->
                val userInfo = database.getUserInfo(userId)
                val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
                Localization.getAdminMessage("admin.rate.limits.overloaded.item", userId, username, tokens)
            }
            Localization.getAdminMessage("admin.rate.limits.overloaded.list", userList)
        }
        
        val eventsText = if (rateLimitEvents.isEmpty()) {
            Localization.getAdminMessage("admin.rate.limits.events.none")
        } else {
            val eventList = rateLimitEvents.take(5).joinToString("\n") { event ->
                val time = event.timestamp.format(DateTimeFormatter.ofPattern("HH:mm"))
                val userId = event.message.substringAfter("user ").substringBefore(" ").toLongOrNull() ?: 0L
                val userInfo = userId?.let { database.getUserInfo(it) }
                val username = userInfo?.username?.let { "(@$it)" } ?: ""
                Localization.getAdminMessage("admin.rate.limits.events.item", time, userId, username)
            }
            Localization.getAdminMessage("admin.rate.limits.events.list", eventList)
        }
        
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val responseText = "${Localization.getAdminMessage("admin.rate.limits.title")}\n" +
                          "${Localization.getAdminMessage("admin.rate.limits.timestamp", timestamp)}\n\n" +
                          Localization.getAdminMessage(
                              "admin.rate.limits.settings",
                              rateLimitStatus["maxTokens"] ?: 0,
                              rateLimitStatus["refillRatePerMinute"] ?: 0,
                              rateLimitStatus["activeUsers"] ?: 0,
                              rateLimitStatus["overloadedUsers"] ?: 0
                          ) + "\n\nOverloaded Users:\n$overloadedText\n\n" +
                          "${Localization.getAdminMessage("admin.rate.limits.events.title")}\n$eventsText\n\n" +
                          Localization.getAdminMessage("admin.rate.limits.management")
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            // NO parseMode - usernames can contain special characters
            .build()
    }
    
    // RATE LIMIT MANAGEMENT - NO MARKDOWN (user input for limits)
    fun handleSetRateLimit(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin set_rate_limit").trim().split(" ")
        
        if (parts.size != 2) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.set.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val perMinute = parts[0].toIntOrNull()
        val burstSize = parts[1].toIntOrNull()
        
        if (perMinute == null || burstSize == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.set.invalid.numbers"))
                // NO parseMode - bulletproof
                .build()
        }
        
        if (!ValidationUtils.isValidRateLimit(perMinute, burstSize)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.set.invalid.range"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val success = rateLimiter.updateRateLimits(burstSize, perMinute)
        
        return if (success) {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.set.success", perMinute, burstSize))
                // NO parseMode - bulletproof
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.set.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    fun handleClearRateLimit(chatId: String, text: String): SendMessage {
        val userIdStr = text.substringAfter("/admin clear_rate_limit").trim()
        
        if (userIdStr.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.clear.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val userId = userIdStr.toLongOrNull()
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.rate.limits.clear.invalid"))
                // NO parseMode - bulletproof
                .build()
        }
        
        rateLimiter.clearUserLimit(userId)
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.rate.limits.clear.success", userId))
            // NO parseMode - bulletproof
            .build()
    }
    
    fun handleClearAllRateLimits(chatId: String): SendMessage {
        rateLimiter.clearAllLimits()
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.rate.limits.clear.all.success"))
            // NO parseMode - bulletproof
            .build()
    }
    
    // LOG LEVEL MANAGEMENT - NO MARKDOWN (dynamic level display)
    fun handleLogLevel(chatId: String, text: String): SendMessage {
        val levelArg = text.substringAfter("/admin log_level").trim()
        
        if (levelArg.isBlank()) {
            // Show current level and options
            val currentLevel = LogManager.getCurrentLogLevel()
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            
            val responseText = "${Localization.getAdminMessage("admin.log.level.title")}\n" +
                              "${Localization.getAdminMessage("admin.log.level.timestamp", timestamp)}\n\n" +
                              "${Localization.getAdminMessage("admin.log.level.current", currentLevel)}\n\n" +
                              "${Localization.getAdminMessage("admin.log.level.available")}\n\n" +
                              Localization.getAdminMessage("admin.log.level.usage")
            
            return SendMessage.builder()
                .chatId(chatId)
                .text(responseText)
                // NO parseMode - bulletproof
                .build()
        }
        
        if (!ValidationUtils.isValidLogLevel(levelArg)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.log.level.invalid"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val success = LogManager.setLogLevel(levelArg.uppercase())
        
        return if (success) {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.log.level.success", levelArg.uppercase()))
                // NO parseMode - bulletproof
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.log.level.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    // SHUTDOWN MANAGEMENT - NO MARKDOWN (user input for reason)
    fun handleShutdownCommand(chatId: String, text: String): SendMessage {
        val reason = text.substringAfter("/admin shutdown").trim()
        val finalReason = if (reason.isBlank()) {
            Localization.getAdminMessage("admin.common.default.reason")
        } else reason
        
        BotShutdownManager.initiateShutdown(finalReason)
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.shutdown.activated", finalReason))
            // NO parseMode - user input reason can contain special chars
            .build()
    }
    
    fun handleCancelShutdown(chatId: String): SendMessage {
        BotShutdownManager.cancelShutdown()
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.shutdown.cancelled"))
            // NO parseMode - bulletproof
            .build()
    }
    
    // CALLBACK HANDLERS - NO MARKDOWN for reliability
    fun handleClearAllLimitsConfirm(chatId: String, messageId: Int): EditMessageText {
        rateLimiter.clearAllLimits()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(Localization.getAdminMessage("admin.rate.limits.clear.all.success"))
            // NO parseMode - bulletproof
            .build()
    }
    
    fun handleClearUserLimit(chatId: String, messageId: Int, userId: Long): EditMessageText {
        rateLimiter.clearUserLimit(userId)
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(Localization.getAdminMessage("admin.rate.limits.clear.success", userId))
            // NO parseMode - bulletproof
            .build()
    }
    
    fun handleSetLogLevelCallback(chatId: String, messageId: Int, level: String): EditMessageText {
        val success = LogManager.setLogLevel(level)
        
        val resultText = if (success) {
            Localization.getAdminMessage("admin.log.level.success", level)
        } else {
            Localization.getAdminMessage("admin.log.level.failed")
        }
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(resultText)
            // NO parseMode - bulletproof
            .build()
    }
}

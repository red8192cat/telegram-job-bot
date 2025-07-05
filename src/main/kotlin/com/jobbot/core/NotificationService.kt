package com.jobbot.core

import com.jobbot.bot.TelegramBot
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger

/**
 * Service for sending admin notifications
 * Extracted from Utils.kt AdminNotificationManager but keeping the same interface
 */
class NotificationService {
    private val logger = getLogger("NotificationService")
    private var botInstance: TelegramBot? = null
    
    fun setBotInstance(bot: TelegramBot) {
        botInstance = bot
        logger.info { "NotificationService: Bot instance set successfully" }
    }
    
    fun notifyRateLimitHit(userId: Long) {
        val bot = botInstance
        if (bot == null) {
            logger.warn { "NotificationService: Cannot send rate limit alert - bot instance not set" }
            return
        }
        
        try {
            logger.info { "NotificationService: Sending rate limit alert for user $userId" }
            
            // Send the rate limit alert (TelegramBot will get user info internally)
            bot.sendRateLimitAlert(userId)
            
            logger.debug { "NotificationService: Rate limit alert sent successfully for user $userId" }
            
        } catch (e: Exception) {
            logger.error(e) { "NotificationService: Failed to send rate limit alert for user $userId" }
            ErrorTracker.logError("ERROR", "Failed to send rate limit alert: ${e.message}", e)
        }
    }
    
    fun sendShutdownReminder() {
        val bot = botInstance
        if (bot == null) {
            logger.warn { "NotificationService: Cannot send shutdown reminder - bot instance not set" }
            return
        }
        
        try {
            logger.info { "NotificationService: Sending shutdown reminder" }
            
            // Send the shutdown reminder
            bot.sendShutdownReminder()
            
            logger.debug { "NotificationService: Shutdown reminder sent successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "NotificationService: Failed to send shutdown reminder" }
            ErrorTracker.logError("ERROR", "Failed to send shutdown reminder: ${e.message}", e)
        }
    }
    
    fun sendAdminNotification(message: String) {
        val bot = botInstance
        if (bot == null) {
            logger.warn { "NotificationService: Cannot send admin notification - bot instance not set" }
            return
        }
        
        try {
            logger.info { "NotificationService: Sending admin notification" }
            
            // Send the admin notification
            bot.sendAdminNotification(message)
            
            logger.debug { "NotificationService: Admin notification sent successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "NotificationService: Failed to send admin notification" }
            ErrorTracker.logError("ERROR", "Failed to send admin notification: ${e.message}", e)
        }
    }
    
    fun isReady(): Boolean {
        return botInstance != null
    }
    
    fun getStatus(): String {
        return if (botInstance != null) {
            "✅ Ready (bot instance connected)"
        } else {
            "❌ Not ready (bot instance not set)"
        }
    }
}

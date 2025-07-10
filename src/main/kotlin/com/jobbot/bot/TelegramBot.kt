package com.jobbot.bot

import com.jobbot.admin.AdminCommandRouter
import com.jobbot.bot.handlers.UserCommandHandler
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.data.models.NotificationMessage
import com.jobbot.data.models.UserInfo
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.monitoring.SystemMonitor
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.infrastructure.shutdown.BotShutdownManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import kotlinx.coroutines.*
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.time.format.DateTimeFormatter

class TelegramBot(
    private val config: BotConfig,
    private val database: Database,
    private val telegramUser: TelegramUser?
) : LongPollingSingleThreadUpdateConsumer {
    
    private val logger = getLogger("TelegramBot")
    private val rateLimiter = RateLimiter(config.rateLimitBurstSize, config.rateLimitMessagesPerMinute)
    private val userCommandHandler = UserCommandHandler(database, rateLimiter)
    
    private val adminCommandRouter = AdminCommandRouter(config, database, telegramUser, rateLimiter)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val telegramClient = OkHttpTelegramClient(config.botToken)
    
    // Notification processor
    private val notificationProcessor = NotificationProcessor(database, rateLimiter, telegramClient)
    
    init {
        adminCommandRouter.setBotInstance(this)
    }
    
    fun getBotToken(): String = config.botToken
    
    fun getBotUsername(): String {
        return System.getenv("BOT_USERNAME") ?: "telegram-job-bot"
    }
    
    override fun consume(update: Update) {
        try {
            when {
                update.hasMessage() && update.message.hasText() -> {
                    handleMessage(update.message)
                }
                update.hasCallbackQuery() -> {
                    handleCallbackQuery(update.callbackQuery)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing update" }
            ErrorTracker.logError("ERROR", "Failed to process update: ${e.message}", e)
        }
    }
    
    private fun handleMessage(message: Message) {
        val userId = message.from.id
        val chatId = message.chatId
        val text = message.text?.trim() ?: return
        
        logger.debug { "Received message from user $userId: $text" }
        
        // Only handle private messages
        if (!message.chat.isUserChat) {
            logger.debug { "Ignoring non-private message from user $userId" }
            return
        }
        
        val response = when {
            // 🔧 SECURITY FIX: Only authorized admins can access admin commands
            // Unauthorized users get "invalid command" - they don't know admin commands exist
            text.contains("/admin") && config.isAdmin(userId) -> 
                adminCommandRouter.handleAdminCommand(message)
            
            // 🔧 SECURITY FIX: All other commands (including unauthorized /admin attempts)
            // are handled as regular user commands - admin commands are invisible to non-admins
            else -> 
                userCommandHandler.handleUserCommand(message)
        }
        
        response?.let { sendResponse(it) }
    }
    
    private fun handleCallbackQuery(callbackQuery: CallbackQuery) {
        val userId = callbackQuery.from.id
        val chatId = callbackQuery.message.chatId
        
        logger.debug { "Received callback query from user $userId: ${callbackQuery.data}" }
        
        // Only handle private callback queries
        if (!callbackQuery.message.chat.isUserChat) {
            logger.debug { "Ignoring non-private callback query from user $userId" }
            return
        }
        
        try {
            // Answer the callback query to remove loading state
            val answerCallback = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQuery.id)
                .build()
            telegramClient.execute(answerCallback)
            
            // 🔧 SECURITY FIX: Only authorized admins can access admin callbacks
            // Unauthorized users get normal user callback handling (which ignores admin callbacks)
            val response = if (config.isAdmin(userId) && callbackQuery.data.startsWith("admin_")) {
                // Handle admin callback queries through the router
                adminCommandRouter.handleAdminCallback(callbackQuery)
            } else {
                // Handle user callback queries (unauthorized admin callbacks are ignored)
                userCommandHandler.handleCallbackQuery(callbackQuery)
            }
            
            response?.let { sendEditResponse(it) }
            
        } catch (e: TelegramApiException) {
            logger.error(e) { "Failed to handle callback query from user $userId" }
            ErrorTracker.logError("ERROR", "Failed to handle callback query: ${e.message}", e)
        }
    }
    
    private fun sendResponse(message: SendMessage) {
        try {
            telegramClient.execute(message)
            logger.debug { "Sent response to chat ${message.chatId}" }
        } catch (e: TelegramApiException) {
            if (e.message?.contains("can't parse entities") == true) {
                logger.warn { "Markdown parsing failed, retrying as plain text for chat ${message.chatId}" }
                // Retry without Markdown
                val plainMessage = SendMessage.builder()
                    .chatId(message.chatId)
                    .text(message.text)
                    // No parseMode - removes Markdown parsing
                    .build()
                try {
                    telegramClient.execute(plainMessage)
                    logger.debug { "Sent plain text response to chat ${message.chatId}" }
                } catch (retryException: TelegramApiException) {
                    logger.error(retryException) { "Failed to send plain text message to chat ${message.chatId}" }
                    ErrorTracker.logError("ERROR", "Failed to send message: ${retryException.message}", retryException)
                }
            } else {
                logger.error(e) { "Failed to send message to chat ${message.chatId}" }
                ErrorTracker.logError("ERROR", "Failed to send message: ${e.message}", e)
            }
        }
    }
    
    private fun sendEditResponse(message: EditMessageText) {
        try {
            telegramClient.execute(message)
            logger.debug { "Edited message in chat ${message.chatId}" }
        } catch (e: TelegramApiException) {
            logger.error(e) { "Failed to edit message in chat ${message.chatId}" }
            ErrorTracker.logError("ERROR", "Failed to edit message: ${e.message}", e)
        }
    }
    
    fun queueNotification(notification: NotificationMessage) {
        notificationProcessor.queueNotification(notification)
    }
    
    fun sendAdminNotification(message: String) {
        scope.launch {
            try {
                // 🔧 UPDATED: Send notifications to ALL authorized admins
                for (adminId in config.authorizedAdminIds) {
                    try {
                        val sendMessage = SendMessage.builder()
                            .chatId(adminId.toString())
                            .text("${Localization.getAdminMessage("admin.notification.prefix")}\n\n$message")
                            // NO parseMode - admin notifications can contain system data with special chars
                            .build()
                        
                        withContext(Dispatchers.IO) {
                            telegramClient.execute(sendMessage)
                        }
                        
                        logger.debug { "Admin notification sent to admin $adminId" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to send admin notification to admin $adminId" }
                        // Continue sending to other admins even if one fails
                    }
                }
                
            } catch (e: Exception) {
                logger.error(e) { "Failed to send admin notifications" }
                ErrorTracker.logError("ERROR", "Failed to send admin notifications: ${e.message}", e)
            }
        }
    }
    
    // NO MARKDOWN - Contains usernames with special characters
    fun sendRateLimitAlert(userId: Long, userInfo: UserInfo? = null) {
        scope.launch {
            try {
                val actualUserInfo = userInfo ?: database.getUserInfo(userId)
                val username = actualUserInfo?.username?.let { "(@$it)" } ?: "(no username)"
                val banStatus = if (actualUserInfo?.isBanned == true) "Banned" else "Not banned"
                
                val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val alertText = Localization.getAdminMessage(
                    "admin.notification.rate.limit.title"
                ) + "\n" + Localization.getAdminMessage(
                    "admin.notification.rate.limit.timestamp", timestamp
                ) + "\n\n" + Localization.getAdminMessage(
                    "admin.notification.rate.limit.details", userId, username, banStatus
                )
                
                val buttons = listOf(
                    InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text("🚫 Ban User")
                            .switchInlineQueryCurrentChat("/admin ban $userId ")
                            .build()
                    ),
                    InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text("🧹 Clear Rate Limit")
                            .switchInlineQueryCurrentChat("/admin clear_rate_limit $userId")
                            .build()
                    )
                )
                
                // Send rate limit alerts to ALL authorized admins
                for (adminId in config.authorizedAdminIds) {
                    try {
                        val sendMessage = SendMessage.builder()
                            .chatId(adminId.toString())
                            .text(alertText)
                            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
                            .build()
                        
                        withContext(Dispatchers.IO) {
                            telegramClient.execute(sendMessage)
                        }
                        
                        logger.debug { "Rate limit alert sent to admin $adminId for user $userId" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to send rate limit alert to admin $adminId" }
                    }
                }
                
            } catch (e: Exception) {
                logger.error(e) { "Failed to send rate limit alerts" }
                ErrorTracker.logError("ERROR", "Failed to send rate limit alerts: ${e.message}", e)
            }
        }
    }
    
    // NO MARKDOWN - Contains system data that might have special characters
    fun sendShutdownReminder() {
        scope.launch {
            try {
                val shutdownStatus = BotShutdownManager.getShutdownStatus()
                val duration = shutdownStatus["duration"] as? String ?: "unknown"
                val health = SystemMonitor.getSystemHealth(database.getActiveUsersCount(), database.getAllChannels().size)
                
                val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val reminderText = Localization.getAdminMessage(
                    "admin.notification.shutdown.reminder.title"
                ) + "\n" + Localization.getAdminMessage(
                    "admin.notification.shutdown.reminder.timestamp", timestamp
                ) + "\n\n" + Localization.getAdminMessage(
                    "admin.notification.shutdown.reminder.details",
                    duration,
                    health.activeUsers,
                    health.monitoredChannels,
                    if (telegramUser?.isConnected() == true) "✅" else "❌",
                    health.errorCount
                )
                
                val buttons = listOf(
                    InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text("🟢 Cancel Shutdown")
                            .switchInlineQueryCurrentChat("/admin cancel_shutdown")
                            .build()
                    ),
                    InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text("📊 View Dashboard")
                            .switchInlineQueryCurrentChat("/admin dashboard")
                            .build()
                    )
                )
                
                // 🔧 UPDATED: Send shutdown reminders to ALL authorized admins
                for (adminId in config.authorizedAdminIds) {
                    try {
                        val sendMessage = SendMessage.builder()
                            .chatId(adminId.toString())
                            .text(reminderText)
                            // NO parseMode - system data might contain special characters
                            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
                            .build()
                        
                        withContext(Dispatchers.IO) {
                            telegramClient.execute(sendMessage)
                        }
                        
                        logger.debug { "Shutdown reminder sent to admin $adminId" }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to send shutdown reminder to admin $adminId" }
                        // Continue sending to other admins even if one fails
                    }
                }
                
            } catch (e: Exception) {
                logger.error(e) { "Failed to send shutdown reminders" }
                ErrorTracker.logError("ERROR", "Failed to send shutdown reminders: ${e.message}", e)
            }
        }
    }
    
    fun notifyRateRestored(userId: Long) {
        scope.launch {
            try {
                val user = database.getUser(userId)
                val language = user?.language ?: "en"
                
                val sendMessage = SendMessage.builder()
                    .chatId(userId.toString())
                    .text(Localization.getMessage(language, "error.rate_limit_restored"))
                    .build()
                
                withContext(Dispatchers.IO) {
                    telegramClient.execute(sendMessage)
                }
                
                logger.debug { "Rate limit restored notification sent to user $userId" }
                
            } catch (e: Exception) {
                logger.debug(e) { "Failed to send rate limit restored notification to user $userId" }
            }
        }
    }
    
    fun getRateLimiter(): RateLimiter = rateLimiter
    
    fun shutdown() {
        logger.info { "Shutting down Telegram bot..." }
        notificationProcessor.shutdown()
        scope.cancel()
    }
    
    fun getQueueSize(): Int = notificationProcessor.getQueueSize()
    
    // Method for compatibility with AdminCommands - proper type handling for v9.x
    fun execute(method: Any) {
        when (method) {
            is SetMyCommands -> telegramClient.execute(method)
            is SendMessage -> telegramClient.execute(method)
            is EditMessageText -> telegramClient.execute(method)
            is AnswerCallbackQuery -> telegramClient.execute(method)
            else -> logger.warn { "Unsupported method type: ${method.javaClass.simpleName}" }
        }
    }
}
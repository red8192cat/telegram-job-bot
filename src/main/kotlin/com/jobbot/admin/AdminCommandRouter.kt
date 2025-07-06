package com.jobbot.admin

import com.jobbot.bot.TelegramBot
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.message.Message

/**
 * Main router for admin commands - coordinates between specialized handlers
 * 🔧 UPDATED: Now supports multiple admins
 */
class AdminCommandRouter(
    private val config: BotConfig,
    private val database: Database,
    private val telegramUser: TelegramUser?,
    private val rateLimiter: RateLimiter
) {
    private val logger = getLogger("AdminCommandRouter")
    
    // Initialize all specialized handlers
    private val dashboardHandler = AdminDashboardHandler(database, config, telegramUser, rateLimiter)
    private val channelHandler = AdminChannelHandler(database, telegramUser)
    private val systemHandler = AdminSystemHandler(database, rateLimiter, telegramUser)
    private val userHandler = AdminUserHandler(database, config)
    private val authHandler = AdminAuthHandler(telegramUser)
    
    private var bot: TelegramBot? = null
    
    fun setBotInstance(botInstance: TelegramBot) {
        this.bot = botInstance
        dashboardHandler.setBotInstance(botInstance)
        channelHandler.setBotInstance(botInstance)
        systemHandler.setBotInstance(botInstance)
    }
    
    fun handleAdminCommand(message: Message): SendMessage? {
        val userId = message.from.id
        val chatId = message.chatId.toString()
        val rawText = message.text?.trim() ?: return null
        
        // 🔧 SECURITY: This method should only be called for authorized admins
        // Unauthorized users are handled as regular user commands in TelegramBot
        if (!config.isAdmin(userId)) {
            logger.error { "SECURITY VIOLATION: handleAdminCommand called for unauthorized user $userId" }
            return null // Should never happen due to TelegramBot routing
        }
        
        // Clean bot mentions from admin commands
        val text = cleanBotMention(rawText)
        
        logger.info { "Admin command received from authorized admin $userId: $text" }
        
        // Route to appropriate handler based on command
        return when {
            // Dashboard commands (default and explicit)
            text == "/admin" || text.startsWith("/admin dashboard") -> 
                dashboardHandler.handleDashboard(chatId)
            
            text.startsWith("/admin help") -> 
                dashboardHandler.handleAdminHelp(chatId)
            
            // Admin management commands
            text.startsWith("/admin list_admins") ->
                handleListAdmins(chatId)
            
            // Channel management commands
            text.startsWith("/admin channels") -> 
                channelHandler.handleChannelsList(chatId)
            
            text.startsWith("/admin add_channel") -> 
                channelHandler.handleAddChannel(chatId, text)
            
            text.startsWith("/admin remove_channel") -> 
                channelHandler.handleRemoveChannel(chatId, text)
            
            text.startsWith("/admin check_all_channels") -> 
                channelHandler.handleCheckAllChannels(chatId)
            
            // System management commands
            text.startsWith("/admin health") -> 
                systemHandler.handleHealth(chatId)
            
            text.startsWith("/admin errors") -> 
                systemHandler.handleErrors(chatId)
            
            text.startsWith("/admin log_level") -> 
                systemHandler.handleLogLevel(chatId, text)
            
            text.startsWith("/admin rate_limits") -> 
                systemHandler.handleRateLimits(chatId)
            
            text.startsWith("/admin set_rate_limit") -> 
                systemHandler.handleSetRateLimit(chatId, text)
            
            text.startsWith("/admin clear_rate_limit") -> 
                systemHandler.handleClearRateLimit(chatId, text)
            
            text.startsWith("/admin clear_all_rate_limits") -> 
                systemHandler.handleClearAllRateLimits(chatId)
            
            text.startsWith("/admin shutdown") -> 
                systemHandler.handleShutdownCommand(chatId, text)
            
            text.startsWith("/admin cancel_shutdown") -> 
                systemHandler.handleCancelShutdown(chatId)
            
            // User management commands
            text.startsWith("/admin banned_users") -> 
                userHandler.handleBannedUsers(chatId)

            text.startsWith("/admin ban") -> 
                userHandler.handleBanUser(chatId, text)
            
            text.startsWith("/admin unban") -> 
                userHandler.handleUnbanUser(chatId, text)
                        
            // Authentication commands
            text.startsWith("/admin auth_code") -> 
                authHandler.handleAuthCode(chatId, text)
            
            text.startsWith("/admin auth_password") -> 
                authHandler.handleAuthPassword(chatId, text)
            
            // Default: show help
            else -> dashboardHandler.handleAdminHelp(chatId)
        }
    }
    
    fun handleAdminCallback(callbackQuery: CallbackQuery): EditMessageText? {
        val userId = callbackQuery.from.id
        val chatId = callbackQuery.message.chatId.toString()
        val messageId = callbackQuery.message.messageId
        val data = callbackQuery.data
        
        // 🔧 SECURITY: This method should only be called for authorized admins
        // Unauthorized users are handled as regular user callbacks in TelegramBot
        if (!config.isAdmin(userId)) {
            logger.error { "SECURITY VIOLATION: handleAdminCallback called for unauthorized user $userId" }
            return null // Should never happen due to TelegramBot routing
        }
        
        logger.info { "Admin callback received from authorized admin $userId: $data" }
        
        // Route to appropriate handler based on callback data
        return when {
            // Main dashboard
            data == "admin_dashboard" -> 
                dashboardHandler.createMainDashboard(chatId, messageId)
            
            // Submenu navigation
            data == "admin_system_menu" -> 
                dashboardHandler.createSystemMenu(chatId, messageId)
            
            data == "admin_channels_menu" -> 
                dashboardHandler.createChannelsMenu(chatId, messageId)
            
            data == "admin_users_menu" -> 
                dashboardHandler.createUsersMenu(chatId, messageId)
            
            // System submenu actions
            data == "admin_system_log_level" -> 
                dashboardHandler.createSystemLogLevelMenu(chatId, messageId)
            
            data == "admin_tdlib_log_level" -> 
                dashboardHandler.createTdlibLogLevelMenu(chatId, messageId)
            
            data == "admin_tdlib_auth" -> 
                dashboardHandler.createTdlibAuthMenu(chatId, messageId)
            
            data == "admin_tdlib_auth_help" -> 
                dashboardHandler.showTdlibAuthHelp(chatId, messageId)
            
            data == "admin_tdlib_auth_status" -> 
                dashboardHandler.showTdlibAuthStatus(chatId, messageId)
            
            // 🔧 NEW: List admins button
            data == "admin_list_admins" ->
                dashboardHandler.createListAdminsPage(chatId, messageId)
            
            // Users submenu actions
            data == "admin_rate_settings" -> 
                dashboardHandler.createRateSettingsMenu(chatId, messageId)
            
            data == "admin_ban_user" -> 
                dashboardHandler.createBanUserMenu(chatId, messageId)
            
            data == "admin_unban_user" -> 
                dashboardHandler.createUnbanUserMenu(chatId, messageId)
            
            // Shutdown actions
            data == "admin_shutdown_confirm" -> 
                dashboardHandler.createShutdownConfirmation(chatId, messageId)
            
            data == "admin_shutdown_execute" -> 
                dashboardHandler.handleShutdownExecute(chatId, messageId)
            
            data == "admin_cancel_shutdown" -> 
                dashboardHandler.handleCancelShutdownCallback(chatId, messageId)
            
            // Log level changes
            data.startsWith("admin_set_system_log_") -> {
                val level = data.substringAfter("admin_set_system_log_")
                dashboardHandler.handleSystemLogLevelChange(chatId, messageId, level)
            }
            
            data.startsWith("admin_set_tdlib_log_") -> {
                val level = data.substringAfter("admin_set_tdlib_log_")
                dashboardHandler.handleTdlibLogLevelChange(chatId, messageId, level)
            }
            
            // Legacy callbacks from old handlers
            data.startsWith("admin_clear_user_") -> {
                val userId = data.substringAfter("admin_clear_user_").toLongOrNull()
                if (userId != null) systemHandler.handleClearUserLimit(chatId, messageId, userId) else null
            }
            
            data.startsWith("admin_ban_user_") -> {
                val userId = data.substringAfter("admin_ban_user_").toLongOrNull()
                if (userId != null) userHandler.createBanConfirmation(chatId, messageId, userId) else null
            }
            
            data.startsWith("admin_unban_user_") -> {
                val userId = data.substringAfter("admin_unban_user_").toLongOrNull()
                if (userId != null) userHandler.handleUnbanUserCallback(chatId, messageId, userId) else null
            }
            
            else -> null
        }
    }
    
    /**
     * 🔧 Handle listing all authorized admins using proper localization
     */
    private fun handleListAdmins(chatId: String): SendMessage {
        val adminList = config.authorizedAdminIds.mapIndexed { index, adminId ->
            Localization.getAdminMessage("admin.list.admins.item", index + 1, adminId.toString())
        }.joinToString("\n")
        
        val responseText = Localization.getAdminMessage(
            "admin.list.admins.response",
            config.getAdminCount(),
            adminList
        )
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            // NO parseMode - bulletproof
            .build()
    }
    
    private fun cleanBotMention(text: String): String {
        val botUsername = System.getenv("BOT_USERNAME") ?: "telegram-job-bot"
        
        // Remove @botname mentions from the beginning
        val cleaned = text.replace(Regex("^@$botUsername\\s*"), "")
        
        logger.debug { "Cleaned admin command: '$text' -> '$cleaned'" }
        return cleaned
    }
}
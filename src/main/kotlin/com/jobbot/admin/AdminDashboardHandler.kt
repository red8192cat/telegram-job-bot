package com.jobbot.admin

import com.jobbot.bot.TelegramBot
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.monitoring.SystemMonitor
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.infrastructure.shutdown.BotShutdownManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.LogManager
import com.jobbot.bot.tdlib.TdlibLogManager
import com.jobbot.bot.tdlib.TdlibAuthManager
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.time.format.DateTimeFormatter

/**
 * Handles the admin dashboard with hierarchical navigation
 * BULLETPROOF: NO MARKDOWN - Works with any system data
 * UPDATED: Premium management merged into users menu - single column layout
 */
class AdminDashboardHandler(
    private val database: Database,
    private val config: BotConfig,
    private val telegramUser: TelegramUser?,
    private val rateLimiter: RateLimiter
) {
    private val logger = getLogger("AdminDashboardHandler")
    
    private var bot: TelegramBot? = null
    
    fun setBotInstance(botInstance: TelegramBot) {
        this.bot = botInstance
    }
    
    // MAIN DASHBOARD - NO MARKDOWN for reliability
    fun handleDashboard(chatId: String): SendMessage {
        val dashboardText = createMainDashboardText()
        val keyboard = createMainDashboardKeyboard()
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(dashboardText)
            // NO parseMode - bulletproof against any system data
            .replyMarkup(keyboard)
            .build()
    }
    
    fun createMainDashboard(chatId: String, messageId: Int): EditMessageText {
        val dashboardText = createMainDashboardText()
        val keyboard = createMainDashboardKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(dashboardText)
            // NO parseMode - bulletproof against any system data
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createMainDashboardText(): String {
        val activeUsers = database.getActiveUsersCount()
        val premiumUsers = database.getPremiumUserCount()
        val channels = database.getAllChannels()
        val rateLimitStatus = rateLimiter.getRateLimitStatus()
        val recentErrors = ErrorTracker.getRecentErrors(5)
        val currentLogLevel = LogManager.getCurrentLogLevel()
        val currentTdlibLogLevel = TdlibLogManager.getCurrentLogLevel()
        
        val shutdownText = if (BotShutdownManager.isShutdownMode()) {
            Localization.getAdminMessage("admin.dashboard.shutdown.active")
        } else ""
        
        val tdlibStatus = if (telegramUser?.isConnected() == true) "✅" else "❌"
        
        val serverTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))

        return "${Localization.getAdminMessage("admin.dashboard.title")}$shutdownText\n\n" +
               "${Localization.getAdminMessage("admin.dashboard.server.time", serverTime)}\n\n" +
               Localization.getAdminMessage(
                   "admin.dashboard.quick.status.with.premium",
                   activeUsers,
                   premiumUsers,
                   channels.size,
                   recentErrors.size,
                   rateLimitStatus["overloadedUsers"] ?: 0,
                   tdlibStatus,
                   currentLogLevel,
                   currentTdlibLogLevel
               )
    }
    
    // UPDATED: Single column layout - premium button removed
    private fun createMainDashboardKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.dashboard.button.system"))
                .callbackData("admin_system_menu")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.dashboard.button.channels"))
                .callbackData("admin_channels_menu")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.dashboard.button.users"))
                .callbackData("admin_users_menu")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.dashboard.button.help"))
                .switchInlineQueryCurrentChat("/admin help")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.dashboard.button.refresh"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // SYSTEM SUBMENU - NO MARKDOWN for reliability (existing code)
    fun createSystemMenu(chatId: String, messageId: Int): EditMessageText {
        val systemText = Localization.getAdminMessage("admin.system.menu.title")
        val keyboard = createSystemMenuKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(systemText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createSystemMenuKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        val currentLogLevel = LogManager.getCurrentLogLevel()
        val currentTdlibLogLevel = TdlibLogManager.getCurrentLogLevel()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.health"))
                .switchInlineQueryCurrentChat("/admin health")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.errors"))
                .switchInlineQueryCurrentChat("/admin errors")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.log.level", currentLogLevel))
                .callbackData("admin_system_log_level")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.tdlib.log.level", currentTdlibLogLevel))
                .callbackData("admin_tdlib_log_level")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.auth"))
                .callbackData("admin_tdlib_auth")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.system.button.list.admins"))
                .callbackData("admin_list_admins")
                .build()
        ))
        
        if (BotShutdownManager.isShutdownMode()) {
            buttons.add(InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.system.button.cancel.shutdown"))
                    .callbackData("admin_cancel_shutdown")
                    .build()
            ))
        } else {
            buttons.add(InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.system.button.emergency.shutdown"))
                    .callbackData("admin_shutdown_confirm")
                    .build()
            ))
        }
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createListAdminsPage(chatId: String, messageId: Int): EditMessageText {
        val adminList = config.authorizedAdminIds.mapIndexed { index, adminId ->
            Localization.getAdminMessage("admin.list.admins.item", index + 1, adminId.toString())
        }.joinToString("\n")
        
        val adminsText = Localization.getAdminMessage(
            "admin.list.admins.page",
            config.getAdminCount(),
            adminList
        )
        
        val keyboard = createListAdminsKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(adminsText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createListAdminsKeyboard(): InlineKeyboardMarkup {
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_system_menu")
                    .build()
            )
        )
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // CHANNELS SUBMENU - NO MARKDOWN for reliability
    fun createChannelsMenu(chatId: String, messageId: Int): EditMessageText {
        val channelsText = Localization.getAdminMessage("admin.channels.menu.title")
        val keyboard = createChannelsMenuKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(channelsText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createChannelsMenuKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.channels.button.list"))
                .switchInlineQueryCurrentChat("/admin channels")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.channels.button.add"))
                .switchInlineQueryCurrentChat("/admin add_channel ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.channels.button.remove"))
                .switchInlineQueryCurrentChat("/admin remove_channel ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.channels.button.check"))
                .switchInlineQueryCurrentChat("/admin check_all_channels")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // UPDATED: USERS SUBMENU with premium management - single column layout
    fun createUsersMenu(chatId: String, messageId: Int): EditMessageText {
        val totalUsers = database.getActiveUsersCount()
        val premiumUsers = database.getPremiumUserCount()
        val bannedUsers = database.getAllBannedUsers().size
        
        val usersText = Localization.getAdminMessage(
            "admin.users.menu.title.with.premium",
            totalUsers,
            premiumUsers,
            bannedUsers
        )
        val keyboard = createUsersMenuKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(usersText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    // UPDATED: Single column layout with premium buttons
    private fun createUsersMenuKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        // Rate limiting
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.rate.limits"))
                .switchInlineQueryCurrentChat("/admin rate_limits")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.rate.settings"))
                .callbackData("admin_rate_settings")
                .build()
        ))
        
        // User moderation
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.banned"))
                .switchInlineQueryCurrentChat("/admin banned_users")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.ban"))
                .callbackData("admin_ban_user")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.unban"))
                .callbackData("admin_unban_user")
                .build()
        ))
        
        // ADDED: Premium management buttons
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.premium.list"))
                .switchInlineQueryCurrentChat("/admin premium_users")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.premium.grant"))
                .callbackData("admin_grant_premium")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.users.button.premium.revoke"))
                .callbackData("admin_revoke_premium")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // UPDATED: Grant Premium Menu - back to users menu
    fun createGrantPremiumMenu(chatId: String, messageId: Int): EditMessageText {
        val grantText = Localization.getAdminMessage("admin.premium.grant.instructions")
        val keyboard = createGrantPremiumKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(grantText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    // UPDATED: Back button goes to users menu
    private fun createGrantPremiumKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.premium.grant.button.enter"))
                .switchInlineQueryCurrentChat("/admin grant_premium ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_users_menu")  // CHANGED: was admin_premium_menu
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // UPDATED: Revoke Premium Menu - back to users menu
    fun createRevokePremiumMenu(chatId: String, messageId: Int): EditMessageText {
        val revokeText = Localization.getAdminMessage("admin.premium.revoke.instructions")
        val keyboard = createRevokePremiumKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(revokeText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    // UPDATED: Back button goes to users menu
    private fun createRevokePremiumKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.premium.revoke.button.enter"))
                .switchInlineQueryCurrentChat("/admin revoke_premium ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_users_menu")  // CHANGED: was admin_premium_menu
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // Rest of the system log level and other methods remain unchanged...
    fun createSystemLogLevelMenu(chatId: String, messageId: Int): EditMessageText {
        val currentLevel = LogManager.getCurrentLogLevel()
        val logLevelText = Localization.getAdminMessage("admin.system.log.level.title") + "\n\n" +
                          Localization.getAdminMessage("admin.system.log.level.current", currentLevel) + "\n\n" +
                          Localization.getAdminMessage("admin.system.log.level.available")
        
        val keyboard = createSystemLogLevelKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(logLevelText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createSystemLogLevelKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        val levels = listOf("DEBUG", "INFO", "WARN", "ERROR")
        
        for (level in levels) {
            buttons.add(InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(level)
                    .callbackData("admin_set_system_log_$level")
                    .build()
            ))
        }
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createTdlibLogLevelMenu(chatId: String, messageId: Int): EditMessageText {
        val currentLevel = TdlibLogManager.getCurrentLogLevel()
        val logLevelText = Localization.getAdminMessage("admin.tdlib.log.level.title") + "\n\n" +
                          Localization.getAdminMessage("admin.tdlib.log.level.current", currentLevel) + "\n\n" +
                          Localization.getAdminMessage("admin.tdlib.log.level.available")
        
        val keyboard = createTdlibLogLevelKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(logLevelText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createTdlibLogLevelKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        val levels = listOf("FATAL", "ERROR", "WARNING", "INFO", "DEBUG", "VERBOSE")
        
        for (level in levels) {
            buttons.add(InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(level)
                    .callbackData("admin_set_tdlib_log_$level")
                    .build()
            ))
        }
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createTdlibAuthMenu(chatId: String, messageId: Int): EditMessageText {
        val authText = if (telegramUser?.isConnected() == true) {
            Localization.getAdminMessage("admin.tdlib.auth.connected")
        } else {
            val authState = TdlibAuthManager.getAuthState()
            when (authState) {
                "WAITING_CODE" -> Localization.getAdminMessage("admin.tdlib.auth.code.needed")
                "WAITING_PASSWORD" -> Localization.getAdminMessage("admin.tdlib.auth.password.needed")
                else -> Localization.getAdminMessage("admin.tdlib.auth.status")
            }
        }
        
        val keyboard = createTdlibAuthKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(authText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createTdlibAuthKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        if (telegramUser?.isConnected() != true) {
            val authState = TdlibAuthManager.getAuthState()
            
            when (authState) {
                "WAITING_CODE" -> {
                    buttons.add(InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(Localization.getAdminMessage("admin.tdlib.auth.button.enter.code"))
                            .switchInlineQueryCurrentChat("/admin auth_code ")
                            .build()
                    ))
                    
                    buttons.add(InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(Localization.getAdminMessage("admin.tdlib.auth.button.help"))
                            .callbackData("admin_tdlib_auth_help")
                            .build()
                    ))
                }
                
                "WAITING_PASSWORD" -> {
                    buttons.add(InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(Localization.getAdminMessage("admin.tdlib.auth.button.enter.password"))
                            .switchInlineQueryCurrentChat("/admin auth_password ")
                            .build()
                    ))
                    
                    buttons.add(InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(Localization.getAdminMessage("admin.tdlib.auth.button.help"))
                            .callbackData("admin_tdlib_auth_help")
                            .build()
                    ))
                }
                
                else -> {
                    buttons.add(InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(Localization.getAdminMessage("admin.tdlib.auth.button.status"))
                            .callbackData("admin_tdlib_auth_status")
                            .build()
                    ))
                }
            }
        } else {
            buttons.add(InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.tdlib.auth.button.status"))
                    .callbackData("admin_tdlib_auth_status")
                    .build()
            ))
        }
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    // Rate settings and other menus remain unchanged...
    fun createRateSettingsMenu(chatId: String, messageId: Int): EditMessageText {
        val rateLimitStatus = rateLimiter.getRateLimitStatus()
        val currentSettings = Localization.getAdminMessage(
            "admin.rate.settings.current",
            rateLimitStatus["maxTokens"] ?: 0,
            rateLimitStatus["refillRatePerMinute"] ?: 0
        ) + "\n\n" + Localization.getAdminMessage("admin.rate.settings.instructions")
        
        val keyboard = createRateSettingsKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(currentSettings)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createRateSettingsKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.rate.settings.button.change"))
                .switchInlineQueryCurrentChat("/admin set_rate_limit ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.rate.settings.button.clear.all"))
                .switchInlineQueryCurrentChat("/admin clear_all_rate_limits")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createBanUserMenu(chatId: String, messageId: Int): EditMessageText {
        val banText = Localization.getAdminMessage("admin.ban.user.instructions")
        val keyboard = createBanUserKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(banText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createBanUserKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.ban.user.button.enter"))
                .switchInlineQueryCurrentChat("/admin ban ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createUnbanUserMenu(chatId: String, messageId: Int): EditMessageText {
        val unbanText = Localization.getAdminMessage("admin.unban.user.instructions")
        val keyboard = createUnbanUserKeyboard()
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(unbanText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createUnbanUserKeyboard(): InlineKeyboardMarkup {
        val buttons = mutableListOf<InlineKeyboardRow>()
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.unban.user.button.enter"))
                .switchInlineQueryCurrentChat("/admin unban ")
                .build()
        ))
        
        buttons.add(InlineKeyboardRow(
            InlineKeyboardButton.builder()
                .text(Localization.getAdminMessage("admin.common.button.back"))
                .callbackData("admin_dashboard")
                .build()
        ))
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    fun createShutdownConfirmation(chatId: String, messageId: Int): EditMessageText {
        val health = SystemMonitor.getSystemHealth(database.getActiveUsersCount(), database.getAllChannels().size)
        
        val confirmText = Localization.getAdminMessage(
            "admin.shutdown.confirm.details",
            health.uptime,
            health.activeUsers,
            health.monitoredChannels
        )
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.shutdown.button.confirm"))
                    .callbackData("admin_shutdown_execute")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.shutdown.button.cancel"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )

        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text("${Localization.getAdminMessage("admin.shutdown.confirm.title")}\n\n$confirmText")
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun handleShutdownExecute(chatId: String, messageId: Int): EditMessageText {
        BotShutdownManager.initiateShutdown("Emergency shutdown via admin dashboard")
        
        val shutdownText = Localization.getAdminMessage("admin.shutdown.executed")
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.shutdown.button.cancel"))
                    .callbackData("admin_cancel_shutdown")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(shutdownText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun handleCancelShutdownCallback(chatId: String, messageId: Int): EditMessageText {
        BotShutdownManager.cancelShutdown()
        
        val cancelText = Localization.getAdminMessage("admin.shutdown.cancelled")
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(cancelText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun handleSystemLogLevelChange(chatId: String, messageId: Int, level: String): EditMessageText {
        val success = LogManager.setLogLevel(level)
        
        val resultText = if (success) {
            Localization.getAdminMessage("admin.system.log.level.success", level)
        } else {
            Localization.getAdminMessage("admin.system.log.level.failed")
        }
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(resultText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun handleTdlibLogLevelChange(chatId: String, messageId: Int, level: String): EditMessageText {
        val success = TdlibLogManager.setLogLevel(level)
        
        val resultText = if (success) {
            Localization.getAdminMessage("admin.tdlib.log.level.success", level)
        } else {
            Localization.getAdminMessage("admin.tdlib.log.level.failed")
        }
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(resultText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun showTdlibAuthHelp(chatId: String, messageId: Int): EditMessageText {
        val helpText = Localization.getAdminMessage("admin.tdlib.auth.help")
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(helpText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    fun showTdlibAuthStatus(chatId: String, messageId: Int): EditMessageText {
        val statusText = if (telegramUser?.isConnected() == true) {
            Localization.getAdminMessage("admin.tdlib.auth.connected.details")
        } else {
            Localization.getAdminMessage("admin.tdlib.auth.disconnected.details")
        }
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(statusText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    // HELP - NO MARKDOWN for reliability
    fun handleAdminHelp(chatId: String): SendMessage {
        val adminHelpText = Localization.getAdminTemplate("admin.help.content")
        
        return SendMessage.builder()
            .chatId(chatId)
            .text("${Localization.getAdminMessage("admin.help.title")}\n\n$adminHelpText")
            // NO parseMode - bulletproof against any help content
            .build()
    }
}
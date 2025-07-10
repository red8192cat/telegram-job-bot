package com.jobbot.admin

import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.time.format.DateTimeFormatter

/**
 * Handles user management and moderation operations
 * UPDATED: Simplified for integrated ban system in User model
 */
class AdminUserHandler(
    private val database: Database,
    private val config: BotConfig
) {
    private val logger = getLogger("AdminUserHandler")
    
    /**
     * Ban user - UPDATED: Uses integrated ban system
     */
    fun handleBanUser(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin ban").trim().split(" ", limit = 2)
        
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.usage"))
                .build()
        }
        
        val userId = parts[0].toLongOrNull()
        val reason = parts[1]
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.invalid.id"))
                .build()
        }
        
        // Prevent banning ANY admin
        if (config.isAdmin(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.cannot.ban.admin"))
                .build()
        }
        
        if (database.isUserBanned(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.already.banned", userId))
                .build()
        }
        
        val success = database.banUser(userId, reason, config.getFirstAdminId())
        
        return if (success) {
            logger.info { "User $userId banned by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.success", userId, reason, timestamp))
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.failed"))
                .build()
        }
    }
    
    /**
     * Unban user - UPDATED: Uses integrated ban system
     */
    fun handleUnbanUser(chatId: String, text: String): SendMessage {
        val userIdStr = text.substringAfter("/admin unban").trim()
        
        if (userIdStr.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.usage"))
                .build()
        }
        
        val userId = userIdStr.toLongOrNull()
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.invalid.id"))
                .build()
        }
        
        if (!database.isUserBanned(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.not.banned", userId))
                .build()
        }
        
        val success = database.unbanUser(userId)
        
        return if (success) {
            logger.info { "User $userId unbanned by admin" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.success", userId, timestamp))
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.failed"))
                .build()
        }
    }
    
    /**
     * Show banned users list - UPDATED: Uses new User model
     */
    fun handleBannedUsers(chatId: String): SendMessage {
        val bannedUsers = database.getAllBannedUsers()
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        val responseText = if (bannedUsers.isEmpty()) {
            "${Localization.getAdminMessage("admin.user.banned.title")}\n" +
            "${Localization.getAdminMessage("admin.user.banned.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.user.banned.empty")
        } else {
            val usersList = bannedUsers.mapIndexed { index, user ->
                val username = "(no username)" // Could be enhanced with TDLib lookup if needed
                val bannedTime = user.bannedAt?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "Unknown"
                val reason = user.banReason ?: "No reason"
                
                Localization.getAdminMessage(
                    "admin.user.banned.item",
                    index + 1,
                    user.telegramId,
                    username,
                    bannedTime,
                    reason
                )
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.user.banned.title")}\n" +
            "${Localization.getAdminMessage("admin.user.banned.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.user.banned.list", usersList, bannedUsers.size)
        }
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            .build()
    }
    
    /**
     * Create ban confirmation dialog
     */
    fun createBanConfirmation(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val userInfo = database.getUserInfo(userId)
        val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
        
        val confirmText = Localization.getAdminMessage(
            "admin.user.ban.confirm.details",
            userId,
            username,
            userId,
            userId
        )
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.button.enter.ban.command"))
                    .switchInlineQueryCurrentChat("/admin ban $userId ")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.button.cancel"))
                    .callbackData("admin_dashboard")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text("${Localization.getAdminMessage("admin.user.ban.confirm.title")}\n\n$confirmText")
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    /**
     * Handle unban via callback
     */
    fun handleUnbanUserCallback(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val success = database.unbanUser(userId)
        
        val resultText = if (success) {
            logger.info { "User $userId unbanned by admin via callback" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            Localization.getAdminMessage("admin.user.unban.success", userId, timestamp)
        } else {
            "❌ Failed to unban user $userId."
        }
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(resultText)
            .build()
    }
}
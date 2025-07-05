package com.jobbot.admin

import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.data.models.UserInfo
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
 * BULLETPROOF: NO MARKDOWN - Works with any usernames, ban reasons, user data
 */
class AdminUserHandler(
    private val database: Database,
    private val config: BotConfig
) {
    private val logger = getLogger("AdminUserHandler")
    
    /**
     * Ban user - NO MARKDOWN (ban reasons can contain special chars)
     */
    fun handleBanUser(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin ban").trim().split(" ", limit = 2)
        
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val userId = parts[0].toLongOrNull()
        val reason = parts[1]
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.invalid.id"))
                // NO parseMode - bulletproof
                .build()
        }
        
        if (userId == config.authorizedAdminId) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.cannot.ban.admin"))
                // NO parseMode - bulletproof
                .build()
        }
        
        if (database.isUserBanned(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.already.banned", userId))
                // NO parseMode - user ID safe
                .build()
        }
        
        val success = database.banUser(userId, reason, config.authorizedAdminId)
        
        return if (success) {
            logger.info { "User $userId banned by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.success", userId, reason, timestamp))
                // NO parseMode - ban reason can contain special chars
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.ban.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    /**
     * Unban user - NO MARKDOWN (handles user data)
     */
    fun handleUnbanUser(chatId: String, text: String): SendMessage {
        val userIdStr = text.substringAfter("/admin unban").trim()
        
        if (userIdStr.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val userId = userIdStr.toLongOrNull()
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.invalid.id"))
                // NO parseMode - bulletproof
                .build()
        }
        
        if (!database.isUserBanned(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.not.banned", userId))
                // NO parseMode - user ID safe
                .build()
        }
        
        val success = database.unbanUser(userId)
        
        return if (success) {
            logger.info { "User $userId unbanned by admin" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.success", userId, timestamp))
                // NO parseMode - user data safe
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.user.unban.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    /**
     * Show banned users list - NO MARKDOWN (usernames can contain special chars)
     */
    fun handleBannedUsers(chatId: String): SendMessage {
        val bannedUsers = database.getAllBannedUsers()
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        val responseText = if (bannedUsers.isEmpty()) {
            "${Localization.getAdminMessage("admin.user.banned.title")}\n" +
            "${Localization.getAdminMessage("admin.user.banned.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.user.banned.empty")
        } else {
            val usersList = bannedUsers.mapIndexed { index, banned ->
                val userInfo = database.getUserInfo(banned.userId)
                val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
                val bannedTime = banned.bannedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                
                Localization.getAdminMessage(
                    "admin.user.banned.item",
                    index + 1,
                    banned.userId,
                    username,
                    bannedTime,
                    banned.reason
                )
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.user.banned.title")}\n" +
            "${Localization.getAdminMessage("admin.user.banned.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.user.banned.list", usersList, bannedUsers.size)
        }
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            // NO parseMode - usernames and ban reasons can contain special chars
            .build()
    }
    
    /**
     * Create ban confirmation dialog - NO MARKDOWN (usernames can contain special chars)
     */
    fun createBanConfirmation(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val userInfo = database.getUserInfo(userId)
        val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
        val commandCount = userInfo?.commandCount ?: 0
        
        val confirmText = Localization.getAdminMessage(
            "admin.user.ban.confirm.details",
            userId,
            username,
            commandCount,
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
            // NO parseMode - usernames can contain special chars
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    /**
     * Handle unban via callback - NO MARKDOWN (user data safe)
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
            // NO parseMode - user data safe
            .build()
    }
}

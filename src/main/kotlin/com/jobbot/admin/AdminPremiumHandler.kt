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
 * Handles premium user management operations
 * BULLETPROOF: NO MARKDOWN - Works with any usernames, premium reasons, user data
 */
class AdminPremiumHandler(
    private val database: Database,
    private val config: BotConfig
) {
    private val logger = getLogger("AdminPremiumHandler")
    
    /**
     * Grant premium to user - NO MARKDOWN (premium reasons can contain special chars)
     */
    fun handleGrantPremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin grant_premium").trim().split(" ", limit = 2)
        
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val userId = parts[0].toLongOrNull()
        val reason = parts[1]
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.invalid.id"))
                // NO parseMode - bulletproof
                .build()
        }
        
        // Check if user already has premium
        if (database.isPremiumUser(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.already.premium", userId))
                // NO parseMode - user ID safe
                .build()
        }
        
        // Check if user exists, create if doesn't exist
        if (!database.premiumRepository.userExists(userId)) {
            logger.info { "Creating user record for new premium user $userId" }
            val newUser = com.jobbot.data.models.User(telegramId = userId, language = "en")
            database.createUser(newUser)
        }
        
        val success = database.grantPremium(userId, config.getFirstAdminId(), reason)
        
        return if (success) {
            logger.info { "Premium granted to user $userId by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.success", userId, reason, timestamp))
                // NO parseMode - premium reason can contain special chars
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    /**
     * Revoke premium from user - NO MARKDOWN (handles user data)
     */
    fun handleRevokePremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin revoke_premium").trim().split(" ", limit = 2)
        
        if (parts.isEmpty() || parts[0].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val userId = parts[0].toLongOrNull()
        val reason = if (parts.size > 1) parts[1] else "Revoked by admin"
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.invalid.id"))
                // NO parseMode - bulletproof
                .build()
        }
        
        if (!database.isPremiumUser(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.not.premium", userId))
                // NO parseMode - user ID safe
                .build()
        }
        
        val success = database.revokePremium(userId, config.getFirstAdminId(), reason)
        
        return if (success) {
            logger.info { "Premium revoked from user $userId by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.success", userId, reason, timestamp))
                // NO parseMode - revoke reason can contain special chars
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.failed"))
                // NO parseMode - bulletproof
                .build()
        }
    }
    
    /**
     * Show premium users list - NO MARKDOWN (usernames can contain special chars)
     */

    fun handlePremiumUsers(chatId: String): SendMessage {
        val premiumUsers = database.getAllPremiumUsers()
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        val responseText = if (premiumUsers.isEmpty()) {
            "${Localization.getAdminMessage("admin.premium.users.title")}\n" +
            "${Localization.getAdminMessage("admin.premium.users.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.premium.users.empty")
        } else {
            val usersList = premiumUsers.mapIndexed { index, premium ->
                val userInfo = database.getUserInfo(premium.userId)
                val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
                val grantedTime = premium.grantedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                val reason = premium.reason ?: Localization.getAdminMessage("admin.common.no.reason")
                
                Localization.getAdminMessage(
                    "admin.premium.users.item",
                    index + 1,
                    premium.userId,
                    username,
                    grantedTime,
                    premium.daysSincePremium,
                    reason
                )
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.premium.users.title")}\n" +
            "${Localization.getAdminMessage("admin.premium.users.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.premium.users.list", usersList, premiumUsers.size)
        }

        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            // NO parseMode - usernames and reasons can contain special chars
            .build()
    }
    
    /**
     * Create grant premium confirmation dialog - NO MARKDOWN (usernames can contain special chars)
     */
    fun createGrantPremiumConfirmation(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val userInfo = database.getUserInfo(userId)
        val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
        val commandCount = userInfo?.commandCount ?: 0
        val isAlreadyPremium = database.isPremiumUser(userId)
        
        val confirmText = if (isAlreadyPremium) {
            Localization.getAdminMessage("admin.premium.grant.already.premium.detailed", userId, username)
        } else {
            Localization.getAdminMessage(
                "admin.premium.grant.confirm.details",
                userId,
                username,
                commandCount,
                userId,
                userId
            )
        }
        
        val buttons = if (isAlreadyPremium) {
            listOf(
                InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                        .text(Localization.getAdminMessage("admin.button.cancel"))
                        .callbackData("admin_premium_menu")
                        .build()
                )
            )
        } else {
            listOf(
                InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                        .text(Localization.getAdminMessage("admin.button.enter.grant.command"))
                        .switchInlineQueryCurrentChat("/admin grant_premium $userId ")
                        .build()
                ),
                InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                        .text(Localization.getAdminMessage("admin.button.cancel"))
                        .callbackData("admin_premium_menu")
                        .build()
                )
            )
        }
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text("${Localization.getAdminMessage("admin.premium.grant.confirm.title")}\n\n$confirmText")
            // NO parseMode - usernames can contain special chars
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    /**
     * Handle revoke premium via callback - NO MARKDOWN (user data safe)
     */
    fun handleRevokePremiumCallback(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val success = database.revokePremium(userId, config.getFirstAdminId(), "Revoked via admin dashboard")
        
        val resultText = if (success) {
            logger.info { "Premium revoked from user $userId by admin via callback" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            Localization.getAdminMessage("admin.premium.revoke.success", userId, "Revoked via admin dashboard", timestamp)
        } else {
            Localization.getAdminMessage("admin.premium.revoke.failed")
        }
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text(Localization.getAdminMessage("admin.common.button.back"))
                    .callbackData("admin_premium_menu")
                    .build()
            )
        )
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(resultText)
            // NO parseMode - user data safe
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
}

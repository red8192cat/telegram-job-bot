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
 * UPDATED: Reason is optional, better username resolution with realistic limitations
 */
class AdminPremiumHandler(
    private val database: Database,
    private val config: BotConfig
) {
    private val logger = getLogger("AdminPremiumHandler")
    
    /**
     * Grant premium to user - UPDATED: Support both user ID and @username, reason is optional
     */
    fun handleGrantPremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin grant_premium").trim().split(" ", limit = 2)
        
        if (parts.isEmpty() || parts[0].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.usage"))
                .build()
        }
        
        val userInput = parts[0]
        val reason = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "Premium access granted by admin"
        
        // Resolve user ID from input (could be ID or @username)
        val userId = resolveUserId(userInput)
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.invalid.user", userInput))
                .build()
        }
        
        // Check if user already has premium
        if (database.isPremiumUser(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.already.premium", userId))
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
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.failed"))
                .build()
        }
    }
    
    /**
     * Revoke premium from user - UPDATED: Support both user ID and @username, reason is optional
     */
    fun handleRevokePremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin revoke_premium").trim().split(" ", limit = 2)
        
        if (parts.isEmpty() || parts[0].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.usage"))
                .build()
        }
        
        val userInput = parts[0]
        val reason = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "Premium access revoked by admin"
        
        // Resolve user ID from input
        val userId = resolveUserId(userInput)
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.invalid.user", userInput))
                .build()
        }
        
        if (!database.isPremiumUser(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.not.premium", userId))
                .build()
        }
        
        val success = database.revokePremium(userId, config.getFirstAdminId(), reason)
        
        return if (success) {
            logger.info { "Premium revoked from user $userId by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.success", userId, reason, timestamp))
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.failed"))
                .build()
        }
    }
    
    /**
     * Show premium users list - UPDATED: Fixed number formatting for user IDs
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
                
                // Build user item without number formatting placeholders
                "${index + 1}. 👤 User: ${premium.userId} $username\n" +
                "   💎 Premium since: $grantedTime (${premium.daysSincePremium} days ago)\n" +
                "   📝 Reason: $reason"
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.premium.users.title")}\n" +
            "${Localization.getAdminMessage("admin.premium.users.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.premium.users.list", usersList, premiumUsers.size)
        }

        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            .build()
    }
    
    /**
     * Resolve user ID from input (supports both user ID and @username)
     * UPDATED: Better error handling and realistic expectations
     */
    private fun resolveUserId(input: String): Long? {
        return when {
            // If input is numeric, treat as user ID
            input.matches(Regex("^[0-9]+$")) -> {
                input.toLongOrNull()
            }
            
            // If input starts with @, treat as username
            input.startsWith("@") -> {
                val username = input.substring(1) // Remove @
                findUserByUsername(username)
            }
            
            // Try as username without @
            else -> {
                findUserByUsername(input)
            }
        }
    }
    
    /**
     * Find user ID by username
     * UPDATED: More realistic implementation with better logging
     */
    private fun findUserByUsername(username: String): Long? {
        return try {
            // LIMITATION: We can only find users who have interacted with our bot before
            // The Telegram Bot API doesn't provide a way to lookup arbitrary users by username
            
            val allUsers = database.getAllUsers()
            
            for (user in allUsers) {
                val userInfo = database.getUserInfo(user.telegramId)
                if (userInfo?.username?.equals(username, ignoreCase = true) == true) {
                    logger.info { "Found user @$username with ID ${user.telegramId}" }
                    return user.telegramId
                }
            }
            
            logger.warn { "User with username '@$username' not found in bot's database" }
            logger.info { "LIMITATION: Bot can only find users who have previously interacted with it" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Error resolving username @$username" }
            null
        }
    }
    
    /**
     * Create grant premium confirmation dialog
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
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    /**
     * Handle revoke premium via callback
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
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
}
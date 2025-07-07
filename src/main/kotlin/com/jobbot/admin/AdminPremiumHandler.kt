package com.jobbot.admin

import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.time.format.DateTimeFormatter

/**
 * Handles premium user management operations
 * BULLETPROOF: NO MARKDOWN - Works with any usernames, premium reasons, user data
 * UPDATED: Uses TDLib for real-time username resolution + supports direct IDs
 */
class AdminPremiumHandler(
    private val database: Database,
    private val config: BotConfig,
    private val telegramUser: TelegramUser?  // NEW: Add TelegramUser for username resolution
) {
    private val logger = getLogger("AdminPremiumHandler")
    
    /**
     * Grant premium to user - UPDATED: TDLib username resolution + direct ID support
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
        val userId = resolveUserIdViaTdlib(userInput)
        
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
                .text(Localization.getAdminMessage("admin.premium.grant.already.premium", userId.toString()))
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
                .text(Localization.getAdminMessage("admin.premium.grant.success", userId.toString(), reason, timestamp))
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.failed"))
                .build()
        }
    }
    
    /**
     * Revoke premium from user - UPDATED: TDLib username resolution + direct ID support
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
        val userId = resolveUserIdViaTdlib(userInput)
        
        if (userId == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.invalid.user", userInput))
                .build()
        }
        
        if (!database.isPremiumUser(userId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.not.premium", userId.toString()))
                .build()
        }
        
        val success = database.revokePremium(userId, config.getFirstAdminId(), reason)
        
        return if (success) {
            logger.info { "Premium revoked from user $userId by admin: $reason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.success", userId.toString(), reason, timestamp))
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.revoke.failed"))
                .build()
        }
    }
    
    /**
     * NEW: Resolve user ID from input using TDLib for @username or direct ID parsing
     * Supports both direct IDs and TDLib username resolution
     */
    private fun resolveUserIdViaTdlib(input: String): Long? {
        return when {
            // If input is numeric, treat as direct user ID
            input.matches(Regex("^[0-9]+$")) -> {
                val userId = input.toLongOrNull()
                if (userId != null && userId > 0) {
                    logger.info { "Direct user ID provided: $userId" }
                    userId
                } else {
                    logger.warn { "Invalid direct user ID: $input" }
                    null
                }
            }
            
            // If input starts with @, use TDLib to resolve username
            input.startsWith("@") -> {
                val username = input.substring(1) // Remove @
                resolveUsernameViaTdlib(username)
            }
            
            // Try as username without @
            else -> {
                resolveUsernameViaTdlib(input)
            }
        }
    }
    
    /**
     * NEW: Use TDLib to resolve username to user ID in real-time
     */
    private fun resolveUsernameViaTdlib(username: String): Long? {
        if (telegramUser == null) {
            logger.warn { "TDLib not available for username resolution: @$username" }
            return null
        }
        
        if (!telegramUser.isConnected()) {
            logger.warn { "TDLib not connected for username resolution: @$username" }
            return null
        }
        
        return try {
            logger.info { "Resolving username @$username via TDLib..." }
            
            // Use TDLib to look up the user by username
            val userInfo = runBlocking {
                telegramUser.lookupUserByUsername(username)
            }
            
            if (userInfo.found && userInfo.userId != null) {
                logger.info { "TDLib resolved @$username to user ID: ${userInfo.userId}" }
                userInfo.userId
            } else {
                logger.warn { "TDLib could not resolve @$username: ${userInfo.error ?: "User not found"}" }
                null
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error resolving username @$username via TDLib" }
            null
        }
    }
    
    /**
     * Show premium users list - Same as before, no changes needed
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
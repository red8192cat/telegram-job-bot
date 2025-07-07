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
 * UPDATED: Back buttons now go to users menu instead of premium menu
 */
class AdminPremiumHandler(
    private val database: Database,
    private val config: BotConfig,
    private val telegramUser: TelegramUser?
) {
    private val logger = getLogger("AdminPremiumHandler")
    
    /**
     * Grant premium to user - UPDATED: Supports duration and TDLib username resolution
     */
    fun handleGrantPremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin grant_premium").trim().split(" ", limit = 3)
        
        if (parts.isEmpty() || parts[0].isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.usage"))
                .build()
        }
        
        val userInput = parts[0]
        val (durationDays, reason) = when {
            parts.size == 1 -> null to "Premium access"
            parts.size == 2 -> {
                val second = parts[1]
                if (second.toIntOrNull() != null) {
                    second.toInt() to "Premium access"
                } else {
                    null to second
                }
            }
            parts.size >= 3 -> {
                val second = parts[1]
                if (second.toIntOrNull() != null) {
                    second.toInt() to parts[2]
                } else {
                    null to "${parts[1]} ${parts[2]}"
                }
            }
            else -> null to "Premium access"
        }
        
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
        if (database.getUser(userId) == null) {
            logger.info { "Creating user record for new premium user $userId" }
            val newUser = com.jobbot.data.models.User(telegramId = userId, language = "en")
            database.createUser(newUser)
        }
        
        val enhancedReason = "$reason (granted by admin ${config.getFirstAdminId()})"
        val success = database.grantPremium(userId, enhancedReason, durationDays)
        
        return if (success) {
            logger.info { "Premium granted to user $userId by admin: $enhancedReason" }
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val expirationText = if (durationDays != null) " (expires in $durationDays days)" else " (permanent)"
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.premium.grant.success", userId.toString(), enhancedReason + expirationText, timestamp))
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
        
        val success = database.revokePremium(userId)
        
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
     * Extend premium for existing user
     */
    fun handleExtendPremium(chatId: String, text: String): SendMessage {
        val parts = text.substringAfter("/admin extend_premium").trim().split(" ")
        
        if (parts.size != 2) {
            return SendMessage.builder()
                .chatId(chatId)
                .text("❌ USAGE: /admin extend_premium <user_id_or_username> <days>\n\n💡 EXAMPLE: /admin extend_premium @romanepic 15")
                .build()
        }
        
        val userInput = parts[0]
        val additionalDays = parts[1].toIntOrNull()
        
        if (additionalDays == null || additionalDays <= 0) {
            return SendMessage.builder()
                .chatId(chatId)
                .text("❌ Invalid number of days. Must be a positive integer.")
                .build()
        }
        
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
                .text("⚠️ User $userId does not have premium status.")
                .build()
        }
        
        val success = database.extendPremium(userId, additionalDays)
        
        return if (success) {
            logger.info { "Premium extended for user $userId by $additionalDays days" }
            SendMessage.builder()
                .chatId(chatId)
                .text("✅ PREMIUM EXTENDED SUCCESSFULLY\n\n👤 User ID: $userId\n📅 Extended by: $additionalDays days\n👨‍💼 Extended by: Admin")
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text("❌ Failed to extend premium.")
                .build()
        }
    }
    
    /**
     * Resolve user ID from input using TDLib for @username or direct ID parsing
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
     * Use TDLib to resolve username to user ID in real-time
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
     * Show premium users list - UPDATED: Uses new User model
     */
    fun handlePremiumUsers(chatId: String): SendMessage {
        val premiumUsers = database.getAllPremiumUsers()
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        
        val responseText = if (premiumUsers.isEmpty()) {
            "${Localization.getAdminMessage("admin.premium.users.title")}\n" +
            "${Localization.getAdminMessage("admin.premium.users.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.premium.users.empty")
        } else {
            val usersList = premiumUsers.mapIndexed { index, user ->
                val grantedTime = user.premiumGrantedAt?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "Unknown"
                val reason = user.premiumReason ?: "No reason"
                val expirationText = when {
                    user.premiumExpiresAt == null -> "♾️ Never expires (permanent)"
                    user.premiumExpiresAt.isAfter(java.time.LocalDateTime.now()) -> {
                        val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDateTime.now(), user.premiumExpiresAt)
                        "⏰ Expires in $daysLeft days (${user.premiumExpiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))})"
                    }
                    else -> "❌ EXPIRED (${user.premiumExpiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))})"
                }
                
                "${index + 1}. 👤 User: ${user.telegramId}\n" +
                "   💎 Premium since: $grantedTime\n" +
                "   $expirationText\n" +
                "   📝 Reason: $reason"
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.premium.users.title")}\n" +
            "${Localization.getAdminMessage("admin.premium.users.timestamp", timestamp)}\n\n" +
            "$usersList\n\n📊 Total premium users: ${premiumUsers.size}"
        }

        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            .build()
    }
    
    /**
     * Create grant premium confirmation dialog
     * UPDATED: Back button goes to users menu
     */
    fun createGrantPremiumConfirmation(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val userInfo = database.getUserInfo(userId)
        val username = userInfo?.username?.let { "(@$it)" } ?: Localization.getAdminMessage("admin.common.no.username")
        val isAlreadyPremium = database.isPremiumUser(userId)
        
        val confirmText = if (isAlreadyPremium) {
            Localization.getAdminMessage("admin.premium.grant.already.premium.detailed", userId, username)
        } else {
            Localization.getAdminMessage(
                "admin.premium.grant.confirm.details",
                userId,
                username,
                0, // No command count in simplified model
                userId,
                userId
            )
        }
        
        val buttons = if (isAlreadyPremium) {
            listOf(
                InlineKeyboardRow(
                    InlineKeyboardButton.builder()
                        .text(Localization.getAdminMessage("admin.button.cancel"))
                        .callbackData("admin_users_menu")  // CHANGED: was admin_premium_menu
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
                        .callbackData("admin_users_menu")  // CHANGED: was admin_premium_menu
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
     * UPDATED: Back button goes to users menu
     */
    fun handleRevokePremiumCallback(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val success = database.revokePremium(userId)
        
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
                    .callbackData("admin_users_menu")  // CHANGED: was admin_premium_menu
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
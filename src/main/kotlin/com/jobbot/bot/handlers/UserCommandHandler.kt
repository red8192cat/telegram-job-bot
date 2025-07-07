package com.jobbot.bot.handlers

import com.jobbot.data.Database
import com.jobbot.data.models.User
import com.jobbot.infrastructure.security.RateLimiter
import com.jobbot.infrastructure.shutdown.BotShutdownManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.ValidationUtils
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

class UserCommandHandler(
    private val database: Database,
    private val rateLimiter: RateLimiter
) {
    private val logger = getLogger("UserCommandHandler")
    
    fun handleUserCommand(message: Message): SendMessage? {
        val userId = message.from.id
        val chatId = message.chatId.toString()
        val rawText = message.text?.trim() ?: return null
        
        val text = cleanBotMention(rawText)
        
        // Check if user is banned first
        if (database.isUserBanned(userId)) {
            val user = database.getUser(userId)
            val reason = user?.banReason ?: "No reason provided"
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getMessage(getUserLanguage(userId), "error.user_banned", reason))
                .build()
        }
        
        // Check if bot is in shutdown mode
        if (BotShutdownManager.isShutdownMode()) {
            val language = getUserLanguage(userId)
            return SendMessage.builder()
                .chatId(chatId)
                .text(BotShutdownManager.getShutdownMessage(language))
                .build()
        }
        
        // Check rate limiting
        if (!rateLimiter.isAllowed(userId)) {
            return createResponse(chatId, getUserLanguage(userId), "error.rate_limit")
        }
        
        // Update user activity
        database.updateUserActivity(userId)
        
        return when {
            text.startsWith("/start") -> handleStart(chatId, userId)
            text.startsWith("/help") -> handleHelp(chatId, userId)
            text.startsWith("/keywords") -> handleKeywords(chatId, userId, text)
            text.startsWith("/ignore_keywords") -> handleIgnoreKeywords(chatId, userId, text)
            text.startsWith("/language") -> handleLanguage(chatId, userId)
            else -> handleInvalidCommand(chatId, userId)
        }
    }
    
    private fun cleanBotMention(text: String): String {
        val botUsername = System.getenv("BOT_USERNAME") ?: "telegram-job-bot"
        val cleaned = text.replace(Regex("^@$botUsername\\s*"), "")
        logger.debug { "Cleaned command: '$text' -> '$cleaned'" }
        return cleaned
    }
    
    fun handleCallbackQuery(callbackQuery: CallbackQuery): EditMessageText? {
        val userId = callbackQuery.from.id
        val chatId = callbackQuery.message.chatId.toString()
        val messageId = callbackQuery.message.messageId
        val data = callbackQuery.data
        
        // Check if user is banned first
        if (database.isUserBanned(userId)) {
            return null
        }
        
        // Check if bot is in shutdown mode
        if (BotShutdownManager.isShutdownMode()) {
            return null
        }
        
        // Check rate limiting
        if (!rateLimiter.isAllowed(userId)) {
            return null
        }
        
        // Update user activity
        database.updateUserActivity(userId)
        
        return when (data) {
            "main_menu" -> createMainMenu(chatId, messageId, userId)
            "keywords_dashboard" -> createKeywordsDashboard(chatId, messageId, userId)
            "language_picker" -> createLanguagePicker(chatId, messageId, userId)
            "help_page" -> createHelpPage(chatId, messageId, userId)
            "clear_all" -> handleClearAll(chatId, messageId, userId)
            "lang_en" -> handleLanguageChange(chatId, messageId, userId, "en")
            "lang_ru" -> handleLanguageChange(chatId, messageId, userId, "ru")
            else -> null
        }
    }
    
    private fun handleStart(chatId: String, userId: Long): SendMessage {
        val language = getUserLanguage(userId)
        
        // Create user if doesn't exist
        val existingUser = database.getUser(userId)
        if (existingUser == null) {
            val newUser = User(telegramId = userId, language = language)
            database.createUser(newUser)
            logger.info { "New user created: $userId" }
        }
        
        return createMainMenuMessage(chatId, language)
    }
    
    private fun handleHelp(chatId: String, userId: Long): SendMessage {
        val language = getUserLanguage(userId)
        val helpText = Localization.getTemplate(language, "help.full")
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🏠 " + Localization.getMessage(language, "button.main_menu"))
                    .callbackData("main_menu")
                    .build()
            )
        )
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(helpText)
            // NO parseMode - bulletproof
            .replyMarkup(InlineKeyboardMarkup.builder().keyboard(buttons).build())
            .build()
    }
    
    private fun handleKeywords(chatId: String, userId: Long, text: String): SendMessage {
        val language = getUserLanguage(userId)
        val keywordsText = text.substringAfter("/keywords").trim()
        
        return if (keywordsText.isBlank()) {
            // Show keywords dashboard
            createKeywordsDashboardMessage(chatId, language, userId)
        } else {
            // Set new keywords
            val sanitizedKeywords = ValidationUtils.sanitizeKeywords(keywordsText)
            
            val user = database.getUser(userId) ?: User(telegramId = userId, language = language)
            val updatedUser = user.copy(keywords = sanitizedKeywords.ifBlank { null })
            
            val success = if (database.getUser(userId) != null) {
                database.updateUser(updatedUser)
            } else {
                database.createUser(updatedUser)
            }
            
            if (success) {
                val messageKey = if (sanitizedKeywords.isBlank()) "keywords.cleared" else "keywords.set"
                val responseText = Localization.getMessage(language, messageKey)
                
                // Show updated dashboard
                val dashboardText = createKeywordsDashboardText(language, userId)
                val keyboard = createKeywordsDashboardKeyboard(language, userId)
                
                SendMessage.builder()
                    .chatId(chatId)
                    .text("$responseText\n\n$dashboardText")
                    // NO parseMode - user keywords can contain special chars
                    .replyMarkup(keyboard)
                    .build()
            } else {
                createResponse(chatId, language, "error.generic")
            }
        }
    }
    
    private fun handleIgnoreKeywords(chatId: String, userId: Long, text: String): SendMessage {
        val language = getUserLanguage(userId)
        val ignoreKeywordsText = text.substringAfter("/ignore_keywords").trim()
        
        return if (ignoreKeywordsText.isBlank()) {
            // Show keywords dashboard
            createKeywordsDashboardMessage(chatId, language, userId)
        } else {
            // Set new ignore keywords
            val sanitizedIgnoreKeywords = ValidationUtils.sanitizeKeywords(ignoreKeywordsText)
            
            val user = database.getUser(userId) ?: User(telegramId = userId, language = language)
            val updatedUser = user.copy(ignoreKeywords = sanitizedIgnoreKeywords.ifBlank { null })
            
            val success = if (database.getUser(userId) != null) {
                database.updateUser(updatedUser)
            } else {
                database.createUser(updatedUser)
            }
            
            if (success) {
                val messageKey = if (sanitizedIgnoreKeywords.isBlank()) "ignore_keywords.cleared" else "ignore_keywords.set"
                val responseText = Localization.getMessage(language, messageKey)
                
                // Show updated dashboard
                val dashboardText = createKeywordsDashboardText(language, userId)
                val keyboard = createKeywordsDashboardKeyboard(language, userId)
                
                SendMessage.builder()
                    .chatId(chatId)
                    .text("$responseText\n\n$dashboardText")
                    // NO parseMode - user input can contain special chars
                    .replyMarkup(keyboard)
                    .build()
            } else {
                createResponse(chatId, language, "error.generic")
            }
        }
    }
    
    private fun handleLanguage(chatId: String, userId: Long): SendMessage {
        val language = getUserLanguage(userId)
        val languageText = Localization.getMessage(language, "language.picker.title")
        val keyboard = createLanguagePickerKeyboard(language)
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(languageText)
            .replyMarkup(keyboard)
            .build()
    }
    
    fun handleInvalidCommand(chatId: String, userId: Long): SendMessage {
        val language = getUserLanguage(userId)
        return createResponse(chatId, language, "error.invalid_command")
    }
    
    // Callback query handlers
    
    private fun createMainMenu(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val language = getUserLanguage(userId)
        val welcomeText = Localization.getMessage(language, "welcome.main_menu")
        val keyboard = createMainMenuKeyboard(language)
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(welcomeText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createKeywordsDashboard(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val language = getUserLanguage(userId)
        val dashboardText = createKeywordsDashboardText(language, userId)
        val keyboard = createKeywordsDashboardKeyboard(language, userId)
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(dashboardText)
            // NO parseMode - user keywords can contain special chars
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createLanguagePicker(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val language = getUserLanguage(userId)
        val languageText = Localization.getMessage(language, "language.picker.title")
        val keyboard = createLanguagePickerKeyboard(language)
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text(languageText)
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createHelpPage(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val language = getUserLanguage(userId)
        val helpText = Localization.getTemplate(language, "help.full")
        
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🏠 " + Localization.getMessage(language, "button.main_menu"))
                    .callbackData("main_menu")
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
    
    private fun handleClearAll(chatId: String, messageId: Int, userId: Long): EditMessageText {
        val language = getUserLanguage(userId)
        
        val user = database.getUser(userId)
        if (user != null) {
            val clearedUser = user.copy(keywords = null, ignoreKeywords = null)
            database.updateUser(clearedUser)
        }
        
        val successText = Localization.getMessage(language, "keywords.all_cleared")
        val dashboardText = createKeywordsDashboardText(language, userId)
        val keyboard = createKeywordsDashboardKeyboard(language, userId)
        
        return EditMessageText.builder()
            .chatId(chatId)
            .messageId(messageId)
            .text("$successText\n\n$dashboardText")
            // NO parseMode - user data can contain special chars
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun handleLanguageChange(chatId: String, messageId: Int, userId: Long, newLanguage: String): EditMessageText {
        val user = database.getUser(userId) ?: User(telegramId = userId, language = newLanguage)
        val updatedUser = user.copy(language = newLanguage)
        
        val success = if (database.getUser(userId) != null) {
            database.updateUser(updatedUser)
        } else {
            database.createUser(updatedUser)
        }
        
        if (success) {
            val confirmationText = Localization.getMessage(newLanguage, "language.changed")
            val mainMenuText = Localization.getMessage(newLanguage, "welcome.main_menu")
            val keyboard = createMainMenuKeyboard(newLanguage)
            
            return EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text("$confirmationText\n\n$mainMenuText")
                // NO parseMode - bulletproof
                .replyMarkup(keyboard)
                .build()
        } else {
            return createLanguagePicker(chatId, messageId, userId)
        }
    }
    
    // Helper methods
    
    private fun createMainMenuMessage(chatId: String, language: String): SendMessage {
        val welcomeText = Localization.getMessage(language, "welcome.main_menu")
        val keyboard = createMainMenuKeyboard(language)
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(welcomeText)
            // NO parseMode - bulletproof
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createKeywordsDashboardMessage(chatId: String, language: String, userId: Long): SendMessage {
        val dashboardText = createKeywordsDashboardText(language, userId)
        val keyboard = createKeywordsDashboardKeyboard(language, userId)
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(dashboardText)
            // NO parseMode - user data can contain special chars
            .replyMarkup(keyboard)
            .build()
    }
    
    private fun createMainMenuKeyboard(language: String): InlineKeyboardMarkup {
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🎯 " + Localization.getMessage(language, "button.keywords"))
                    .callbackData("keywords_dashboard")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🌐 " + Localization.getMessage(language, "button.language"))
                    .callbackData("language_picker")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("❓ " + Localization.getMessage(language, "button.help"))
                    .callbackData("help_page")
                    .build()
            )
        )
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    private fun createKeywordsDashboardText(language: String, userId: Long): String {
        val user = database.getUser(userId)
        val currentKeywords = user?.keywords ?: Localization.getMessage(language, "not_set")
        val currentIgnoreKeywords = user?.ignoreKeywords ?: Localization.getMessage(language, "not_set")
        val currentLanguage = Localization.getMessage(language, "language.${user?.language ?: "en"}")
        
        return Localization.getMessage(
            language, "keywords.dashboard",
            currentKeywords,
            currentIgnoreKeywords,
            currentLanguage
        )
    }
    
    private fun createKeywordsDashboardKeyboard(language: String, userId: Long): InlineKeyboardMarkup {
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("✏️ " + Localization.getMessage(language, "button.edit_keywords"))
                    .switchInlineQueryCurrentChat("/keywords ")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🚫 " + Localization.getMessage(language, "button.edit_ignore"))
                    .switchInlineQueryCurrentChat("/ignore_keywords ")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🗑️ " + Localization.getMessage(language, "button.clear_all"))
                    .callbackData("clear_all")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🏠 " + Localization.getMessage(language, "button.main_menu"))
                    .callbackData("main_menu")
                    .build()
            )
        )
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    private fun createLanguagePickerKeyboard(language: String): InlineKeyboardMarkup {
        val buttons = listOf(
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🇺🇸 English")
                    .callbackData("lang_en")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🇷🇺 Русский")
                    .callbackData("lang_ru")
                    .build()
            ),
            InlineKeyboardRow(
                InlineKeyboardButton.builder()
                    .text("🏠 " + Localization.getMessage(language, "button.main_menu"))
                    .callbackData("main_menu")
                    .build()
            )
        )
        
        return InlineKeyboardMarkup.builder().keyboard(buttons).build()
    }
    
    private fun getUserLanguage(userId: Long): String {
        return database.getUser(userId)?.language ?: "en"
    }
    
    private fun createResponse(chatId: String, language: String, messageKey: String, vararg args: Any): SendMessage {
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getMessage(language, messageKey, *args))
            .build()
    }
}

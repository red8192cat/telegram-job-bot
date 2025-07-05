package com.jobbot.admin

import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import org.telegram.telegrambots.meta.api.methods.send.SendMessage

/**
 * Handles TDLib authentication operations 
 * BULLETPROOF: NO MARKDOWN - Works with any auth responses
 */
class AdminAuthHandler(
    private val telegramUser: TelegramUser?
) {
    private val logger = getLogger("AdminAuthHandler")
    
    fun handleAuthCode(chatId: String, text: String): SendMessage {
        val code = text.substringAfter("/admin auth_code").trim()
        
        if (code.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.auth.code.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val result = telegramUser?.submitAuthCode(code) ?: Localization.getAdminMessage("admin.auth.code.unavailable")
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.auth.code.submitted", result))
            // NO parseMode - auth responses can contain any text
            .build()
    }
    
    fun handleAuthPassword(chatId: String, text: String): SendMessage {
        val password = text.substringAfter("/admin auth_password").trim()
        
        if (password.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.auth.password.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val result = telegramUser?.submitAuthPassword(password) ?: Localization.getAdminMessage("admin.auth.password.unavailable")
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage("admin.auth.password.submitted", result))
            // NO parseMode - auth responses can contain any text
            .build()
    }
}

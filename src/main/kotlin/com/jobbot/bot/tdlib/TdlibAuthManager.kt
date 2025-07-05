package com.jobbot.bot.tdlib

import com.jobbot.shared.getLogger

/**
 * Manages TDLib authentication state tracking
 */
object TdlibAuthManager {
    private val logger = getLogger("TdlibAuthManager")
    
    // In-memory storage of current authentication state
    private var currentAuthState: String = "UNKNOWN"
    
    fun updateAuthState(state: String) {
        logger.debug { "TDLib auth state changed: $currentAuthState -> $state" }
        currentAuthState = state
    }
    
    fun getAuthState(): String = currentAuthState
    
    fun isWaitingForCode(): Boolean = currentAuthState == "WAITING_CODE"
    
    fun isWaitingForPassword(): Boolean = currentAuthState == "WAITING_PASSWORD"
    
    fun isConnected(): Boolean = currentAuthState == "READY"
    
    fun getAuthStateDescription(): String {
        return when (currentAuthState) {
            "UNKNOWN" -> "Authentication state unknown"
            "WAITING_PHONE" -> "Waiting for phone number"
            "WAITING_CODE" -> "Waiting for SMS/Telegram code"
            "WAITING_PASSWORD" -> "Waiting for 2FA password"
            "READY" -> "Successfully authenticated and connected"
            "LOGGING_OUT" -> "Logging out"
            "CLOSED" -> "Connection closed"
            else -> "Unknown state: $currentAuthState"
        }
    }
    
    fun reset() {
        logger.info { "Resetting TDLib auth state" }
        currentAuthState = "UNKNOWN"
    }
}

package com.jobbot.data.models

// Configuration models
data class BotConfig(
    val botToken: String,
    val apiId: Int?,
    val apiHash: String?,
    val phoneNumber: String?,
    val authorizedAdminIds: List<Long>, // 🔧 Multi-admin support
    val databasePath: String,
    val logPath: String,
    val logLevel: String,
    val tdlibLogLevel: String,
    val rateLimitMessagesPerMinute: Int,
    val rateLimitBurstSize: Int
) {
    // Convenience methods
    fun isAdmin(userId: Long): Boolean = authorizedAdminIds.contains(userId)
    
    fun getAdminCount(): Int = authorizedAdminIds.size
    
    fun getFirstAdminId(): Long = authorizedAdminIds.first() // For startup notifications
}
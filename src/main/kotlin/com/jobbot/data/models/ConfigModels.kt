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
    val rateLimitBurstSize: Int,
    val mediaUploadTimeoutSeconds: Int = 300, // Media upload timeout (5 minutes default)
    // NEW: TDLib storage configuration
    val tdlibMaxStorageGB: Int = 2,           // Max storage in GB
    val tdlibFileTtlDays: Int = 7,            // Delete files older than N days
    val tdlibMaxFileCount: Int = 5000,        // Max number of files to keep
    val tdlibCleanupIntervalHours: Int = 24   // Run cleanup every N hours
) {
    // Convenience methods
    fun isAdmin(userId: Long): Boolean = authorizedAdminIds.contains(userId)
    
    fun getAdminCount(): Int = authorizedAdminIds.size
    
    fun getFirstAdminId(): Long = authorizedAdminIds.first() // For startup notifications
}
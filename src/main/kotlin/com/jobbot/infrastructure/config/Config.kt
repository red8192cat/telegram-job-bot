package com.jobbot.infrastructure.config

import com.jobbot.data.models.BotConfig
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.ValidationUtils
import java.io.File

object Config {
    private val logger = getLogger("Config")
    
    val config: BotConfig by lazy {
        loadConfig()
    }
    
    private fun loadConfig(): BotConfig {
        logger.info { "Loading configuration..." }
        
        return BotConfig(
            botToken = getEnvOrThrow("TELEGRAM_BOT_TOKEN"),
            apiId = getEnv("API_ID")?.toIntOrNull(),
            apiHash = getEnv("API_HASH"),
            phoneNumber = getEnv("PHONE_NUMBER"),
            authorizedAdminIds = parseAdminIds(),
            databasePath = getEnv("DATABASE_PATH") ?: "./data/bot.db",
            logPath = getEnv("LOG_PATH") ?: "./logs",
            logLevel = getEnv("LOG_LEVEL") ?: "INFO",
            tdlibLogLevel = getEnv("TDLIB_LOG_LEVEL") ?: "ERROR",
            rateLimitMessagesPerMinute = getEnv("RATE_LIMIT_MESSAGES_PER_MINUTE")?.toIntOrNull() ?: 60,
            rateLimitBurstSize = getEnv("RATE_LIMIT_BURST_SIZE")?.toIntOrNull() ?: 10,
            mediaUploadTimeoutSeconds = getEnv("MEDIA_UPLOAD_TIMEOUT_SECONDS")?.toIntOrNull() ?: 300,
            // NEW: TDLib storage configuration
            tdlibMaxStorageGB = getEnv("TDLIB_MAX_STORAGE_GB")?.toIntOrNull() ?: 2,
            tdlibFileTtlDays = getEnv("TDLIB_FILE_TTL_DAYS")?.toIntOrNull() ?: 7,
            tdlibMaxFileCount = getEnv("TDLIB_MAX_FILE_COUNT")?.toIntOrNull() ?: 5000,
            tdlibCleanupIntervalHours = getEnv("TDLIB_CLEANUP_INTERVAL_HOURS")?.toIntOrNull() ?: 24
        )
    }
    
    /**
     * Parse admin IDs from environment variable
     * Supports both single and multiple admins in one simple format
     * Examples:
     * - AUTHORIZED_ADMIN_IDS=123456789 (single admin)
     * - AUTHORIZED_ADMIN_IDS=123456789,987654321,555444333 (multiple admins)
     */
    private fun parseAdminIds(): List<Long> {
        val adminIdsEnv = getEnvOrThrow("AUTHORIZED_ADMIN_IDS")
        
        val adminIds = adminIdsEnv.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { 
                try {
                    it.toLong().also { id ->
                        if (id <= 0) {
                            logger.warn { "Invalid admin ID: $id (must be positive)" }
                            null
                        } else id
                    }
                } catch (e: NumberFormatException) {
                    logger.warn { "Invalid admin ID format: $it" }
                    null
                }
            }
        
        if (adminIds.isEmpty()) {
            throw IllegalStateException("AUTHORIZED_ADMIN_IDS must contain at least one valid admin ID")
        }
        
        logger.info { "Loaded ${adminIds.size} admin ID(s): ${adminIds.joinToString(", ")}" }
        return adminIds
    }
    
    private fun getEnv(key: String): String? {
        return System.getenv(key)
    }
    
    private fun getEnvOrThrow(key: String): String {
        return getEnv(key) ?: throw IllegalStateException("Required environment variable $key is not set")
    }
    
    fun validateConfig() {
        logger.info { "Validating configuration..." }
        
        with(config) {
            if (botToken.isBlank()) {
                throw IllegalStateException("TELEGRAM_BOT_TOKEN cannot be empty")
            }
            
            if (authorizedAdminIds.isEmpty()) {
                throw IllegalStateException("At least one admin ID must be configured")
            }
            
            authorizedAdminIds.forEach { adminId ->
                if (adminId <= 0) {
                    throw IllegalStateException("Admin ID $adminId must be a valid positive Telegram user ID")
                }
            }
            
            // Validate rate limiting settings
            if (rateLimitMessagesPerMinute <= 0) {
                throw IllegalStateException("RATE_LIMIT_MESSAGES_PER_MINUTE must be positive")
            }
            
            if (rateLimitBurstSize <= 0) {
                throw IllegalStateException("RATE_LIMIT_BURST_SIZE must be positive")
            }
            
            if (rateLimitBurstSize > rateLimitMessagesPerMinute) {
                logger.warn { "RATE_LIMIT_BURST_SIZE ($rateLimitBurstSize) is larger than RATE_LIMIT_MESSAGES_PER_MINUTE ($rateLimitMessagesPerMinute)" }
            }
            
            // Validate media upload timeout
            if (mediaUploadTimeoutSeconds <= 0) {
                throw IllegalStateException("MEDIA_UPLOAD_TIMEOUT_SECONDS must be positive")
            }
            
            if (mediaUploadTimeoutSeconds < 30) {
                logger.warn { "MEDIA_UPLOAD_TIMEOUT_SECONDS ($mediaUploadTimeoutSeconds) is very short, may cause upload failures" }
            }
            
            if (mediaUploadTimeoutSeconds > 600) {
                logger.warn { "MEDIA_UPLOAD_TIMEOUT_SECONDS ($mediaUploadTimeoutSeconds) is very long (>10 minutes)" }
            }
            
            // Validate TDLib storage settings
            if (tdlibMaxStorageGB <= 0) {
                throw IllegalStateException("TDLIB_MAX_STORAGE_GB must be positive")
            }
            
            if (tdlibMaxStorageGB > 50) {
                logger.warn { "TDLIB_MAX_STORAGE_GB ($tdlibMaxStorageGB) is very large (>50GB)" }
            }
            
            if (tdlibFileTtlDays <= 0) {
                throw IllegalStateException("TDLIB_FILE_TTL_DAYS must be positive")
            }
            
            if (tdlibFileTtlDays < 1) {
                logger.warn { "TDLIB_FILE_TTL_DAYS ($tdlibFileTtlDays) is very short, may delete recent files" }
            }
            
            if (tdlibMaxFileCount <= 0) {
                throw IllegalStateException("TDLIB_MAX_FILE_COUNT must be positive")
            }
            
            if (tdlibCleanupIntervalHours <= 0) {
                throw IllegalStateException("TDLIB_CLEANUP_INTERVAL_HOURS must be positive")
            }
            
            if (tdlibCleanupIntervalHours < 1) {
                logger.warn { "TDLIB_CLEANUP_INTERVAL_HOURS ($tdlibCleanupIntervalHours) is very frequent" }
            }
            
            // Validate TDLib log level
            if (!ValidationUtils.isValidTdlibLogLevel(tdlibLogLevel)) {
                logger.warn { "Invalid TDLIB_LOG_LEVEL ($tdlibLogLevel), falling back to ERROR" }
            }
            
            // Create necessary directories
            File(databasePath).parentFile?.mkdirs()
            File(logPath).mkdirs()
            
            // Validate TDLib configuration if provided
            if (apiId != null || apiHash != null || phoneNumber != null) {
                if (apiId == null || apiHash == null || phoneNumber == null) {
                    logger.warn { "Incomplete TDLib configuration. Channel monitoring will be disabled." }
                } else {
                    logger.info { "TDLib configuration found. Channel monitoring enabled." }
                }
            } else {
                logger.warn { "TDLib configuration not provided. Bot will work in bot-only mode." }
            }
            
            logger.info { "Configuration validated successfully" }
            logger.info { "Admin IDs: ${authorizedAdminIds.joinToString(", ")}" }
            logger.info { "Admin count: ${getAdminCount()}" }
            logger.info { "Database path: $databasePath" }
            logger.info { "Log path: $logPath" }
            logger.info { "System log level: $logLevel" }
            logger.info { "TDLib log level: $tdlibLogLevel" }
            logger.info { "Rate limit: $rateLimitMessagesPerMinute messages/minute (burst: $rateLimitBurstSize)" }
            logger.info { "Media upload timeout: $mediaUploadTimeoutSeconds seconds" }
            logger.info { "TDLib storage: max ${tdlibMaxStorageGB}GB, TTL ${tdlibFileTtlDays} days, cleanup every ${tdlibCleanupIntervalHours}h" }
            
            // Warn if rate limits seem too restrictive for interactive use
            if (rateLimitMessagesPerMinute < 30) {
                logger.warn { "Rate limit ($rateLimitMessagesPerMinute msg/min) may be too restrictive for interactive use" }
            }
            
            if (rateLimitBurstSize < 5) {
                logger.warn { "Burst size ($rateLimitBurstSize) may be too small for smooth user experience" }
            }
        }
    }
    
    fun hasTdLibConfig(): Boolean {
        return config.apiId != null && 
               config.apiHash != null && 
               config.phoneNumber != null
    }
}
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
            authorizedAdminId = getEnvOrThrow("AUTHORIZED_ADMIN_ID").toLong(),
            databasePath = getEnv("DATABASE_PATH") ?: "./data/bot.db",
            logPath = getEnv("LOG_PATH") ?: "./logs",
            logLevel = getEnv("LOG_LEVEL") ?: "INFO",
            tdlibLogLevel = getEnv("TDLIB_LOG_LEVEL") ?: "ERROR",
            // Updated more permissive defaults for better user experience
            rateLimitMessagesPerMinute = getEnv("RATE_LIMIT_MESSAGES_PER_MINUTE")?.toIntOrNull() ?: 60,
            rateLimitBurstSize = getEnv("RATE_LIMIT_BURST_SIZE")?.toIntOrNull() ?: 10
        )
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
            
            if (authorizedAdminId <= 0) {
                throw IllegalStateException("AUTHORIZED_ADMIN_ID must be a valid Telegram user ID")
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
            logger.info { "Admin ID: $authorizedAdminId" }
            logger.info { "Database path: $databasePath" }
            logger.info { "Log path: $logPath" }
            logger.info { "System log level: $logLevel" }
            logger.info { "TDLib log level: $tdlibLogLevel" }
            logger.info { "Rate limit: $rateLimitMessagesPerMinute messages/minute (burst: $rateLimitBurstSize)" }
            
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

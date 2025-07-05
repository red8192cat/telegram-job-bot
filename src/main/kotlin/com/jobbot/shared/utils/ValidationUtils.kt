package com.jobbot.shared.utils

// Validation utilities
object ValidationUtils {
    fun isValidTelegramId(id: String): Boolean {
        return id.toLongOrNull()?.let { it > 0 } ?: false
    }
    
    fun isValidPhoneNumber(phone: String): Boolean {
        return phone.matches(Regex("^\\+?[1-9]\\d{1,14}$"))
    }
    
    fun sanitizeKeywords(keywords: String): String {
        return keywords.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }
    
    fun validateKeywordSyntax(keyword: String): Boolean {
        // Allow Unicode letters/numbers, spaces, wildcards (*), brackets ([]), pipes (|), slashes (/), plus (+)
        return keyword.matches(Regex("^[\\p{L}\\p{N}\\s*\\[\\]/|+]+$"))  // Added /
    }

    fun isValidRateLimit(messagesPerMinute: Int, burstSize: Int): Boolean {
        return messagesPerMinute in 10..200 && burstSize in 10..200
    }
    
    fun isValidLogLevel(level: String): Boolean {
        return level.uppercase() in listOf("DEBUG", "INFO", "WARN", "ERROR")
    }

    fun isValidTdlibLogLevel(level: String): Boolean {
        return level.uppercase() in listOf("FATAL", "ERROR", "WARNING", "INFO", "DEBUG", "VERBOSE")
    }
}

package com.jobbot.shared.utils

import java.time.LocalDateTime
import java.util.regex.Pattern

// Text processing utilities
object TextUtils {
    private val channelPatterns = listOf(
        Pattern.compile("^@([a-zA-Z0-9_]+)$"), // @channelname
        Pattern.compile("^(?:https?://)?t\\.me/([a-zA-Z0-9_]+)/?$"), // t.me/channelname
        Pattern.compile("^(-?[0-9]{10,})$") // chat ID
    )
    
    fun parseChannelIdentifier(input: String): String? {
        val trimmed = input.trim()
        
        for (pattern in channelPatterns) {
            val matcher = pattern.matcher(trimmed)
            if (matcher.matches()) {
                return if (matcher.groupCount() > 0) {
                    matcher.group(1) // Extract channel name
                } else {
                    trimmed // Return as is for chat IDs
                }
            }
        }
        
        return null
    }
    
    fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s+*/\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun escapeMarkdown(text: String): String {
        // Only escape characters that actually break Markdown in this context
        // Don't escape hyphens in channel IDs, but escape underscores in usernames
        return text.replace(Regex("([_*\\[\\]()~`>#+=|{}.!])"), "\\\\$1")
            // Remove hyphen from the escape list since it's safe in channel IDs
    }
    
    fun formatUptime(startTime: LocalDateTime): String {
        val duration = java.time.Duration.between(startTime, LocalDateTime.now())
        val days = duration.toDays()
        val hours = duration.toHours() % 24
        val minutes = duration.toMinutes() % 60
        
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
    
    fun truncateText(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength - 3) + "..."
        }
    }
}

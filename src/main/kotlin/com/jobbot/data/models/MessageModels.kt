package com.jobbot.data.models

import java.time.LocalDateTime

// Message processing models
// UPDATED: Added formattedText support for preserving original formatting
data class ChannelMessage(
    val channelId: String,
    val channelName: String?,
    val messageId: Long,
    val text: String, // Plain text for keyword matching
    val formattedText: String? = null, // Formatted text for user notifications (MarkdownV2)
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val senderUsername: String? = null,
    val messageLink: String? = null
)

// UPDATED: Added formattedMessageText support for notifications
data class NotificationMessage(
    val userId: Long,
    val channelName: String,
    val messageText: String, // Plain text for compatibility
    val formattedMessageText: String? = null, // Formatted text (MarkdownV2) for rich notifications
    val priority: Int = 0,
    val senderUsername: String? = null,
    val messageLink: String? = null
)

// Keyword processing models (unchanged)
data class ParsedKeywords(
    val required: List<String> = emptyList(),
    val requiredOr: List<List<String>> = emptyList(),
    val optional: List<String> = emptyList(),
    val wildcards: List<String> = emptyList(),
    val phrases: List<List<String>> = emptyList(),
    val andGroups: List<List<String>> = emptyList()
)

data class MatchResult(
    val isMatch: Boolean,
    val matchedKeywords: List<String> = emptyList(),
    val blockedByIgnore: Boolean = false,
    val ignoredKeywords: List<String> = emptyList()
)
package com.jobbot.data.models

import java.time.LocalDateTime

// Message processing models
// UPDATED: Added media attachment support for rich notifications
data class ChannelMessage(
    val channelId: String,
    val channelName: String?,
    val messageId: Long,
    val text: String, // Plain text for keyword matching
    val formattedText: String? = null, // Formatted text for user notifications (MarkdownV2)
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val senderUsername: String? = null,
    val messageLink: String? = null,
    val mediaAttachments: List<MediaAttachment> = emptyList() // NEW: Media attachments
)

// UPDATED: Added media attachments support for notifications
data class NotificationMessage(
    val userId: Long,
    val channelName: String,
    val messageText: String, // Plain text for compatibility
    val formattedMessageText: String? = null, // Formatted text (MarkdownV2) for rich notifications
    val priority: Int = 0,
    val senderUsername: String? = null,
    val messageLink: String? = null,
    val mediaAttachments: List<MediaAttachment> = emptyList() // NEW: Media attachments
)

// NEW: Media attachment data model - Updated for direct file access
data class MediaAttachment(
    val type: MediaType,
    val filePath: String, // Original TDLib file path (preserves original filename)
    val originalFileName: String? = null, // Display name extracted from TDLib
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val caption: String? = null, // For photos/videos with captions
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null // For videos/audio
) {
    /**
     * Get the actual filename from the file path
     * Preserves original Unicode characters (Cyrillic, etc.)
     */
    val actualFileName: String
        get() = try {
            File(filePath).name
        } catch (e: Exception) {
            originalFileName ?: "unknown"
        }
}

enum class MediaType {
    PHOTO,
    VIDEO,
    DOCUMENT,
    AUDIO,
    VOICE,
    ANIMATION // GIFs
}

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
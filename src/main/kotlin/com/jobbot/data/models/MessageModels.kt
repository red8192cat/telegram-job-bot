// File: src/main/kotlin/com/jobbot/data/models/MessageModels.kt

package com.jobbot.data.models

import java.time.LocalDateTime

// UPDATED: Enhanced message models for media support

data class ChannelMessage(
    val channelId: String,
    val channelName: String?,
    val messageId: Long,
    val text: String, // Plain text for keyword matching
    val formattedText: String? = null, // Formatted text for display  
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val senderUsername: String? = null,
    val messageLink: String? = null,
    
    // NEW: Media support
    val mediaGroup: List<MediaItem> = emptyList() // Support multiple media items
)

data class NotificationMessage(
    val userId: Long,
    val channelName: String,
    val messageText: String, // Plain text for compatibility
    val formattedMessageText: String? = null, // Formatted text for rich notifications
    val priority: Int = 0,
    val senderUsername: String? = null,
    val messageLink: String? = null,
    
    // NEW: Media support  
    val mediaGroup: List<MediaItem> = emptyList() // Original media to forward
)

// NEW: Simple media item representation
data class MediaItem(
    val type: MediaType,
    val fileId: String,
    val fileUniqueId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val thumbnailFileId: String? = null
)

enum class MediaType {
    PHOTO,
    VIDEO, 
    ANIMATION,
    DOCUMENT,
    AUDIO,
    VOICE,
    VIDEO_NOTE,
    STICKER
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
import java.time.LocalDateTime

// Channel-related data classes
data class Channel(
    val id: Long = 0,
    val channelId: String,
    val channelName: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * Enhanced channel details model with tag support
 */
data class ChannelDetails(
    val id: Long = 0,
    val channelId: String,
    val channelName: String? = null,
    val channelTag: String? = null,  // Store @tag
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime? = null
) {
    /**
     * Get display name (prioritize tag, then name, then ID)
     */
    fun getDisplayName(): String {
        return when {
            !channelTag.isNullOrBlank() -> channelTag
            !channelName.isNullOrBlank() -> channelName
            else -> channelId
        }
    }
    
    /**
     * Get display info for admin (ID + tag/status)
     */
    fun getAdminDisplayInfo(): String {
        val tagInfo = when {
            !channelTag.isNullOrBlank() -> channelTag
            else -> "<tag unavailable>"
        }
        return "$channelId $tagInfo"
    }
}

/**
 * Channel lookup result from Telegram API
 */
data class ChannelLookupResult(
    val found: Boolean,
    val channelId: String?,
    val channelTag: String?,
    val channelName: String?,
    val error: String? = null
)


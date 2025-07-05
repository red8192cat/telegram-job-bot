import java.time.LocalDateTime

// Message processing models
data class ChannelMessage(
    val channelId: String,
    val channelName: String?,
    val messageId: Long,
    val text: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val senderUsername: String? = null
)

data class NotificationMessage(
    val userId: Long,
    val channelName: String,
    val messageText: String,
    val priority: Int = 0,
    val senderUsername: String? = null
)

// Keyword processing models
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

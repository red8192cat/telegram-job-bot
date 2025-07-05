import java.time.LocalDateTime

// System monitoring models
data class SystemHealth(
    val uptime: String,
    val activeUsers: Int,
    val monitoredChannels: Int,
    val messagesProcessed: Long,
    val lastError: String? = null,
    val errorCount: Int = 0
)

data class ErrorLog(
    val timestamp: LocalDateTime,
    val level: String,
    val message: String,
    val exception: String? = null
)

// Rate limiting models
data class RateLimitState(
    val tokens: Int,
    val lastRefill: Long,
    val isLimited: Boolean = false
)

// Rate limit event for admin notifications
data class RateLimitEvent(
    val userId: Long,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val tokensRemaining: Int = 0,
    val userInfo: UserInfo? = null
)

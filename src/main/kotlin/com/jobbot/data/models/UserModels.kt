import java.time.LocalDateTime

// User-related data classes
data class User(
    val id: Long = 0,
    val telegramId: Long,
    val language: String = "en",
    val keywords: String? = null,
    val ignoreKeywords: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class UserActivity(
    val id: Long = 0,
    val userTelegramId: Long,
    val lastInteraction: LocalDateTime = LocalDateTime.now(),
    val commandCount: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

// Banned users model
data class BannedUser(
    val userId: Long,
    val bannedAt: LocalDateTime = LocalDateTime.now(),
    val reason: String,
    val bannedByAdmin: Long
)

// Extended user info for admin purposes
data class UserInfo(
    val userId: Long,
    val username: String? = null,
    val language: String? = null,
    val isActive: Boolean = false,
    val isBanned: Boolean = false,
    val bannedInfo: BannedUser? = null,
    val lastActivity: LocalDateTime? = null,
    val commandCount: Int = 0
)

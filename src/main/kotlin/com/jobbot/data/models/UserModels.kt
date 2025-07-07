package com.jobbot.data.models

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// UPDATED: Single simplified User model with all user data
data class User(
    val telegramId: Long,
    val language: String = "en",
    val keywords: String? = null,
    val ignoreKeywords: String? = null,
    val lastInteraction: LocalDateTime = LocalDateTime.now(),
    
    // Premium (simplified)
    val isPremium: Boolean = false,
    val premiumGrantedAt: LocalDateTime? = null,
    val premiumExpiresAt: LocalDateTime? = null, // NULL = permanent
    val premiumReason: String? = null,
    
    // Moderation (integrated)
    val isBanned: Boolean = false,
    val bannedAt: LocalDateTime? = null,
    val banReason: String? = null,
    
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    // Helper properties for premium status
    val isPremiumActive: Boolean
        get() = isPremium && (premiumExpiresAt == null || premiumExpiresAt.isAfter(LocalDateTime.now()))
    
    val premiumDaysRemaining: Long?
        get() = premiumExpiresAt?.let { 
            val days = ChronoUnit.DAYS.between(LocalDateTime.now(), it)
            if (days > 0) days else 0
        }
    
    val premiumDaysTotal: Long?
        get() = if (premiumGrantedAt != null && premiumExpiresAt != null) {
            ChronoUnit.DAYS.between(premiumGrantedAt, premiumExpiresAt)
        } else null
    
    val isPremiumExpired: Boolean
        get() = isPremium && premiumExpiresAt != null && premiumExpiresAt.isBefore(LocalDateTime.now())
    
    val isPremiumPermanent: Boolean
        get() = isPremium && premiumExpiresAt == null
}

// REMOVED: UserActivity (merged into User)
// REMOVED: BannedUser (merged into User) 
// REMOVED: PremiumUser (merged into User)
// REMOVED: PremiumUserInfo (merged into User)

// KEPT: Extended user info for admin purposes (combines user data + additional metadata)
data class UserInfo(
    val userId: Long,
    val username: String? = null,
    val language: String? = null,
    val isActive: Boolean = false,
    val isBanned: Boolean = false,
    val bannedInfo: BannedInfo? = null,
    val lastActivity: LocalDateTime? = null,
    val isPremium: Boolean = false,
    val premiumInfo: PremiumInfo? = null
)

// Simplified info structures for admin display
data class BannedInfo(
    val bannedAt: LocalDateTime,
    val reason: String
)

data class PremiumInfo(
    val grantedAt: LocalDateTime,
    val expiresAt: LocalDateTime?,
    val reason: String?,
    val isActive: Boolean,
    val daysRemaining: Long?
)

// KEPT: User lookup result from TDLib API for username resolution
data class UserLookupResult(
    val found: Boolean,
    val userId: Long?,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val error: String? = null
)
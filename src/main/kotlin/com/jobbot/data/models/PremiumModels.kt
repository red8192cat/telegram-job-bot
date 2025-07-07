package com.jobbot.data.models

import java.time.LocalDateTime

/**
 * Premium user data model
 */
data class PremiumUser(
    val userId: Long,
    val grantedAt: LocalDateTime = LocalDateTime.now(),
    val grantedByAdmin: Long,
    val reason: String? = null,
    val isActive: Boolean = true,
    val revokedAt: LocalDateTime? = null,
    val revokedByAdmin: Long? = null,
    val revokeReason: String? = null
)

/**
 * Premium user info for admin display
 */
data class PremiumUserInfo(
    val userId: Long,
    val username: String? = null,
    val grantedAt: LocalDateTime,
    val grantedByAdmin: Long,
    val reason: String? = null,
    val isActive: Boolean = true,
    val daysSincePremium: Long = 0,
    val lastActivity: LocalDateTime? = null,
    val commandCount: Int = 0
)

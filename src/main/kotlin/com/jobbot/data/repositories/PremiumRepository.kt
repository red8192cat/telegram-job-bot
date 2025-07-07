package com.jobbot.data.repositories

import com.jobbot.data.models.PremiumUser
import com.jobbot.data.models.PremiumUserInfo
import com.jobbot.shared.getLogger
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Repository for managing premium user operations
 * Handles premium user grants, revokes, and queries
 */
class PremiumRepository(
    private val getConnection: () -> Connection,
    private val userRepository: UserRepository
) {
    private val logger = getLogger("PremiumRepository")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    /**
     * Grant premium access to a user
     */
    fun grantPremium(userId: Long, grantedByAdmin: Long, reason: String?): Boolean {
        return try {
            getConnection().use { conn ->
                conn.autoCommit = false
                
                try {
                    // 1. Update users table
                    val updateUserSql = """
                        UPDATE users 
                        SET is_premium = 1, 
                            premium_granted_at = CURRENT_TIMESTAMP,
                            premium_granted_by = ?
                        WHERE telegram_id = ?
                    """
                    conn.prepareStatement(updateUserSql).use { stmt ->
                        stmt.setLong(1, grantedByAdmin)
                        stmt.setLong(2, userId)
                        stmt.executeUpdate()
                    }
                    
                    // 2. Insert into premium_users table
                    val insertPremiumSql = """
                        INSERT OR REPLACE INTO premium_users 
                        (user_id, granted_by_admin, reason, is_active) 
                        VALUES (?, ?, ?, 1)
                    """
                    conn.prepareStatement(insertPremiumSql).use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.setLong(2, grantedByAdmin)
                        stmt.setString(3, reason)
                        stmt.executeUpdate()
                    }
                    
                    conn.commit()
                    logger.info { "Premium granted to user $userId by admin $grantedByAdmin" }
                    true
                    
                } catch (e: SQLException) {
                    conn.rollback()
                    logger.error(e) { "Failed to grant premium to user $userId" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to grant premium to user $userId" }
            false
        }
    }
    
    /**
     * Revoke premium access from a user
     */
    fun revokePremium(userId: Long, revokedByAdmin: Long, reason: String?): Boolean {
        return try {
            getConnection().use { conn ->
                conn.autoCommit = false
                
                try {
                    // 1. Update users table
                    val updateUserSql = """
                        UPDATE users 
                        SET is_premium = 0,
                            premium_granted_at = NULL,
                            premium_granted_by = NULL
                        WHERE telegram_id = ?
                    """
                    conn.prepareStatement(updateUserSql).use { stmt ->
                        stmt.setLong(1, userId)
                        stmt.executeUpdate()
                    }
                    
                    // 2. Update premium_users table (mark as inactive instead of deleting)
                    val updatePremiumSql = """
                        UPDATE premium_users 
                        SET is_active = 0,
                            revoked_at = CURRENT_TIMESTAMP,
                            revoked_by_admin = ?,
                            revoke_reason = ?
                        WHERE user_id = ?
                    """
                    conn.prepareStatement(updatePremiumSql).use { stmt ->
                        stmt.setLong(1, revokedByAdmin)
                        stmt.setString(2, reason)
                        stmt.setLong(3, userId)
                        stmt.executeUpdate()
                    }
                    
                    conn.commit()
                    logger.info { "Premium revoked from user $userId by admin $revokedByAdmin" }
                    true
                    
                } catch (e: SQLException) {
                    conn.rollback()
                    logger.error(e) { "Failed to revoke premium from user $userId" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to revoke premium from user $userId" }
            false
        }
    }
    
    /**
     * Check if a user has premium access
     */
    fun isPremiumUser(userId: Long): Boolean {
        val sql = "SELECT is_premium FROM users WHERE telegram_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            rs.getInt("is_premium") == 1
                        } else false
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check premium status for user $userId" }
            false
        }
    }
    
    /**
     * Get premium user details
     */
    fun getPremiumUser(userId: Long): PremiumUser? {
        val sql = """
            SELECT p.*, u.premium_granted_at 
            FROM premium_users p
            LEFT JOIN users u ON p.user_id = u.telegram_id
            WHERE p.user_id = ? AND p.is_active = 1
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            PremiumUser(
                                userId = rs.getLong("user_id"),
                                grantedAt = LocalDateTime.parse(rs.getString("granted_at"), dateFormatter),
                                grantedByAdmin = rs.getLong("granted_by_admin"),
                                reason = rs.getString("reason"),
                                isActive = rs.getInt("is_active") == 1
                            )
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get premium user $userId" }
            null
        }
    }
    
    /**
     * Get all premium users with calculated info
     */
    fun getAllPremiumUsers(): List<PremiumUserInfo> {
        val sql = """
            SELECT p.*, u.premium_granted_at 
            FROM premium_users p
            LEFT JOIN users u ON p.user_id = u.telegram_id
            WHERE p.is_active = 1
            ORDER BY p.granted_at DESC
        """
        
        val premiumUsers = mutableListOf<PremiumUserInfo>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            val grantedAt = LocalDateTime.parse(rs.getString("granted_at"), dateFormatter)
                            val daysSincePremium = ChronoUnit.DAYS.between(grantedAt, LocalDateTime.now()).toInt()
                            
                            premiumUsers.add(
                                PremiumUserInfo(
                                    userId = rs.getLong("user_id"),
                                    grantedAt = grantedAt,
                                    grantedByAdmin = rs.getLong("granted_by_admin"),
                                    reason = rs.getString("reason"),
                                    daysSincePremium = daysSincePremium
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all premium users" }
        }
        
        return premiumUsers
    }
    
    /**
     * Get count of active premium users
     */
    fun getPremiumUserCount(): Int {
        val sql = "SELECT COUNT(*) FROM premium_users WHERE is_active = 1"
        
        return try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get premium user count" }
            0
        }
    }
    
    /**
     * Helper method to check if user exists (used by AdminPremiumHandler)
     */
    fun userExists(userId: Long): Boolean {
        return userRepository.getUser(userId) != null
    }
}
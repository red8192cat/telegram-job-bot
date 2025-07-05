import com.jobbot.data.models.BannedUser
import com.jobbot.data.models.UserInfo
import com.jobbot.shared.getLogger
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AdminRepository(
    private val getConnection: () -> Connection,
    private val userRepository: UserRepository
) {
    private val logger = getLogger("AdminRepository")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    // Banned users operations
    fun banUser(userId: Long, reason: String, bannedByAdmin: Long): Boolean {
        val sql = "INSERT OR REPLACE INTO banned_users (user_id, reason, banned_by_admin) VALUES (?, ?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.setString(2, reason)
                    stmt.setLong(3, bannedByAdmin)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ban user $userId" }
            false
        }
    }
    
    fun unbanUser(userId: Long): Boolean {
        val sql = "DELETE FROM banned_users WHERE user_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to unban user $userId" }
            false
        }
    }
    
    fun isUserBanned(userId: Long): Boolean {
        val sql = "SELECT 1 FROM banned_users WHERE user_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if user is banned $userId" }
            false
        }
    }
    
    fun getBannedUser(userId: Long): BannedUser? {
        val sql = "SELECT * FROM banned_users WHERE user_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            BannedUser(
                                userId = rs.getLong("user_id"),
                                bannedAt = LocalDateTime.parse(rs.getString("banned_at"), dateFormatter),
                                reason = rs.getString("reason"),
                                bannedByAdmin = rs.getLong("banned_by_admin")
                            )
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get banned user $userId" }
            null
        }
    }
    
    fun getAllBannedUsers(): List<BannedUser> {
        val sql = "SELECT * FROM banned_users ORDER BY banned_at DESC"
        val bannedUsers = mutableListOf<BannedUser>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            bannedUsers.add(
                                BannedUser(
                                    userId = rs.getLong("user_id"),
                                    bannedAt = LocalDateTime.parse(rs.getString("banned_at"), dateFormatter),
                                    reason = rs.getString("reason"),
                                    bannedByAdmin = rs.getLong("banned_by_admin")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all banned users" }
        }
        
        return bannedUsers
    }
    
    // Get comprehensive user info for admin purposes (combines user data + ban status)
    fun getUserInfo(userId: Long): UserInfo? {
        // Get basic user info from user repository
        val userInfo = userRepository.getUserInfo(userId)
        val banned = getBannedUser(userId)
        
        return if (userInfo != null || banned != null) {
            userInfo?.copy(
                isBanned = banned != null,
                bannedInfo = banned
            ) ?: UserInfo(
                userId = userId,
                username = null, // Will be filled by TelegramBot when needed
                language = null,
                isActive = false,
                isBanned = true,
                bannedInfo = banned,
                lastActivity = null,
                commandCount = 0
            )
        } else null
    }
}

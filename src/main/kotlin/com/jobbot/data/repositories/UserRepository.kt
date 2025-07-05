package com.jobbot.data.repositories

import com.jobbot.data.models.*
import com.jobbot.shared.getLogger
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UserRepository(private val getConnection: () -> Connection) {
    private val logger = getLogger("UserRepository")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    fun getUser(telegramId: Long): User? {
        val sql = "SELECT * FROM users WHERE telegram_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, telegramId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            User(
                                id = rs.getLong("id"),
                                telegramId = rs.getLong("telegram_id"),
                                language = rs.getString("language"),
                                keywords = rs.getString("keywords"),
                                ignoreKeywords = rs.getString("ignore_keywords"),
                                createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                updatedAt = LocalDateTime.parse(rs.getString("updated_at"), dateFormatter)
                            )
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get user $telegramId" }
            null
        }
    }
    
    fun createUser(user: User): Boolean {
        val sql = "INSERT INTO users (telegram_id, language, keywords, ignore_keywords) VALUES (?, ?, ?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, user.telegramId)
                    stmt.setString(2, user.language)
                    stmt.setString(3, user.keywords)
                    stmt.setString(4, user.ignoreKeywords)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create user ${user.telegramId}" }
            false
        }
    }
    
    fun updateUser(user: User): Boolean {
        val sql = "UPDATE users SET language = ?, keywords = ?, ignore_keywords = ?, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, user.language)
                    stmt.setString(2, user.keywords)
                    stmt.setString(3, user.ignoreKeywords)
                    stmt.setLong(4, user.telegramId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update user ${user.telegramId}" }
            false
        }
    }
    
    fun getAllUsers(): List<User> {
        val sql = "SELECT * FROM users WHERE keywords IS NOT NULL"
        val users = mutableListOf<User>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            users.add(
                                User(
                                    id = rs.getLong("id"),
                                    telegramId = rs.getLong("telegram_id"),
                                    language = rs.getString("language"),
                                    keywords = rs.getString("keywords"),
                                    ignoreKeywords = rs.getString("ignore_keywords"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                    updatedAt = LocalDateTime.parse(rs.getString("updated_at"), dateFormatter)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all users" }
        }
        
        return users
    }
    
    fun getActiveUsersCount(): Int {
        val sql = "SELECT COUNT(*) FROM users WHERE keywords IS NOT NULL"
        
        return try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        rs.getInt(1)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get active users count" }
            0
        }
    }
    
    // User activity operations
    fun updateUserActivity(telegramId: Long) {
        val sql = """
            INSERT INTO user_activity (user_telegram_id, last_interaction, command_count) 
            VALUES (?, CURRENT_TIMESTAMP, 1)
            ON CONFLICT(user_telegram_id) DO UPDATE SET 
                last_interaction = CURRENT_TIMESTAMP,
                command_count = command_count + 1
        """
        
        try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, telegramId)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update user activity for $telegramId" }
        }
    }
    
    fun getUserActivity(telegramId: Long): UserActivity? {
        val sql = "SELECT * FROM user_activity WHERE user_telegram_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, telegramId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            UserActivity(
                                id = rs.getLong("id"),
                                userTelegramId = rs.getLong("user_telegram_id"),
                                lastInteraction = LocalDateTime.parse(rs.getString("last_interaction"), dateFormatter),
                                commandCount = rs.getInt("command_count"),
                                createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter)
                            )
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get user activity for $telegramId" }
            null
        }
    }
    
    // Get user info with username for admin purposes
    fun getUserInfo(userId: Long): UserInfo? {
        // Get basic user info from users table
        val user = getUser(userId)
        val activity = getUserActivity(userId)
        
        return if (user != null) {
            UserInfo(
                userId = userId,
                username = null, // Will be filled by TelegramBot when needed
                language = user.language,
                isActive = user.keywords != null,
                isBanned = false, // Will be checked by AdminRepository
                bannedInfo = null, // Will be filled by AdminRepository
                lastActivity = activity?.lastInteraction,
                commandCount = activity?.commandCount ?: 0
            )
        } else null
    }
    
    // Batch operations for better performance
    fun batchUpdateUsers(updates: List<Pair<Long, String>>): Boolean {
        return try {
            getConnection().use { conn ->
                conn.autoCommit = false  // Start transaction
                
                try {
                    val sql = "UPDATE users SET keywords = ?, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = ?"
                    conn.prepareStatement(sql).use { stmt ->
                        for ((userId, keywords) in updates) {
                            stmt.setString(1, keywords)
                            stmt.setLong(2, userId)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    
                    conn.commit()  // Commit transaction
                    true
                } catch (e: Exception) {
                    conn.rollback()  // Rollback on error
                    throw e
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to batch update users" }
            false
        }
    }
}

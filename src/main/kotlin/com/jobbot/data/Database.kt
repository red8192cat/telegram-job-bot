package com.jobbot.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.jobbot.data.migrations.DatabaseMigration
import com.jobbot.data.models.*
import com.jobbot.shared.getLogger
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Database(private val databasePath: String) {
    private val logger = getLogger("Database")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    // ✅ CONNECTION POOL: Created once, reused for all operations
    private val dataSource: HikariDataSource
    
    init {
        dataSource = createDataSource()
        initDatabase()
    }
    
    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            // SQLite specific configuration
            jdbcUrl = "jdbc:sqlite:$databasePath"
            driverClassName = "org.sqlite.JDBC"
            
            // Pool configuration optimized for SQLite
            maximumPoolSize = 10        // SQLite works well with small pools
            minimumIdle = 2             // Keep 2 connections ready
            connectionTimeout = 30000   // 30 seconds
            idleTimeout = 600000        // 10 minutes
            maxLifetime = 1800000       // 30 minutes
            
            // SQLite optimizations
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            
            // Enable WAL mode for better concurrent access
            connectionInitSql = "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA cache_size=10000; PRAGMA temp_store=memory;"
            
            // Pool name for monitoring
            poolName = "TelegramBotPool"
            
            // Leak detection
            leakDetectionThreshold = 60000 // 60 seconds
        }
        
        logger.info { "Creating HikariCP connection pool for database: $databasePath" }
        return HikariDataSource(config)
    }
    
    // ✅ EFFICIENT: Get connection from pool (typically <1ms)
    private fun getConnection(): Connection {
        return dataSource.connection
    }
    
    private fun initDatabase() {
        logger.info { "Initializing database at $databasePath" }
        
        try {
            getConnection().use { conn ->
                // First, read and execute base schema
                val schema = this::class.java.getResourceAsStream("/schema.sql")?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Could not load database schema")
                
                // Execute schema statements (this creates tables if they don't exist)
                val statements = schema.split(";").filter { it.trim().isNotBlank() }
                for (statement in statements) {
                    try {
                        conn.createStatement().execute(statement.trim())
                    } catch (e: Exception) {
                        // Log but continue for "already exists" errors
                        if (e.message?.contains("already exists", ignoreCase = true) != true) {
                            logger.debug { "Schema statement executed: ${statement.take(50)}..." }
                        }
                    }
                }
                
                // Run database migrations
                val migration = DatabaseMigration(logger)
                migration.runMigrations(conn)
                
                logger.info { "Database initialized successfully with connection pooling and migrations" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize database" }
            throw e
        }
    }
    
    // ===============================
    // USER OPERATIONS (DIRECT METHODS)
    // ===============================
    
    fun getUser(telegramId: Long): User? {
        val sql = "SELECT * FROM users WHERE telegram_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, telegramId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            User(
                                telegramId = rs.getLong("telegram_id"),
                                language = rs.getString("language"),
                                keywords = rs.getString("keywords"),
                                ignoreKeywords = rs.getString("ignore_keywords"),
                                lastInteraction = LocalDateTime.parse(rs.getString("last_interaction"), dateFormatter),
                                isPremium = rs.getInt("is_premium") == 1,
                                premiumGrantedAt = rs.getString("premium_granted_at")?.let { 
                                    LocalDateTime.parse(it, dateFormatter) 
                                },
                                premiumExpiresAt = rs.getString("premium_expires_at")?.let { 
                                    LocalDateTime.parse(it, dateFormatter) 
                                },
                                premiumReason = rs.getString("premium_reason"),
                                isBanned = rs.getInt("is_banned") == 1,
                                bannedAt = rs.getString("banned_at")?.let { 
                                    LocalDateTime.parse(it, dateFormatter) 
                                },
                                banReason = rs.getString("ban_reason"),
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
        val sql = """
            INSERT INTO users (telegram_id, language, keywords, ignore_keywords, last_interaction,
                             is_premium, premium_granted_at, premium_expires_at, premium_reason,
                             is_banned, banned_at, ban_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, user.telegramId)
                    stmt.setString(2, user.language)
                    stmt.setString(3, user.keywords)
                    stmt.setString(4, user.ignoreKeywords)
                    stmt.setString(5, user.lastInteraction.format(dateFormatter))
                    stmt.setInt(6, if (user.isPremium) 1 else 0)
                    stmt.setString(7, user.premiumGrantedAt?.format(dateFormatter))
                    stmt.setString(8, user.premiumExpiresAt?.format(dateFormatter))
                    stmt.setString(9, user.premiumReason)
                    stmt.setInt(10, if (user.isBanned) 1 else 0)
                    stmt.setString(11, user.bannedAt?.format(dateFormatter))
                    stmt.setString(12, user.banReason)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create user ${user.telegramId}" }
            false
        }
    }
    
    fun updateUser(user: User): Boolean {
        val sql = """
            UPDATE users SET 
                language = ?, keywords = ?, ignore_keywords = ?, last_interaction = ?,
                is_premium = ?, premium_granted_at = ?, premium_expires_at = ?, premium_reason = ?,
                is_banned = ?, banned_at = ?, ban_reason = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, user.language)
                    stmt.setString(2, user.keywords)
                    stmt.setString(3, user.ignoreKeywords)
                    stmt.setString(4, user.lastInteraction.format(dateFormatter))
                    stmt.setInt(5, if (user.isPremium) 1 else 0)
                    stmt.setString(6, user.premiumGrantedAt?.format(dateFormatter))
                    stmt.setString(7, user.premiumExpiresAt?.format(dateFormatter))
                    stmt.setString(8, user.premiumReason)
                    stmt.setInt(9, if (user.isBanned) 1 else 0)
                    stmt.setString(10, user.bannedAt?.format(dateFormatter))
                    stmt.setString(11, user.banReason)
                    stmt.setLong(12, user.telegramId)
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
                                    telegramId = rs.getLong("telegram_id"),
                                    language = rs.getString("language"),
                                    keywords = rs.getString("keywords"),
                                    ignoreKeywords = rs.getString("ignore_keywords"),
                                    lastInteraction = LocalDateTime.parse(rs.getString("last_interaction"), dateFormatter),
                                    isPremium = rs.getInt("is_premium") == 1,
                                    premiumGrantedAt = rs.getString("premium_granted_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumExpiresAt = rs.getString("premium_expires_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumReason = rs.getString("premium_reason"),
                                    isBanned = rs.getInt("is_banned") == 1,
                                    bannedAt = rs.getString("banned_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    banReason = rs.getString("ban_reason"),
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
    
    fun updateUserActivity(telegramId: Long) {
        val sql = """
            UPDATE users SET 
                last_interaction = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
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
    
    // ===============================
    // PREMIUM OPERATIONS (INTEGRATED)
    // ===============================
    
    fun grantPremium(userId: Long, reason: String, durationDays: Int? = null): Boolean {
        val sql = """
            UPDATE users SET 
                is_premium = 1,
                premium_granted_at = CURRENT_TIMESTAMP,
                premium_expires_at = CASE 
                    WHEN ? IS NULL THEN NULL 
                    ELSE DATETIME('now', '+' || ? || ' days')
                END,
                premium_reason = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, durationDays)
                    stmt.setObject(2, durationDays)
                    stmt.setString(3, reason)
                    stmt.setLong(4, userId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to grant premium to user $userId" }
            false
        }
    }
    
    fun revokePremium(userId: Long): Boolean {
        val sql = """
            UPDATE users SET 
                is_premium = 0,
                premium_granted_at = NULL,
                premium_expires_at = NULL,
                premium_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to revoke premium from user $userId" }
            false
        }
    }
    
    fun extendPremium(userId: Long, additionalDays: Int): Boolean {
        val sql = """
            UPDATE users SET 
                premium_expires_at = CASE
                    WHEN premium_expires_at IS NULL THEN 
                        DATETIME('now', '+' || ? || ' days')
                    ELSE 
                        DATETIME(premium_expires_at, '+' || ? || ' days')
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ? AND is_premium = 1
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, additionalDays)
                    stmt.setInt(2, additionalDays)
                    stmt.setLong(3, userId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to extend premium for user $userId" }
            false
        }
    }
    
    fun isPremiumUser(userId: Long): Boolean {
        val sql = """
            SELECT 1 FROM users 
            WHERE telegram_id = ? 
            AND is_premium = 1 
            AND (premium_expires_at IS NULL OR premium_expires_at > DATETIME('now'))
        """
        
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
            logger.error(e) { "Failed to check premium status for user $userId" }
            false
        }
    }
    
    fun getAllPremiumUsers(): List<User> {
        val sql = """
            SELECT * FROM users 
            WHERE is_premium = 1 
            ORDER BY premium_granted_at DESC
        """
        val premiumUsers = mutableListOf<User>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            premiumUsers.add(
                                User(
                                    telegramId = rs.getLong("telegram_id"),
                                    language = rs.getString("language"),
                                    keywords = rs.getString("keywords"),
                                    ignoreKeywords = rs.getString("ignore_keywords"),
                                    lastInteraction = LocalDateTime.parse(rs.getString("last_interaction"), dateFormatter),
                                    isPremium = rs.getInt("is_premium") == 1,
                                    premiumGrantedAt = rs.getString("premium_granted_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumExpiresAt = rs.getString("premium_expires_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumReason = rs.getString("premium_reason"),
                                    isBanned = rs.getInt("is_banned") == 1,
                                    bannedAt = rs.getString("banned_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    banReason = rs.getString("ban_reason"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                    updatedAt = LocalDateTime.parse(rs.getString("updated_at"), dateFormatter)
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
    
    fun getPremiumUserCount(): Int {
        val sql = "SELECT COUNT(*) FROM users WHERE is_premium = 1"
        
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
    
    fun expireOldPremiumUsers(): Int {
        val sql = """
            UPDATE users SET 
                is_premium = 0,
                premium_granted_at = NULL,
                premium_expires_at = NULL,
                premium_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE premium_expires_at IS NOT NULL 
            AND premium_expires_at <= DATETIME('now')
        """
        
        return try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate(sql)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to expire old premium users" }
            0
        }
    }
    
    // ===============================
    // BAN OPERATIONS (INTEGRATED)
    // ===============================
    
    fun banUser(userId: Long, reason: String, bannedByAdmin: Long): Boolean {
        val enhancedReason = "$reason (banned by admin $bannedByAdmin)"
        val sql = """
            UPDATE users SET 
                is_banned = 1,
                banned_at = CURRENT_TIMESTAMP,
                ban_reason = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
        """
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, enhancedReason)
                    stmt.setLong(2, userId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ban user $userId" }
            false
        }
    }
    
    fun unbanUser(userId: Long): Boolean {
        val sql = """
            UPDATE users SET 
                is_banned = 0,
                banned_at = NULL,
                ban_reason = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE telegram_id = ?
        """
        
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
        val sql = "SELECT 1 FROM users WHERE telegram_id = ? AND is_banned = 1"
        
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
    
    fun getBannedUser(userId: Long): BannedInfo? {
        val sql = "SELECT banned_at, ban_reason FROM users WHERE telegram_id = ? AND is_banned = 1"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, userId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            BannedInfo(
                                bannedAt = LocalDateTime.parse(rs.getString("banned_at"), dateFormatter),
                                reason = rs.getString("ban_reason")
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
    
    fun getAllBannedUsers(): List<User> {
        val sql = "SELECT * FROM users WHERE is_banned = 1 ORDER BY banned_at DESC"
        val bannedUsers = mutableListOf<User>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            bannedUsers.add(
                                User(
                                    telegramId = rs.getLong("telegram_id"),
                                    language = rs.getString("language"),
                                    keywords = rs.getString("keywords"),
                                    ignoreKeywords = rs.getString("ignore_keywords"),
                                    lastInteraction = LocalDateTime.parse(rs.getString("last_interaction"), dateFormatter),
                                    isPremium = rs.getInt("is_premium") == 1,
                                    premiumGrantedAt = rs.getString("premium_granted_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumExpiresAt = rs.getString("premium_expires_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    premiumReason = rs.getString("premium_reason"),
                                    isBanned = rs.getInt("is_banned") == 1,
                                    bannedAt = rs.getString("banned_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    },
                                    banReason = rs.getString("ban_reason"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                    updatedAt = LocalDateTime.parse(rs.getString("updated_at"), dateFormatter)
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
    
    // Get comprehensive user info for admin purposes
    fun getUserInfo(userId: Long): UserInfo? {
        val user = getUser(userId)
        
        return user?.let {
            UserInfo(
                userId = it.telegramId,
                username = null, // Will be filled by TelegramBot when needed
                language = it.language,
                isActive = it.keywords != null,
                isBanned = it.isBanned,
                bannedInfo = if (it.isBanned && it.bannedAt != null && it.banReason != null) {
                    BannedInfo(it.bannedAt, it.banReason)
                } else null,
                lastActivity = it.lastInteraction,
                isPremium = it.isPremium,
                premiumInfo = if (it.isPremium && it.premiumGrantedAt != null) {
                    PremiumInfo(
                        grantedAt = it.premiumGrantedAt,
                        expiresAt = it.premiumExpiresAt,
                        reason = it.premiumReason,
                        isActive = it.isPremiumActive,
                        daysRemaining = it.premiumDaysRemaining
                    )
                } else null
            )
        }
    }
    
    // ===============================
    // CHANNEL OPERATIONS (UNCHANGED)
    // ===============================
    
    fun addChannel(channelId: String, channelName: String?): Boolean {
        val sql = "INSERT OR IGNORE INTO channels (channel_id, channel_name) VALUES (?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.setString(2, channelName)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add channel $channelId" }
            false
        }
    }
    
    fun addChannelWithDetails(channelId: String, channelTag: String?, channelName: String?): Boolean {
        val sql = "INSERT OR REPLACE INTO channels (channel_id, channel_name, channel_tag) VALUES (?, ?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.setString(2, channelName)
                    stmt.setString(3, channelTag)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add channel $channelId with tag $channelTag" }
            false
        }
    }
    
    fun findChannelIdByTag(channelTag: String): String? {
        val cleanTag = channelTag.removePrefix("@").lowercase()
        val sql = "SELECT channel_id FROM channels WHERE LOWER(REPLACE(channel_tag, '@', '')) = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, cleanTag)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("channel_id") else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to find channel by tag $channelTag" }
            null
        }
    }
    
    fun updateChannelTag(channelId: String, newTag: String?, newName: String?): Boolean {
        val sql = "UPDATE channels SET channel_tag = ?, channel_name = ?, updated_at = CURRENT_TIMESTAMP WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, newTag)
                    stmt.setString(2, newName)
                    stmt.setString(3, channelId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update channel tag for $channelId" }
            false
        }
    }
    
    fun removeChannel(channelId: String): Boolean {
        val sql = "DELETE FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to remove channel $channelId" }
            false
        }
    }
    
    fun removeChannelById(channelId: String): Boolean = removeChannel(channelId)
    
    fun getAllChannels(): List<Channel> {
        val sql = "SELECT * FROM channels ORDER BY created_at ASC"
        val channels = mutableListOf<Channel>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channels.add(
                                Channel(
                                    id = rs.getLong("id"),
                                    channelId = rs.getString("channel_id"),
                                    channelName = rs.getString("channel_name"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channels" }
        }
        
        return channels
    }
    
    fun getAllChannelsWithDetails(): List<ChannelDetails> {
        val sql = "SELECT * FROM channels ORDER BY created_at ASC"
        val channels = mutableListOf<ChannelDetails>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channels.add(
                                ChannelDetails(
                                    id = rs.getLong("id"),
                                    channelId = rs.getString("channel_id"),
                                    channelName = rs.getString("channel_name"),
                                    channelTag = rs.getString("channel_tag"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                    updatedAt = rs.getString("updated_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channels with details" }
        }
        
        return channels
    }
    
    fun getAllChannelIds(): List<String> {
        val sql = "SELECT channel_id FROM channels"
        val channelIds = mutableListOf<String>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channelIds.add(rs.getString("channel_id"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channel IDs" }
        }
        
        return channelIds
    }
    
    fun channelExists(channelId: String): Boolean {
        val sql = "SELECT 1 FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if channel exists $channelId" }
            false
        }
    }
    
    fun channelExistsById(channelId: String): Boolean = channelExists(channelId)
    
    // ✅ MONITORING: Check pool health
    fun getPoolStats(): Map<String, Any> {
        return try {
            mapOf(
                "activeConnections" to dataSource.hikariPoolMXBean.activeConnections,
                "idleConnections" to dataSource.hikariPoolMXBean.idleConnections,
                "totalConnections" to dataSource.hikariPoolMXBean.totalConnections,
                "threadsAwaitingConnection" to dataSource.hikariPoolMXBean.threadsAwaitingConnection,
                "maximumPoolSize" to dataSource.maximumPoolSize,
                "minimumIdle" to dataSource.minimumIdle
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pool stats" }
            mapOf("error" to "Unable to retrieve pool statistics")
        }
    }
    
    // ✅ PROPER CLEANUP
    fun close() {
        logger.info { "Closing database connection pool" }
        try {
            dataSource.close()
            logger.info { "Database connection pool closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing database connection pool" }
        }
    }
    
    // ✅ HEALTH CHECK for monitoring
    fun isHealthy(): Boolean {
        return try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 1").use { rs ->
                        rs.next() && rs.getInt(1) == 1
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Database health check failed" }
            false
        }
    }
}
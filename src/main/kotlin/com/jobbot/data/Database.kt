package com.jobbot.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.jobbot.data.repositories.UserRepository
import com.jobbot.data.repositories.ChannelRepository
import com.jobbot.data.repositories.AdminRepository
import com.jobbot.data.migrations.DatabaseMigration
import com.jobbot.data.models.*
import com.jobbot.shared.getLogger
import java.sql.Connection
import javax.sql.DataSource

class Database(private val databasePath: String) {
    private val logger = getLogger("Database")
    
    // ✅ CONNECTION POOL: Created once, reused for all operations
    private val dataSource: HikariDataSource
    
    // Repository instances
    val userRepository: UserRepository
    val channelRepository: ChannelRepository
    val adminRepository: AdminRepository
    
    init {
        dataSource = createDataSource()
        
        // Initialize repositories with connection provider
        userRepository = UserRepository { getConnection() }
        channelRepository = ChannelRepository { getConnection() }
        adminRepository = AdminRepository({ getConnection() }, userRepository)
        
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
            
            // Leak detection (helps find connection leaks during development)
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
    
    // Delegate methods to repositories for backward compatibility
    
    // User operations
    fun getUser(telegramId: Long): User? = userRepository.getUser(telegramId)
    fun createUser(user: User): Boolean = userRepository.createUser(user)
    fun updateUser(user: User): Boolean = userRepository.updateUser(user)
    fun getAllUsers(): List<User> = userRepository.getAllUsers()
    fun getActiveUsersCount(): Int = userRepository.getActiveUsersCount()
    fun updateUserActivity(telegramId: Long) = userRepository.updateUserActivity(telegramId)
    fun getUserActivity(telegramId: Long): UserActivity? = userRepository.getUserActivity(telegramId)
    fun batchUpdateUsers(updates: List<Pair<Long, String>>): Boolean = userRepository.batchUpdateUsers(updates)
    
    // Channel operations
    fun addChannel(channelId: String, channelName: String?): Boolean = channelRepository.addChannel(channelId, channelName)
    fun addChannelWithDetails(channelId: String, channelTag: String?, channelName: String?): Boolean = 
        channelRepository.addChannelWithDetails(channelId, channelTag, channelName)
    fun findChannelIdByTag(channelTag: String): String? = channelRepository.findChannelIdByTag(channelTag)
    fun updateChannelTag(channelId: String, newTag: String?, newName: String?): Boolean = 
        channelRepository.updateChannelTag(channelId, newTag, newName)
    fun removeChannel(channelId: String): Boolean = channelRepository.removeChannel(channelId)
    fun removeChannelById(channelId: String): Boolean = channelRepository.removeChannelById(channelId)
    fun getAllChannels(): List<Channel> = channelRepository.getAllChannels()
    fun getAllChannelsWithDetails(): List<ChannelDetails> = channelRepository.getAllChannelsWithDetails()
    fun getAllChannelIds(): List<String> = channelRepository.getAllChannelIds()
    fun channelExists(channelId: String): Boolean = channelRepository.channelExists(channelId)
    fun channelExistsById(channelId: String): Boolean = channelRepository.channelExistsById(channelId)
    
    // Admin operations
    fun banUser(userId: Long, reason: String, bannedByAdmin: Long): Boolean = 
        adminRepository.banUser(userId, reason, bannedByAdmin)
    fun unbanUser(userId: Long): Boolean = adminRepository.unbanUser(userId)
    fun isUserBanned(userId: Long): Boolean = adminRepository.isUserBanned(userId)
    fun getBannedUser(userId: Long): BannedUser? = adminRepository.getBannedUser(userId)
    fun getAllBannedUsers(): List<BannedUser> = adminRepository.getAllBannedUsers()
    fun getUserInfo(userId: Long): UserInfo? = adminRepository.getUserInfo(userId)
    
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

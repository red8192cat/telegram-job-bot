package com.jobbot.data.migrations

import java.sql.Connection

class DatabaseMigration(private val logger: io.github.oshai.kotlinlogging.KLogger) {
    
    companion object {
        const val CURRENT_VERSION = 5  // Updated to version 5
    }
    
    fun runMigrations(connection: Connection) {
        logger.info { "Checking database version and running migrations if needed..." }
        
        createSchemaVersionTable(connection)
        val currentVersion = getCurrentSchemaVersion(connection)
        logger.info { "Current database version: $currentVersion, target version: $CURRENT_VERSION" }
        
        if (currentVersion < CURRENT_VERSION) {
            logger.info { "Database migration needed from version $currentVersion to $CURRENT_VERSION" }
            runMigrationsFromVersion(connection, currentVersion)
        } else {
            logger.info { "Database schema is up to date" }
        }
    }
    
    private fun createSchemaVersionTable(connection: Connection) {
        val sql = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER PRIMARY KEY,
                applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                description TEXT
            )
        """
        connection.createStatement().execute(sql)
        
        // Insert initial version if table is empty
        val checkSql = "SELECT COUNT(*) FROM schema_version"
        connection.createStatement().executeQuery(checkSql).use { rs ->
            if (rs.next() && rs.getInt(1) == 0) {
                val insertSql = "INSERT INTO schema_version (version, description) VALUES (1, 'Initial schema')"
                connection.createStatement().execute(insertSql)
                logger.info { "Initialized schema version table with version 1" }
            }
        }
    }
    
    private fun getCurrentSchemaVersion(connection: Connection): Int {
        val sql = "SELECT MAX(version) FROM schema_version"
        connection.createStatement().executeQuery(sql).use { rs ->
            return if (rs.next()) rs.getInt(1) else 1
        }
    }
    
    private fun runMigrationsFromVersion(connection: Connection, fromVersion: Int) {
        try {
            connection.autoCommit = false
            
            for (version in (fromVersion + 1)..CURRENT_VERSION) {
                logger.info { "Applying migration to version $version..." }
                
                when (version) {
                    2 -> migrateToVersion2(connection)
                    3 -> migrateToVersion3(connection)
                    4 -> migrateToVersion4(connection)
                    5 -> migrateToVersion5(connection)  // NEW: Ultra-simplified schema
                    // Add future migrations here
                }
                
                // Record successful migration
                val recordSql = "INSERT INTO schema_version (version, description) VALUES (?, ?)"
                connection.prepareStatement(recordSql).use { stmt ->
                    stmt.setInt(1, version)
                    stmt.setString(2, getMigrationDescription(version))
                    stmt.executeUpdate()
                }
                
                logger.info { "Successfully migrated to version $version" }
            }
            
            connection.commit()
            logger.info { "All migrations completed successfully!" }
            
        } catch (e: Exception) {
            connection.rollback()
            logger.error(e) { "Migration failed, rolling back" }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }
    
    private fun migrateToVersion2(connection: Connection) {
        logger.info { "Migration 2: Enhanced channel management and user activity fixes" }
        
        try {
            // Add missing columns to channels table
            logger.info { "Adding channel_tag and updated_at columns to channels table..." }
            addColumnIfNotExists(connection, "channels", "channel_tag", "TEXT")
            addColumnIfNotExists(connection, "channels", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP")
            
            // Create missing index
            logger.info { "Creating index on channel_tag..." }
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_channels_tag ON channels(channel_tag)"
            )
            
            // Fix user_activity table - add UNIQUE constraint
            logger.info { "Fixing user_activity table with UNIQUE constraint..." }
            
            val hasUniqueConstraint = checkUniqueConstraintExists(connection, "user_activity", "user_telegram_id")
            
            if (!hasUniqueConstraint) {
                // Recreate table with proper constraint
                connection.createStatement().execute("""
                    CREATE TABLE user_activity_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_telegram_id INTEGER NOT NULL UNIQUE,
                        last_interaction DATETIME DEFAULT CURRENT_TIMESTAMP,
                        command_count INTEGER DEFAULT 0,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (user_telegram_id) REFERENCES users(telegram_id)
                    )
                """)
                
                connection.createStatement().execute("""
                    INSERT INTO user_activity_new (user_telegram_id, last_interaction, command_count, created_at)
                    SELECT 
                        user_telegram_id,
                        MAX(last_interaction) as last_interaction,
                        MAX(command_count) as command_count,
                        MIN(created_at) as created_at
                    FROM user_activity 
                    GROUP BY user_telegram_id
                """)
                
                connection.createStatement().execute("DROP TABLE user_activity")
                connection.createStatement().execute("ALTER TABLE user_activity_new RENAME TO user_activity")
                
                connection.createStatement().execute(
                    "CREATE INDEX IF NOT EXISTS idx_activity_user_id ON user_activity(user_telegram_id)"
                )
                
                logger.info { "user_activity table recreated with UNIQUE constraint" }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate to version 2" }
            throw e
        }
    }
    
    private fun migrateToVersion3(connection: Connection) {
        logger.info { "Migration 3: Premium user management system" }
        
        try {
            // Add premium columns to users table
            addColumnIfNotExists(connection, "users", "is_premium", "INTEGER DEFAULT 0 CHECK (is_premium IN (0, 1))")
            addColumnIfNotExists(connection, "users", "premium_granted_at", "DATETIME")
            addColumnIfNotExists(connection, "users", "premium_granted_by", "INTEGER")
            
            // Create premium_users table
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS premium_users (
                    user_id INTEGER PRIMARY KEY,
                    granted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    granted_by_admin INTEGER NOT NULL,
                    reason TEXT,
                    is_active INTEGER DEFAULT 1 CHECK (is_active IN (0, 1)),
                    revoked_at DATETIME,
                    revoked_by_admin INTEGER,
                    revoke_reason TEXT,
                    FOREIGN KEY (user_id) REFERENCES users(telegram_id)
                )
            """)
            
            // Create indexes
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_premium_users_user_id ON premium_users(user_id)"
            )
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_premium_users_active ON premium_users(is_active)"
            )
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_users_premium ON users(is_premium)"
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate to version 3" }
            throw e
        }
    }
    
    private fun migrateToVersion4(connection: Connection) {
        logger.info { "Migration 4: Placeholder for any missing migrations" }
        // Placeholder - add any missing migrations between 3 and 5 here
    }
    
    private fun migrateToVersion5(connection: Connection) {
        logger.info { "Migration 5: Ultra-simplified schema - consolidate all user data" }
        
        try {
            // Step 1: Add new columns to users table
            logger.info { "Adding new columns to users table..." }
            addColumnIfNotExists(connection, "users", "is_banned", "INTEGER DEFAULT 0 CHECK (is_banned IN (0, 1))")
            addColumnIfNotExists(connection, "users", "banned_at", "DATETIME")
            addColumnIfNotExists(connection, "users", "ban_reason", "TEXT")
            addColumnIfNotExists(connection, "users", "last_interaction", "DATETIME DEFAULT CURRENT_TIMESTAMP")
            addColumnIfNotExists(connection, "users", "premium_expires_at", "DATETIME")
            addColumnIfNotExists(connection, "users", "premium_reason", "TEXT")
            
            // Step 2: Migrate banned_users data if table exists
            if (tableExists(connection, "banned_users")) {
                logger.info { "Migrating banned_users data..." }
                connection.createStatement().execute("""
                    UPDATE users SET 
                        is_banned = 1,
                        banned_at = (SELECT bu.banned_at FROM banned_users bu WHERE bu.user_id = users.telegram_id),
                        ban_reason = (SELECT bu.reason || ' (banned by admin ' || bu.banned_by_admin || ')' 
                                     FROM banned_users bu WHERE bu.user_id = users.telegram_id)
                    WHERE telegram_id IN (SELECT user_id FROM banned_users)
                """)
            }
            
            // Step 3: Migrate user_activity data if table exists
            if (tableExists(connection, "user_activity")) {
                logger.info { "Migrating user_activity data..." }
                connection.createStatement().execute("""
                    UPDATE users SET 
                        last_interaction = COALESCE(
                            (SELECT ua.last_interaction FROM user_activity ua WHERE ua.user_telegram_id = users.telegram_id),
                            users.created_at
                        )
                    WHERE EXISTS (SELECT 1 FROM user_activity ua WHERE ua.user_telegram_id = users.telegram_id)
                """)
            }
            
            // Step 4: Migrate premium_users data if table exists and users.premium_granted_by exists
            if (tableExists(connection, "premium_users") && columnExists(connection, "users", "premium_granted_by")) {
                logger.info { "Migrating premium_users data..." }
                connection.createStatement().execute("""
                    UPDATE users SET 
                        premium_reason = COALESCE(
                            (SELECT COALESCE(p.reason, 'Premium access') || ' (granted by admin ' || users.premium_granted_by || ')'
                             FROM premium_users p WHERE p.user_id = users.telegram_id AND p.is_active = 1),
                            'Premium access (granted by admin ' || users.premium_granted_by || ')'
                        )
                    WHERE is_premium = 1 AND premium_granted_by IS NOT NULL
                """)
            }
            
            // Step 5: Drop old columns if they exist
            logger.info { "Cleaning up old columns and tables..." }
            if (columnExists(connection, "users", "premium_granted_by")) {
                // SQLite doesn't support DROP COLUMN directly, but we'll leave it for now
                // It will be ignored in the new code
                logger.info { "Note: premium_granted_by column left in place (will be ignored)" }
            }
            
            // Step 6: Drop old tables if they exist
            if (tableExists(connection, "user_activity")) {
                connection.createStatement().execute("DROP TABLE user_activity")
                logger.info { "Dropped user_activity table" }
            }
            
            if (tableExists(connection, "premium_users")) {
                connection.createStatement().execute("DROP TABLE premium_users")
                logger.info { "Dropped premium_users table" }
            }
            
            if (tableExists(connection, "banned_users")) {
                connection.createStatement().execute("DROP TABLE banned_users")
                logger.info { "Dropped banned_users table" }
            }
            
            // Step 7: Create new indexes
            logger.info { "Creating new indexes..." }
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_users_banned ON users(is_banned)"
            )
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_users_expires ON users(premium_expires_at)"
            )
            
            logger.info { "Migration to version 5 completed successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate to version 5" }
            throw e
        }
    }
    
    private fun addColumnIfNotExists(connection: Connection, tableName: String, columnName: String, columnDef: String) {
        if (!columnExists(connection, tableName, columnName)) {
            val sql = "ALTER TABLE $tableName ADD COLUMN $columnName $columnDef"
            connection.createStatement().execute(sql)
            logger.info { "Added column $columnName to table $tableName" }
        } else {
            logger.debug { "Column $columnName already exists in table $tableName" }
        }
    }
    
    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean {
        val metadata = connection.metaData
        metadata.getColumns(null, null, tableName, columnName).use { rs ->
            return rs.next()
        }
    }
    
    private fun tableExists(connection: Connection, tableName: String): Boolean {
        val metadata = connection.metaData
        metadata.getTables(null, null, tableName, null).use { rs ->
            return rs.next()
        }
    }
    
    private fun checkUniqueConstraintExists(connection: Connection, tableName: String, columnName: String): Boolean {
        return try {
            val testSql = "INSERT INTO $tableName ($columnName) VALUES (?), (?)"
            val stmt = connection.prepareStatement(testSql)
            stmt.setLong(1, -999999)
            stmt.setLong(2, -999999)
            stmt.executeUpdate()
            connection.createStatement().execute("DELETE FROM $tableName WHERE $columnName = -999999")
            false
        } catch (e: Exception) {
            true
        }
    }
    
    private fun getMigrationDescription(version: Int): String {
        return when (version) {
            2 -> "Enhanced channel management and user activity fixes"
            3 -> "Premium user management system"
            4 -> "Placeholder migration"
            5 -> "Ultra-simplified schema - all user data consolidated"
            else -> "Migration to version $version"
        }
    }
}
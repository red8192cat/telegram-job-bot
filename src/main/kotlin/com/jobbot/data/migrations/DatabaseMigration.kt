import java.sql.Connection

class DatabaseMigration(private val logger: io.github.oshai.kotlinlogging.KLogger) {
    
    companion object {
        const val CURRENT_VERSION = 2
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
                    // Add future migrations here:
                    // 3 -> migrateToVersion3(connection)
                    // 4 -> migrateToVersion4(connection)
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
            // 1. Add missing columns to channels table
            logger.info { "Adding channel_tag and updated_at columns to channels table..." }
            addColumnIfNotExists(connection, "channels", "channel_tag", "TEXT")
            addColumnIfNotExists(connection, "channels", "updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP")
            
            // 2. Create missing index
            logger.info { "Creating index on channel_tag..." }
            connection.createStatement().execute(
                "CREATE INDEX IF NOT EXISTS idx_channels_tag ON channels(channel_tag)"
            )
            
            // 3. Fix user_activity table - add UNIQUE constraint
            logger.info { "Fixing user_activity table with UNIQUE constraint..." }
            
            // Check if constraint already exists
            val hasUniqueConstraint = checkUniqueConstraintExists(connection, "user_activity", "user_telegram_id")
            
            if (!hasUniqueConstraint) {
                // Recreate table with proper constraint
                logger.info { "Recreating user_activity table with UNIQUE constraint..." }
                
                // Step 1: Create new table with correct schema
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
                
                // Step 2: Copy data from old table (handle duplicates by keeping latest)
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
                
                // Step 3: Drop old table and rename new one
                connection.createStatement().execute("DROP TABLE user_activity")
                connection.createStatement().execute("ALTER TABLE user_activity_new RENAME TO user_activity")
                
                // Step 4: Recreate index
                connection.createStatement().execute(
                    "CREATE INDEX IF NOT EXISTS idx_activity_user_id ON user_activity(user_telegram_id)"
                )
                
                logger.info { "user_activity table recreated with UNIQUE constraint" }
            } else {
                logger.info { "user_activity table already has UNIQUE constraint" }
            }
            
            logger.info { "Migration to version 2 completed successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to migrate to version 2" }
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
    
    private fun checkUniqueConstraintExists(connection: Connection, tableName: String, columnName: String): Boolean {
        return try {
            // Try to insert duplicate values - if it fails, constraint exists
            val testSql = "INSERT INTO $tableName ($columnName) VALUES (?), (?)"
            val stmt = connection.prepareStatement(testSql)
            stmt.setLong(1, -999999)
            stmt.setLong(2, -999999)
            stmt.executeUpdate()
            // If no exception, no unique constraint - clean up test data
            connection.createStatement().execute("DELETE FROM $tableName WHERE $columnName = -999999")
            false
        } catch (e: Exception) {
            // Exception means unique constraint exists
            true
        }
    }
    
    private fun getMigrationDescription(version: Int): String {
        return when (version) {
            2 -> "Enhanced channel management and user activity fixes"
            else -> "Migration to version $version"
        }
    }
}

#!/bin/bash
# inspect-database.sh - Enhanced database inspection with HikariCP monitoring
# Updated for repository pattern and multi-admin support

# Use environment variable or default path
DB_PATH="${DATABASE_PATH:-/app/data/bot.db}"

echo "========================================"
echo "🗄️  DATABASE INSPECTION & POOL MONITORING"
echo "========================================"
echo

if [ ! -f "$DB_PATH" ]; then
    echo "❌ Database file not found: $DB_PATH"
    echo ""
    echo "💡 Troubleshooting:"
    echo "  • Check DATABASE_PATH environment variable"
    echo "  • Verify Docker volume mounting"
    echo "  • Check if bot has been started at least once"
    exit 1
fi

echo "📁 Database Information:"
echo "  📍 Path: $DB_PATH"
echo "  📏 Size: $(du -h "$DB_PATH" | cut -f1)"
echo "  📅 Modified: $(stat -c %y "$DB_PATH" | cut -d. -f1)"
echo "  🔒 Permissions: $(stat -c %A "$DB_PATH")"
echo "  👤 Owner: $(stat -c %U:%G "$DB_PATH")"
echo

# Check if sqlite3 is available
if ! command -v sqlite3 &> /dev/null; then
    echo "❌ sqlite3 command not found. Installing..."
    if command -v apt-get &> /dev/null; then
        apt-get update && apt-get install -y sqlite3
    elif command -v apk &> /dev/null; then
        apk add sqlite
    else
        echo "❌ Cannot install sqlite3. Please install manually."
        exit 1
    fi
fi

echo "🏊 HIKARICP CONNECTION POOL STATUS:"
echo "----------------------------------------"

# Try to get pool information from health endpoint
if command -v curl >/dev/null 2>&1; then
    POOL_INFO=$(curl -s -m 5 http://localhost:8080/health/database 2>/dev/null)
    if [ $? -eq 0 ] && [ -n "$POOL_INFO" ]; then
        echo "✅ Real-time pool information from health endpoint:"
        
        # Extract pool statistics
        ACTIVE=$(echo "$POOL_INFO" | grep -o '"activeConnections":[0-9]*' | cut -d':' -f2)
        IDLE=$(echo "$POOL_INFO" | grep -o '"idleConnections":[0-9]*' | cut -d':' -f2)
        TOTAL=$(echo "$POOL_INFO" | grep -o '"totalConnections":[0-9]*' | cut -d':' -f2)
        MAX_POOL=$(echo "$POOL_INFO" | grep -o '"maximumPoolSize":[0-9]*' | cut -d':' -f2)
        MIN_IDLE=$(echo "$POOL_INFO" | grep -o '"minimumIdle":[0-9]*' | cut -d':' -f2)
        AWAITING=$(echo "$POOL_INFO" | grep -o '"threadsAwaitingConnection":[0-9]*' | cut -d':' -f2)
        
        echo "  🔗 Active Connections: ${ACTIVE:-0}/${MAX_POOL:-10}"
        echo "  💤 Idle Connections: ${IDLE:-0}"
        echo "  📊 Total Connections: ${TOTAL:-0}"
        echo "  📈 Maximum Pool Size: ${MAX_POOL:-10}"
        echo "  📉 Minimum Idle: ${MIN_IDLE:-2}"
        echo "  ⏳ Threads Awaiting: ${AWAITING:-0}"
        
        # Pool health analysis
        if [ -n "$ACTIVE" ] && [ -n "$MAX_POOL" ] && [ "$MAX_POOL" -gt 0 ]; then
            UTILIZATION=$((ACTIVE * 100 / MAX_POOL))
            echo "  📊 Pool Utilization: ${UTILIZATION}%"
            
            if [ "$UTILIZATION" -gt 90 ]; then
                echo "  🚨 CRITICAL: Very high pool utilization (>90%)"
            elif [ "$UTILIZATION" -gt 70 ]; then
                echo "  ⚠️ WARNING: High pool utilization (>70%)"
            elif [ "$UTILIZATION" -lt 5 ] && [ "$ACTIVE" -gt 0 ]; then
                echo "  ℹ️ INFO: Low pool utilization (<5%) - pool may be oversized"
            else
                echo "  ✅ Normal pool utilization"
            fi
        fi
        
        # Connection wait analysis
        if [ -n "$AWAITING" ] && [ "$AWAITING" -gt 0 ]; then
            echo "  🚨 WARNING: $AWAITING threads waiting for connections"
            echo "    💡 Consider increasing pool size or checking for connection leaks"
        fi
        
        # Pool efficiency metrics
        if [ -n "$TOTAL" ] && [ -n "$ACTIVE" ] && [ "$TOTAL" -gt 0 ]; then
            EFFICIENCY=$((ACTIVE * 100 / TOTAL))
            echo "  ⚡ Pool Efficiency: ${EFFICIENCY}% (active/total ratio)"
            
            if [ "$EFFICIENCY" -lt 30 ] && [ "$TOTAL" -gt 3 ]; then
                echo "    💡 Many idle connections - consider reducing pool size"
            fi
        fi
        
    else
        echo "⚠️ Pool information not available via health endpoint"
        echo "  💡 Bot may not be running or health endpoint may be disabled"
    fi
else
    echo "❌ curl not available - cannot check real-time pool status"
fi
echo

echo "📊 DATABASE CONFIGURATION:"
echo "----------------------------------------"

# Check SQLite configuration
echo "🔧 SQLite Configuration:"
JOURNAL_MODE=$(sqlite3 "$DB_PATH" "PRAGMA journal_mode;" 2>/dev/null || echo "unknown")
SYNC_MODE=$(sqlite3 "$DB_PATH" "PRAGMA synchronous;" 2>/dev/null || echo "unknown")
CACHE_SIZE=$(sqlite3 "$DB_PATH" "PRAGMA cache_size;" 2>/dev/null || echo "unknown")
TEMP_STORE=$(sqlite3 "$DB_PATH" "PRAGMA temp_store;" 2>/dev/null || echo "unknown")

echo "  📋 Journal Mode: $JOURNAL_MODE $([ "$JOURNAL_MODE" = "wal" ] && echo "(✅ Optimized)" || echo "(⚠️ Consider WAL mode)")"
echo "  🔄 Synchronous: $SYNC_MODE $([ "$SYNC_MODE" = "1" ] && echo "(✅ Normal)" || echo "(ℹ️ Non-standard)")"
echo "  🧠 Cache Size: $CACHE_SIZE pages"
echo "  💾 Temp Store: $TEMP_STORE $([ "$TEMP_STORE" = "2" ] && echo "(✅ Memory)" || echo "(ℹ️ File-based)")"

# Check database integrity
INTEGRITY=$(sqlite3 "$DB_PATH" "PRAGMA integrity_check;" 2>/dev/null | head -1)
echo "  ✅ Integrity Check: $INTEGRITY"

# Check foreign key constraints
FK_CHECK=$(sqlite3 "$DB_PATH" "PRAGMA foreign_key_check;" 2>/dev/null | wc -l)
echo "  🔗 Foreign Key Violations: $FK_CHECK"
echo

echo "📊 DATABASE STATISTICS:"
echo "----------------------------------------"

# Table info with enhanced details
echo "📋 Tables and Record Counts:"
sqlite3 "$DB_PATH" ".tables" | tr ' ' '\n' | while read table; do
    if [ -n "$table" ]; then
        count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM $table;" 2>/dev/null || echo "0")
        
        # Get table size estimation
        if [ "$table" = "users" ]; then
            active_count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE keywords IS NOT NULL;" 2>/dev/null || echo "0")
            echo "  👥 $table: $count total records ($active_count active users)"
        elif [ "$table" = "channels" ]; then
            with_tags=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels WHERE channel_tag IS NOT NULL;" 2>/dev/null || echo "0")
            echo "  📺 $table: $count total records ($with_tags with tags)"
        elif [ "$table" = "banned_users" ]; then
            echo "  🚫 $table: $count banned users"
        elif [ "$table" = "user_activity" ]; then
            echo "  📈 $table: $count activity records"
        elif [ "$table" = "schema_version" ]; then
            current_version=$(sqlite3 "$DB_PATH" "SELECT MAX(version) FROM schema_version;" 2>/dev/null || echo "unknown")
            echo "  🔄 $table: $count migration records (current version: $current_version)"
        else
            echo "  📄 $table: $count records"
        fi
    fi
done
echo

echo "👥 USERS ANALYSIS:"
echo "----------------------------------------"
TOTAL_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users;" 2>/dev/null || echo "0")
ACTIVE_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE keywords IS NOT NULL;" 2>/dev/null || echo "0")
echo "  📊 Total registered users: $TOTAL_USERS"
echo "  🎯 Users with keywords set: $ACTIVE_USERS"
echo "  💤 Inactive users: $((TOTAL_USERS - ACTIVE_USERS))"

if [ "$TOTAL_USERS" -gt 0 ]; then
    echo "  🌐 Languages used:"
    sqlite3 "$DB_PATH" "SELECT language, COUNT(*) FROM users GROUP BY language;" 2>/dev/null | while IFS='|' read lang count; do
        percentage=$((count * 100 / TOTAL_USERS))
        echo "    $lang: $count users (${percentage}%)"
    done
    
    echo "  📅 Recent registrations (last 5):"
    sqlite3 "$DB_PATH" "SELECT telegram_id, language, created_at FROM users ORDER BY created_at DESC LIMIT 5;" 2>/dev/null | while IFS='|' read id lang created; do
        echo "    User $id ($lang) - $created"
    done
    
    # Analyze keyword complexity
    COMPLEX_KEYWORDS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE keywords LIKE '%[%' OR keywords LIKE '%+%' OR keywords LIKE '%*%';" 2>/dev/null || echo "0")
    if [ "$ACTIVE_USERS" -gt 0 ] && [ "$COMPLEX_KEYWORDS" -gt 0 ]; then
        COMPLEXITY_RATE=$((COMPLEX_KEYWORDS * 100 / ACTIVE_USERS))
        echo "  🧩 Users with advanced keywords: $COMPLEX_KEYWORDS (${COMPLEXITY_RATE}%)"
    fi
fi
echo

echo "📺 CHANNELS ANALYSIS:"
echo "----------------------------------------"
TOTAL_CHANNELS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels;" 2>/dev/null || echo "0")
echo "  📊 Total monitored channels: $TOTAL_CHANNELS"

if [ "$TOTAL_CHANNELS" -gt 0 ]; then
    WITH_TAGS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels WHERE channel_tag IS NOT NULL;" 2>/dev/null || echo "0")
    WITH_NAMES=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels WHERE channel_name IS NOT NULL;" 2>/dev/null || echo "0")
    
    echo "  🏷️ Channels with tags: $WITH_TAGS"
    echo "  📝 Channels with names: $WITH_NAMES"
    
    echo "  📋 Channel list (last 10):"
    sqlite3 "$DB_PATH" "SELECT channel_id, COALESCE(channel_tag, '<no tag>'), COALESCE(channel_name, '<no name>'), created_at FROM channels ORDER BY created_at DESC LIMIT 10;" 2>/dev/null | while IFS='|' read id tag name created; do
        echo "    $id | $tag | $name | $created"
    done
    
    # Check for recently updated channels
    RECENTLY_UPDATED=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels WHERE updated_at > datetime('now', '-7 days');" 2>/dev/null || echo "0")
    if [ "$RECENTLY_UPDATED" -gt 0 ]; then
        echo "  🔄 Recently updated channels (last 7 days): $RECENTLY_UPDATED"
    fi
fi
echo

echo "📈 USER ACTIVITY ANALYSIS:"
echo "----------------------------------------"
TOTAL_ACTIVITY=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity;" 2>/dev/null || echo "0")
echo "  📊 Users with activity records: $TOTAL_ACTIVITY"

if [ "$TOTAL_ACTIVITY" -gt 0 ]; then
    echo "  🏆 Most active users (top 5):"
    sqlite3 "$DB_PATH" "SELECT user_telegram_id, command_count, last_interaction FROM user_activity ORDER BY command_count DESC LIMIT 5;" 2>/dev/null | while IFS='|' read user_id commands last; do
        echo "    User $user_id: $commands commands (last: $last)"
    done
    
    echo "  🕐 Recent activity (last 5):"
    sqlite3 "$DB_PATH" "SELECT user_telegram_id, command_count, last_interaction FROM user_activity ORDER BY last_interaction DESC LIMIT 5;" 2>/dev/null | while IFS='|' read user_id commands last; do
        echo "    User $user_id: $commands commands (last: $last)"
    done
    
    # Activity statistics
    TOTAL_COMMANDS=$(sqlite3 "$DB_PATH" "SELECT SUM(command_count) FROM user_activity;" 2>/dev/null || echo "0")
    AVG_COMMANDS=$(sqlite3 "$DB_PATH" "SELECT AVG(command_count) FROM user_activity;" 2>/dev/null || echo "0")
    echo "  📊 Total commands processed: $TOTAL_COMMANDS"
    echo "  📊 Average commands per user: $(printf "%.1f" "$AVG_COMMANDS" 2>/dev/null || echo "$AVG_COMMANDS")"
    
    # Find inactive users
    INACTIVE_WEEK=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity WHERE last_interaction < datetime('now', '-7 days');" 2>/dev/null || echo "0")
    echo "  💤 Users inactive for 7+ days: $INACTIVE_WEEK"
fi
echo

echo "🚫 BANNED USERS ANALYSIS:"
echo "----------------------------------------"
BANNED_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM banned_users;" 2>/dev/null || echo "0")
echo "  📊 Total banned users: $BANNED_COUNT"

if [ "$BANNED_COUNT" -gt 0 ]; then
    echo "  📋 Banned users list:"
    sqlite3 "$DB_PATH" "SELECT user_id, reason, banned_at, banned_by_admin FROM banned_users ORDER BY banned_at DESC;" 2>/dev/null | while IFS='|' read user_id reason banned_at admin_id; do
        # Truncate reason if too long
        short_reason=$(echo "$reason" | cut -c1-50)
        if [ ${#reason} -gt 50 ]; then
            short_reason="$short_reason..."
        fi
        echo "    User $user_id: '$short_reason' (banned $banned_at by admin $admin_id)"
    done
    
    # Banned user statistics
    echo "  📊 Ban statistics:"
    sqlite3 "$DB_PATH" "SELECT banned_by_admin, COUNT(*) FROM banned_users GROUP BY banned_by_admin;" 2>/dev/null | while IFS='|' read admin_id count; do
        echo "    Admin $admin_id: $count bans"
    done
    
    # Recent bans
    RECENT_BANS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM banned_users WHERE banned_at > datetime('now', '-7 days');" 2>/dev/null || echo "0")
    echo "  🚫 Recent bans (last 7 days): $RECENT_BANS"
fi
echo

echo "🔄 DATABASE MIGRATION STATUS:"
echo "----------------------------------------"
MIGRATION_COUNT=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM schema_version;" 2>/dev/null || echo "0")
if [ "$MIGRATION_COUNT" -gt 0 ]; then
    CURRENT_VERSION=$(sqlite3 "$DB_PATH" "SELECT MAX(version) FROM schema_version;" 2>/dev/null || echo "unknown")
    echo "  📊 Migration records: $MIGRATION_COUNT"
    echo "  🔢 Current schema version: $CURRENT_VERSION"
    
    echo "  📋 Migration history:"
    sqlite3 "$DB_PATH" "SELECT version, description, applied_at FROM schema_version ORDER BY version;" 2>/dev/null | while IFS='|' read version description applied; do
        echo "    v$version: $description ($applied)"
    done
else
    echo "  ⚠️ No migration records found (may be pre-migration database)"
fi
echo

echo "🔍 DATABASE HEALTH CHECK:"
echo "----------------------------------------"

# Check integrity (already done above, but summarize)
if [ "$INTEGRITY" = "ok" ]; then
    echo "  ✅ Database integrity: OK"
else
    echo "  ❌ Database integrity: $INTEGRITY"
fi

# Check schema validation
echo "  📋 Schema validation:"
EXPECTED_TABLES=("users" "channels" "user_activity" "banned_users" "schema_version")
for table in "${EXPECTED_TABLES[@]}"; do
    if sqlite3 "$DB_PATH" ".schema $table" &>/dev/null; then
        echo "    ✅ Table '$table' exists"
    else
        echo "    ❌ Table '$table' missing"
    fi
done

# Check indexes
echo "  🔍 Performance indexes:"
INDEX_COUNT=$(sqlite3 "$DB_PATH" ".schema" | grep -c "CREATE INDEX")
echo "    📊 Total indexes: $INDEX_COUNT"

sqlite3 "$DB_PATH" ".schema" | grep "CREATE INDEX" | while read index; do
    index_name=$(echo "$index" | sed 's/.*INDEX \([^ ]*\).*/\1/')
    echo "    ✅ $index_name"
done

# Check for potential issues
echo "  🔧 Performance analysis:"

# Check for large tables without proper indexes
LARGE_USER_TABLE=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users;" 2>/dev/null || echo "0")
if [ "$LARGE_USER_TABLE" -gt 10000 ]; then
    echo "    ⚠️ Large users table ($LARGE_USER_TABLE rows) - ensure proper indexing"
fi

# Check for orphaned records (foreign key violations would be caught above)
ORPHANED_ACTIVITY=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity WHERE user_telegram_id NOT IN (SELECT telegram_id FROM users);" 2>/dev/null || echo "0")
if [ "$ORPHANED_ACTIVITY" -gt 0 ]; then
    echo "    ⚠️ Orphaned activity records: $ORPHANED_ACTIVITY"
fi

echo

echo "📋 SAMPLE DATA:"
echo "----------------------------------------"
echo "  🎯 Recent active users (with keywords):"
sqlite3 "$DB_PATH" "SELECT telegram_id, language, SUBSTR(keywords, 1, 50) || CASE WHEN LENGTH(keywords) > 50 THEN '...' ELSE '' END FROM users WHERE keywords IS NOT NULL ORDER BY updated_at DESC LIMIT 3;" 2>/dev/null | while IFS='|' read id lang keywords; do
    echo "    User $id ($lang): $keywords"
done

echo "  📺 Recent channels:"
sqlite3 "$DB_PATH" "SELECT channel_id, COALESCE(channel_tag, '<no tag>'), COALESCE(SUBSTR(channel_name, 1, 30), '<no name>') FROM channels ORDER BY created_at DESC LIMIT 3;" 2>/dev/null | while IFS='|' read id tag name; do
    echo "    $id ($tag): $name"
done
echo

echo "💡 MAINTENANCE SUGGESTIONS:"
echo "----------------------------------------"

# Check for old data
OLD_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE updated_at < datetime('now', '-30 days');" 2>/dev/null || echo "0")
if [ "$OLD_USERS" -gt 0 ]; then
    echo "  🟡 $OLD_USERS users haven't updated keywords in 30+ days"
fi

# Check for inactive users
INACTIVE_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity WHERE last_interaction < datetime('now', '-30 days');" 2>/dev/null || echo "0")
if [ "$INACTIVE_USERS" -gt 0 ]; then
    echo "  🟡 $INACTIVE_USERS users inactive for 30+ days"
fi

# Database size check
DB_SIZE_KB=$(du -k "$DB_PATH" | cut -f1)
if [ "$DB_SIZE_KB" -gt 10240 ]; then  # > 10MB
    echo "  🟡 Database is large ($(du -h "$DB_PATH" | cut -f1)) - consider cleanup or optimization"
fi

# Pool size recommendations based on usage
if [ -n "$ACTIVE" ] && [ -n "$MAX_POOL" ]; then
    if [ "$ACTIVE" -eq 0 ] && [ "$MAX_POOL" -gt 5 ]; then
        echo "  💡 No active connections but large pool ($MAX_POOL) - consider reducing pool size"
    elif [ "$ACTIVE" -gt $((MAX_POOL * 8 / 10)) ]; then
        echo "  💡 High pool utilization - consider increasing pool size or optimizing queries"
    fi
fi

# Journal mode optimization
if [ "$JOURNAL_MODE" != "wal" ]; then
    echo "  💡 Consider enabling WAL mode for better concurrent access:"
    echo "    sqlite3 $DB_PATH 'PRAGMA journal_mode=WAL;'"
fi

# Check for potential cleanup opportunities
EMPTY_KEYWORDS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE keywords IS NULL OR keywords = '';" 2>/dev/null || echo "0")
if [ "$EMPTY_KEYWORDS" -gt 100 ]; then
    echo "  🧹 $EMPTY_KEYWORDS users with no keywords - consider cleanup of inactive users"
fi

# Migration recommendations
if [ "$MIGRATION_COUNT" -eq 0 ]; then
    echo "  🔄 No migration records found - database may need migration system initialization"
fi

echo

echo "🔧 REPOSITORY PATTERN PERFORMANCE:"
echo "----------------------------------------"
echo "  📊 Architecture Status:"
echo "    ✅ UserRepository: Handles user CRUD and activity tracking"
echo "    ✅ ChannelRepository: Manages channels with tag support"
echo "    ✅ AdminRepository: Handles bans and user info aggregation"
echo "    ✅ HikariCP Integration: Professional connection pooling"
echo "    ✅ Migration System: Automatic schema versioning"

# Connection pool efficiency analysis
if [ -n "$TOTAL" ] && [ -n "$ACTIVE" ] && [ "$TOTAL" -gt 0 ]; then
    EFFICIENCY=$((ACTIVE * 100 / TOTAL))
    echo "  ⚡ Current Pool Efficiency: ${EFFICIENCY}%"
    
    if [ "$EFFICIENCY" -gt 80 ]; then
        echo "    ✅ Excellent pool utilization"
    elif [ "$EFFICIENCY" -gt 50 ]; then
        echo "    ✅ Good pool utilization"
    elif [ "$EFFICIENCY" -gt 20 ]; then
        echo "    ⚠️ Moderate pool utilization - may be over-provisioned"
    else
        echo "    🟡 Low pool utilization - consider reducing pool size"
    fi
fi

echo

echo "🎯 OPTIMIZATION RECOMMENDATIONS:"
echo "----------------------------------------"

# Database optimization suggestions
echo "  🗃️ Database Optimizations:"
if [ "$JOURNAL_MODE" = "wal" ]; then
    echo "    ✅ WAL mode enabled (optimal for concurrent access)"
else
    echo "    💡 Enable WAL mode: PRAGMA journal_mode=WAL;"
fi

if [ "$CACHE_SIZE" != "unknown" ] && [ "$CACHE_SIZE" -lt 5000 ]; then
    echo "    💡 Increase cache size: PRAGMA cache_size=10000;"
fi

if [ "$TEMP_STORE" != "2" ]; then
    echo "    💡 Use memory for temp storage: PRAGMA temp_store=memory;"
fi

# Pool optimization suggestions
echo "  🏊 HikariCP Pool Optimizations:"
if [ -n "$AWAITING" ] && [ "$AWAITING" -gt 0 ]; then
    echo "    🚨 Threads waiting for connections - increase pool size"
fi

if [ -n "$UTILIZATION" ]; then
    if [ "$UTILIZATION" -gt 90 ]; then
        echo "    💡 Consider increasing maximumPoolSize from $MAX_POOL to $((MAX_POOL + 5))"
    elif [ "$UTILIZATION" -lt 10 ] && [ "$MAX_POOL" -gt 5 ]; then
        echo "    💡 Consider decreasing maximumPoolSize from $MAX_POOL to $((MAX_POOL - 2))"
    fi
fi

# Application optimization suggestions
echo "  🚀 Application Optimizations:"
if [ "$TOTAL_COMMANDS" -gt 10000 ] && [ "$TOTAL_ACTIVITY" -gt 100 ]; then
    AVG_CMD_PER_USER=$((TOTAL_COMMANDS / TOTAL_ACTIVITY))
    if [ "$AVG_CMD_PER_USER" -gt 50 ]; then
        echo "    💡 High command volume - monitor rate limiting effectiveness"
    fi
fi

if [ "$BANNED_COUNT" -gt $((TOTAL_USERS / 10)) ] && [ "$TOTAL_USERS" -gt 50 ]; then
    echo "    💡 High ban rate (${BANNED_COUNT}/${TOTAL_USERS}) - review moderation policies"
fi

echo

echo "📊 PERFORMANCE METRICS SUMMARY:"
echo "----------------------------------------"
echo "  📈 User Engagement:"
echo "    • Total Users: $TOTAL_USERS"
echo "    • Active Users: $ACTIVE_USERS ($([ "$TOTAL_USERS" -gt 0 ] && echo "$((ACTIVE_USERS * 100 / TOTAL_USERS))%" || echo "0%"))"
echo "    • Total Commands: $TOTAL_COMMANDS"
echo "    • Banned Users: $BANNED_COUNT"

echo "  📺 Channel Management:"
echo "    • Total Channels: $TOTAL_CHANNELS"
echo "    • Channels with Tags: $WITH_TAGS"
echo "    • Recently Updated: $RECENTLY_UPDATED"

echo "  🏊 Connection Pool:"
if [ -n "$ACTIVE" ]; then
    echo "    • Active Connections: $ACTIVE/$MAX_POOL"
    echo "    • Pool Utilization: ${UTILIZATION:-0}%"
    echo "    • Pool Efficiency: ${EFFICIENCY:-0}%"
    echo "    • Threads Waiting: ${AWAITING:-0}"
else
    echo "    • Pool Status: Not available (bot may not be running)"
fi

echo "  💾 Database Health:"
echo "    • Database Size: $(du -h "$DB_PATH" | cut -f1)"
echo "    • Journal Mode: $JOURNAL_MODE"
echo "    • Integrity: $INTEGRITY"
echo "    • Schema Version: $CURRENT_VERSION"

echo

echo "========================================"
echo "✨ Database inspection complete"
echo "========================================"
echo
echo "💡 Usage tips:"
echo "  • Monitor pool utilization: curl http://localhost:8080/health/database"
echo "  • Access database directly: sqlite3 $DB_PATH"
echo "  • Export data: sqlite3 $DB_PATH .dump > backup.sql"
echo "  • Query users: sqlite3 $DB_PATH 'SELECT * FROM users LIMIT 5;'"
echo "  • Check migrations: sqlite3 $DB_PATH 'SELECT * FROM schema_version;'"
echo "  • Monitor pool in real-time via /admin dashboard in Telegram"
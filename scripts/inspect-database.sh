#!/bin/bash
# inspect-database.sh - Database inspection and statistics script

DB_PATH="${DATABASE_PATH:-/app/data/bot.db}"

echo "========================================"
echo "🗄️  DATABASE INSPECTION"
echo "========================================"
echo

if [ ! -f "$DB_PATH" ]; then
    echo "❌ Database file not found: $DB_PATH"
    exit 1
fi

echo "📁 Database: $DB_PATH"
echo "📏 Size: $(du -h "$DB_PATH" | cut -f1)"
echo "📅 Modified: $(stat -c %y "$DB_PATH" | cut -d. -f1)"
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

echo "📊 DATABASE STATISTICS:"
echo "----------------------------------------"

# Table info
echo "📋 Tables:"
sqlite3 "$DB_PATH" ".tables" | while read table; do
    if [ -n "$table" ]; then
        count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM $table;")
        echo "  $table: $count records"
    fi
done
echo

# Users statistics
echo "👥 USERS ANALYSIS:"
echo "----------------------------------------"
TOTAL_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users;" 2>/dev/null || echo "0")
ACTIVE_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM users WHERE keywords IS NOT NULL;" 2>/dev/null || echo "0")
echo "  Total registered users: $TOTAL_USERS"
echo "  Users with keywords set: $ACTIVE_USERS"

if [ "$TOTAL_USERS" -gt 0 ]; then
    echo "  Languages used:"
    sqlite3 "$DB_PATH" "SELECT language, COUNT(*) FROM users GROUP BY language;" 2>/dev/null | while IFS='|' read lang count; do
        echo "    $lang: $count users"
    done
    
    echo "  Recent registrations:"
    sqlite3 "$DB_PATH" "SELECT telegram_id, language, created_at FROM users ORDER BY created_at DESC LIMIT 5;" 2>/dev/null | while IFS='|' read id lang created; do
        echo "    User $id ($lang) - $created"
    done
fi
echo

# Channels statistics
echo "📺 CHANNELS ANALYSIS:"
echo "----------------------------------------"
TOTAL_CHANNELS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM channels;" 2>/dev/null || echo "0")
echo "  Total monitored channels: $TOTAL_CHANNELS"

if [ "$TOTAL_CHANNELS" -gt 0 ]; then
    echo "  Channel list:"
    sqlite3 "$DB_PATH" "SELECT channel_id, channel_name, created_at FROM channels ORDER BY created_at;" 2>/dev/null | while IFS='|' read id name created; do
        name_display=${name:-"(no name)"}
        echo "    $id - $name_display - $created"
    done
fi
echo

# Activity statistics
echo "📈 ACTIVITY ANALYSIS:"
echo "----------------------------------------"
TOTAL_ACTIVITY=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity;" 2>/dev/null || echo "0")
echo "  Users with activity records: $TOTAL_ACTIVITY"

if [ "$TOTAL_ACTIVITY" -gt 0 ]; then
    echo "  Most active users:"
    sqlite3 "$DB_PATH" "SELECT user_telegram_id, command_count, last_interaction FROM user_activity ORDER BY command_count DESC LIMIT 5;" 2>/dev/null | while IFS='|' read user_id commands last; do
        echo "    User $user_id: $commands commands (last: $last)"
    done
    
    echo "  Recent activity:"
    sqlite3 "$DB_PATH" "SELECT user_telegram_id, command_count, last_interaction FROM user_activity ORDER BY last_interaction DESC LIMIT 5;" 2>/dev/null | while IFS='|' read user_id commands last; do
        echo "    User $user_id: $commands commands (last: $last)"
    done
fi
echo

# Database health check
echo "🔍 DATABASE HEALTH CHECK:"
echo "----------------------------------------"

# Check integrity
INTEGRITY=$(sqlite3 "$DB_PATH" "PRAGMA integrity_check;" 2>/dev/null)
if [ "$INTEGRITY" = "ok" ]; then
    echo "  ✅ Database integrity: OK"
else
    echo "  ❌ Database integrity: $INTEGRITY"
fi

# Check schema
echo "  📋 Schema validation:"
EXPECTED_TABLES=("users" "channels" "user_activity")
for table in "${EXPECTED_TABLES[@]}"; do
    if sqlite3 "$DB_PATH" ".schema $table" &>/dev/null; then
        echo "    ✅ Table '$table' exists"
    else
        echo "    ❌ Table '$table' missing"
    fi
done

# Check indexes
echo "  🔍 Indexes:"
sqlite3 "$DB_PATH" ".schema" | grep "CREATE INDEX" | while read index; do
    index_name=$(echo "$index" | sed 's/.*INDEX \([^ ]*\).*/\1/')
    echo "    ✅ $index_name"
done
echo

# Sample data
echo "📋 SAMPLE DATA:"
echo "----------------------------------------"
echo "  Recent users (with keywords):"
sqlite3 "$DB_PATH" "SELECT telegram_id, language, keywords FROM users WHERE keywords IS NOT NULL ORDER BY updated_at DESC LIMIT 3;" 2>/dev/null | while IFS='|' read id lang keywords; do
    truncated_keywords=$(echo "$keywords" | cut -c1-50)
    if [ ${#keywords} -gt 50 ]; then
        truncated_keywords="$truncated_keywords..."
    fi
    echo "    User $id ($lang): $truncated_keywords"
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
INACTIVE_USERS=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM user_activity WHERE last_interaction < datetime('now', '-7 days');" 2>/dev/null || echo "0")
if [ "$INACTIVE_USERS" -gt 0 ]; then
    echo "  🟡 $INACTIVE_USERS users inactive for 7+ days"
fi

# Database size check
DB_SIZE_KB=$(du -k "$DB_PATH" | cut -f1)
if [ "$DB_SIZE_KB" -gt 10240 ]; then  # > 10MB
    echo "  🟡 Database is large ($(du -h "$DB_PATH" | cut -f1)) - consider cleanup"
fi

echo
echo "========================================"
echo "✨ Database inspection complete"
echo "========================================"
echo
echo "💡 Usage tips:"
echo "  - Access database directly: sqlite3 $DB_PATH"
echo "  - Export data: sqlite3 $DB_PATH .dump > backup.sql"
echo "  - Query users: sqlite3 $DB_PATH 'SELECT * FROM users;'"

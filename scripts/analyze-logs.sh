#!/bin/bash
# analyze-logs.sh - Enhanced log analysis with HikariCP and multi-admin support
# Updated for com.jobbot package structure and repository pattern

# Use environment variables or defaults
LOG_DIR="${LOG_PATH:-/app/logs}"
LINES="${1:-100}"

echo "========================================"
echo "📊 ENHANCED TELEGRAM BOT LOG ANALYSIS"
echo "========================================"
echo

if [ ! -d "$LOG_DIR" ]; then
    echo "❌ Log directory not found: $LOG_DIR"
    echo ""
    echo "💡 Troubleshooting:"
    echo "  • Check LOG_PATH environment variable: ${LOG_PATH:-not set}"
    echo "  • Verify Docker volume mounting"
    echo "  • Check if bot has generated logs"
    exit 1
fi

echo "📁 Log Directory: $LOG_DIR"
echo "📏 Analyzing last $LINES lines"
echo "🕐 Analysis Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo

# Find log files
LOG_FILES=$(find "$LOG_DIR" -name "*.log" -type f | sort)
if [ -z "$LOG_FILES" ]; then
    echo "❌ No log files found in $LOG_DIR"
    exit 1
fi

echo "📄 Available log files:"
for file in $LOG_FILES; do
    if [ -f "$file" ]; then
        size=$(du -h "$file" 2>/dev/null | cut -f1)
        modified=$(stat -c %y "$file" 2>/dev/null | cut -d. -f1)
        lines=$(wc -l < "$file" 2>/dev/null || echo "0")
        echo "  $(basename "$file") - $size ($lines lines) - $modified"
    fi
done
echo

# Analyze main bot log
MAIN_LOG="$LOG_DIR/bot.log"
if [ -f "$MAIN_LOG" ]; then
    echo "🔍 COMPREHENSIVE ERROR ANALYSIS (last $LINES lines):"
    echo "----------------------------------------------------"
    
    # Basic error counting
    ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "ERROR")
    WARNS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "WARN")
    INFOS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "INFO")
    DEBUG=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "DEBUG")
    
    echo "📊 Log Level Distribution:"
    echo "  🔴 ERRORS: $ERRORS"
    echo "  🟡 WARNINGS: $WARNS"
    echo "  🔵 INFO: $INFOS"
    echo "  🔍 DEBUG: $DEBUG"
    echo
    
    # Enhanced error analysis for refactored architecture
    echo "🏗️ ARCHITECTURE-SPECIFIC ERROR ANALYSIS:"
    echo "--------------------------------------------"
    
    # HikariCP connection pool errors
    HIKARI_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "hikari\|connection.*pool\|connection.*timeout\|connection.*leak")
    POOL_EXHAUSTED=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "pool.*exhausted\|unable.*to.*obtain.*connection")
    CONNECTION_TIMEOUT=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "connection.*timeout\|timeout.*connection")
    
    echo "🏊 HikariCP Connection Pool Issues:"
    echo "  📊 Pool-related errors: $HIKARI_ERRORS"
    echo "  🚨 Pool exhaustion events: $POOL_EXHAUSTED"
    echo "  ⏱️ Connection timeouts: $CONNECTION_TIMEOUT"
    
    if [ "$HIKARI_ERRORS" -gt 0 ]; then
        echo "  🔍 Recent pool errors:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "hikari\|connection.*pool\|connection.*timeout\|connection.*leak" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # Repository pattern errors
    REPO_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "repository\|userrepository\|channelrepository\|adminrepository")
    DATABASE_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "database.*error\|sql.*exception\|sqlite.*error")
    
    echo "🗃️ Repository Pattern & Database Issues:"
    echo "  📊 Repository errors: $REPO_ERRORS"
    echo "  💾 Database errors: $DATABASE_ERRORS"
    
    if [ "$REPO_ERRORS" -gt 0 ]; then
        echo "  🔍 Recent repository errors:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "repository\|userrepository\|channelrepository\|adminrepository" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # Multi-admin system analysis
    ADMIN_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "admin.*notification.*failed\|admin.*error\|unauthorized.*admin")
    ADMIN_NOTIFICATIONS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "admin.*notification.*sent\|rate.*limit.*alert\|shutdown.*reminder")
    MULTI_ADMIN_EVENTS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "multiple.*admin\|admin.*command.*from\|authorized.*admin")
    
    echo "👥 Multi-Admin System Analysis:"
    echo "  📊 Admin notification errors: $ADMIN_ERRORS"
    echo "  📤 Admin notifications sent: $ADMIN_NOTIFICATIONS"
    echo "  👑 Multi-admin events: $MULTI_ADMIN_EVENTS"
    
    if [ "$ADMIN_ERRORS" -gt 0 ]; then
        echo "  🔍 Recent admin notification errors:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "admin.*notification.*failed\|admin.*error" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # Database migration analysis
    MIGRATION_EVENTS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "migration\|schema.*version\|database.*migration")
    MIGRATION_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "migration.*failed\|schema.*error\|migration.*error")
    
    echo "🔄 Database Migration Analysis:"
    echo "  📊 Migration events: $MIGRATION_EVENTS"
    echo "  🚨 Migration errors: $MIGRATION_ERRORS"
    
    if [ "$MIGRATION_EVENTS" -gt 0 ]; then
        echo "  🔍 Recent migration events:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "migration\|schema.*version" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # TDLib and authentication analysis
    TDLIB_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "tdlib.*error\|authentication.*failed\|tdlib.*exception")
    AUTH_EVENTS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "authentication.*code\|authentication.*password\|auth.*successful")
    TDLIB_CONNECTIONS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "tdlib.*connected\|tdlib.*ready\|tdlib.*disconnected")
    
    echo "🔐 TDLib & Authentication Analysis:"
    echo "  📊 TDLib errors: $TDLIB_ERRORS"
    echo "  🔑 Authentication events: $AUTH_EVENTS"
    echo "  🔗 Connection events: $TDLIB_CONNECTIONS"
    
    if [ "$TDLIB_ERRORS" -gt 0 ]; then
        echo "  🔍 Recent TDLib errors:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "tdlib.*error\|authentication.*failed" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # Rate limiting enhanced analysis
    RATE_LIMIT_HITS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "rate.*limit.*exceeded\|rate.*limit.*hit")
    RATE_LIMIT_RESTORED=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "rate.*limit.*restored\|rate.*limit.*cleared")
    RATE_LIMIT_ADMIN_ALERTS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "rate.*limit.*alert.*sent\|admin.*notification.*rate")
    
    echo "🚦 Enhanced Rate Limiting Analysis:"
    echo "  📊 Rate limit violations: $RATE_LIMIT_HITS"
    echo "  ✅ Rate limits restored: $RATE_LIMIT_RESTORED"
    echo "  📤 Admin alerts sent: $RATE_LIMIT_ADMIN_ALERTS"
    
    if [ "$RATE_LIMIT_HITS" -gt 0 ]; then
        echo "  🔍 Recent rate limit events:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "rate.*limit" | tail -5 | while read line; do
            # Extract user ID if present
            USER_ID=$(echo "$line" | grep -o "user [0-9]*" | head -1)
            TIME=$(echo "$line" | cut -d' ' -f1-2)
            echo "    $TIME - $USER_ID"
        done
    fi
    echo
    
    # Notification processor analysis
    NOTIFICATION_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "notification.*failed\|notification.*error\|notification.*timeout")
    NOTIFICATIONS_SENT=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "notification.*sent\|notification.*delivered")
    QUEUE_ISSUES=$(tail -n "$LINES" "$MAIN_LOG" | grep -i -c "queue.*full\|notification.*dropped\|queue.*overflow")
    
    echo "📬 Notification Processor Analysis:"
    echo "  📊 Notification errors: $NOTIFICATION_ERRORS"
    echo "  ✅ Notifications sent: $NOTIFICATIONS_SENT"
    echo "  🚨 Queue issues: $QUEUE_ISSUES"
    
    if [ "$NOTIFICATION_ERRORS" -gt 0 ]; then
        echo "  🔍 Recent notification errors:"
        tail -n "$LINES" "$MAIN_LOG" | grep -i "notification.*failed\|notification.*error" | tail -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    # Package structure analysis (com.jobbot)
    PACKAGE_ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "com\.jobbot")
    OLD_PACKAGE_REFS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "\.bot\." | grep -v "com\.jobbot")
    
    echo "📦 Package Structure Analysis:"
    echo "  📊 New package (com.jobbot) references: $PACKAGE_ERRORS"
    echo "  ⚠️ Old package structure references: $OLD_PACKAGE_REFS"
    
    if [ "$OLD_PACKAGE_REFS" -gt 0 ]; then
        echo "  🔍 Old package references found (may indicate incomplete refactoring):"
        tail -n "$LINES" "$MAIN_LOG" | grep "\.bot\." | grep -v "com\.jobbot" | head -3 | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
    fi
    echo
    
    if [ "$ERRORS" -gt 0 ]; then
        echo "🚨 CRITICAL ERROR MESSAGES (last 5):"
        echo "--------------------------------------"
        tail -n "$LINES" "$MAIN_LOG" | grep "ERROR" | tail -5 | while read line; do
            # Clean up the line for better readability
            clean_line=$(echo "$line" | sed 's/[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\} [0-9]\{2\}:[0-9]\{2\}:[0-9]\{2\}/[TIMESTAMP]/')
            echo "  $(echo "$clean_line" | cut -c1-120)..."
        done
        echo
    fi
    
    if [ "$WARNS" -gt 0 ]; then
        echo "⚠️ WARNING MESSAGES (last 5):"
        echo "------------------------------"
        tail -n "$LINES" "$MAIN_LOG" | grep "WARN" | tail -5 | while read line; do
            clean_line=$(echo "$line" | sed 's/[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\} [0-9]\{2\}:[0-9]\{2\}:[0-9]\{2\}/[TIMESTAMP]/')
            echo "  $(echo "$clean_line" | cut -c1-120)..."
        done
        echo
    fi
    
    echo "📈 ERROR PATTERN ANALYSIS:"
    echo "--------------------------"
    echo "🔍 Top error patterns:"
    tail -n "$LINES" "$MAIN_LOG" | grep "ERROR" | \
        sed 's/.*ERROR [^ ]* - //' | \
        sed 's/[0-9]\+/NUMBER/g' | \
        sed 's/User [0-9]\+/User NUMBER/g' | \
        sort | uniq -c | sort -nr | head -5 | while read count error; do
        echo "  $count× $error"
    done
    echo
    
    echo "🕐 RECENT ACTIVITY (last 10 entries):"
    echo "--------------------------------------"
    tail -10 "$MAIN_LOG" | while read line; do
        # Truncate very long lines for readability
        echo "  $(echo "$line" | cut -c1-150)$([ ${#line} -gt 150 ] && echo "...")"
    done
    echo

else
    echo "❌ Main log file not found: $MAIN_LOG"
    echo ""
    echo "💡 Check LOG_PATH configuration: ${LOG_PATH:-not set}"
fi

# Check error log if it exists
ERROR_LOG="$LOG_DIR/errors.log"
if [ -f "$ERROR_LOG" ]; then
    echo "💥 DEDICATED ERROR LOG ANALYSIS:"
    echo "---------------------------------"
    
    ERROR_COUNT=$(wc -l < "$ERROR_LOG" 2>/dev/null || echo "0")
    echo "  📊 Total errors logged: $ERROR_COUNT"
    
    if [ "$ERROR_COUNT" -gt 0 ]; then
        echo "  🔍 Recent critical errors:"
        tail -5 "$ERROR_LOG" | while read line; do
            echo "    $(echo "$line" | cut -c1-120)..."
        done
        
        # Analyze error patterns in dedicated log
        echo "  📊 Error categories in dedicated log:"
        tail -50 "$ERROR_LOG" | grep -o 'Exception\|Error\|Failed\|Timeout\|Connection' | sort | uniq -c | sort -nr | head -5 | while read count category; do
            echo "    $count× $category"
        done
    fi
    echo
fi

# Performance analysis enhanced for new architecture
echo "⚡ ENHANCED PERFORMANCE INDICATORS:"
echo "-----------------------------------"
if [ -f "$MAIN_LOG" ]; then
    # Startup and initialization
    STARTUP_TIME=$(grep "Bot started successfully\|Application.*started" "$MAIN_LOG" | tail -1 | cut -d' ' -f1-2)
    if [ -n "$STARTUP_TIME" ]; then
        echo "  🚀 Last startup: $STARTUP_TIME"
    fi
    
    # HikariCP initialization
    HIKARI_INIT=$(grep -c "HikariCP.*initialized\|Connection pool.*created\|Database initialized.*HikariCP" "$MAIN_LOG")
    echo "  🏊 HikariCP initializations: $HIKARI_INIT"
    
    # TDLib connections
    TDLIB_READY=$(grep -c "TDLib.*ready\|authorization.*successful\|TDLib.*connected" "$MAIN_LOG")
    echo "  📱 TDLib connections: $TDLIB_READY"
    
    # Message processing
    PROCESSED_MESSAGES=$(grep -c "Processed message from\|Message processing complete" "$MAIN_LOG")
    echo "  📨 Messages processed: $PROCESSED_MESSAGES"
    
    # Notifications
    NOTIFICATIONS_SENT_TOTAL=$(grep -c "Notification sent to user\|notification.*delivered" "$MAIN_LOG")
    echo "  📤 Notifications sent: $NOTIFICATIONS_SENT_TOTAL"
    
    # Database operations
    DB_OPERATIONS=$(grep -c "Database.*operation\|Repository.*query\|SQL.*executed" "$MAIN_LOG")
    echo "  💾 Database operations: $DB_OPERATIONS"
    
    # Admin operations
    ADMIN_COMMANDS=$(grep -c "Admin command.*received\|AdminCommandRouter" "$MAIN_LOG")
    echo "  👑 Admin commands processed: $ADMIN_COMMANDS"
    
    # Multi-admin events
    MULTI_ADMIN_EVENTS_TOTAL=$(grep -c "admin.*notification.*sent.*to.*all\|multiple.*admin" "$MAIN_LOG")
    echo "  👥 Multi-admin events: $MULTI_ADMIN_EVENTS_TOTAL"
    
    # Repository pattern usage
    REPO_OPERATIONS=$(grep -c "UserRepository\|ChannelRepository\|AdminRepository" "$MAIN_LOG")
    echo "  🗃️ Repository operations: $REPO_OPERATIONS"
    
    # Performance warnings
    PERFORMANCE_WARNINGS=$(grep -i -c "slow.*query\|timeout\|performance.*warning\|pool.*exhausted" "$MAIN_LOG")
    if [ "$PERFORMANCE_WARNINGS" -gt 0 ]; then
        echo "  ⚠️ Performance warnings: $PERFORMANCE_WARNINGS"
    fi
fi
echo

echo "💡 ENHANCED RECOMMENDATIONS:"
echo "-----------------------------"

# HikariCP recommendations
if [ "$HIKARI_ERRORS" -gt 5 ]; then
    echo "  🏊 HikariCP Issues Detected:"
    echo "    • $HIKARI_ERRORS pool-related errors found"
    echo "    • Check connection pool configuration"
    echo "    • Monitor pool utilization via /admin dashboard"
    echo "    • Consider increasing pool size if exhaustion detected"
fi

if [ "$POOL_EXHAUSTED" -gt 0 ]; then
    echo "  🚨 Pool Exhaustion Warning:"
    echo "    • $POOL_EXHAUSTED pool exhaustion events detected"
    echo "    • Increase maximumPoolSize in HikariCP configuration"
    echo "    • Check for connection leaks in application code"
fi

# Multi-admin recommendations
if [ "$ADMIN_ERRORS" -gt 0 ]; then
    echo "  👥 Multi-Admin Issues:"
    echo "    • $ADMIN_ERRORS admin notification failures detected"
    echo "    • Verify all admin IDs in AUTHORIZED_ADMIN_IDS are valid"
    echo "    • Check network connectivity to Telegram API"
fi

# Repository pattern recommendations
if [ "$REPO_ERRORS" -gt 3 ]; then
    echo "  🗃️ Repository Pattern Issues:"
    echo "    • $REPO_ERRORS repository-related errors found"
    echo "    • Check database connection stability"
    echo "    • Review SQL query performance"
    echo "    • Consider connection timeout adjustments"
fi

# Migration recommendations
if [ "$MIGRATION_ERRORS" -gt 0 ]; then
    echo "  🔄 Database Migration Issues:"
    echo "    • $MIGRATION_ERRORS migration errors detected"
    echo "    • Check database permissions and schema state"
    echo "    • Review migration logs for specific failure details"
fi

# General recommendations
if [ "$ERRORS" -gt 10 ]; then
    echo "  🔴 High Error Count:"
    echo "    • $ERRORS errors detected - investigate error patterns above"
    echo "    • Check system resources and connectivity"
    echo "    • Review application configuration"
fi

if [ "$WARNS" -gt 20 ]; then
    echo "  🟡 High Warning Count:"
    echo "    • $WARNS warnings detected - check configuration and network"
    echo "    • Many warnings may indicate impending issues"
fi

if [ -f "$MAIN_LOG" ] && [ $(stat -c%s "$MAIN_LOG") -gt 52428800 ]; then  # > 50MB
    echo "  📁 Large Log File:"
    echo "    • Log file is large (>50MB) - consider log rotation"
    echo "    • Check logback.xml configuration"
fi

# Architecture-specific recommendations
if [ "$OLD_PACKAGE_REFS" -gt 0 ]; then
    echo "  📦 Package Structure:"
    echo "    • $OLD_PACKAGE_REFS old package references found"
    echo "    • May indicate incomplete refactoring"
    echo "    • All references should use com.jobbot package structure"
fi

echo

echo "========================================"
echo "✨ Enhanced log analysis complete"
echo "========================================"
echo
echo "💡 Usage tips:"
echo "  • Run with custom line count: $0 500"
echo "  • Monitor in real-time: tail -f $MAIN_LOG"
echo "  • Filter HikariCP logs: grep -i hikari $MAIN_LOG"
echo "  • Check admin events: grep -i admin.*notification $MAIN_LOG"
echo "  • Monitor migrations: grep -i migration $MAIN_LOG"
echo "  • Repository errors: grep -i repository $MAIN_LOG"
echo "  • Real-time monitoring: Use /admin dashboard in Telegram"
echo
echo "🔗 Related tools:"
echo "  • debug-system.sh - System diagnostics with pool monitoring"
echo "  • inspect-database.sh - Database analysis with HikariCP stats"
echo "  • Health endpoints: http://localhost:8080/health/database"
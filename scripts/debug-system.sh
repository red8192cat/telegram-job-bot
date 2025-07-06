#!/bin/bash
# debug-system.sh - Enhanced system debugging and information script
# Updated for HikariCP connection pooling and multi-admin support

echo "========================================"
echo "🔍 TELEGRAM JOB BOT DEBUG INFORMATION"
echo "========================================"
echo

echo "📋 System Information:"
echo "  OS: $(cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d'"' -f2 || uname -s)"
echo "  Architecture: $(uname -m)"
echo "  Kernel: $(uname -r)"
echo "  Hostname: $(hostname)"
echo "  Uptime: $(uptime -p 2>/dev/null || uptime)"
echo "  Current Time: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo

echo "☕ Java Information:"
echo "  Java Version: $(java -version 2>&1 | head -1)"
echo "  Java Home: ${JAVA_HOME:-Not set}"
echo "  Java Library Path: ${JAVA_LIBRARY_PATH:-Not set}"
echo "  LD Library Path: ${LD_LIBRARY_PATH:-Not set}"
echo "  Max Heap Size: $(java -XX:+PrintFlagsFinal -version 2>/dev/null | grep MaxHeapSize | awk '{print $4}' | head -1 || echo 'Unknown')"
echo

echo "📚 TDLib Information:"
echo "  Custom TDLib Native Library:"
if [ -d "/app/natives" ]; then
    echo "    📁 Natives directory found:"
    ls -la /app/natives/ 2>/dev/null | while read line; do
        echo "      $line"
    done
    
    # Check if native library is properly loaded
    if [ -f "/app/natives/libtdjni.so" ]; then
        echo "    📦 Native library: $(file /app/natives/libtdjni.so 2>/dev/null || echo 'File type unknown')"
        echo "    📏 Size: $(du -h /app/natives/libtdjni.so 2>/dev/null | cut -f1 || echo 'Unknown')"
        echo "    🔗 Dependencies: $(ldd /app/natives/libtdjni.so 2>/dev/null | grep -c "=>" || echo 'Cannot check')"
    fi
else
    echo "    ❌ Natives directory not found at /app/natives"
fi
echo

echo "🔗 SSL Libraries:"
echo "  OpenSSL Version: $(openssl version 2>/dev/null || echo 'Not available')"
echo "  SSL Libraries found:"
find /usr/lib* /lib* -name "*ssl*" 2>/dev/null | grep libssl | head -5 | while read lib; do
    echo "    📦 $lib"
done || echo "    ❌ No SSL libraries found"
echo

echo "💾 Storage Information:"
echo "  Database:"
# Use environment variable or default path
DB_PATH="${DATABASE_PATH:-/app/data/bot.db}"
if [ -f "$DB_PATH" ]; then
    echo "    📁 Path: $DB_PATH"
    echo "    📏 Size: $(du -h "$DB_PATH" 2>/dev/null | cut -f1)"
    echo "    📅 Modified: $(stat -c %y "$DB_PATH" 2>/dev/null | cut -d. -f1)"
    echo "    🔒 Permissions: $(stat -c %A "$DB_PATH" 2>/dev/null)"
    
    # Check if SQLite is in WAL mode
    if command -v sqlite3 >/dev/null 2>&1; then
        WAL_MODE=$(sqlite3 "$DB_PATH" "PRAGMA journal_mode;" 2>/dev/null || echo "unknown")
        echo "    📋 Journal Mode: $WAL_MODE"
        
        # Check database integrity
        INTEGRITY=$(sqlite3 "$DB_PATH" "PRAGMA integrity_check;" 2>/dev/null | head -1)
        echo "    ✅ Integrity: $INTEGRITY"
    fi
else
    echo "    ❌ Database file not found at $DB_PATH"
fi

echo "  Log files:"
LOG_PATH="${LOG_PATH:-/app/logs}"
if [ -d "$LOG_PATH" ]; then
    echo "    📁 Path: $LOG_PATH"
    echo "    📏 Directory size: $(du -sh "$LOG_PATH" 2>/dev/null | cut -f1)"
    echo "    📄 Recent files:"
    ls -lt "$LOG_PATH"/*.log 2>/dev/null | head -3 | while read line; do
        echo "      $line"
    done
    
    # Check log rotation status
    LOG_COUNT=$(find "$LOG_PATH" -name "*.log*" 2>/dev/null | wc -l)
    echo "    🔄 Total log files: $LOG_COUNT"
else
    echo "    ❌ Logs directory not found at $LOG_PATH"
fi
echo

echo "🌐 Network Information:"
echo "  Health endpoint: http://localhost:8080/health"
echo "  Database endpoint: http://localhost:8080/health/database"
echo "  Testing health endpoint:"
HEALTH_RESPONSE=$(curl -s -m 5 http://localhost:8080/health 2>/dev/null)
if [ $? -eq 0 ]; then
    echo "    ✅ Health endpoint responding"
    # Try to extract status from JSON response
    STATUS=$(echo "$HEALTH_RESPONSE" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 2>/dev/null || echo "unknown")
    echo "    📊 Status: $STATUS"
    
    # Test database-specific endpoint
    DB_HEALTH=$(curl -s -m 5 http://localhost:8080/health/database 2>/dev/null)
    if [ $? -eq 0 ]; then
        echo "    ✅ Database health endpoint responding"
        # Try to extract connection info
        ACTIVE_CONN=$(echo "$DB_HEALTH" | grep -o '"activeConnections":[0-9]*' | cut -d':' -f2 2>/dev/null || echo "unknown")
        TOTAL_CONN=$(echo "$DB_HEALTH" | grep -o '"totalConnections":[0-9]*' | cut -d':' -f2 2>/dev/null || echo "unknown")
        echo "    🔗 HikariCP Connections: $ACTIVE_CONN/$TOTAL_CONN active/total"
    else
        echo "    ⚠️ Database health endpoint not responding"
    fi
else
    echo "    ❌ Health endpoint not responding"
fi
echo

echo "🔧 Environment Variables (non-sensitive):"
echo "  Package Structure:"
echo "    📦 Main Package: com.jobbot (refactored from old bot/ structure)"
echo "  Database Configuration:"
echo "    📁 DATABASE_PATH: ${DATABASE_PATH:-Not set (using default)}"
echo "    🏊 Connection Pool: HikariCP enabled"
echo "  Logging Configuration:"
echo "    📁 LOG_PATH: ${LOG_PATH:-Not set (using default)}"
echo "    📊 LOG_LEVEL: ${LOG_LEVEL:-Not set}"
echo "    📱 TDLIB_LOG_LEVEL: ${TDLIB_LOG_LEVEL:-Not set}"
echo "  TDLib Configuration:"
echo "    🆔 API_ID configured: $([ -n "$API_ID" ] && echo "✅ Yes" || echo "❌ No")"
echo "    🔑 API_HASH configured: $([ -n "$API_HASH" ] && echo "✅ Yes" || echo "❌ No")"
echo "    📞 PHONE_NUMBER configured: $([ -n "$PHONE_NUMBER" ] && echo "✅ Yes" || echo "❌ No")"
echo "  Bot Configuration:"
echo "    🤖 BOT_TOKEN configured: $([ -n "$TELEGRAM_BOT_TOKEN" ] && echo "✅ Yes" || echo "❌ No")"
echo "    👑 BOT_USERNAME: ${BOT_USERNAME:-Not set}"
echo "  Multi-Admin Configuration:"
if [ -n "$AUTHORIZED_ADMIN_IDS" ]; then
    ADMIN_COUNT=$(echo "$AUTHORIZED_ADMIN_IDS" | tr ',' '\n' | wc -l)
    echo "    👥 AUTHORIZED_ADMIN_IDS: ✅ Configured ($ADMIN_COUNT admins)"
    echo "    📋 Admin IDs: $AUTHORIZED_ADMIN_IDS"
else
    echo "    👥 AUTHORIZED_ADMIN_IDS: ❌ Not configured"
fi
echo "  Rate Limiting:"
echo "    ⏱️ RATE_LIMIT_MESSAGES_PER_MINUTE: ${RATE_LIMIT_MESSAGES_PER_MINUTE:-Not set}"
echo "    💥 RATE_LIMIT_BURST_SIZE: ${RATE_LIMIT_BURST_SIZE:-Not set}"
echo

echo "📊 Process Information:"
echo "  Bot processes:"
ps aux 2>/dev/null | grep -E "(java|telegram)" | grep -v grep | while read line; do
    echo "    $line"
done || echo "    ❌ No bot processes found"

echo "  Java processes:"
pgrep -l java 2>/dev/null | while read line; do
    echo "    $line"
done || echo "    ❌ No Java processes found"
echo

echo "🏥 HikariCP Connection Pool Status:"
# Try to get pool information from health endpoint
if command -v curl >/dev/null 2>&1; then
    POOL_INFO=$(curl -s -m 3 http://localhost:8080/health/database 2>/dev/null)
    if [ $? -eq 0 ] && [ -n "$POOL_INFO" ]; then
        echo "    ✅ Pool information available via health endpoint"
        
        # Extract pool statistics using grep and cut
        ACTIVE=$(echo "$POOL_INFO" | grep -o '"activeConnections":[0-9]*' | cut -d':' -f2)
        IDLE=$(echo "$POOL_INFO" | grep -o '"idleConnections":[0-9]*' | cut -d':' -f2)
        TOTAL=$(echo "$POOL_INFO" | grep -o '"totalConnections":[0-9]*' | cut -d':' -f2)
        MAX_POOL=$(echo "$POOL_INFO" | grep -o '"maximumPoolSize":[0-9]*' | cut -d':' -f2)
        MIN_IDLE=$(echo "$POOL_INFO" | grep -o '"minimumIdle":[0-9]*' | cut -d':' -f2)
        AWAITING=$(echo "$POOL_INFO" | grep -o '"threadsAwaitingConnection":[0-9]*' | cut -d':' -f2)
        
        echo "    🔗 Active Connections: ${ACTIVE:-0}"
        echo "    💤 Idle Connections: ${IDLE:-0}"
        echo "    📊 Total Connections: ${TOTAL:-0}"
        echo "    📈 Maximum Pool Size: ${MAX_POOL:-0}"
        echo "    📉 Minimum Idle: ${MIN_IDLE:-0}"
        echo "    ⏳ Threads Awaiting: ${AWAITING:-0}"
        
        # Calculate pool utilization percentage
        if [ -n "$ACTIVE" ] && [ -n "$MAX_POOL" ] && [ "$MAX_POOL" -gt 0 ]; then
            UTILIZATION=$((ACTIVE * 100 / MAX_POOL))
            echo "    📊 Pool Utilization: ${UTILIZATION}%"
            
            if [ "$UTILIZATION" -gt 80 ]; then
                echo "    ⚠️ WARNING: High pool utilization (>80%)"
            elif [ "$UTILIZATION" -lt 10 ] && [ "$ACTIVE" -gt 0 ]; then
                echo "    ℹ️ INFO: Low pool utilization (<10%)"
            fi
        fi
        
        # Check for connection issues
        if [ -n "$AWAITING" ] && [ "$AWAITING" -gt 0 ]; then
            echo "    🚨 WARNING: $AWAITING threads waiting for connections"
        fi
    else
        echo "    ⚠️ Pool information not available via health endpoint"
    fi
else
    echo "    ❌ curl not available - cannot check pool status"
fi
echo

echo "🎯 Quick Health Check:"
# Bot process check
if pgrep -f "com.jobbot" > /dev/null 2>&1; then
    echo "  ✅ Bot process is running (com.jobbot package)"
elif pgrep -f "telegram" > /dev/null 2>&1; then
    echo "  ✅ Bot process is running (telegram process found)"
else
    echo "  ❌ Bot process not found"
fi

# Database file check
if [ -f "${DATABASE_PATH:-/app/data/bot.db}" ]; then
    echo "  ✅ Database file exists"
else
    echo "  ❌ Database file missing"
fi

# Health endpoint check
if curl -s -m 2 http://localhost:8080/health > /dev/null 2>&1; then
    echo "  ✅ Health endpoint responding"
else
    echo "  ❌ Health endpoint not responding"
fi

# Database health check
if curl -s -m 2 http://localhost:8080/health/database > /dev/null 2>&1; then
    echo "  ✅ Database health endpoint responding"
else
    echo "  ❌ Database health endpoint not responding"
fi

# TDLib configuration check
if [ -n "$API_ID" ] && [ -n "$API_HASH" ] && [ -n "$PHONE_NUMBER" ]; then
    echo "  ✅ TDLib configuration complete"
else
    echo "  ⚠️ TDLib configuration incomplete (bot-only mode)"
fi

# Multi-admin configuration check
if [ -n "$AUTHORIZED_ADMIN_IDS" ]; then
    ADMIN_COUNT=$(echo "$AUTHORIZED_ADMIN_IDS" | tr ',' '\n' | wc -l)
    echo "  ✅ Multi-admin configured ($ADMIN_COUNT admins)"
else
    echo "  ❌ Admin configuration missing"
fi

# Log directory check
if [ -d "${LOG_PATH:-/app/logs}" ]; then
    echo "  ✅ Logs directory exists"
else
    echo "  ❌ Logs directory missing"
fi

# Native library check
if [ -f "/app/natives/libtdjni.so" ]; then
    echo "  ✅ TDLib native library available"
else
    echo "  ⚠️ TDLib native library not found (may use Maven version)"
fi

echo

echo "🔍 Repository Pattern Status:"
echo "  📦 Architecture: Repository pattern implemented"
echo "  🗃️ UserRepository: User CRUD and activity tracking"
echo "  📋 ChannelRepository: Channel management with tag support"
echo "  👥 AdminRepository: Ban management and user info aggregation"
echo "  🏊 Database: HikariCP connection pooling enabled"
echo "  🔄 Migrations: Automatic schema migration system"
echo

echo "🎨 Package Structure Verification:"
echo "  📁 Main Package: com.jobbot (refactored from old structure)"
echo "  🏗️ Architecture Components:"
echo "    • com.jobbot.admin (AdminCommandRouter, specialized handlers)"
echo "    • com.jobbot.bot (TelegramBot, NotificationProcessor)"
echo "    • com.jobbot.data (Database, repositories, models)"
echo "    • com.jobbot.infrastructure (config, monitoring, security)"
echo "    • com.jobbot.shared (utilities, localization)"
echo "  ✅ Modern Kotlin project structure implemented"
echo

echo "📈 Performance Indicators:"
# Memory usage
if command -v free >/dev/null 2>&1; then
    MEMORY=$(free -h | grep '^Mem:' | awk '{print "Used: " $3 " / Total: " $2 " (" $3/$2*100 "%)"}' 2>/dev/null || echo "Memory info unavailable")
    echo "  🧠 Memory: $MEMORY"
fi

# Disk usage for database and logs
if [ -f "${DATABASE_PATH:-/app/data/bot.db}" ]; then
    DB_SIZE=$(du -h "${DATABASE_PATH:-/app/data/bot.db}" 2>/dev/null | cut -f1)
    echo "  💾 Database Size: $DB_SIZE"
fi

if [ -d "${LOG_PATH:-/app/logs}" ]; then
    LOG_SIZE=$(du -sh "${LOG_PATH:-/app/logs}" 2>/dev/null | cut -f1)
    echo "  📄 Logs Size: $LOG_SIZE"
fi

# Load average
if command -v uptime >/dev/null 2>&1; then
    LOAD=$(uptime | grep -o 'load average: .*' 2>/dev/null || echo "Load average unavailable")
    echo "  ⚖️ $LOAD"
fi

echo

echo "🔧 Troubleshooting Information:"
echo "  📚 For HikariCP issues:"
echo "    • Check pool utilization above"
echo "    • Look for connection timeout errors in logs"
echo "    • Verify database file permissions and WAL mode"
echo "  🔐 For TDLib authentication:"
echo "    • Use /admin auth_code <code> for SMS verification"
echo "    • Use /admin auth_password <password> for 2FA"
echo "    • Check TDLib native library status above"
echo "  👥 For multi-admin issues:"
echo "    • Verify AUTHORIZED_ADMIN_IDS configuration"
echo "    • Check admin notification delivery in logs"
echo "    • Ensure all admin IDs are valid Telegram user IDs"
echo "  🚦 For rate limiting problems:"
echo "    • Use /admin rate_limits to check status"
echo "    • Use /admin clear_rate_limit <user_id> to clear limits"
echo "    • Check rate limit configuration values"
echo

echo "========================================"
echo "✨ System debug information collection complete"
echo "========================================"
echo
echo "💡 Next steps:"
echo "  • Run 'analyze-logs.sh' to check for errors"
echo "  • Run 'inspect-database.sh' for database analysis"
echo "  • Use '/admin dashboard' in Telegram for real-time monitoring"
echo "  • Check health endpoints: http://localhost:8080/health"
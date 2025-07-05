#!/bin/bash
# debug-system.sh - System debugging and information script

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
echo

echo "☕ Java Information:"
echo "  Java Version: $(java -version 2>&1 | head -1)"
echo "  Java Home: ${JAVA_HOME:-Not set}"
echo "  Java Library Path: ${JAVA_LIBRARY_PATH:-Not set}"
echo "  LD Library Path: ${LD_LIBRARY_PATH:-Not set}"
echo

echo "📚 TDLib Information:"
echo "  Custom TDLib Native Library:"
if [ -d "/app/natives" ]; then
    ls -la /app/natives/ 2>/dev/null || echo "    No natives directory found"
else
    echo "    Natives directory not found"
fi
echo

echo "🔗 SSL Libraries:"
echo "  OpenSSL Version: $(openssl version 2>/dev/null || echo 'Not available')"
echo "  SSL Libraries found:"
find /usr/lib* /lib* -name "*ssl*" 2>/dev/null | grep libssl | head -5 || echo "    No SSL libraries found"
echo

echo "💾 Storage Information:"
echo "  Database:"
if [ -f "/app/data/bot.db" ]; then
    echo "    Size: $(du -h /app/data/bot.db 2>/dev/null | cut -f1)"
    echo "    Modified: $(stat -c %y /app/data/bot.db 2>/dev/null | cut -d. -f1)"
else
    echo "    Database file not found"
fi

echo "  Log files:"
if [ -d "/app/logs" ]; then
    echo "    Directory size: $(du -sh /app/logs 2>/dev/null | cut -f1)"
    echo "    Recent files:"
    ls -lt /app/logs/*.log 2>/dev/null | head -3 | while read line; do
        echo "      $line"
    done
else
    echo "    Logs directory not found"
fi
echo

echo "🌐 Network Information:"
echo "  Health endpoint: http://localhost:8080/health"
echo "  Testing health endpoint:"
curl -s -m 5 http://localhost:8080/health 2>/dev/null | head -3 || echo "    Health endpoint not responding"
echo

echo "🔧 Environment Variables (non-sensitive):"
echo "  DATABASE_PATH: ${DATABASE_PATH:-Not set}"
echo "  LOG_PATH: ${LOG_PATH:-Not set}"
echo "  LOG_LEVEL: ${LOG_LEVEL:-Not set}"
echo "  API_ID configured: $([ -n "$API_ID" ] && echo "Yes" || echo "No")"
echo "  API_HASH configured: $([ -n "$API_HASH" ] && echo "Yes" || echo "No")"
echo "  PHONE_NUMBER configured: $([ -n "$PHONE_NUMBER" ] && echo "Yes" || echo "No")"
echo "  BOT_TOKEN configured: $([ -n "$TELEGRAM_BOT_TOKEN" ] && echo "Yes" || echo "No")"
echo

echo "📊 Process Information:"
echo "  Bot process:"
ps aux | grep -E "(java|telegram)" | grep -v grep || echo "    No bot process found"
echo

echo "🎯 Quick Health Check:"
if pgrep -f "telegram-job-bot" > /dev/null; then
    echo "  ✅ Bot process is running"
else
    echo "  ❌ Bot process not found"
fi

if [ -f "/app/data/bot.db" ]; then
    echo "  ✅ Database file exists"
else
    echo "  ❌ Database file missing"
fi

if curl -s -m 2 http://localhost:8080/health > /dev/null; then
    echo "  ✅ Health endpoint responding"
else
    echo "  ❌ Health endpoint not responding"
fi

echo
echo "========================================"
echo "✨ Debug information collection complete"
echo "========================================"

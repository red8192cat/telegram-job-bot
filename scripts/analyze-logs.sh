#!/bin/bash
# analyze-logs.sh - Log analysis and error detection script

LOG_DIR="${LOG_PATH:-/app/logs}"
LINES="${1:-100}"

echo "========================================"
echo "📊 TELEGRAM BOT LOG ANALYSIS"
echo "========================================"
echo

if [ ! -d "$LOG_DIR" ]; then
    echo "❌ Log directory not found: $LOG_DIR"
    exit 1
fi

echo "📁 Log Directory: $LOG_DIR"
echo "📏 Analyzing last $LINES lines"
echo

# Find log files
LOG_FILES=$(find "$LOG_DIR" -name "*.log" -type f | sort)
if [ -z "$LOG_FILES" ]; then
    echo "❌ No log files found in $LOG_DIR"
    exit 1
fi

echo "📄 Available log files:"
for file in $LOG_FILES; do
    size=$(du -h "$file" 2>/dev/null | cut -f1)
    modified=$(stat -c %y "$file" 2>/dev/null | cut -d. -f1)
    echo "  $(basename "$file") - $size - $modified"
done
echo

# Analyze main bot log
MAIN_LOG="$LOG_DIR/bot.log"
if [ -f "$MAIN_LOG" ]; then
    echo "🔍 ERROR ANALYSIS (last $LINES lines):"
    echo "----------------------------------------"
    
    ERRORS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "ERROR")
    WARNS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "WARN")
    INFOS=$(tail -n "$LINES" "$MAIN_LOG" | grep -c "INFO")
    
    echo "  🔴 ERRORS: $ERRORS"
    echo "  🟡 WARNINGS: $WARNS"
    echo "  🔵 INFO: $INFOS"
    echo
    
    if [ "$ERRORS" -gt 0 ]; then
        echo "🚨 Recent Error Messages:"
        echo "----------------------------------------"
        tail -n "$LINES" "$MAIN_LOG" | grep "ERROR" | tail -10 | while read line; do
            echo "  $line"
        done
        echo
    fi
    
    if [ "$WARNS" -gt 0 ]; then
        echo "⚠️  Recent Warning Messages:"
        echo "----------------------------------------"
        tail -n "$LINES" "$MAIN_LOG" | grep "WARN" | tail -5 | while read line; do
            echo "  $line"
        done
        echo
    fi
    
    echo "📈 ERROR PATTERNS:"
    echo "----------------------------------------"
    tail -n "$LINES" "$MAIN_LOG" | grep "ERROR" | \
        sed 's/.*ERROR [^ ]* - //' | \
        sort | uniq -c | sort -nr | head -5 | while read count error; do
        echo "  $count× $error"
    done
    echo
    
    echo "🕐 RECENT ACTIVITY (last 10 entries):"
    echo "----------------------------------------"
    tail -10 "$MAIN_LOG" | while read line; do
        echo "  $line"
    done
    echo
    
else
    echo "❌ Main log file not found: $MAIN_LOG"
fi

# Check error log if it exists
ERROR_LOG="$LOG_DIR/errors.log"
if [ -f "$ERROR_LOG" ]; then
    echo "💥 DEDICATED ERROR LOG ANALYSIS:"
    echo "----------------------------------------"
    
    ERROR_COUNT=$(wc -l < "$ERROR_LOG" 2>/dev/null || echo "0")
    echo "  Total errors logged: $ERROR_COUNT"
    
    if [ "$ERROR_COUNT" -gt 0 ]; then
        echo "  Recent errors:"
        tail -5 "$ERROR_LOG" | while read line; do
            echo "    $line"
        done
    fi
    echo
fi

# Performance analysis
echo "⚡ PERFORMANCE INDICATORS:"
echo "----------------------------------------"
if [ -f "$MAIN_LOG" ]; then
    STARTUP_TIME=$(grep "Bot started successfully" "$MAIN_LOG" | tail -1 | cut -d' ' -f1-2)
    if [ -n "$STARTUP_TIME" ]; then
        echo "  Last startup: $STARTUP_TIME"
    fi
    
    TDLIB_READY=$(grep -c "TDLib.*ready\|authorization.*successful" "$MAIN_LOG")
    echo "  TDLib connections: $TDLIB_READY"
    
    PROCESSED_MESSAGES=$(grep -c "Processed message from" "$MAIN_LOG")
    echo "  Messages processed: $PROCESSED_MESSAGES"
    
    NOTIFICATIONS_SENT=$(grep -c "Notification sent to user" "$MAIN_LOG")
    echo "  Notifications sent: $NOTIFICATIONS_SENT"
    
    RATE_LIMITS=$(grep -c "Rate limit" "$MAIN_LOG")
    if [ "$RATE_LIMITS" -gt 0 ]; then
        echo "  🟡 Rate limit hits: $RATE_LIMITS"
    fi
fi
echo

echo "💡 RECOMMENDATIONS:"
echo "----------------------------------------"
if [ "$ERRORS" -gt 10 ]; then
    echo "  🔴 High error count detected - investigate error patterns above"
fi
if [ "$WARNS" -gt 20 ]; then
    echo "  🟡 High warning count - check configuration and network"
fi
if [ -f "$MAIN_LOG" ] && [ $(stat -c%s "$MAIN_LOG") -gt 10485760 ]; then
    echo "  📁 Log file is large (>10MB) - consider log rotation"
fi

echo
echo "========================================"
echo "✨ Log analysis complete"
echo "========================================"
echo
echo "💡 Usage tips:"
echo "  - Run with custom line count: $0 500"
echo "  - Monitor in real-time: tail -f $MAIN_LOG"
echo "  - Filter errors only: grep ERROR $MAIN_LOG"

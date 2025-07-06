#!/bin/bash
# debug.sh - Enhanced main debugging script with architectural awareness
# Updated for HikariCP, multi-admin support, and repository pattern

echo "🔧 TELEGRAM JOB BOT DEBUGGING TOOLS"
echo "===================================="
echo "🏗️ Architecture: com.jobbot with HikariCP & Multi-Admin Support"
echo

if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "Enhanced debugging tools for production Telegram Job Bot:"
    echo
    echo "📋 AVAILABLE COMMANDS:"
    echo
    echo "  debug.sh system      - System diagnostics with HikariCP pool monitoring"
    echo "  debug.sh logs [N]    - Enhanced log analysis (default: last 100 lines)"
    echo "  debug.sh database    - Database inspection with connection pool stats"
    echo "  debug.sh all         - Run comprehensive diagnostic suite"
    echo "  debug.sh health      - Quick health check with pool status"
    echo "  debug.sh --help      - Show this help"
    echo
    echo "🔍 ENHANCED FEATURES:"
    echo
    echo "  HikariCP Monitoring:"
    echo "    • Real-time connection pool statistics"
    echo "    • Pool utilization and efficiency analysis"
    echo "    • Connection leak detection and warnings"
    echo "    • Pool exhaustion monitoring"
    echo
    echo "  Multi-Admin Support:"
    echo "    • Verification of multiple admin configuration"
    echo "    • Admin notification delivery tracking"
    echo "    • Multi-admin command attribution analysis"
    echo "    • Authorization validation and audit trails"
    echo
    echo "  Repository Pattern Analysis:"
    echo "    • UserRepository, ChannelRepository, AdminRepository monitoring"
    echo "    • Database access layer error tracking"
    echo "    • Repository operation performance metrics"
    echo "    • Clean architecture validation"
    echo
    echo "  Database Migration System:"
    echo "    • Schema version tracking and validation"
    echo "    • Migration success/failure monitoring"
    echo "    • Database integrity and optimization analysis"
    echo "    • Automatic migration status reporting"
    echo
    echo "💡 QUICK EXAMPLES:"
    echo
    echo "  # Quick health check with pool status"
    echo "  docker exec -it telegram-job-bot debug.sh health"
    echo
    echo "  # Analyze recent errors including HikariCP issues"
    echo "  docker exec -it telegram-job-bot debug.sh logs 200"
    echo
    echo "  # Check database performance and pool efficiency"
    echo "  docker exec -it telegram-job-bot debug.sh database"
    echo
    echo "  # Comprehensive system analysis"
    echo "  docker exec -it telegram-job-bot debug.sh all"
    echo
    echo "  # Real-time log monitoring for specific issues"
    echo "  docker exec -it telegram-job-bot tail -f /app/logs/bot.log | grep -i hikari"
    echo
    echo "🔗 ARCHITECTURE-SPECIFIC TROUBLESHOOTING:"
    echo
    echo "  HikariCP Connection Issues:"
    echo "    debug.sh system | grep -A 10 'HikariCP'"
    echo "    debug.sh logs | grep -A 5 'Pool.*exhausted'"
    echo
    echo "  Multi-Admin Configuration:"
    echo "    debug.sh system | grep -A 5 'AUTHORIZED_ADMIN_IDS'"
    echo "    debug.sh logs | grep -i 'admin.*notification'"
    echo
    echo "  Repository Pattern Errors:"
    echo "    debug.sh logs | grep -A 10 'Repository.*error'"
    echo "    debug.sh database | grep -A 5 'Repository'"
    echo
    echo "  Database Migration Status:"
    echo "    debug.sh database | grep -A 10 'MIGRATION'"
    echo "    debug.sh logs | grep -i 'migration'"
    echo
    echo "🌐 MONITORING ENDPOINTS:"
    echo "  • General Health: http://localhost:8080/health"
    echo "  • Database Pool: http://localhost:8080/health/database"
    echo "  • Admin Dashboard: /admin in Telegram"
    echo
    echo "📚 Or run without arguments for interactive menu."
    exit 0
fi

case "$1" in
    "system")
        echo "🔍 Running enhanced system diagnostics..."
        echo "  • System information and health check"
        echo "  • HikariCP connection pool monitoring"
        echo "  • Multi-admin configuration verification"
        echo "  • TDLib status and native library validation"
        echo "  • Repository pattern status verification"
        echo
        debug-system.sh
        ;;
    "logs")
        echo "📊 Running enhanced log analysis..."
        echo "  • Comprehensive error pattern analysis"
        echo "  • HikariCP connection pool error detection"
        echo "  • Multi-admin notification tracking"
        echo "  • Repository pattern error analysis"
        echo "  • Database migration event monitoring"
        echo "  • TDLib authentication status tracking"
        echo
        analyze-logs.sh "$2"
        ;;
    "database")
        echo "🗄️ Running enhanced database inspection..."
        echo "  • Database file analysis and integrity check"
        echo "  • HikariCP connection pool statistics"
        echo "  • Repository pattern performance metrics"
        echo "  • Database migration status verification"
        echo "  • Multi-admin user management analytics"
        echo "  • Performance optimization recommendations"
        echo
        inspect-database.sh
        ;;
    "health")
        echo "🏥 Running quick health check..."
        echo "  • Bot process status verification"
        echo "  • HikariCP pool utilization check"
        echo "  • Database connectivity validation"
        echo "  • TDLib connection status"
        echo "  • Multi-admin configuration check"
        echo
        
        # Quick health check combining key indicators
        echo "📊 QUICK HEALTH SUMMARY:"
        echo "========================"
        
        # Bot process check
        if pgrep -f "com.jobbot\|telegram" > /dev/null 2>&1; then
            echo "✅ Bot Process: Running"
        else
            echo "❌ Bot Process: Not running"
        fi
        
        # Database file check
        DB_PATH="${DATABASE_PATH:-/app/data/bot.db}"
        if [ -f "$DB_PATH" ]; then
            echo "✅ Database: File exists ($(du -h "$DB_PATH" | cut -f1))"
        else
            echo "❌ Database: File missing"
        fi
        
        # Health endpoint check
        if curl -s -m 3 http://localhost:8080/health > /dev/null 2>&1; then
            echo "✅ Health Endpoint: Responding"
            
            # Try to get pool info
            POOL_INFO=$(curl -s -m 3 http://localhost:8080/health/database 2>/dev/null)
            if [ $? -eq 0 ] && [ -n "$POOL_INFO" ]; then
                ACTIVE=$(echo "$POOL_INFO" | grep -o '"activeConnections":[0-9]*' | cut -d':' -f2)
                MAX_POOL=$(echo "$POOL_INFO" | grep -o '"maximumPoolSize":[0-9]*' | cut -d':' -f2)
                echo "✅ HikariCP Pool: ${ACTIVE:-0}/${MAX_POOL:-10} connections active"
                
                if [ -n "$ACTIVE" ] && [ -n "$MAX_POOL" ] && [ "$MAX_POOL" -gt 0 ]; then
                    UTILIZATION=$((ACTIVE * 100 / MAX_POOL))
                    if [ "$UTILIZATION" -gt 80 ]; then
                        echo "⚠️ Pool Warning: High utilization (${UTILIZATION}%)"
                    else
                        echo "✅ Pool Status: Normal utilization (${UTILIZATION}%)"
                    fi
                fi
            else
                echo "⚠️ HikariCP Pool: Status unavailable"
            fi
        else
            echo "❌ Health Endpoint: Not responding"
        fi
        
        # Multi-admin config check
        if [ -n "$AUTHORIZED_ADMIN_IDS" ]; then
            ADMIN_COUNT=$(echo "$AUTHORIZED_ADMIN_IDS" | tr ',' '\n' | wc -l)
            echo "✅ Multi-Admin: $ADMIN_COUNT admins configured"
        else
            echo "❌ Multi-Admin: Configuration missing"
        fi
        
        # TDLib config check
        if [ -n "$API_ID" ] && [ -n "$API_HASH" ] && [ -n "$PHONE_NUMBER" ]; then
            echo "✅ TDLib Config: Complete"
        else
            echo "⚠️ TDLib Config: Incomplete (bot-only mode)"
        fi
        
        echo
        echo "💡 For detailed analysis, run: debug.sh all"
        ;;
    "all")
        echo "🔄 Running comprehensive diagnostic suite..."
        echo "  This will execute all debugging tools in sequence"
        echo "  and provide a complete system analysis."
        echo
        echo "🔍 Phase 1: System Diagnostics"
        echo "==============================="
        debug-system.sh
        echo
        echo "📊 Phase 2: Log Analysis"
        echo "========================"
        analyze-logs.sh
        echo
        echo "🗄️ Phase 3: Database Inspection"
        echo "================================="
        inspect-database.sh
        echo
        echo "📋 COMPREHENSIVE ANALYSIS COMPLETE"
        echo "==================================="
        echo
        echo "💡 Summary Recommendations:"
        echo "  • Check any ❌ or ⚠️ indicators above"
        echo "  • Monitor HikariCP pool utilization regularly"
        echo "  • Verify multi-admin notification delivery"
        echo "  • Ensure database migrations are up to date"
        echo "  • Use /admin dashboard for real-time monitoring"
        echo
        echo "🔗 Next Steps:"
        echo "  • Real-time monitoring: tail -f /app/logs/bot.log"
        echo "  • Pool monitoring: curl http://localhost:8080/health/database"
        echo "  • Admin interface: Use /admin in Telegram"
        ;;
    "")
        # Interactive menu with enhanced descriptions
        echo "Select debugging tool:"
        echo
        echo "1) 🔍 System Diagnostics"
        echo "   • System info, Java, TDLib native library status"
        echo "   • HikariCP connection pool monitoring"
        echo "   • Multi-admin configuration verification"
        echo "   • Performance indicators and troubleshooting"
        echo
        echo "2) 📊 Log Analysis"
        echo "   • Comprehensive error pattern analysis"
        echo "   • HikariCP pool error detection"
        echo "   • Multi-admin notification tracking"
        echo "   • Repository pattern error monitoring"
        echo "   • Database migration event analysis"
        echo
        echo "3) 🗄️ Database Inspection"
        echo "   • Database file analysis and integrity"
        echo "   • HikariCP pool statistics and performance"
        echo "   • Repository pattern metrics"
        echo "   • Migration status and recommendations"
        echo "   • Multi-admin user management analytics"
        echo
        echo "4) 🏥 Quick Health Check"
        echo "   • Rapid system status overview"
        echo "   • Connection pool health verification"
        echo "   • Configuration validation"
        echo
        echo "5) 🔄 Run All Tools"
        echo "   • Comprehensive diagnostic suite"
        echo "   • Complete system analysis"
        echo "   • All monitoring capabilities"
        echo
        echo "6) ❌ Exit"
        echo
        read -p "Enter choice (1-6): " choice
        
        case $choice in
            1)
                debug-system.sh
                ;;
            2)
                read -p "Number of log lines to analyze (default 100): " lines
                analyze-logs.sh "${lines:-100}"
                ;;
            3)
                inspect-database.sh
                ;;
            4)
                $0 health
                ;;
            5)
                $0 all
                ;;
            6)
                echo "👋 Goodbye!"
                exit 0
                ;;
            *)
                echo "❌ Invalid choice. Please select 1-6."
                exit 1
                ;;
        esac
        ;;
    *)
        echo "❌ Unknown option: $1"
        echo
        echo "💡 Available options:"
        echo "  system, logs [lines], database, health, all, --help"
        echo
        echo "📚 Examples:"
        echo "  debug.sh system           # System diagnostics with pool monitoring"
        echo "  debug.sh logs 200         # Analyze last 200 log lines"
        echo "  debug.sh database         # Database inspection with HikariCP stats"
        echo "  debug.sh health           # Quick health check"
        echo "  debug.sh all              # Comprehensive analysis"
        echo "  debug.sh --help           # Detailed help with examples"
        echo
        echo "🔗 Or run without arguments for interactive menu"
        exit 1
        ;;
esac
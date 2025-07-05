#!/bin/bash
# debug.sh - Main debugging script with menu

echo "🔧 TELEGRAM JOB BOT DEBUGGING TOOLS"
echo "===================================="
echo

if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "Available debugging tools:"
    echo
    echo "  debug.sh system      - System information and health check"
    echo "  debug.sh logs [N]    - Analyze logs (default: last 100 lines)"
    echo "  debug.sh database    - Database inspection and statistics"
    echo "  debug.sh all         - Run all debugging tools"
    echo "  debug.sh --help      - Show this help"
    echo
    echo "Or run without arguments for interactive menu."
    exit 0
fi

case "$1" in
    "system")
        echo "🔍 Running system diagnostics..."
        debug-system.sh
        ;;
    "logs")
        echo "📊 Analyzing logs..."
        analyze-logs.sh "$2"
        ;;
    "database")
        echo "🗄️ Inspecting database..."
        inspect-database.sh
        ;;
    "all")
        echo "🔄 Running complete diagnostic suite..."
        echo
        debug-system.sh
        echo
        analyze-logs.sh
        echo
        inspect-database.sh
        ;;
    "")
        # Interactive menu
        echo "Select debugging tool:"
        echo
        echo "1) System information and health check"
        echo "2) Log analysis"
        echo "3) Database inspection"
        echo "4) Run all tools"
        echo "5) Exit"
        echo
        read -p "Enter choice (1-5): " choice
        
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
                debug-system.sh
                echo
                analyze-logs.sh
                echo
                inspect-database.sh
                ;;
            5)
                echo "Goodbye!"
                exit 0
                ;;
            *)
                echo "Invalid choice"
                exit 1
                ;;
        esac
        ;;
    *)
        echo "Unknown option: $1"
        echo "Use debug.sh --help for available options"
        exit 1
        ;;
esac

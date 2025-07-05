# Utility Scripts

This directory contains utility scripts for debugging and maintaining the Telegram Job Bot.

## Available Scripts

### 🔧 debug.sh
Main debugging script with interactive menu or command-line options.

```bash
# Interactive menu
docker exec -it telegram-job-bot debug.sh

# Run specific tool
docker exec -it telegram-job-bot debug.sh system
docker exec -it telegram-job-bot debug.sh logs 200
docker exec -it telegram-job-bot debug.sh database
docker exec -it telegram-job-bot debug.sh all

# Get help
docker exec -it telegram-job-bot debug.sh --help
```

### 🔍 debug-system.sh
System diagnostics and health check.

**What it shows:**
- System information (OS, Java, architecture)
- TDLib native library status
- SSL libraries and OpenSSL version
- Database and log file information
- Environment configuration (non-sensitive)
- Process status and health endpoint test

### 📊 analyze-logs.sh
Log analysis and error detection.

**What it shows:**
- Error, warning, and info counts
- Recent error patterns and frequencies
- Performance indicators (startup times, message counts)
- Recent activity log entries
- Recommendations based on log analysis

```bash
# Analyze last 100 lines (default)
docker exec -it telegram-job-bot analyze-logs.sh

# Analyze last 500 lines
docker exec -it telegram-job-bot analyze-logs.sh 500
```

### 🗄️ inspect-database.sh
Database inspection and statistics.

**What it shows:**
- Database file information and size
- Table statistics and record counts
- User analysis (total, active, languages)
- Channel monitoring status
- User activity patterns
- Database health and integrity check
- Maintenance suggestions

## Usage Examples

### Quick Health Check
```bash
# System status
docker exec -it telegram-job-bot debug-system.sh

# Recent errors
docker exec -it telegram-job-bot analyze-logs.sh 50 | grep -A 10 "ERROR ANALYSIS"

# User count
docker exec -it telegram-job-bot inspect-database.sh | grep "Total registered users"
```

### Debugging Connection Issues
```bash
# Check TDLib status
docker exec -it telegram-job-bot debug-system.sh | grep -A 5 "TDLib"

# Check recent errors
docker exec -it telegram-job-bot analyze-logs.sh | grep -A 20 "Recent Error Messages"

# Check environment
docker exec -it telegram-job-bot debug-system.sh | grep -A 10 "Environment Variables"
```

### Performance Analysis
```bash
# Check message processing
docker exec -it telegram-job-bot analyze-logs.sh | grep -A 10 "PERFORMANCE INDICATORS"

# Check active users
docker exec -it telegram-job-bot inspect-database.sh | grep -A 10 "USERS ANALYSIS"

# Check database size
docker exec -it telegram-job-bot inspect-database.sh | grep "Size:"
```

### Full Diagnostic Report
```bash
# Run all tools and save to file
docker exec -it telegram-job-bot debug.sh all > diagnostic-report.txt
```

## Script Locations

Inside the container, scripts are located at:
- `/usr/local/bin/debug.sh`
- `/usr/local/bin/debug-system.sh`
- `/usr/local/bin/analyze-logs.sh`
- `/usr/local/bin/inspect-database.sh`

## Adding Custom Scripts

To add your own debugging scripts:

1. Create the script in the `scripts/` directory
2. Make it executable: `chmod +x scripts/your-script.sh`
3. Rebuild the Docker image
4. The script will be available at `/usr/local/bin/your-script.sh`

## Security Notes

- Scripts only show non-sensitive environment variables
- Database content is shown in truncated form for privacy
- No API tokens or secrets are displayed
- All scripts are read-only and don't modify data

# Utility Scripts

This directory contains utility scripts for debugging and maintaining the Telegram Job Bot with HikariCP connection pooling and multi-admin support.

## Available Scripts

### 🔧 debug.sh
Enhanced main debugging script with interactive menu, command-line options, and quick health checks.

```bash
# Interactive menu with detailed descriptions
docker exec -it telegram-job-bot debug.sh

# Quick health check with pool monitoring
docker exec -it telegram-job-bot debug.sh health

# Run specific tool with enhanced descriptions
docker exec -it telegram-job-bot debug.sh system
docker exec -it telegram-job-bot debug.sh logs 200
docker exec -it telegram-job-bot debug.sh database

# Comprehensive diagnostic suite
docker exec -it telegram-job-bot debug.sh all

# Enhanced help with architecture-specific examples
docker exec -it telegram-job-bot debug.sh --help
```

**Enhanced Features:**
- **Quick Health Check**: `debug.sh health` for rapid system overview
- **Architecture-Aware Help**: Specific troubleshooting examples for HikariCP, multi-admin, repository pattern
- **Enhanced Interactive Menu**: Detailed descriptions of each tool's capabilities
- **Smart Error Handling**: Better guidance and troubleshooting suggestions
- **Comprehensive Analysis**: `debug.sh all` with structured reporting and recommendations

### 🔍 debug-system.sh
System diagnostics and health check with HikariCP monitoring.

**What it shows:**
- System information (OS, Java, architecture)
- TDLib native library status and custom build verification
- SSL libraries and OpenSSL version compatibility
- Database and log file information with HikariCP pool status
- Environment configuration (non-sensitive) including multi-admin setup
- Process status and health endpoint testing
- HikariCP connection pool statistics and health

### 📊 analyze-logs.sh
Log analysis and error detection with enhanced pattern recognition.

**What it shows:**
- Error, warning, and info counts with categorization
- Recent error patterns and frequencies with HikariCP-specific errors
- Performance indicators (startup times, message counts, pool statistics)
- Recent activity log entries with connection pool events
- Rate limiting events and multi-admin notifications
- Recommendations based on log analysis and pool performance

```bash
# Analyze last 100 lines (default)
docker exec -it telegram-job-bot analyze-logs.sh

# Analyze last 500 lines
docker exec -it telegram-job-bot analyze-logs.sh 500
```

### 🗄️ inspect-database.sh
Database inspection and statistics with HikariCP pool monitoring.

**What it shows:**
- Database file information, size, and SQLite WAL mode status
- HikariCP connection pool statistics and performance metrics
- Table statistics and record counts including migration status
- User analysis (total, active, languages) with activity patterns
- Channel monitoring status with tag information
- User activity patterns and command usage analytics
- Banned users statistics and admin attribution
- Database health, integrity check, and migration version
- Repository pattern usage and performance metrics
- Maintenance suggestions and optimization recommendations

## Usage Examples

### Quick Health Check
```bash
# Rapid system status overview
docker exec -it telegram-job-bot debug.sh health

# Combined with specific monitoring
docker exec -it telegram-job-bot debug.sh health && echo "--- Detailed Analysis ---" && docker exec -it telegram-job-bot debug.sh logs 50
```

### Enhanced Debugging Workflows
```bash
# Complete system analysis workflow
docker exec -it telegram-job-bot debug.sh all > system-report-$(date +%Y%m%d).txt

# Architecture-specific troubleshooting
docker exec -it telegram-job-bot debug.sh system | grep -A 10 -E "HikariCP|Multi-Admin"
docker exec -it telegram-job-bot debug.sh logs | grep -A 5 -E "Repository|Migration"

# Real-time monitoring setup
docker exec -it telegram-job-bot debug.sh health  # Initial status
watch 'docker exec telegram-job-bot debug.sh health'  # Continuous monitoring
```

### Debugging Connection Issues
```bash
# Check TDLib and database status
docker exec -it telegram-job-bot debug-system.sh | grep -A 5 -E "TDLib|HikariCP"

# Check recent errors including pool issues
docker exec -it telegram-job-bot analyze-logs.sh | grep -A 20 "Recent Error Messages"

# Check environment and multi-admin config
docker exec -it telegram-job-bot debug-system.sh | grep -A 15 "Environment Variables"
```

### Performance Analysis
```bash
# Check message processing and pool performance
docker exec -it telegram-job-bot analyze-logs.sh | grep -A 10 "PERFORMANCE INDICATORS"

# Check active users and database performance
docker exec -it telegram-job-bot inspect-database.sh | grep -A 15 "USERS ANALYSIS"

# Check database size and pool efficiency
docker exec -it telegram-job-bot inspect-database.sh | grep -E "Size:|Connection pool:"
```

### Enhanced Architecture-Specific Monitoring
```bash
# HikariCP Connection Pool Monitoring
docker exec -it telegram-job-bot debug.sh system | grep -A 15 "HikariCP"
docker exec -it telegram-job-bot debug.sh logs | grep -A 10 "Pool.*exhausted\|Connection.*timeout"
docker exec -it telegram-job-bot debug.sh database | grep -A 20 "CONNECTION POOL"

# Multi-Admin System Verification
docker exec -it telegram-job-bot debug.sh system | grep -A 10 "Multi-Admin"
docker exec -it telegram-job-bot debug.sh logs | grep -i "admin.*notification\|multi.*admin"
docker exec -it telegram-job-bot debug.sh health  # Quick admin config check

# Repository Pattern Performance Analysis
docker exec -it telegram-job-bot debug.sh logs | grep -A 15 "Repository.*error\|Repository.*Pattern"
docker exec -it telegram-job-bot debug.sh database | grep -A 10 "REPOSITORY PATTERN"

# Database Migration Status Monitoring
docker exec -it telegram-job-bot debug.sh database | grep -A 15 "MIGRATION STATUS"
docker exec -it telegram-job-bot debug.sh logs | grep -i "migration\|schema.*version"

# TDLib Authentication Troubleshooting
docker exec -it telegram-job-bot debug.sh system | grep -A 10 "TDLib"
docker exec -it telegram-job-bot debug.sh logs | grep -A 5 "auth.*code\|auth.*password\|TDLib.*error"
```

### Enhanced Monitoring Workflows

#### Daily Health Check Routine
```bash
# Morning health check
docker exec -it telegram-job-bot debug.sh health

# If issues detected, run detailed analysis
docker exec -it telegram-job-bot debug.sh system  # System diagnostics
docker exec -it telegram-job-bot debug.sh logs 100  # Recent error analysis
docker exec -it telegram-job-bot debug.sh database  # Pool and migration status
```

#### Performance Monitoring
```bash
# Pool performance analysis
docker exec -it telegram-job-bot debug.sh database | grep -A 15 "Pool.*Efficiency\|Pool.*Utilization"

# Error trend analysis
docker exec -it telegram-job-bot debug.sh logs 500 | grep -A 20 "ERROR PATTERN ANALYSIS"

# Architecture health validation
docker exec -it telegram-job-bot debug.sh all | grep -A 5 "✅\|❌\|⚠️"
```

#### Troubleshooting Workflows
```bash
# Connection issues
docker exec -it telegram-job-bot debug.sh health  # Quick overview
docker exec -it telegram-job-bot debug.sh system | grep -A 10 "HikariCP\|Database"
docker exec -it telegram-job-bot debug.sh logs | grep -A 5 "Connection.*timeout\|Pool.*exhausted"

# Multi-admin problems
docker exec -it telegram-job-bot debug.sh system | grep -A 5 "AUTHORIZED_ADMIN_IDS"
docker exec -it telegram-job-bot debug.sh logs | grep -i "admin.*notification.*failed"

# Performance degradation
docker exec -it telegram-job-bot debug.sh database | grep -A 10 "OPTIMIZATION RECOMMENDATIONS"
docker exec -it telegram-job-bot debug.sh logs | grep -A 10 "PERFORMANCE INDICATORS"
```

### Database Migration Status
```bash
# Check migration version and status
docker exec -it telegram-job-bot inspect-database.sh | grep -A 5 "Migration"

# Verify repository pattern usage
docker exec -it telegram-job-bot analyze-logs.sh | grep -i "repository\|hikaricp"

# Check database optimization
docker exec -it telegram-job-bot inspect-database.sh | grep -A 10 "OPTIMIZATION"
```

### Full Diagnostic Report
```bash
# Run all tools and save to file with timestamp
docker exec -it telegram-job-bot debug.sh all > diagnostic-report-$(date +%Y%m%d-%H%M%S).txt

# Quick system overview
docker exec -it telegram-job-bot debug.sh system | head -50
```

## Enhanced Features

### HikariCP Integration
- **Connection Pool Monitoring**: Real-time pool statistics and health
- **Performance Metrics**: Connection timing and efficiency analysis
- **Leak Detection**: Connection leak monitoring and alerts
- **Pool Optimization**: Configuration recommendations and tuning

### Multi-Admin Support
- **Admin Configuration**: Verification of multiple admin setup
- **Notification Tracking**: Admin notification delivery monitoring
- **Command Attribution**: Admin command tracking and audit trails
- **Access Verification**: Admin authorization validation

### Database Migrations
- **Version Tracking**: Current migration version and history
- **Migration Status**: Success/failure tracking and logs
- **Schema Validation**: Table structure and index verification
- **Data Integrity**: Constraint validation and health checks

### Repository Pattern Monitoring
- **Usage Analytics**: Repository method call tracking
- **Performance Metrics**: Query performance and efficiency
- **Connection Management**: Repository-level connection usage
- **Error Tracking**: Repository-specific error monitoring

## Script Locations

Inside the container, scripts are located at:
- `/usr/local/bin/debug.sh`
- `/usr/local/bin/debug-system.sh`
- `/usr/local/bin/analyze-logs.sh`
- `/usr/local/bin/inspect-database.sh`

## Environment Variables

Scripts automatically detect and use these environment variables:
- `DATABASE_PATH` - Database file location (defaults to `/app/data/bot.db`)
- `LOG_PATH` - Log files directory (defaults to `/app/logs`)
- `AUTHORIZED_ADMIN_IDS` - Multi-admin configuration verification
- `API_ID`, `API_HASH`, `PHONE_NUMBER` - TDLib configuration status

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
- Admin IDs are shown for configuration verification only

## Troubleshooting

### Common Issues

**Database Connection Problems:**
```bash
# Check HikariCP pool status
docker exec -it telegram-job-bot inspect-database.sh | grep -A 10 "CONNECTION POOL"

# Verify database file permissions
docker exec -it telegram-job-bot debug-system.sh | grep -A 5 "Database:"
```

**TDLib Authentication Issues:**
```bash
# Check TDLib status and native library
docker exec -it telegram-job-bot debug-system.sh | grep -A 10 "TDLib"

# Check authentication logs
docker exec -it telegram-job-bot analyze-logs.sh | grep -i "auth\|tdlib"
```

**Performance Issues:**
```bash
# Check connection pool performance
docker exec -it telegram-job-bot inspect-database.sh | grep -A 15 "PERFORMANCE"

# Analyze rate limiting and user activity
docker exec -it telegram-job-bot analyze-logs.sh | grep -A 10 "rate limit\|performance"
```

**Multi-Admin Issues:**
```bash
# Verify admin configuration
docker exec -it telegram-job-bot debug-system.sh | grep -A 3 "AUTHORIZED_ADMIN_IDS"

# Check admin notification delivery
docker exec -it telegram-job-bot analyze-logs.sh | grep -i "admin.*notification"
```

## Performance Monitoring

### Key Metrics to Watch
- **HikariCP Pool**: Active/idle connection ratios
- **Database Performance**: Query response times and connection efficiency
- **Memory Usage**: Pool memory consumption and optimization
- **Error Rates**: Connection timeouts and pool exhaustion
- **Migration Status**: Schema version and migration success rates

### Recommended Monitoring Schedule
- **Real-time**: During high-traffic periods or system changes
- **Daily**: General health checks and performance reviews
- **Weekly**: Comprehensive analysis and optimization reviews
- **Monthly**: Historical trend analysis and capacity planning
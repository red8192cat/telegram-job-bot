# Telegram Job Bot - Technical Specification (Updated)

## Table of Contents
1. [Project Overview](#project-overview)
2. [Architecture Design](#architecture-design)
3. [Project Structure](#project-structure)
4. [Core Components](#core-components)
5. [Database Design](#database-design)
6. [API Integrations](#api-integrations)
7. [Keyword Matching System](#keyword-matching-system)
8. [Message Flow](#message-flow)
9. [Admin Dashboard System](#admin-dashboard-system)
10. [User Management System](#user-management-system)
11. [Rate Limiting System](#rate-limiting-system)
12. [System Monitoring](#system-monitoring)
13. [Configuration Management](#configuration-management)
14. [Error Handling & Logging](#error-handling--logging)
15. [Deployment](#deployment)
16. [Development Guidelines](#development-guidelines)
17. [Security Considerations](#security-considerations)
18. [Performance Optimization](#performance-optimization)
19. [Future Enhancements](#future-enhancements)

## Project Overview

### Purpose
A production-ready Telegram bot that monitors job-related channels and sends personalized notifications to users based on their keyword preferences. The bot features a comprehensive admin dashboard, user management system, and advanced monitoring capabilities.

### Key Requirements
- **Channel Monitoring**: Monitor multiple Telegram channels for new job postings
- **Advanced Keyword Matching**: Sophisticated keyword matching with wildcards, phrases, required/optional logic
- **Admin Dashboard**: Interactive dashboard with real-time monitoring and controls
- **User Management**: User banning, activity tracking, and preference management
- **System Controls**: Emergency shutdown, rate limit management, log level control
- **Production Ready**: Docker deployment, comprehensive logging, error recovery, real-time notifications

### Technology Stack
- **Language**: Kotlin (JVM target, Java 21)
- **Database**: SQLite with custom DAO layer and banned users support
- **Telegram APIs**: 
  - Telegram Bot API (for user interactions and admin dashboard)
  - TDLib (for channel monitoring)
- **Build System**: Gradle with Kotlin DSL
- **Deployment**: Docker with health checks and persistent storage
- **Logging**: Logback with dynamic log level control
- **Concurrency**: Kotlin Coroutines with structured concurrency

## Architecture Design

### High-Level Architecture
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Telegram      │    │   Telegram      │    │     Admin       │
│   Channels      │    │     Users       │    │   Dashboard     │
│                 │    │                 │    │                 │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          │ New Messages         │ Commands             │ Interactive Controls
          │                      │                      │
          ▼                      ▼                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Telegram Job Bot                            │
├─────────────────┬─────────────────┬─────────────────────────────┤
│   TelegramUser  │  TelegramBot    │     AdminCommands           │
│   (TDLib)       │  (Bot API)      │   • Dashboard               │
│                 │                 │   • User Management         │
│                 │                 │   • System Controls         │
├─────────────────┼─────────────────┼─────────────────────────────┤
│             MessageProcessor     │     UserCommands            │
├─────────────────┬─────────────────┼─────────────────────────────┤
│    Database     │   RateLimiter   │     SystemMonitor           │
│   • Users       │   • Dynamic     │   • Real-time Alerts        │
│   • Channels    │   • Per-user    │   • Health Monitoring       │
│   • Banned      │   • Admin       │   • Error Tracking          │
│   • Activity    │     Controls    │   • Shutdown Management     │
└─────────────────┴─────────────────┴─────────────────────────────┘
```

### Component Interaction Flow
1. **TelegramUser** monitors channels via TDLib
2. **MessageProcessor** analyzes messages against user keywords
3. **TelegramBot** handles user interactions and admin dashboard
4. **AdminCommands** provides comprehensive system management
5. **UserManagement** handles banning, activity tracking
6. **SystemMonitor** provides real-time alerts and health monitoring
7. **Database** stores all persistent data with banned users support

### Design Principles
- **Separation of Concerns**: Each component has a single responsibility
- **Event-Driven**: Asynchronous message processing with coroutines
- **Fault Tolerance**: Automatic reconnection and error recovery
- **Real-time Monitoring**: Live system status and alerts
- **Admin-Focused**: Comprehensive admin tools for production management
- **Maintainability**: Clean code structure with extensive documentation

## Project Structure

```
telegram-job-bot/
├── .gitignore                    # Git ignore rules (includes secrets protection)
├── README.md                     # User documentation and setup guide
├── TECHNICAL_SPECIFICATION.md    # This comprehensive technical document
├── Dockerfile                    # Multi-stage Docker build configuration
├── docker-compose.yml            # Docker deployment with health checks
├── bot-secrets.env               # Environment variables (NOT in git)
│
├── gradle/                       # Gradle configuration directory
│   ├── build.gradle.kts          # Build script with dependencies
│   ├── settings.gradle.kts       # Project settings
│   └── gradle.properties         # Gradle properties and JVM settings
│
├── scripts/                      # Utility scripts for debugging
│   ├── debug.sh                  # Main debugging script
│   ├── debug-system.sh           # System diagnostics
│   ├── analyze-logs.sh           # Log analysis
│   └── inspect-database.sh       # Database inspection
│
└── src/
    ├── bot/                      # Main source code directory
    │   ├── Main.kt               # Application entry point and startup logic
    │   ├── Config.kt             # Configuration management and validation
    │   ├── Database.kt           # SQLite operations with banned users support
    │   ├── TelegramBot.kt        # Bot API integration with admin notifications
    │   ├── TelegramUser.kt       # TDLib integration and channel monitoring
    │   ├── MessageProcessor.kt   # Keyword matching and message analysis
    │   ├── AdminCommands.kt      # Comprehensive admin dashboard and controls
    │   ├── UserCommands.kt       # User command handlers with ban checks
    │   ├── Utils.kt              # Enhanced utilities with system management
    │   └── Models.kt             # Data classes with user management models
    │
    └── resources/                # Resource files
        ├── schema.sql            # Database schema with banned users table
        ├── messages_en.properties # English localization strings
        ├── messages_ru.properties # Russian localization strings
        └── logback.xml           # Logging configuration
```

## Core Components

### Main.kt - Application Entry Point
```kotlin
// Key responsibilities:
// 1. Configuration loading and validation with rate limit checks
// 2. Component initialization with admin notification setup
// 3. Shutdown hook registration with graceful cleanup
// 4. Health check server startup with system monitoring
// 5. TDLib and Bot API coordination
// 6. Admin startup notifications with system status
```

### Config.kt - Enhanced Configuration Management
```kotlin
object Config {
    // Environment variable loading with enhanced validation
    // Rate limit configuration with bounds checking (10-200)
    // TDLib configuration validation with warnings
    // Directory creation for data, logs, and TDLib files
    // Configuration caching and runtime access
    // Admin-friendly configuration reporting
}
```

### Database.kt - Extended Data Access Layer
```kotlin
class Database {
    // Enhanced SQLite connection management
    // Schema initialization with banned users support
    // User CRUD operations with ban status checks
    // Channel management operations
    // User activity tracking with command counts
    // Banned user management with admin audit trails
    // Transaction handling and connection pooling
    // Admin query methods for dashboard
}
```

### TelegramBot.kt - Enhanced Bot API Integration
```kotlin
class TelegramBot : TelegramLongPollingBot {
    // Bot API client with admin callback handling
    // Message routing (user vs admin commands)
    // Admin notification system for real-time alerts
    // Rate limit alert notifications with action buttons
    // Shutdown reminder system (30-minute intervals)
    // Enhanced notification queue management
    // Coroutine-based admin notification processing
}
```

### AdminCommands.kt - Comprehensive Admin Dashboard
```kotlin
class AdminCommands {
    // Interactive dashboard with real-time status
    // System health monitoring and reporting
    // Channel management with TDLib integration
    // Rate limit management with dynamic updates
    // User banning system with confirmation dialogs
    // Log level control without restart requirements
    // Emergency shutdown with maintenance messaging
    // Error tracking and analysis tools
    // Callback query handling for interactive buttons
}
```

### UserCommands.kt - Enhanced User Interface
```kotlin
class UserCommands {
    // User command processing with ban checks
    // Shutdown mode detection and messaging
    // Enhanced rate limiting with user feedback
    // Interactive menu system with one-button-per-row layout
    // Callback query handling for menu navigation
    // User preference management
    // Multi-language support with dynamic switching
}
```

## Database Design

### Enhanced Schema Overview
```sql
-- Core users table with enhanced tracking
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER UNIQUE NOT NULL,
    language TEXT DEFAULT 'en',
    keywords TEXT,
    ignore_keywords TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Monitored channels table
CREATE TABLE channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id TEXT UNIQUE NOT NULL,
    channel_name TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- User activity tracking for admin analytics
CREATE TABLE user_activity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_telegram_id INTEGER NOT NULL,
    last_interaction DATETIME DEFAULT CURRENT_TIMESTAMP,
    command_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_telegram_id) REFERENCES users(telegram_id)
);

-- NEW: Banned users table for user management
CREATE TABLE banned_users (
    user_id INTEGER PRIMARY KEY,
    banned_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL,
    banned_by_admin INTEGER NOT NULL
);

-- Performance indexes for admin queries
CREATE INDEX idx_users_telegram_id ON users(telegram_id);
CREATE INDEX idx_channels_channel_id ON channels(channel_id);
CREATE INDEX idx_activity_user_id ON user_activity(user_telegram_id);
CREATE INDEX idx_banned_users_id ON banned_users(user_id);
CREATE INDEX idx_banned_users_admin ON banned_users(banned_by_admin);
```

### Data Flow Enhancements
1. User interactions trigger ban status checks
2. Admin actions logged with timestamps and reasons
3. Activity tracking for command usage analytics
4. Banned user checks integrated into all user flows
5. Real-time admin notifications for user issues

## Admin Dashboard System

### Interactive Dashboard Features
The admin dashboard provides comprehensive system control through an interactive interface:

#### **Main Dashboard** (`/admin dashboard`)
- **Real-time system status** (users, channels, errors, rate limits)
- **TDLib connection status** monitoring
- **Current log level** display
- **Shutdown mode indicator** when active
- **One-button-per-row layout** for mobile-friendly interface

#### **System Information Commands**
- **📈 System Health**: Complete health report with uptime, message counts, error rates
- **🚨 Recent Errors**: Detailed error log with timestamps and stack traces
- **📋 Channel List**: All monitored channels with join dates and status
- **📊 Log Level Management**: Dynamic log level control (DEBUG/INFO/WARN/ERROR)

#### **Channel Management**
- **➕ Add Channel**: Pre-filled command input for easy channel addition
- **➖ Remove Channel**: Pre-filled command input for channel removal
- **TDLib Integration**: Automatic joining/leaving with status feedback

#### **Rate Limit Management**
- **🚦 Rate Limits**: Comprehensive rate limit status and user analytics
- **Dynamic Configuration**: Change rate limits instantly (10-200 range validation)
- **User-specific Actions**: Clear individual user limits with buttons
- **Real-time Alerts**: Automatic notifications when users hit rate limits

#### **User Management System**
- **👤 Banned Users**: Complete banned user list with reasons and timestamps
- **🚫 Ban User**: Confirmation-required banning with mandatory reason
- **🟢 Unban User**: One-click unbanning with audit trail
- **Activity Tracking**: User command counts and interaction history

#### **Emergency Controls**
- **⚠️ Emergency Shutdown**: Graceful bot shutdown with maintenance messaging
- **🟢 Cancel Shutdown**: Restore normal operation
- **30-minute Reminders**: Automatic reminders during shutdown mode
- **Admin Command Continuity**: Admin commands work during shutdown

### Admin Notification System
Real-time notifications keep admins informed of system events:

#### **Rate Limit Alerts**
```
🚨 Rate Limit Alert
User: 123456789 (@username)
Event: Hit rate limit (0 tokens remaining)
Total commands: 147
[🚫 Ban User] [🧹 Clear Rate Limit]
```

#### **Shutdown Reminders**
```
🚨 Shutdown Mode Reminder
⚠️ Bot has been in shutdown mode for 30+ minutes
Current Status: 5 users affected, 3 channels monitoring
[🟢 Cancel Shutdown] [📊 View Dashboard]
```

## User Management System

### User Lifecycle Management
The system provides comprehensive user lifecycle tracking and management:

#### **User States**
- **Active**: Users with keywords set, receiving notifications
- **Inactive**: Users without keywords, not receiving notifications
- **Banned**: Users prohibited from using the bot
- **Rate Limited**: Users temporarily blocked due to excessive commands

#### **Banning System**
- **Confirmation Required**: All bans require explicit confirmation
- **Mandatory Reason**: Ban reason must be provided for audit trail
- **Admin Tracking**: All bans logged with admin ID and timestamp
- **Instant Effect**: Banned users immediately blocked from all bot functions
- **Audit Trail**: Complete history of ban actions for compliance

#### **Activity Tracking**
- **Command Counting**: Track total commands per user
- **Last Interaction**: Monitor user engagement patterns
- **Rate Limit Events**: Log when users hit rate limits
- **Admin Analytics**: Aggregate data for system optimization

### User Interface Enhancements
- **Ban Status Checks**: All user commands check ban status first
- **Shutdown Mode Handling**: Graceful maintenance messaging
- **Rate Limit Feedback**: Clear messaging when limits are hit
- **One-Button-Per-Row**: Mobile-friendly interface design

## Rate Limiting System

### Enhanced Token Bucket Algorithm
The rate limiting system provides sophisticated traffic control with admin management:

#### **Dynamic Configuration**
```kotlin
class RateLimiter {
    // Runtime rate limit updates (10-200 range validation)
    // Immediate application to all users
    // Admin notification integration
    // Per-user token state management
    // Overloaded user tracking and alerts
}
```

#### **Configuration Options**
- **Messages Per Minute**: 10-200 (configurable, defaults to 60)
- **Burst Size**: 10-200 (configurable, defaults to 10)
- **Validation**: Range checking with admin feedback
- **Reset to ENV**: Settings reset to environment variables on restart

#### **Admin Integration**
- **Real-time Alerts**: Immediate notification when users hit limits
- **User-specific Control**: Clear individual user limits
- **Bulk Management**: Clear all rate limits with confirmation
- **Status Dashboard**: Live view of overloaded users and statistics

#### **User Experience**
- **Progressive Limiting**: Token bucket allows burst then sustained rate
- **Clear Messaging**: Users informed when rate limited
- **Restoration Notifications**: Users notified when limits restored
- **Queue Management**: Notifications queued during rate limiting

## System Monitoring

### Real-time Health Monitoring
Comprehensive system monitoring provides real-time insights:

#### **System Health Metrics**
- **Uptime**: Time since bot startup
- **Message Processing**: Total messages processed since startup
- **Active Users**: Users with keywords configured
- **Error Counts**: Recent error statistics with severity levels
- **TDLib Status**: Connection state and authentication status
- **Rate Limit Status**: Active users, overloaded users, configuration

#### **Error Tracking System**
```kotlin
object ErrorTracker {
    // In-memory error storage (last 100 errors)
    // Error categorization (ERROR, WARN, INFO)
    // Timestamp tracking for debugging
    // Admin interface integration
    // Automatic cleanup of old errors
}
```

#### **Admin Alerts**
- **Rate Limit Violations**: Immediate alerts with user details
- **System Errors**: Critical error notifications
- **Shutdown Reminders**: Regular reminders during maintenance
- **TDLib Connection Issues**: Authentication and connection alerts

### Shutdown Management
Emergency shutdown capabilities for maintenance:

#### **Shutdown Features**
- **Graceful Shutdown**: Users receive maintenance message
- **Admin Continuity**: Admin commands continue to work
- **30-minute Reminders**: Automatic reminder system
- **Easy Restoration**: One-command restoration to normal operation
- **Status Tracking**: Duration tracking and admin notifications

## Configuration Management

### Enhanced Environment Variables
```bash
# Core Configuration (Required)
TELEGRAM_BOT_TOKEN=              # Bot token from @BotFather
AUTHORIZED_ADMIN_ID=             # Admin's Telegram user ID
BOT_USERNAME=                    # Bot username for interface elements

# TDLib Configuration (Optional - enables channel monitoring)
API_ID=                          # API ID from my.telegram.org
API_HASH=                        # API hash from my.telegram.org  
PHONE_NUMBER=                    # Phone number for user account

# Enhanced System Configuration
DATABASE_PATH=/app/data/bot.db   # SQLite database file path
LOG_PATH=/app/logs               # Log files directory
LOG_LEVEL=INFO                   # Initial logging level (changeable at runtime)

# Enhanced Rate Limiting (Validation: 10-200)
RATE_LIMIT_MESSAGES_PER_MINUTE=60  # Default: 60 (was 20)
RATE_LIMIT_BURST_SIZE=10           # Default: 10 (was 5)
```

### Runtime Configuration
- **Dynamic Log Levels**: Change log verbosity without restart
- **Dynamic Rate Limits**: Adjust rate limiting in real-time
- **Configuration Validation**: Comprehensive startup validation
- **Admin Reporting**: Configuration status in admin dashboard

## Error Handling & Logging

### Enhanced Logging Architecture
- **Dynamic Log Levels**: Runtime log level control via admin commands
- **Structured Logging**: Consistent log format with timestamps
- **Error Categorization**: ERROR, WARN, INFO levels with filtering
- **Admin Integration**: Recent errors accessible via admin dashboard
- **File Rotation**: Automatic log rotation by date and size

### Error Recovery Strategies
- **TDLib Reconnection**: Exponential backoff with admin notifications
- **Rate Limit Management**: User feedback and admin alerts
- **Database Resilience**: Connection retry with error logging
- **Admin Notifications**: Real-time alerts for critical issues
- **Graceful Degradation**: Shutdown mode for maintenance

## Deployment

### Enhanced Docker Configuration
```dockerfile
# Multi-stage build for minimal production image
FROM gradle:8.5-jdk21-alpine AS builder
# Optimized build process with dependency caching

FROM openjdk:21-jdk-alpine
# Production runtime with health checks
# Enhanced signal handling for graceful shutdown
# Proper user permissions and security
```

### Production Features
- **Health Monitoring**: HTTP endpoint for Docker health checks
- **Persistent Storage**: Database and logs in mounted volumes
- **Resource Limits**: Memory and CPU limits for production
- **Security**: Non-root user execution, minimal attack surface
- **Admin Notifications**: Startup and shutdown notifications

## Development Guidelines

### Enhanced Code Organization
- **Admin-Focused Design**: Comprehensive admin tooling for production
- **Real-time Monitoring**: Live system status and alerts
- **User Management**: Complete user lifecycle management
- **Error Resilience**: Comprehensive error handling and recovery
- **Documentation**: Extensive inline and technical documentation

### Testing Strategy
- **Unit Tests**: Core functionality with mock dependencies
- **Integration Tests**: Database operations and API integrations
- **Admin Testing**: Dashboard functionality and admin workflows
- **Load Testing**: Rate limiting and system performance
- **Security Testing**: Authentication and authorization

## Security Considerations

### Enhanced Security Measures
- **Admin Authorization**: Strict admin command authorization
- **User Banning**: Immediate blocking of problematic users
- **Rate Limiting**: Protection against abuse and DoS attacks
- **Input Validation**: Comprehensive input sanitization
- **Audit Trails**: Complete logging of admin actions

### Operational Security
- **Secret Management**: Environment-only secret storage
- **Container Security**: Non-root execution, minimal base image
- **Access Control**: Admin-only system management functions
- **Monitoring**: Real-time alerts for security events

## Performance Optimization

### Enhanced Performance Features
- **Dynamic Rate Limiting**: Efficient token bucket implementation
- **Caching**: Regex pattern caching for keyword matching
- **Connection Pooling**: Efficient database connection management
- **Coroutine Optimization**: Structured concurrency for scalability
- **Memory Management**: Efficient object lifecycle management

### Monitoring and Metrics
- **Real-time Dashboards**: Live system performance monitoring
- **Response Time Tracking**: Command processing performance
- **Resource Monitoring**: Memory and CPU usage tracking
- **User Analytics**: Activity patterns and usage statistics

## Future Enhancements

### Planned Features
1. **Advanced Analytics**: User engagement metrics, keyword effectiveness
2. **Machine Learning**: Job relevance scoring, smart keyword suggestions
3. **Rich Notifications**: Enhanced message formatting with action buttons
4. **Job Deduplication**: Intelligent duplicate detection and filtering
5. **Multi-Admin Support**: Multiple admin users with role-based access
6. **API Integration**: RESTful API for external system integration
7. **Database Migration**: PostgreSQL support for higher scale
8. **Metrics Export**: Prometheus/Grafana integration

### Scalability Roadmap
1. **Microservices**: Split into specialized services
2. **Message Queues**: External queue systems (Redis, RabbitMQ)
3. **Load Balancing**: Multiple bot instances with shared state
4. **Caching Layer**: Redis for performance optimization
5. **Monitoring**: Comprehensive observability stack

---

## Conclusion

This enhanced Telegram Job Bot represents a production-ready solution with comprehensive admin tooling, user management, and system monitoring. The architecture emphasizes real-time control, operational visibility, and administrative efficiency.

**Key Production Features:**
- **Interactive Admin Dashboard**: Complete system control via Telegram interface
- **User Management**: Comprehensive user lifecycle management with banning
- **Real-time Monitoring**: Live system health and performance metrics
- **Emergency Controls**: Graceful shutdown and maintenance capabilities
- **Enhanced Security**: Admin authorization, user banning, and audit trails
- **Operational Excellence**: Dynamic configuration, error recovery, and alerting

The system is designed for single-admin operation with comprehensive tooling for production deployment, monitoring, and maintenance. All admin functions are accessible via Telegram, making remote management convenient and efficient.
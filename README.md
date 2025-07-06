# Telegram Job Bot

Smart Telegram bot that monitors job channels/groups and sends personalized notifications based on users' keywords.

## Features

- **🎯 Smart Keyword Matching** - Wildcards, required keywords, ignore lists, AND/OR logic
- **🤖 Dual Account System** - User account monitors channels, bot handles interactions  
- **🔧 Interactive Admin Dashboard** - Real-time controls with hierarchical navigation
- **👥 Multi-Admin Support** - Multiple authorized administrators
- **👤 Advanced User Management** - Ban users, track activity, manage rate limits
- **💾 Professional Database** - HikariCP connection pooling with migrations
- **🌐 Multi-Language** - English and Russian support

## Quick Setup

### 1. Get Tokens
```bash
# Bot token from @BotFather
# Admin user ID(s) from @userinfobot  
# API credentials from my.telegram.org (optional, for channel monitoring)
```

### 2. Configure
```bash
cp bot-secrets.env.example bot-secrets.env
nano bot-secrets.env  # Add your tokens
```

**Key Configuration:**
```bash
# Required
TELEGRAM_BOT_TOKEN=your_bot_token
AUTHORIZED_ADMIN_IDS=123456789,987654321  # Multiple admins supported
BOT_USERNAME=your_bot_username

# Optional (enables channel monitoring)
API_ID=your_api_id
API_HASH=your_api_hash  
PHONE_NUMBER=+1234567890
```

### 3. Deploy
```bash
docker compose up -d
```

## Usage

### For Users
Send `/start` to your bot:
- **🎯 Keywords** - Set job search keywords with advanced syntax
- **🚫 Ignore** - Block unwanted terms
- **🌐 Language** - Switch between English/Russian

**Keyword Examples:**
```
Basic: python, developer, remote
Wildcards: develop*, engineer*
Required: [remote], [python/java]
AND groups: python+django, react+typescript
Ignore: junior*, intern*, sales*
```

### For Admins
Use `/admin` for the interactive dashboard:
- **📊 System Management** - Health, errors, log levels, multi-admin list
- **📋 Channel Management** - Add/remove channels, bulk validation
- **👤 User Management** - Rate limits, ban/unban users
- **🔐 TDLib Authentication** - Handle 2FA if needed

## Debugging & Maintenance

The bot includes comprehensive debugging tools with architectural awareness:

```bash
# Interactive debugging menu
docker exec -it telegram-job-bot debug.sh

# Quick health check with pool monitoring
docker exec -it telegram-job-bot debug.sh health

# Enhanced system diagnostics
docker exec -it telegram-job-bot debug-system.sh     

# Advanced log analysis with architectural awareness
docker exec -it telegram-job-bot analyze-logs.sh     

# Database inspection with HikariCP monitoring
docker exec -it telegram-job-bot inspect-database.sh 

# Comprehensive diagnostic suite
docker exec -it telegram-job-bot debug.sh all
```

**Enhanced Capabilities:**
- **HikariCP Monitoring**: Real-time connection pool statistics, utilization analysis, leak detection
- **Multi-Admin Verification**: Configuration validation, notification delivery tracking
- **Repository Pattern Analysis**: UserRepository, ChannelRepository, AdminRepository error tracking
- **Database Migration Monitoring**: Schema version tracking, migration success/failure analysis
- **Architecture Validation**: `com.jobbot` package structure verification
- **TDLib Authentication**: Connection status, auth event tracking, native library validation
- **Notification Processor**: Queue monitoring, delivery metrics, performance analysis

**Architecture-Specific Troubleshooting:**
```bash
# HikariCP connection pool issues
docker exec -it telegram-job-bot debug.sh system | grep -A 10 'HikariCP'

# Multi-admin configuration problems  
docker exec -it telegram-job-bot debug.sh logs | grep -i 'admin.*notification'

# Repository pattern errors
docker exec -it telegram-job-bot debug.sh database | grep -A 10 'Repository'

# Database migration status
docker exec -it telegram-job-bot debug.sh database | grep -A 5 'Migration'
```

## Monitoring

- **Health Check**: `http://localhost:8080/health`
- **Database Pool**: `http://localhost:8080/health/database`  
- **Logs**: `docker compose logs -f`
- **Admin Dashboard**: `/admin` in Telegram

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    TELEGRAM JOB BOT                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────┐                  ┌──────────────────┐     │
│  │  CHANNEL SIDE    │                  │   USER SIDE      │     │
│  │                  │                  │                  │     │
│  │  TelegramUser    │                  │  TelegramBot     │     │
│  │  (TDLib)         │                  │  (Bot API)       │     │
│  │                  │                  │                  │     │
│  │  ┌─────────────┐ │                  │ ┌─────────────┐  │     │
│  │  │ Channel     │ │                  │ │ User        │  │     │
│  │  │ Monitor     │ │                  │ │ Commands    │  │     │
│  │  └─────────────┘ │                  │ └─────────────┘  │     │
│  │                  │                  │                  │     │
│  └──────────┬───────┘                  └──────────┬───────┘     │
│             │                                     │             │
│             │         ┌─────────────────┐         │             │
│             └────────▶│ MESSAGE         │◀────────┘             │
│                       │ PROCESSOR       │                       │
│                       └────────┬────────┘                       │
│                                │                                 │
│             ┌──────────────────┼──────────────────┐              │
│             │                  ▼                  │              │
│   ┌─────────▼─────────┐ ┌─────────────┐ ┌────────▼─────────┐    │
│   │ USER REPOSITORY   │ │  DATABASE   │ │ CHANNEL REPOSITORY│    │
│   │                   │ │ (HikariCP)  │ │                   │    │
│   │ • CRUD Ops        │ │             │ │ • Channel Mgmt    │    │
│   │ • Activity Track  │ │ • Pooling   │ │ • Tag Resolution  │    │
│   └───────────────────┘ │ • Migration │ └───────────────────┘    │
│                         │ • Health    │                          │
│   ┌───────────────────┐ └─────────────┘ ┌───────────────────┐    │
│   │ ADMIN REPOSITORY  │                 │ NOTIFICATION      │    │
│   │                   │                 │ PROCESSOR         │    │
│   │ • Ban Management  │                 │                   │    │
│   │ • Multi-Admin     │                 │ • Queue Mgmt      │    │
│   └───────────────────┘                 └───────────────────┘    │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    ADMIN LAYER                            │   │
│  │                                                           │   │
│  │ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │   │
│  │ │ Dashboard   │ │ System      │ │ Rate        │          │   │
│  │ │ Handler     │ │ Monitor     │ │ Limiter     │          │   │
│  │ └─────────────┘ └─────────────┘ └─────────────┘          │   │
│  │                                                           │   │
│  │ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │   │
│  │ │ Channel     │ │ User        │ │ Error       │          │   │
│  │ │ Handler     │ │ Handler     │ │ Tracker     │          │   │
│  │ └─────────────┘ └─────────────┘ └─────────────┘          │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘

DATA FLOW:
Channels → TDLib → ChannelMonitor → MessageProcessor → Database → 
NotificationProcessor → Users

ADMIN FLOW:  
Multiple Admins → AdminCommandRouter → Specialized Handlers → 
Database Repositories → Real-time Dashboard
```

## Production Features

- **HikariCP Connection Pooling** - Professional database performance
- **Database Migrations** - Automatic schema updates
- **Multi-Admin Support** - Team-friendly administration
- **Rate Limiting** - Dynamic user throttling with admin controls
- **Health Monitoring** - Docker-compatible health checks
- **Error Tracking** - Comprehensive logging and analysis
- **Emergency Controls** - Graceful shutdown capabilities

---

Need help? Use `/admin help` or run `docker exec -it telegram-job-bot debug.sh` for system diagnostics.
# Telegram Job Bot

Smart Telegram bot that monitors job channels/groups and sends personalized notifications based on users' keywords.

## Features

- **🎯 Smart Keyword Matching** - Wildcards, required keywords, ignore lists
- **🤖 Dual Account System** - User account monitors channels, bot handles interactions  
- **🔧 Admin Dashboard** - Interactive admin panel with real-time controls
- **👤 User Management** - Ban users, track activity, manage rate limits
- **🌐 Multi-Language** - English and Russian support

## Quick Setup

### 1. Get Tokens
```bash
# Bot token from @BotFather
# Your admin user ID from @userinfobot
# API credentials from my.telegram.org
```

### 2. Configure
```bash
cp bot-secrets.env.example bot-secrets.env
nano bot-secrets.env  # Add your tokens
```

### 3. Deploy
```bash
docker compose up -d
```

## Configuration

**Credentials in bot-secrets.env:**
- `TELEGRAM_BOT_TOKEN` - Get from [@BotFather](https://t.me/BotFather)
- `AUTHORIZED_ADMIN_ID` - Your Telegram user ID from [@userinfobot](https://t.me/userinfobot)
- `API_ID`, `API_HASH`, `PHONE_NUMBER` - Get from [my.telegram.org](https://my.telegram.org/auth)

## Usage

### For Users
Send `/start` to your bot:
- **🎯 Keywords** - Set job search keywords (`[remote*|online], python, project manag*`)
- **🚫 Ignore** - Block unwanted terms (`senior*, sell*`)
- **🌐 Language** - Switch to Russian/English

### For Admins
Use `/admin` for the interactive dashboard:
- **📊 System** - Health, errors, log levels, TDLib auth
- **📋 Channels** - Add/remove channels, check status
- **👤 Users** - Rate limits, ban/unban users

## Monitoring

- **Health Check**: `http://localhost:8080/health`
- **Logs**: `docker compose logs -f`
- **Admin Dashboard**: `/admin` in Telegram

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                        TELEGRAM JOB BOT                        │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────┐                    ┌──────────────────┐  │
│  │  CHANNEL SIDE    │                    │   USER SIDE      │  │
│  │                  │                    │                  │  │
│  │  Telegram        │                    │  Telegram        │  │
│  │  Channels  ─┐    │                    │  Users    ──┐    │  │
│  │             │    │                    │             │    │  │
│  │  ┌──────────▼──┐ │                    │  ┌──────────▼──┐ │  │
│  │  │    TDLib    │ │                    │  │  Bot API    │ │  │
│  │  │ (Monitor)   │ │                    │  │ (Interact)  │ │  │
│  │  └──────────┬──┘ │                    │  └──────────┬──┘ │  │
│  │             │    │                    │             │    │  │
│  │  ┌──────────▼──┐ │                    │  ┌──────────▼──┐ │  │
│  │  │  Message    │ │                    │  │  User       │ │  │
│  │  │ Processor   │ │                    │  │ Commands    │ │  │
│  │  │(Keywords)   │ │                    │  │             │ │  │
│  │  └──────────┬──┘ │                    │  └──────────┬──┘ │  │
│  │             │    │                    │             │    │  │
│  └─────────────┼────┘                    └─────────────┼────┘  │
│                │                                       │       │
│                │         ┌─────────────┐               │       │
│                └───────▶│  DATABASE   │◀─────────────┘       │
│                          │             │                       │
│                          │ • Users     │                       │
│                          │ • Channels  │                       │
│                          │ • Banned    │                       │
│                          │   Users     │                       │
│                          │ • Activity  │                       │
│                          └─────────┬───┘                       │
│                                    │                           │
│  ┌─────────────────────────────────▼────────────────────────┐  │
│  │                  ADMIN LAYER                             │  │
│  │                                                          │  │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │   │   Dashboard  │  │ User Manager │  │Rate Limiter  │   │  │
│  │   │ • Health     │  │ • Ban Users  │  │• Alerts      │   │  │
│  │   │ • Channels   │  │ • Activity   │  │• Controls    │   │  │
│  │   │ • Errors     │  │ • Tracking   │  │• Limits      │   │  │
│  │   └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  │                                                          │  │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│  │   │System Monitor│  │Log Manager   │  │ Shutdown     │   │  │
│  │   │• Real-time   │  │• Dynamic     │  │ Controller   │   │  │
│  │   │• Alerts      │  │• Levels      │  │• Maintenance │   │  │
│  │   │• Health      │  │• Runtime     │  │• Mode        │   │  │
│  │   └──────────────┘  └──────────────┘  └──────────────┘   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
└────────────────────────────────────────────────────────────────┘

DATA FLOW:
Channels → TDLib → Message Processor → Database → Notifications → Users
                                          ↕
                                    Admin Layer
                                  (Monitor & Control)
```
---

Need help? Use `/admin help` or check logs with `docker compose logs`

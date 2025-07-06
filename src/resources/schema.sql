-- Database schema for Telegram Job Bot
-- UPDATED: Added Premium User Support

-- Users table (UPDATED with premium columns)
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    telegram_id INTEGER NOT NULL UNIQUE,
    language TEXT NOT NULL DEFAULT 'en',
    keywords TEXT,
    ignore_keywords TEXT,
    is_premium INTEGER DEFAULT 0 CHECK (is_premium IN (0, 1)),
    premium_granted_at DATETIME,
    premium_granted_by INTEGER,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Channels table with enhanced support for tags
CREATE TABLE IF NOT EXISTS channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id TEXT NOT NULL UNIQUE,
    channel_name TEXT,
    channel_tag TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- User activity tracking
CREATE TABLE IF NOT EXISTS user_activity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_telegram_id INTEGER NOT NULL UNIQUE,
    last_interaction DATETIME DEFAULT CURRENT_TIMESTAMP,
    command_count INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_telegram_id) REFERENCES users(telegram_id)
);

-- Banned users table
CREATE TABLE IF NOT EXISTS banned_users (
    user_id INTEGER PRIMARY KEY,
    banned_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL,
    banned_by_admin INTEGER NOT NULL
);

-- NEW: Premium users tracking table
CREATE TABLE IF NOT EXISTS premium_users (
    user_id INTEGER PRIMARY KEY,
    granted_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    granted_by_admin INTEGER NOT NULL,
    reason TEXT,
    is_active INTEGER DEFAULT 1 CHECK (is_active IN (0, 1)),
    revoked_at DATETIME,
    revoked_by_admin INTEGER,
    revoke_reason TEXT,
    FOREIGN KEY (user_id) REFERENCES users(telegram_id)
);

-- Schema version table for migrations
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_users_telegram_id ON users(telegram_id);
CREATE INDEX IF NOT EXISTS idx_users_premium ON users(is_premium);
CREATE INDEX IF NOT EXISTS idx_channels_channel_id ON channels(channel_id);
CREATE INDEX IF NOT EXISTS idx_channels_tag ON channels(channel_tag);
CREATE INDEX IF NOT EXISTS idx_activity_user_id ON user_activity(user_telegram_id);
CREATE INDEX IF NOT EXISTS idx_banned_users ON banned_users(user_id);
CREATE INDEX IF NOT EXISTS idx_premium_users_user_id ON premium_users(user_id);
CREATE INDEX IF NOT EXISTS idx_premium_users_active ON premium_users(is_active);

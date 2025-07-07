-- Database schema for Telegram Job Bot
-- UPDATED: Ultra-simplified with all user data in one table

-- Users table (everything user-related in ONE place)
CREATE TABLE IF NOT EXISTS users (
    telegram_id INTEGER PRIMARY KEY,
    language TEXT DEFAULT 'en',
    
    -- Keywords
    keywords TEXT,
    ignore_keywords TEXT,
    
    -- Activity (minimal tracking)
    last_interaction DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    -- Premium (simplified)
    is_premium INTEGER DEFAULT 0 CHECK (is_premium IN (0, 1)),
    premium_granted_at DATETIME,
    premium_expires_at DATETIME,  -- NULL = permanent
    premium_reason TEXT,
    
    -- Moderation (integrated)
    is_banned INTEGER DEFAULT 0 CHECK (is_banned IN (0, 1)),
    banned_at DATETIME,
    ban_reason TEXT,
    
    -- Timestamps
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Channels table (unchanged - working well)
CREATE TABLE IF NOT EXISTS channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id TEXT NOT NULL UNIQUE,
    channel_name TEXT,
    channel_tag TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Schema version table for migrations
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_users_premium ON users(is_premium);
CREATE INDEX IF NOT EXISTS idx_users_banned ON users(is_banned);
CREATE INDEX IF NOT EXISTS idx_users_expires ON users(premium_expires_at);
CREATE INDEX IF NOT EXISTS idx_channels_channel_id ON channels(channel_id);
CREATE INDEX IF NOT EXISTS idx_channels_tag ON channels(channel_tag);
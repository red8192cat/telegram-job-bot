package com.jobbot.data.repositories

import com.jobbot.data.models.Channel
import com.jobbot.data.models.ChannelDetails
import com.jobbot.shared.getLogger
import java.sql.Connection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChannelRepository(private val getConnection: () -> Connection) {
    private val logger = getLogger("ChannelRepository")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    fun addChannel(channelId: String, channelName: String?): Boolean {
        val sql = "INSERT OR IGNORE INTO channels (channel_id, channel_name) VALUES (?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.setString(2, channelName)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add channel $channelId" }
            false
        }
    }
    
    /**
     * Enhanced channel storage with both ID and tag
     */
    fun addChannelWithDetails(channelId: String, channelTag: String?, channelName: String?): Boolean {
        val sql = "INSERT OR REPLACE INTO channels (channel_id, channel_name, channel_tag) VALUES (?, ?, ?)"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.setString(2, channelName)
                    stmt.setString(3, channelTag)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to add channel $channelId with tag $channelTag" }
            false
        }
    }
    
    /**
     * Find channel by tag (returns the actual ID)
     */
    fun findChannelIdByTag(channelTag: String): String? {
        val cleanTag = channelTag.removePrefix("@").lowercase()
        val sql = "SELECT channel_id FROM channels WHERE LOWER(REPLACE(channel_tag, '@', '')) = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, cleanTag)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("channel_id") else null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to find channel by tag $channelTag" }
            null
        }
    }
    
    /**
     * Update channel tag for existing channel ID
     */
    fun updateChannelTag(channelId: String, newTag: String?, newName: String?): Boolean {
        val sql = "UPDATE channels SET channel_tag = ?, channel_name = ?, updated_at = CURRENT_TIMESTAMP WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, newTag)
                    stmt.setString(2, newName)
                    stmt.setString(3, channelId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update channel tag for $channelId" }
            false
        }
    }
    
    fun removeChannel(channelId: String): Boolean {
        val sql = "DELETE FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to remove channel $channelId" }
            false
        }
    }
    
    /**
     * Remove channel by ID (primary method)
     */
    fun removeChannelById(channelId: String): Boolean {
        val sql = "DELETE FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to remove channel by ID $channelId" }
            false
        }
    }
    
    fun getAllChannels(): List<Channel> {
        val sql = "SELECT * FROM channels ORDER BY created_at ASC"
        val channels = mutableListOf<Channel>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channels.add(
                                Channel(
                                    id = rs.getLong("id"),
                                    channelId = rs.getString("channel_id"),
                                    channelName = rs.getString("channel_name"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channels" }
        }
        
        return channels
    }
    
    /**
     * Get all channels with enhanced info
     */
    fun getAllChannelsWithDetails(): List<ChannelDetails> {
        val sql = "SELECT * FROM channels ORDER BY created_at ASC"
        val channels = mutableListOf<ChannelDetails>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channels.add(
                                ChannelDetails(
                                    id = rs.getLong("id"),
                                    channelId = rs.getString("channel_id"),
                                    channelName = rs.getString("channel_name"),
                                    channelTag = rs.getString("channel_tag"),
                                    createdAt = LocalDateTime.parse(rs.getString("created_at"), dateFormatter),
                                    updatedAt = rs.getString("updated_at")?.let { 
                                        LocalDateTime.parse(it, dateFormatter) 
                                    }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channels with details" }
        }
        
        return channels
    }
    
    /**
     * Get all channel IDs for bulk update operations
     */
    fun getAllChannelIds(): List<String> {
        val sql = "SELECT channel_id FROM channels"
        val channelIds = mutableListOf<String>()
        
        try {
            getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            channelIds.add(rs.getString("channel_id"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all channel IDs" }
        }
        
        return channelIds
    }
    
    fun channelExists(channelId: String): Boolean {
        val sql = "SELECT 1 FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if channel exists $channelId" }
            false
        }
    }
    
    /**
     * Check if channel exists by ID
     */
    fun channelExistsById(channelId: String): Boolean {
        val sql = "SELECT 1 FROM channels WHERE channel_id = ?"
        
        return try {
            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, channelId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check if channel exists by ID $channelId" }
            false
        }
    }
}

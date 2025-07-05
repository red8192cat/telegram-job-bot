package com.jobbot.admin

import com.jobbot.bot.TelegramBot
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.data.Database
import com.jobbot.data.models.ChannelDetails
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import java.time.format.DateTimeFormatter

/**
 * Handles all channel management operations
 * BULLETPROOF: NO MARKDOWN - Works with any channel names, IDs, tags, error messages
 */
class AdminChannelHandler(
    private val database: Database,
    private val telegramUser: TelegramUser?
) {
    private val logger = getLogger("AdminChannelHandler")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    
    private var bot: TelegramBot? = null
    
    fun setBotInstance(botInstance: TelegramBot) {
        this.bot = botInstance
    }
    
    fun handleChannelsList(chatId: String): SendMessage {
        val channels = database.getAllChannelsWithDetails()
        
        val responseText = if (channels.isEmpty()) {
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            "${Localization.getAdminMessage("admin.channels.title")}\n" +
            "${Localization.getAdminMessage("admin.channels.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.channels.empty")
        } else {
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            
            val channelList = channels.mapIndexed { index, channel ->
                val tagDisplay = channel.channelTag ?: Localization.getAdminMessage("admin.common.tag.unavailable")
                val nameDisplay = channel.channelName ?: "Unknown"
                
                Localization.getAdminMessage(
                    "admin.channels.item",
                    index + 1,
                    tagDisplay,
                    channel.channelId,
                    nameDisplay
                )
            }.joinToString("\n\n")
            
            "${Localization.getAdminMessage("admin.channels.title")}\n" +
            "${Localization.getAdminMessage("admin.channels.timestamp", timestamp)}\n\n" +
            Localization.getAdminMessage("admin.channels.list", channelList, channels.size)
        }

        return SendMessage.builder()
            .chatId(chatId)
            .text(responseText)
            // NO parseMode - channel names can contain any special characters
            .build()
    }
    
    /**
     * Add channel with proper ID and tag resolution - NO MARKDOWN (handles any channel input)
     */
    fun handleAddChannel(chatId: String, text: String): SendMessage {
        val channelInput = text.substringAfter("/admin add_channel").trim()
        
        if (channelInput.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.add.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val channelId = TextUtils.parseChannelIdentifier(channelInput) ?: channelInput
        
        // Check if channel already exists by ID first
        if (database.channelExistsById(channelId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.add.exists", channelId))
                // NO parseMode - channel ID safe
                .build()
        }
        
        // Try to get detailed channel info and join via TDLib
        if (telegramUser != null) {
            try {
                val (joinSuccess, lookupResult) = runBlocking {
                    telegramUser.joinChannelEnhanced(channelInput)
                }
                
                if (!joinSuccess || !lookupResult.found || lookupResult.channelId == null) {
                    val errorMsg = lookupResult.error ?: "Unknown error"
                    return SendMessage.builder()
                        .chatId(chatId)
                        .text(Localization.getAdminMessage("admin.channels.add.failed", channelInput, errorMsg))
                        // NO parseMode - error messages can contain special chars
                        .build()
                }
                
                // Store channel with detailed info
                val success = database.addChannelWithDetails(
                    channelId = lookupResult.channelId,
                    channelTag = lookupResult.channelTag,
                    channelName = lookupResult.channelName
                )
                
                return if (success) {
                    logger.debug { "Channel ${lookupResult.channelId} (${lookupResult.channelTag}) added by admin" }
                    val tagInfo = lookupResult.channelTag ?: Localization.getAdminMessage("admin.common.tag.unavailable")
                    SendMessage.builder()
                        .chatId(chatId)
                        .text(Localization.getAdminMessage(
                            "admin.channels.add.success",
                            lookupResult.channelId,
                            tagInfo,
                            lookupResult.channelName ?: "Unknown"
                        ))
                        // NO parseMode - channel data can contain special chars
                        .build()
                } else {
                    SendMessage.builder()
                        .chatId(chatId)
                        .text(Localization.getAdminMessage("admin.channels.add.database.failed"))
                        // NO parseMode - bulletproof
                        .build()
                }
                
            } catch (e: Exception) {
                logger.error(e) { "Failed to add channel $channelInput" }
                return SendMessage.builder()
                    .chatId(chatId)
                    .text(Localization.getAdminMessage("admin.channels.add.error.generic", e.message ?: "Unknown error"))
                    // NO parseMode - exception messages can contain special chars
                    .build()
            }
        } else {
            // TDLib not available - basic storage only
            val success = database.addChannelWithDetails(channelId, null, null)
            
            return if (success) {
                logger.debug { "Channel $channelId added by admin (TDLib unavailable)" }
                SendMessage.builder()
                    .chatId(chatId)
                    .text(Localization.getAdminMessage("admin.channels.add.tdlib.unavailable", channelId))
                    // NO parseMode - channel ID safe
                    .build()
            } else {
                SendMessage.builder()
                    .chatId(chatId)
                    .text(Localization.getAdminMessage("admin.channels.add.database.failed"))
                    // NO parseMode - bulletproof
                    .build()
            }
        }
    }

    /**
     * Remove channel - NO MARKDOWN (handles channel IDs, tags, error messages)
     */
    fun handleRemoveChannel(chatId: String, text: String): SendMessage {
        val channelInput = text.substringAfter("/admin remove_channel").trim()
        
        if (channelInput.isBlank()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.remove.usage"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val actualChannelId = when {
            // If it looks like an ID, use directly
            channelInput.matches(Regex("-?[0-9]{10,}")) -> {
                channelInput
            }
            // If it's a tag, lookup via Telegram API to get current ID
            channelInput.startsWith("@") || !channelInput.matches(Regex("-?[0-9]{10,}")) -> {
                if (telegramUser == null) {
                    return SendMessage.builder()
                        .chatId(chatId)
                        .text(Localization.getAdminMessage("admin.channels.remove.tdlib.unavailable"))
                        // NO parseMode - bulletproof
                        .build()
                }
                
                try {
                    val lookupResult = runBlocking {
                        telegramUser.lookupChannelInfo(channelInput)
                    }
                    
                    if (!lookupResult.found || lookupResult.channelId == null) {
                        val errorMsg = lookupResult.error ?: "Channel not found"
                        return SendMessage.builder()
                            .chatId(chatId)
                            .text(Localization.getAdminMessage("admin.channels.remove.resolve.failed", channelInput, errorMsg))
                            // NO parseMode - error messages can contain special chars
                            .build()
                    }
                    
                    logger.debug { "Resolved @tag $channelInput to ID: ${lookupResult.channelId}" }
                    lookupResult.channelId
                    
                } catch (e: Exception) {
                    logger.error(e) { "Failed to lookup channel $channelInput via Telegram API" }
                    return SendMessage.builder()
                        .chatId(chatId)
                        .text(Localization.getAdminMessage("admin.channels.remove.lookup.failed", channelInput, e.message ?: "Unknown error"))
                        // NO parseMode - exception messages can contain special chars
                        .build()
                }
            }
            // Parse other formats (t.me links, etc.)
            else -> {
                val parsed = TextUtils.parseChannelIdentifier(channelInput)
                if (parsed?.matches(Regex("-?[0-9]{10,}")) == true) {
                    parsed
                } else {
                    // It's not an ID, need to resolve via Telegram API
                    if (telegramUser == null) {
                        return SendMessage.builder()
                            .chatId(chatId)
                            .text(Localization.getAdminMessage("admin.channels.remove.tdlib.unavailable"))
                            // NO parseMode - bulletproof
                            .build()
                    }
                    
                    try {
                        val lookupResult = runBlocking {
                            telegramUser.lookupChannelInfo(channelInput)
                        }
                        
                        if (!lookupResult.found || lookupResult.channelId == null) {
                            val errorMsg = lookupResult.error ?: "Channel not found"
                            return SendMessage.builder()
                                .chatId(chatId)
                                .text(Localization.getAdminMessage("admin.channels.remove.resolve.failed", channelInput, errorMsg))
                                // NO parseMode - error messages can contain special chars
                                .build()
                        }
                        
                        logger.debug { "Resolved $channelInput to ID: ${lookupResult.channelId}" }
                        lookupResult.channelId
                        
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to lookup channel $channelInput via Telegram API" }
                        return SendMessage.builder()
                            .chatId(chatId)
                            .text(Localization.getAdminMessage("admin.channels.remove.lookup.failed", channelInput, e.message ?: "Unknown error"))
                            // NO parseMode - exception messages can contain special chars
                            .build()
                    }
                }
            }
        }
        
        // Check if channel exists in our database
        if (!database.channelExistsById(actualChannelId)) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.remove.not.found", actualChannelId, channelInput))
                // NO parseMode - channel data safe
                .build()
        }
        
        // Try to leave the channel via TDLib
        if (telegramUser != null) {
            try {
                runBlocking {
                    telegramUser.leaveChannel(actualChannelId)
                }
                logger.debug { "Successfully left channel $actualChannelId via TDLib" }
            } catch (e: Exception) {
                logger.warn { "Failed to leave channel $actualChannelId via TDLib: ${e.message}" }
                // Continue with database removal even if leaving failed
            }
        }
        
        // Remove channel from database
        val success = database.removeChannelById(actualChannelId)
        
        return if (success) {
            logger.debug { "Channel $actualChannelId removed by admin (input: $channelInput)" }
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.remove.success", actualChannelId, channelInput))
                // NO parseMode - channel data safe
                .build()
        } else {
            SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.remove.database.failed", actualChannelId))
                // NO parseMode - channel ID safe
                .build()
        }
    }
    
    /**
     * Bulk check all channels - NO MARKDOWN (handles any system responses)
     */
    fun handleCheckAllChannels(chatId: String): SendMessage {
        if (telegramUser == null) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.check.tdlib.unavailable"))
                // NO parseMode - bulletproof
                .build()
        }
        
        val allChannels = database.getAllChannelsWithDetails()
        
        if (allChannels.isEmpty()) {
            return SendMessage.builder()
                .chatId(chatId)
                .text(Localization.getAdminMessage("admin.channels.check.empty"))
                // NO parseMode - bulletproof
                .build()
        }
        
        // Send initial response and start async check process
        scope.launch {
            performChannelCheck(chatId, allChannels)
        }
        
        return SendMessage.builder()
            .chatId(chatId)
            .text(Localization.getAdminMessage(
                "admin.channels.check.start",
                allChannels.size,
                allChannels.size * 0.5
            ))
            // NO parseMode - bulletproof
            .build()
    }
    
    /**
     * Perform the actual bulk update asynchronously - NO MARKDOWN (handles any channel data)
     */
    private suspend fun performChannelCheck(chatId: String, channels: List<ChannelDetails>) {
        try {
            logger.debug { "Starting channel check for ${channels.size} channels" }
            if (bot == null) {
                logger.error { "Bot instance is null - cannot send admin notifications" }
                return
            }
            logger.debug { "Bot instance is available" }

            val results = mutableListOf<ChannelCheckResult>()
            
            for (channel in channels) {
                try {
                    logger.debug { "Checking channel: ${channel.channelId} (tag: ${channel.channelTag})" }
                    val result = checkSingleChannel(channel)
                    results.add(result)
                    logger.debug { "Added result to list, total results: ${results.size}" }
                    
                    // Small delay to avoid rate limiting
                    logger.debug { "About to delay 500ms..." }
                    delay(500)
                    logger.debug { "Delay completed" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to check channel ${channel.channelId}" }
                    results.add(ChannelCheckResult(
                        channelId = channel.channelId,
                        channelTag = channel.channelTag,
                        storedName = channel.channelName,
                        status = ChannelStatus.BROKEN,
                        issue = "Check failed: ${e.message}"
                    ))
                }
            }
            
            logger.debug { "For loop completed, generating report..." }
            
            // Generate and send report
            val report = generateChannelReport(results)
            bot?.sendAdminNotification(report)
            
        } catch (e: Exception) {
            logger.error(e) { "Channel check failed" }
            bot?.sendAdminNotification(Localization.getAdminMessage("admin.channels.check.error", e.message ?: "Unknown error"))
        }
    }

    private suspend fun checkSingleChannel(channel: ChannelDetails): ChannelCheckResult {
        logger.debug { "Starting checkSingleChannel for: ${channel.channelId} (tag: ${channel.channelTag})" }
        
        // Step 1: Try to resolve the channel by tag/link to get current ID and info
        val lookupInput = if (!channel.channelTag.isNullOrBlank()) {
            channel.channelTag
        } else {
            channel.channelId
        }
        logger.debug { "Looking up channel with input: $lookupInput (from tag: ${channel.channelTag}, id: ${channel.channelId})" }
        
        val lookupResult = telegramUser!!.lookupChannelInfo(lookupInput)
        logger.debug { "Lookup completed: found=${lookupResult.found}, id=${lookupResult.channelId}" }
        
        if (!lookupResult.found || lookupResult.channelId == null) {
            logger.debug { "Channel lookup failed, returning BROKEN result" }
            return ChannelCheckResult(
                channelId = channel.channelId,
                channelTag = channel.channelTag,
                storedName = channel.channelName,
                status = ChannelStatus.BROKEN,
                issue = "Cannot resolve channel: ${lookupResult.error ?: "Not found"}"
            )
        }
        
        // Step 2: Compare IDs
        val currentId = lookupResult.channelId
        val storedId = channel.channelId
        logger.debug { "Comparing IDs: stored=$storedId, current=$currentId" }
        
        if (currentId != storedId) {
            logger.debug { "ID mismatch detected, returning BROKEN result" }
            return ChannelCheckResult(
                channelId = storedId,
                channelTag = channel.channelTag,
                storedName = channel.channelName,
                currentId = currentId,
                currentName = lookupResult.channelName,
                status = ChannelStatus.BROKEN,
                issue = "ID changed from $storedId to $currentId"
            )
        }
        
        // Step 3: Check permissions (try to get chat info and check if we can access it)
        logger.debug { "Checking access for channel: $currentId" }
        val hasAccess = checkChannelAccess(currentId)
        logger.debug { "Access check result: $hasAccess" }
        
        if (!hasAccess) {
            logger.debug { "Access check failed, returning BROKEN result" }
            return ChannelCheckResult(
                channelId = storedId,
                channelTag = channel.channelTag,
                storedName = channel.channelName,
                currentId = currentId,
                currentName = lookupResult.channelName,
                status = ChannelStatus.BROKEN,
                issue = "Lost access (banned or channel deleted)"
            )
        }
        
        // Step 4: Compare names and update if different
        val storedName = channel.channelName
        val currentName = lookupResult.channelName
        logger.debug { "Comparing names: stored='$storedName', current='$currentName'" }
        
        val nameChanged = currentName != null && storedName != currentName
        
        if (nameChanged) {
            logger.debug { "Name changed, updating in database" }
            // Update name in database
            val updateSuccess = database.updateChannelTag(
                channelId = storedId,
                newTag = channel.channelTag, // Keep existing tag
                newName = currentName
            )
            
            if (updateSuccess) {
                logger.debug { "Updated channel name: $storedId from '$storedName' to '$currentName'" }
            } else {
                logger.warn { "Failed to update channel name in database" }
            }
        }
        
        // Step 5: Return result
        logger.debug { "checkSingleChannel returning VALID result" }
        return ChannelCheckResult(
            channelId = storedId,
            channelTag = channel.channelTag,
            storedName = storedName,
            currentId = currentId,
            currentName = currentName,
            status = ChannelStatus.VALID,
            nameUpdated = nameChanged
        )
    }

    private suspend fun checkChannelAccess(channelId: String): Boolean {
        return try {
            val chatId = channelId.toLong()
            val accessResult = telegramUser!!.checkChannelAccess(chatId)
            accessResult
        } catch (e: Exception) {
            logger.warn(e) { "Failed to check access for channel $channelId" }
            false
        }
    }

    /**
     * Generate channel report - NO MARKDOWN (channel data can contain special chars)
     */
    private fun generateChannelReport(results: List<ChannelCheckResult>): String {
        val validChannels = results.filter { it.status == ChannelStatus.VALID }
        val brokenChannels = results.filter { it.status == ChannelStatus.BROKEN }
        val updatedNames = validChannels.filter { it.nameUpdated }
        
        val brokenList = if (brokenChannels.isNotEmpty()) {
            brokenChannels.mapIndexed { index, result ->
                val tagDisplay = result.channelTag ?: result.channelId
                Localization.getAdminMessage("admin.channels.check.broken.item", index + 1, tagDisplay, result.issue ?: "Unknown issue")
            }.joinToString("\n")
        } else ""
        
        val updatedList = if (updatedNames.isNotEmpty()) {
            updatedNames.mapIndexed { index, result ->
                val tagDisplay = result.channelTag ?: result.channelId
                Localization.getAdminMessage("admin.channels.check.updated.item", 
                    index + 1, tagDisplay, result.storedName ?: "Unknown", result.currentName ?: "Unknown")
            }.joinToString("\n")
        } else ""
        
        return Localization.getAdminMessage(
            "admin.channels.check.report",
            validChannels.size,
            brokenChannels.size,
            results.size,
            if (brokenList.isNotEmpty()) "\n\nBroken Channels:\n$brokenList" else "",
            if (updatedList.isNotEmpty()) "\n\nName Updates:\n$updatedList" else ""
        )
    }

    // Data classes for check results
    data class ChannelCheckResult(
        val channelId: String,
        val channelTag: String?,
        val storedName: String?,
        val currentId: String? = null,
        val currentName: String? = null,
        val status: ChannelStatus,
        val issue: String? = null,
        val nameUpdated: Boolean = false
    )

    enum class ChannelStatus {
        VALID,
        BROKEN
    }
}

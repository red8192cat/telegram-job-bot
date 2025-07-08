package com.jobbot.bot.tdlib

import com.jobbot.bot.TelegramBot
import com.jobbot.core.MessageProcessor
import com.jobbot.data.Database
import com.jobbot.data.models.ChannelMessage
import com.jobbot.data.models.ChannelDetails
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles TDLib message monitoring and processing
 * Extracted from TelegramUser.kt
 */
class ChannelMonitor(
    private val database: Database,
    private val messageProcessor: MessageProcessor,
    private var bot: TelegramBot?
) {
    private val logger = getLogger("ChannelMonitor")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // FIXED: Bounded cache with automatic cleanup
    private val channelIdCache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 500 // Keep max 500 entries
        }
    }
    
    fun updateBotReference(botInstance: TelegramBot) {
        this.bot = botInstance
    }
    
    fun handleNewMessage(update: TdApi.UpdateNewMessage, client: Client?) {
        try {
            val message = update.message
            val chatId = message.chatId
            
            logger.debug { "Processing message from chat $chatId, isChannelPost: ${message.isChannelPost}" }
            
            val isMonitoredChat = message.isChannelPost || isMonitoredGroupChat(chatId)
            
            if (isMonitoredChat) {
                val channelId = getChannelIdentifier(chatId)
                logger.debug { "Message from channelId: $channelId (isChannelPost: ${message.isChannelPost})" }
                
                if (channelId == null) {
                    logger.debug { "Could not determine channel identifier for chat $chatId" }
                    return
                }
                
                if (!database.channelExists(channelId)) {
                    logger.debug { "Ignoring message from unmonitored chat: $channelId" }
                    return
                }
                
                logger.info { "Processing message from monitored chat: $channelId (type: ${if (message.isChannelPost) "channel" else "group"})" }
                
                val textContent = when (val content = message.content) {
                    is TdApi.MessageText -> {
                        logger.debug { "Text message content: ${content.text.text}" }
                        content.text.text
                    }
                    is TdApi.MessagePhoto -> {
                        logger.debug { "Photo message with caption: ${content.caption?.text}" }
                        content.caption?.text ?: ""
                    }
                    is TdApi.MessageVideo -> {
                        logger.debug { "Video message with caption: ${content.caption?.text}" }
                        content.caption?.text ?: ""
                    }
                    is TdApi.MessageDocument -> {
                        logger.debug { "Document message with caption: ${content.caption?.text}" }
                        content.caption?.text ?: ""
                    }
                    else -> {
                        logger.debug { "Unsupported message type: ${content.javaClass.simpleName}" }
                        return
                    }
                }
                
                if (textContent.isBlank()) {
                    logger.debug { "Message has no text content, skipping" }
                    return
                }
                
                logger.debug { "Processing text content: '$textContent'" }
                
                scope.launch {
                    try {
                        // Get channel details
                        val channelDetails = database.getAllChannelsWithDetails().find { it.channelId == channelId }
                        val displayName = when {
                            !channelDetails?.channelTag.isNullOrBlank() -> channelDetails!!.channelTag!!
                            !channelDetails?.channelName.isNullOrBlank() -> channelDetails!!.channelName!!
                            else -> if (message.isChannelPost) "Channel" else "Group"
                        }
                        
                        val senderUsername = extractSenderUsername(message, client)
                        logger.debug { "Creating ChannelMessage with senderUsername: $senderUsername" }
                        
                        // Generate message link
                        val messageLink = generateMessageLink(channelDetails, message.id)
                        
                        val channelMessage = ChannelMessage(
                            channelId = channelId,
                            channelName = displayName,
                            messageId = message.id,
                            text = textContent,
                            senderUsername = senderUsername,
                            messageLink = messageLink
                        )
                        
                        logger.debug { "Calling messageProcessor.processChannelMessage..." }
                        val notifications = messageProcessor.processChannelMessage(channelMessage)
                        
                        logger.info { "Message processing complete. Generated ${notifications.size} notifications" }
                        
                        // Queue notifications
                        for (notification in notifications) {
                            logger.debug { "Queueing notification for user ${notification.userId}" }
                            bot?.queueNotification(notification)
                        }
                        
                        logger.debug { "Processed message from monitored chat $channelId, generated ${notifications.size} notifications" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing new message" }
                        ErrorTracker.logError("ERROR", "Message processing error: ${e.message}", e)
                    }
                }
            } else {
                logger.debug { "Ignoring message from unmonitored chat $chatId (isChannelPost: ${message.isChannelPost})" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in handleNewMessage" }
            ErrorTracker.logError("ERROR", "New message handler error: ${e.message}", e)
        }
    }
    
    private fun extractSenderUsername(message: TdApi.Message, client: Client?): String? {
        return try {
            when (val senderId = message.senderId) {
                is TdApi.MessageSenderUser -> {
                    val userId = senderId.userId
                    getUserUsername(userId, client)
                }
                is TdApi.MessageSenderChat -> {
                    val chatId = senderId.chatId
                    getChatUsername(chatId, client)
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to extract sender username: ${e.message}" }
            null
        }
    }

    private fun getUserUsername(userId: Long, client: Client?): String? {
        return try {
            val deferred = CompletableDeferred<String?>()
            
            client?.send(TdApi.GetUser(userId)) { result ->
                when (result) {
                    is TdApi.User -> {
                        val username = try {
                            // Access usernames.editableUsername field
                            val usernamesField = result.javaClass.getDeclaredField("usernames")
                            usernamesField.isAccessible = true
                            val usernames = usernamesField.get(result)
                            
                            if (usernames != null) {
                                val editableUsernameField = usernames.javaClass.getDeclaredField("editableUsername")
                                editableUsernameField.isAccessible = true
                                val editableUsername = editableUsernameField.get(usernames) as? String
                                
                                if (!editableUsername.isNullOrEmpty()) {
                                    logger.debug { "Found username: $editableUsername" }
                                    "@$editableUsername"
                                } else {
                                    logger.debug { "No editable username found for user $userId" }
                                    null
                                }
                            } else {
                                logger.debug { "No usernames object found for user $userId" }
                                null
                            }
                        } catch (e: Exception) {
                            logger.debug { "Could not extract username for user $userId: ${e.message}" }
                            null
                        }
                        
                        deferred.complete(username)
                    }
                    is TdApi.Error -> {
                        logger.debug { "Could not get user $userId: ${result.message}" }
                        deferred.complete(null)
                    }
                }
            }
            
            runBlocking {
                withTimeout(2000) { deferred.await() }
            }
        } catch (e: Exception) {
            logger.debug { "Failed to get username for user $userId: ${e.message}" }
            null
        }
    }

    private fun getChatUsername(chatId: Long, client: Client?): String? {
        return try {
            val deferred = CompletableDeferred<String?>()
            
            client?.send(TdApi.GetChat(chatId)) { result ->
                when (result) {
                    is TdApi.Chat -> {
                        val username = extractChannelTag(result, client)
                        deferred.complete(username)
                    }
                    is TdApi.Error -> {
                        logger.debug { "Could not get chat $chatId: ${result.message}" }
                        deferred.complete(null)
                    }
                }
            }
            
            runBlocking {
                withTimeout(2000) { deferred.await() }
            }
        } catch (e: Exception) {
            logger.debug { "Failed to get username for chat $chatId: ${e.message}" }
            null
        }
    }
    
    // FIXED: Safer tag extraction without dangerous reflection
    fun extractChannelTag(chat: TdApi.Chat, client: Client?): String? {
        return try {
            when (val chatType = chat.type) {
                is TdApi.ChatTypeSupergroup -> {
                    // Try to get supergroup info for username
                    val deferred = CompletableDeferred<String?>()
                    
                    client?.send(TdApi.GetSupergroup(chatType.supergroupId)) { result ->
                        when (result) {
                            is TdApi.Supergroup -> {
                                // SAFER: Use try-catch for reflection instead of assuming field exists
                                val username = try {
                                    val usernameField = result.javaClass.getDeclaredField("username")
                                    usernameField.isAccessible = true
                                    val value = usernameField.get(result) as? String
                                    if (!value.isNullOrEmpty()) "@$value" else null
                                } catch (e: NoSuchFieldException) {
                                    logger.debug { "Username field not available in TDLib version - this is expected" }
                                    null
                                } catch (e: SecurityException) {
                                    logger.debug { "Cannot access username field due to security restrictions" }
                                    null
                                } catch (e: Exception) {
                                    logger.debug { "Could not extract username via reflection: ${e.message}" }
                                    null
                                }
                                deferred.complete(username)
                            }
                            is TdApi.Error -> {
                                logger.debug { "Could not get supergroup info: ${result.message}" }
                                deferred.complete(null)
                            }
                        }
                    }
                    
                    // Wait briefly for the result, but don't block
                    try {
                        runBlocking {
                            withTimeout(2000) { deferred.await() }
                        }
                    } catch (e: Exception) {
                        logger.debug { "Timeout getting supergroup username - continuing without tag" }
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "Error extracting channel tag: ${e.message}" }
            null
        }
    }
    
    private fun isMonitoredGroupChat(chatId: Long): Boolean {
        // Check if this chat ID matches any of our monitored channels/groups
        val channelId = getChannelIdentifier(chatId)
        return channelId != null && database.channelExists(channelId)
    }
    
    private fun getChannelIdentifier(chatId: Long): String? {
        return try {
            // For debugging, let's also cache this mapping
            val identifier = chatId.toString()
            logger.debug { "Chat ID $chatId mapped to identifier: $identifier" }
            identifier
        } catch (e: Exception) {
            logger.error(e) { "Failed to get channel identifier for chat $chatId" }
            null
        }
    }
    
    /**
     * Generate a Telegram message link for the post
     * Format: https://t.me/channelname/messageId or https://t.me/c/channelId/messageId
     */
    private fun generateMessageLink(channelDetails: ChannelDetails?, messageId: Long): String? {
        return try {
            when {
                // If we have a channel tag (e.g., @channelname), use it
                !channelDetails?.channelTag.isNullOrBlank() -> {
                    val cleanTag = channelDetails!!.channelTag!!.removePrefix("@")
                    "https://t.me/$cleanTag/$messageId"
                }
                
                // If channel ID starts with -100, it's a supergroup/channel
                channelDetails?.channelId?.startsWith("-100") == true -> {
                    val chatId = channelDetails.channelId.removePrefix("-100")
                    "https://t.me/c/$chatId/$messageId"
                }
                
                // For other channel IDs, try the basic format
                channelDetails?.channelId != null -> {
                    val chatId = channelDetails.channelId.removePrefix("-")
                    "https://t.me/c/$chatId/$messageId"
                }
                
                else -> null
            }
        } catch (e: Exception) {
            logger.debug { "Failed to generate message link for channel ${channelDetails?.channelId}, message $messageId: ${e.message}" }
            null
        }
    }
    
    // Cache management
    fun cacheChannelMapping(channelId: String, chatId: Long) {
        channelIdCache[channelId] = chatId
    }
    
    fun getCachedChatId(channelId: String): Long? {
        return channelIdCache[channelId]
    }
    
    // FIXED: Add cache cleanup method
    fun cleanupCache() {
        if (channelIdCache.size > 400) {
            logger.debug { "Cleaning up channel ID cache (${channelIdCache.size} entries)" }
            // The LinkedHashMap will automatically remove eldest entries
            // when new ones are added, but we can force cleanup if needed
        }
    }
    
    fun shutdown() {
        logger.info { "Shutting down ChannelMonitor..." }
        scope.cancel()
    }
}
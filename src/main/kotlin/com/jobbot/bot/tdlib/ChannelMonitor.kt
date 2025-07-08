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
 * Handles TDLib message monitoring and processing with robust formatting support
 * Strategy: Extract rich formatting with conservative escaping and safety checks
 */
class ChannelMonitor(
    private val database: Database,
    private val messageProcessor: MessageProcessor,
    private var bot: TelegramBot?
) {
    private val logger = getLogger("ChannelMonitor")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Bounded cache with automatic cleanup
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
                
                // Extract both plain text and formatted text
                val (plainText, formattedText) = extractMessageContent(message.content)
                
                if (plainText.isBlank()) {
                    logger.debug { "Message has no text content, skipping" }
                    return
                }
                
                logger.debug { "Processing plain text: '$plainText'" }
                if (formattedText != plainText) {
                    logger.debug { "Generated formatted text with ${formattedText.length - plainText.length} additional markup characters" }
                }
                
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
                        
                        // Create ChannelMessage with both plain and formatted text
                        val channelMessage = ChannelMessage(
                            channelId = channelId,
                            channelName = displayName,
                            messageId = message.id,
                            text = plainText, // For keyword matching
                            formattedText = formattedText, // For user notifications
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
    
    /**
     * Extract both plain text and formatted text from message content
     * Returns Pair<plainText, formattedText>
     */
    private fun extractMessageContent(content: TdApi.MessageContent): Pair<String, String> {
        return when (content) {
            is TdApi.MessageText -> {
                val plainText = content.text.text
                val formattedText = convertFormattedTextToMarkdown(content.text)
                logger.debug { "Text message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            is TdApi.MessagePhoto -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Photo message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            is TdApi.MessageVideo -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Video message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            is TdApi.MessageDocument -> {
                val plainText = content.caption?.text ?: ""
                val formattedText = content.caption?.let { convertFormattedTextToMarkdown(it) } ?: ""
                logger.debug { "Document message - plain: ${plainText.length} chars, formatted: ${formattedText.length} chars" }
                Pair(plainText, formattedText)
            }
            else -> {
                logger.debug { "Unsupported message type: ${content.javaClass.simpleName}" }
                Pair("", "")
            }
        }
    }
    
    /**
     * CONSERVATIVE: Convert TDLib FormattedText to MarkdownV2 with safety-first approach
     * Only handles the most reliable formatting types, skips risky ones
     */
    private fun convertFormattedTextToMarkdown(formattedText: TdApi.FormattedText): String {
        if (formattedText.entities.isEmpty()) {
            return formattedText.text
        }
        
        try {
            val text = formattedText.text
            val entities = formattedText.entities.sortedBy { it.offset }
            val result = StringBuilder()
            var currentPos = 0
            
            for (entity in entities) {
                // Add text before this entity
                if (entity.offset > currentPos) {
                    result.append(safeEscape(text.substring(currentPos, entity.offset)))
                }
                
                // Get the entity text
                val entityText = text.substring(entity.offset, entity.offset + entity.length)
                
                // CONSERVATIVE: Only handle the safest formatting types
                when (entity.type) {
                    is TdApi.TextEntityTypeBold -> {
                        result.append("*${safeEscapeForFormatting(entityText)}*")
                    }
                    
                    is TdApi.TextEntityTypeItalic -> {
                        result.append("_${safeEscapeForFormatting(entityText)}_")
                    }
                    
                    is TdApi.TextEntityTypeCode -> {
                        // Code is usually safe, just wrap it
                        result.append("`${entityText}`")
                    }
                    
                    is TdApi.TextEntityTypePre -> {
                        // Pre-formatted code blocks
                        result.append("```\n${entityText}\n```")
                    }
                    
                    is TdApi.TextEntityTypeTextUrl -> {
                        // Only include URLs if they look safe
                        if (isSafeUrl(entity.type.url)) {
                            result.append("[${safeEscapeForFormatting(entityText)}](${entity.type.url})")
                        } else {
                            result.append("${safeEscape(entityText)} (${entity.type.url})")
                        }
                    }
                    
                    // CONSERVATIVE: Skip risky formatting types
                    is TdApi.TextEntityTypeUnderline,
                    is TdApi.TextEntityTypeStrikethrough,
                    is TdApi.TextEntityTypeSpoiler -> {
                        logger.debug { "Skipping risky formatting: ${entity.type.javaClass.simpleName}" }
                        result.append(safeEscape(entityText))
                    }
                    
                    // Keep safe entities as-is
                    is TdApi.TextEntityTypeMention,
                    is TdApi.TextEntityTypeHashtag,
                    is TdApi.TextEntityTypeCashtag -> {
                        result.append(entityText) // These are usually safe
                    }
                    
                    else -> {
                        logger.debug { "Unknown entity type: ${entity.type.javaClass.simpleName}" }
                        result.append(safeEscape(entityText))
                    }
                }
                
                currentPos = entity.offset + entity.length
            }
            
            // Add remaining text
            if (currentPos < text.length) {
                result.append(safeEscape(text.substring(currentPos)))
            }
            
            val finalResult = result.toString()
            
            // SAFETY CHECK: If result looks risky, return plain text
            if (looksRisky(finalResult)) {
                logger.debug { "Generated markdown looks risky, returning plain text" }
                return formattedText.text
            }
            
            return finalResult
            
        } catch (e: Exception) {
            logger.warn(e) { "Error converting formatted text, using plain text" }
            return formattedText.text
        }
    }
    
    /**
     * CONSERVATIVE escaping - only escape the most essential characters
     */
    private fun safeEscape(text: String): String {
        return text
            .replace("\\", "\\\\")  // Escape backslashes first
            .replace("*", "\\*")    // Bold markers
            .replace("_", "\\_")    // Italic markers  
            .replace("[", "\\[")    // Link brackets
            .replace("]", "\\]")
            .replace("(", "\\(")    // Link parentheses
            .replace(")", "\\)")
            .replace("`", "\\`")    // Code markers
    }
    
    /**
     * CONSERVATIVE escaping for text inside formatting (less aggressive)
     */
    private fun safeEscapeForFormatting(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
    }
    
    /**
     * Check if a URL looks safe to include in markdown
     */
    private fun isSafeUrl(url: String): Boolean {
        return try {
            url.startsWith("http://") || 
            url.startsWith("https://") ||
            url.startsWith("tg://")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Final safety check - does the generated markdown look risky?
     */
    private fun looksRisky(markdown: String): Boolean {
        return try {
            // Check for unbalanced brackets/parens
            val openBrackets = markdown.count { it == '[' }
            val closeBrackets = markdown.count { it == ']' }
            val openParens = markdown.count { it == '(' }
            val closeParens = markdown.count { it == ')' }
            
            // Check for excessive escaping
            val backslashes = markdown.count { it == '\\' }
            val totalChars = markdown.length
            
            // Simple heuristics for "risky" content
            openBrackets != closeBrackets ||
            openParens != closeParens ||
            backslashes > totalChars / 10 ||  // More than 10% backslashes
            markdown.contains("\\\\\\") ||    // Triple backslashes
            markdown.length > 4000            // Too long
            
        } catch (e: Exception) {
            true // If we can't check, assume it's risky
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
    
    // Safer tag extraction without dangerous reflection
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
     * Generate a Telegram message link with proper TDLib to Bot API message ID conversion
     */
    private fun generateMessageLink(channelDetails: ChannelDetails?, tdlibMessageId: Long): String? {
        return try {
            if (channelDetails == null) return null
            
            // Convert TDLib message ID to Bot API message ID
            val publicMessageId = convertTdlibToPublicMessageId(tdlibMessageId)
            if (publicMessageId == null) {
                logger.debug { "Could not convert TDLib message ID $tdlibMessageId to public ID - skipping link generation" }
                return null
            }
            
            logger.debug { "Converted TDLib ID $tdlibMessageId to public ID $publicMessageId" }
            
            when {
                // For public channels with @username
                !channelDetails.channelTag.isNullOrBlank() -> {
                    val cleanTag = channelDetails.channelTag.removePrefix("@")
                    if (cleanTag.isNotEmpty()) {
                        "https://t.me/$cleanTag/$publicMessageId"
                    } else null
                }
                
                // For supergroups with -100 prefix
                channelDetails.channelId.startsWith("-100") -> {
                    val chatId = channelDetails.channelId.substring(4) // Remove "-100"
                    "https://t.me/c/$chatId/$publicMessageId"
                }
                
                else -> null
            }
            
        } catch (e: Exception) {
            logger.debug { "Failed to generate message link: ${e.message}" }
            null
        }
    }
    
    /**
     * Convert TDLib message ID to Bot API/public message ID
     * TDLib message ID = Bot API message ID * 1048576 (2^20)
     * Bot API message ID = TDLib message ID / 1048576 (if divisible)
     */
    private fun convertTdlibToPublicMessageId(tdlibMessageId: Long): Long? {
        val SHIFT_FACTOR = 1048576L // 2^20
        
        return if (tdlibMessageId % SHIFT_FACTOR == 0L) {
            val publicMessageId = tdlibMessageId / SHIFT_FACTOR
            if (publicMessageId > 0) publicMessageId else null
        } else {
            null // Local-only message, no public ID
        }
    }
    
    // Cache management
    fun cacheChannelMapping(channelId: String, chatId: Long) {
        channelIdCache[channelId] = chatId
    }
    
    fun getCachedChatId(channelId: String): Long? {
        return channelIdCache[channelId]
    }
    
    // Add cache cleanup method
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
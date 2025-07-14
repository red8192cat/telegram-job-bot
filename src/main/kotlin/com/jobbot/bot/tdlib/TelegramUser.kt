package com.jobbot.bot.tdlib

import com.jobbot.bot.TelegramBot
import com.jobbot.core.MessageProcessor
import com.jobbot.data.Database
import com.jobbot.data.models.BotConfig
import com.jobbot.data.models.ChannelLookupResult
import com.jobbot.data.models.UserLookupResult
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import kotlinx.coroutines.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TelegramUser(
    private val config: BotConfig,
    private val database: Database,
    private val messageProcessor: MessageProcessor,
    private var bot: TelegramBot?
) {
    private val logger = getLogger("TelegramUser")
    private var client: Client? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Channel monitoring component
    private val channelMonitor = ChannelMonitor(database, messageProcessor, bot)
    
    fun updateBotReference(botInstance: TelegramBot) {
        this.bot = botInstance
        channelMonitor.updateBotReference(botInstance)
    }
    
    fun start() {
        if (config.apiId == null || config.apiHash == null || config.phoneNumber == null) {
            logger.warn { "TDLib configuration incomplete, user account monitoring disabled" }
            bot?.sendAdminNotification(Localization.getAdminMessage("system.limited.mode"))
            return
        }
        
        scope.launch {
            try {
                initializeClient()
            } catch (e: Exception) {
                logger.error(e) { "Failed to start TelegramUser" }
                ErrorTracker.logError("ERROR", "TelegramUser startup failed: ${e.message}", e)
                scheduleReconnection()
            }
        }
    }
    
    private suspend fun initializeClient(): Unit = withContext(Dispatchers.IO) {
        logger.info { "Initializing TDLib client..." }
        
        try {
            logger.info { "Java version: ${System.getProperty("java.version")}" }
            logger.info { "OS name: ${System.getProperty("os.name")}" }
            logger.info { "OS arch: ${System.getProperty("os.arch")}" }
            logger.info { "Java library path: ${System.getProperty("java.library.path")}" }
                        
            // Create TDLib client with update handler
            client = Client.create(
                { update ->
                    // Handle updates
                    when (update) {
                        is TdApi.UpdateNewMessage -> {
                            logger.debug { "Received UpdateNewMessage from chat ${update.message.chatId}" }
                            channelMonitor.handleNewMessage(update, client)
                        }
                        is TdApi.UpdateConnectionState -> {
                            logger.info { "Received UpdateConnectionState: ${update.state.javaClass.simpleName}" }
                            handleConnectionState(update)
                        }
                        is TdApi.UpdateAuthorizationState -> {
                            logger.info { "Received UpdateAuthorizationState: ${update.authorizationState.javaClass.simpleName}" }
                            handleAuthState(update)
                        }
                        else -> {
                            logger.debug { "Received update: ${update.javaClass.simpleName}" }
                        }
                    }
                },
                null, // Update exception handler
                null  // Default exception handler
            )
            
            logger.info { "TDLib client created successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize TDLib client" }
            throw e
        }
    }
    
    private fun handleAuthState(update: TdApi.UpdateAuthorizationState) {
        scope.launch {
            when (val authState = update.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    logger.info { "Setting TDLib parameters..." }
                    TdlibAuthManager.updateAuthState("WAITING_PARAMS")
                    
                    val request = TdApi.SetTdlibParameters()
                    request.databaseDirectory = "${config.databasePath}_tdlib"
                    request.useMessageDatabase = true
                    request.useSecretChats = true
                    request.apiId = config.apiId!!
                    request.apiHash = config.apiHash!!
                    request.systemLanguageCode = "en"
                    request.deviceModel = "Server"
                    request.applicationVersion = "1.0"
                    
                    // NEW: Storage configuration (let TDLib use default file handling)
                    request.useFileDatabase = true
                    // Note: Storage optimization will be handled manually via periodic cleanup
                    
                    logger.info { "TDLib storage configured: max ${config.tdlibMaxStorageGB}GB, TTL ${config.tdlibFileTtlDays} days" }
                    
                    client?.send(request, null)
                }
                
                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    logger.info { "Sending phone number for authentication..." }
                    TdlibAuthManager.updateAuthState("WAITING_PHONE")
                    
                    val phoneNumber = config.phoneNumber!!
                    client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null), null)
                }
                
                is TdApi.AuthorizationStateWaitCode -> {
                    logger.warn { "TDLib waiting for authentication code" }
                    TdlibAuthManager.updateAuthState("WAITING_CODE")
                    
                    // Add timeout for authentication
                    scope.launch {
                        delay(300_000) // 5 minutes timeout
                        if (TdlibAuthManager.getAuthState() == "WAITING_CODE") {
                            logger.error { "Authentication code timeout - no code provided in 5 minutes" }
                            bot?.sendAdminNotification(
                                "🔐 **Authentication Code Timeout**\n\n" +
                                "No authentication code was provided within 5 minutes.\n" +
                                "The authentication process has timed out.\n\n" +
                                "Please restart the bot and try authentication again."
                            )
                        }
                    }
                    
                    bot?.sendAdminNotification(Localization.getAdminMessage("tdlib.auth.code.needed"))
                }
                
                is TdApi.AuthorizationStateWaitPassword -> {
                    logger.warn { "TDLib waiting for 2FA password" }
                    TdlibAuthManager.updateAuthState("WAITING_PASSWORD")
                    
                    // Add timeout for password
                    scope.launch {
                        delay(300_000) // 5 minutes timeout
                        if (TdlibAuthManager.getAuthState() == "WAITING_PASSWORD") {
                            logger.error { "Authentication password timeout - no password provided in 5 minutes" }
                            bot?.sendAdminNotification(
                                "🔐 **Authentication Password Timeout**\n\n" +
                                "No 2FA password was provided within 5 minutes.\n" +
                                "The authentication process has timed out.\n\n" +
                                "Please restart the bot and try authentication again."
                            )
                        }
                    }
                    
                    bot?.sendAdminNotification(Localization.getAdminMessage("tdlib.auth.password.needed"))
                }
                
                is TdApi.AuthorizationStateReady -> {
                    logger.info { "TDLib authorization successful - ready to monitor channels and groups" }
                    TdlibAuthManager.updateAuthState("READY")
                    isConnected = true
                    
                    // Join existing channels
                    joinExistingChannels()
                    
                    // NEW: Start storage management
                    startStorageManagement()
                    
                    bot?.sendAdminNotification(Localization.getAdminMessage("tdlib.connected"))
                }
                
                is TdApi.AuthorizationStateLoggingOut -> {
                    logger.info { "TDLib logging out" }
                    TdlibAuthManager.updateAuthState("LOGGING_OUT")
                    isConnected = false
                }
                
                is TdApi.AuthorizationStateClosed -> {
                    logger.info { "TDLib closed" }
                    TdlibAuthManager.updateAuthState("CLOSED")
                    isConnected = false
                }
                
                else -> {
                    logger.debug { "Authorization state: ${authState.javaClass.simpleName}" }
                    TdlibAuthManager.updateAuthState("UNKNOWN")
                }
            }
        }
    }
    
    // NEW: Start TDLib storage management
    private fun startStorageManagement() {
        scope.launch {
            logger.info { "Starting TDLib storage management (cleanup every ${config.tdlibCleanupIntervalHours}h)" }
            
            // Initial storage check
            checkStorageStats()
            
            while (isActive && isConnected) {
                try {
                    // Wait for cleanup interval
                    delay(config.tdlibCleanupIntervalHours * 3600000L)
                    
                    if (!isConnected) break
                    
                    logger.info { "Running TDLib storage optimization..." }
                    
                    // Get storage stats before cleanup
                    checkStorageStats()
                    
                    // Run storage optimization
                    optimizeStorage()
                    
                } catch (e: CancellationException) {
                    logger.info { "Storage management stopped" }
                    break
                } catch (e: Exception) {
                    logger.warn(e) { "Error in storage management cycle" }
                    // Continue running even if one cycle fails
                    delay(3600000L) // Wait 1 hour before retry
                }
            }
            
            logger.info { "TDLib storage management stopped" }
        }
    }
    
    // NEW: Check TDLib storage statistics
    private suspend fun checkStorageStats() = withContext(Dispatchers.IO) {
        try {
            val statsDeferred = CompletableDeferred<TdApi.StorageStatistics?>()
            
            client?.send(TdApi.GetStorageStatistics(100)) { result ->
                when (result) {
                    is TdApi.StorageStatistics -> {
                        statsDeferred.complete(result)
                    }
                    is TdApi.Error -> {
                        logger.warn { "Failed to get storage stats: ${result.message}" }
                        statsDeferred.complete(null)
                    }
                }
            }
            
            val stats = withTimeout(10000) { statsDeferred.await() }
            
            if (stats != null) {
                val sizeMB = stats.size / 1024 / 1024
                val sizeGB = sizeMB / 1024.0
                val maxGB = config.tdlibMaxStorageGB
                
                logger.info { "TDLib storage: ${sizeMB}MB (${String.format("%.1f", sizeGB)}GB / ${maxGB}GB)" }
                
                if (sizeGB > maxGB * 0.8) { // 80% of limit
                    logger.warn { "TDLib storage is ${String.format("%.1f", (sizeGB / maxGB * 100))}% full!" }
                }
                
                // Notify admin if storage is getting full
                if (sizeGB > maxGB * 0.9) { // 90% of limit
                    bot?.sendAdminNotification(
                        "⚠️ **TDLib Storage Alert**\n\n" +
                        "Storage usage: ${String.format("%.1f", sizeGB)}GB / ${maxGB}GB (${String.format("%.1f", (sizeGB / maxGB * 100))}%)\n\n" +
                        "Consider increasing TDLIB_MAX_STORAGE_GB or reducing TDLIB_FILE_TTL_DAYS."
                    )
                }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Error checking storage stats" }
        }
    }
    
    // NEW: Optimize TDLib storage (cleanup old files) - using correct API
    private suspend fun optimizeStorage() = withContext(Dispatchers.IO) {
        try {
            val optimizeDeferred = CompletableDeferred<TdApi.StorageStatistics?>()
            
            // Use the correct TdApi.OptimizeStorage constructor
            client?.send(TdApi.OptimizeStorage(
                config.tdlibMaxStorageGB * 1024L * 1024L * 1024L,  // size: Max storage in bytes
                config.tdlibFileTtlDays * 24 * 3600,                // ttl: TTL in seconds  
                config.tdlibMaxFileCount,                           // count: Max file count
                3600,                                               // immunityDelay: Don't delete files used in last hour
                arrayOf(                                            // fileTypes: What to clean up
                    TdApi.FileTypePhoto(),
                    TdApi.FileTypeVideo(),
                    TdApi.FileTypeDocument(),
                    TdApi.FileTypeAudio(),
                    TdApi.FileTypeAnimation(),
                    TdApi.FileTypeVoiceNote()
                ),
                longArrayOf(),                                      // chatIds: Apply to all chats
                longArrayOf(),                                      // excludeChatIds: No exclusions  
                true,                                               // returnDeletedFileStatistics
                100                                                 // chatLimit
            )) { result ->
                when (result) {
                    is TdApi.StorageStatistics -> {
                        optimizeDeferred.complete(result)
                    }
                    is TdApi.Error -> {
                        logger.warn { "Storage optimization failed: ${result.message}" }
                        optimizeDeferred.complete(null)
                    }
                }
            }
            
            val deletedStats = withTimeout(60000) { optimizeDeferred.await() } // 60s timeout for cleanup
            
            if (deletedStats != null) {
                val deletedMB = deletedStats.size / 1024 / 1024
                logger.info { "TDLib storage optimization completed: freed ${deletedMB}MB" }
                
                // Notify admin of significant cleanup
                if (deletedMB > 100) { // More than 100MB cleaned
                    bot?.sendAdminNotification(
                        "🧹 **TDLib Storage Cleanup**\n\n" +
                        "Freed ${deletedMB}MB of storage space.\n" +
                        "Old files removed according to configured TTL (${config.tdlibFileTtlDays} days)."
                    )
                }
                
                // Check storage stats after cleanup
                delay(1000)
                checkStorageStats()
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Error during storage optimization" }
        }
    }
    
    private suspend fun joinExistingChannels() {
        val channels = database.getAllChannels()
        logger.debug { "Joining ${channels.size} existing channels/groups..." }
        
        for (channel in channels) {
            try {
                logger.debug { "Attempting to join channel/group: ${channel.channelId} (${channel.channelName})" }
                joinChannel(channel.channelId)
                delay(1000) // Rate limiting
            } catch (e: Exception) {
                logger.warn(e) { "Failed to join existing channel/group ${channel.channelId}" }
            }
        }
        
        logger.debug { "Finished joining existing channels/groups" }
    }
    
    private fun handleConnectionState(update: TdApi.UpdateConnectionState) {
        when (update.state) {
            is TdApi.ConnectionStateReady -> {
                logger.info { "TDLib connection ready - can now receive messages from channels and groups" }
                isConnected = true
            }
            is TdApi.ConnectionStateConnecting -> {
                logger.info { "TDLib connecting..." }
                isConnected = false
            }
            is TdApi.ConnectionStateConnectingToProxy -> {
                logger.info { "TDLib connecting to proxy..." }
                isConnected = false
            }
            is TdApi.ConnectionStateUpdating -> {
                logger.info { "TDLib updating..." }
            }
            is TdApi.ConnectionStateWaitingForNetwork -> {
                logger.warn { "TDLib waiting for network..." }
                isConnected = false
                scheduleReconnection()
            }
        }
    }
    
    fun submitAuthCode(code: String): String {
        return try {
            logger.info { "Auth code received: $code" }
            client?.send(TdApi.CheckAuthenticationCode(code), null)
            Localization.getAdminMessage("admin.auth.code.success")
        } catch (e: Exception) {
            logger.error(e) { "Error with auth code" }
            Localization.getAdminMessage("admin.auth.code.error", e.message ?: "Unknown error")
        }
    }
    
    fun submitAuthPassword(password: String): String {
        return try {
            logger.info { "Auth password received" }
            client?.send(TdApi.CheckAuthenticationPassword(password), null)
            Localization.getAdminMessage("admin.auth.password.success")
        } catch (e: Exception) {
            logger.error(e) { "Error with auth password" }
            Localization.getAdminMessage("admin.auth.code.error", e.message ?: "Unknown error")
        }
    }
    
    /**
     * NEW: Lookup user information by username using TDLib
     * Returns user ID and basic info for any public username
     */
    suspend fun lookupUserByUsername(username: String): UserLookupResult = withContext(Dispatchers.IO) {
        return@withContext try {
            logger.debug { "Looking up user info for: @$username" }

            val cleanUsername = when {
                username.startsWith("@") -> username.substring(1)
                else -> username
            }
            
            // Use CompletableDeferred for async response handling
            val resultDeferred = CompletableDeferred<UserLookupResult>()
            
            // Search for the user by username
            client?.send(TdApi.SearchPublicChat(cleanUsername)) { result ->
                when (result) {
                    is TdApi.Chat -> {
                        // Check if this is actually a user (not a channel/group)
                        when (val chatType = result.type) {
                            is TdApi.ChatTypePrivate -> {
                                // This is a private chat with a user - get the user ID
                                val userId = chatType.userId
                                
                                // Now get detailed user info
                                client?.send(TdApi.GetUser(userId)) { userResult ->
                                    when (userResult) {
                                        is TdApi.User -> {
                                            logger.info { "TDLib found user @$cleanUsername with ID: $userId" }
                                            
                                            // Extract username from user object
                                            val currentUsername = try {
                                                val usernamesField = userResult.javaClass.getDeclaredField("usernames")
                                                usernamesField.isAccessible = true
                                                val usernames = usernamesField.get(userResult)
                                                
                                                if (usernames != null) {
                                                    val editableUsernameField = usernames.javaClass.getDeclaredField("editableUsername")
                                                    editableUsernameField.isAccessible = true
                                                    editableUsernameField.get(usernames) as? String
                                                } else null
                                            } catch (e: Exception) {
                                                logger.debug { "Could not extract username from user object: ${e.message}" }
                                                cleanUsername // fallback to search term
                                            }
                                            
                                            resultDeferred.complete(
                                                UserLookupResult(
                                                    found = true,
                                                    userId = userId,
                                                    username = currentUsername ?: cleanUsername,
                                                    firstName = userResult.firstName,
                                                    lastName = userResult.lastName
                                                )
                                            )
                                        }
                                        is TdApi.Error -> {
                                            logger.warn { "Failed to get user details for @$cleanUsername: ${userResult.message}" }
                                            resultDeferred.complete(
                                                UserLookupResult(
                                                    found = false,
                                                    userId = null,
                                                    username = null,
                                                    firstName = null,
                                                    lastName = null,
                                                    error = "Failed to get user details: ${userResult.message}"
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                            
                            is TdApi.ChatTypeSupergroup -> {
                                logger.warn { "@$cleanUsername is a supergroup/channel, not a user" }
                                resultDeferred.complete(
                                    UserLookupResult(
                                        found = false,
                                        userId = null,
                                        username = null,
                                        firstName = null,
                                        lastName = null,
                                        error = "@$cleanUsername is a channel/group, not a user"
                                    )
                                )
                            }
                            
                            else -> {
                                logger.warn { "@$cleanUsername is not a user chat" }
                                resultDeferred.complete(
                                    UserLookupResult(
                                        found = false,
                                        userId = null,
                                        username = null,
                                        firstName = null,
                                        lastName = null,
                                        error = "@$cleanUsername is not a user"
                                    )
                                )
                            }
                        }
                    }
                    
                    is TdApi.Error -> {
                        logger.warn { "User search failed for @$cleanUsername: ${result.message}" }
                        resultDeferred.complete(
                            UserLookupResult(
                                found = false,
                                userId = null,
                                username = null,
                                firstName = null,
                                lastName = null,
                                error = result.message
                            )
                        )
                    }
                }
            }
            
            // Wait for result with timeout
            try {
                withTimeout(10000) { // 10 second timeout
                    resultDeferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "User lookup timeout for: @$cleanUsername" }
                UserLookupResult(
                    found = false,
                    userId = null,
                    username = null,
                    firstName = null,
                    lastName = null,
                    error = "Lookup timeout"
                )
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to lookup user info for @$username" }
            UserLookupResult(
                found = false,
                userId = null,
                username = null,
                firstName = null,
                lastName = null,
                error = e.message
            )
        }
    }
    
    /**
     * Lookup channel information by identifier (ID or @tag)
     * Returns actual ID, tag, and name
     */
    suspend fun lookupChannelInfo(identifier: String): ChannelLookupResult = withContext(Dispatchers.IO) {
        return@withContext try {
            logger.debug { "Looking up channel info for: $identifier" }

            val cleanIdentifier = when {
                identifier.startsWith("@") -> identifier.substring(1)
                identifier.startsWith("https://t.me/") -> identifier.substringAfter("https://t.me/")
                identifier.startsWith("t.me/") -> identifier.substringAfter("t.me/")
                else -> identifier
            }
            
            // Use CompletableDeferred for async response handling
            val resultDeferred = CompletableDeferred<ChannelLookupResult>()
            
            if (cleanIdentifier.matches(Regex("-?[0-9]{10,}"))) {
                // It's a chat ID - get chat info
                val chatId = cleanIdentifier.toLong()
                client?.send(TdApi.GetChat(chatId)) { result ->
                    when (result) {
                        is TdApi.Chat -> {
                            val tag = channelMonitor.extractChannelTag(result, client)
                            resultDeferred.complete(
                                ChannelLookupResult(
                                    found = true,
                                    channelId = chatId.toString(),
                                    channelTag = tag,
                                    channelName = result.title
                                )
                            )
                        }
                        is TdApi.Error -> {
                            logger.warn { "Chat lookup failed for ID $chatId: ${result.message}" }
                            resultDeferred.complete(
                                ChannelLookupResult(
                                    found = false,
                                    channelId = null,
                                    channelTag = null,
                                    channelName = null,
                                    error = result.message
                                )
                            )
                        }
                    }
                }
            } else {
                // It's a username - search for public chat
                client?.send(TdApi.SearchPublicChat(cleanIdentifier)) { result ->
                    when (result) {
                        is TdApi.Chat -> {
                            logger.info { "SearchPublicChat succeeded for $cleanIdentifier, completing result..." }
                            val tag = "@$cleanIdentifier"
                            resultDeferred.complete(
                                ChannelLookupResult(
                                    found = true,
                                    channelId = result.id.toString(),
                                    channelTag = tag,
                                    channelName = result.title
                                )
                            )
                        }
                        is TdApi.Error -> {
                            logger.warn { "Public chat search failed for @$cleanIdentifier: ${result.message}" }
                            resultDeferred.complete(
                                ChannelLookupResult(
                                    found = false,
                                    channelId = null,
                                    channelTag = null,
                                    channelName = null,
                                    error = result.message
                                )
                            )
                        }
                    }
                }
            }
            
            // Wait for result with timeout
            try {
                withTimeout(10000) { // 10 second timeout
                    resultDeferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Channel lookup timeout for: $identifier" }
                ChannelLookupResult(
                    found = false,
                    channelId = null,
                    channelTag = null,
                    channelName = null,
                    error = "Lookup timeout"
                )
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to lookup channel info for $identifier" }
            ChannelLookupResult(
                found = false,
                channelId = null,
                channelTag = null,
                channelName = null,
                error = e.message
            )
        }
    }
    
    /**
     * Enhanced join channel with proper ID and tag resolution
     */
    suspend fun joinChannelEnhanced(channelIdentifier: String): Pair<Boolean, ChannelLookupResult> = withContext(Dispatchers.IO) {
        return@withContext try {
            logger.info { "Enhanced joining channel: $channelIdentifier" }
            
            // First, lookup channel info
            val lookupResult = lookupChannelInfo(channelIdentifier)
            
            if (!lookupResult.found || lookupResult.channelId == null) {
                return@withContext Pair(false, lookupResult)
            }
            
            val chatId = lookupResult.channelId.toLong()
            
            // Use CompletableDeferred for join result
            val joinDeferred = CompletableDeferred<Boolean>()
            
            // Try to join the chat
            client?.send(TdApi.JoinChat(chatId)) { result ->
                when (result) {
                    is TdApi.Ok -> {
                        logger.debug { "Successfully joined chat: ${lookupResult.channelId}" }
                        joinDeferred.complete(true)
                    }
                    is TdApi.Error -> {
                        logger.warn { "Failed to join chat ${lookupResult.channelId}: ${result.message}" }
                        joinDeferred.complete(false)
                    }
                }
            }
            
            // Wait for join result
            val joinSuccess = try {
                withTimeout(10000) { // 10 second timeout
                    joinDeferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Join timeout for channel: ${lookupResult.channelId}" }
                false
            }
            
            // Cache the channel mapping
            if (joinSuccess) {
                channelMonitor.cacheChannelMapping(lookupResult.channelId, chatId)
            }
            
            Pair(joinSuccess, lookupResult)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to join channel enhanced $channelIdentifier" }
            val errorResult = ChannelLookupResult(
                found = false,
                channelId = null,
                channelTag = null,
                channelName = null,
                error = e.message
            )
            Pair(false, errorResult)
        }
    }
        
    suspend fun joinChannel(channelIdentifier: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        return@withContext try {
            logger.debug { "Attempting to join channel/group: $channelIdentifier" }
            
            if (channelIdentifier.startsWith("-")) {
                // Chat ID - try to join directly
                val chatId = channelIdentifier.toLong()
                client?.send(TdApi.JoinChat(chatId), null)
                channelMonitor.cacheChannelMapping(channelIdentifier, chatId)
                logger.info { "Join request sent for chat ID: $chatId" }
                Pair(true, channelIdentifier)
            } else {
                // Username - search first, then join
                logger.debug { "Searching for public chat: $channelIdentifier" }
                
                try {
                    client?.send(TdApi.SearchPublicChat(channelIdentifier), null)
                    delay(1000) // Give search time to complete
                    logger.debug { "Search request sent for: $channelIdentifier" }
                } catch (e: Exception) {
                    logger.warn { "Search failed for $channelIdentifier: ${e.message}" }
                }
                
                Pair(true, channelIdentifier)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to join channel/group $channelIdentifier" }
            ErrorTracker.logError("ERROR", "Failed to join channel/group: ${e.message}", e)
            Pair(false, null)
        }
    }
    
    suspend fun leaveChannel(channelIdentifier: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val chatId = channelMonitor.getCachedChatId(channelIdentifier) ?: run {
                if (channelIdentifier.startsWith("-")) {
                    channelIdentifier.toLongOrNull()
                } else {
                    null
                }
            }
            
            if (chatId != null) {
                client?.send(TdApi.LeaveChat(chatId), null)
                logger.debug { "Leave request sent for channel/group: $channelIdentifier" }
                true
            } else {
                logger.warn { "Channel/group not found for leaving: $channelIdentifier" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to leave channel/group $channelIdentifier" }
            ErrorTracker.logError("ERROR", "Failed to leave channel/group: ${e.message}", e)
            false
        }
    }
    
    suspend fun checkChannelAccess(chatId: Long): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            logger.debug { "Checking access for channel: $chatId" }
            
            val accessDeferred = CompletableDeferred<Boolean>()
            
            // Try to get chat info first
            client?.send(TdApi.GetChat(chatId)) { result ->
                when (result) {
                    is TdApi.Chat -> {
                        // Chat exists and we can access basic info
                        // For channels, if we can get the chat info, we likely have access
                        logger.debug { "Successfully accessed chat $chatId" }
                        accessDeferred.complete(true)
                    }
                    is TdApi.Error -> {
                        logger.debug { "Cannot access chat $chatId: ${result.message}" }
                        accessDeferred.complete(false)
                    }
                }
            }
            
            // Wait for result with timeout
            try {
                withTimeout(10000) { // 10 second timeout
                    accessDeferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Access check timeout for channel: $chatId" }
                false
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to check access for channel $chatId" }
            false
        }
    }
    
    private fun scheduleReconnection() {
        scope.launch {
            logger.info { "Scheduling reconnection in 30 seconds..." }
            delay(30000)
            
            if (!isConnected) {
                logger.info { "Attempting to reconnect..." }
                try {
                    client = null
                    delay(5000)
                    initializeClient()
                } catch (e: Exception) {
                    logger.error(e) { "Reconnection failed" }
                    ErrorTracker.logError("ERROR", "Reconnection failed: ${e.message}", e)
                    scheduleReconnection()
                }
            }
        }
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun shutdown() {
        logger.info { "Shutting down TelegramUser..." }
        channelMonitor.shutdown()
        scope.cancel()
    }
    
    // FIXED: Add cache cleanup method
    fun cleanupCache() {
        channelMonitor.cleanupCache()
    }
}
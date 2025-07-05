package com.jobbot

import com.jobbot.bot.TelegramBot
import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.bot.tdlib.TdlibLogManager
import com.jobbot.core.MessageProcessor
import com.jobbot.core.NotificationService
import com.jobbot.data.Database
import com.jobbot.infrastructure.config.Config
import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.infrastructure.monitoring.HealthCheckServer
import com.jobbot.infrastructure.monitoring.SystemMonitor
import com.jobbot.shared.AdminNotificationManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import kotlin.system.exitProcess

fun main() {
    val logger = getLogger("Application")
    
    logger.info { "Starting Telegram Job Bot..." }
    
    // Initialize application
    val app = Application()
    
    try {
        app.start()
    } catch (e: Exception) {
        logger.error(e) { "Failed to start application" }
        ErrorTracker.logError("FATAL", "Application startup failed: ${e.message}", e)
        exitProcess(1)
    }
}

class Application {
    private val logger = getLogger("Application")
    
    // Core components
    private lateinit var database: Database
    private lateinit var messageProcessor: MessageProcessor
    private lateinit var notificationService: NotificationService
    private lateinit var telegramBotsApplication: TelegramBotsLongPollingApplication
    private lateinit var healthCheckServer: HealthCheckServer
    
    // Bot components
    private var bot: TelegramBot? = null
    private var telegramUser: TelegramUser? = null
    
    fun start() {
        logger.info { "Initializing application components..." }
        
        // 1. Load and validate configuration
        logger.info { "Loading and validating configuration..." }
        Config.validateConfig()
        val config = Config.config
        
        // 2. Initialize database with connection pooling
        logger.info { "Initializing database with HikariCP connection pooling..." }
        database = Database(config.databasePath)
        
        // Log database initialization success with pool stats
        val poolStats = database.getPoolStats()
        logger.info { "Database initialized successfully with connection pool (${poolStats["maximumPoolSize"]} max connections)" }
        
        // 3. Initialize core services
        logger.info { "Initializing core services..." }
        messageProcessor = MessageProcessor(database)
        notificationService = NotificationService()
        
        // 4. Initialize Telegram Bot API for v9.x
        logger.info { "Initializing Telegram Bot API..." }
        telegramBotsApplication = TelegramBotsLongPollingApplication()
        
        // 5. Initialize TelegramUser if TDLib config is available
        if (Config.hasTdLibConfig()) {
            initializeTdlibMode(config)
        } else {
            initializeBotOnlyMode(config)
        }
        
        // 6. Wire up notification services
        setupNotificationServices()
        
        // 7. Set up bot commands menu
        setupBotCommands()
        
        // 8. Initialize health check server
        healthCheckServer = HealthCheckServer(database, telegramUser)
        
        // 9. Set up shutdown hook
        setupShutdownHook()
        
        // 10. Send startup notification
        sendStartupNotification(config)
        
        // 11. Start health check server and keep application running
        startHealthCheckAndKeepAlive()
    }
    
    private fun initializeTdlibMode(config: com.jobbot.data.models.BotConfig) {
        logger.info { "Initializing TelegramUser with TDLib..." }
        
        // Initialize TDLib log manager
        TdlibLogManager.initialize(config.tdlibLogLevel)
        
        // Create TelegramUser with null bot reference initially
        telegramUser = TelegramUser(config, database, messageProcessor, null)
        
        // Create bot instance with TelegramUser
        bot = TelegramBot(config, database, telegramUser)
        
        // Update TelegramUser with bot reference
        telegramUser!!.updateBotReference(bot!!)
        
        // Register bot with v9.x API
        telegramBotsApplication.registerBot(config.botToken, bot!!)
        
        // Start TelegramUser
        telegramUser!!.start()
        
        logger.info { "TDLib mode initialized successfully" }
    }
    
    private fun initializeBotOnlyMode(config: com.jobbot.data.models.BotConfig) {
        logger.warn { "TDLib configuration not found. Running in bot-only mode." }
        
        // Create bot instance without TelegramUser
        bot = TelegramBot(config, database, null)
        
        // Register bot with v9.x API
        telegramBotsApplication.registerBot(config.botToken, bot!!)
        
        logger.info { "Bot-only mode initialized successfully" }
    }
    
    private fun setupNotificationServices() {
        logger.info { "Setting up notification services..." }
        
        // Wire up notification services with bot instance
        notificationService.setBotInstance(bot!!)
        AdminNotificationManager.setBotInstance(bot!!)
        
        logger.info { "Notification services configured successfully" }
        logger.debug { "NotificationService status: ${notificationService.getStatus()}" }
        logger.debug { "AdminNotificationManager status: ${AdminNotificationManager.getStatus()}" }
    }
    
    private fun setupBotCommands() {
        logger.info { "Setting up bot commands menu..." }
        
        try {
            val commands = listOf(
                BotCommand("start", "Start bot and show main menu"),
                BotCommand("keywords", "View and manage job keywords"),
                BotCommand("language", "Change interface language"),
                BotCommand("help", "Show help and syntax guide")
            )
            
            val setCommands = SetMyCommands.builder()
                .commands(commands)
                .build()
            
            // Use the new v9.x execute method
            bot!!.execute(setCommands)
            logger.info { "Bot commands menu set successfully: ${commands.size} commands" }
            
            // Log the commands for verification
            commands.forEach { command ->
                logger.debug { "Command: /${command.command} - ${command.description}" }
            }
            
        } catch (e: Exception) {
            logger.warn(e) { "Failed to set bot commands menu: ${e.message}" }
            ErrorTracker.logError("WARN", "Bot commands setup failed: ${e.message}", e)
        }
    }
    
    private fun setupShutdownHook() {
        logger.info { "Setting up shutdown hook..." }
        
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutdown signal received, cleaning up..." }
            shutdown()
        })
        
        logger.info { "Shutdown hook configured successfully" }
    }
    
    private fun sendStartupNotification(config: com.jobbot.data.models.BotConfig) {
        logger.info { "Sending startup notification..." }
        
        try {
            val tdlibStatus = if (telegramUser != null) {
                Localization.getAdminMessage("admin.status.connected")
            } else {
                Localization.getAdminMessage("admin.status.disabled")
            }
            
            val currentPoolStats = database.getPoolStats()
            val startupMessage = Localization.getAdminMessage(
                "admin.startup.message",
                tdlibStatus,
                config.authorizedAdminId.toString(),
                currentPoolStats["maximumPoolSize"] ?: 0,
                currentPoolStats["activeConnections"] ?: 0,
                config.rateLimitMessagesPerMinute,
                config.rateLimitBurstSize
            )
            
            // Send startup notification
            if (telegramUser != null) {
                bot!!.sendAdminNotification(startupMessage)
            } else {
                // Send limited mode notification
                bot!!.sendAdminNotification(Localization.getAdminMessage("system.limited.mode"))
            }
            
            logger.info { "Startup notification sent successfully" }
            
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send startup notification: ${e.message}" }
            ErrorTracker.logError("WARN", "Startup notification failed: ${e.message}", e)
        }
    }
    
    private fun startHealthCheckAndKeepAlive() {
        val config = Config.config
        
        logger.info { "Bot started successfully!" }
        logger.info { "Admin ID: ${config.authorizedAdminId}" }
        logger.info { "Database: ${config.databasePath} (HikariCP pooling enabled)" }
        
        val currentPoolStats = database.getPoolStats()
        logger.info { "Connection pool: ${currentPoolStats["maximumPoolSize"]} max, ${currentPoolStats["minimumIdle"]} min idle" }
        logger.info { "TDLib enabled: ${telegramUser != null}" }
        logger.info { "Rate limits: ${config.rateLimitMessagesPerMinute} msg/min, ${config.rateLimitBurstSize} burst" }
        logger.info { "Bot is running..." }
        
        // Start health check server and keep application running
        runBlocking {
            try {
                healthCheckServer.start()
            } catch (e: Exception) {
                logger.error(e) { "Health check server failed" }
                ErrorTracker.logError("ERROR", "Health check server failed: ${e.message}", e)
                throw e
            }
        }
    }
    
    private fun shutdown() {
        logger.info { "Starting application shutdown..." }
        
        try {
            // 1. Close database connection pool FIRST (most critical)
            logger.info { "Closing database connection pool..." }
            database.close()
            logger.info { "Database connection pool closed successfully" }
            
            // 2. Close bot components
            logger.info { "Shutting down bot components..." }
            bot?.shutdown()
            logger.info { "Bot shutdown complete" }
            
            // 3. Close TelegramUser
            logger.info { "Shutting down TelegramUser..." }
            telegramUser?.shutdown()
            logger.info { "TelegramUser shutdown complete" }
            
            // 4. Close notification services
            logger.info { "Shutting down notification services..." }
            // NotificationService doesn't need explicit shutdown (it just holds bot reference)
            logger.info { "Notification services shutdown complete" }
            
            // 5. Close Telegram Bots Application
            logger.info { "Shutting down Telegram Bots Application..." }
            telegramBotsApplication.close()
            logger.info { "Telegram Bots Application closed" }
            
            logger.info { "All components shutdown successfully" }
            
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown" }
            ErrorTracker.logError("ERROR", "Shutdown error: ${e.message}", e)
        }
    }
}

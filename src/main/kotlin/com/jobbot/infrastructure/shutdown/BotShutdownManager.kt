import com.jobbot.infrastructure.monitoring.ErrorTracker
import com.jobbot.shared.AdminNotificationManager
import com.jobbot.shared.getLogger
import com.jobbot.shared.localization.Localization
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Bot shutdown management utility
object BotShutdownManager {
    private val logger = getLogger("BotShutdownManager")
    private var isShuttingDown = false
    private var shutdownReason: String? = null
    private var shutdownTime: LocalDateTime? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var reminderJob: Job? = null
    
    fun initiateShutdown(reason: String = "Emergency shutdown by admin") {
        logger.warn { "Bot shutdown initiated: $reason" }
        isShuttingDown = true
        shutdownReason = reason
        shutdownTime = LocalDateTime.now()
        
        // Log the shutdown for monitoring
        ErrorTracker.logError("WARN", "Bot shutdown initiated: $reason", null)
        
        // Start 30-minute reminder job
        startShutdownReminders()
    }
    
    fun cancelShutdown() {
        logger.info { "Bot shutdown cancelled" }
        isShuttingDown = false
        shutdownReason = null
        shutdownTime = null
        
        // Cancel reminder job
        reminderJob?.cancel()
        reminderJob = null
    }
    
    private fun startShutdownReminders() {
        reminderJob?.cancel()
        reminderJob = scope.launch {
            delay(30 * 60 * 1000) // 30 minutes
        
            while (isShuttingDown) {
                try {
                    if (AdminNotificationManager.isReady()) {
                        AdminNotificationManager.sendShutdownReminder()
                    } else {
                        logger.warn { "AdminNotificationManager not ready - cannot send shutdown reminder" }
                    }
                
                    delay(30 * 60 * 1000) // Next reminder in 30 minutes
                } catch (e: Exception) {
                    logger.error(e) { "Error sending shutdown reminder" }
                    delay(60 * 1000) // Retry in 1 minute on error
                }
            }
        }
    }    

    fun isShutdownMode(): Boolean = isShuttingDown
    
    fun getShutdownStatus(): Map<String, Any?> {
        return mapOf(
            "isShuttingDown" to isShuttingDown,
            "reason" to shutdownReason,
            "shutdownTime" to shutdownTime?.toString(),
            "duration" to shutdownTime?.let { 
                TextUtils.formatUptime(it)
            }
        )
    }
    
    fun getShutdownMessage(language: String): String {
        return if (isShuttingDown) {
            val timeText = shutdownTime?.let { 
                " (${it.format(DateTimeFormatter.ofPattern("HH:mm"))})"
            } ?: ""
            
            when (language) {
                "ru" -> "🛠️ Бот временно недоступен для обслуживания$timeText. Попробуйте позже."
                else -> "🛠️ Bot is temporarily unavailable for maintenance$timeText. Please try again later."
            }
        } else {
            Localization.getMessage(language, "error.rate_limit")
        }
    }
}

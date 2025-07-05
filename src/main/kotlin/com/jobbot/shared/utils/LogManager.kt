import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import io.github.oshai.kotlinlogging.KotlinLogging

// Log level management utility
object LogManager {
    private val logger = KotlinLogging.logger("LogManager")
    
    fun setLogLevel(level: String): Boolean {
        return try {
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            
            val newLevel = when (level.uppercase()) {
                "DEBUG" -> Level.DEBUG
                "INFO" -> Level.INFO  
                "WARN" -> Level.WARN
                "ERROR" -> Level.ERROR
                else -> return false
            }
            
            rootLogger.level = newLevel
            logger.info { "Log level changed to: $level" }
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to change log level" }
            false
        }
    }
    
    fun getCurrentLogLevel(): String {
        return try {
            val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            rootLogger.level?.toString() ?: "INFO"
        } catch (e: Exception) {
            logger.error(e) { "Failed to get current log level" }
            "INFO"
        }
    }
}

import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.ValidationUtils
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/**
 * Manages TDLib log level settings
 */
object TdlibLogManager {
    private val logger = getLogger("TdlibLogManager")
    
    // In-memory storage of current TDLib log level
    private var currentLogLevel: String = "ERROR"
    
    // TDLib log level mapping
    private val tdlibLevelMap = mapOf(
        "FATAL" to 0,
        "ERROR" to 1,
        "WARNING" to 2,
        "INFO" to 3,
        "DEBUG" to 4,
        "VERBOSE" to 5
    )
    
    fun initialize(configLogLevel: String) {
        if (ValidationUtils.isValidTdlibLogLevel(configLogLevel)) {
            currentLogLevel = configLogLevel.uppercase()
            logger.info { "TDLib log level initialized to: $currentLogLevel" }
            
            // Apply the log level to TDLib
            applyLogLevel()
        } else {
            logger.warn { "Invalid TDLib log level from config: $configLogLevel, using ERROR" }
            currentLogLevel = "ERROR"
            applyLogLevel()
        }
    }
    
    fun setLogLevel(level: String): Boolean {
        val upperLevel = level.uppercase()
        
        return if (ValidationUtils.isValidTdlibLogLevel(upperLevel)) {
            logger.info { "Changing TDLib log level from $currentLogLevel to $upperLevel" }
            currentLogLevel = upperLevel
            applyLogLevel()
            true
        } else {
            logger.warn { "Invalid TDLib log level: $level" }
            false
        }
    }
    
    fun getCurrentLogLevel(): String = currentLogLevel
    
    private fun applyLogLevel() {
        try {
            val verbosityLevel = tdlibLevelMap[currentLogLevel] ?: 1
            
            // Apply TDLib log level
            Client.execute(TdApi.SetLogVerbosityLevel(verbosityLevel))
            logger.info { "TDLib log verbosity set to $verbosityLevel ($currentLogLevel)" }
            
        } catch (e: Exception) {
            logger.warn(e) { "Failed to apply TDLib log level: ${e.message}" }
        }
    }
    
    fun getAvailableLevels(): List<String> {
        return listOf("FATAL", "ERROR", "WARNING", "INFO", "DEBUG", "VERBOSE")
    }
    
    fun getLevelDescription(level: String): String {
        return when (level.uppercase()) {
            "FATAL" -> "Only fatal errors"
            "ERROR" -> "Errors only"
            "WARNING" -> "Warnings + errors"
            "INFO" -> "Info + warnings + errors"
            "DEBUG" -> "Debug + all above"
            "VERBOSE" -> "Everything"
            else -> "Unknown level"
        }
    }
}

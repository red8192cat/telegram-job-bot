package com.jobbot.infrastructure.monitoring

import com.jobbot.data.models.ErrorLog
import com.jobbot.shared.getLogger
import java.time.LocalDateTime

// Error tracking utility
object ErrorTracker {
    private val logger = getLogger("ErrorTracker")
    private val recentErrors = mutableListOf<ErrorLog>()
    private val maxErrors = 100
    
    fun logError(level: String, message: String, exception: Throwable? = null) {
        val error = ErrorLog(
            timestamp = LocalDateTime.now(),
            level = level,
            message = message,
            exception = exception?.toString()
        )
        
        synchronized(recentErrors) {
            recentErrors.add(error)
            if (recentErrors.size > maxErrors) {
                recentErrors.removeAt(0)
            }
        }
        
        when (level) {
            "ERROR" -> if (exception != null) logger.error(exception) { message } else logger.error { message }
            "WARN" -> if (exception != null) logger.warn(exception) { message } else logger.warn { message }
            else -> if (exception != null) logger.info(exception) { message } else logger.info { message }
        }
    }
    
    fun getRecentErrors(limit: Int = 10): List<ErrorLog> {
        synchronized(recentErrors) {
            return recentErrors.takeLast(limit).reversed()
        }
    }
    
    fun getErrorCount(): Int {
        synchronized(recentErrors) {
            return recentErrors.size
        }
    }
}

package com.jobbot.infrastructure.monitoring

import com.jobbot.data.models.SystemHealth
import com.jobbot.shared.utils.TextUtils
import java.time.LocalDateTime

// System monitoring utility
object SystemMonitor {
    private val startTime = LocalDateTime.now()
    private var messagesProcessed = 0L
    
    fun getStartTime(): LocalDateTime = startTime

    fun incrementMessageCount() {
        messagesProcessed++
    }
    
    fun getSystemHealth(activeUsers: Int, monitoredChannels: Int): SystemHealth {
        val recentErrors = ErrorTracker.getRecentErrors(1)
        
        return SystemHealth(
            uptime = TextUtils.formatUptime(startTime),
            activeUsers = activeUsers,
            monitoredChannels = monitoredChannels,
            messagesProcessed = messagesProcessed,
            lastError = recentErrors.firstOrNull()?.message,
            errorCount = ErrorTracker.getErrorCount()
        )
    }
}

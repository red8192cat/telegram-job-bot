package com.jobbot.infrastructure.services

import com.jobbot.data.Database
import com.jobbot.shared.getLogger
import kotlinx.coroutines.*

class PremiumExpirationService(
    private val database: Database
) {
    private val logger = getLogger("PremiumExpirationService")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    
    fun start() {
        job = scope.launch {
            logger.info { "Premium expiration service started (runs every 6 hours)" }
            
            while (isActive) {
                try {
                    // Wait 6 hours
                    delay(6 * 60 * 60 * 1000L) // 6 hours = 21,600,000 ms
                    
                    // Simple expiration check
                    val expiredCount = database.expireOldPremiumUsers()
                    
                    if (expiredCount > 0) {
                        logger.info { "Expired premium for $expiredCount users" }
                    } else {
                        logger.debug { "No expired premium users found" }
                    }
                    
                } catch (e: CancellationException) {
                    logger.info { "Premium expiration service stopped" }
                    break
                } catch (e: Exception) {
                    logger.error(e) { "Premium expiration check failed" }
                    // Continue running - don't let one failure stop the service
                }
            }
        }
    }
    
    fun stop() {
        logger.info { "Stopping premium expiration service..." }
        job?.cancel()
        scope.cancel()
    }
}

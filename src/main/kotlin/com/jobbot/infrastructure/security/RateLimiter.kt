import com.jobbot.data.models.RateLimitState
import com.jobbot.shared.AdminNotificationManager
import com.jobbot.shared.getLogger
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

// Enhanced rate limiting utility with dynamic configuration and race condition fix
class RateLimiter(
    private var maxTokens: Int,
    private var refillRatePerMinute: Int
) {
    private val logger = getLogger("RateLimiter")
    private val userStates = ConcurrentHashMap<Long, RateLimitState>()
    private val overloadedUsers = ConcurrentHashMap<Long, Boolean>()
    
    // Convert rate per minute to tokens per millisecond for more precise calculation
    private var tokensPerMs = refillRatePerMinute.toDouble() / 60_000.0
    
    // Dynamic rate limit updating
    fun updateRateLimits(newMaxTokens: Int, newRefillRatePerMinute: Int): Boolean {
        return if (newMaxTokens in 10..200 && newRefillRatePerMinute in 10..200) {
            logger.info { "Updating rate limits: $maxTokens->$newMaxTokens tokens, $refillRatePerMinute->$newRefillRatePerMinute per minute" }
            
            maxTokens = newMaxTokens
            refillRatePerMinute = newRefillRatePerMinute
            tokensPerMs = newRefillRatePerMinute.toDouble() / 60_000.0
            
            // Reset all user states to apply new limits immediately
            userStates.clear()
            overloadedUsers.clear()
            
            logger.info { "Rate limits updated successfully - all users reset" }
            true
        } else {
            logger.warn { "Invalid rate limit values: tokens=$newMaxTokens (10-200), rate=$newRefillRatePerMinute (10-200)" }
            false
        }
    }
    
    // ✅ FIXED: Thread-safe rate limiting with atomic compare-and-swap
    fun isAllowed(userId: Long): Boolean {
        val now = System.currentTimeMillis()
    
        while (true) {
            // Read current state
            val currentState = userStates.getOrPut(userId) {
                RateLimitState(maxTokens, now)
            }
        
            // Calculate new state based on current state
            val timePassed = now - currentState.lastRefill
            val tokensToAdd = (timePassed * tokensPerMs).toInt()
            val availableTokens = minOf(maxTokens, currentState.tokens + tokensToAdd)
        
            // Prepare new state
            val newState = if (availableTokens > 0) {
                RateLimitState(availableTokens - 1, now, false)
            } else {
                RateLimitState(0, now, true)
            }
        
            // Atomic update: only succeeds if no other thread modified the state
            if (userStates.replace(userId, currentState, newState)) {
                // Successfully updated state - handle notifications
                if (availableTokens > 0) {
                    // User was allowed - remove from overloaded if present
                    if (overloadedUsers.remove(userId) == true) {
                        logger.info { "Rate limit restored for user $userId" }
                        notifyRateRestored(userId)
                    }
                    
                    logger.debug { "Rate limit check passed for user $userId (tokens remaining: ${newState.tokens})" }
                    return true
                } else {
                    // User was rate limited - add to overloaded if first time
                    if (overloadedUsers.put(userId, true) == null) {
                        logger.warn { "Rate limit exceeded for user $userId" }
                        
                        // Send admin notification about rate limit hit
                        if (AdminNotificationManager.isReady()) {
                            AdminNotificationManager.notifyRateLimitHit(userId)
                        } else {
                            logger.warn { "AdminNotificationManager not ready - cannot send rate limit alert" }
                        }
                    }
                    
                    return false
                }
            }
            // If replace failed, another thread modified the state - retry with fresh data
        }
    }
    
    fun getUserTokens(userId: Long): Int {
        val now = System.currentTimeMillis()
        val currentState = userStates[userId] ?: return maxTokens
        
        val timePassed = now - currentState.lastRefill
        val tokensToAdd = (timePassed * tokensPerMs).toInt()
        
        return minOf(maxTokens, currentState.tokens + tokensToAdd)
    }
    
    fun isUserLimited(userId: Long): Boolean {
        return getUserTokens(userId) <= 0
    }
    
    private fun notifyRateRestored(userId: Long) {
        // This should be connected to the bot instance for notifications
        // For now, just log - this can be enhanced later
        logger.info { "Rate limit restored for user $userId" }
    }
    
    fun getOverloadedUsers(): Set<Long> = overloadedUsers.keys.toSet()
    
    fun getOverloadedUsersWithTokens(): Map<Long, Int> {
        return overloadedUsers.keys.associateWith { getUserTokens(it) }
    }
    
    fun getRateLimitStatus(): Map<String, Any> {
        return mapOf(
            "maxTokens" to maxTokens,
            "refillRatePerMinute" to refillRatePerMinute,
            "activeUsers" to userStates.size,
            "overloadedUsers" to overloadedUsers.size
        )
    }
    
    // Admin function to clear rate limits for a user
    fun clearUserLimit(userId: Long) {
        userStates.remove(userId)
        overloadedUsers.remove(userId)
        logger.info { "Rate limit cleared for user $userId by admin" }
    }
    
    // Admin function to clear all rate limits
    fun clearAllLimits() {
        val userCount = userStates.size
        userStates.clear()
        overloadedUsers.clear()
        logger.info { "All rate limits cleared by admin ($userCount users affected)" }
    }
}

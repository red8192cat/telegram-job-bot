package com.jobbot.infrastructure.monitoring

import com.jobbot.bot.tdlib.TelegramUser
import com.jobbot.data.Database
import com.jobbot.shared.getLogger
import com.jobbot.shared.utils.TextUtils
import kotlinx.coroutines.delay
import java.time.LocalDateTime

class HealthCheckServer(
    private val database: Database,
    private val telegramUser: TelegramUser?
) {
    private val logger = getLogger("HealthCheckServer")
    
    suspend fun start() {
        try {
            // Simple HTTP server for Docker health checks with database monitoring
            val server = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress(8080), 0
            )
            
            server.createContext("/health") { exchange ->
                try {
                    // Check both database AND TDLib connectivity
                    val isDatabaseHealthy = database.isHealthy()
                    val isTdlibHealthy = telegramUser?.isConnected() ?: true // If no TDLib, consider healthy
                    val poolStats = database.getPoolStats()
                    
                    val isOverallHealthy = isDatabaseHealthy && isTdlibHealthy
                    
                    val response = """
                        {
                            "status": "${if (isOverallHealthy) "healthy" else "unhealthy"}",
                            "timestamp": "${LocalDateTime.now()}",
                            "uptime": "${TextUtils.formatUptime(SystemMonitor.getStartTime())}",
                            "components": {
                                "database": {
                                    "status": "${if (isDatabaseHealthy) "healthy" else "unhealthy"}",
                                    "activeConnections": ${poolStats["activeConnections"]},
                                    "totalConnections": ${poolStats["totalConnections"]},
                                    "maximumPoolSize": ${poolStats["maximumPoolSize"]}
                                },
                                "tdlib": {
                                    "status": "${if (isTdlibHealthy) "healthy" else "unhealthy"}",
                                    "connected": $isTdlibHealthy,
                                    "enabled": ${telegramUser != null}
                                }
                            }
                        }
                    """.trimIndent()
                    
                    val statusCode = if (isOverallHealthy) 200 else 503
                    
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(statusCode, response.toByteArray().size.toLong())
                    exchange.responseBody.write(response.toByteArray())
                    exchange.responseBody.close()
                    
                } catch (e: Exception) {
                    logger.error(e) { "Health check error" }
                    val errorResponse = """{"status": "error", "message": "Health check failed"}"""
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(500, errorResponse.toByteArray().size.toLong())
                    exchange.responseBody.write(errorResponse.toByteArray())
                    exchange.responseBody.close()
                }
            }
            
            // Add database pool stats endpoint for monitoring
            server.createContext("/health/database") { exchange ->
                try {
                    val poolStats = database.getPoolStats()
                    val isHealthy = database.isHealthy()
                    
                    val response = """
                        {
                            "database": {
                                "healthy": $isHealthy,
                                "connectionPool": {
                                    "activeConnections": ${poolStats["activeConnections"]},
                                    "idleConnections": ${poolStats["idleConnections"]},
                                    "totalConnections": ${poolStats["totalConnections"]},
                                    "threadsAwaitingConnection": ${poolStats["threadsAwaitingConnection"]},
                                    "maximumPoolSize": ${poolStats["maximumPoolSize"]},
                                    "minimumIdle": ${poolStats["minimumIdle"]}
                                }
                            }
                        }
                    """.trimIndent()
                    
                    val statusCode = if (isHealthy) 200 else 503
                    
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(statusCode, response.toByteArray().size.toLong())
                    exchange.responseBody.write(response.toByteArray())
                    exchange.responseBody.close()
                    
                } catch (e: Exception) {
                    logger.error(e) { "Database health check error" }
                    val errorResponse = """{"status": "error", "message": "Database health check failed"}"""
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(500, errorResponse.toByteArray().size.toLong())
                    exchange.responseBody.write(errorResponse.toByteArray())
                    exchange.responseBody.close()
                }
            }
            
            server.executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            server.start()
            
            logger.info { "Health check server started on port 8080" }
            logger.info { "Endpoints: /health (general), /health/database (detailed pool stats)" }
            
            // Keep the main thread alive with periodic health logging
            while (true) {
                delay(300000) // Check every 5 minutes
                
                // Log periodic status with database pool info
                val poolStats = database.getPoolStats()
                logger.debug { "Bot running normally - Active DB connections: ${poolStats["activeConnections"]}/${poolStats["totalConnections"]}" }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to start health check server" }
            throw e
        }
    }
}

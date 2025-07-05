// Configuration models
data class BotConfig(
    val botToken: String,
    val apiId: Int?,
    val apiHash: String?,
    val phoneNumber: String?,
    val authorizedAdminId: Long,
    val databasePath: String,
    val logPath: String,
    val logLevel: String,
    val tdlibLogLevel: String,
    val rateLimitMessagesPerMinute: Int,
    val rateLimitBurstSize: Int
)

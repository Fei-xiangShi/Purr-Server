package life.fxs.purr.server.config

data class PurrServerConfig(
    val environment: RuntimeEnvironment,
    val auth: AuthConfig,
    val pair: PairConfig,
    val liveKit: LiveKitConfig,
    val recording: RecordingConfig,
    val realtime: RealtimeConfig,
    val outbox: OutboxConfig,
    val push: PushConfig,
    val rateLimit: AuthRateLimitConfig,
    val database: DatabaseConfig,
    val callReconciliation: CallReconciliationConfig = CallReconciliationConfig(),
)

enum class RuntimeEnvironment {
    DEVELOPMENT,
    TEST,
    PRODUCTION,
}

data class AuthConfig(
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    val issuer: String,
    val audience: String,
    val jwtSecret: String,
    val seedUsers: List<SeedUserConfig>,
)

data class SeedUserConfig(
    val userId: String,
    val username: String,
    val password: String,
    val displayName: String,
    val avatarUrl: String?,
)

data class PairConfig(
    val pairId: String,
    val bondedAtEpochMillis: Long,
    val userAId: String,
    val userBId: String,
)

data class LiveKitConfig(
    val wsUrl: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtlSeconds: Long,
    val httpUrl: String,
)

data class RecordingConfig(
    val enabled: Boolean,
    val provider: RecordingProvider,
    val idPrefix: String,
    val filePrefix: String,
    val bucket: String,
    val endpoint: String,
    val publicEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String,
    val forcePathStyle: Boolean,
    val recoveryEnabled: Boolean,
    val recoveryIntervalSeconds: Long,
    val recoveryStaleAfterSeconds: Long,
    val recoveryMaxAttempts: Int,
    val downloadUrlTtlSeconds: Long,
    val consentPolicyVersion: String,
    val cleanupEnabled: Boolean,
    val retentionDays: Int,
    val cleanupIntervalSeconds: Long,
    val cleanupBatchSize: Int,
    val cleanupMaxAttempts: Int,
)

enum class RecordingProvider {
    LIVEKIT,
    IN_MEMORY,
}

data class RealtimeConfig(
    val provider: RealtimeProvider,
    val redisUri: String,
    val redisPassword: String,
    val channel: String,
)

enum class RealtimeProvider {
    LOCAL,
    REDIS,
}

data class OutboxConfig(
    val pollIntervalMillis: Long,
    val batchSize: Int,
    val leaseSeconds: Long,
    val maxAttempts: Int,
    val retryBaseSeconds: Long,
    val retryMaxSeconds: Long,
)

data class PushConfig(
    val enabled: Boolean,
    val provider: PushProvider,
    val fcmProjectId: String,
    val fcmServiceAccountPath: String,
    val messageTtlSeconds: Long,
)

enum class PushProvider {
    FCM,
}

data class CallReconciliationConfig(
    val enabled: Boolean = false,
    val intervalSeconds: Long = 10,
    val waitingTtlSeconds: Long = 120,
    val emptyRoomGraceSeconds: Long = 15,
    val batchSize: Int = 100,
)

data class AuthRateLimitConfig(
    val provider: RateLimitProvider,
    val limit: Int,
    val refillPeriodSeconds: Long,
    val redisUri: String,
    val redisPassword: String,
    val keyPrefix: String,
)

enum class RateLimitProvider {
    LOCAL,
    REDIS,
}

data class DatabaseConfig(
    val jdbcUrl: String,
    val driverClassName: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
)

package life.fxs.purr.server.config

data class PurrServerConfig(
    val auth: AuthConfig,
    val pair: PairConfig,
    val liveKit: LiveKitConfig,
    val recording: RecordingConfig,
    val database: DatabaseConfig,
)

data class AuthConfig(
    val accessTokenTtlSeconds: Long,
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
    val provider: String,
    val idPrefix: String,
    val filePrefix: String,
    val bucket: String,
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String,
    val forcePathStyle: Boolean,
)

data class DatabaseConfig(
    val jdbcUrl: String,
    val driverClassName: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
)

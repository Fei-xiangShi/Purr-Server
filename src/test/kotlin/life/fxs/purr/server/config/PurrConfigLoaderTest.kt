package life.fxs.purr.server.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurrConfigLoaderTest {
    @Test
    fun `valid production configuration is accepted`() {
        PurrConfigLoader.validate(validProductionConfig())
    }

    @Test
    fun `production rejects local realtime provider`() {
        val config = validProductionConfig().copy(
            realtime = validProductionConfig().realtime.copy(provider = RealtimeProvider.LOCAL),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production rejects short Redis password`() {
        val config = validProductionConfig().copy(
            realtime = validProductionConfig().realtime.copy(redisPassword = "too-short"),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production rejects local authentication rate limiting`() {
        val config = validProductionConfig().copy(
            rateLimit = validProductionConfig().rateLimit.copy(provider = RateLimitProvider.LOCAL),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production rejects short authentication rate limit Redis password`() {
        val config = validProductionConfig().copy(
            rateLimit = validProductionConfig().rateLimit.copy(redisPassword = "too-short"),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production recording requires recovery`() {
        val config = validProductionConfig().copy(
            recording = validProductionConfig().recording.copy(recoveryEnabled = false),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production recording download endpoint requires TLS`() {
        val config = validProductionConfig().copy(
            recording = validProductionConfig().recording.copy(publicEndpoint = "http://storage.internal"),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    @Test
    fun `production recording requires retention cleanup`() {
        val config = validProductionConfig().copy(
            recording = validProductionConfig().recording.copy(cleanupEnabled = false),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(config) }
    }

    private fun validProductionConfig() = PurrServerConfig(
        environment = RuntimeEnvironment.PRODUCTION,
        auth = AuthConfig(
            accessTokenTtlSeconds = 900,
            refreshTokenTtlSeconds = 2_592_000,
            issuer = "purr-server",
            audience = "purr-mobile",
            jwtSecret = "a-production-jwt-secret-with-32-bytes",
            seedUsers = listOf(
                SeedUserConfig("user-a", "user-a", "password-a", "A", null),
                SeedUserConfig("user-b", "user-b", "password-b", "B", null),
            ),
        ),
        pair = PairConfig("pair-1", 1L, "user-a", "user-b"),
        liveKit = LiveKitConfig(
            wsUrl = "wss://call.example.com",
            apiKey = "key",
            apiSecret = "secret",
            tokenTtlSeconds = 900,
            httpUrl = "http://livekit:7880",
        ),
        recording = RecordingConfig(
            enabled = true,
            provider = RecordingProvider.LIVEKIT,
            idPrefix = "rec",
            filePrefix = "recordings",
            bucket = "purr-recordings",
            endpoint = "http://minio:9000",
            publicEndpoint = "https://storage.example.com",
            accessKey = "key",
            secretKey = "secret",
            region = "us-east-1",
            forcePathStyle = true,
            recoveryEnabled = true,
            recoveryIntervalSeconds = 30,
            recoveryStaleAfterSeconds = 90,
            recoveryMaxAttempts = 5,
            downloadUrlTtlSeconds = 300,
            consentPolicyVersion = "2026-01",
            cleanupEnabled = true,
            retentionDays = 30,
            cleanupIntervalSeconds = 3600,
            cleanupBatchSize = 100,
            cleanupMaxAttempts = 10,
        ),
        realtime = RealtimeConfig(
            provider = RealtimeProvider.REDIS,
            redisUri = "redis://redis:6379",
            redisPassword = "a-strong-redis-password",
            channel = "purr:realtime",
        ),
        outbox = OutboxConfig(
            pollIntervalMillis = 100,
            batchSize = 100,
            leaseSeconds = 30,
            maxAttempts = 10,
            retryBaseSeconds = 1,
            retryMaxSeconds = 300,
        ),
        rateLimit = AuthRateLimitConfig(
            provider = RateLimitProvider.REDIS,
            limit = 10,
            refillPeriodSeconds = 60,
            redisUri = "redis://redis:6379",
            redisPassword = "a-strong-redis-password",
            keyPrefix = "purr:rate-limit:auth",
        ),
        database = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://postgres:5432/purr",
            driverClassName = "org.postgresql.Driver",
            username = "purr",
            password = "database-password",
            maximumPoolSize = 5,
        ),
    )
}

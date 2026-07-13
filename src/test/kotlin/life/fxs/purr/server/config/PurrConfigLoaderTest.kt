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

    @Test
    fun `pair users must match unique seed users`() {
        val duplicateUsers = validProductionConfig().copy(
            auth = validProductionConfig().auth.copy(
                seedUsers = listOf(
                    SeedUserConfig("user-a", "user-a", "strong-password-a", "A", null),
                    SeedUserConfig("user-a", "user-b", "strong-password-b", "B", null),
                ),
            ),
        )
        val mismatchedPair = validProductionConfig().copy(
            pair = validProductionConfig().pair.copy(userBId = "user-c"),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(duplicateUsers) }
        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(mismatchedPair) }
    }

    @Test
    fun `production rejects weak account and infrastructure secrets`() {
        val weakUserPassword = validProductionConfig().copy(
            auth = validProductionConfig().auth.copy(
                seedUsers = validProductionConfig().auth.seedUsers.mapIndexed { index, user ->
                    if (index == 0) user.copy(password = user.username) else user
                },
            ),
        )
        val weakDatabasePassword = validProductionConfig().copy(
            database = validProductionConfig().database.copy(password = "short"),
        )
        val placeholderRecordingSecret = validProductionConfig().copy(
            recording = validProductionConfig().recording.copy(secretKey = "change-me-recording-key"),
        )

        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(weakUserPassword) }
        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(weakDatabasePassword) }
        assertFailsWith<IllegalArgumentException> { PurrConfigLoader.validate(placeholderRecordingSecret) }
    }

    @Test
    fun `production accepts eight character user passwords`() {
        val config = validProductionConfig().copy(
            auth = validProductionConfig().auth.copy(
                seedUsers = listOf(
                    validProductionConfig().auth.seedUsers[0].copy(password = "abc12345"),
                    validProductionConfig().auth.seedUsers[1],
                ),
            ),
        )

        PurrConfigLoader.validate(config)
    }

    @Test
    fun `production rejects user passwords shorter than eight characters`() {
        val config = validProductionConfig().copy(
            auth = validProductionConfig().auth.copy(
                seedUsers = listOf(
                    validProductionConfig().auth.seedUsers[0].copy(password = "abc1234"),
                    validProductionConfig().auth.seedUsers[1],
                ),
            ),
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
                SeedUserConfig("user-a", "user-a", "strong-password-a", "A", null),
                SeedUserConfig("user-b", "user-b", "strong-password-b", "B", null),
            ),
        ),
        pair = PairConfig("pair-1", 1L, "user-a", "user-b"),
        liveKit = LiveKitConfig(
            wsUrl = "wss://call.example.com",
            apiKey = "key",
            apiSecret = "a-strong-livekit-secret",
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
            secretKey = "a-strong-recording-secret",
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
        callReconciliation = CallReconciliationConfig(
            enabled = true,
            intervalSeconds = 10,
            waitingTtlSeconds = 120,
            emptyRoomGraceSeconds = 15,
            batchSize = 100,
        ),
    )
}

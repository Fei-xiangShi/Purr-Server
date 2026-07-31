package life.fxs.purr.server.config

import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.time.ZoneId

object PurrConfigLoader {
    fun load(config: ApplicationConfig): PurrServerConfig {
        val serverConfig = PurrServerConfig(
            environment = enumValueOf<RuntimeEnvironment>(
                string(config, "purr.environment", "PURR_ENVIRONMENT").uppercase(),
            ),
            auth = AuthConfig(
                accessTokenTtlSeconds = long(config, "purr.auth.accessTokenTtlSeconds", "PURR_AUTH_ACCESS_TOKEN_TTL_SECONDS"),
                refreshTokenTtlSeconds = long(config, "purr.auth.refreshTokenTtlSeconds", "PURR_AUTH_REFRESH_TOKEN_TTL_SECONDS"),
                issuer = string(config, "purr.auth.issuer", "PURR_AUTH_ISSUER"),
                audience = string(config, "purr.auth.audience", "PURR_AUTH_AUDIENCE"),
                jwtSecret = string(config, "purr.auth.jwtSecret", "PURR_AUTH_JWT_SECRET"),
                seedUsers = listOf(
                    SeedUserConfig(
                        userId = string(config, "purr.auth.seedUsers.userA.userId", "PURR_AUTH_USER_A_ID"),
                        username = string(config, "purr.auth.seedUsers.userA.username", "PURR_AUTH_USER_A_USERNAME"),
                        password = string(config, "purr.auth.seedUsers.userA.password", "PURR_AUTH_USER_A_PASSWORD"),
                        displayName = string(config, "purr.auth.seedUsers.userA.displayName", "PURR_AUTH_USER_A_DISPLAY_NAME"),
                        avatarUrl = optionalString(config, "purr.auth.seedUsers.userA.avatarUrl", "PURR_AUTH_USER_A_AVATAR_URL"),
                    ),
                    SeedUserConfig(
                        userId = string(config, "purr.auth.seedUsers.userB.userId", "PURR_AUTH_USER_B_ID"),
                        username = string(config, "purr.auth.seedUsers.userB.username", "PURR_AUTH_USER_B_USERNAME"),
                        password = string(config, "purr.auth.seedUsers.userB.password", "PURR_AUTH_USER_B_PASSWORD"),
                        displayName = string(config, "purr.auth.seedUsers.userB.displayName", "PURR_AUTH_USER_B_DISPLAY_NAME"),
                        avatarUrl = optionalString(config, "purr.auth.seedUsers.userB.avatarUrl", "PURR_AUTH_USER_B_AVATAR_URL"),
                    ),
                ),
            ),
            pair = PairConfig(
                pairId = string(config, "purr.pair.pairId", "PURR_PAIR_ID"),
                bondedAtEpochMillis = long(config, "purr.pair.bondedAtEpochMillis", "PURR_PAIR_BONDED_AT_EPOCH_MILLIS"),
                userAId = string(config, "purr.pair.userAId", "PURR_PAIR_USER_A_ID"),
                userBId = string(config, "purr.pair.userBId", "PURR_PAIR_USER_B_ID"),
            ),
            liveKit = LiveKitConfig(
                wsUrl = string(config, "purr.livekit.wsUrl", "PURR_LIVEKIT_WS_URL"),
                apiKey = string(config, "purr.livekit.apiKey", "PURR_LIVEKIT_API_KEY"),
                apiSecret = string(config, "purr.livekit.apiSecret", "PURR_LIVEKIT_API_SECRET"),
                tokenTtlSeconds = long(config, "purr.livekit.tokenTtlSeconds", "PURR_LIVEKIT_TOKEN_TTL_SECONDS"),
                httpUrl = string(config, "purr.livekit.httpUrl", "PURR_LIVEKIT_HTTP_URL"),
            ),
            recording = RecordingConfig(
                enabled = boolean(config, "purr.recording.enabled", "PURR_RECORDING_ENABLED"),
                provider = enumValueOf<RecordingProvider>(
                    string(config, "purr.recording.provider", "PURR_RECORDING_PROVIDER").uppercase(),
                ),
                idPrefix = string(config, "purr.recording.idPrefix", "PURR_RECORDING_ID_PREFIX"),
                filePrefix = string(config, "purr.recording.filePrefix", "PURR_RECORDING_FILE_PREFIX"),
                bucket = string(config, "purr.recording.bucket", "PURR_RECORDING_BUCKET"),
                endpoint = string(config, "purr.recording.endpoint", "PURR_RECORDING_ENDPOINT"),
                publicEndpoint = string(
                    config,
                    "purr.recording.publicEndpoint",
                    "PURR_RECORDING_PUBLIC_ENDPOINT",
                ),
                accessKey = string(config, "purr.recording.accessKey", "PURR_RECORDING_ACCESS_KEY"),
                secretKey = string(config, "purr.recording.secretKey", "PURR_RECORDING_SECRET_KEY"),
                region = string(config, "purr.recording.region", "PURR_RECORDING_REGION"),
                forcePathStyle = boolean(config, "purr.recording.forcePathStyle", "PURR_RECORDING_FORCE_PATH_STYLE"),
                recoveryEnabled = boolean(config, "purr.recording.recoveryEnabled", "PURR_RECORDING_RECOVERY_ENABLED"),
                recoveryIntervalSeconds = long(
                    config,
                    "purr.recording.recoveryIntervalSeconds",
                    "PURR_RECORDING_RECOVERY_INTERVAL_SECONDS",
                ),
                recoveryStaleAfterSeconds = long(
                    config,
                    "purr.recording.recoveryStaleAfterSeconds",
                    "PURR_RECORDING_RECOVERY_STALE_AFTER_SECONDS",
                ),
                recoveryMaxAttempts = int(
                    config,
                    "purr.recording.recoveryMaxAttempts",
                    "PURR_RECORDING_RECOVERY_MAX_ATTEMPTS",
                ),
                downloadUrlTtlSeconds = long(
                    config,
                    "purr.recording.downloadUrlTtlSeconds",
                    "PURR_RECORDING_DOWNLOAD_URL_TTL_SECONDS",
                ),
                consentPolicyVersion = string(
                    config,
                    "purr.recording.consentPolicyVersion",
                    "PURR_RECORDING_CONSENT_POLICY_VERSION",
                ),
                cleanupEnabled = boolean(
                    config,
                    "purr.recording.cleanupEnabled",
                    "PURR_RECORDING_CLEANUP_ENABLED",
                ),
                retentionDays = int(config, "purr.recording.retentionDays", "PURR_RECORDING_RETENTION_DAYS"),
                cleanupIntervalSeconds = long(
                    config,
                    "purr.recording.cleanupIntervalSeconds",
                    "PURR_RECORDING_CLEANUP_INTERVAL_SECONDS",
                ),
                cleanupBatchSize = int(
                    config,
                    "purr.recording.cleanupBatchSize",
                    "PURR_RECORDING_CLEANUP_BATCH_SIZE",
                ),
                cleanupMaxAttempts = int(
                    config,
                    "purr.recording.cleanupMaxAttempts",
                    "PURR_RECORDING_CLEANUP_MAX_ATTEMPTS",
                ),
                cleanupHour = int(config, "purr.recording.cleanupHour", "PURR_RECORDING_CLEANUP_HOUR"),
                cleanupMinute = int(config, "purr.recording.cleanupMinute", "PURR_RECORDING_CLEANUP_MINUTE"),
                cleanupTimeZone = string(
                    config,
                    "purr.recording.cleanupTimeZone",
                    "PURR_RECORDING_CLEANUP_TIME_ZONE",
                ),
                cleanupLeaseSeconds = long(
                    config,
                    "purr.recording.cleanupLeaseSeconds",
                    "PURR_RECORDING_CLEANUP_LEASE_SECONDS",
                ),
            ),
            avatar = AvatarConfig(
                bucket = string(config, "purr.avatar.bucket", "PURR_AVATAR_BUCKET"),
                endpoint = string(config, "purr.avatar.endpoint", "PURR_AVATAR_ENDPOINT"),
                publicEndpoint = string(config, "purr.avatar.publicEndpoint", "PURR_AVATAR_PUBLIC_ENDPOINT"),
                accessKey = string(config, "purr.avatar.accessKey", "PURR_AVATAR_ACCESS_KEY"),
                secretKey = string(config, "purr.avatar.secretKey", "PURR_AVATAR_SECRET_KEY"),
                region = string(config, "purr.avatar.region", "PURR_AVATAR_REGION"),
                forcePathStyle = boolean(config, "purr.avatar.forcePathStyle", "PURR_AVATAR_FORCE_PATH_STYLE"),
                outputSizePixels = int(config, "purr.avatar.outputSizePixels", "PURR_AVATAR_OUTPUT_SIZE_PIXELS"),
                maxSourceDimensionPixels = int(
                    config,
                    "purr.avatar.maxSourceDimensionPixels",
                    "PURR_AVATAR_MAX_SOURCE_DIMENSION_PIXELS",
                ),
                maxSourcePixels = long(config, "purr.avatar.maxSourcePixels", "PURR_AVATAR_MAX_SOURCE_PIXELS"),
                jpegQualityPercent = int(
                    config,
                    "purr.avatar.jpegQualityPercent",
                    "PURR_AVATAR_JPEG_QUALITY_PERCENT",
                ),
                maxOutputBytes = int(config, "purr.avatar.maxOutputBytes", "PURR_AVATAR_MAX_OUTPUT_BYTES"),
                maxConcurrentProcessing = int(
                    config,
                    "purr.avatar.maxConcurrentProcessing",
                    "PURR_AVATAR_MAX_CONCURRENT_PROCESSING",
                ),
                cleanupEnabled = boolean(config, "purr.avatar.cleanupEnabled", "PURR_AVATAR_CLEANUP_ENABLED"),
                cleanupIntervalSeconds = long(
                    config,
                    "purr.avatar.cleanupIntervalSeconds",
                    "PURR_AVATAR_CLEANUP_INTERVAL_SECONDS",
                ),
                cleanupBatchSize = int(
                    config,
                    "purr.avatar.cleanupBatchSize",
                    "PURR_AVATAR_CLEANUP_BATCH_SIZE",
                ),
                cleanupMaxAttempts = int(
                    config,
                    "purr.avatar.cleanupMaxAttempts",
                    "PURR_AVATAR_CLEANUP_MAX_ATTEMPTS",
                ),
                cleanupRetryBaseSeconds = long(
                    config,
                    "purr.avatar.cleanupRetryBaseSeconds",
                    "PURR_AVATAR_CLEANUP_RETRY_BASE_SECONDS",
                ),
                cleanupRetryMaxSeconds = long(
                    config,
                    "purr.avatar.cleanupRetryMaxSeconds",
                    "PURR_AVATAR_CLEANUP_RETRY_MAX_SECONDS",
                ),
                orphanGraceSeconds = long(
                    config,
                    "purr.avatar.orphanGraceSeconds",
                    "PURR_AVATAR_ORPHAN_GRACE_SECONDS",
                ),
            ),
            realtime = RealtimeConfig(
                provider = enumValueOf<RealtimeProvider>(
                    string(config, "purr.realtime.provider", "PURR_REALTIME_PROVIDER").uppercase(),
                ),
                redisUri = string(config, "purr.realtime.redisUri", "PURR_REALTIME_REDIS_URI"),
                redisPassword = string(config, "purr.realtime.redisPassword", "PURR_REALTIME_REDIS_PASSWORD"),
                channel = string(config, "purr.realtime.channel", "PURR_REALTIME_CHANNEL"),
            ),
            outbox = OutboxConfig(
                pollIntervalMillis = long(
                    config,
                    "purr.outbox.pollIntervalMillis",
                    "PURR_OUTBOX_POLL_INTERVAL_MILLIS",
                ),
                batchSize = int(config, "purr.outbox.batchSize", "PURR_OUTBOX_BATCH_SIZE"),
                leaseSeconds = long(config, "purr.outbox.leaseSeconds", "PURR_OUTBOX_LEASE_SECONDS"),
                maxAttempts = int(config, "purr.outbox.maxAttempts", "PURR_OUTBOX_MAX_ATTEMPTS"),
                retryBaseSeconds = long(
                    config,
                    "purr.outbox.retryBaseSeconds",
                    "PURR_OUTBOX_RETRY_BASE_SECONDS",
                ),
                retryMaxSeconds = long(
                    config,
                    "purr.outbox.retryMaxSeconds",
                    "PURR_OUTBOX_RETRY_MAX_SECONDS",
                ),
            ),
            push = PushConfig(
                enabled = boolean(config, "purr.push.enabled", "PURR_PUSH_ENABLED"),
                provider = enumValueOf<PushProvider>(
                    string(config, "purr.push.provider", "PURR_PUSH_PROVIDER").uppercase(),
                ),
                fcmProjectId = string(config, "purr.push.fcmProjectId", "PURR_PUSH_FCM_PROJECT_ID"),
                fcmServiceAccountPath = string(
                    config,
                    "purr.push.fcmServiceAccountPath",
                    "PURR_PUSH_FCM_SERVICE_ACCOUNT_PATH",
                ),
                messageTtlSeconds = long(
                    config,
                    "purr.push.messageTtlSeconds",
                    "PURR_PUSH_MESSAGE_TTL_SECONDS",
                ),
            ),
            rateLimit = AuthRateLimitConfig(
                provider = enumValueOf<RateLimitProvider>(
                    string(config, "purr.rateLimit.provider", "PURR_RATE_LIMIT_PROVIDER").uppercase(),
                ),
                limit = int(config, "purr.rateLimit.limit", "PURR_RATE_LIMIT_LIMIT"),
                refillPeriodSeconds = long(
                    config,
                    "purr.rateLimit.refillPeriodSeconds",
                    "PURR_RATE_LIMIT_REFILL_PERIOD_SECONDS",
                ),
                redisUri = string(config, "purr.rateLimit.redisUri", "PURR_RATE_LIMIT_REDIS_URI"),
                redisPassword = string(
                    config,
                    "purr.rateLimit.redisPassword",
                    "PURR_RATE_LIMIT_REDIS_PASSWORD",
                ),
                keyPrefix = string(config, "purr.rateLimit.keyPrefix", "PURR_RATE_LIMIT_KEY_PREFIX"),
            ),
            database = DatabaseConfig(
                jdbcUrl = string(config, "purr.database.jdbcUrl", "PURR_DB_JDBC_URL"),
                driverClassName = string(config, "purr.database.driverClassName", "PURR_DB_DRIVER_CLASS_NAME"),
                username = string(config, "purr.database.username", "PURR_DB_USERNAME"),
                password = string(config, "purr.database.password", "PURR_DB_PASSWORD"),
                maximumPoolSize = int(config, "purr.database.maximumPoolSize", "PURR_DB_MAXIMUM_POOL_SIZE"),
            ),
            callReconciliation = CallReconciliationConfig(
                enabled = boolean(
                    config,
                    "purr.callReconciliation.enabled",
                    "PURR_CALL_RECONCILIATION_ENABLED",
                ),
                intervalSeconds = long(
                    config,
                    "purr.callReconciliation.intervalSeconds",
                    "PURR_CALL_RECONCILIATION_INTERVAL_SECONDS",
                ),
                waitingTtlSeconds = long(
                    config,
                    "purr.callReconciliation.waitingTtlSeconds",
                    "PURR_CALL_RECONCILIATION_WAITING_TTL_SECONDS",
                ),
                emptyRoomGraceSeconds = long(
                    config,
                    "purr.callReconciliation.emptyRoomGraceSeconds",
                    "PURR_CALL_RECONCILIATION_EMPTY_ROOM_GRACE_SECONDS",
                ),
                batchSize = int(
                    config,
                    "purr.callReconciliation.batchSize",
                    "PURR_CALL_RECONCILIATION_BATCH_SIZE",
                ),
            ),
            googleDrive = GoogleDriveConfig(
                enabled = boolean(config, "purr.googleDrive.enabled", "PURR_GOOGLE_DRIVE_ENABLED"),
                serviceAccountPath = string(
                    config,
                    "purr.googleDrive.serviceAccountPath",
                    "PURR_GOOGLE_DRIVE_SERVICE_ACCOUNT_PATH",
                ),
                folderId = string(config, "purr.googleDrive.folderId", "PURR_GOOGLE_DRIVE_FOLDER_ID"),
                pollIntervalMillis = long(
                    config,
                    "purr.googleDrive.pollIntervalMillis",
                    "PURR_GOOGLE_DRIVE_POLL_INTERVAL_MILLIS",
                ),
                leaseSeconds = long(config, "purr.googleDrive.leaseSeconds", "PURR_GOOGLE_DRIVE_LEASE_SECONDS"),
                retryBaseSeconds = long(
                    config,
                    "purr.googleDrive.retryBaseSeconds",
                    "PURR_GOOGLE_DRIVE_RETRY_BASE_SECONDS",
                ),
                retryMaxSeconds = long(
                    config,
                    "purr.googleDrive.retryMaxSeconds",
                    "PURR_GOOGLE_DRIVE_RETRY_MAX_SECONDS",
                ),
            ),
        )
        validate(serverConfig)
        return serverConfig
    }

    private fun string(config: ApplicationConfig, key: String, envKey: String): String {
        return System.getenv(envKey)?.takeIf { it.isNotBlank() }
            ?: config.property(key).getString()
    }

    private fun optionalString(config: ApplicationConfig, key: String, envKey: String): String? {
        val envValue = System.getenv(envKey)
        return when {
            envValue != null -> envValue.takeIf { it.isNotBlank() }
            else -> config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
        }
    }

    private fun boolean(config: ApplicationConfig, key: String, envKey: String): Boolean {
        return when (val value = string(config, key, envKey).lowercase()) {
            "true" -> true
            "false" -> false
            else -> error("$envKey must be either true or false, but was '$value'")
        }
    }

    private fun int(config: ApplicationConfig, key: String, envKey: String): Int {
        return string(config, key, envKey).toInt()
    }

    private fun long(config: ApplicationConfig, key: String, envKey: String): Long {
        return string(config, key, envKey).toLong()
    }

    internal fun validate(config: PurrServerConfig) {
        require(config.auth.seedUsers.size == EXPECTED_SEED_USER_COUNT) {
            "Exactly two seed users are required"
        }
        require(config.auth.seedUsers.map { it.userId }.toSet().size == config.auth.seedUsers.size) {
            "Seed user IDs must be unique"
        }
        require(config.auth.seedUsers.map { it.username }.toSet().size == config.auth.seedUsers.size) {
            "Seed usernames must be unique"
        }
        config.auth.seedUsers.forEach { user ->
            require(user.userId.matches(IDENTIFIER_PATTERN)) { "Invalid seed user ID: ${user.userId}" }
            require(user.username.matches(USERNAME_PATTERN)) { "Invalid seed username: ${user.username}" }
            require(user.displayName.isNotBlank() && user.displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
                "Seed user display name must contain between 1 and $MAX_DISPLAY_NAME_LENGTH characters"
            }
        }
        require(config.pair.pairId.matches(IDENTIFIER_PATTERN)) { "Invalid pair ID" }
        require(config.pair.userAId != config.pair.userBId) { "Pair users must be different" }
        require(
            setOf(config.pair.userAId, config.pair.userBId) == config.auth.seedUsers.map { it.userId }.toSet(),
        ) {
            "Pair users must match the configured seed users"
        }
        require(config.auth.accessTokenTtlSeconds in 60..3600) {
            "Access token TTL must be between 60 and 3600 seconds"
        }
        require(config.auth.refreshTokenTtlSeconds > config.auth.accessTokenTtlSeconds) {
            "Refresh token TTL must be longer than access token TTL"
        }
        require(config.auth.jwtSecret.toByteArray().size >= 32) {
            "JWT secret must contain at least 32 bytes"
        }
        require(config.liveKit.tokenTtlSeconds in 60..3600) {
            "LiveKit token TTL must be between 60 and 3600 seconds"
        }
        require(config.database.maximumPoolSize in 1..100) {
            "Database pool size must be between 1 and 100"
        }
        require(config.recording.recoveryIntervalSeconds in 5..3600) {
            "Recording recovery interval must be between 5 and 3600 seconds"
        }
        require(config.recording.recoveryStaleAfterSeconds >= config.recording.recoveryIntervalSeconds) {
            "Recording recovery stale threshold must not be shorter than the recovery interval"
        }
        require(config.recording.recoveryMaxAttempts in 1..100) {
            "Recording recovery max attempts must be between 1 and 100"
        }
        require(config.recording.downloadUrlTtlSeconds in 30..900) {
            "Recording download URL TTL must be between 30 and 900 seconds"
        }
        require(config.recording.consentPolicyVersion.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "Recording consent policy version contains invalid characters"
        }
        require(config.recording.retentionDays in 1..3650) {
            "Recording retention must be between 1 and 3650 days"
        }
        require(config.recording.cleanupIntervalSeconds in 60..86_400) {
            "Recording cleanup interval must be between 60 and 86400 seconds"
        }
        require(config.recording.cleanupBatchSize in 1..1000) {
            "Recording cleanup batch size must be between 1 and 1000"
        }
        require(config.recording.cleanupMaxAttempts in 1..100) {
            "Recording cleanup max attempts must be between 1 and 100"
        }
        require(config.recording.cleanupHour in 0..23) {
            "Recording cleanup hour must be between 0 and 23"
        }
        require(config.recording.cleanupMinute in 0..59) {
            "Recording cleanup minute must be between 0 and 59"
        }
        runCatching { ZoneId.of(config.recording.cleanupTimeZone) }
            .getOrElse { throw IllegalArgumentException("Recording cleanup timezone is invalid", it) }
        require(config.recording.cleanupLeaseSeconds in 30..86_400) {
            "Recording cleanup lease must be between 30 and 86400 seconds"
        }
        require(config.googleDrive.pollIntervalMillis in 100..60_000) {
            "Google Drive poll interval must be between 100 and 60000 milliseconds"
        }
        require(config.googleDrive.leaseSeconds in 60..86_400) {
            "Google Drive upload lease must be between 60 and 86400 seconds"
        }
        require(config.googleDrive.retryBaseSeconds in 1..3_600) {
            "Google Drive retry base must be between 1 and 3600 seconds"
        }
        require(config.googleDrive.retryMaxSeconds in config.googleDrive.retryBaseSeconds..86_400) {
            "Google Drive retry max must be between the base delay and 86400 seconds"
        }
        if (config.googleDrive.enabled) {
            require(config.googleDrive.serviceAccountPath.isNotBlank()) {
                "Google Drive service account path is required when recording archive is enabled"
            }
            require(config.googleDrive.folderId.matches(Regex("[A-Za-z0-9_-]{10,256}"))) {
                "Google Drive folder ID is invalid"
            }
        }
        requireHttpEndpoint(config.recording.endpoint, "Recording storage endpoint")
        val recordingPublicEndpoint = requireHttpEndpoint(
            config.recording.publicEndpoint,
            "Recording public endpoint",
        )
        require(config.avatar.bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) {
            "Avatar bucket name is invalid"
        }
        require(config.avatar.bucket != config.recording.bucket) {
            "Avatar and recording storage must use separate buckets"
        }
        require(config.avatar.forcePathStyle || '.' !in config.avatar.bucket) {
            "Virtual-hosted avatar buckets must not contain dots because wildcard TLS certificates will not match"
        }
        requireHttpEndpoint(config.avatar.endpoint, "Avatar storage endpoint")
        val avatarPublicEndpoint = requireHttpEndpoint(config.avatar.publicEndpoint, "Avatar public endpoint")
        require(config.avatar.accessKey.isNotBlank() && config.avatar.secretKey.isNotBlank()) {
            "Avatar storage credentials must not be blank"
        }
        require(config.avatar.outputSizePixels in 128..2_048) {
            "Avatar output size must be between 128 and 2048 pixels"
        }
        require(config.avatar.maxSourceDimensionPixels in config.avatar.outputSizePixels..32_768) {
            "Avatar source dimension limit is invalid"
        }
        require(config.avatar.maxSourcePixels in 1_000_000L..100_000_000L) {
            "Avatar source pixel limit must be between 1 and 100 million pixels"
        }
        require(config.avatar.jpegQualityPercent in 50..95) {
            "Avatar JPEG quality must be between 50 and 95"
        }
        require(config.avatar.maxOutputBytes in 64 * 1024..5 * 1024 * 1024) {
            "Avatar output byte limit must be between 64 KB and 5 MB"
        }
        require(config.avatar.maxConcurrentProcessing in 1..16) {
            "Avatar processing concurrency must be between 1 and 16"
        }
        require(config.avatar.cleanupIntervalSeconds in 10..86_400) {
            "Avatar cleanup interval must be between 10 and 86400 seconds"
        }
        require(config.avatar.cleanupBatchSize in 1..1_000) {
            "Avatar cleanup batch size must be between 1 and 1000"
        }
        require(config.avatar.cleanupMaxAttempts in 1..100) {
            "Avatar cleanup max-backoff attempt threshold must be between 1 and 100"
        }
        require(config.avatar.cleanupRetryBaseSeconds in 1..3_600) {
            "Avatar cleanup retry base must be between 1 and 3600 seconds"
        }
        require(config.avatar.cleanupRetryMaxSeconds in config.avatar.cleanupRetryBaseSeconds..86_400) {
            "Avatar cleanup retry max must be between the base delay and 86400 seconds"
        }
        require(config.avatar.orphanGraceSeconds in 300..604_800) {
            "Avatar orphan grace must be between 5 minutes and 7 days"
        }
        require(config.realtime.redisUri.startsWith("redis://") || config.realtime.redisUri.startsWith("rediss://")) {
            "Realtime Redis URI must use redis:// or rediss://"
        }
        require(config.realtime.channel.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) {
            "Realtime channel must contain only letters, numbers, colon, underscore, or hyphen"
        }
        require(config.outbox.pollIntervalMillis in 10..60_000) {
            "Outbox poll interval must be between 10 and 60000 milliseconds"
        }
        require(config.outbox.batchSize in 1..1000) {
            "Outbox batch size must be between 1 and 1000"
        }
        require(config.outbox.leaseSeconds in 5..3600) {
            "Outbox lease must be between 5 and 3600 seconds"
        }
        require(config.outbox.maxAttempts in 1..100) {
            "Outbox max attempts must be between 1 and 100"
        }
        require(config.outbox.retryBaseSeconds in 1..3600) {
            "Outbox retry base delay must be between 1 and 3600 seconds"
        }
        require(config.outbox.retryMaxSeconds in config.outbox.retryBaseSeconds..86_400) {
            "Outbox retry max delay must be between the base delay and 86400 seconds"
        }
        require(config.push.messageTtlSeconds in 15..120) {
            "Push message TTL must be between 15 and 120 seconds"
        }
        if (config.push.enabled) {
            require(config.push.fcmProjectId.matches(Regex("[a-z][a-z0-9-]{4,62}"))) {
                "FCM project ID is invalid"
            }
            require(config.push.fcmServiceAccountPath.isNotBlank()) {
                "FCM service account path is required when push delivery is enabled"
            }
        }
        require(config.rateLimit.limit in 1..10_000) {
            "Authentication rate limit must be between 1 and 10000"
        }
        require(config.rateLimit.refillPeriodSeconds in 1..86_400) {
            "Authentication rate limit refill period must be between 1 and 86400 seconds"
        }
        require(
            config.rateLimit.redisUri.startsWith("redis://") ||
                config.rateLimit.redisUri.startsWith("rediss://"),
        ) {
            "Rate limit Redis URI must use redis:// or rediss://"
        }
        require(config.rateLimit.keyPrefix.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) {
            "Rate limit key prefix contains invalid characters"
        }
        require(config.callReconciliation.intervalSeconds in 1..300) {
            "Call reconciliation interval must be between 1 and 300 seconds"
        }
        require(config.callReconciliation.waitingTtlSeconds >= config.callReconciliation.intervalSeconds) {
            "Call waiting TTL must not be shorter than the reconciliation interval"
        }
        require(config.callReconciliation.waitingTtlSeconds in 10..86_400) {
            "Call waiting TTL must be between 10 and 86400 seconds"
        }
        require(config.callReconciliation.emptyRoomGraceSeconds in 1..3_600) {
            "Empty room grace must be between 1 and 3600 seconds"
        }
        require(config.callReconciliation.batchSize in 1..1_000) {
            "Call reconciliation batch size must be between 1 and 1000"
        }

        if (config.environment == RuntimeEnvironment.PRODUCTION) {
            require(config.push.enabled) {
                "Production incoming calls require push delivery"
            }
            require(config.callReconciliation.enabled) {
                "Production call reconciliation must be enabled"
            }
            require(!config.auth.jwtSecret.isPlaceholderSecret()) {
                "Development or placeholder JWT secret is forbidden in production"
            }
            require(config.liveKit.wsUrl.startsWith("wss://")) {
                "LiveKit WebSocket URL must use wss:// in production"
            }
            require(!config.database.jdbcUrl.startsWith("jdbc:h2:")) {
                "H2 is forbidden in production"
            }
            require(!config.recording.enabled || config.recording.provider == RecordingProvider.LIVEKIT) {
                "Enabled production recording must use the livekit provider"
            }
            require(!config.recording.enabled || config.recording.recoveryEnabled) {
                "Enabled production recording must enable recovery"
            }
            require(recordingPublicEndpoint.scheme.equals("https", ignoreCase = true)) {
                "Production media public endpoint must use https://"
            }
            require(avatarPublicEndpoint.scheme.equals("https", ignoreCase = true)) {
                "Production avatar public endpoint must use https://"
            }
            require(config.avatar.accessKey != config.recording.accessKey) {
                "Production avatar and recording storage must use different access keys"
            }
            require(config.avatar.secretKey != config.recording.secretKey) {
                "Production avatar and recording storage must use different secret keys"
            }
            require(config.avatar.cleanupEnabled) {
                "Production avatar storage must enable durable cleanup"
            }
            require(!config.recording.enabled || config.recording.cleanupEnabled) {
                "Enabled production recording must enable retention cleanup"
            }
            require(!config.recording.enabled || config.googleDrive.enabled) {
                "Enabled production recording must enable Google Drive archive"
            }
            require(config.realtime.provider == RealtimeProvider.REDIS) {
                "Production realtime delivery must use the Redis provider"
            }
            require(config.realtime.redisPassword.length >= 16) {
                "Production Redis password must contain at least 16 characters"
            }
            require(config.rateLimit.provider == RateLimitProvider.REDIS) {
                "Production authentication rate limiting must use the Redis provider"
            }
            require(config.rateLimit.redisPassword.length >= 16) {
                "Production rate limit Redis password must contain at least 16 characters"
            }
            require(config.database.password.length >= MIN_PRODUCTION_SECRET_LENGTH) {
                "Production database password must contain at least $MIN_PRODUCTION_SECRET_LENGTH characters"
            }
            require(config.liveKit.apiSecret.length >= MIN_PRODUCTION_SECRET_LENGTH) {
                "Production LiveKit API secret must contain at least $MIN_PRODUCTION_SECRET_LENGTH characters"
            }
            require(!config.liveKit.apiSecret.isPlaceholderSecret()) {
                "Placeholder LiveKit API secret is forbidden in production"
            }
            config.auth.seedUsers.forEach { user ->
                require(user.password.length >= MIN_PRODUCTION_PASSWORD_LENGTH) {
                    "Production seed user password must contain at least $MIN_PRODUCTION_PASSWORD_LENGTH characters"
                }
                require(user.password != user.username && !user.password.isPlaceholderSecret()) {
                    "Weak or placeholder seed user password is forbidden in production"
                }
            }
            if (config.recording.enabled) {
                require(config.recording.secretKey.length >= MIN_PRODUCTION_SECRET_LENGTH) {
                    "Production recording secret key must contain at least $MIN_PRODUCTION_SECRET_LENGTH characters"
                }
                require(!config.recording.secretKey.isPlaceholderSecret()) {
                    "Placeholder recording secret key is forbidden in production"
                }
            }
            require(config.avatar.secretKey.length >= MIN_PRODUCTION_SECRET_LENGTH) {
                "Production avatar secret key must contain at least $MIN_PRODUCTION_SECRET_LENGTH characters"
            }
            require(!config.avatar.secretKey.isPlaceholderSecret()) {
                "Placeholder avatar secret key is forbidden in production"
            }
        }
    }

    private fun String.isPlaceholderSecret(): Boolean {
        val normalized = lowercase()
        return normalized.startsWith("change-me") || normalized.startsWith("dev-") || normalized == "minioadmin"
    }

    private fun requireHttpEndpoint(value: String, label: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { error -> throw IllegalArgumentException("$label is not a valid URI", error) }
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "$label must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "$label must contain a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "$label must not contain user info, a query, or a fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "$label must not contain a path"
        }
        return uri
    }

    private const val EXPECTED_SEED_USER_COUNT = 2
    private const val MAX_DISPLAY_NAME_LENGTH = 100
    private const val MIN_PRODUCTION_PASSWORD_LENGTH = 8
    private const val MIN_PRODUCTION_SECRET_LENGTH = 16
    private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    private val USERNAME_PATTERN = Regex("[A-Za-z0-9._@+-]{1,128}")
}

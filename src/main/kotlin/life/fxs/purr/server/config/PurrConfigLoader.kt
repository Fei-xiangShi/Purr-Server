package life.fxs.purr.server.config

import io.ktor.server.config.ApplicationConfig

object PurrConfigLoader {
    fun load(config: ApplicationConfig): PurrServerConfig {
        return PurrServerConfig(
            auth = AuthConfig(
                accessTokenTtlSeconds = long(config, "purr.auth.accessTokenTtlSeconds", "PURR_AUTH_ACCESS_TOKEN_TTL_SECONDS"),
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
                provider = string(config, "purr.recording.provider", "PURR_RECORDING_PROVIDER"),
                idPrefix = string(config, "purr.recording.idPrefix", "PURR_RECORDING_ID_PREFIX"),
                filePrefix = string(config, "purr.recording.filePrefix", "PURR_RECORDING_FILE_PREFIX"),
                bucket = string(config, "purr.recording.bucket", "PURR_RECORDING_BUCKET"),
                endpoint = string(config, "purr.recording.endpoint", "PURR_RECORDING_ENDPOINT"),
                accessKey = string(config, "purr.recording.accessKey", "PURR_RECORDING_ACCESS_KEY"),
                secretKey = string(config, "purr.recording.secretKey", "PURR_RECORDING_SECRET_KEY"),
                region = string(config, "purr.recording.region", "PURR_RECORDING_REGION"),
                forcePathStyle = boolean(config, "purr.recording.forcePathStyle", "PURR_RECORDING_FORCE_PATH_STYLE"),
            ),
            database = DatabaseConfig(
                jdbcUrl = string(config, "purr.database.jdbcUrl", "PURR_DB_JDBC_URL"),
                driverClassName = string(config, "purr.database.driverClassName", "PURR_DB_DRIVER_CLASS_NAME"),
                username = string(config, "purr.database.username", "PURR_DB_USERNAME"),
                password = string(config, "purr.database.password", "PURR_DB_PASSWORD"),
                maximumPoolSize = int(config, "purr.database.maximumPoolSize", "PURR_DB_MAXIMUM_POOL_SIZE"),
            ),
        )
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
        return string(config, key, envKey).toBoolean()
    }

    private fun int(config: ApplicationConfig, key: String, envKey: String): Int {
        return string(config, key, envKey).toInt()
    }

    private fun long(config: ApplicationConfig, key: String, envKey: String): Long {
        return string(config, key, envKey).toLong()
    }
}

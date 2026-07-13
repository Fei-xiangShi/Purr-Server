package life.fxs.purr.server.service

import life.fxs.purr.server.auth.AuthContextResolver
import life.fxs.purr.server.application.account.AuthService
import life.fxs.purr.server.application.account.AvatarService
import life.fxs.purr.server.application.account.PasswordChangeService
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.account.ProfileService
import life.fxs.purr.server.application.port.PasswordHasher
import life.fxs.purr.server.application.port.PasswordVerifier
import life.fxs.purr.server.application.port.PresenceStore
import life.fxs.purr.server.application.port.RealtimeEventSink
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.auth.JwtTokenService
import life.fxs.purr.server.config.PurrServerConfig
import life.fxs.purr.server.config.RealtimeProvider
import life.fxs.purr.server.config.RecordingProvider
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.db.DatabaseResources
import life.fxs.purr.server.livekit.JwtLiveKitTokenService
import life.fxs.purr.server.livekit.LiveKitEgressRecordingControlService
import life.fxs.purr.server.livekit.LiveKitRoomParticipantService
import life.fxs.purr.server.livekit.LiveKitWebhookService
import life.fxs.purr.server.livekit.RecordingRecoveryService
import life.fxs.purr.server.livekit.RoomParticipantService
import life.fxs.purr.server.livekit.InMemoryRecordingController
import life.fxs.purr.server.repository.AuthSessionRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallRecordingConsentRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository
import life.fxs.purr.server.repository.PresenceRepository
import life.fxs.purr.server.realtime.RealtimeHub
import life.fxs.purr.server.realtime.BrokeredRealtimeEventPublisher
import life.fxs.purr.server.realtime.OutboxDispatcher
import life.fxs.purr.server.realtime.OutboxRepository
import life.fxs.purr.server.realtime.RedisRealtimeMessageBroker
import life.fxs.purr.server.recording.S3RecordingDownloadUrlProvider
import life.fxs.purr.server.recording.S3RecordingObjectStore
import life.fxs.purr.server.avatar.AvatarStorageConfig
import life.fxs.purr.server.avatar.S3AvatarObjectStore
import life.fxs.purr.server.recording.RecordingRetentionService
import life.fxs.purr.server.ratelimit.AuthRateLimiter
import life.fxs.purr.server.ratelimit.AuthRateLimiterFactory
import life.fxs.purr.server.redis.RedisClientResources
import life.fxs.purr.server.seed.BootstrapSeeder
import life.fxs.purr.server.application.call.CallAccessPolicy
import life.fxs.purr.server.application.call.CallSessionService
import life.fxs.purr.server.application.call.CallLifecycleService
import life.fxs.purr.server.application.call.CallHistoryQueryService
import life.fxs.purr.server.application.call.RecordingCommandService
import life.fxs.purr.server.application.call.RecordingQueryService
import org.mindrot.jbcrypt.BCrypt

data class ServerDependencies(
    val databaseResources: DatabaseResources,
    val authContextResolver: AuthContextResolver,
    val jwtTokenService: JwtTokenService,
    val authService: AuthService,
    val passwordChangeService: PasswordChangeService,
    val avatarService: AvatarService,
    val profileService: ProfileService,
    val pairService: PairService,
    val callSessionService: CallSessionService,
    val callHistoryQueryService: CallHistoryQueryService,
    val recordingCommandService: RecordingCommandService,
    val recordingQueryService: RecordingQueryService,
    val liveKitWebhookService: LiveKitWebhookService,
    val presenceStore: PresenceStore,
    val realtimeHub: RealtimeHub,
    val realtimeEventPublisher: RealtimeEventSink,
    val authRateLimiter: AuthRateLimiter,
    private val realtimeResource: AutoCloseable?,
    private val recordingDownloadResource: AutoCloseable,
    private val recordingObjectStoreResource: AutoCloseable,
    private val avatarObjectStoreResource: AutoCloseable,
    private val recordingRecoveryService: RecordingRecoveryService,
    private val recordingRetentionService: RecordingRetentionService,
    private val outboxDispatcher: OutboxDispatcher,
    private val redisResources: RedisClientResources,
) : AutoCloseable {
    override fun close() {
        var failure: Throwable? = null
        try {
            outboxDispatcher.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            recordingRetentionService.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            recordingRecoveryService.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            realtimeResource?.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            authRateLimiter.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            redisResources.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            recordingDownloadResource.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            recordingObjectStoreResource.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            avatarObjectStoreResource.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            (databaseResources.dataSource as? AutoCloseable)?.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }
}

object ServerDependenciesFactory {
    fun create(config: PurrServerConfig): ServerDependencies {
        val databaseResources = DatabaseFactory(config.database).connect()
        val redisResources = RedisClientResources()
        var realtimeResource: AutoCloseable? = null
        var authRateLimiterResource: AuthRateLimiter? = null
        var recordingDownloadResource: AutoCloseable? = null
        var recordingObjectStoreResource: AutoCloseable? = null
        var avatarObjectStoreResource: AutoCloseable? = null
        var recordingRecoveryService: RecordingRecoveryService? = null
        var recordingRetentionService: RecordingRetentionService? = null
        var outboxDispatcher: OutboxDispatcher? = null

        try {
            val userRepository = UserRepository()
            val pairBondRepository = PairBondRepository()
            val authSessionRepository = AuthSessionRepository()
            val callSessionRepository = CallSessionRepository()
            val callRecordingRepository = CallRecordingRepository()
            val callRecordingConsentRepository = CallRecordingConsentRepository()
            val presenceRepository = PresenceRepository()
            val applicationTransaction = databaseResources.applicationTransaction
            val outboxRepository = OutboxRepository()
            val realtimeHub = RealtimeHub()
            val realtimeEventPublisher = when (config.realtime.provider) {
                RealtimeProvider.LOCAL -> realtimeHub
                RealtimeProvider.REDIS -> BrokeredRealtimeEventPublisher(
                    broker = RedisRealtimeMessageBroker(config.realtime, redisResources),
                    localPublisher = realtimeHub,
                ).also { realtimeResource = it }
            }
            val authRateLimiter = AuthRateLimiterFactory.create(config.rateLimit, redisResources)
                .also { authRateLimiterResource = it }

            BootstrapSeeder(
                userRepository = userRepository,
                pairBondRepository = pairBondRepository,
            ).seed(config)

            val jwtTokenService = JwtTokenService(config.auth)
            val authContextResolver = AuthContextResolver()
            val authService = AuthService(
                refreshTokenTtlSeconds = config.auth.refreshTokenTtlSeconds,
                userAccountStore = userRepository,
                authSessionStore = authSessionRepository,
                accessTokenIssuer = jwtTokenService,
                passwordVerifier = PasswordVerifier(BCrypt::checkpw),
            )
            val passwordChangeService = PasswordChangeService(
                userAccountStore = userRepository,
                authSessionStore = authSessionRepository,
                passwordVerifier = PasswordVerifier(BCrypt::checkpw),
                passwordHasher = PasswordHasher { password ->
                    BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_LOG_ROUNDS))
                },
                transaction = applicationTransaction,
            )
            val profileService = ProfileService(
                userAccountStore = userRepository,
                transaction = applicationTransaction,
            )
            val pairService = PairService(
                pairStore = pairBondRepository,
                userAccountStore = userRepository,
                presenceStore = presenceRepository,
            )
            val tokenService = JwtLiveKitTokenService(config.liveKit)
            val recordingAdapters = when (config.recording.provider) {
                RecordingProvider.LIVEKIT -> RecordingAdapters(
                    controller = LiveKitEgressRecordingControlService(
                        liveKitConfig = config.liveKit,
                        recordingConfig = config.recording,
                    ),
                    participantService = LiveKitRoomParticipantService(config.liveKit),
                )
                RecordingProvider.IN_MEMORY -> RecordingAdapters(
                    controller = InMemoryRecordingController(config.recording),
                    participantService = null,
                )
            }
            val recordingController = recordingAdapters.controller
            val recordingDownloadUrlProvider = S3RecordingDownloadUrlProvider(config.recording)
                .also { recordingDownloadResource = it }
            val recordingObjectStore = S3RecordingObjectStore(config.recording)
                .also { recordingObjectStoreResource = it }
            val avatarObjectStore = S3AvatarObjectStore(
                AvatarStorageConfig(
                    bucket = config.recording.bucket,
                    endpoint = config.recording.endpoint,
                    publicEndpoint = config.recording.publicEndpoint,
                    accessKey = config.recording.accessKey,
                    secretKey = config.recording.secretKey,
                    region = config.recording.region,
                    forcePathStyle = config.recording.forcePathStyle,
                ),
            )
                .also { avatarObjectStoreResource = it }
            val avatarService = AvatarService(
                userAccountStore = userRepository,
                avatarObjectStore = avatarObjectStore,
                transaction = applicationTransaction,
            )
            val callAccessPolicy = CallAccessPolicy(
                pairService = pairService,
                callSessionStore = callSessionRepository,
            )
            val recordingCommandService = RecordingCommandService(
                callAccessPolicy = callAccessPolicy,
                pairService = pairService,
                callSessionStore = callSessionRepository,
                callRecordingStore = callRecordingRepository,
                recordingConsentStore = callRecordingConsentRepository,
                recordingController = recordingController,
                recordingEnabled = config.recording.enabled,
                consentPolicyVersion = config.recording.consentPolicyVersion,
            )
            val callSessionService = CallSessionService(
                pairService = pairService,
                callAccessPolicy = callAccessPolicy,
                callSessionStore = callSessionRepository,
                recordingConsentStore = callRecordingConsentRepository,
                mediaTokenIssuer = tokenService,
                mediaServerWsUrl = config.liveKit.wsUrl,
                recordingEnabled = config.recording.enabled,
                consentPolicyVersion = config.recording.consentPolicyVersion,
                transaction = applicationTransaction,
                realtimeOutbox = outboxRepository,
            )
            val callLifecycleService = CallLifecycleService(
                callSessionStore = callSessionRepository,
                pairStore = pairBondRepository,
                transaction = applicationTransaction,
                realtimeOutbox = outboxRepository,
            )
            val recordingQueryService = RecordingQueryService(
                callAccessPolicy = callAccessPolicy,
                callRecordingStore = callRecordingRepository,
                recordingDownloadProvider = recordingDownloadUrlProvider,
            )
            val callHistoryQueryService = CallHistoryQueryService(
                pairService = pairService,
                callSessionStore = callSessionRepository,
            )
            val liveKitWebhookService = LiveKitWebhookService(
                liveKitConfig = config.liveKit,
                callSessionRepository = callSessionRepository,
                callRecordingRepository = callRecordingRepository,
                callRecordingConsentRepository = callRecordingConsentRepository,
                pairBondRepository = pairBondRepository,
                recordingConfig = config.recording,
                recordingController = recordingController,
                callLifecycleService = callLifecycleService,
                roomParticipantService = recordingAdapters.participantService,
            )
            val recoveryService = RecordingRecoveryService(
                config = config.recording,
                callSessionRepository = callSessionRepository,
                callRecordingRepository = callRecordingRepository,
                recordingController = recordingController,
            ).also { it.start() }
            recordingRecoveryService = recoveryService
            val retentionService = RecordingRetentionService(
                config = config.recording,
                repository = callRecordingRepository,
                objectStore = recordingObjectStore,
            ).also { it.start() }
            recordingRetentionService = retentionService
            val dispatcher = OutboxDispatcher(
                config = config.outbox,
                repository = outboxRepository,
                eventSink = realtimeEventPublisher,
            ).also { it.start() }
            outboxDispatcher = dispatcher
            return ServerDependencies(
                databaseResources = databaseResources,
                authContextResolver = authContextResolver,
                jwtTokenService = jwtTokenService,
                authService = authService,
                passwordChangeService = passwordChangeService,
                avatarService = avatarService,
                profileService = profileService,
                pairService = pairService,
                callSessionService = callSessionService,
                callHistoryQueryService = callHistoryQueryService,
                recordingCommandService = recordingCommandService,
                recordingQueryService = recordingQueryService,
                liveKitWebhookService = liveKitWebhookService,
                presenceStore = presenceRepository,
                realtimeHub = realtimeHub,
                realtimeEventPublisher = realtimeEventPublisher,
                authRateLimiter = authRateLimiter,
                realtimeResource = realtimeResource,
                recordingDownloadResource = recordingDownloadUrlProvider,
                recordingObjectStoreResource = recordingObjectStore,
                avatarObjectStoreResource = avatarObjectStore,
                recordingRecoveryService = recoveryService,
                recordingRetentionService = retentionService,
                outboxDispatcher = dispatcher,
                redisResources = redisResources,
            )
        } catch (error: Throwable) {
            runCatching { outboxDispatcher?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingRecoveryService?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingRetentionService?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { realtimeResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { authRateLimiterResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { redisResources.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingDownloadResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingObjectStoreResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { avatarObjectStoreResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { (databaseResources.dataSource as? AutoCloseable)?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }
}

private data class RecordingAdapters(
    val controller: RecordingController,
    val participantService: RoomParticipantService?,
)

private const val BCRYPT_LOG_ROUNDS = 12

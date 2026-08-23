package life.fxs.purr.server.service

import life.fxs.purr.server.auth.AuthContextResolver
import life.fxs.purr.server.api.AvatarUploadAdmission
import life.fxs.purr.server.application.account.AuthService
import life.fxs.purr.server.application.account.AvatarService
import life.fxs.purr.server.application.account.PasswordChangeService
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.account.ProfileService
import life.fxs.purr.server.application.account.PushDeviceService
import life.fxs.purr.server.application.port.PasswordHasher
import life.fxs.purr.server.application.port.PasswordVerifier
import life.fxs.purr.server.application.port.PresenceStore
import life.fxs.purr.server.application.port.RealtimeEventSink
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.application.port.CallRoomTerminator
import life.fxs.purr.server.application.port.AvatarTelemetry
import life.fxs.purr.server.application.port.NoOpAvatarTelemetry
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
import life.fxs.purr.server.recording.RecordingCommandDispatcher
import life.fxs.purr.server.call.CallRoomReconciliationWorker
import life.fxs.purr.server.repository.AuthSessionRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallRecordingConsentRepository
import life.fxs.purr.server.repository.CallTelemetryRepository
import life.fxs.purr.server.repository.RecordingCommandRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository
import life.fxs.purr.server.repository.PresenceRepository
import life.fxs.purr.server.repository.WebhookInboxRepository
import life.fxs.purr.server.repository.PushDeviceRepository
import life.fxs.purr.server.realtime.RealtimeHub
import life.fxs.purr.server.realtime.BrokeredRealtimeEventPublisher
import life.fxs.purr.server.realtime.OutboxDispatcher
import life.fxs.purr.server.realtime.OutboxRepository
import life.fxs.purr.server.realtime.RedisRealtimeMessageBroker
import life.fxs.purr.server.push.CompositeRealtimeEventSink
import life.fxs.purr.server.push.FcmPushNotificationSender
import life.fxs.purr.server.push.IncomingCallPushEventSink
import life.fxs.purr.server.recording.S3RecordingDownloadUrlProvider
import life.fxs.purr.server.recording.S3RecordingObjectStore
import life.fxs.purr.server.avatar.AvatarCleanupRepository
import life.fxs.purr.server.avatar.AvatarCleanupService
import life.fxs.purr.server.avatar.AvatarCleanupWorker
import life.fxs.purr.server.avatar.AvatarOrphanReconciler
import life.fxs.purr.server.avatar.JvmAvatarImageProcessor
import life.fxs.purr.server.avatar.S3AvatarObjectStore
import life.fxs.purr.server.recording.RecordingRetentionService
import life.fxs.purr.server.recording.GoogleDriveRecordingArchive
import life.fxs.purr.server.recording.RecordingArchiveWorker
import life.fxs.purr.server.recording.RestoringRecordingDownloadProvider
import life.fxs.purr.server.ratelimit.AuthRateLimiter
import life.fxs.purr.server.ratelimit.AuthRateLimiterFactory
import life.fxs.purr.server.redis.RedisClientResources
import life.fxs.purr.server.seed.BootstrapSeeder
import java.util.concurrent.atomic.AtomicBoolean
import life.fxs.purr.server.application.call.CallAccessPolicy
import life.fxs.purr.server.application.call.CallSessionService
import life.fxs.purr.server.application.call.CallLifecycleService
import life.fxs.purr.server.application.call.CallRoomLifecycleService
import life.fxs.purr.server.application.call.CallRoomReconciliationService
import life.fxs.purr.server.application.call.CallRecordingWebhookService
import life.fxs.purr.server.application.call.CallHistoryQueryService
import life.fxs.purr.server.application.call.CallCalendarQueryService
import life.fxs.purr.server.application.call.CallDetailQueryService
import life.fxs.purr.server.application.call.CallTelemetryService
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
    val avatarUploadAdmission: AvatarUploadAdmission,
    val avatarStorageReadiness: () -> Boolean,
    val profileService: ProfileService,
    val pairService: PairService,
    val pushDeviceService: PushDeviceService,
    val callSessionService: CallSessionService,
    val callHistoryQueryService: CallHistoryQueryService,
    val callCalendarQueryService: CallCalendarQueryService,
    val callDetailQueryService: CallDetailQueryService,
    val callTelemetryService: CallTelemetryService,
    val recordingCommandService: RecordingCommandService,
    val recordingQueryService: RecordingQueryService,
    val liveKitWebhookService: LiveKitWebhookService,
    val presenceStore: PresenceStore,
    val realtimeHub: RealtimeHub,
    val realtimeEventPublisher: RealtimeEventSink,
    val durableEventSink: RealtimeEventSink,
    val authRateLimiter: AuthRateLimiter,
    private val realtimeResource: AutoCloseable?,
    private val recordingDownloadResource: AutoCloseable,
    private val recordingObjectStoreResource: AutoCloseable,
    private val avatarObjectStoreResource: AutoCloseable,
    private val avatarCleanupWorker: AvatarCleanupWorker,
    private val recordingRecoveryService: RecordingRecoveryService,
    private val recordingRetentionService: RecordingRetentionService,
    private val recordingArchiveWorker: RecordingArchiveWorker,
    private val googleDriveResource: AutoCloseable?,
    private val recordingCommandDispatcher: RecordingCommandDispatcher,
    private val callRoomReconciliationWorker: CallRoomReconciliationWorker?,
    private val outboxDispatcher: OutboxDispatcher,
    private val redisResources: RedisClientResources,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            callRoomReconciliationWorker?.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            recordingCommandDispatcher.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            outboxDispatcher.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            recordingRetentionService.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            recordingArchiveWorker.close()
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
            googleDriveResource?.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            avatarCleanupWorker.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            avatarObjectStoreResource.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        try {
            databaseResources.close()
        } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }
}

object ServerDependenciesFactory {
    fun create(
        config: PurrServerConfig,
        avatarTelemetry: AvatarTelemetry = NoOpAvatarTelemetry,
    ): ServerDependencies {
        val databaseResources = DatabaseFactory(config.database).connect()
        val redisResources = RedisClientResources()
        var realtimeResource: AutoCloseable? = null
        var authRateLimiterResource: AuthRateLimiter? = null
        var recordingDownloadResource: AutoCloseable? = null
        var recordingObjectStoreResource: AutoCloseable? = null
        var googleDriveResource: AutoCloseable? = null
        var avatarObjectStoreResource: AutoCloseable? = null
        var avatarCleanupWorkerResource: AvatarCleanupWorker? = null
        var recordingRecoveryService: RecordingRecoveryService? = null
        var recordingRetentionService: RecordingRetentionService? = null
        var recordingArchiveWorker: RecordingArchiveWorker? = null
        var recordingCommandDispatcher: RecordingCommandDispatcher? = null
        var callRoomReconciliationWorker: CallRoomReconciliationWorker? = null
        var outboxDispatcher: OutboxDispatcher? = null

        try {
            val avatarObjectStore = S3AvatarObjectStore(config.avatar)
                .also { avatarObjectStoreResource = it }
            val userRepository = UserRepository(avatarObjectStore::publicUrl)
            val pairBondRepository = PairBondRepository()
            val authSessionRepository = AuthSessionRepository()
            val callSessionRepository = CallSessionRepository()
            val callRecordingRepository = CallRecordingRepository()
            val webhookInboxRepository = WebhookInboxRepository()
            val recordingCommandRepository = RecordingCommandRepository(callRecordingRepository)
            val callRecordingConsentRepository = CallRecordingConsentRepository()
            val callTelemetryRepository = CallTelemetryRepository()
            val presenceRepository = PresenceRepository()
            val pushDeviceRepository = PushDeviceRepository()
            val avatarCleanupRepository = AvatarCleanupRepository()
            val applicationTransaction = databaseResources.applicationTransaction
            val outboxRepository = OutboxRepository()
            val realtimeHub = RealtimeHub()
            val realtimeEventPublisher = when (config.realtime.provider) {
                RealtimeProvider.LOCAL -> realtimeHub
                RealtimeProvider.REDIS -> BrokeredRealtimeEventPublisher(
                    broker = RedisRealtimeMessageBroker(config.realtime, redisResources),
                    localPublisher = realtimeHub,
                    onInboundOverflow = {
                        realtimeHub.closeAll(
                            io.ktor.websocket.CloseReason(
                                io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER,
                                "Realtime resynchronization required",
                            ),
                        )
                    },
                ).also { realtimeResource = it }
            }
            val authRateLimiter = AuthRateLimiterFactory.create(config.rateLimit, redisResources)
                .also { authRateLimiterResource = it }

            val pushSender = config.push.enabled.takeIf { it }
                ?.let { FcmPushNotificationSender(config.push) }
            val pushEventSink = IncomingCallPushEventSink(
                deviceStore = pushDeviceRepository,
                sender = pushSender ?: life.fxs.purr.server.application.port.PushNotificationSender { _, _ ->
                    life.fxs.purr.server.application.port.PushDeliveryResult.Delivered
                },
                enabled = config.push.enabled,
            )
            val durableEventSink = CompositeRealtimeEventSink(
                listOf(realtimeEventPublisher, pushEventSink),
            )

            BootstrapSeeder(
                userRepository = userRepository,
                pairBondRepository = pairBondRepository,
            ).seed(config)

            val jwtTokenService = JwtTokenService(config.auth)
            val authContextResolver = AuthContextResolver()
            val authService = AuthService(
                refreshTokenTtlSeconds = config.auth.refreshTokenTtlSeconds,
                userAccountReader = userRepository,
                authSessionStore = authSessionRepository,
                accessTokenIssuer = jwtTokenService,
                passwordVerifier = PasswordVerifier(BCrypt::checkpw),
            )
            val passwordChangeService = PasswordChangeService(
                userAccountReader = userRepository,
                userCredentialStore = userRepository,
                authSessionStore = authSessionRepository,
                passwordVerifier = PasswordVerifier(BCrypt::checkpw),
                passwordHasher = PasswordHasher { password ->
                    BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_LOG_ROUNDS))
                },
                transaction = applicationTransaction,
            )
            val profileService = ProfileService(
                userAccountReader = userRepository,
                userProfileStore = userRepository,
                transaction = applicationTransaction,
            )
            val pairService = PairService(
                pairStore = pairBondRepository,
                userAccountReader = userRepository,
                presenceStore = presenceRepository,
            )
            val pushDeviceService = PushDeviceService(pushDeviceRepository)
            val tokenService = JwtLiveKitTokenService(config.liveKit)
            val recordingAdapters = when (config.recording.provider) {
                RecordingProvider.LIVEKIT -> {
                    val controller = LiveKitEgressRecordingControlService(
                        liveKitConfig = config.liveKit,
                        recordingConfig = config.recording,
                    )
                    RecordingAdapters(
                        controller = controller,
                        participantService = LiveKitRoomParticipantService(config.liveKit),
                        roomTerminator = controller,
                    )
                }
                RecordingProvider.IN_MEMORY -> {
                    val controller = InMemoryRecordingController(config.recording)
                    RecordingAdapters(
                        controller = controller,
                        participantService = null,
                        roomTerminator = controller,
                    )
                }
            }
            val recordingController = recordingAdapters.controller
            val roomTerminator = recordingAdapters.roomTerminator
            val commandDispatcher = RecordingCommandDispatcher(
                config = config.outbox,
                repository = recordingCommandRepository,
                callSessionStore = callSessionRepository,
                recordingController = recordingController,
                roomTerminator = roomTerminator,
            ).also { it.start() }
            recordingCommandDispatcher = commandDispatcher
            val recordingObjectStore = S3RecordingObjectStore(config.recording)
                .also { recordingObjectStoreResource = it }
            val googleDriveArchive = config.googleDrive.enabled.takeIf { it }
                ?.let { GoogleDriveRecordingArchive(config.googleDrive) }
                .also { googleDriveResource = it }
            val recordingDownloadUrlProvider = if (googleDriveArchive != null) {
                RestoringRecordingDownloadProvider(
                    config = config.googleDrive,
                    delegate = S3RecordingDownloadUrlProvider(config.recording),
                    repository = callRecordingRepository,
                    objectRestorer = recordingObjectStore,
                    archiveDownloader = googleDriveArchive,
                )
            } else {
                S3RecordingDownloadUrlProvider(config.recording)
            }.also { recordingDownloadResource = it }
            val archiveWorker = RecordingArchiveWorker(
                config = config.googleDrive,
                repository = callRecordingRepository,
                objectReader = recordingObjectStore,
                uploader = googleDriveArchive,
            ).also { it.start() }
            recordingArchiveWorker = archiveWorker
            val avatarService = AvatarService(
                userAccountReader = userRepository,
                userProfileStore = userRepository,
                imageProcessor = JvmAvatarImageProcessor(config.avatar),
                avatarObjectUploader = avatarObjectStore,
                avatarObjectDeleter = avatarObjectStore,
                cleanupQueue = avatarCleanupRepository,
                transaction = applicationTransaction,
                telemetry = avatarTelemetry,
            )
            val callAccessPolicy = CallAccessPolicy(
                pairService = pairService,
                callSessionStore = callSessionRepository,
            )
            val recordingCommandService = RecordingCommandService(
                callAccessPolicy = callAccessPolicy,
                pairService = pairService,
                callSessionStore = callSessionRepository,
                recordingConsentStore = callRecordingConsentRepository,
                recordingEnabled = config.recording.enabled,
                consentPolicyVersion = config.recording.consentPolicyVersion,
                recordingCommandStore = recordingCommandRepository,
                transaction = applicationTransaction,
                recordingCommandProcessor = commandDispatcher,
            )
            val callLifecycleService = CallLifecycleService(
                callSessionStore = callSessionRepository,
                pairStore = pairBondRepository,
                transaction = applicationTransaction,
                realtimeOutbox = outboxRepository,
            )
            val roomParticipantReader = recordingAdapters.participantService
                ?: config.callReconciliation.enabled.takeIf { it }
                    ?.let { LiveKitRoomParticipantService(config.liveKit) }
            val callRoomLifecycleService = CallRoomLifecycleService(
                callSessionStore = callSessionRepository,
                recordingConsentStore = callRecordingConsentRepository,
                pairStore = pairBondRepository,
                callLifecycleService = callLifecycleService,
                recordingEnabled = config.recording.enabled,
                consentPolicyVersion = config.recording.consentPolicyVersion,
                participantReader = roomParticipantReader,
                recordingCommandStore = recordingCommandRepository,
                transaction = applicationTransaction,
                recordingCommandWakeup = commandDispatcher,
                recordingCommandProcessor = commandDispatcher,
                roomTerminator = roomTerminator,
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
            val callRecordingWebhookService = CallRecordingWebhookService(
                callSessionStore = callSessionRepository,
                callRecordingStore = callRecordingRepository,
                recordingCommandStore = recordingCommandRepository,
                transaction = applicationTransaction,
                recordingCommandWakeup = commandDispatcher,
                recordingArchiveWakeup = archiveWorker,
                roomTerminator = roomTerminator,
            )
            val reconciliationWorker = roomParticipantReader?.let { reader ->
                CallRoomReconciliationWorker(
                    config = config.callReconciliation,
                    service = CallRoomReconciliationService(
                        store = callSessionRepository,
                        participantReader = reader,
                        roomEventHandler = callRoomLifecycleService,
                        waitingCallTerminator = callLifecycleService,
                        waitingTtlMillis = config.callReconciliation.waitingTtlSeconds * 1_000L,
                        emptyRoomGraceMillis = config.callReconciliation.emptyRoomGraceSeconds * 1_000L,
                        batchSize = config.callReconciliation.batchSize,
                        roomTerminator = roomTerminator,
                        recordingCommandStore = recordingCommandRepository,
                    ),
                ).also { it.start() }
            }
            callRoomReconciliationWorker = reconciliationWorker
            val recordingQueryService = RecordingQueryService(
                callAccessPolicy = callAccessPolicy,
                callRecordingStore = callRecordingRepository,
                recordingDownloadProvider = recordingDownloadUrlProvider,
            )
            val callHistoryQueryService = CallHistoryQueryService(
                pairService = pairService,
                callSessionStore = callSessionRepository,
            )
            val callCalendarQueryService = CallCalendarQueryService(
                pairService = pairService,
                callSessionStore = callSessionRepository,
            )
            val callDetailQueryService = CallDetailQueryService(
                callAccessPolicy = callAccessPolicy,
                callRecordingStore = callRecordingRepository,
                callTelemetryStore = callTelemetryRepository,
            )
            val callTelemetryService = CallTelemetryService(
                callAccessPolicy = callAccessPolicy,
                callTelemetryStore = callTelemetryRepository,
            )
            val liveKitWebhookService = LiveKitWebhookService(
                liveKitConfig = config.liveKit,
                callRoomLifecycleService = callRoomLifecycleService,
                callRecordingWebhookService = callRecordingWebhookService,
                webhookInboxStore = webhookInboxRepository,
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
                eventSink = durableEventSink,
            ).also { it.start() }
            outboxDispatcher = dispatcher
            val avatarOrphanReconciler = AvatarOrphanReconciler(
                config = config.avatar,
                cleanupQueue = avatarCleanupRepository,
                objectCatalog = avatarObjectStore,
                referenceReader = userRepository,
            )
            val avatarCleanupService = AvatarCleanupService(
                config = config.avatar,
                taskStore = avatarCleanupRepository,
                objectDeleter = avatarObjectStore,
                orphanReconciler = avatarOrphanReconciler,
                telemetry = avatarTelemetry,
            )
            val avatarCleanupWorker = AvatarCleanupWorker(config.avatar, avatarCleanupService)
                .also { it.start() }
            avatarCleanupWorkerResource = avatarCleanupWorker
            return ServerDependencies(
                databaseResources = databaseResources,
                authContextResolver = authContextResolver,
                jwtTokenService = jwtTokenService,
                authService = authService,
                passwordChangeService = passwordChangeService,
                avatarService = avatarService,
                avatarUploadAdmission = AvatarUploadAdmission(config.avatar.maxConcurrentProcessing),
                avatarStorageReadiness = avatarObjectStore::isReady,
                profileService = profileService,
                pairService = pairService,
                pushDeviceService = pushDeviceService,
                callSessionService = callSessionService,
                callHistoryQueryService = callHistoryQueryService,
                callCalendarQueryService = callCalendarQueryService,
                callDetailQueryService = callDetailQueryService,
                callTelemetryService = callTelemetryService,
                recordingCommandService = recordingCommandService,
                recordingQueryService = recordingQueryService,
                liveKitWebhookService = liveKitWebhookService,
                presenceStore = presenceRepository,
                realtimeHub = realtimeHub,
                realtimeEventPublisher = realtimeEventPublisher,
                durableEventSink = durableEventSink,
                authRateLimiter = authRateLimiter,
                realtimeResource = realtimeResource,
                recordingDownloadResource = recordingDownloadUrlProvider,
                recordingObjectStoreResource = recordingObjectStore,
                avatarObjectStoreResource = avatarObjectStore,
                avatarCleanupWorker = avatarCleanupWorker,
                recordingRecoveryService = recoveryService,
                recordingRetentionService = retentionService,
                recordingArchiveWorker = archiveWorker,
                googleDriveResource = googleDriveArchive,
                recordingCommandDispatcher = commandDispatcher,
                callRoomReconciliationWorker = reconciliationWorker,
                outboxDispatcher = dispatcher,
                redisResources = redisResources,
            )
        } catch (error: Throwable) {
            runCatching { callRoomReconciliationWorker?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingCommandDispatcher?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { outboxDispatcher?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingRecoveryService?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingRetentionService?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { recordingArchiveWorker?.close() }
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
            runCatching { googleDriveResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { avatarCleanupWorkerResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { avatarObjectStoreResource?.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            runCatching { databaseResources.close() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }
}

private data class RecordingAdapters(
    val controller: RecordingController,
    val participantService: RoomParticipantService?,
    val roomTerminator: CallRoomTerminator,
)

private const val BCRYPT_LOG_ROUNDS = 12

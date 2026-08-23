package life.fxs.purr.server.service

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.config.RecordingProvider
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.MediaTokenIssuer
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.application.call.CallAccessPolicy
import life.fxs.purr.server.application.call.CallSessionService
import life.fxs.purr.server.application.call.RecordingCommandService
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CallSessionResult
import life.fxs.purr.server.application.model.CreateCallSessionCommand
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallRecordingConsentRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository
import life.fxs.purr.server.repository.RecordingCommandRepository

class CallApplicationServicesTest {
    @Test
    fun `concurrent callers join one active call`() {
        val databaseResources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:concurrent-call-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 4,
            ),
        ).connect()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val userRepository = UserRepository()
            val pairBondRepository = PairBondRepository()
            userRepository.insertIfAbsent("user-a", "user-a", "pass-a", "User A", null)
            userRepository.insertIfAbsent("user-b", "user-b", "pass-b", "User B", null)
            pairBondRepository.insertIfAbsent("pair-demo", "user-a", "user-b", 1L)

            val service = createTestServices(
                pairService = PairService(pairBondRepository, userRepository),
                callSessionRepository = CallSessionRepository(),
                callRecordingRepository = CallRecordingRepository(),
                callRecordingConsentRepository = CallRecordingConsentRepository(),
                mediaTokenIssuer = FakeMediaTokenIssuer(),
                recordingController = FakeRecordingController(
                    ProviderRecordingResult(RecordingStatus.STOPPED, null, 1L),
                ),
                liveKitConfig = LiveKitConfig(
                    wsUrl = "ws://localhost:7880",
                    httpUrl = "http://localhost:7880",
                    apiKey = "devkey",
                    apiSecret = "devsecret",
                    tokenTtlSeconds = 3600,
                ),
                recordingConfig = testRecordingConfig(enabled = false),
            ).callSessionService
            val start = CountDownLatch(1)
            val futures = listOf("user-a", "user-b").map { userId ->
                executor.submit<CallSessionResult> {
                    check(start.await(5, TimeUnit.SECONDS))
                    service.createSession(
                        userId,
                        CreateCallSessionCommand("pair-demo", expectedCallId = null, recordingConsent = false),
                    )
                }
            }

            start.countDown()
            val sessions = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, sessions.map { it.callId }.distinct().size)
            assertEquals(1, sessions.map { it.roomName }.distinct().size)
        } finally {
            executor.shutdownNow()
            (databaseResources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `explicit end acknowledges local hangup without terminating the shared call`() {
        val databaseResources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-service-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val userRepository = UserRepository()
            val pairBondRepository = PairBondRepository()
            userRepository.insertIfAbsent("user-a", "user-a", "pass-a", "User A", null)
            userRepository.insertIfAbsent("user-b", "user-b", "pass-b", "User B", null)
            pairBondRepository.insertIfAbsent("pair-demo", "user-a", "user-b", 1L)

            val repository = CallSessionRepository()
            val now = Instant.parse("2026-03-19T14:00:00Z")
            val stopTime = now.plusSeconds(5)
            val callId = "call-test"
            val roomName = "pair-demo-call-test"

            repository.upsert(
                CallRecord(
                    callId = callId,
                    pairId = "pair-demo",
                    roomName = roomName,
                    createdByUserId = "user-a",
                    startedAtEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                    state = CallState.ACTIVE,
                    recordingStatus = RecordingStatus.STARTING,
                    recordingId = "egress-1",
                ),
            )

            val recordingController = FakeRecordingController(
                stopResult = ProviderRecordingResult(
                    status = RecordingStatus.STOPPING,
                    recordingId = "egress-1",
                    updatedAtEpochMillis = stopTime.toEpochMilli(),
                ),
            )

            val service = createTestServices(
                pairService = PairService(pairBondRepository, userRepository),
                callSessionRepository = repository,
                callRecordingRepository = CallRecordingRepository(),
                callRecordingConsentRepository = CallRecordingConsentRepository(),
                mediaTokenIssuer = FakeMediaTokenIssuer(),
                recordingController = recordingController,
                liveKitConfig = LiveKitConfig(
                    wsUrl = "ws://localhost:7880",
                    httpUrl = "http://localhost:7880",
                    apiKey = "devkey",
                    apiSecret = "devsecret",
                    tokenTtlSeconds = 3600,
                ),
                recordingConfig = testRecordingConfig(enabled = false),
                nowProvider = { stopTime },
            ).callSessionService

            service.endCall(userId = "user-a", callId = callId)

            assertEquals(0, recordingController.stopCalls.size)

            val stored = repository.find(callId) ?: error("call not found")
            assertEquals(CallState.ACTIVE, stored.state)
            assertEquals(RecordingStatus.STARTING, stored.recordingStatus)
            assertEquals("egress-1", stored.recordingId)
            assertEquals(null, stored.endedAtEpochMillis)

            val nextSession = service.createSession(
                userId = "user-a",
                command = CreateCallSessionCommand(
                    pairId = "pair-demo",
                    expectedCallId = null,
                    recordingConsent = false,
                ),
            )
            assertEquals(callId, nextSession.callId)
        } finally {
            (databaseResources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `concurrent recording starts create only one provider recording`() {
        val databaseResources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:concurrent-recording-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 4,
            ),
        ).connect()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val userRepository = UserRepository()
            val pairBondRepository = PairBondRepository()
            userRepository.insertIfAbsent("user-a", "user-a", "pass-a", "User A", null)
            userRepository.insertIfAbsent("user-b", "user-b", "pass-b", "User B", null)
            pairBondRepository.insertIfAbsent("pair-demo", "user-a", "user-b", 1L)

            val callRepository = CallSessionRepository()
            val consentRepository = CallRecordingConsentRepository()
            val callId = "call-concurrent-recording"
            val now = Instant.parse("2026-07-10T12:00:00Z")
            callRepository.upsert(
                CallRecord(
                    callId = callId,
                    pairId = "pair-demo",
                    roomName = "pair-demo-$callId",
                    createdByUserId = "user-a",
                    startedAtEpochMillis = now.minusSeconds(30).toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                    state = CallState.ACTIVE,
                    recordingStatus = RecordingStatus.IDLE,
                    connectedAtEpochMillis = now.minusSeconds(30).toEpochMilli(),
                ),
            )
            consentRepository.record(callId, "user-a", "test-v1", now.toEpochMilli())
            consentRepository.record(callId, "user-b", "test-v1", now.toEpochMilli())
            val recordingController = FakeRecordingController(
                stopResult = ProviderRecordingResult(RecordingStatus.STOPPED, null, now.toEpochMilli()),
                startResult = ProviderRecordingResult(
                    status = RecordingStatus.RECORDING,
                    recordingId = "egress-one",
                    updatedAtEpochMillis = now.plusSeconds(1).toEpochMilli(),
                ),
            )
            val service = createTestServices(
                pairService = PairService(pairBondRepository, userRepository),
                callSessionRepository = callRepository,
                callRecordingRepository = CallRecordingRepository(),
                callRecordingConsentRepository = consentRepository,
                mediaTokenIssuer = FakeMediaTokenIssuer(),
                recordingController = recordingController,
                liveKitConfig = testLiveKitConfig(),
                recordingConfig = testRecordingConfig(enabled = true),
                nowProvider = { now },
            ).recordingCommandService
            val start = CountDownLatch(1)
            val futures = List(2) {
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching { service.startRecording("user-a", callId) }.isSuccess
                }
            }

            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(2, outcomes.count { it })
            assertEquals(0, recordingController.startCalls.size)
        } finally {
            executor.shutdownNow()
            (databaseResources.dataSource as? AutoCloseable)?.close()
        }
    }
}

private fun testRecordingConfig(enabled: Boolean) = RecordingConfig(
    enabled = enabled,
    provider = RecordingProvider.IN_MEMORY,
    idPrefix = "rec",
    filePrefix = "recordings",
    bucket = "purr-recordings",
    endpoint = "http://localhost:9000",
    publicEndpoint = "http://localhost:9000",
    accessKey = "key",
    secretKey = "secret",
    region = "us-east-1",
    forcePathStyle = true,
    recoveryEnabled = false,
    recoveryIntervalSeconds = 30,
    recoveryStaleAfterSeconds = 90,
    recoveryMaxAttempts = 5,
    downloadUrlTtlSeconds = 300,
    consentPolicyVersion = "test-v1",
    cleanupEnabled = false,
    retentionDays = 30,
    cleanupIntervalSeconds = 3600,
    cleanupBatchSize = 100,
    cleanupMaxAttempts = 10,
)

private fun testLiveKitConfig() = LiveKitConfig(
    wsUrl = "ws://localhost:7880",
    httpUrl = "http://localhost:7880",
    apiKey = "devkey",
    apiSecret = "devsecret",
    tokenTtlSeconds = 3600,
)

private data class TestCallServices(
    val callSessionService: CallSessionService,
    val recordingCommandService: RecordingCommandService,
)

private fun createTestServices(
    pairService: PairService,
    callSessionRepository: CallSessionRepository,
    callRecordingRepository: CallRecordingRepository,
    callRecordingConsentRepository: CallRecordingConsentRepository,
    mediaTokenIssuer: MediaTokenIssuer,
    recordingController: RecordingController,
    liveKitConfig: LiveKitConfig,
    recordingConfig: RecordingConfig,
    nowProvider: () -> Instant = Instant::now,
): TestCallServices {
    val accessPolicy = CallAccessPolicy(pairService, callSessionRepository)
    val recordingCommandService = RecordingCommandService(
        callAccessPolicy = accessPolicy,
        pairService = pairService,
        callSessionStore = callSessionRepository,
        recordingConsentStore = callRecordingConsentRepository,
        recordingEnabled = recordingConfig.enabled,
        consentPolicyVersion = recordingConfig.consentPolicyVersion,
        nowProvider = nowProvider,
        recordingCommandStore = RecordingCommandRepository(callRecordingRepository) { "command-${System.nanoTime()}" },
    )
    val callSessionService = CallSessionService(
        pairService = pairService,
        callAccessPolicy = accessPolicy,
        callSessionStore = callSessionRepository,
        recordingConsentStore = callRecordingConsentRepository,
        mediaTokenIssuer = mediaTokenIssuer,
        mediaServerWsUrl = liveKitConfig.wsUrl,
        recordingEnabled = recordingConfig.enabled,
        consentPolicyVersion = recordingConfig.consentPolicyVersion,
        transaction = ImmediateTransaction,
        realtimeOutbox = RealtimeOutbox { _, _, _ -> },
        nowProvider = nowProvider,
    )
    return TestCallServices(callSessionService, recordingCommandService)
}

private class FakeMediaTokenIssuer : MediaTokenIssuer {
    override fun issueAccessToken(roomName: String, participantIdentity: String): String = "token"
}

private object ImmediateTransaction : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = block()
}

private class FakeRecordingController(
    private val stopResult: ProviderRecordingResult,
    private val startResult: ProviderRecordingResult? = null,
) : RecordingController {
    data class StopCall(
        val callId: String,
        val roomName: String,
        val recordingId: String?,
    )

    val startCalls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val stopCalls = Collections.synchronizedList(mutableListOf<StopCall>())

    override fun startRecording(callId: String, roomName: String, operationId: String): ProviderRecordingResult {
        startCalls += callId to roomName
        return startResult ?: error("Not used in this test")
    }

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
        operationId: String,
    ): ProviderRecordingResult {
        stopCalls += StopCall(callId, roomName, currentRecordingId)
        return stopResult
    }
}

package life.fxs.purr.server.recording

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.OutboxConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.RecordingCommandRepository
import life.fxs.purr.server.repository.UserRepository

class RecordingCommandDispatcherTest {
    @Test
    fun `call ending before thirty seconds completes delayed start without provider IO`() = withDatabase {
        seedPair()
        val callRepository = CallSessionRepository()
        callRepository.upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = PAIR_ID,
                roomName = ROOM_NAME,
                createdByUserId = "user-a",
                startedAtEpochMillis = 100L,
                updatedAtEpochMillis = 30_099L,
                state = CallState.ENDED,
                recordingStatus = RecordingStatus.IDLE,
                endedAtEpochMillis = 30_099L,
                connectedAtEpochMillis = 100L,
                durationMillis = 29_999L,
            ),
        )
        val ids = AtomicInteger()
        val commandRepository = RecordingCommandRepository(
            callRecordingRepository = CallRecordingRepository(),
            commandIdProvider = { "command-${ids.incrementAndGet()}" },
        )
        commandRepository.enqueueStart(
            CALL_ID,
            ROOM_NAME,
            requestedAtEpochMillis = 100L,
            availableAtEpochMillis = 30_100L,
        )
        val controller = RecordingControllerSpy()
        val dispatcher = RecordingCommandDispatcher(
            config = outboxConfig(),
            repository = commandRepository,
            callSessionStore = callRepository,
            recordingController = controller,
            workerId = "worker-1",
        )

        val startSummary = dispatcher.dispatchOnce(Instant.ofEpochMilli(30_100L))

        assertEquals(1, startSummary.claimed)
        assertEquals(1, startSummary.succeeded)
        assertEquals(0, controller.startCalls)
        assertEquals(0, controller.stopCalls)
        assertEquals(RecordingStatus.IDLE, callRepository.find(CALL_ID)?.recordingStatus)
        assertEquals(null, callRepository.find(CALL_ID)?.recordingId)
        dispatcher.close()
    }

    @Test
    fun `delayed start becomes executable at exactly thirty seconds`() = withDatabase {
        seedPair()
        val callRepository = CallSessionRepository()
        callRepository.upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = PAIR_ID,
                roomName = ROOM_NAME,
                createdByUserId = "user-a",
                startedAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
                state = CallState.ACTIVE,
                recordingStatus = RecordingStatus.IDLE,
                connectedAtEpochMillis = 100L,
            ),
        )
        val commandRepository = RecordingCommandRepository(
            callRecordingRepository = CallRecordingRepository(),
            commandIdProvider = { "command-1" },
        )
        commandRepository.enqueueStart(
            CALL_ID,
            ROOM_NAME,
            requestedAtEpochMillis = 100L,
            availableAtEpochMillis = 30_100L,
        )
        val controller = StartingRecordingController()
        val dispatcher = RecordingCommandDispatcher(
            config = outboxConfig(),
            repository = commandRepository,
            callSessionStore = callRepository,
            recordingController = controller,
            workerId = "worker-1",
        )

        val early = dispatcher.dispatchOnce(Instant.ofEpochMilli(30_099L))
        val eligible = dispatcher.dispatchOnce(Instant.ofEpochMilli(30_100L))
        val duplicate = dispatcher.dispatchOnce(Instant.ofEpochMilli(30_101L))

        assertEquals(0, early.claimed)
        assertEquals(1, eligible.claimed)
        assertEquals(1, eligible.succeeded)
        assertEquals(0, duplicate.claimed)
        assertEquals(1, controller.startCalls)
        assertEquals(RecordingStatus.RECORDING, callRepository.find(CALL_ID)?.recordingStatus)
        dispatcher.close()
    }

    @Test
    fun `accepted start is reconciled before stop and provider clock cannot hide its id`() = withDatabase {
        seedPair()
        val callRepository = CallSessionRepository()
        callRepository.upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = PAIR_ID,
                roomName = ROOM_NAME,
                createdByUserId = "user-a",
                startedAtEpochMillis = 100L,
                updatedAtEpochMillis = 10_000L,
                state = CallState.ENDED,
                recordingStatus = RecordingStatus.STOPPING,
                endedAtEpochMillis = 10_000L,
                connectedAtEpochMillis = 100L,
            ),
        )
        val ids = AtomicInteger()
        val commandRepository = RecordingCommandRepository(
            callRecordingRepository = CallRecordingRepository(),
            commandIdProvider = { "command-${ids.incrementAndGet()}" },
        )
        commandRepository.enqueueStart(CALL_ID, ROOM_NAME, requestedAtEpochMillis = 100L)
        commandRepository.enqueueStop(CALL_ID, ROOM_NAME, recordingId = null, requestedAtEpochMillis = 200L)
        val controller = ReconciledRecordingController()
        val dispatcher = RecordingCommandDispatcher(
            config = outboxConfig(),
            repository = commandRepository,
            callSessionStore = callRepository,
            recordingController = controller,
            workerId = "worker-1",
        )

        val startSummary = dispatcher.dispatchOnce(Instant.ofEpochMilli(20_000L))
        val afterStart = assertNotNull(callRepository.find(CALL_ID))
        val stopSummary = dispatcher.dispatchOnce(Instant.ofEpochMilli(20_001L))

        assertEquals(1, startSummary.claimed)
        assertEquals("egress-1", afterStart.recordingId)
        assertEquals(1_000L, afterStart.recordingProviderUpdatedAtEpochMillis)
        assertEquals(1, stopSummary.claimed)
        assertEquals(listOf("egress-1"), controller.stopCalls)
        assertEquals(RecordingStatus.STOPPED, callRepository.find(CALL_ID)?.recordingStatus)
        dispatcher.close()
    }

    @Test
    fun `crash after final claim remains reclaimable after lease expiry`() = withDatabase {
        seedPair()
        val callRepository = CallSessionRepository()
        callRepository.upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = PAIR_ID,
                roomName = ROOM_NAME,
                createdByUserId = "user-a",
                startedAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
                state = CallState.ACTIVE,
                recordingStatus = RecordingStatus.STARTING,
                connectedAtEpochMillis = 100L,
            ),
        )
        val repository = RecordingCommandRepository(
            callRecordingRepository = CallRecordingRepository(),
            commandIdProvider = { "command-1" },
        )
        repository.enqueueStart(CALL_ID, ROOM_NAME, requestedAtEpochMillis = 100L)

        val abandoned = repository.claimBatch(
            workerId = "worker-a",
            nowEpochMillis = 1_000L,
            leaseUntilEpochMillis = 2_000L,
            maxAttempts = 1,
            limit = 10,
        )
        val recovered = repository.claimBatch(
            workerId = "worker-b",
            nowEpochMillis = 2_001L,
            leaseUntilEpochMillis = 3_001L,
            maxAttempts = 1,
            limit = 10,
        )

        assertEquals(1, abandoned.size)
        assertEquals(0, abandoned.single().attemptCount)
        assertEquals(1, recovered.size)
        assertEquals(abandoned.single().commandId, recovered.single().commandId)
        assertEquals("worker-b", recovered.single().leaseOwner)
    }

    private fun seedPair() {
        val users = UserRepository()
        users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
        users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
        PairBondRepository().insertIfAbsent(PAIR_ID, "user-a", "user-b", bondedAtEpochMillis = 1L)
    }

    private fun outboxConfig() = OutboxConfig(
        pollIntervalMillis = 100,
        batchSize = 100,
        leaseSeconds = 30,
        maxAttempts = 3,
        retryBaseSeconds = 1,
        retryMaxSeconds = 10,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:recording-command-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            block()
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private class RecordingControllerSpy : RecordingController {
        var startCalls = 0
        var stopCalls = 0

        override fun startRecording(callId: String, roomName: String): ProviderRecordingResult {
            startCalls++
            error("Provider start must not be called")
        }

        override fun stopRecording(
            callId: String,
            roomName: String,
            currentRecordingId: String?,
        ): ProviderRecordingResult {
            stopCalls++
            error("Provider stop must not be called")
        }
    }

    private class ReconciledRecordingController : RecordingController {
        val stopCalls = mutableListOf<String>()

        override fun startRecording(callId: String, roomName: String): ProviderRecordingResult = error("Not used")

        override fun findRecordingForOperation(
            callId: String,
            roomName: String,
            operationId: String,
        ) = ProviderRecordingResult(
            status = RecordingStatus.RECORDING,
            recordingId = "egress-1",
            updatedAtEpochMillis = 1_000L,
        )

        override fun stopRecording(
            callId: String,
            roomName: String,
            currentRecordingId: String?,
        ): ProviderRecordingResult {
            val id = assertNotNull(currentRecordingId)
            stopCalls += id
            return ProviderRecordingResult(
                status = RecordingStatus.STOPPED,
                recordingId = id,
                updatedAtEpochMillis = 2_000L,
            )
        }
    }

    private class StartingRecordingController : RecordingController {
        var startCalls = 0

        override fun startRecording(callId: String, roomName: String): ProviderRecordingResult {
            startCalls++
            return ProviderRecordingResult(
                status = RecordingStatus.RECORDING,
                recordingId = "egress-1",
                updatedAtEpochMillis = 30_100L,
            )
        }

        override fun stopRecording(
            callId: String,
            roomName: String,
            currentRecordingId: String?,
        ): ProviderRecordingResult = error("Not used")
    }

    private companion object {
        const val CALL_ID = "call-1"
        const val PAIR_ID = "pair-1"
        const val ROOM_NAME = "pair-1-call-1"
    }
}

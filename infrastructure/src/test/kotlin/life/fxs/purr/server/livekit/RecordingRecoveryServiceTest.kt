package life.fxs.purr.server.livekit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.config.RecordingProvider
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class RecordingRecoveryServiceTest {
    @Test
    fun `ended call with active provider recording is stopped`() = withDatabase {
        val callRepository = CallSessionRepository()
        val recordingRepository = CallRecordingRepository()
        seedCall(
            callRepository,
            state = CallState.ENDED,
            recordingStatus = RecordingStatus.STOPPING,
            recordingId = "egress-1",
        )
        val control = RecoveryRecordingControl(
            providerResult = ProviderRecordingResult(
                status = RecordingStatus.RECORDING,
                recordingId = "egress-1",
                updatedAtEpochMillis = 2L,
            ),
            stopResult = ProviderRecordingResult(
                status = RecordingStatus.STOPPED,
                recordingId = "egress-1",
                updatedAtEpochMillis = NOW.toEpochMilli(),
                endedAtEpochMillis = NOW.toEpochMilli(),
            ),
        )
        val service = recoveryService(callRepository, recordingRepository, control)

        val summary = service.recoverOnce(NOW)

        assertEquals(1, summary.claimed)
        assertEquals(1, summary.recovered)
        assertEquals(listOf("egress-1"), control.stopCalls)
        val call = assertNotNull(callRepository.find(CALL_ID))
        assertEquals(RecordingStatus.STOPPED, call.recordingStatus)
        assertEquals(RecordingStatus.STOPPED, recordingRepository.findByRecordingId("egress-1")?.status)
        service.close()
    }

    @Test
    fun `missing provider recording becomes failed after bounded retries`() = withDatabase {
        val callRepository = CallSessionRepository()
        val recordingRepository = CallRecordingRepository()
        seedCall(
            callRepository,
            state = CallState.ACTIVE,
            recordingStatus = RecordingStatus.STARTING,
            recordingId = "egress-missing",
        )
        val control = RecoveryRecordingControl(providerResult = null)
        val service = recoveryService(callRepository, recordingRepository, control)

        val first = service.recoverOnce(NOW)
        val afterFirst = assertNotNull(callRepository.find(CALL_ID))
        assertEquals(1, first.claimed)
        assertEquals(RecordingStatus.STARTING, afterFirst.recordingStatus)
        assertEquals(1, afterFirst.recordingRecoveryAttempts)
        assertTrue(afterFirst.recordingErrorMessage?.contains("not found") == true)

        val second = service.recoverOnce(NOW.plusSeconds(RECOVERY_INTERVAL_SECONDS))
        val afterSecond = assertNotNull(callRepository.find(CALL_ID))
        assertEquals(1, second.terminalFailures)
        assertEquals(RecordingStatus.FAILED, afterSecond.recordingStatus)
        assertTrue(afterSecond.recordingErrorMessage?.contains("not found") == true)
        assertEquals(RecordingStatus.FAILED, recordingRepository.findByRecordingId("egress-missing")?.status)
        service.close()
    }

    private fun recoveryService(
        callRepository: CallSessionRepository,
        recordingRepository: CallRecordingRepository,
        control: RecordingController,
    ) = RecordingRecoveryService(
        config = recordingConfig(),
        callSessionRepository = callRepository,
        callRecordingRepository = recordingRepository,
        recordingController = control,
    )

    private fun seedCall(
        repository: CallSessionRepository,
        state: CallState,
        recordingStatus: RecordingStatus,
        recordingId: String?,
    ) {
        val users = UserRepository()
        val pairs = PairBondRepository()
        users.insertIfAbsent("user-a", "user-a", "pass-a", "User A", null)
        users.insertIfAbsent("user-b", "user-b", "pass-b", "User B", null)
        pairs.insertIfAbsent("pair-1", "user-a", "user-b", 1L)
        repository.upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = "pair-1",
                roomName = "pair-1-call-1",
                createdByUserId = "user-a",
                startedAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                endedAtEpochMillis = 2L.takeIf { state == CallState.ENDED },
                state = state,
                recordingStatus = recordingStatus,
                recordingId = recordingId,
            ),
        )
    }

    private fun recordingConfig() = RecordingConfig(
        enabled = true,
        provider = RecordingProvider.LIVEKIT,
        idPrefix = "rec",
        filePrefix = "recordings",
        bucket = "purr-recordings",
        endpoint = "http://minio:9000",
        publicEndpoint = "http://localhost:9000",
        accessKey = "key",
        secretKey = "secret",
        region = "us-east-1",
        forcePathStyle = true,
        recoveryEnabled = true,
        recoveryIntervalSeconds = RECOVERY_INTERVAL_SECONDS,
        recoveryStaleAfterSeconds = RECOVERY_INTERVAL_SECONDS,
        recoveryMaxAttempts = 2,
        downloadUrlTtlSeconds = 300,
        consentPolicyVersion = "test-v1",
        cleanupEnabled = false,
        retentionDays = 30,
        cleanupIntervalSeconds = 3600,
        cleanupBatchSize = 100,
        cleanupMaxAttempts = 10,
    )

    private fun withDatabase(block: () -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:recording-recovery-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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

    private companion object {
        const val CALL_ID = "call-1"
        const val RECOVERY_INTERVAL_SECONDS = 5L
        val NOW: Instant = Instant.parse("2026-07-10T12:00:00Z")
    }
}

private class RecoveryRecordingControl(
    private val providerResult: ProviderRecordingResult?,
    private val stopResult: ProviderRecordingResult = errorResult(),
) : RecordingController {
    val stopCalls = mutableListOf<String>()

    override fun startRecording(callId: String, roomName: String): ProviderRecordingResult = error("Not used")

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
    ): ProviderRecordingResult {
        stopCalls += currentRecordingId ?: error("Missing recording id")
        return stopResult
    }

    override fun getRecording(recordingId: String): ProviderRecordingResult? = providerResult

    companion object {
        private fun errorResult() = ProviderRecordingResult(
            status = RecordingStatus.FAILED,
            recordingId = null,
            updatedAtEpochMillis = 1L,
        )
    }
}

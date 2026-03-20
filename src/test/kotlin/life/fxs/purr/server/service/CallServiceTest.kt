package life.fxs.purr.server.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.LiveKitConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.livekit.LiveKitTokenService
import life.fxs.purr.server.livekit.RecordingControlService
import life.fxs.purr.server.livekit.RecordingResult
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.StoredCallSession
import life.fxs.purr.server.repository.UserRepository

class CallServiceTest {
    @Test
    fun `end call stops recording that is still starting once recording id exists`() {
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
            userRepository.upsert("user-a", "user-a", "pass-a", "User A", null)
            userRepository.upsert("user-b", "user-b", "pass-b", "User B", null)
            pairBondRepository.upsert("pair-demo", "user-a", "user-b", 1L)

            val repository = CallSessionRepository()
            val now = Instant.parse("2026-03-19T14:00:00Z")
            val stopTime = now.plusSeconds(5)
            val callId = "call-test"
            val roomName = "pair-demo-call-test"

            repository.upsert(
                StoredCallSession(
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

            val recordingControl = FakeRecordingControlService(
                stopResult = RecordingResult(
                    status = RecordingStatus.STOPPING,
                    recordingId = "egress-1",
                    updatedAtEpochMillis = stopTime.toEpochMilli(),
                ),
            )

            val service = CallService(
                pairService = PairService(pairBondRepository, userRepository),
                callSessionRepository = repository,
                liveKitTokenService = FakeLiveKitTokenService(),
                recordingControlService = recordingControl,
                liveKitConfig = LiveKitConfig(
                    wsUrl = "ws://localhost:7880",
                    httpUrl = "http://localhost:7880",
                    apiKey = "devkey",
                    apiSecret = "devsecret",
                    tokenTtlSeconds = 3600,
                ),
                nowProvider = { stopTime },
            )

            service.endCall(userId = "user-a", callId = callId)

            assertEquals(1, recordingControl.stopCalls.size)
            assertEquals(FakeRecordingControlService.StopCall(callId, roomName, "egress-1"), recordingControl.stopCalls.single())

            val stored = repository.find(callId) ?: error("call not found")
            assertEquals(CallState.ENDED, stored.state)
            assertEquals(RecordingStatus.STOPPING, stored.recordingStatus)
            assertEquals("egress-1", stored.recordingId)
            assertEquals(stopTime.toEpochMilli(), stored.endedAtEpochMillis)
        } finally {
            (databaseResources.dataSource as? AutoCloseable)?.close()
        }
    }
}

private class FakeLiveKitTokenService : LiveKitTokenService {
    override fun issueAccessToken(roomName: String, participantIdentity: String): String = "token"
}

private class FakeRecordingControlService(
    private val stopResult: RecordingResult,
) : RecordingControlService {
    data class StopCall(
        val callId: String,
        val roomName: String,
        val recordingId: String?,
    )

    val stopCalls = mutableListOf<StopCall>()

    override fun startRecording(callId: String, roomName: String): RecordingResult {
        error("Not used in this test")
    }

    override fun stopRecording(callId: String, roomName: String, currentRecordingId: String?): RecordingResult {
        stopCalls += StopCall(callId, roomName, currentRecordingId)
        return stopResult
    }
}

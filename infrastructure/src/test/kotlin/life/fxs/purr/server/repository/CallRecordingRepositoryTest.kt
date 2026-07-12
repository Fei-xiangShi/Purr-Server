package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallRecordingRepositoryTest {
    @Test
    fun `late webhook updates history without replacing newer current recording`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:recording-history-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            seedCall()
            val callRepository = CallSessionRepository()
            val recordingRepository = CallRecordingRepository()

            recordingRepository.updateCurrent(
                CALL_ID,
                result("egress-a", RecordingStatus.RECORDING, updatedAt = 20L),
            )
            recordingRepository.updateCurrent(
                CALL_ID,
                result("egress-a", RecordingStatus.STOPPED, updatedAt = 30L),
            )
            assertNotNull(callRepository.claimRecordingStart(CALL_ID, updatedAtEpochMillis = 40L))
            recordingRepository.updateCurrent(
                CALL_ID,
                result("egress-b", RecordingStatus.RECORDING, updatedAt = 50L),
            )

            recordingRepository.updateCurrent(
                CALL_ID,
                result("egress-a", RecordingStatus.FAILED, updatedAt = 60L),
            )

            val call = assertNotNull(callRepository.find(CALL_ID))
            assertEquals("egress-b", call.recordingId)
            assertEquals(RecordingStatus.RECORDING, call.recordingStatus)
            val history = recordingRepository.findByCallId(CALL_ID).associateBy { it.recordingId }
            assertEquals(setOf("egress-a", "egress-b"), history.keys)
            assertEquals(RecordingStatus.FAILED, history.getValue("egress-a").status)
            assertEquals("provider failure", history.getValue("egress-a").errorMessage)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun seedCall() {
        val users = UserRepository()
        val pairs = PairBondRepository()
        users.upsert("user-a", "user-a", "pass-a", "User A", null)
        users.upsert("user-b", "user-b", "pass-b", "User B", null)
        pairs.upsert("pair-1", "user-a", "user-b", 1L)
        CallSessionRepository().upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = "pair-1",
                roomName = "pair-1-call-1",
                createdByUserId = "user-a",
                startedAtEpochMillis = 1L,
                updatedAtEpochMillis = 10L,
                state = CallState.ACTIVE,
                recordingStatus = RecordingStatus.STARTING,
            ),
        )
    }

    private fun result(
        recordingId: String,
        status: RecordingStatus,
        updatedAt: Long,
    ) = ProviderRecordingResult(
        status = status,
        recordingId = recordingId,
        updatedAtEpochMillis = updatedAt,
        objectKey = "recordings/$CALL_ID/$recordingId.ogg",
        errorMessage = "provider failure".takeIf { status == RecordingStatus.FAILED },
    )

    private companion object {
        const val CALL_ID = "call-1"
    }
}

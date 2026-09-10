package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareRepositoryTest {
    @Test
    fun `migration and repository enforce one active share and preserve convergence state`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:screen-share-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            seedCall()
            val repository = ScreenShareRepository()
            val first = share("share-1", 1_000)
            val second = share("share-2", 2_000)

            assertTrue(repository.createIfAbsent(first))
            assertFalse(repository.createIfAbsent(second))
            assertEquals(first, repository.findByMediaPath(first.mediaPath))

            val live = assertNotNull(repository.markLive(first.shareId, "webrtcSession", "session-1", 1_100))
            assertTrue(live.changed)
            assertEquals(ScreenShareStatus.LIVE, live.record.status)
            assertEquals(1_100, live.record.liveAtEpochMillis)

            assertEquals(1, repository.observeMissing(first.shareId, 1_200)?.missingSnapshotCount)
            assertEquals(0, repository.observePresent(first.shareId, "srtConn", "session-2", 1_300)?.missingSnapshotCount)

            val stopping = assertNotNull(repository.requestStop(CALL_ID, 1_400))
            assertEquals(ScreenShareStatus.STOPPING, stopping.record.status)
            assertEquals(1_400, stopping.record.stoppedAtEpochMillis)
            assertTrue(repository.createIfAbsent(second), "stop request must revoke the single-active-share lease")

            val stopped = assertNotNull(repository.markStopped(first.shareId, 1_500))
            assertEquals(ScreenShareStatus.STOPPED, stopped.record.status)
            assertEquals(1_500, stopped.record.stoppedAtEpochMillis)
            assertNotNull(repository.markProviderCleaned(first.shareId, 1_600)?.providerCleanedAtEpochMillis)
            assertTrue(repository.findReconciliationCandidates(1_700, 10).none { it.shareId == first.shareId })
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun seedCall() {
        val users = UserRepository()
        users.insertIfAbsent(USER_A, USER_A, "pass-a", "A", null)
        users.insertIfAbsent(USER_B, USER_B, "pass-b", "B", null)
        PairBondRepository().insertIfAbsent(PAIR_ID, USER_A, USER_B, 1)
        CallSessionRepository().upsert(
            CallRecord(
                callId = CALL_ID,
                pairId = PAIR_ID,
                roomName = "room-1",
                createdByUserId = USER_A,
                startedAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                state = CallState.ACTIVE,
                recordingStatus = RecordingStatus.IDLE,
                connectedAtEpochMillis = 1,
            ),
        )
    }

    private fun share(shareId: String, createdAt: Long) = ScreenShareRecord(
        shareId = shareId,
        callId = CALL_ID,
        ownerUserId = USER_A,
        source = ScreenShareSource.MOBILE,
        mediaPath = "screen-$shareId",
        status = ScreenShareStatus.AUTHORIZED,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        expiresAtEpochMillis = createdAt + 60_000,
    )

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val PAIR_ID = "pair-1"
        const val CALL_ID = "call-1"
    }
}

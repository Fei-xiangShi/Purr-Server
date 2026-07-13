package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallSessionRepositoryTest {
    @Test
    fun `history returns ended calls newest first with cursor pagination`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-history-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val users = UserRepository()
            users.upsert("user-a", "user-a", "pass-a", "A", null)
            users.upsert("user-b", "user-b", "pass-b", "B", null)
            PairBondRepository().upsert("pair-1", "user-a", "user-b", 1L)
            val repository = CallSessionRepository()
            repository.upsert(call("call-old", 1_000L, 4_000L))
            repository.upsert(call("call-new", 5_000L, 9_000L))
            repository.upsert(call("call-active", 10_000L, null))

            val firstPage = repository.findEndedByPairId("pair-1", 1, null)
            val secondPage = repository.findEndedByPairId(
                pairId = "pair-1",
                limit = 1,
                cursor = CallHistoryCursor(firstPage.single().startedAtEpochMillis, firstPage.single().callId),
            )

            assertEquals(listOf("call-new"), firstPage.map { it.callId })
            assertEquals(listOf("call-old"), secondPage.map { it.callId })
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun call(callId: String, startedAt: Long, endedAt: Long?) = CallRecord(
        callId = callId,
        pairId = "pair-1",
        roomName = "room-$callId",
        createdByUserId = "user-a",
        startedAtEpochMillis = startedAt,
        updatedAtEpochMillis = endedAt ?: startedAt,
        state = if (endedAt == null) CallState.ACTIVE else CallState.ENDED,
        recordingStatus = RecordingStatus.IDLE,
        endedAtEpochMillis = endedAt,
        connectedAtEpochMillis = startedAt.takeIf { endedAt != null },
    )
}

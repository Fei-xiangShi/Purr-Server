package life.fxs.purr.server.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallSessionRepositoryTest {
    @Test
    fun `history returns only connected calls lasting at least thirty seconds`() {
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
            users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
            users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
            PairBondRepository().insertIfAbsent("pair-1", "user-a", "user-b", 1L)
            val repository = CallSessionRepository()
            repository.upsert(call("call-old", 1_000L, 2_000L, 32_000L))
            repository.upsert(call("call-new", 5_000L, 6_000L, 36_001L))
            repository.upsert(call("call-short", 7_000L, 8_000L, 37_999L))
            repository.upsert(call("call-unanswered", 9_000L, null, 40_000L))
            repository.upsert(call("call-active", 10_000L, 10_000L, null))

            val firstPage = repository.findEndedByPairId("pair-1", 1, null)
            val secondPage = repository.findEndedByPairId(
                pairId = "pair-1",
                limit = 1,
                cursor = CallHistoryCursor(firstPage.single().startedAtEpochMillis, firstPage.single().callId),
            )

            assertEquals(listOf("call-new"), firstPage.map { it.callId })
            assertEquals(listOf("call-old"), secondPage.map { it.callId })
            assertEquals(
                listOf("call-new", "call-old"),
                repository.findEndedByPairIdBetween("pair-1", 0L, 50_000L, 10).map { it.callId },
            )
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `room empty observation is persisted and only cleared while call is open`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-reconciliation-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val users = UserRepository()
            users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
            users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
            PairBondRepository().insertIfAbsent("pair-1", "user-a", "user-b", 1L)
            val repository = CallSessionRepository()
            repository.upsert(call("call-active", 1_000L, 1_000L, null))

            assertEquals(1_100L, repository.observeRoomEmpty("call-active", 1_100L)
                ?.roomEmptySinceEpochMillis)
            assertEquals(1_100L, repository.observeRoomEmpty("call-active", 1_200L)
                ?.roomEmptySinceEpochMillis)
            assertEquals(true, repository.clearRoomEmptyObservation("call-active"))
            assertEquals(null, repository.find("call-active")?.roomEmptySinceEpochMillis)
            assertNotNull(repository.findOpenCalls(10).single())
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `ending an active call persists authoritative duration`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-duration-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()

        try {
            val users = UserRepository()
            users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
            users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
            PairBondRepository().insertIfAbsent("pair-1", "user-a", "user-b", 1L)
            val repository = CallSessionRepository()
            repository.upsert(call("call-exact", 500L, 1_000L, null))

            val ended = assertNotNull(repository.endIfOpen("call-exact", 31_000L))

            assertEquals(30_000L, ended.call.durationMillis)
            assertEquals(listOf("call-exact"), repository.findEndedByPairId("pair-1", 10, null).map { it.callId })
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun call(
        callId: String,
        startedAt: Long,
        connectedAt: Long?,
        endedAt: Long?,
    ) = CallRecord(
        callId = callId,
        pairId = "pair-1",
        roomName = "room-$callId",
        createdByUserId = "user-a",
        startedAtEpochMillis = startedAt,
        updatedAtEpochMillis = endedAt ?: startedAt,
        state = if (endedAt == null) CallState.ACTIVE else CallState.ENDED,
        recordingStatus = RecordingStatus.IDLE,
        endedAtEpochMillis = endedAt,
        connectedAtEpochMillis = connectedAt,
    )
}

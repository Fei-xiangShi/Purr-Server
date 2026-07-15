package life.fxs.purr.server.realtime

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.OutboxConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.UserRepository

class OutboxDispatcherTest {
    @Test
    fun `only one dispatcher owns the global ordering lease`() = withDatabase {
        val outbox = OutboxRepository()

        assertTrue(
            outbox.acquireDispatcherLease(
                workerId = "worker-a",
                nowEpochMillis = NOW.toEpochMilli(),
                leaseUntilEpochMillis = NOW.plusSeconds(30).toEpochMilli(),
            ),
        )
        assertFalse(
            outbox.acquireDispatcherLease(
                workerId = "worker-b",
                nowEpochMillis = NOW.toEpochMilli(),
                leaseUntilEpochMillis = NOW.plusSeconds(30).toEpochMilli(),
            ),
        )
        outbox.releaseDispatcherLease("worker-a")
        assertTrue(
            outbox.acquireDispatcherLease(
                workerId = "worker-b",
                nowEpochMillis = NOW.toEpochMilli(),
                leaseUntilEpochMillis = NOW.plusSeconds(30).toEpochMilli(),
            ),
        )
    }

    @Test
    fun `call state and event roll back in the same transaction`() = withDatabase { resources ->
        seedPair()
        val calls = CallSessionRepository()
        val outbox = OutboxRepository(eventIdProvider = { EVENT_ID })
        val transaction = resources.applicationTransaction

        assertFailsWith<IllegalStateException> {
            transaction.execute {
                calls.upsert(call())
                outbox.enqueue("user-b", callStartedEvent(), NOW.toEpochMilli())
                error("force rollback")
            }
        }

        assertNull(calls.find(CALL_ID))
        assertNull(outbox.find(EVENT_ID))
    }

    @Test
    fun `event is marked published only after confirmed sink success`() = withDatabase {
        seedPair()
        val outbox = OutboxRepository(eventIdProvider = { EVENT_ID })
        val delivered = mutableListOf<RealtimeEvent>()
        outbox.enqueue("user-b", callStartedEvent(), NOW.toEpochMilli())
        val dispatcher = OutboxDispatcher(
            config = outboxConfig(),
            repository = outbox,
            eventSink = RealtimeEventSink { _, event -> delivered += event },
            workerId = "worker-a",
        )

        val summary = runBlocking { dispatcher.dispatchOnce(NOW) }

        assertEquals(OutboxDispatchSummary(1, 1, 0), summary)
        assertEquals(listOf(callStartedEvent()), delivered)
        assertEquals(NOW.toEpochMilli(), assertNotNull(outbox.find(EVENT_ID)).publishedAtEpochMillis)
        dispatcher.close()
    }

    @Test
    fun `failed publication is retried after exponential backoff`() = withDatabase {
        seedPair()
        val outbox = OutboxRepository(eventIdProvider = { EVENT_ID })
        var attempts = 0
        outbox.enqueue("user-b", callStartedEvent(), NOW.toEpochMilli())
        val dispatcher = OutboxDispatcher(
            config = outboxConfig(),
            repository = outbox,
            eventSink = RealtimeEventSink { _, _ ->
                attempts++
                if (attempts == 1) error("redis unavailable")
            },
            workerId = "worker-a",
        )

        assertEquals(1, runBlocking { dispatcher.dispatchOnce(NOW) }.failed)
        val failed = assertNotNull(outbox.find(EVENT_ID))
        assertEquals(1, failed.attemptCount)
        assertEquals(NOW.plusSeconds(1).toEpochMilli(), failed.availableAtEpochMillis)
        assertEquals(0, runBlocking { dispatcher.dispatchOnce(NOW.plusMillis(999)) }.claimed)
        assertEquals(1, runBlocking { dispatcher.dispatchOnce(NOW.plusSeconds(1)) }.published)
        assertEquals(2, attempts)
        dispatcher.close()
    }

    @Test
    fun `publication cancellation is propagated without recording a transport failure`() = withDatabase {
        seedPair()
        val outbox = OutboxRepository(eventIdProvider = { EVENT_ID })
        outbox.enqueue("user-b", callStartedEvent(), NOW.toEpochMilli())
        val dispatcher = OutboxDispatcher(
            config = outboxConfig(),
            repository = outbox,
            eventSink = RealtimeEventSink { _, _ -> throw CancellationException("worker stopping") },
            workerId = "worker-a",
        )

        assertFailsWith<CancellationException> {
            runBlocking { dispatcher.dispatchOnce(NOW) }
        }

        val record = assertNotNull(outbox.find(EVENT_ID))
        assertEquals(1, record.attemptCount)
        assertEquals(NOW.toEpochMilli(), record.availableAtEpochMillis)
        assertNull(record.publishedAtEpochMillis)
        assertNull(record.lastError)
        dispatcher.close()
    }

    private fun seedPair() {
        val users = UserRepository()
        users.insertIfAbsent("user-a", "user-a", "pass-a", "A", null)
        users.insertIfAbsent("user-b", "user-b", "pass-b", "B", null)
        PairBondRepository().insertIfAbsent("pair-1", "user-a", "user-b", 1L)
    }

    private fun call() = CallRecord(
        callId = CALL_ID,
        pairId = "pair-1",
        roomName = "pair-1-$CALL_ID",
        createdByUserId = "user-a",
        startedAtEpochMillis = NOW.toEpochMilli(),
        updatedAtEpochMillis = NOW.toEpochMilli(),
        state = CallState.ACTIVE,
        recordingStatus = RecordingStatus.IDLE,
    )

    private fun callStartedEvent() = RealtimeEvent(
        type = RealtimeEvent.CALL_STARTED,
        callId = CALL_ID,
        pairId = "pair-1",
        callerUserId = "user-a",
        startedAtEpochMillis = NOW.toEpochMilli(),
    )

    private fun outboxConfig() = OutboxConfig(
        pollIntervalMillis = 100,
        batchSize = 100,
        leaseSeconds = 30,
        maxAttempts = 3,
        retryBaseSeconds = 1,
        retryMaxSeconds = 10,
    )

    private fun withDatabase(block: (life.fxs.purr.server.db.DatabaseResources) -> Unit) {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:outbox-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            block(resources)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private companion object {
        const val CALL_ID = "call-1"
        const val EVENT_ID = "event-1"
        val NOW: Instant = Instant.parse("2026-07-10T12:00:00Z")
    }
}

package life.fxs.purr.server.recording

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import life.fxs.purr.server.application.call.CallLifecycleService
import life.fxs.purr.server.application.call.CallRoomLifecycleService
import life.fxs.purr.server.application.call.CallRoomReconciliationService
import life.fxs.purr.server.application.port.*
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.config.OutboxConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.repository.*

class RecordingPauseIntegrationTest {
    @Test
    fun `repeated pause resume cycles use distinct durable commands and retain segment history`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        f.dispatch()
        val first = assertNotNull(f.calls.find("call")?.recordingId)
        repeat(2) {
            f.leave(1)
            f.dispatch()
            assertEquals(CallState.ACTIVE, f.calls.find("call")?.state)
            assertEquals(RecordingStatus.STOPPED, f.calls.find("call")?.recordingStatus)
            assertEquals(true, f.calls.find("call")?.recordingPaused)
            f.join()
            f.dispatch()
            assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)
        }
        assertEquals(3, f.started.size)
        assertEquals(3, f.started.toSet().size)
        assertNotEquals(first, f.calls.find("call")?.recordingId)
        f.leave(0)
        f.dispatch()
        assertEquals(CallState.ACTIVE, f.calls.find("call")?.state)
        f.now += 15_000L
        f.leave(0)
        f.dispatch()
        assertEquals(CallState.ENDED, f.calls.find("call")?.state)
        assertEquals(3, f.stopped.size)
        assertEquals(1, f.deletedRooms.size)
        assertEquals(3, f.recordings.findByCallId("call").size)
    }

    @Test
    fun `departure before delayed start prevents recording and returning pair can still start`() = withFixture { f ->
        f.join()
        f.leave(1)
        f.now = 31_000L
        f.dispatch()
        assertEquals(emptyList(), f.started)
        f.join()
        f.dispatch()
        assertEquals(1, f.started.size)
        assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)
    }

    @Test
    fun `late previous segment callback cannot capture new start whose id is not known yet`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        f.dispatch()
        val oldId = assertNotNull(f.calls.find("call")?.recordingId)
        f.leave(1)
        f.dispatch()
        f.join()
        assertNotNull(f.calls.claimRecordingStart("call", f.now))
        f.recordings.updateCurrent("call", ProviderRecordingResult(RecordingStatus.STOPPED, oldId, ++f.now))
        assertEquals(RecordingStatus.STARTING, f.calls.find("call")?.recordingStatus)
        assertNull(f.calls.find("call")?.recordingId)
        f.dispatch()
        assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)
        assertNotEquals(oldId, f.calls.find("call")?.recordingId)
    }

    @Test
    fun `resume waits for pending start and stop even if callback has already completed segment`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        assertNotNull(f.calls.claimRecordingStart("call", f.now))
        f.leave(1)
        f.recordings.updateCurrent("call", ProviderRecordingResult(RecordingStatus.STOPPED, "uncertain", ++f.now))
        f.join()
        assertEquals(true, f.calls.find("call")?.recordingPaused)
        assertEquals(0L, f.calls.find("call")?.recordingGeneration)
    }

    @Test
    fun `polling pauses after a missed departure webhook and resumes after a missed return webhook`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        f.dispatch()
        assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)

        f.present = 1
        f.reconcile()
        f.dispatch()
        assertEquals(true, f.calls.find("call")?.recordingPaused)
        assertEquals(RecordingStatus.STOPPED, f.calls.find("call")?.recordingStatus)
        assertEquals(CallState.ACTIVE, f.calls.find("call")?.state)
        // The remaining participant may wait indefinitely without further work.
        repeat(3) { f.reconcile(); f.dispatch() }
        assertEquals(1, f.stopped.size)
        assertEquals(1, f.started.size)

        f.present = 2
        f.reconcile()
        f.dispatch()
        assertEquals(false, f.calls.find("call")?.recordingPaused)
        assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)
        assertEquals(2, f.started.size)
        assertEquals(2, f.recordings.findByCallId("call").size)
    }

    @Test
    fun `reordered webhooks are judged against the live room instead of their stale counts`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        f.dispatch()

        // The departure is delivered after the participant has already returned.
        f.present = 2
        f.liveLifecycle.handle(CallRoomEvent("late-left", CallRoomEventType.PARTICIPANT_LEFT,
            "room", CallRoomParticipant(false, false), 1))
        f.dispatch()
        assertEquals(false, f.calls.find("call")?.recordingPaused)
        assertEquals(RecordingStatus.RECORDING, f.calls.find("call")?.recordingStatus)
        assertEquals(emptyList(), f.stopped)

        // A real departure pauses; its delayed, duplicated join must not resume.
        f.present = 1
        f.liveLifecycle.handle(CallRoomEvent("left", CallRoomEventType.PARTICIPANT_LEFT,
            "room", CallRoomParticipant(false, false), 1))
        f.dispatch()
        repeat(2) {
            f.liveLifecycle.handle(CallRoomEvent("late-join-$it", CallRoomEventType.PARTICIPANT_JOINED,
                "room", CallRoomParticipant(true, false), 2))
            f.dispatch()
        }
        assertEquals(true, f.calls.find("call")?.recordingPaused)
        assertEquals(1, f.started.size)
        assertEquals(1, f.stopped.size)
    }

    @Test
    fun `pause and resume survive a server restart between the state change and the provider call`() = withFixture { f ->
        f.join()
        f.now = 31_000L
        f.dispatch()
        f.leave(1)
        // The process dies after committing the pause but before stopping the egress.
        assertEquals(RecordingStatus.STOPPING, f.calls.find("call")?.recordingStatus)
        assertEquals(emptyList(), f.stopped)

        val restarted = Fixture(f.transaction, seed = false, tag = "restarted")
        try {
            restarted.now = f.now + 60_000L
            restarted.dispatch()
            assertEquals(1, restarted.stopped.size)
            assertEquals(RecordingStatus.STOPPED, restarted.calls.find("call")?.recordingStatus)
            assertEquals(true, restarted.calls.find("call")?.recordingPaused)

            restarted.join()
            // Dies again after committing the resume but before starting the new segment.
            val afterSecondRestart = Fixture(f.transaction, seed = false, tag = "restarted-again")
            try {
                afterSecondRestart.now = restarted.now + 60_000L
                afterSecondRestart.dispatch()
                assertEquals(1, afterSecondRestart.started.size)
                assertEquals(RecordingStatus.RECORDING, afterSecondRestart.calls.find("call")?.recordingStatus)
                assertEquals(2, afterSecondRestart.recordings.findByCallId("call").size)
            } finally { afterSecondRestart.dispatcher.close() }
        } finally { restarted.dispatcher.close() }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        DatabaseFactory(DatabaseConfig(
            jdbcUrl = "jdbc:h2:mem:recording-pause-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driverClassName = "org.h2.Driver", username = "sa", password = "", maximumPoolSize = 2,
        )).connect().use { resources ->
            val fixture = Fixture(resources.applicationTransaction)
            try { block(fixture) } finally { fixture.dispatcher.close() }
        }
    }

    private class Fixture(
        val transaction: ApplicationTransaction,
        seed: Boolean = true,
        private val tag: String = "egress",
    ) {
        var now = 1_000L
        var present = 2
        val calls = CallSessionRepository()
        val recordings = CallRecordingRepository()
        val commands = RecordingCommandRepository(recordings)
        val pairs = PairBondRepository()
        val consents = CallRecordingConsentRepository()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val deletedRooms = mutableListOf<String>()
        val controller = object : RecordingController {
            override fun startRecording(callId: String, roomName: String, operationId: String): ProviderRecordingResult {
                started += operationId
                return ProviderRecordingResult(RecordingStatus.RECORDING, "$tag-${started.size}", now,
                    objectKey = "recordings/$operationId.ogg")
            }
            override fun stopRecording(callId: String, roomName: String, currentRecordingId: String?, operationId: String): ProviderRecordingResult {
                stopped += operationId
                return ProviderRecordingResult(RecordingStatus.STOPPED, currentRecordingId, now, endedAtEpochMillis = now)
            }
        }
        val dispatcher = RecordingCommandDispatcher(
            OutboxConfig(100, 100, 30, 3, 1, 10), commands, calls, controller,
            CallRoomTerminator { deletedRooms += it }, nowProvider = { Instant.ofEpochMilli(now) },
        )
        val lifecycle = CallRoomLifecycleService(
            callSessionStore = calls, recordingConsentStore = consents, pairStore = pairs,
            callLifecycleService = CallLifecycleService(calls, pairs, transaction, RealtimeOutbox { _, _, _ -> }),
            recordingEnabled = true, consentPolicyVersion = "v1", nowProvider = { Instant.ofEpochMilli(now) },
            recordingCommandStore = commands, transaction = transaction, reconciliationStore = calls,
            emptyRoomGraceMillis = 15_000L,
        )
        private val reader = object : CallRoomParticipantReader {
            override fun countActiveNonEgressParticipants(roomName: String) = present
            override fun countPresentNonEgressParticipants(roomName: String) = present
        }
        /** Same service wired with a provider inventory, as in production. */
        val liveLifecycle = CallRoomLifecycleService(
            callSessionStore = calls, recordingConsentStore = consents, pairStore = pairs,
            callLifecycleService = CallLifecycleService(calls, pairs, transaction, RealtimeOutbox { _, _, _ -> }),
            recordingEnabled = true, consentPolicyVersion = "v1", participantReader = reader,
            nowProvider = { Instant.ofEpochMilli(now) },
            recordingCommandStore = commands, transaction = transaction, reconciliationStore = calls,
            emptyRoomGraceMillis = 15_000L,
        )
        private val reconciler = CallRoomReconciliationService(
            store = calls, participantReader = reader, roomEventHandler = liveLifecycle,
            waitingCallTerminator = WaitingCallTerminator { _, _ -> error("unexpected waiting expiry") },
            waitingTtlMillis = 120_000L, emptyRoomGraceMillis = 15_000L, batchSize = 10,
            recordingCommandStore = commands,
        )
        fun reconcile() { reconciler.reconcileOnce(now) }
        init {
            if (seed) seed()
        }
        private fun seed() {
            UserRepository().apply {
                insertIfAbsent("a", "a", "hash", "A", null)
                insertIfAbsent("b", "b", "hash", "B", null)
            }
            pairs.insertIfAbsent("pair", "a", "b", now)
            calls.upsert(CallRecord("call", "pair", "room", "a", now, now,
                state = CallState.WAITING, recordingStatus = RecordingStatus.IDLE))
            consents.record("call", "a", "v1", now)
            consents.record("call", "b", "v1", now)
        }
        fun join() = lifecycle.handle(CallRoomEvent("join-$now", CallRoomEventType.PARTICIPANT_JOINED,
            "room", CallRoomParticipant(true, false), 2))
        fun leave(count: Int) = lifecycle.handle(CallRoomEvent("leave-$now", CallRoomEventType.PARTICIPANT_LEFT,
            "room", CallRoomParticipant(false, false), count))
        fun dispatch() { dispatcher.dispatchOnce(Instant.ofEpochMilli(now++)) }
    }
}

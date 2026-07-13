package life.fxs.purr.server.application.call

import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventHandler
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallRoomReconciliationStore
import life.fxs.purr.server.application.port.WaitingCallTerminator
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallRoomReconciliationServiceTest {
    @Test
    fun `active empty room must remain empty for the full grace period`() {
        val store = ReconciliationStore(activeCall())
        val reader = MutableParticipantReader(0)
        val events = RecordingEventHandler()
        val service = service(store, reader, events)

        service.reconcileOnce(1_000L)
        service.reconcileOnce(1_099L)
        assertEquals(0, events.events.size)
        service.reconcileOnce(1_100L)

        assertEquals(1, events.events.size)
        assertEquals("reconcile-empty-call-1", events.events.single().eventId)
        assertEquals(0, events.events.single().reportedParticipantCount)
    }

    @Test
    fun `a participant returning during grace clears the persisted empty observation`() {
        val store = ReconciliationStore(activeCall())
        val reader = MutableParticipantReader(0)
        val events = RecordingEventHandler()
        val service = service(store, reader, events)

        service.reconcileOnce(1_000L)
        reader.presentCount = 1
        service.reconcileOnce(1_099L)
        reader.presentCount = 0
        service.reconcileOnce(1_100L)

        assertEquals(0, events.events.size)
        assertEquals(1_100L, store.call.roomEmptySinceEpochMillis)
    }

    @Test
    fun `waiting call expires with one participant and two participants trigger join reconciliation`() {
        val store = ReconciliationStore(waitingCall())
        val reader = MutableParticipantReader(1)
        val events = RecordingEventHandler()
        val terminator = RecordingWaitingTerminator()
        val service = service(store, reader, events, terminator)

        service.reconcileOnce(1_001L)
        assertEquals(listOf("call-1"), terminator.callIds)
        assertEquals(0, events.events.size)

        store.call = waitingCall()
        reader.presentCount = 2
        service.reconcileOnce(1_001L)
        assertEquals(1, events.events.size)
        assertEquals(2, events.events.single().reportedParticipantCount)
    }

    private fun service(
        store: ReconciliationStore,
        reader: MutableParticipantReader,
        events: RecordingEventHandler,
        terminator: RecordingWaitingTerminator = RecordingWaitingTerminator(),
    ) = CallRoomReconciliationService(
        store = store,
        participantReader = reader,
        roomEventHandler = events,
        waitingCallTerminator = terminator,
        waitingTtlMillis = 1_000L,
        emptyRoomGraceMillis = 100L,
        batchSize = 10,
    )

    private class ReconciliationStore(var call: CallRecord) : CallRoomReconciliationStore {
        override fun findOpenCalls(limit: Int): List<CallRecord> =
            listOf(call).filter { it.state != CallState.ENDED }

        override fun observeRoomEmpty(callId: String, observedAtEpochMillis: Long): CallRecord? {
            if (call.callId != callId || call.state != CallState.ACTIVE) return null
            if (call.roomEmptySinceEpochMillis == null) {
                call = call.copy(roomEmptySinceEpochMillis = observedAtEpochMillis)
            }
            return call
        }

        override fun clearRoomEmptyObservation(callId: String): Boolean {
            if (call.callId != callId || call.roomEmptySinceEpochMillis == null) return false
            call = call.copy(roomEmptySinceEpochMillis = null)
            return true
        }
    }

    private class MutableParticipantReader(var presentCount: Int) : CallRoomParticipantReader {
        override fun countActiveNonEgressParticipants(roomName: String): Int = presentCount
        override fun countPresentNonEgressParticipants(roomName: String): Int = presentCount
    }

    private class RecordingEventHandler : CallRoomEventHandler {
        val events = mutableListOf<CallRoomEvent>()
        override fun handle(event: CallRoomEvent) {
            events += event
        }
    }

    private class RecordingWaitingTerminator : WaitingCallTerminator {
        val callIds = mutableListOf<String>()
        override fun endWaitingCall(callId: String, endedAtEpochMillis: Long) {
            callIds += callId
        }
    }

    private fun activeCall() = baseCall().copy(
        state = CallState.ACTIVE,
        connectedAtEpochMillis = 1L,
    )

    private fun waitingCall() = baseCall()

    private fun baseCall() = CallRecord(
        callId = "call-1",
        pairId = "pair-1",
        roomName = "room-1",
        createdByUserId = "user-a",
        startedAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        state = CallState.WAITING,
        recordingStatus = RecordingStatus.IDLE,
    )
}

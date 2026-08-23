package life.fxs.purr.server.application.call

import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventHandler
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallRoomReconciliationStore
import life.fxs.purr.server.application.port.WaitingCallTerminator
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingCommandRecord
import life.fxs.purr.server.application.port.RecordingCommandType
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingCommandState
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
        roomTerminator = NoOpRoomTerminator,
        recordingCommandStore = NoOpRecordingCommandStore,
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

    private object NoOpRoomTerminator : life.fxs.purr.server.application.port.CallRoomTerminator {
        override fun deleteRoom(roomName: String) = Unit
    }

    private object NoOpRecordingCommandStore : RecordingCommandStore {
        private fun command(type: RecordingCommandType, callId: String, roomName: String, at: Long) =
            RecordingCommandRecord("noop", "noop:$callId:${type.name}", callId, roomName, type, null, at, at, 0, null, null, RecordingCommandState.PENDING, null, null)
        override fun enqueueStart(callId: String, roomName: String, requestedAtEpochMillis: Long, availableAtEpochMillis: Long) = command(RecordingCommandType.START, callId, roomName, requestedAtEpochMillis)
        override fun enqueueStop(callId: String, roomName: String, recordingId: String?, requestedAtEpochMillis: Long) = command(RecordingCommandType.STOP, callId, roomName, requestedAtEpochMillis)
        override fun enqueueRoomDelete(callId: String, roomName: String, requestedAtEpochMillis: Long) = command(RecordingCommandType.DELETE_ROOM, callId, roomName, requestedAtEpochMillis)
        override fun claimBatch(workerId: String, nowEpochMillis: Long, leaseUntilEpochMillis: Long, maxAttempts: Int, limit: Int) = emptyList<RecordingCommandRecord>()
        override fun markSucceeded(commandId: String, workerId: String, result: ProviderRecordingResult, completedAtEpochMillis: Long) = false
        override fun markFailed(commandId: String, workerId: String, availableAtEpochMillis: Long, errorMessage: String, terminal: Boolean, completedAtEpochMillis: Long) = false
        override fun findOpenForCall(callId: String, type: RecordingCommandType): RecordingCommandRecord? = null
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

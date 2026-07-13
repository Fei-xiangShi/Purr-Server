package life.fxs.purr.server.application.call

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.port.ActiveCallResolution
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRecordingStore
import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventType
import life.fxs.purr.server.application.port.CallRoomParticipant
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.EndCallResolution
import life.fxs.purr.server.application.port.PairRecord
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.application.port.RecordingController
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallRoomLifecycleServiceTest {
    @Test
    fun `second participant activates and starts recording once across duplicate events`() {
        val harness = harness(waitingCall())
        val event = joinedEvent(reportedParticipantCount = 2)

        harness.service.handle(event)
        harness.service.handle(event)

        assertEquals(CallState.ACTIVE, harness.calls.call.state)
        assertEquals(NOW.toEpochMilli(), harness.calls.call.connectedAtEpochMillis)
        assertEquals(1, harness.calls.activationTransitions)
        assertEquals(1, harness.calls.recordingClaims)
        assertEquals(1, harness.recordingController.startCalls)
        assertEquals(RecordingStatus.RECORDING, harness.calls.call.recordingStatus)
        assertEquals("recording-1", harness.calls.call.recordingId)
    }

    @Test
    fun `one participant remains waiting and does not start recording`() {
        val harness = harness(waitingCall())

        harness.service.handle(joinedEvent(reportedParticipantCount = 1))

        assertEquals(CallState.WAITING, harness.calls.call.state)
        assertNull(harness.calls.call.connectedAtEpochMillis)
        assertEquals(0, harness.recordingController.startCalls)
    }

    @Test
    fun `authoritative participant reader wins over reported room count`() {
        val harness = harness(
            waitingCall(),
            participantReader = object : CallRoomParticipantReader {
                override fun countActiveNonEgressParticipants(roomName: String): Int = 1

                override fun countPresentNonEgressParticipants(roomName: String): Int = 1
            },
        )

        harness.service.handle(joinedEvent(reportedParticipantCount = 2))

        assertEquals(CallState.WAITING, harness.calls.call.state)
        assertEquals(0, harness.recordingController.startCalls)
    }

    @Test
    fun `two connections with an unexpected identity cannot activate the call`() {
        val harness = harness(
            waitingCall(),
            participantReader = object : CallRoomParticipantReader {
                override fun countActiveNonEgressParticipants(roomName: String): Int = 2

                override fun countPresentNonEgressParticipants(roomName: String): Int = 2

                override fun activeNonEgressParticipantIdentities(roomName: String): Set<String> =
                    setOf("user-a-$CALL_ID", "intruder-$CALL_ID")
            },
        )

        harness.service.handle(joinedEvent(reportedParticipantCount = 2))

        assertEquals(CallState.WAITING, harness.calls.call.state)
        assertEquals(0, harness.calls.activationTransitions)
        assertEquals(0, harness.recordingController.startCalls)
    }

    @Test
    fun `both call scoped identities activate the call`() {
        val harness = harness(
            waitingCall(),
            participantReader = object : CallRoomParticipantReader {
                override fun countActiveNonEgressParticipants(roomName: String): Int = 2

                override fun countPresentNonEgressParticipants(roomName: String): Int = 2

                override fun activeNonEgressParticipantIdentities(roomName: String): Set<String> =
                    setOf("user-a-$CALL_ID", "user-b-$CALL_ID")
            },
        )

        harness.service.handle(joinedEvent(reportedParticipantCount = 2))

        assertEquals(CallState.ACTIVE, harness.calls.call.state)
        assertEquals(1, harness.calls.activationTransitions)
        assertEquals(1, harness.recordingController.startCalls)
    }

    @Test
    fun `empty room stops recording and ends call once across duplicate events`() {
        val harness = harness(
            waitingCall().copy(
                state = CallState.ACTIVE,
                connectedAtEpochMillis = NOW.minusSeconds(10).toEpochMilli(),
                recordingStatus = RecordingStatus.RECORDING,
                recordingId = "recording-1",
            ),
        )
        val event = CallRoomEvent(
            eventId = "left-1",
            type = CallRoomEventType.PARTICIPANT_LEFT,
            roomName = ROOM_NAME,
            participant = CallRoomParticipant(isActive = false, isEgress = false),
            reportedParticipantCount = 0,
        )

        harness.service.handle(event)
        harness.service.handle(event)

        assertEquals(CallState.ENDED, harness.calls.call.state)
        assertEquals(RecordingStatus.STOPPED, harness.calls.call.recordingStatus)
        assertEquals(1, harness.recordingController.stopCalls)
        assertEquals(1, harness.calls.endTransitions)
        assertEquals(setOf("user-a", "user-b"), harness.outbox.map { it.first }.toSet())
        assertEquals(2, harness.outbox.size)
        assertEquals(setOf(RealtimeEvent.CALL_ENDED), harness.outbox.map { it.second.type }.toSet())
    }

    @Test
    fun `explicit termination shares the idempotent recording and notification path`() {
        val harness = harness(
            waitingCall().copy(
                state = CallState.ACTIVE,
                connectedAtEpochMillis = NOW.minusSeconds(10).toEpochMilli(),
                recordingStatus = RecordingStatus.RECORDING,
                recordingId = "recording-1",
            ),
        )

        harness.service.terminate(CALL_ID, NOW.toEpochMilli())
        harness.service.terminate(CALL_ID, NOW.plusSeconds(1).toEpochMilli())

        assertEquals(CallState.ENDED, harness.calls.call.state)
        assertEquals(NOW.toEpochMilli(), harness.calls.call.endedAtEpochMillis)
        assertEquals(RecordingStatus.STOPPED, harness.calls.call.recordingStatus)
        assertEquals(1, harness.recordingController.stopCalls)
        assertEquals(1, harness.calls.endTransitions)
        assertEquals(setOf("user-a", "user-b"), harness.outbox.map { it.first }.toSet())
        assertEquals(2, harness.outbox.size)
    }

    @Test
    fun `egress participant leaving does not end call`() {
        val harness = harness(
            waitingCall().copy(
                state = CallState.ACTIVE,
                connectedAtEpochMillis = NOW.minusSeconds(10).toEpochMilli(),
            ),
        )

        harness.service.handle(
            CallRoomEvent(
                eventId = "egress-left",
                type = CallRoomEventType.PARTICIPANT_LEFT,
                roomName = ROOM_NAME,
                participant = CallRoomParticipant(isActive = false, isEgress = true),
                reportedParticipantCount = 0,
            ),
        )

        assertEquals(CallState.ACTIVE, harness.calls.call.state)
        assertEquals(0, harness.calls.endTransitions)
    }

    @Test
    fun `stale zero count cannot end while an expected identity is still present`() {
        val harness = harness(
            waitingCall().copy(
                state = CallState.ACTIVE,
                connectedAtEpochMillis = NOW.minusSeconds(10).toEpochMilli(),
            ),
            participantReader = object : CallRoomParticipantReader {
                override fun countActiveNonEgressParticipants(roomName: String): Int = 1

                override fun countPresentNonEgressParticipants(roomName: String): Int = 0

                override fun presentNonEgressParticipantIdentities(roomName: String): Set<String> =
                    setOf("user-b-$CALL_ID")
            },
        )

        harness.service.handle(
            CallRoomEvent(
                eventId = "stale-left",
                type = CallRoomEventType.PARTICIPANT_LEFT,
                roomName = ROOM_NAME,
                participant = CallRoomParticipant(isActive = false, isEgress = false),
                reportedParticipantCount = 0,
            ),
        )

        assertEquals(CallState.ACTIVE, harness.calls.call.state)
        assertEquals(0, harness.calls.endTransitions)
    }

    private fun harness(
        initialCall: CallRecord,
        participantReader: CallRoomParticipantReader? = null,
    ): LifecycleHarness {
        val calls = MutableCallStore(initialCall)
        val recordings = FakeRecordingStore(calls)
        val pairStore = FakePairStore
        val outbox = mutableListOf<Pair<String, RealtimeEvent>>()
        val recordingController = FakeRecordingController()
        val lifecycle = CallLifecycleService(
            callSessionStore = calls,
            pairStore = pairStore,
            transaction = ImmediateTransaction,
            realtimeOutbox = RealtimeOutbox { userId, event, _ -> outbox += userId to event },
        )
        val service = CallRoomLifecycleService(
            callSessionStore = calls,
            callRecordingStore = recordings,
            recordingConsentStore = AlwaysConsented,
            pairStore = pairStore,
            recordingController = recordingController,
            callLifecycleService = lifecycle,
            recordingEnabled = true,
            consentPolicyVersion = "test-v1",
            participantReader = participantReader,
            nowProvider = { NOW },
        )
        return LifecycleHarness(service, calls, recordingController, outbox)
    }

    private fun joinedEvent(reportedParticipantCount: Int) = CallRoomEvent(
        eventId = "joined-2",
        type = CallRoomEventType.PARTICIPANT_JOINED,
        roomName = ROOM_NAME,
        participant = CallRoomParticipant(isActive = true, isEgress = false),
        reportedParticipantCount = reportedParticipantCount,
    )

    private data class LifecycleHarness(
        val service: CallRoomLifecycleService,
        val calls: MutableCallStore,
        val recordingController: FakeRecordingController,
        val outbox: List<Pair<String, RealtimeEvent>>,
    )
}

class CallRecordingWebhookServiceTest {
    @Test
    fun `ended call stops active provider recording once across duplicate callbacks`() {
        val calls = MutableCallStore(
            waitingCall().copy(
                state = CallState.ENDED,
                connectedAtEpochMillis = NOW.minusSeconds(20).toEpochMilli(),
                endedAtEpochMillis = NOW.minusSeconds(1).toEpochMilli(),
                recordingStatus = RecordingStatus.STARTING,
                recordingId = "recording-1",
            ),
        )
        val recordings = FakeRecordingStore(calls)
        val controller = FakeRecordingController()
        val service = CallRecordingWebhookService(
            callSessionStore = calls,
            callRecordingStore = recordings,
            recordingController = controller,
            nowProvider = { NOW },
        )
        val callback = ProviderRecordingResult(
            status = RecordingStatus.RECORDING,
            recordingId = "recording-1",
            updatedAtEpochMillis = NOW.toEpochMilli(),
        )

        service.handle("recording-1", callback)
        service.handle("recording-1", callback)

        assertEquals(1, controller.stopCalls)
        assertEquals(RecordingStatus.STOPPED, calls.call.recordingStatus)
    }
}

private class MutableCallStore(initialCall: CallRecord) : CallSessionStore {
    var call: CallRecord = initialCall
    var activationTransitions: Int = 0
    var endTransitions: Int = 0
    var recordingClaims: Int = 0

    override fun find(callId: String): CallRecord? = call.takeIf { it.callId == callId }

    override fun findByRoomName(roomName: String): CallRecord? = call.takeIf { it.roomName == roomName }

    override fun findByRecordingId(recordingId: String): CallRecord? = call.takeIf { it.recordingId == recordingId }

    override fun findActiveByPair(pairId: String): CallRecord? =
        call.takeIf { it.pairId == pairId && it.state != CallState.ENDED }

    override fun findOrCreateActive(pairId: String, newCall: () -> CallRecord): ActiveCallResolution =
        ActiveCallResolution(call, created = false)

    override fun activateIfWaiting(callId: String, connectedAtEpochMillis: Long): CallRecord? {
        if (call.callId != callId) return null
        if (call.state == CallState.WAITING && call.connectedAtEpochMillis == null) {
            activationTransitions++
            call = call.copy(
                state = CallState.ACTIVE,
                connectedAtEpochMillis = connectedAtEpochMillis,
                updatedAtEpochMillis = connectedAtEpochMillis,
            )
        }
        return call
    }

    override fun endIfWaiting(callId: String, endedAtEpochMillis: Long): EndCallResolution? =
        if (call.state == CallState.WAITING) endIfOpen(callId, endedAtEpochMillis) else EndCallResolution(call, false)

    override fun endIfOpen(callId: String, endedAtEpochMillis: Long): EndCallResolution? {
        if (call.callId != callId) return null
        if (call.state == CallState.ENDED) return EndCallResolution(call, endedNow = false)
        endTransitions++
        call = call.copy(
            state = CallState.ENDED,
            endedAtEpochMillis = endedAtEpochMillis,
            updatedAtEpochMillis = endedAtEpochMillis,
        )
        return EndCallResolution(call, endedNow = true)
    }

    override fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long): CallRecord? {
        if (call.callId != callId || call.state != CallState.ACTIVE) return null
        if (call.recordingStatus !in setOf(RecordingStatus.IDLE, RecordingStatus.STOPPED, RecordingStatus.FAILED)) {
            return null
        }
        recordingClaims++
        call = call.copy(
            recordingStatus = RecordingStatus.STARTING,
            recordingId = null,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        return call
    }

    override fun findEndedByPairId(
        pairId: String,
        limit: Int,
        cursor: CallHistoryCursor?,
    ): List<CallRecord> = listOf(call).filter { it.pairId == pairId && it.state == CallState.ENDED }
}

private class FakeRecordingStore(
    private val calls: MutableCallStore,
) : CallRecordingStore {
    private val history = linkedMapOf<String, RecordingRecord>()

    override fun updateCurrent(callId: String, result: ProviderRecordingResult): Boolean {
        if (calls.call.callId != callId) return false
        val current = calls.call
        val resultIsCurrent = current.recordingId == null || current.recordingId == result.recordingId
        if (resultIsCurrent && result.updatedAtEpochMillis >= current.updatedAtEpochMillis) {
            calls.call = current.copy(
                recordingStatus = result.status,
                recordingId = result.recordingId,
                updatedAtEpochMillis = result.updatedAtEpochMillis,
            )
        }
        result.recordingId?.let { recordingId ->
            history[recordingId] = recordingRecord(callId, recordingId, result)
        }
        return true
    }

    override fun findByCallId(callId: String): List<RecordingRecord> = history.values.filter { it.callId == callId }

    override fun findByRecordingId(recordingId: String): RecordingRecord? = history[recordingId]
}

private class FakeRecordingController : RecordingController {
    var startCalls: Int = 0
    var stopCalls: Int = 0

    override fun startRecording(callId: String, roomName: String): ProviderRecordingResult {
        startCalls++
        return ProviderRecordingResult(
            status = RecordingStatus.RECORDING,
            recordingId = "recording-1",
            updatedAtEpochMillis = NOW.plusMillis(1).toEpochMilli(),
            startedAtEpochMillis = NOW.toEpochMilli(),
        )
    }

    override fun stopRecording(
        callId: String,
        roomName: String,
        currentRecordingId: String?,
    ): ProviderRecordingResult {
        stopCalls++
        return ProviderRecordingResult(
            status = RecordingStatus.STOPPED,
            recordingId = currentRecordingId,
            updatedAtEpochMillis = NOW.plusMillis(2).toEpochMilli(),
            endedAtEpochMillis = NOW.plusMillis(2).toEpochMilli(),
        )
    }
}

private object FakePairStore : PairStore {
    private val pair = PairRecord(PAIR_ID, "user-a", "user-b", bondedAtEpochMillis = 1L)

    override fun findByUserId(userId: String): PairRecord? = pair

    override fun findByPairId(pairId: String): PairRecord? = pair.takeIf { it.pairId == pairId }
}

private object AlwaysConsented : RecordingConsentStore {
    override fun record(callId: String, userId: String, policyVersion: String, consentedAtEpochMillis: Long) = Unit

    override fun hasAllConsents(callId: String, userIds: Set<String>, policyVersion: String): Boolean = true
}

private object ImmediateTransaction : ApplicationTransaction {
    override fun <T> execute(block: () -> T): T = block()
}

private fun waitingCall() = CallRecord(
    callId = CALL_ID,
    pairId = PAIR_ID,
    roomName = ROOM_NAME,
    createdByUserId = "user-a",
    startedAtEpochMillis = NOW.minusSeconds(30).toEpochMilli(),
    updatedAtEpochMillis = NOW.minusSeconds(30).toEpochMilli(),
    state = CallState.WAITING,
    recordingStatus = RecordingStatus.IDLE,
)

private fun recordingRecord(
    callId: String,
    recordingId: String,
    result: ProviderRecordingResult,
) = RecordingRecord(
    recordingId = recordingId,
    callId = callId,
    status = result.status,
    objectKey = result.objectKey,
    location = result.location,
    startedAtEpochMillis = result.startedAtEpochMillis,
    endedAtEpochMillis = result.endedAtEpochMillis,
    durationMillis = result.durationMillis,
    sizeBytes = result.sizeBytes,
    errorCode = result.errorCode,
    errorMessage = result.errorMessage,
    createdAtEpochMillis = result.updatedAtEpochMillis,
    updatedAtEpochMillis = result.updatedAtEpochMillis,
    deletedAtEpochMillis = null,
    deletionAttempts = 0,
    lastDeletionAttemptAtEpochMillis = null,
    deletionErrorMessage = null,
)

private const val CALL_ID = "call-1"
private const val PAIR_ID = "pair-1"
private const val ROOM_NAME = "pair-1-call-1"
private val NOW: Instant = Instant.parse("2026-07-13T10:00:00Z")

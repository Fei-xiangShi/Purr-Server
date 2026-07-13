package life.fxs.purr.server.application.call

import kotlin.test.Test
import kotlin.test.assertEquals
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.port.ActiveCallResolution
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.EndCallResolution
import life.fxs.purr.server.application.port.PairRecord
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

class CallLifecycleServiceTest {
    @Test
    fun `ending an open call publishes one terminal event to each participant`() {
        val store = FakeCallSessionStore(call())
        val published = mutableListOf<Pair<String, RealtimeEvent>>()
        val service = CallLifecycleService(
            callSessionStore = store,
            pairStore = object : PairStore {
                override fun findByUserId(userId: String): PairRecord? = findByPairId("pair-1")

                override fun findByPairId(pairId: String): PairRecord =
                    PairRecord(pairId, "user-a", "user-b", bondedAtEpochMillis = 1L)
            },
            transaction = object : ApplicationTransaction {
                override fun <T> execute(block: () -> T): T = block()
            },
            realtimeOutbox = RealtimeOutbox { recipientUserId, event, _ ->
                published += recipientUserId to event
            },
        )

        service.endOpenCall("call-1", endedAtEpochMillis = 20_000L)
        service.endOpenCall("call-1", endedAtEpochMillis = 21_000L)

        assertEquals(CallState.ENDED, store.call.state)
        assertEquals(setOf("user-a", "user-b"), published.map { it.first }.toSet())
        assertEquals(2, published.size)
        assertEquals(setOf(RealtimeEvent.CALL_ENDED), published.map { it.second.type }.toSet())
    }
}

private class FakeCallSessionStore(initialCall: CallRecord) : CallSessionStore {
    var call: CallRecord = initialCall
        private set

    override fun find(callId: String): CallRecord? = call.takeIf { it.callId == callId }

    override fun findByRoomName(roomName: String): CallRecord? = call.takeIf { it.roomName == roomName }

    override fun findByRecordingId(recordingId: String): CallRecord? = call.takeIf { it.recordingId == recordingId }

    override fun findActiveByPair(pairId: String): CallRecord? =
        call.takeIf { it.pairId == pairId && it.state != CallState.ENDED }

    override fun findOrCreateActive(pairId: String, newCall: () -> CallRecord): ActiveCallResolution =
        ActiveCallResolution(call, created = false)

    override fun activateIfWaiting(callId: String, connectedAtEpochMillis: Long): CallRecord? = call

    override fun endIfWaiting(callId: String, endedAtEpochMillis: Long): EndCallResolution? =
        endIfOpen(callId, endedAtEpochMillis)

    override fun endIfOpen(callId: String, endedAtEpochMillis: Long): EndCallResolution? {
        if (call.callId != callId) return null
        if (call.state == CallState.ENDED) return EndCallResolution(call, endedNow = false)
        call = call.copy(
            state = CallState.ENDED,
            endedAtEpochMillis = endedAtEpochMillis,
            updatedAtEpochMillis = endedAtEpochMillis,
        )
        return EndCallResolution(call, endedNow = true)
    }

    override fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long): CallRecord? = call

    override fun findEndedByPairId(
        pairId: String,
        limit: Int,
        cursor: CallHistoryCursor?,
    ): List<CallRecord> = listOf(call).filter { it.state == CallState.ENDED }
}

private fun call() = CallRecord(
    callId = "call-1",
    pairId = "pair-1",
    roomName = "room-1",
    createdByUserId = "user-a",
    startedAtEpochMillis = 10_000L,
    updatedAtEpochMillis = 10_000L,
    connectedAtEpochMillis = 11_000L,
    state = CallState.ACTIVE,
    recordingStatus = RecordingStatus.STOPPED,
)

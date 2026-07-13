package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.WaitingCallTerminator

class CallLifecycleService(
    private val callSessionStore: CallSessionStore,
    private val pairStore: PairStore,
    private val transaction: ApplicationTransaction,
    private val realtimeOutbox: RealtimeOutbox,
) : WaitingCallTerminator {
    override fun endWaitingCall(callId: String, endedAtEpochMillis: Long) {
        endCall(callId, endedAtEpochMillis) {
            callSessionStore.endIfWaiting(callId, endedAtEpochMillis)
        }
    }

    fun endOpenCall(callId: String, endedAtEpochMillis: Long) {
        endCall(callId, endedAtEpochMillis) {
            callSessionStore.endIfOpen(callId, endedAtEpochMillis)
        }
    }

    private fun endCall(
        callId: String,
        endedAtEpochMillis: Long,
        transition: () -> life.fxs.purr.server.application.port.EndCallResolution?,
    ) {
        transaction.execute {
            val resolution = transition()
                ?: return@execute
            if (!resolution.endedNow) return@execute

            val pair = checkNotNull(pairStore.findByPairId(resolution.call.pairId)) {
                "Pair not found for call $callId"
            }
            val event = RealtimeEvent(
                type = RealtimeEvent.CALL_ENDED,
                callId = callId,
                pairId = resolution.call.pairId,
            )
            setOf(pair.userAId, pair.userBId).forEach { userId ->
                realtimeOutbox.enqueue(
                    recipientUserId = userId,
                    event = event,
                    occurredAtEpochMillis = endedAtEpochMillis,
                )
            }
        }
    }
}

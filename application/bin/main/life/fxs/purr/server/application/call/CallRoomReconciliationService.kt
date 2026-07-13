package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventHandler
import life.fxs.purr.server.application.port.CallRoomParticipant
import life.fxs.purr.server.application.port.CallRoomEventType
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallRoomReconciliationStore
import life.fxs.purr.server.application.port.WaitingCallTerminator
import life.fxs.purr.server.model.CallState

/**
 * Periodic server-side convergence for calls whose provider webhook was lost
 * or whose client process disappeared without a clean disconnect. The empty
 * observation is persisted, so multiple workers and restarts share the same
 * grace-period clock.
 */
class CallRoomReconciliationService(
    private val store: CallRoomReconciliationStore,
    private val participantReader: CallRoomParticipantReader,
    private val roomEventHandler: CallRoomEventHandler,
    private val waitingCallTerminator: WaitingCallTerminator,
    private val waitingTtlMillis: Long,
    private val emptyRoomGraceMillis: Long,
    private val batchSize: Int,
) {
    fun reconcileOnce(nowEpochMillis: Long): CallRoomReconciliationSummary {
        val calls = store.findOpenCalls(batchSize)
        var inspected = 0
        var converged = 0
        var failed = 0
        calls.forEach { call ->
            inspected++
            try {
                val presentCount = participantReader.countPresentNonEgressParticipants(call.roomName)
                when (call.state) {
                    CallState.WAITING -> {
                        when {
                            presentCount >= MIN_PARTICIPANTS_TO_START -> {
                                roomEventHandler.handle(
                                    CallRoomEvent(
                                        eventId = "reconcile-join-${call.callId}",
                                        type = CallRoomEventType.PARTICIPANT_JOINED,
                                        roomName = call.roomName,
                                        participant = CallRoomParticipant(isActive = true, isEgress = false),
                                        reportedParticipantCount = presentCount,
                                    ),
                                )
                                converged++
                            }
                            nowEpochMillis - call.startedAtEpochMillis >= waitingTtlMillis -> {
                                waitingCallTerminator.endWaitingCall(call.callId, nowEpochMillis)
                                converged++
                            }
                        }
                    }
                    CallState.ACTIVE -> {
                        if (presentCount == 0) {
                            val observed = store.observeRoomEmpty(call.callId, nowEpochMillis)
                            val emptySince = observed?.roomEmptySinceEpochMillis ?: nowEpochMillis
                            if (nowEpochMillis - emptySince >= emptyRoomGraceMillis) {
                                roomEventHandler.handle(
                                    CallRoomEvent(
                                        eventId = "reconcile-empty-${call.callId}",
                                        type = CallRoomEventType.PARTICIPANT_LEFT,
                                        roomName = call.roomName,
                                        participant = CallRoomParticipant(isActive = false, isEgress = false),
                                        reportedParticipantCount = 0,
                                    ),
                                )
                                converged++
                            }
                        } else {
                            store.clearRoomEmptyObservation(call.callId)
                        }
                    }
                    CallState.ENDED -> Unit
                }
            } catch (_: Throwable) {
                // A provider outage must leave the call untouched and retry on
                // the next pass; one bad room must not abort the whole batch.
                failed++
            }
        }
        return CallRoomReconciliationSummary(inspected, converged, failed)
    }

    private companion object {
        const val MIN_PARTICIPANTS_TO_START = 2
    }
}

data class CallRoomReconciliationSummary(
    val inspected: Int,
    val converged: Int,
    val failed: Int,
)

package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventHandler
import life.fxs.purr.server.application.port.CallRoomParticipant
import life.fxs.purr.server.application.port.CallRoomEventType
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.CallRoomReconciliationStore
import life.fxs.purr.server.application.port.WaitingCallTerminator
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus

/**
 * Periodic server-side convergence for calls whose provider/client process
 * restarted without a clean disconnect. The empty
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
    private val recordingCommandStore: RecordingCommandStore,
    /** Reports a call that could not be reconciled in this pass; it is retried on the next one. */
    private val onCallFailure: (callId: String, error: Throwable) -> Unit = { _, _ -> },
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
                            presentCount >= MIN_PARTICIPANTS_TO_START_CALL -> {
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
                            // Recording stops as soon as the room is empty; the
                            // call itself only ends after the grace period.
                            if (!call.recordingPaused || call.recordingStatus.isInFlight ||
                                nowEpochMillis - emptySince >= emptyRoomGraceMillis
                            ) {
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
                            // Repairs a missed departure/return webhook. A room
                            // that is already paused with nothing left to stop
                            // needs no further work while one person waits.
                            val alone = presentCount < MIN_PARTICIPANTS_TO_START_CALL
                            val needsPause = alone && (
                                !call.recordingPaused ||
                                    call.recordingStatus == RecordingStatus.STARTING ||
                                    call.recordingStatus == RecordingStatus.RECORDING
                                )
                            val needsResume = !alone && call.recordingPaused
                            if (needsPause || needsResume) {
                                roomEventHandler.handle(
                                    CallRoomEvent(
                                        eventId = "reconcile-recording-${call.callId}-$nowEpochMillis",
                                        type = if (needsPause) {
                                            CallRoomEventType.PARTICIPANT_LEFT
                                        } else {
                                            CallRoomEventType.PARTICIPANT_JOINED
                                        },
                                        roomName = call.roomName,
                                        participant = CallRoomParticipant(isActive = true, isEgress = false),
                                        reportedParticipantCount = presentCount,
                                    ),
                                )
                            }
                        }
                    }
                    CallState.ENDED -> Unit
                }
            } catch (error: Throwable) {
                // A provider outage must leave the call untouched and retry on
                // the next pass; one bad room must not abort the whole batch.
                failed++
                runCatching { onCallFailure(call.callId, error) }
            }
        }
        store.findEndedCallsForRoomCleanup(batchSize).forEach { call ->
            try {
                if (!call.recordingStatus.isInFlight) {
                    recordingCommandStore.enqueueRoomDelete(
                        callId = call.callId,
                        roomName = call.roomName,
                        requestedAtEpochMillis = nowEpochMillis,
                    )
                    converged++
                }
            } catch (error: Throwable) {
                failed++
                runCatching { onCallFailure(call.callId, error) }
            }
        }
        return CallRoomReconciliationSummary(inspected, converged, failed)
    }
}

data class CallRoomReconciliationSummary(
    val inspected: Int,
    val converged: Int,
    val failed: Int,
)

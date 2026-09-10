package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.application.port.ScreenShareTerminator
import life.fxs.purr.server.application.port.ScreenShareTransition

class ScreenShareLifecycleService(
    private val store: ScreenShareStore,
    private val callSessionStore: CallSessionStore,
    private val pairService: PairService,
    private val transaction: ApplicationTransaction,
    private val realtimeOutbox: RealtimeOutbox,
) : ScreenShareTerminator {
    fun stop(callId: String, stoppedAtEpochMillis: Long, expectedShareId: String? = null): ScreenShareRecord? = transaction.execute {
        val transition = store.requestStop(callId, stoppedAtEpochMillis, expectedShareId) ?: return@execute null
        publishIfChanged(transition, stoppedAtEpochMillis)
        transition.record
    }

    fun expire(shareId: String, expiredAtEpochMillis: Long): ScreenShareRecord? = transaction.execute {
        val transition = store.markExpired(shareId, expiredAtEpochMillis) ?: return@execute null
        publishIfChanged(transition, expiredAtEpochMillis)
        transition.record
    }

    fun fail(shareId: String, failedAtEpochMillis: Long, message: String): ScreenShareRecord? = transaction.execute {
        val transition = store.markFailed(shareId, failedAtEpochMillis, message) ?: return@execute null
        publishIfChanged(transition, failedAtEpochMillis)
        transition.record
    }

    fun stopped(shareId: String, stoppedAtEpochMillis: Long, message: String? = null): ScreenShareRecord? =
        transaction.execute {
            val transition = store.markStopped(shareId, stoppedAtEpochMillis, message) ?: return@execute null
            publishIfChanged(transition, stoppedAtEpochMillis)
            transition.record
        }

    fun publish(record: ScreenShareRecord, occurredAtEpochMillis: Long) = transaction.execute {
        publishToParticipants(record, occurredAtEpochMillis)
    }

    override fun terminateForCall(callId: String, endedAtEpochMillis: Long) {
        stop(callId, endedAtEpochMillis)
    }

    private fun publishIfChanged(transition: ScreenShareTransition, occurredAtEpochMillis: Long) {
        if (transition.changed) publishToParticipants(transition.record, occurredAtEpochMillis)
    }

    private fun publishToParticipants(record: ScreenShareRecord, occurredAtEpochMillis: Long) {
        val event = RealtimeEvent(
            type = RealtimeEvent.SCREEN_SHARE_CHANGED,
            callId = record.callId,
            screenShareId = record.shareId,
            screenShareStatus = record.status.wireValue,
            screenShareSource = record.source.wireValue,
            screenShareOwnerUserId = record.ownerUserId,
        )
        val pairId = checkNotNull(callSessionStore.find(record.callId)?.pairId) {
            "Call not found for screen share ${record.shareId}"
        }
        pairService.requirePairUserIds(pairId).forEach { userId ->
            realtimeOutbox.enqueue(userId, event, occurredAtEpochMillis)
        }
    }
}

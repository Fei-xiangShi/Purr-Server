package life.fxs.purr.server.push

import java.time.Instant
import kotlinx.coroutines.CancellationException
import life.fxs.purr.server.application.port.IncomingCallPushMessage
import life.fxs.purr.server.application.port.PushDeliveryResult
import life.fxs.purr.server.application.port.PushDeviceStore
import life.fxs.purr.server.application.port.PushNotificationSender
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeEventSink
import org.slf4j.LoggerFactory

class IncomingCallPushEventSink(
    private val deviceStore: PushDeviceStore,
    private val sender: PushNotificationSender,
    private val enabled: Boolean,
    private val nowProvider: () -> Instant = Instant::now,
    /**
     * The outbox redelivers a failed event with backoff for minutes. A wake-up
     * for a call that was answered, cancelled or expired in the meantime would
     * ring a phone for nothing, so delivery is limited to calls still ringing.
     */
    private val isCallRinging: (callId: String) -> Boolean = { true },
) : RealtimeEventSink {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun publishToUser(userId: String, event: RealtimeEvent) {
        if (!enabled || event.type != RealtimeEvent.CALL_STARTED) return
        val callId = event.callId ?: error("Incoming call push event is missing callId")
        val startedAt = event.startedAtEpochMillis
            ?: error("Incoming call push event is missing startedAtEpochMillis")
        if (!isCallRinging(callId)) {
            logger.info("Skipping incoming-call push for call {} because it is no longer ringing", callId)
            return
        }
        val message = IncomingCallPushMessage(callId, startedAt)
        val failures = mutableListOf<Throwable>()

        deviceStore.findActiveByUserId(userId).forEach { device ->
            try {
                when (val result = sender.send(device, message)) {
                    PushDeliveryResult.Delivered -> Unit
                    // Not retried: a retry would only repeat the wake-up on the
                    // user's other devices. The token itself is never logged.
                    is PushDeliveryResult.Rejected -> logger.warn(
                        "Push provider rejected incoming-call push for call {} installation {}: {}",
                        callId,
                        device.installationId,
                        result.reason,
                    )
                    PushDeliveryResult.DeviceUnregistered -> deviceStore.disable(
                        provider = device.provider,
                        token = device.token,
                        disabledAtEpochMillis = nowProvider().toEpochMilli(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failures += error
            }
        }

        if (failures.isNotEmpty()) {
            val combined = IllegalStateException("Incoming call push delivery failed")
            failures.forEach(combined::addSuppressed)
            throw combined
        }
    }

    override fun isReady(): Boolean = !enabled || sender.isReady()
}

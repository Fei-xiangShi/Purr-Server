package life.fxs.purr.server.realtime

import kotlinx.serialization.Serializable
import life.fxs.purr.server.application.port.RealtimeEvent

@Serializable
internal data class RealtimeEventPayload(
    val type: String,
    val partnerOnline: Boolean? = null,
    val callId: String? = null,
    val pairId: String? = null,
    val callerUserId: String? = null,
    val startedAtEpochMillis: Long? = null,
    val screenShareId: String? = null,
    val screenShareStatus: String? = null,
    val screenShareSource: String? = null,
    val screenShareOwnerUserId: String? = null,
)

internal fun RealtimeEvent.toPayload() = RealtimeEventPayload(
    type = type,
    partnerOnline = partnerOnline,
    callId = callId,
    pairId = pairId,
    callerUserId = callerUserId,
    startedAtEpochMillis = startedAtEpochMillis,
    screenShareId = screenShareId,
    screenShareStatus = screenShareStatus,
    screenShareSource = screenShareSource,
    screenShareOwnerUserId = screenShareOwnerUserId,
)

internal fun RealtimeEventPayload.toApplicationEvent() = RealtimeEvent(
    type = type,
    partnerOnline = partnerOnline,
    callId = callId,
    pairId = pairId,
    callerUserId = callerUserId,
    startedAtEpochMillis = startedAtEpochMillis,
    screenShareId = screenShareId,
    screenShareStatus = screenShareStatus,
    screenShareSource = screenShareSource,
    screenShareOwnerUserId = screenShareOwnerUserId,
)

class RealtimeEventEncoder(
    private val json: kotlinx.serialization.json.Json = realtimeJson,
) {
    fun encode(event: RealtimeEvent): String = json.encodeToString(RealtimeEventPayload.serializer(), event.toPayload())
}

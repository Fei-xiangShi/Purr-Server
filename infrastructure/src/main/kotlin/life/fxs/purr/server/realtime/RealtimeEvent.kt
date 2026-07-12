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
)

internal fun RealtimeEvent.toPayload() = RealtimeEventPayload(
    type = type,
    partnerOnline = partnerOnline,
    callId = callId,
    pairId = pairId,
    callerUserId = callerUserId,
    startedAtEpochMillis = startedAtEpochMillis,
)

internal fun RealtimeEventPayload.toApplicationEvent() = RealtimeEvent(
    type = type,
    partnerOnline = partnerOnline,
    callId = callId,
    pairId = pairId,
    callerUserId = callerUserId,
    startedAtEpochMillis = startedAtEpochMillis,
)

class RealtimeEventEncoder(
    private val json: kotlinx.serialization.json.Json = realtimeJson,
) {
    fun encode(event: RealtimeEvent): String = json.encodeToString(RealtimeEventPayload.serializer(), event.toPayload())
}

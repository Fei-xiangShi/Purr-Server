package life.fxs.purr.server.livekit

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.call.CallRecordingWebhookService
import life.fxs.purr.server.application.call.CallRoomLifecycleService
import life.fxs.purr.server.application.port.CallRoomEvent
import life.fxs.purr.server.application.port.CallRoomEventType
import life.fxs.purr.server.application.port.CallRoomParticipant
import life.fxs.purr.server.application.port.WebhookInboxClaimState
import life.fxs.purr.server.application.port.WebhookInboxStore
import life.fxs.purr.server.config.LiveKitConfig
import livekit.LivekitModels
import livekit.LivekitWebhook

/**
 * LiveKit adapter boundary. It verifies the provider signature, maps provider
 * payloads to application models, and delegates all call behavior to use
 * cases. No call state or recording orchestration belongs here.
 */
class LiveKitWebhookService(
    liveKitConfig: LiveKitConfig,
    private val callRoomLifecycleService: CallRoomLifecycleService,
    private val callRecordingWebhookService: CallRecordingWebhookService,
    private val webhookInboxStore: WebhookInboxStore,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val leaseOwnerProvider: () -> String = { "webhook-${UUID.randomUUID()}" },
) {
    private val receiver = io.livekit.server.WebhookReceiver(liveKitConfig.apiKey, liveKitConfig.apiSecret)

    fun handle(body: String, authorization: String?): LiveKitWebhookAck {
        val event = try {
            receiver.receive(body, authorization)
        } catch (error: IllegalArgumentException) {
            throw ApplicationException(
                ApplicationError.UNAUTHENTICATED,
                error.message ?: "Invalid LiveKit webhook",
            )
        }

        val eventId = event.id.takeIf(String::isNotBlank)
            ?: throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "LiveKit webhook event id is required")
        val leaseOwner = leaseOwnerProvider()
        val claimedAt = nowProvider()
        val claim = webhookInboxStore.claim(
            provider = PROVIDER,
            eventId = eventId,
            eventType = event.event,
            payload = body,
            payloadHash = body.stablePayloadHash(),
            leaseOwner = leaseOwner,
            nowEpochMillis = claimedAt,
            leaseUntilEpochMillis = claimedAt + PROCESSING_LEASE_MILLIS,
        )
        val ack = LiveKitWebhookAck(event = event.event, id = eventId)
        when (claim.state) {
            WebhookInboxClaimState.PROCESSED -> return ack
            WebhookInboxClaimState.IN_FLIGHT -> throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit webhook event is already being processed",
            )
            WebhookInboxClaimState.CLAIMED -> Unit
        }

        try {
            event.toCallRoomEvent()?.let(callRoomLifecycleService::handle)

            if (event.hasEgressInfo()) {
                val recordingId = event.egressInfo.egressId.takeIf { it.isNotBlank() }
                if (recordingId != null) {
                    callRecordingWebhookService.handle(
                        recordingId = recordingId,
                        result = event.egressInfo.toRecordingResult().copy(recordingId = recordingId),
                    )
                }
            }
            check(
                webhookInboxStore.markProcessed(
                    provider = PROVIDER,
                    eventId = eventId,
                    leaseOwner = leaseOwner,
                    processedAtEpochMillis = nowProvider(),
                ),
            ) { "Lost webhook inbox lease before marking $PROVIDER/$eventId processed" }
        } catch (error: Throwable) {
            runCatching {
                webhookInboxStore.releaseForRetry(
                    provider = PROVIDER,
                    eventId = eventId,
                    leaseOwner = leaseOwner,
                    errorMessage = error.message ?: error::class.simpleName.orEmpty(),
                    availableAtEpochMillis = nowProvider(),
                )
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return ack
    }

    private fun LivekitWebhook.WebhookEvent.toCallRoomEvent(): CallRoomEvent? {
        val type = when (event) {
            PARTICIPANT_JOINED_EVENT -> CallRoomEventType.PARTICIPANT_JOINED
            PARTICIPANT_LEFT_EVENT -> CallRoomEventType.PARTICIPANT_LEFT
            ROOM_FINISHED_EVENT -> CallRoomEventType.ROOM_FINISHED
            else -> return null
        }
        if (!hasRoom()) return null
        val roomName = room.name.takeIf { it.isNotBlank() } ?: return null
        val participant = if (hasParticipant()) {
            participant.let {
                CallRoomParticipant(
                    isActive = it.state == LivekitModels.ParticipantInfo.State.ACTIVE,
                    isEgress = it.kind == LivekitModels.ParticipantInfo.Kind.EGRESS,
                    identity = it.identity.takeIf(String::isNotBlank),
                )
            }
        } else {
            null
        }
        return CallRoomEvent(
            eventId = id,
            type = type,
            roomName = roomName,
            participant = participant,
            reportedParticipantCount = room.numParticipants,
        )
    }

    private companion object {
        const val PROVIDER = "livekit"
        const val PROCESSING_LEASE_MILLIS = 120_000L
        const val PARTICIPANT_JOINED_EVENT = "participant_joined"
        const val PARTICIPANT_LEFT_EVENT = "participant_left"
        const val ROOM_FINISHED_EVENT = "room_finished"
    }
}

private fun String.stablePayloadHash(): String {
    val canonicalPayload = runCatching {
        Json.parseToJsonElement(this).canonicalJson()
    }.getOrElse { this }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun JsonElement.canonicalJson(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${value.canonicalJson()}"
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    else -> toString()
}


@kotlinx.serialization.Serializable
data class LiveKitWebhookAck(
    val event: String,
    val id: String,
)

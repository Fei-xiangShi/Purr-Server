package life.fxs.purr.server.livekit

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import io.livekit.server.RoomServiceClient
import life.fxs.purr.server.config.LiveKitConfig
import livekit.LivekitModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface RoomParticipantService : CallRoomParticipantReader

class LiveKitRoomParticipantService(
    private val liveKitConfig: LiveKitConfig,
) : RoomParticipantService {
    private val roomClient: RoomServiceClient by lazy {
        RoomServiceClient.createClient(
            liveKitConfig.httpUrl,
            liveKitConfig.apiKey,
            liveKitConfig.apiSecret,
        )
    }

    override fun countActiveNonEgressParticipants(roomName: String): Int = listRelevantParticipants(roomName)
        .count { it.state == LivekitModels.ParticipantInfo.State.ACTIVE }

    override fun countPresentNonEgressParticipants(roomName: String): Int = listRelevantParticipants(roomName)
        .count { it.state != LivekitModels.ParticipantInfo.State.DISCONNECTED }

    override fun activeNonEgressParticipantIdentities(roomName: String): Set<String> = listRelevantParticipants(roomName)
        .asSequence()
        .filter { it.state == LivekitModels.ParticipantInfo.State.ACTIVE }
        .mapNotNull { it.identity.takeIf(String::isNotBlank) }
        .toSet()

    override fun presentNonEgressParticipantIdentities(roomName: String): Set<String> = listRelevantParticipants(roomName)
        .asSequence()
        .filter { it.state != LivekitModels.ParticipantInfo.State.DISCONNECTED }
        .mapNotNull { it.identity.takeIf(String::isNotBlank) }
        .toSet()

    private fun listRelevantParticipants(roomName: String): List<LivekitModels.ParticipantInfo> {
        val response = roomClient.listParticipants(roomName).execute()
        // A provider-confirmed missing room is an empty inventory. A proxy 404,
        // authorization failure, or provider outage is not proof of absence.
        if (response.code() == 404) {
            val code = runCatching {
                Json.parseToJsonElement(response.errorBody()?.string().orEmpty())
                    .jsonObject["code"]?.jsonPrimitive?.content
            }.getOrNull()
            if (code == "not_found") return emptyList()
        }
        if (!response.isSuccessful) {
            throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit failed to list participants: ${response.code()} ${response.message()}",
            )
        }
        return response.body()?.filter { it.kind != LivekitModels.ParticipantInfo.Kind.EGRESS }
            ?: throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit returned empty response while trying to list participants",
            )
    }
}

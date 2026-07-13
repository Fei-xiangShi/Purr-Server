package life.fxs.purr.server.livekit

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import io.livekit.server.RoomServiceClient
import life.fxs.purr.server.config.LiveKitConfig
import livekit.LivekitModels
import retrofit2.Call

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

    private fun listRelevantParticipants(roomName: String): List<LivekitModels.ParticipantInfo> = roomClient.listParticipants(roomName)
        .executeOrThrow("list participants")
        .filter { participant -> participant.kind != LivekitModels.ParticipantInfo.Kind.EGRESS }

    private fun <T> Call<T>.executeOrThrow(action: String): T {
        val response = execute()
        if (!response.isSuccessful) {
            throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit failed to $action: ${response.code()} ${response.message()}",
            )
        }
        return response.body()
            ?: throw ApplicationException(
                ApplicationError.EXTERNAL_DEPENDENCY,
                "LiveKit returned empty response while trying to $action",
            )
    }
}

package life.fxs.purr.server.application.model

import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

data class CreateScreenShareCommand(
    val source: ScreenShareSource,
)

data class ScreenShareMediaEndpointResult(
    val url: String,
    val bearerToken: String,
    val expiresAtEpochMillis: Long,
)

data class ScreenShareSrtResult(
    val url: String,
    val streamId: String,
    val passphrase: String,
)

data class ScreenSharePublishingResult(
    val whip: ScreenShareMediaEndpointResult,
    val srt: ScreenShareSrtResult?,
)

data class ScreenShareResult(
    val shareId: String,
    val callId: String,
    val ownerUserId: String,
    val source: ScreenShareSource,
    val status: ScreenShareStatus,
    val mediaPath: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val liveAtEpochMillis: Long?,
    val stoppedAtEpochMillis: Long?,
    val publishing: ScreenSharePublishingResult?,
    val playback: ScreenShareMediaEndpointResult?,
    val errorMessage: String?,
)

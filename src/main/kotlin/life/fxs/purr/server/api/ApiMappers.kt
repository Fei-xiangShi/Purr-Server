package life.fxs.purr.server.api

import life.fxs.purr.server.application.model.ActiveCallResult
import life.fxs.purr.server.application.model.AuthSessionResult
import life.fxs.purr.server.application.model.CallRecordingResult
import life.fxs.purr.server.application.model.CallSessionResult
import life.fxs.purr.server.application.model.CallStatusResult
import life.fxs.purr.server.application.model.PairDetails
import life.fxs.purr.server.application.model.PartnerProfile
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.model.RecordingLibraryResult
import life.fxs.purr.server.application.model.RecordingResultView
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.model.ActiveCallDto
import life.fxs.purr.server.model.AuthSessionDto
import life.fxs.purr.server.model.CallRecordingDto
import life.fxs.purr.server.model.CallStatusDto
import life.fxs.purr.server.model.PairBond
import life.fxs.purr.server.model.PairedPartner
import life.fxs.purr.server.model.RecordingDownloadDto
import life.fxs.purr.server.model.RecordingLibraryResponseDto
import life.fxs.purr.server.model.RecordingResponseDto
import life.fxs.purr.server.model.SelfProfile
import life.fxs.purr.server.model.SessionResponseDto

internal fun AuthSessionResult.toDto() = AuthSessionDto(
    accessToken = accessToken,
    refreshToken = refreshToken,
    self = self.toDto(),
)

internal fun UserProfile.toDto() = SelfProfile(userId, displayName, avatarUrl)

internal fun PairDetails.toDto() = PairBond(
    pairId = pairId,
    self = self.toDto(),
    partner = partner.toDto(),
    bondedAtEpochMillis = bondedAtEpochMillis,
)

private fun PartnerProfile.toDto() = PairedPartner(
    userId = userId,
    displayName = displayName,
    avatarUrl = avatarUrl,
    isOnline = isOnline,
    isCallable = isCallable,
)

internal fun CallSessionResult.toDto() = SessionResponseDto(
    callId = callId,
    pairId = pairId,
    roomName = roomName,
    participantIdentity = participantIdentity,
    token = token,
    wsUrl = wsUrl,
)

internal fun CallStatusResult.toDto() = CallStatusDto(
    callId = callId,
    pairId = pairId,
    state = state.wireValue,
    recordingStatus = recordingStatus.wireValue,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
)

internal fun ActiveCallResult.toDto() = ActiveCallDto(
    callId = callId,
    pairId = pairId,
    callerUserId = callerUserId,
    isIncoming = isIncoming,
    startedAtEpochMillis = startedAtEpochMillis,
)

internal fun RecordingResultView.toDto() = RecordingResponseDto(
    callId = callId,
    status = status.wireValue,
    recordingId = recordingId,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun CallRecordingResult.toDto() = CallRecordingDto(
    recordingId = recordingId,
    callId = callId,
    status = status.wireValue,
    downloadAvailable = downloadAvailable,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    durationMillis = durationMillis,
    sizeBytes = sizeBytes,
    errorCode = errorCode,
    errorMessage = errorMessage,
)

internal fun RecordingDownloadResult.toDto() = RecordingDownloadDto(
    recordingId = recordingId,
    url = url,
    expiresAtEpochMillis = expiresAtEpochMillis,
)

internal fun RecordingLibraryResult.toDto() = RecordingLibraryResponseDto(
    recordings = recordings.map { it.toDto() },
    nextCursor = nextCursor,
)

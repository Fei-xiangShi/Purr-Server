package life.fxs.purr.server.api

import life.fxs.purr.server.application.model.ActiveCallResult
import life.fxs.purr.server.application.model.AuthSessionResult
import life.fxs.purr.server.application.model.CallRecordingResult
import life.fxs.purr.server.application.model.CallHistoryItemResult
import life.fxs.purr.server.application.model.CallHistoryResult
import life.fxs.purr.server.application.model.CallCalendarResult
import life.fxs.purr.server.application.model.CallDetailResult
import life.fxs.purr.server.application.model.CallQualitySummaryResult
import life.fxs.purr.server.application.model.CallSessionResult
import life.fxs.purr.server.application.model.CallStatusResult
import life.fxs.purr.server.application.model.PairDetails
import life.fxs.purr.server.application.model.PartnerProfile
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.application.model.RecordingResultView
import life.fxs.purr.server.application.model.UserProfile
import life.fxs.purr.server.model.ActiveCallDto
import life.fxs.purr.server.model.AuthSessionDto
import life.fxs.purr.server.model.CallRecordingDto
import life.fxs.purr.server.model.CallHistoryItemDto
import life.fxs.purr.server.model.CallHistoryResponseDto
import life.fxs.purr.server.model.CallCalendarDayDto
import life.fxs.purr.server.model.CallCalendarResponseDto
import life.fxs.purr.server.model.CallDetailDto
import life.fxs.purr.server.model.CallQualitySummaryDto
import life.fxs.purr.server.model.CallStatusDto
import life.fxs.purr.server.model.PairBond
import life.fxs.purr.server.model.PairedPartner
import life.fxs.purr.server.model.RecordingDownloadDto
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
    createdByRequest = createdByRequest,
)

internal fun CallStatusResult.toDto() = CallStatusDto(
    callId = callId,
    pairId = pairId,
    state = state.wireValue,
    recordingStatus = recordingStatus.wireValue,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    durationMillis = durationMillis,
    serverNowEpochMillis = serverNowEpochMillis,
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

internal fun CallHistoryResult.toDto() = CallHistoryResponseDto(
    calls = calls.map(CallHistoryItemResult::toDto),
    nextCursor = nextCursor,
)

private fun CallHistoryItemResult.toDto() = CallHistoryItemDto(
    callId = callId,
    direction = direction.wireValue,
    outcome = outcome.wireValue,
    requestedAtEpochMillis = requestedAtEpochMillis,
    startedAtEpochMillis = startedAtEpochMillis,
    connectedAtEpochMillis = connectedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    ringingDurationMillis = ringingDurationMillis,
    durationMillis = durationMillis,
    recordingStatus = recordingStatus.wireValue,
)

internal fun CallCalendarResult.toDto() = CallCalendarResponseDto(
    days = days.map { day ->
        CallCalendarDayDto(day.date, day.callCount, day.totalDurationMillis)
    },
)

internal fun CallDetailResult.toDto() = CallDetailDto(
    callId = callId,
    direction = direction.wireValue,
    outcome = outcome.wireValue,
    requestedAtEpochMillis = requestedAtEpochMillis,
    connectedAtEpochMillis = connectedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    ringingDurationMillis = ringingDurationMillis,
    durationMillis = durationMillis,
    recordingStatus = recordingStatus.wireValue,
    recordingCount = recordingCount,
    recordingAvailable = recordingAvailable,
    quality = quality?.toDto(),
)

private fun CallQualitySummaryResult.toDto() = CallQualitySummaryDto(
    sampleCount = sampleCount,
    averageRoundTripTimeMs = averageRoundTripTimeMs,
    averageJitterMs = averageJitterMs,
    averagePacketLossPercent = averagePacketLossPercent,
    maximumPacketLossPercent = maximumPacketLossPercent,
    averageUplinkBitrateKbps = averageUplinkBitrateKbps,
    averageDownlinkBitrateKbps = averageDownlinkBitrateKbps,
    networkTransports = networkTransports,
    codecs = codecs,
)

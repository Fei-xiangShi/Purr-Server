package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CallDirection
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.model.CallHistoryCursorCodec
import life.fxs.purr.server.application.model.CallHistoryItemResult
import life.fxs.purr.server.application.model.CallHistoryResult
import life.fxs.purr.server.application.model.CallOutcome
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.model.CallDurationPolicy

class CallHistoryQueryService(
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
) {
    fun getHistory(userId: String, limit: Int, cursor: CallHistoryCursor?): CallHistoryResult {
        val pairId = pairService.requirePairId(userId)
        return callSessionStore.findEndedByPairId(pairId, limit + 1, cursor)
            .toHistoryResult(userId, limit)
    }

    fun getDay(
        userId: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int,
        cursor: CallHistoryCursor?,
    ): CallHistoryResult {
        validateRange(fromEpochMillis, toEpochMillis, MAX_DAY_RANGE_MILLIS)
        val pairId = pairService.requirePairId(userId)
        return callSessionStore.findEndedByPairIdBetween(
            pairId = pairId,
            fromEpochMillis = fromEpochMillis,
            toEpochMillis = toEpochMillis,
            limit = limit + 1,
            cursor = cursor,
        ).toHistoryResult(userId, limit)
    }

    private fun List<CallRecord>.toHistoryResult(userId: String, limit: Int): CallHistoryResult {
        val hasMore = size > limit
        val calls = take(limit)
        return CallHistoryResult(
            calls = calls.map { it.toHistoryItem(userId) },
            nextCursor = calls.lastOrNull()?.takeIf { hasMore }?.let { call ->
                CallHistoryCursorCodec.encode(CallHistoryCursor(call.startedAtEpochMillis, call.callId))
            },
        )
    }

    private companion object {
        const val MAX_DAY_RANGE_MILLIS = 36L * 60L * 60L * 1_000L
    }
}

internal fun CallRecord.toHistoryItem(userId: String): CallHistoryItemResult {
    val endedAt = requireNotNull(endedAtEpochMillis) { "History query returned an active call: $callId" }
    val connectedAt = connectedAtEpochMillis
    val direction = if (createdByUserId == userId) CallDirection.OUTGOING else CallDirection.INCOMING
    val outcome = when {
        connectedAt != null -> CallOutcome.COMPLETED
        direction == CallDirection.INCOMING -> CallOutcome.MISSED
        else -> CallOutcome.CANCELLED
    }
    return CallHistoryItemResult(
        callId = callId,
        direction = direction,
        outcome = outcome,
        requestedAtEpochMillis = startedAtEpochMillis,
        startedAtEpochMillis = connectedAt ?: startedAtEpochMillis,
        connectedAtEpochMillis = connectedAt,
        endedAtEpochMillis = endedAt,
        ringingDurationMillis = ((connectedAt ?: endedAt) - startedAtEpochMillis).coerceAtLeast(0L),
        durationMillis = durationMillis
            ?: CallDurationPolicy.completedDurationMillis(connectedAt, endedAt)
            ?: 0L,
        recordingStatus = recordingStatus,
    )
}

internal fun validateRange(fromEpochMillis: Long, toEpochMillis: Long, maxRangeMillis: Long) {
    if (fromEpochMillis < 0L || toEpochMillis <= fromEpochMillis || toEpochMillis - fromEpochMillis > maxRangeMillis) {
        throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Invalid call history time range")
    }
}

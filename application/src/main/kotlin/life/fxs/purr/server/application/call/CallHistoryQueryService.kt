package life.fxs.purr.server.application.call

import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.model.CallHistoryCursorCodec
import life.fxs.purr.server.application.model.CallHistoryItemResult
import life.fxs.purr.server.application.model.CallHistoryResult
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore

class CallHistoryQueryService(
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
) {
    fun getHistory(userId: String, limit: Int, cursor: CallHistoryCursor?): CallHistoryResult {
        val pairId = pairService.requirePairId(userId)
        val page = callSessionStore.findEndedByPairId(pairId, limit + 1, cursor)
        val hasMore = page.size > limit
        val calls = page.take(limit)
        return CallHistoryResult(
            calls = calls.map(CallRecord::toHistoryItem),
            nextCursor = calls.lastOrNull()?.takeIf { hasMore }?.let { call ->
                CallHistoryCursorCodec.encode(
                    CallHistoryCursor(requireNotNull(call.connectedAtEpochMillis), call.callId),
                )
            },
        )
    }
}

private fun CallRecord.toHistoryItem(): CallHistoryItemResult {
    val endedAt = requireNotNull(endedAtEpochMillis) { "History query returned an active call: $callId" }
    val connectedAt = requireNotNull(connectedAtEpochMillis) {
        "History query returned a call without a server connection boundary: $callId"
    }
    return CallHistoryItemResult(
        callId = callId,
        startedAtEpochMillis = connectedAt,
        durationMillis = (endedAt - connectedAt).coerceAtLeast(0L),
    )
}

package life.fxs.purr.server.application.call

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CallCalendarDayResult
import life.fxs.purr.server.application.model.CallCalendarResult
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.model.CallDurationPolicy

class CallCalendarQueryService(
    private val pairService: PairService,
    private val callSessionStore: CallSessionStore,
) {
    fun getCalendar(
        userId: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        zoneIdValue: String,
    ): CallCalendarResult {
        validateRange(fromEpochMillis, toEpochMillis, MAX_CALENDAR_RANGE_MILLIS)
        val zoneId = try {
            ZoneId.of(zoneIdValue)
        } catch (_: DateTimeException) {
            throw ApplicationException(ApplicationError.INVALID_ARGUMENT, "Invalid calendar time zone")
        }
        val pairId = pairService.requirePairId(userId)
        val calls = callSessionStore.findEndedByPairIdBetween(
            pairId = pairId,
            fromEpochMillis = fromEpochMillis,
            toEpochMillis = toEpochMillis,
            limit = MAX_CALENDAR_CALLS + 1,
        )
        if (calls.size > MAX_CALENDAR_CALLS) {
            throw ApplicationException(ApplicationError.CONFLICT, "Calendar range contains too many calls")
        }
        return CallCalendarResult(
            days = calls
                .groupBy { call ->
                    Instant.ofEpochMilli(call.startedAtEpochMillis).atZone(zoneId).toLocalDate()
                }
                .toSortedMap()
                .map { (date, dayCalls) ->
                    CallCalendarDayResult(
                        date = date.toString(),
                        callCount = dayCalls.size,
                        totalDurationMillis = dayCalls.sumOf { call ->
                            call.durationMillis ?: CallDurationPolicy.completedDurationMillis(
                                connectedAtEpochMillis = call.connectedAtEpochMillis,
                                endedAtEpochMillis = call.endedAtEpochMillis,
                            ) ?: 0L
                        },
                    )
                },
        )
    }

    private companion object {
        const val MAX_CALENDAR_RANGE_MILLIS = 370L * 24L * 60L * 60L * 1_000L
        const val MAX_CALENDAR_CALLS = 10_000
    }
}

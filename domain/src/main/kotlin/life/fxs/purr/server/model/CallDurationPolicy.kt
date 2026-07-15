package life.fxs.purr.server.model

object CallDurationPolicy {
    const val MINIMUM_HISTORY_DURATION_MILLIS = 30_000L
    const val MINIMUM_RECORDING_DURATION_MILLIS = 30_000L

    fun completedDurationMillis(
        connectedAtEpochMillis: Long?,
        endedAtEpochMillis: Long?,
    ): Long? {
        if (connectedAtEpochMillis == null || endedAtEpochMillis == null) return null
        return (endedAtEpochMillis - connectedAtEpochMillis).coerceAtLeast(0L)
    }

    fun isRecordingEligible(
        connectedAtEpochMillis: Long?,
        nowEpochMillis: Long,
    ): Boolean = connectedAtEpochMillis != null &&
        nowEpochMillis - connectedAtEpochMillis >= MINIMUM_RECORDING_DURATION_MILLIS

    fun recordingAvailableAtEpochMillis(connectedAtEpochMillis: Long): Long =
        connectedAtEpochMillis + MINIMUM_RECORDING_DURATION_MILLIS
}

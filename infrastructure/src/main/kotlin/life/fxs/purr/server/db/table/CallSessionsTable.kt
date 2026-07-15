package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object CallSessionsTable : Table("call_sessions") {
    val callId = varchar("call_id", 128)
    val pairId = varchar("pair_id", 64).references(PairBondsTable.pairId)
    val activePairId = varchar("active_pair_id", 64).nullable().uniqueIndex()
    val roomName = varchar("room_name", 255).uniqueIndex()
    val createdByUserId = varchar("created_by_user_id", 64).references(UsersTable.id)
    val startedAtEpochMillis = long("started_at_epoch_millis")
    val updatedAtEpochMillis = long("updated_at_epoch_millis")
    val recordingProviderUpdatedAtEpochMillis = long("recording_provider_updated_at_epoch_millis").nullable()
    val endedAtEpochMillis = long("ended_at_epoch_millis").nullable()
    val connectedAtEpochMillis = long("connected_at_epoch_millis").nullable()
    val durationMillis = long("duration_millis").nullable()
    val roomEmptySinceEpochMillis = long("room_empty_since_epoch_millis").nullable()
    val callState = varchar("call_state", 32)
    val recordingStatus = varchar("recording_status", 32)
    val recordingId = varchar("recording_id", 255).nullable()
    val recordingRecoveryAttempts = integer("recording_recovery_attempts").default(0)
    val recordingLastRecoveryAtEpochMillis = long("recording_last_recovery_at_epoch_millis").nullable()
    val recordingErrorMessage = varchar("recording_error_message", 2048).nullable()

    override val primaryKey = PrimaryKey(callId)
}

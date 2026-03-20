package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object CallSessionsTable : Table("call_sessions") {
    val callId = varchar("call_id", 128)
    val pairId = varchar("pair_id", 64).references(PairBondsTable.pairId)
    val roomName = varchar("room_name", 255)
    val createdByUserId = varchar("created_by_user_id", 64).references(UsersTable.id)
    val startedAtEpochMillis = long("started_at_epoch_millis")
    val updatedAtEpochMillis = long("updated_at_epoch_millis")
    val endedAtEpochMillis = long("ended_at_epoch_millis").nullable()
    val callState = varchar("call_state", 32)
    val recordingStatus = varchar("recording_status", 32)
    val recordingId = varchar("recording_id", 255).nullable()

    override val primaryKey = PrimaryKey(callId)
}

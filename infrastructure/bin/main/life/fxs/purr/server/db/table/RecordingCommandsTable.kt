package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object RecordingCommandsTable : Table("recording_commands") {
    val commandId = varchar("command_id", 128)
    val idempotencyKey = varchar("idempotency_key", 512).uniqueIndex()
    val callId = varchar("call_id", 128).references(CallSessionsTable.callId)
    val roomName = varchar("room_name", 255)
    val commandType = varchar("command_type", 16)
    val recordingId = varchar("recording_id", 255).nullable()
    val requestedAtEpochMillis = long("requested_at_epoch_millis")
    val availableAtEpochMillis = long("available_at_epoch_millis")
    val attemptCount = integer("attempt_count").default(0)
    val leaseOwner = varchar("lease_owner", 128).nullable()
    val leaseUntilEpochMillis = long("lease_until_epoch_millis").nullable()
    val commandState = varchar("command_state", 16)
    val completedAtEpochMillis = long("completed_at_epoch_millis").nullable()
    val lastError = varchar("last_error", 2048).nullable()

    override val primaryKey = PrimaryKey(commandId)
}

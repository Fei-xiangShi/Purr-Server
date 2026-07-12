package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object CallRecordingsTable : Table("call_recordings") {
    val recordingId = varchar("recording_id", 255)
    val callId = varchar("call_id", 128).references(CallSessionsTable.callId)
    val recordingStatus = varchar("recording_status", 32)
    val objectKey = varchar("object_key", 1024).nullable()
    val location = varchar("location", 2048).nullable()
    val startedAtEpochMillis = long("started_at_epoch_millis").nullable()
    val endedAtEpochMillis = long("ended_at_epoch_millis").nullable()
    val durationMillis = long("duration_millis").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val errorCode = integer("error_code").nullable()
    val errorMessage = varchar("error_message", 2048).nullable()
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val updatedAtEpochMillis = long("updated_at_epoch_millis")
    val deletedAtEpochMillis = long("deleted_at_epoch_millis").nullable()
    val deletionAttempts = integer("deletion_attempts").default(0)
    val lastDeletionAttemptAtEpochMillis = long("last_deletion_attempt_at_epoch_millis").nullable()
    val deletionErrorMessage = varchar("deletion_error_message", 2048).nullable()

    override val primaryKey = PrimaryKey(recordingId)
}

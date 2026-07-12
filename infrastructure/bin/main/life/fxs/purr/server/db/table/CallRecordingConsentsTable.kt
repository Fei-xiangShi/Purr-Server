package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object CallRecordingConsentsTable : Table("call_recording_consents") {
    val callId = varchar("call_id", 128).references(CallSessionsTable.callId)
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val policyVersion = varchar("policy_version", 64)
    val consentedAtEpochMillis = long("consented_at_epoch_millis")

    override val primaryKey = PrimaryKey(callId, userId, policyVersion)
}

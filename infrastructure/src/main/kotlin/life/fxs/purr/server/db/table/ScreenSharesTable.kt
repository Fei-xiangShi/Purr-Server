package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object ScreenSharesTable : Table("screen_shares") {
    val shareId = varchar("share_id", 64)
    val callId = varchar("call_id", 128).references(CallSessionsTable.callId)
    val activeCallId = varchar("active_call_id", 128).nullable().uniqueIndex()
    val ownerUserId = varchar("owner_user_id", 64).references(UsersTable.id)
    val sourceType = varchar("source_type", 16)
    val mediaPath = varchar("media_path", 128).uniqueIndex()
    val status = varchar("status", 16)
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val updatedAtEpochMillis = long("updated_at_epoch_millis")
    val expiresAtEpochMillis = long("expires_at_epoch_millis")
    val liveAtEpochMillis = long("live_at_epoch_millis").nullable()
    val stoppedAtEpochMillis = long("stopped_at_epoch_millis").nullable()
    val providerSourceType = varchar("provider_source_type", 64).nullable()
    val providerSourceId = varchar("provider_source_id", 128).nullable()
    val missingSnapshotCount = integer("missing_snapshot_count").default(0)
    val providerCleanedAtEpochMillis = long("provider_cleaned_at_epoch_millis").nullable()
    val lastError = varchar("last_error", 2048).nullable()

    override val primaryKey = PrimaryKey(shareId)
}

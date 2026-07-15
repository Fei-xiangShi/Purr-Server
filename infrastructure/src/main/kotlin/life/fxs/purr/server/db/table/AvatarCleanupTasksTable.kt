package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object AvatarCleanupTasksTable : Table("avatar_cleanup_tasks") {
    val objectKey = varchar("object_key", 512)
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val availableAtEpochMillis = long("available_at_epoch_millis")
    val attemptCount = integer("attempt_count").default(0)
    val leaseOwner = varchar("lease_owner", 64).nullable()
    val leaseUntilEpochMillis = long("lease_until_epoch_millis").nullable()
    val completedAtEpochMillis = long("completed_at_epoch_millis").nullable()
    val lastError = varchar("last_error", 1024).nullable()

    override val primaryKey = PrimaryKey(objectKey)
}

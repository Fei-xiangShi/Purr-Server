package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object PushDevicesTable : Table("push_devices") {
    val installationId = varchar("installation_id", 128)
    val userId = varchar("user_id", 64).references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val sessionId = varchar("session_id", 64)
        .references(AuthSessionsTable.sessionId, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 32)
    val token = varchar("token", 4_096)
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val updatedAtEpochMillis = long("updated_at_epoch_millis")
    val disabledAtEpochMillis = long("disabled_at_epoch_millis").nullable()

    init {
        uniqueIndex(provider, token)
        index(false, userId, disabledAtEpochMillis, updatedAtEpochMillis)
    }

    override val primaryKey = PrimaryKey(installationId)
}

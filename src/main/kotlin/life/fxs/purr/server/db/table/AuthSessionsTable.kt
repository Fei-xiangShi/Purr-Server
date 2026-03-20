package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object AuthSessionsTable : Table("auth_sessions") {
    val sessionId = varchar("session_id", 64)
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val refreshTokenHash = varchar("refresh_token_hash", 255)
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val expiresAtEpochMillis = long("expires_at_epoch_millis")

    override val primaryKey = PrimaryKey(sessionId)
}

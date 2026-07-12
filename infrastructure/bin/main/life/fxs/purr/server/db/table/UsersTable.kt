package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 255)
    val avatarUrl = varchar("avatar_url", 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}

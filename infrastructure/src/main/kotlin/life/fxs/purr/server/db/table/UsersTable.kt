package life.fxs.purr.server.db.table

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 64).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 255)
    val avatarUrl = varchar("avatar_url", 1024).nullable()
    val avatarObjectKey = varchar("avatar_object_key", 512).nullable()
    val avatarVersion = long("avatar_version").default(0)

    override val primaryKey = PrimaryKey(id)
}

package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.UsersTable
import life.fxs.purr.server.model.SelfProfile
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

class UserRepository {
    fun upsert(
        id: String,
        username: String,
        password: String,
        displayName: String,
        avatarUrl: String?,
    ) {
        transaction {
            UsersTable.insertIgnore {
                it[UsersTable.id] = id
                it[UsersTable.username] = username
                it[passwordHash] = BCrypt.hashpw(password, BCrypt.gensalt())
                it[UsersTable.displayName] = displayName
                it[UsersTable.avatarUrl] = avatarUrl
            }
        }
    }

    fun findByUsername(username: String): StoredUser? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .singleOrNull()
            ?.toStoredUser()
    }

    fun findById(userId: String): StoredUser? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.id eq userId }
            .singleOrNull()
            ?.toStoredUser()
    }

    private fun ResultRow.toStoredUser(): StoredUser = StoredUser(
        userId = this[UsersTable.id],
        username = this[UsersTable.username],
        passwordHash = this[UsersTable.passwordHash],
        displayName = this[UsersTable.displayName],
        avatarUrl = this[UsersTable.avatarUrl],
    )
}

data class StoredUser(
    val userId: String,
    val username: String,
    val passwordHash: String,
    val displayName: String,
    val avatarUrl: String?,
) {
    fun toSelfProfile(): SelfProfile = SelfProfile(
        userId = userId,
        displayName = displayName,
        avatarUrl = avatarUrl,
    )
}

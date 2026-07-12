package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.UsersTable
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

class UserRepository : UserAccountStore {
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

    override fun findByUsername(username: String): UserAccountRecord? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .singleOrNull()
            ?.toUserAccountRecord()
    }

    override fun findById(userId: String): UserAccountRecord? = transaction {
        UsersTable.selectAll()
            .where { UsersTable.id eq userId }
            .singleOrNull()
            ?.toUserAccountRecord()
    }

    private fun ResultRow.toUserAccountRecord(): UserAccountRecord = UserAccountRecord(
        userId = this[UsersTable.id],
        username = this[UsersTable.username],
        passwordHash = this[UsersTable.passwordHash],
        displayName = this[UsersTable.displayName],
        avatarUrl = this[UsersTable.avatarUrl],
    )
}

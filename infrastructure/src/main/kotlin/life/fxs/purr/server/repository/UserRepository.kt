package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.UsersTable
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.application.port.UserAccountStore
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt

class UserRepository(
    private val avatarUrlResolver: (String) -> String = { it },
) : UserAccountStore {
    fun insertIfAbsent(
        id: String,
        username: String,
        password: String,
        displayName: String,
        avatarUrl: String?,
    ): Boolean = transaction {
        if (UsersTable.selectAll().where { UsersTable.id eq id }.any()) {
            return@transaction true
        }
        val inserted = UsersTable.insertIgnore {
            it[UsersTable.id] = id
            it[UsersTable.username] = username
            it[UsersTable.passwordHash] = BCrypt.hashpw(password, BCrypt.gensalt())
            it[UsersTable.displayName] = displayName
            it[UsersTable.avatarUrl] = avatarUrl
        }.insertedCount == 1
        inserted || UsersTable.selectAll().where { UsersTable.id eq id }.any()
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

    override fun replacePasswordHash(
        userId: String,
        expectedPasswordHash: String,
        newPasswordHash: String,
    ): Boolean = transaction {
        UsersTable.update({
            (UsersTable.id eq userId) and (UsersTable.passwordHash eq expectedPasswordHash)
        }) {
            it[UsersTable.passwordHash] = newPasswordHash
        } == 1
    }

    override fun compareAndSetAvatar(
        userId: String,
        expectedVersion: Long,
        objectKey: String,
    ): Boolean = transaction {
        UsersTable.update({
            (UsersTable.id eq userId) and (UsersTable.avatarVersion eq expectedVersion)
        }) {
            it[avatarObjectKey] = objectKey
            it[avatarUrl] = null
            it[avatarVersion] = expectedVersion + 1
        } == 1
    }

    override fun updateDisplayName(userId: String, displayName: String): Boolean = transaction {
        UsersTable.update({ UsersTable.id eq userId }) {
            it[UsersTable.displayName] = displayName
        } == 1
    }

    override fun findReferencedObjectKeys(candidates: Set<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        return transaction {
            UsersTable.selectAll()
                .where { UsersTable.avatarObjectKey inList candidates }
                .mapNotNullTo(mutableSetOf()) { it[UsersTable.avatarObjectKey] }
        }
    }

    private fun ResultRow.toUserAccountRecord(): UserAccountRecord {
        val objectKey = this[UsersTable.avatarObjectKey]
        return UserAccountRecord(
            userId = this[UsersTable.id],
            username = this[UsersTable.username],
            passwordHash = this[UsersTable.passwordHash],
            displayName = this[UsersTable.displayName],
            avatarUrl = objectKey?.let(avatarUrlResolver) ?: this[UsersTable.avatarUrl],
            avatarObjectKey = objectKey,
            avatarVersion = this[UsersTable.avatarVersion],
        )
    }
}

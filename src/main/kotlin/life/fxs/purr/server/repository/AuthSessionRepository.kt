package life.fxs.purr.server.repository

import java.security.MessageDigest
import java.util.UUID
import life.fxs.purr.server.db.table.AuthSessionsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AuthSessionRepository {
    fun create(userId: String, refreshToken: String, createdAtEpochMillis: Long, expiresAtEpochMillis: Long): StoredAuthSession {
        val sessionId = UUID.randomUUID().toString()
        val refreshTokenHash = refreshToken.sha256()
        transaction {
            AuthSessionsTable.insert {
                it[AuthSessionsTable.sessionId] = sessionId
                it[AuthSessionsTable.userId] = userId
                it[AuthSessionsTable.refreshTokenHash] = refreshTokenHash
                it[AuthSessionsTable.createdAtEpochMillis] = createdAtEpochMillis
                it[AuthSessionsTable.expiresAtEpochMillis] = expiresAtEpochMillis
            }
        }
        return StoredAuthSession(
            sessionId = sessionId,
            userId = userId,
            refreshTokenHash = refreshTokenHash,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    fun findByRefreshToken(refreshToken: String): StoredAuthSession? = transaction {
        val refreshTokenHash = refreshToken.sha256()
        AuthSessionsTable.selectAll()
            .where { AuthSessionsTable.refreshTokenHash eq refreshTokenHash }
            .singleOrNull()
            ?.toStoredAuthSession()
    }

    fun deleteBySessionId(sessionId: String) {
        transaction {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.sessionId eq sessionId }
        }
    }

    fun deleteAllByUserId(userId: String) {
        transaction {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.userId eq userId }
        }
    }

    private fun ResultRow.toStoredAuthSession(): StoredAuthSession = StoredAuthSession(
        sessionId = this[AuthSessionsTable.sessionId],
        userId = this[AuthSessionsTable.userId],
        refreshTokenHash = this[AuthSessionsTable.refreshTokenHash],
        createdAtEpochMillis = this[AuthSessionsTable.createdAtEpochMillis],
        expiresAtEpochMillis = this[AuthSessionsTable.expiresAtEpochMillis],
    )
}

data class StoredAuthSession(
    val sessionId: String,
    val userId: String,
    val refreshTokenHash: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString(separator = "") { "%02x".format(it) }

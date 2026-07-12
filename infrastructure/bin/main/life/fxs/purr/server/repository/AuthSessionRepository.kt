package life.fxs.purr.server.repository

import java.security.MessageDigest
import java.util.UUID
import life.fxs.purr.server.db.table.AuthSessionsTable
import life.fxs.purr.server.application.port.AuthSessionRecord
import life.fxs.purr.server.application.port.AuthSessionStore
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AuthSessionRepository : AuthSessionStore {
    override fun create(
        userId: String,
        refreshToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord {
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
        return AuthSessionRecord(
            sessionId = sessionId,
            userId = userId,
            refreshTokenHash = refreshTokenHash,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    fun findByRefreshToken(refreshToken: String): AuthSessionRecord? = transaction {
        val refreshTokenHash = refreshToken.sha256()
        AuthSessionsTable.selectAll()
            .where { AuthSessionsTable.refreshTokenHash eq refreshTokenHash }
            .singleOrNull()
            ?.toAuthSessionRecord()
    }

    override fun isActive(sessionId: String, userId: String, nowEpochMillis: Long): Boolean = transaction {
        AuthSessionsTable.selectAll()
            .where {
                (AuthSessionsTable.sessionId eq sessionId) and
                    (AuthSessionsTable.userId eq userId)
            }
            .singleOrNull()
            ?.get(AuthSessionsTable.expiresAtEpochMillis)
            ?.let { it > nowEpochMillis }
            ?: false
    }

    override fun rotate(
        refreshToken: String,
        replacementToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord? = transaction {
        val current = AuthSessionsTable.selectAll()
            .where { AuthSessionsTable.refreshTokenHash eq refreshToken.sha256() }
            .forUpdate()
            .singleOrNull()
            ?.toAuthSessionRecord()
            ?: return@transaction null

        if (current.expiresAtEpochMillis <= createdAtEpochMillis) {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.sessionId eq current.sessionId }
            return@transaction null
        }

        AuthSessionsTable.deleteWhere { AuthSessionsTable.sessionId eq current.sessionId }
        createInCurrentTransaction(
            userId = current.userId,
            refreshToken = replacementToken,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
        )
    }

    override fun deleteBySessionId(sessionId: String) {
        transaction {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.sessionId eq sessionId }
        }
    }

    fun deleteAllByUserId(userId: String) {
        transaction {
            AuthSessionsTable.deleteWhere { AuthSessionsTable.userId eq userId }
        }
    }

    private fun ResultRow.toAuthSessionRecord(): AuthSessionRecord = AuthSessionRecord(
        sessionId = this[AuthSessionsTable.sessionId],
        userId = this[AuthSessionsTable.userId],
        refreshTokenHash = this[AuthSessionsTable.refreshTokenHash],
        createdAtEpochMillis = this[AuthSessionsTable.createdAtEpochMillis],
        expiresAtEpochMillis = this[AuthSessionsTable.expiresAtEpochMillis],
    )

    private fun createInCurrentTransaction(
        userId: String,
        refreshToken: String,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): AuthSessionRecord {
        val sessionId = UUID.randomUUID().toString()
        val refreshTokenHash = refreshToken.sha256()
        AuthSessionsTable.insert {
            it[AuthSessionsTable.sessionId] = sessionId
            it[AuthSessionsTable.userId] = userId
            it[AuthSessionsTable.refreshTokenHash] = refreshTokenHash
            it[AuthSessionsTable.createdAtEpochMillis] = createdAtEpochMillis
            it[AuthSessionsTable.expiresAtEpochMillis] = expiresAtEpochMillis
        }
        return AuthSessionRecord(sessionId, userId, refreshTokenHash, createdAtEpochMillis, expiresAtEpochMillis)
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString(separator = "") { "%02x".format(it) }

package life.fxs.purr.server.repository

import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.db.table.CallRecordingConsentsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class CallRecordingConsentRepository : RecordingConsentStore {
    override fun record(callId: String, userId: String, policyVersion: String, consentedAtEpochMillis: Long) {
        transaction {
            CallRecordingConsentsTable.insertIgnore {
                it[CallRecordingConsentsTable.callId] = callId
                it[CallRecordingConsentsTable.userId] = userId
                it[CallRecordingConsentsTable.policyVersion] = policyVersion
                it[CallRecordingConsentsTable.consentedAtEpochMillis] = consentedAtEpochMillis
            }
        }
    }

    fun hasConsent(callId: String, userId: String, policyVersion: String): Boolean = transaction {
        CallRecordingConsentsTable.selectAll()
            .where {
                (CallRecordingConsentsTable.callId eq callId) and
                    (CallRecordingConsentsTable.userId eq userId) and
                    (CallRecordingConsentsTable.policyVersion eq policyVersion)
            }
            .limit(1)
            .any()
    }

    override fun hasAllConsents(callId: String, userIds: Set<String>, policyVersion: String): Boolean {
        if (userIds.isEmpty()) return false
        return userIds.all { userId -> hasConsent(callId, userId, policyVersion) }
    }
}

package life.fxs.purr.server.repository

import life.fxs.purr.server.db.table.CallSessionsTable
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.CallStatusDto
import life.fxs.purr.server.model.RecordingStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class CallSessionRepository {
    fun find(callId: String): StoredCallSession? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.callId eq callId }
            .singleOrNull()
            ?.toStoredCallSession()
    }

    fun findByRecordingId(recordingId: String): StoredCallSession? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.recordingId eq recordingId }
            .singleOrNull()
            ?.toStoredCallSession()
    }

    fun findByRoomName(roomName: String): StoredCallSession? = transaction {
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.roomName eq roomName }
            .singleOrNull()
            ?.toStoredCallSession()
    }

    fun findActiveByPair(pairId: String): StoredCallSession? = transaction {
        CallSessionsTable.selectAll()
            .where {
                (CallSessionsTable.pairId eq pairId) and
                    (CallSessionsTable.callState eq CallState.ACTIVE.wireValue)
            }
            .singleOrNull()
            ?.toStoredCallSession()
    }

    fun upsert(call: StoredCallSession): StoredCallSession {
        transaction {
            CallSessionsTable.insert {
                it[callId] = call.callId
                it[pairId] = call.pairId
                it[roomName] = call.roomName
                it[createdByUserId] = call.createdByUserId
                it[startedAtEpochMillis] = call.startedAtEpochMillis
                it[updatedAtEpochMillis] = call.updatedAtEpochMillis
                it[endedAtEpochMillis] = call.endedAtEpochMillis
                it[callState] = call.state.wireValue
                it[recordingStatus] = call.recordingStatus.wireValue
                it[recordingId] = call.recordingId
            }
        }
        return call
    }

    fun end(callId: String, endedAtEpochMillis: Long): StoredCallSession? {
        transaction {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[callState] = CallState.ENDED.wireValue
                it[CallSessionsTable.endedAtEpochMillis] = endedAtEpochMillis
                it[updatedAtEpochMillis] = endedAtEpochMillis
            }
        }
        return find(callId)
    }

    fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long): StoredCallSession? {
        val updatedRows = transaction {
            CallSessionsTable.update(
                where = {
                    (CallSessionsTable.callId eq callId) and
                        (CallSessionsTable.callState eq CallState.ACTIVE.wireValue) and
                        (CallSessionsTable.recordingStatus inList startableRecordingStatuses)
                },
            ) {
                it[recordingStatus] = RecordingStatus.STARTING.wireValue
                it[recordingId] = null
                it[CallSessionsTable.updatedAtEpochMillis] = updatedAtEpochMillis
            }
        }
        if (updatedRows == 0) {
            return null
        }
        return find(callId)
    }

    fun updateRecording(
        callId: String,
        recordingStatus: RecordingStatus,
        recordingId: String?,
        updatedAtEpochMillis: Long,
    ): StoredCallSession? {
        transaction {
            CallSessionsTable.update({ CallSessionsTable.callId eq callId }) {
                it[CallSessionsTable.recordingStatus] = recordingStatus.wireValue
                it[CallSessionsTable.recordingId] = recordingId
                it[CallSessionsTable.updatedAtEpochMillis] = updatedAtEpochMillis
            }
        }
        return find(callId)
    }

    private fun ResultRow.toStoredCallSession(): StoredCallSession = StoredCallSession(
        callId = this[CallSessionsTable.callId],
        pairId = this[CallSessionsTable.pairId],
        roomName = this[CallSessionsTable.roomName],
        createdByUserId = this[CallSessionsTable.createdByUserId],
        startedAtEpochMillis = this[CallSessionsTable.startedAtEpochMillis],
        updatedAtEpochMillis = this[CallSessionsTable.updatedAtEpochMillis],
        state = CallState.entries.first { it.wireValue == this[CallSessionsTable.callState] },
        recordingStatus = RecordingStatus.entries.first { it.wireValue == this[CallSessionsTable.recordingStatus] },
        recordingId = this[CallSessionsTable.recordingId],
        endedAtEpochMillis = this[CallSessionsTable.endedAtEpochMillis],
    )

    private companion object {
        val startableRecordingStatuses = listOf(
            RecordingStatus.IDLE.wireValue,
            RecordingStatus.STOPPED.wireValue,
            RecordingStatus.FAILED.wireValue,
        )
    }
}

data class StoredCallSession(
    val callId: String,
    val pairId: String,
    val roomName: String,
    val createdByUserId: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val state: CallState,
    val recordingStatus: RecordingStatus,
    val recordingId: String? = null,
    val endedAtEpochMillis: Long? = null,
) {
    fun toCallStatusDto(): CallStatusDto = CallStatusDto(
        callId = callId,
        pairId = pairId,
        state = state.wireValue,
        recordingStatus = recordingStatus.wireValue,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
    )
}

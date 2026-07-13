package life.fxs.purr.server.repository

import java.util.UUID
import life.fxs.purr.server.application.port.ProviderRecordingResult
import life.fxs.purr.server.application.port.RecordingCommandRecord
import life.fxs.purr.server.application.port.RecordingCommandState
import life.fxs.purr.server.application.port.RecordingCommandStore
import life.fxs.purr.server.application.port.RecordingCommandType
import life.fxs.purr.server.db.table.CallSessionsTable
import life.fxs.purr.server.db.table.RecordingCommandsTable
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** JDBC/Exposed adapter for the durable recording command log. */
class RecordingCommandRepository(
    private val callRecordingRepository: CallRecordingRepository = CallRecordingRepository(),
    private val commandIdProvider: () -> String = { "recording-command-${UUID.randomUUID()}" },
) : RecordingCommandStore {
    override fun enqueueStart(
        callId: String,
        roomName: String,
        requestedAtEpochMillis: Long,
    ): RecordingCommandRecord = enqueue(
        callId = callId,
        roomName = roomName,
        type = RecordingCommandType.START,
        recordingId = null,
        requestedAtEpochMillis = requestedAtEpochMillis,
        // A call has one logical start command. The key must stay stable across
        // duplicate webhooks and crash reconciliation passes.
        idempotencyKey = "start:$callId",
    )

    override fun enqueueStop(
        callId: String,
        roomName: String,
        recordingId: String?,
        requestedAtEpochMillis: Long,
    ): RecordingCommandRecord = enqueue(
        callId = callId,
        roomName = roomName,
        type = RecordingCommandType.STOP,
        recordingId = recordingId,
        requestedAtEpochMillis = requestedAtEpochMillis,
        idempotencyKey = "stop:$callId",
    )

    override fun claimBatch(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
        maxAttempts: Int,
        limit: Int,
    ): List<RecordingCommandRecord> = transaction {
        val candidates = RecordingCommandsTable.selectAll()
            .where {
                (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name) and
                    (RecordingCommandsTable.availableAtEpochMillis lessEq nowEpochMillis) and
                    (RecordingCommandsTable.attemptCount less maxAttempts) and
                    (
                        RecordingCommandsTable.leaseUntilEpochMillis.isNull() or
                            (RecordingCommandsTable.leaseUntilEpochMillis less nowEpochMillis)
                        )
            }
            .orderBy(
                RecordingCommandsTable.requestedAtEpochMillis to SortOrder.ASC,
                RecordingCommandsTable.commandType to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[RecordingCommandsTable.commandId] }

        candidates.mapNotNull { commandId ->
            val candidate = RecordingCommandsTable.selectAll()
                .where { RecordingCommandsTable.commandId eq commandId }
                .singleOrNull()
                ?: return@mapNotNull null
            if (!prerequisiteIsTerminal(candidate)) return@mapNotNull null

            val claimed = RecordingCommandsTable.update(
                where = {
                    (RecordingCommandsTable.commandId eq commandId) and
                        (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name) and
                        (RecordingCommandsTable.availableAtEpochMillis lessEq nowEpochMillis) and
                        (RecordingCommandsTable.attemptCount less maxAttempts) and
                        (
                            RecordingCommandsTable.leaseUntilEpochMillis.isNull() or
                                (RecordingCommandsTable.leaseUntilEpochMillis less nowEpochMillis)
                            )
                },
            ) {
                it[leaseOwner] = workerId
                it[RecordingCommandsTable.leaseUntilEpochMillis] = leaseUntilEpochMillis
            }
            if (claimed != 1) {
                null
            } else {
                RecordingCommandsTable.selectAll()
                    .where { RecordingCommandsTable.commandId eq commandId }
                    .single()
                    .toRecordingCommand()
            }
        }
    }

    override fun markSucceeded(
        commandId: String,
        workerId: String,
        result: ProviderRecordingResult,
        completedAtEpochMillis: Long,
    ): Boolean = transaction {
        val command = RecordingCommandsTable.selectAll()
            .where {
                (RecordingCommandsTable.commandId eq commandId) and
                    (RecordingCommandsTable.leaseOwner eq workerId) and
                    (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name)
            }
            .singleOrNull()
            ?: return@transaction false

        val callId = command[RecordingCommandsTable.callId]
        val effectiveRecordingId = result.recordingId ?: command[RecordingCommandsTable.recordingId]
        // The provider result and command completion are committed in the same
        // database transaction. A retry can therefore safely replay the command
        // if the process dies before this transaction commits.
        val persisted = callRecordingRepository.updateCurrent(
            callId,
            result.copy(recordingId = effectiveRecordingId),
        )
        if (!persisted) return@transaction false

        RecordingCommandsTable.update(
            where = {
                (RecordingCommandsTable.commandId eq commandId) and
                    (RecordingCommandsTable.leaseOwner eq workerId) and
                    (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name)
            },
        ) {
            it[commandState] = RecordingCommandState.SUCCEEDED.name
            it[leaseOwner] = null
            it[leaseUntilEpochMillis] = null
            it[RecordingCommandsTable.completedAtEpochMillis] = completedAtEpochMillis
            it[lastError] = null
            if (effectiveRecordingId != null) it[recordingId] = effectiveRecordingId
        } == 1
    }

    override fun markFailed(
        commandId: String,
        workerId: String,
        availableAtEpochMillis: Long,
        errorMessage: String,
        terminal: Boolean,
        completedAtEpochMillis: Long,
    ): Boolean = transaction {
        val command = RecordingCommandsTable.selectAll()
            .where {
                (RecordingCommandsTable.commandId eq commandId) and
                    (RecordingCommandsTable.leaseOwner eq workerId) and
                    (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name)
            }
            .singleOrNull()
            ?: return@transaction false

        val recordingId = command[RecordingCommandsTable.recordingId]
        if (terminal) {
            callRecordingRepository.updateCurrent(
                command[RecordingCommandsTable.callId],
                ProviderRecordingResult(
                    status = RecordingStatus.FAILED,
                    recordingId = recordingId,
                    updatedAtEpochMillis = completedAtEpochMillis,
                    errorMessage = errorMessage,
                ),
            )
        }
        RecordingCommandsTable.update(
            where = {
                (RecordingCommandsTable.commandId eq commandId) and
                    (RecordingCommandsTable.leaseOwner eq workerId) and
                    (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name)
            },
        ) {
            it[commandState] = if (terminal) {
                RecordingCommandState.FAILED.name
            } else {
                RecordingCommandState.PENDING.name
            }
            it[leaseOwner] = null
            it[leaseUntilEpochMillis] = null
            it[attemptCount] = RecordingCommandsTable.attemptCount + 1
            it[RecordingCommandsTable.availableAtEpochMillis] = availableAtEpochMillis
            it[lastError] = errorMessage.take(MAX_ERROR_LENGTH)
            if (terminal) it[RecordingCommandsTable.completedAtEpochMillis] = completedAtEpochMillis
        } == 1
    }

    override fun findOpenForCall(
        callId: String,
        type: RecordingCommandType,
    ): RecordingCommandRecord? = transaction {
        RecordingCommandsTable.selectAll()
            .where {
                (RecordingCommandsTable.callId eq callId) and
                    (RecordingCommandsTable.commandType eq type.name) and
                    (RecordingCommandsTable.commandState eq RecordingCommandState.PENDING.name)
            }
            .orderBy(RecordingCommandsTable.requestedAtEpochMillis to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toRecordingCommand()
    }

    override fun reconcileOpenCalls(nowEpochMillis: Long): Int = transaction {
        val candidates = CallSessionsTable.selectAll()
            .where {
                CallSessionsTable.recordingStatus inList listOf(
                    RecordingStatus.STARTING.wireValue,
                    RecordingStatus.STOPPING.wireValue,
                )
            }
            .map {
                ReconciliationCandidate(
                    callId = it[CallSessionsTable.callId],
                    roomName = it[CallSessionsTable.roomName],
                    type = if (it[CallSessionsTable.recordingStatus] == RecordingStatus.STARTING.wireValue) {
                        RecordingCommandType.START
                    } else {
                        RecordingCommandType.STOP
                    },
                    recordingId = it[CallSessionsTable.recordingId],
                )
            }

        var inserted = 0
        candidates.forEach { candidate ->
            val hasNonTerminal = RecordingCommandsTable.selectAll()
                .where {
                    (RecordingCommandsTable.callId eq candidate.callId) and
                        (RecordingCommandsTable.commandType eq candidate.type.name) and
                        (
                            RecordingCommandsTable.commandState inList listOf(
                                RecordingCommandState.PENDING.name,
                                RecordingCommandState.SUCCEEDED.name,
                            )
                            )
                }
                .limit(1)
                .count() > 0
            if (hasNonTerminal) return@forEach

            val requestedAt = nowEpochMillis
            val key = when (candidate.type) {
                RecordingCommandType.START -> "start:${candidate.callId}"
                RecordingCommandType.STOP -> "stop:${candidate.callId}"
            }
            val commandId = commandIdProvider()
            RecordingCommandsTable.insertIgnore {
                it[RecordingCommandsTable.commandId] = commandId
                it[idempotencyKey] = key
                it[callId] = candidate.callId
                it[roomName] = candidate.roomName
                it[commandType] = candidate.type.name
                it[recordingId] = candidate.recordingId
                it[requestedAtEpochMillis] = requestedAt
                it[availableAtEpochMillis] = requestedAt
                it[attemptCount] = 0
                it[leaseOwner] = null
                it[leaseUntilEpochMillis] = null
                it[commandState] = RecordingCommandState.PENDING.name
                it[completedAtEpochMillis] = null
                it[lastError] = null
            }
            if (RecordingCommandsTable.selectAll()
                    .where { RecordingCommandsTable.commandId eq commandId }
                    .count() > 0
            ) {
                inserted++
            }
        }
        inserted
    }

    private fun enqueue(
        callId: String,
        roomName: String,
        type: RecordingCommandType,
        recordingId: String?,
        requestedAtEpochMillis: Long,
        idempotencyKey: String,
    ): RecordingCommandRecord = transaction {
        // Serializing against the call row makes the idempotency check safe on
        // databases that do not support partial unique indexes.
        CallSessionsTable.selectAll()
            .where { CallSessionsTable.callId eq callId }
            .forUpdate()
            .single()

        val existing = RecordingCommandsTable.selectAll()
            .where { RecordingCommandsTable.idempotencyKey eq idempotencyKey }
            .singleOrNull()
        if (existing != null) return@transaction existing.toRecordingCommand()

        val commandId = commandIdProvider()
        RecordingCommandsTable.insert {
            it[RecordingCommandsTable.commandId] = commandId
            it[RecordingCommandsTable.idempotencyKey] = idempotencyKey
            it[RecordingCommandsTable.callId] = callId
            it[RecordingCommandsTable.roomName] = roomName
            it[RecordingCommandsTable.commandType] = type.name
            it[RecordingCommandsTable.recordingId] = recordingId
            it[RecordingCommandsTable.requestedAtEpochMillis] = requestedAtEpochMillis
            it[RecordingCommandsTable.availableAtEpochMillis] = requestedAtEpochMillis
            it[attemptCount] = 0
            it[leaseOwner] = null
            it[leaseUntilEpochMillis] = null
            it[commandState] = RecordingCommandState.PENDING.name
            it[completedAtEpochMillis] = null
            it[lastError] = null
        }
        RecordingCommandsTable.selectAll()
            .where { RecordingCommandsTable.commandId eq commandId }
            .single()
            .toRecordingCommand()
    }

    private fun ResultRow.toRecordingCommand() = RecordingCommandRecord(
        commandId = this[RecordingCommandsTable.commandId],
        idempotencyKey = this[RecordingCommandsTable.idempotencyKey],
        callId = this[RecordingCommandsTable.callId],
        roomName = this[RecordingCommandsTable.roomName],
        type = RecordingCommandType.valueOf(this[RecordingCommandsTable.commandType]),
        recordingId = this[RecordingCommandsTable.recordingId],
        requestedAtEpochMillis = this[RecordingCommandsTable.requestedAtEpochMillis],
        availableAtEpochMillis = this[RecordingCommandsTable.availableAtEpochMillis],
        attemptCount = this[RecordingCommandsTable.attemptCount],
        leaseOwner = this[RecordingCommandsTable.leaseOwner],
        leaseUntilEpochMillis = this[RecordingCommandsTable.leaseUntilEpochMillis],
        state = RecordingCommandState.valueOf(this[RecordingCommandsTable.commandState]),
        completedAtEpochMillis = this[RecordingCommandsTable.completedAtEpochMillis],
        lastError = this[RecordingCommandsTable.lastError],
    )

    /** STOP cannot overtake a START whose provider result is still uncertain. */
    private fun prerequisiteIsTerminal(command: ResultRow): Boolean {
        if (command[RecordingCommandsTable.commandType] != RecordingCommandType.STOP.name) return true
        val start = RecordingCommandsTable.selectAll()
            .where {
                (RecordingCommandsTable.callId eq command[RecordingCommandsTable.callId]) and
                    (RecordingCommandsTable.commandType eq RecordingCommandType.START.name)
            }
            .limit(1)
            .singleOrNull()
            ?: return true
        return start[RecordingCommandsTable.commandState] != RecordingCommandState.PENDING.name
    }

    private data class ReconciliationCandidate(
        val callId: String,
        val roomName: String,
        val type: RecordingCommandType,
        val recordingId: String?,
    )

    private companion object {
        const val MAX_ERROR_LENGTH = 2_048
    }
}

package life.fxs.purr.server.avatar

import life.fxs.purr.server.application.port.AvatarCleanupBacklog
import life.fxs.purr.server.application.port.AvatarCleanupTask
import life.fxs.purr.server.application.port.AvatarCleanupTaskStore
import life.fxs.purr.server.db.table.AvatarCleanupTasksTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class AvatarCleanupRepository : AvatarCleanupTaskStore {
    override fun enqueue(objectKey: String, nowEpochMillis: Long) {
        transaction {
            AvatarCleanupTasksTable.insertIgnore {
                it[AvatarCleanupTasksTable.objectKey] = objectKey
                it[AvatarCleanupTasksTable.createdAtEpochMillis] = nowEpochMillis
                it[AvatarCleanupTasksTable.availableAtEpochMillis] = nowEpochMillis
            }
        }
    }

    override fun claimNext(
        workerId: String,
        nowEpochMillis: Long,
        leaseUntilEpochMillis: Long,
    ): AvatarCleanupTask? = transaction {
        val candidates = AvatarCleanupTasksTable.selectAll()
            .where {
                AvatarCleanupTasksTable.completedAtEpochMillis.isNull() and
                    (AvatarCleanupTasksTable.availableAtEpochMillis lessEq nowEpochMillis) and
                    (
                        AvatarCleanupTasksTable.leaseUntilEpochMillis.isNull() or
                            (AvatarCleanupTasksTable.leaseUntilEpochMillis less nowEpochMillis)
                    )
            }
            .orderBy(AvatarCleanupTasksTable.createdAtEpochMillis to SortOrder.ASC)
            .limit(CLAIM_CANDIDATE_WINDOW)
            .map { it[AvatarCleanupTasksTable.objectKey] }

        candidates.firstNotNullOfOrNull { key ->
            val claimed = AvatarCleanupTasksTable.update({
                (AvatarCleanupTasksTable.objectKey eq key) and
                    AvatarCleanupTasksTable.completedAtEpochMillis.isNull() and
                    (AvatarCleanupTasksTable.availableAtEpochMillis lessEq nowEpochMillis) and
                    (
                        AvatarCleanupTasksTable.leaseUntilEpochMillis.isNull() or
                            (AvatarCleanupTasksTable.leaseUntilEpochMillis less nowEpochMillis)
                    )
            }) {
                it[AvatarCleanupTasksTable.leaseOwner] = workerId
                it[AvatarCleanupTasksTable.leaseUntilEpochMillis] = leaseUntilEpochMillis
                it[AvatarCleanupTasksTable.attemptCount] = AvatarCleanupTasksTable.attemptCount + 1
            } == 1
            if (!claimed) return@firstNotNullOfOrNull null
            AvatarCleanupTasksTable.selectAll()
                .where { AvatarCleanupTasksTable.objectKey eq key }
                .single()
                .toTask()
        }
    }

    override fun markCompleted(objectKey: String, workerId: String, nowEpochMillis: Long): Boolean = transaction {
        AvatarCleanupTasksTable.update({
            (AvatarCleanupTasksTable.objectKey eq objectKey) and
                (AvatarCleanupTasksTable.leaseOwner eq workerId) and
                AvatarCleanupTasksTable.completedAtEpochMillis.isNull()
        }) {
            it[AvatarCleanupTasksTable.completedAtEpochMillis] = nowEpochMillis
            it[AvatarCleanupTasksTable.leaseOwner] = null
            it[AvatarCleanupTasksTable.leaseUntilEpochMillis] = null
            it[AvatarCleanupTasksTable.lastError] = null
        } == 1
    }

    override fun recordFailure(
        objectKey: String,
        workerId: String,
        availableAtEpochMillis: Long,
        message: String,
    ): Boolean = transaction {
        AvatarCleanupTasksTable.update({
            (AvatarCleanupTasksTable.objectKey eq objectKey) and
                (AvatarCleanupTasksTable.leaseOwner eq workerId) and
                AvatarCleanupTasksTable.completedAtEpochMillis.isNull()
        }) {
            it[AvatarCleanupTasksTable.availableAtEpochMillis] = availableAtEpochMillis
            it[AvatarCleanupTasksTable.leaseOwner] = null
            it[AvatarCleanupTasksTable.leaseUntilEpochMillis] = null
            it[AvatarCleanupTasksTable.lastError] = message.take(MAX_ERROR_LENGTH)
        } == 1
    }

    internal fun find(objectKey: String): AvatarCleanupTask? = transaction {
        AvatarCleanupTasksTable.selectAll()
            .where { AvatarCleanupTasksTable.objectKey eq objectKey }
            .singleOrNull()
            ?.toTask()
    }

    override fun purgeCompleted(completedBeforeEpochMillis: Long): Int = transaction {
        AvatarCleanupTasksTable.deleteWhere {
            completedAtEpochMillis.isNotNull() and
                (completedAtEpochMillis less completedBeforeEpochMillis)
        }
    }

    override fun backlog(nowEpochMillis: Long): AvatarCleanupBacklog = transaction {
        val pendingTasks = AvatarCleanupTasksTable.selectAll()
            .where { AvatarCleanupTasksTable.completedAtEpochMillis.isNull() }
            .count()
        val oldestCreatedAt = AvatarCleanupTasksTable.selectAll()
            .where { AvatarCleanupTasksTable.completedAtEpochMillis.isNull() }
            .orderBy(AvatarCleanupTasksTable.createdAtEpochMillis to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.get(AvatarCleanupTasksTable.createdAtEpochMillis)
        AvatarCleanupBacklog(
            pendingTasks = pendingTasks,
            oldestTaskAgeSeconds = oldestCreatedAt
                ?.let { ((nowEpochMillis - it).coerceAtLeast(0L)) / MILLIS_PER_SECOND }
                ?: 0L,
        )
    }

    private companion object {
        const val CLAIM_CANDIDATE_WINDOW = 16
        const val MAX_ERROR_LENGTH = 1_024
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private fun ResultRow.toTask() = AvatarCleanupTask(
    objectKey = this[AvatarCleanupTasksTable.objectKey],
    attemptCount = this[AvatarCleanupTasksTable.attemptCount],
    availableAtEpochMillis = this[AvatarCleanupTasksTable.availableAtEpochMillis],
    completedAtEpochMillis = this[AvatarCleanupTasksTable.completedAtEpochMillis],
    leaseUntilEpochMillis = this[AvatarCleanupTasksTable.leaseUntilEpochMillis],
)

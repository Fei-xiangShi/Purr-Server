package life.fxs.purr.server.call

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import life.fxs.purr.server.application.call.CallLifecycleService
import life.fxs.purr.server.application.call.CallRoomLifecycleService
import life.fxs.purr.server.application.call.CallRoomReconciliationService
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallRoomParticipantReader
import life.fxs.purr.server.application.port.RecordingCommandType
import life.fxs.purr.server.application.port.RecordingConsentStore
import life.fxs.purr.server.config.DatabaseConfig
import life.fxs.purr.server.db.DatabaseFactory
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.realtime.OutboxRepository
import life.fxs.purr.server.repository.CallRecordingRepository
import life.fxs.purr.server.repository.CallSessionRepository
import life.fxs.purr.server.repository.PairBondRepository
import life.fxs.purr.server.repository.RecordingCommandRepository
import life.fxs.purr.server.repository.UserRepository

class CallRoomReconciliationIntegrationTest {
    @Test
    fun `continuous empty room ends call once and persists one stop command`() {
        val resources = DatabaseFactory(
            DatabaseConfig(
                jdbcUrl = "jdbc:h2:mem:call-room-reconcile-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                password = "",
                maximumPoolSize = 2,
            ),
        ).connect()
        try {
            seedPair()
            val calls = CallSessionRepository()
            calls.upsert(activeRecordingCall())
            val recordings = CallRecordingRepository()
            val commands = RecordingCommandRepository(recordings) { "stop-command-1" }
            var now = 1_000L
            val lifecycle = CallLifecycleService(
                callSessionStore = calls,
                pairStore = PairBondRepository(),
                transaction = resources.applicationTransaction,
                realtimeOutbox = OutboxRepository(),
            )
            val participantReader = EmptyRoomReader
            val roomLifecycle = CallRoomLifecycleService(
                callSessionStore = calls,
                callRecordingStore = recordings,
                recordingConsentStore = NoConsentNeeded,
                pairStore = PairBondRepository(),
                recordingController = null,
                callLifecycleService = lifecycle,
                recordingEnabled = true,
                consentPolicyVersion = "test-v1",
                participantReader = participantReader,
                recordingCommandStore = commands,
                transaction = resources.applicationTransaction,
                nowProvider = { Instant.ofEpochMilli(now) },
            )
            val reconciler = CallRoomReconciliationService(
                store = calls,
                participantReader = participantReader,
                roomEventHandler = roomLifecycle,
                waitingCallTerminator = lifecycle,
                waitingTtlMillis = 1_000L,
                emptyRoomGraceMillis = 100L,
                batchSize = 10,
            )

            reconciler.reconcileOnce(now)
            now = 1_100L
            reconciler.reconcileOnce(now)
            now = 1_200L
            reconciler.reconcileOnce(now)

            val ended = assertNotNull(calls.find(CALL_ID))
            assertEquals(CallState.ENDED, ended.state)
            assertEquals(1_100L, ended.endedAtEpochMillis)
            assertEquals(RecordingStatus.STOPPING, ended.recordingStatus)
            val stop = assertNotNull(commands.findOpenForCall(CALL_ID, RecordingCommandType.STOP))
            assertEquals("egress-1", stop.recordingId)
            assertEquals("stop-command-1", stop.commandId)
        } finally {
            (resources.dataSource as? AutoCloseable)?.close()
        }
    }

    private fun seedPair() {
        val users = UserRepository()
        users.upsert("user-a", "user-a", "pass-a", "A", null)
        users.upsert("user-b", "user-b", "pass-b", "B", null)
        PairBondRepository().upsert(PAIR_ID, "user-a", "user-b", 1L)
    }

    private fun activeRecordingCall() = CallRecord(
        callId = CALL_ID,
        pairId = PAIR_ID,
        roomName = ROOM_NAME,
        createdByUserId = "user-a",
        startedAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        state = CallState.ACTIVE,
        recordingStatus = RecordingStatus.RECORDING,
        recordingId = "egress-1",
        connectedAtEpochMillis = 1L,
    )

    private object EmptyRoomReader : CallRoomParticipantReader {
        override fun countActiveNonEgressParticipants(roomName: String): Int = 0
        override fun countPresentNonEgressParticipants(roomName: String): Int = 0
        override fun presentNonEgressParticipantIdentities(roomName: String): Set<String> = emptySet()
    }

    private object NoConsentNeeded : RecordingConsentStore {
        override fun record(callId: String, userId: String, policyVersion: String, consentedAtEpochMillis: Long) = Unit
        override fun hasAllConsents(callId: String, userIds: Set<String>, policyVersion: String): Boolean = true
    }

    private companion object {
        const val CALL_ID = "call-1"
        const val PAIR_ID = "pair-1"
        const val ROOM_NAME = "room-1"
    }
}

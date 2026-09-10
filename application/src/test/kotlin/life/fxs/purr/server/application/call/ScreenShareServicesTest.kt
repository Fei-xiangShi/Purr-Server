package life.fxs.purr.server.application.call

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CallHistoryCursor
import life.fxs.purr.server.application.model.CreateScreenShareCommand
import life.fxs.purr.server.application.port.ActiveCallResolution
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.CallRecord
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.EndCallResolution
import life.fxs.purr.server.application.port.PairRecord
import life.fxs.purr.server.application.port.PairStore
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.ScreenShareAuthorizationRequest
import life.fxs.purr.server.application.port.ScreenShareProvider
import life.fxs.purr.server.application.port.ScreenShareProviderPath
import life.fxs.purr.server.application.port.ScreenShareProviderSnapshot
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.application.port.ScreenShareTokenClaims
import life.fxs.purr.server.application.port.ScreenShareTokenRequest
import life.fxs.purr.server.application.port.ScreenShareTransition
import life.fxs.purr.server.application.port.UserAccountReader
import life.fxs.purr.server.application.port.UserAccountRecord
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.RecordingStatus
import life.fxs.purr.server.model.ScreenSharePurpose
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareServicesTest {
    @Test
    fun `stop during provider provisioning never returns publishing credentials`() {
        val fixture = Fixture()
        fixture.provider.onEnsure = { fixture.lifecycle.stop(CALL_ID, NOW) }

        assertFailsWith<ApplicationException> {
            fixture.service.create(USER_A, CALL_ID, CreateScreenShareCommand(ScreenShareSource.MOBILE))
        }
        assertEquals(1, fixture.provider.cleaned.size)
        assertNull(fixture.service.get(USER_A, CALL_ID)?.publishing)
        assertNull(fixture.service.get(USER_A, CALL_ID)?.playback)
    }

    @Test
    fun `provider cleanup failure remains stopping until reconciliation succeeds`() {
        val fixture = Fixture()
        fixture.service.create(USER_A, CALL_ID, CreateScreenShareCommand(ScreenShareSource.MOBILE))
        fixture.provider.cleanupFailure = IllegalStateException("provider unavailable")

        val stopping = fixture.service.stop(USER_A, CALL_ID)
        assertEquals(ScreenShareStatus.STOPPING, stopping?.status)
        assertNull(fixture.store.findByShareId(SHARE_ID)?.providerCleanedAtEpochMillis)
        assertNull(stopping?.playback)
        fixture.provider.cleanupFailure = null
        val stopped = fixture.service.stop(USER_A, CALL_ID)
        assertEquals(ScreenShareStatus.STOPPED, stopped?.status)
    }

    @Test
    fun `obs create returns whip whep and encrypted srt fallback then stop revokes the share`() {
        val fixture = Fixture()

        val created = fixture.service.create(
            userId = USER_A,
            callId = CALL_ID,
            command = CreateScreenShareCommand(ScreenShareSource.OBS),
        )

        assertEquals(ScreenShareStatus.AUTHORIZED, created.status)
        assertEquals("https://stream.example/${created.mediaPath}/whip", created.publishing?.whip?.url)
        assertEquals("https://stream.example/${created.mediaPath}/whep", created.playback?.url)
        assertEquals("publish:${created.mediaPath}:purr:publish-$USER_A", created.publishing?.srt?.streamId)
        assertTrue(created.publishing?.srt?.url?.contains("passphrase=pass-${created.shareId}") == true)
        assertEquals(listOf(created.mediaPath), fixture.provider.ensured.map { it.mediaPath })
        assertEquals(2, fixture.events.count { it.second.type == RealtimeEvent.SCREEN_SHARE_CHANGED })

        val viewedByPartner = fixture.service.get(USER_B, CALL_ID)
        assertNotNull(viewedByPartner?.playback)
        assertNull(viewedByPartner?.publishing)

        val stillOwnedByA = fixture.service.stop(USER_B, CALL_ID)
        assertEquals(ScreenShareStatus.AUTHORIZED, stillOwnedByA?.status)
        assertEquals(0, fixture.provider.cleaned.size)
        val staleStop = fixture.service.stop(USER_A, CALL_ID, expectedShareId = "old-share")
        assertEquals(ScreenShareStatus.AUTHORIZED, staleStop?.status)
        assertEquals(0, fixture.provider.cleaned.size)

        val stopped = fixture.service.stop(USER_A, CALL_ID, expectedShareId = created.shareId)
        assertEquals(ScreenShareStatus.STOPPED, stopped?.status)
        assertEquals(listOf(created.mediaPath), fixture.provider.cleaned.map { it.mediaPath })
        assertNotNull(fixture.store.findByShareId(created.shareId)?.providerCleanedAtEpochMillis)
        assertNull(stopped?.playback)
    }

    @Test
    fun `authorization binds protocol action path participant and owner`() {
        val fixture = Fixture()
        val record = fixture.record(status = ScreenShareStatus.LIVE)
        fixture.store.createIfAbsent(record)
        var claims = fixture.claims(record, USER_A, ScreenSharePurpose.PUBLISH)
        val service = ScreenShareAuthorizationService(
            enabled = true,
            tokenVerifier = { token -> claims.takeIf { token == "valid" } },
            screenShareStore = fixture.store,
            callSessionStore = fixture.callStore,
            pairService = fixture.pairService,
            nowProvider = { Instant.ofEpochMilli(NOW) },
        )

        assertTrue(service.authorize(request("valid", "publish", "webrtc", record.mediaPath)))
        assertFalse(service.authorize(request("valid", "read", "webrtc", record.mediaPath)))
        assertFalse(service.authorize(request("valid", "publish", "hls", record.mediaPath)))
        assertFalse(service.authorize(request("valid", "publish", "webrtc", "another-path")))

        claims = fixture.claims(record, USER_B, ScreenSharePurpose.READ)
        assertTrue(service.authorize(request("valid", "read", "webrtc", "/${record.mediaPath}")))
        claims = fixture.claims(record, USER_B, ScreenSharePurpose.PUBLISH)
        assertFalse(service.authorize(request("valid", "publish", "srt", record.mediaPath)))

        fixture.store.markStopped(record.shareId, NOW, null)
        claims = fixture.claims(record, USER_A, ScreenSharePurpose.PUBLISH)
        assertFalse(service.authorize(request("valid", "publish", "webrtc", record.mediaPath)))
    }

    @Test
    fun `two complete missing snapshots stop a live share but provider errors do not`() {
        val fixture = Fixture()
        val live = fixture.record(status = ScreenShareStatus.LIVE)
        fixture.store.createIfAbsent(live)
        fixture.provider.snapshotFailure = IllegalStateException("provider unavailable")

        val reconciliation = ScreenShareReconciliationService(
            store = fixture.store,
            provider = fixture.provider,
            lifecycleService = fixture.lifecycle,
            batchSize = 10,
        )

        runCatching { reconciliation.reconcileOnce(NOW) }
        assertEquals(ScreenShareStatus.LIVE, fixture.store.findByShareId(live.shareId)?.status)
        assertEquals(0, fixture.store.findByShareId(live.shareId)?.missingSnapshotCount)

        fixture.provider.snapshotFailure = null
        fixture.provider.paths = emptyMap()
        reconciliation.reconcileOnce(NOW + 1)
        assertEquals(1, fixture.store.findByShareId(live.shareId)?.missingSnapshotCount)
        assertEquals(ScreenShareStatus.LIVE, fixture.store.findByShareId(live.shareId)?.status)

        reconciliation.reconcileOnce(NOW + 2)
        assertEquals(ScreenShareStatus.STOPPED, fixture.store.findByShareId(live.shareId)?.status)
        assertEquals(listOf(live.mediaPath), fixture.provider.cleaned.map { it.mediaPath })
    }

    @Test
    fun `online path with stalled ingress is stopped within the reconciliation window`() {
        val fixture = Fixture()
        val live = fixture.record(status = ScreenShareStatus.LIVE)
        fixture.store.createIfAbsent(live)
        fixture.provider.paths = mapOf(
            live.mediaPath to ScreenShareProviderPath(
                mediaPath = live.mediaPath,
                online = true,
                sourceType = "webrtcSession",
                sourceId = "publisher-1",
                inboundBytes = 10_000,
            ),
        )
        val reconciliation = ScreenShareReconciliationService(
            store = fixture.store,
            provider = fixture.provider,
            lifecycleService = fixture.lifecycle,
            batchSize = 10,
        )

        reconciliation.reconcileOnce(NOW)
        repeat(4) { index -> reconciliation.reconcileOnce(NOW + index + 1L) }
        assertEquals(ScreenShareStatus.LIVE, fixture.store.findByShareId(live.shareId)?.status)

        reconciliation.reconcileOnce(NOW + 5)
        assertEquals(ScreenShareStatus.STOPPED, fixture.store.findByShareId(live.shareId)?.status)
        assertEquals("Publisher stopped sending media", fixture.store.findByShareId(live.shareId)?.lastError)
        assertEquals(listOf(live.mediaPath), fixture.provider.cleaned.map { it.mediaPath })
    }

    private fun request(token: String, action: String, protocol: String, path: String) =
        ScreenShareAuthorizationRequest(token, null, action, protocol, path)

    private class Fixture {
        val store = MemoryScreenShareStore()
        val callStore = FakeCallStore(activeCall())
        val pairService = PairService(FakePairStore, FakeUserReader)
        val provider = FakeProvider()
        val events = mutableListOf<Pair<String, RealtimeEvent>>()
        val lifecycle = ScreenShareLifecycleService(
            store = store,
            callSessionStore = callStore,
            pairService = pairService,
            transaction = ImmediateTransaction,
            realtimeOutbox = RealtimeOutbox { userId, event, _ -> events += userId to event },
        )
        val service = ScreenShareService(
            enabled = true,
            callAccessPolicy = CallAccessPolicy(pairService, callStore),
            pairService = pairService,
            store = store,
            provider = provider,
            tokenIssuer = { request -> "${request.purpose.wireValue}-${request.userId}" },
            srtPassphraseIssuer = { shareId -> "pass-$shareId" },
            lifecycleService = lifecycle,
            transaction = ImmediateTransaction,
            realtimeOutbox = RealtimeOutbox { userId, event, _ -> events += userId to event },
            publicBaseUrl = "https://stream.example",
            srtPublicHost = "stream.example",
            srtPublicPort = 8890,
            publishTokenTtlMillis = 60_000,
            readTokenTtlMillis = 30_000,
            shareTtlMillis = 600_000,
            nowProvider = { Instant.ofEpochMilli(NOW) },
            shareIdProvider = { SHARE_ID },
        )

        fun record(status: ScreenShareStatus) = ScreenShareRecord(
            shareId = SHARE_ID,
            callId = CALL_ID,
            ownerUserId = USER_A,
            source = ScreenShareSource.MOBILE,
            mediaPath = "screen-$SHARE_ID",
            status = status,
            createdAtEpochMillis = NOW - 1_000,
            updatedAtEpochMillis = NOW - 1_000,
            expiresAtEpochMillis = NOW + 60_000,
            liveAtEpochMillis = NOW - 500,
        )

        fun claims(record: ScreenShareRecord, userId: String, purpose: ScreenSharePurpose) =
            ScreenShareTokenClaims(
                shareId = record.shareId,
                callId = record.callId,
                userId = userId,
                mediaPath = record.mediaPath,
                purpose = purpose,
                expiresAtEpochMillis = NOW + 30_000,
            )
    }

    private class FakeProvider : ScreenShareProvider {
        val ensured = mutableListOf<ScreenShareRecord>()
        val cleaned = mutableListOf<ScreenShareRecord>()
        var paths: Map<String, ScreenShareProviderPath> = emptyMap()
        var snapshotFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var onEnsure: (() -> Unit)? = null

        override fun ensurePath(record: ScreenShareRecord, srtPublishPassphrase: String) {
            ensured += record
            onEnsure?.invoke()
        }

        override fun snapshot(): ScreenShareProviderSnapshot {
            snapshotFailure?.let { throw it }
            return ScreenShareProviderSnapshot(paths)
        }

        override fun cleanup(record: ScreenShareRecord) {
            cleanupFailure?.let { throw it }
            cleaned += record
        }
    }

    private class MemoryScreenShareStore : ScreenShareStore {
        private val records = linkedMapOf<String, ScreenShareRecord>()

        override fun createIfAbsent(record: ScreenShareRecord): Boolean {
            if (records.values.any { it.callId == record.callId && it.status in activeStatuses }) return false
            records[record.shareId] = record
            return true
        }

        override fun findByShareId(shareId: String) = records[shareId]

        override fun findByMediaPath(mediaPath: String) = records.values.firstOrNull { it.mediaPath == mediaPath }

        override fun findCurrentByCallId(callId: String) = records.values
            .filter { it.callId == callId }
            .maxByOrNull { it.createdAtEpochMillis }

        override fun findReconciliationCandidates(nowEpochMillis: Long, limit: Int) = records.values
            .filter { it.status in openStatuses || (it.status in terminalStatuses && it.providerCleanedAtEpochMillis == null) }
            .take(limit)

        override fun markLive(
            shareId: String,
            providerSourceType: String?,
            providerSourceId: String?,
            observedAtEpochMillis: Long,
        ) = update(shareId) { current ->
            if (current.status !in activeStatuses) return@update current to false
            current.copy(
                status = ScreenShareStatus.LIVE,
                updatedAtEpochMillis = observedAtEpochMillis,
                liveAtEpochMillis = current.liveAtEpochMillis ?: observedAtEpochMillis,
                providerSourceType = providerSourceType,
                providerSourceId = providerSourceId,
                missingSnapshotCount = 0,
                lastError = null,
            ) to (current.status != ScreenShareStatus.LIVE)
        }

        override fun observePresent(
            shareId: String,
            providerSourceType: String?,
            providerSourceId: String?,
            observedAtEpochMillis: Long,
        ) = updateRecord(shareId) {
            it.copy(
                updatedAtEpochMillis = observedAtEpochMillis,
                providerSourceType = providerSourceType,
                providerSourceId = providerSourceId,
                missingSnapshotCount = 0,
                lastError = null,
            )
        }

        override fun observeMissing(shareId: String, observedAtEpochMillis: Long) = updateRecord(shareId) {
            if (it.status != ScreenShareStatus.LIVE) it else it.copy(
                updatedAtEpochMillis = observedAtEpochMillis,
                missingSnapshotCount = it.missingSnapshotCount + 1,
            )
        }

        override fun requestStop(callId: String, stoppedAtEpochMillis: Long, expectedShareId: String?): ScreenShareTransition? {
            val current = findCurrentByCallId(callId) ?: return null
            if (expectedShareId != null && current.shareId != expectedShareId) return null
            if (current.status !in activeStatuses) return ScreenShareTransition(current, false)
            return transition(current.shareId, ScreenShareStatus.STOPPING, stoppedAtEpochMillis, null)
        }

        override fun markExpired(shareId: String, expiredAtEpochMillis: Long) =
            transition(shareId, ScreenShareStatus.EXPIRED, expiredAtEpochMillis, "expired")

        override fun markFailed(shareId: String, failedAtEpochMillis: Long, message: String) =
            transition(shareId, ScreenShareStatus.FAILED, failedAtEpochMillis, message)

        override fun markStopped(shareId: String, stoppedAtEpochMillis: Long, message: String?) =
            transition(shareId, ScreenShareStatus.STOPPED, stoppedAtEpochMillis, message)

        override fun markProviderCleaned(shareId: String, cleanedAtEpochMillis: Long) = updateRecord(shareId) {
            it.copy(providerCleanedAtEpochMillis = cleanedAtEpochMillis, updatedAtEpochMillis = cleanedAtEpochMillis)
        }

        override fun recordProviderError(shareId: String, observedAtEpochMillis: Long, message: String) =
            updateRecord(shareId) { it.copy(updatedAtEpochMillis = observedAtEpochMillis, lastError = message) }

        private fun transition(
            shareId: String,
            target: ScreenShareStatus,
            atEpochMillis: Long,
            message: String?,
        ) = update(shareId) { current ->
            if (target == ScreenShareStatus.STOPPED && current.status !in setOf(ScreenShareStatus.LIVE, ScreenShareStatus.STOPPING)) {
                return@update current to false
            }
            if (target != ScreenShareStatus.STOPPED && current.status !in activeStatuses) return@update current to false
            current.copy(
                status = target,
                updatedAtEpochMillis = atEpochMillis,
                stoppedAtEpochMillis = atEpochMillis,
                missingSnapshotCount = 0,
                lastError = message,
            ) to (current.status != target)
        }

        private fun update(
            shareId: String,
            block: (ScreenShareRecord) -> Pair<ScreenShareRecord, Boolean>,
        ): ScreenShareTransition? {
            val current = records[shareId] ?: return null
            val (next, changed) = block(current)
            records[shareId] = next
            return ScreenShareTransition(next, changed)
        }

        private fun updateRecord(shareId: String, block: (ScreenShareRecord) -> ScreenShareRecord): ScreenShareRecord? {
            val current = records[shareId] ?: return null
            return block(current).also { records[shareId] = it }
        }

        private companion object {
            val activeStatuses = setOf(ScreenShareStatus.AUTHORIZED, ScreenShareStatus.LIVE)
            val openStatuses = activeStatuses + ScreenShareStatus.STOPPING
            val terminalStatuses = setOf(ScreenShareStatus.STOPPED, ScreenShareStatus.EXPIRED, ScreenShareStatus.FAILED)
        }
    }

    private class FakeCallStore(private var call: CallRecord) : CallSessionStore {
        override fun find(callId: String) = call.takeIf { it.callId == callId }
        override fun findByRoomName(roomName: String) = call.takeIf { it.roomName == roomName }
        override fun findByRecordingId(recordingId: String) = call.takeIf { it.recordingId == recordingId }
        override fun findActiveByPair(pairId: String) = call.takeIf { it.pairId == pairId && it.state != CallState.ENDED }
        override fun findOrCreateActive(pairId: String, newCall: () -> CallRecord) = ActiveCallResolution(call, false)
        override fun activateIfWaiting(callId: String, connectedAtEpochMillis: Long) = call
        override fun endIfWaiting(callId: String, endedAtEpochMillis: Long): EndCallResolution? = null
        override fun endIfOpen(callId: String, endedAtEpochMillis: Long): EndCallResolution? = null
        override fun claimRecordingStart(callId: String, updatedAtEpochMillis: Long) = call
        override fun findEndedByPairId(pairId: String, limit: Int, cursor: CallHistoryCursor?) = emptyList<CallRecord>()
    }

    private object FakePairStore : PairStore {
        private val pair = PairRecord(PAIR_ID, USER_A, USER_B, 1L)
        override fun findByUserId(userId: String) = pair.takeIf { userId == USER_A || userId == USER_B }
        override fun findByPairId(pairId: String) = pair.takeIf { pairId == PAIR_ID }
    }

    private object FakeUserReader : UserAccountReader {
        override fun findByUsername(username: String) = findById(username)
        override fun findById(userId: String) = userId.takeIf { it == USER_A || it == USER_B }?.let {
            UserAccountRecord(it, it, "hash", it, null)
        }
    }

    private object ImmediateTransaction : ApplicationTransaction {
        override fun <T> execute(block: () -> T): T = block()
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val PAIR_ID = "pair-1"
        const val CALL_ID = "call-1"
        const val SHARE_ID = "share-1"
        const val NOW = 2_000_000_000_000L

        fun activeCall() = CallRecord(
            callId = CALL_ID,
            pairId = PAIR_ID,
            roomName = "room-1",
            createdByUserId = USER_A,
            startedAtEpochMillis = NOW - 10_000,
            updatedAtEpochMillis = NOW - 1_000,
            state = CallState.ACTIVE,
            recordingStatus = RecordingStatus.IDLE,
            connectedAtEpochMillis = NOW - 9_000,
        )
    }
}

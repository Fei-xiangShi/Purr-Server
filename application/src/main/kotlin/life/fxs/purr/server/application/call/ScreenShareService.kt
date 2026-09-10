package life.fxs.purr.server.application.call

import java.time.Instant
import java.util.UUID
import life.fxs.purr.server.application.ApplicationError
import life.fxs.purr.server.application.ApplicationException
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.model.CreateScreenShareCommand
import life.fxs.purr.server.application.model.ScreenShareMediaEndpointResult
import life.fxs.purr.server.application.model.ScreenSharePublishingResult
import life.fxs.purr.server.application.model.ScreenShareResult
import life.fxs.purr.server.application.model.ScreenShareSrtResult
import life.fxs.purr.server.application.port.ApplicationTransaction
import life.fxs.purr.server.application.port.RealtimeEvent
import life.fxs.purr.server.application.port.RealtimeOutbox
import life.fxs.purr.server.application.port.ScreenShareProvider
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.application.port.ScreenShareSrtPassphraseIssuer
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.application.port.ScreenShareTokenIssuer
import life.fxs.purr.server.application.port.ScreenShareTokenRequest
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.ScreenSharePurpose
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareService(
    private val enabled: Boolean,
    private val callAccessPolicy: CallAccessPolicy,
    private val pairService: PairService,
    private val store: ScreenShareStore,
    private val provider: ScreenShareProvider,
    private val tokenIssuer: ScreenShareTokenIssuer,
    private val srtPassphraseIssuer: ScreenShareSrtPassphraseIssuer,
    private val lifecycleService: ScreenShareLifecycleService,
    private val transaction: ApplicationTransaction,
    private val realtimeOutbox: RealtimeOutbox,
    private val publicBaseUrl: String,
    private val srtPublicHost: String,
    private val srtPublicPort: Int,
    private val publishTokenTtlMillis: Long,
    private val readTokenTtlMillis: Long,
    private val shareTtlMillis: Long,
    private val nowProvider: () -> Instant = Instant::now,
    private val shareIdProvider: () -> String = { "share-${UUID.randomUUID()}" },
) {
    fun create(userId: String, callId: String, command: CreateScreenShareCommand): ScreenShareResult {
        requireEnabled()
        val call = callAccessPolicy.requireAccessibleCall(userId, callId)
        if (call.state != CallState.ACTIVE) {
            throw ApplicationException(ApplicationError.CONFLICT, "Screen sharing requires an active call")
        }

        val now = nowProvider().toEpochMilli()
        val record = createRecord(userId, callId, command.source, now)
        val created = transaction.execute {
            if (!store.createIfAbsent(record)) return@execute false
            val event = record.toRealtimeEvent()
            pairService.requirePairUserIds(call.pairId).forEach { participantUserId ->
                realtimeOutbox.enqueue(participantUserId, event, now)
            }
            true
        }
        if (!created) {
            throw ApplicationException(ApplicationError.CONFLICT, "Call is no longer active or already has a screen share")
        }

        val passphrase = srtPassphraseIssuer.issue(record.shareId)
        try {
            provider.ensurePath(record, passphrase)
        } catch (error: Throwable) {
            lifecycleService.fail(record.shareId, nowProvider().toEpochMilli(), error.boundedMessage())
            throw ApplicationException(ApplicationError.EXTERNAL_DEPENDENCY, "Screen media service is unavailable")
        }
        val current = checkNotNull(store.findByShareId(record.shareId))
        if (callAccessPolicy.requireAccessibleCall(userId, callId).state != CallState.ACTIVE ||
            current.status !in setOf(ScreenShareStatus.AUTHORIZED, ScreenShareStatus.LIVE)
        ) {
            lifecycleService.stop(callId, nowProvider().toEpochMilli(), record.shareId)
            // ensurePath may have completed after a prior cleanup. Revoke the
            // exact path again and leave provider errors retryable.
            runCatching { provider.cleanup(record) }
                .onSuccess { store.markProviderCleaned(record.shareId, nowProvider().toEpochMilli()) }
                .onFailure { store.recordProviderError(record.shareId, nowProvider().toEpochMilli(), it.boundedMessage()) }
            throw ApplicationException(ApplicationError.CONFLICT, "Screen share ended during preparation")
        }
        return current.toResult(userId, includePublishing = true, srtPassphrase = passphrase)
    }

    fun get(userId: String, callId: String): ScreenShareResult? {
        requireEnabled()
        callAccessPolicy.requireAccessibleCall(userId, callId)
        return store.findCurrentByCallId(callId)?.toResult(userId, includePublishing = false)
    }

    fun stop(userId: String, callId: String, expectedShareId: String? = null): ScreenShareResult? {
        requireEnabled()
        callAccessPolicy.requireAccessibleCall(userId, callId)
        val current = store.findCurrentByCallId(callId) ?: return null
        // Leaving the voice room must not stop a peer-owned OBS/mobile stream.
        // Shared call termination still stops all media through the internal lifecycle.
        if (current.ownerUserId != userId || (expectedShareId != null && current.shareId != expectedShareId)) {
            return current.toResult(userId, includePublishing = false)
        }
        val record = lifecycleService.stop(callId, nowProvider().toEpochMilli(), current.shareId)
            ?: return get(userId, callId)
        val cleanup = runCatching { provider.cleanup(record) }
            .onSuccess { store.markProviderCleaned(record.shareId, nowProvider().toEpochMilli()) }
            .onFailure { error ->
                store.recordProviderError(record.shareId, nowProvider().toEpochMilli(), error.boundedMessage())
            }
        val stopped = if (cleanup.isSuccess) {
            lifecycleService.stopped(record.shareId, nowProvider().toEpochMilli()) ?: record
        } else {
            store.findByShareId(record.shareId) ?: record
        }
        return stopped.toResult(userId, includePublishing = false)
    }

    private fun createRecord(
        userId: String,
        callId: String,
        source: ScreenShareSource,
        nowEpochMillis: Long,
    ): ScreenShareRecord {
        val shareId = shareIdProvider()
        return ScreenShareRecord(
            shareId = shareId,
            callId = callId,
            ownerUserId = userId,
            source = source,
            mediaPath = "screen-$shareId",
            status = ScreenShareStatus.AUTHORIZED,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nowEpochMillis + shareTtlMillis,
        )
    }

    private fun ScreenShareRecord.toResult(
        userId: String,
        includePublishing: Boolean,
        srtPassphrase: String? = null,
    ): ScreenShareResult {
        val credentialsAllowed = status == ScreenShareStatus.AUTHORIZED || status == ScreenShareStatus.LIVE
        val playback = if (credentialsAllowed) {
            issueEndpoint(userId, ScreenSharePurpose.READ, readTokenTtlMillis, "whep")
        } else {
            null
        }
        val publishing = if (includePublishing && credentialsAllowed && ownerUserId == userId) {
            val whip = issueEndpoint(userId, ScreenSharePurpose.PUBLISH, publishTokenTtlMillis, "whip")
            val srt = if (source == ScreenShareSource.OBS) {
                val passphrase = requireNotNull(srtPassphrase)
                val streamId = "publish:$mediaPath:purr:${whip.bearerToken}"
                ScreenShareSrtResult(
                    url = "srt://$srtPublicHost:$srtPublicPort?streamid=$streamId&pkt_size=1316&passphrase=$passphrase",
                    streamId = streamId,
                    passphrase = passphrase,
                )
            } else {
                null
            }
            ScreenSharePublishingResult(whip = whip, srt = srt)
        } else {
            null
        }
        return ScreenShareResult(
            shareId = shareId,
            callId = callId,
            ownerUserId = ownerUserId,
            source = source,
            status = status,
            mediaPath = mediaPath,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            liveAtEpochMillis = liveAtEpochMillis,
            stoppedAtEpochMillis = stoppedAtEpochMillis,
            publishing = publishing,
            playback = playback,
            errorMessage = lastError,
        )
    }

    private fun ScreenShareRecord.issueEndpoint(
        userId: String,
        purpose: ScreenSharePurpose,
        ttlMillis: Long,
        endpoint: String,
    ): ScreenShareMediaEndpointResult {
        val now = nowProvider().toEpochMilli()
        // JWT NumericDate is second-precision. Return the same value carried by
        // the signed token so clients never believe a credential lives longer.
        val tokenExpiresAt = minOf(expiresAtEpochMillis, now + ttlMillis)
            .let { (it / MILLIS_PER_SECOND) * MILLIS_PER_SECOND }
        val token = tokenIssuer.issue(
            ScreenShareTokenRequest(
                shareId = shareId,
                callId = callId,
                userId = userId,
                mediaPath = mediaPath,
                purpose = purpose,
                expiresAtEpochMillis = tokenExpiresAt,
            ),
        )
        return ScreenShareMediaEndpointResult(
            url = "$publicBaseUrl/$mediaPath/$endpoint",
            bearerToken = token,
            expiresAtEpochMillis = tokenExpiresAt,
        )
    }

    private fun ScreenShareRecord.toRealtimeEvent() = RealtimeEvent(
        type = RealtimeEvent.SCREEN_SHARE_CHANGED,
        callId = callId,
        screenShareId = shareId,
        screenShareStatus = status.wireValue,
        screenShareSource = source.wireValue,
        screenShareOwnerUserId = ownerUserId,
    )

    private fun requireEnabled() {
        if (!enabled) {
            throw ApplicationException(ApplicationError.NOT_FOUND, "Screen sharing is not enabled")
        }
    }

    private fun Throwable.boundedMessage(): String =
        (message ?: this::class.simpleName ?: "provider error").take(MAX_ERROR_LENGTH)

    private companion object {
        const val MAX_ERROR_LENGTH = 2_048
        const val MILLIS_PER_SECOND = 1_000L
    }
}

package life.fxs.purr.server.application.port

import life.fxs.purr.server.model.ScreenSharePurpose
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

data class ScreenShareRecord(
    val shareId: String,
    val callId: String,
    val ownerUserId: String,
    val source: ScreenShareSource,
    val mediaPath: String,
    val status: ScreenShareStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val liveAtEpochMillis: Long? = null,
    val stoppedAtEpochMillis: Long? = null,
    val providerSourceType: String? = null,
    val providerSourceId: String? = null,
    val missingSnapshotCount: Int = 0,
    val providerCleanedAtEpochMillis: Long? = null,
    val lastError: String? = null,
)

data class ScreenShareTransition(
    val record: ScreenShareRecord,
    val changed: Boolean,
)

interface ScreenShareStore {
    fun createIfAbsent(record: ScreenShareRecord): Boolean

    fun findByShareId(shareId: String): ScreenShareRecord?

    fun findByMediaPath(mediaPath: String): ScreenShareRecord?

    fun findCurrentByCallId(callId: String): ScreenShareRecord?

    fun findReconciliationCandidates(nowEpochMillis: Long, limit: Int): List<ScreenShareRecord>

    fun markLive(
        shareId: String,
        providerSourceType: String?,
        providerSourceId: String?,
        observedAtEpochMillis: Long,
    ): ScreenShareTransition?

    fun observePresent(
        shareId: String,
        providerSourceType: String?,
        providerSourceId: String?,
        observedAtEpochMillis: Long,
    ): ScreenShareRecord?

    fun observeMissing(shareId: String, observedAtEpochMillis: Long): ScreenShareRecord?

    fun requestStop(callId: String, stoppedAtEpochMillis: Long): ScreenShareTransition?

    fun markExpired(shareId: String, expiredAtEpochMillis: Long): ScreenShareTransition?

    fun markFailed(shareId: String, failedAtEpochMillis: Long, message: String): ScreenShareTransition?

    fun markStopped(
        shareId: String,
        stoppedAtEpochMillis: Long,
        message: String? = null,
    ): ScreenShareTransition?

    fun markProviderCleaned(shareId: String, cleanedAtEpochMillis: Long): ScreenShareRecord?

    fun recordProviderError(shareId: String, observedAtEpochMillis: Long, message: String): ScreenShareRecord?
}

data class ScreenShareTokenRequest(
    val shareId: String,
    val callId: String,
    val userId: String,
    val mediaPath: String,
    val purpose: ScreenSharePurpose,
    val expiresAtEpochMillis: Long,
)

data class ScreenShareTokenClaims(
    val shareId: String,
    val callId: String,
    val userId: String,
    val mediaPath: String,
    val purpose: ScreenSharePurpose,
    val expiresAtEpochMillis: Long,
)

fun interface ScreenShareTokenIssuer {
    fun issue(request: ScreenShareTokenRequest): String
}

fun interface ScreenShareTokenVerifier {
    fun verify(token: String): ScreenShareTokenClaims?
}

fun interface ScreenShareSrtPassphraseIssuer {
    fun issue(shareId: String): String
}

data class ScreenShareProviderPath(
    val mediaPath: String,
    val online: Boolean,
    val sourceType: String?,
    val sourceId: String?,
)

data class ScreenShareProviderSnapshot(
    val paths: Map<String, ScreenShareProviderPath>,
)

interface ScreenShareProvider {
    fun ensurePath(record: ScreenShareRecord, srtPublishPassphrase: String)

    /** Returns a complete snapshot or throws. Partial/error snapshots must not change durable state. */
    fun snapshot(): ScreenShareProviderSnapshot

    /** Kicks matching SRT/WebRTC sessions and removes the dynamic path configuration. */
    fun cleanup(record: ScreenShareRecord)
}

data class ScreenShareAuthorizationRequest(
    val token: String?,
    val password: String?,
    val action: String,
    val protocol: String,
    val path: String,
)

fun interface ScreenShareTerminator {
    fun terminateForCall(callId: String, endedAtEpochMillis: Long)
}

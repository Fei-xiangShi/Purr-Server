package life.fxs.purr.server.application.call

import java.time.Instant
import life.fxs.purr.server.application.account.PairService
import life.fxs.purr.server.application.port.CallSessionStore
import life.fxs.purr.server.application.port.ScreenShareAuthorizationRequest
import life.fxs.purr.server.application.port.ScreenShareStore
import life.fxs.purr.server.application.port.ScreenShareTokenVerifier
import life.fxs.purr.server.model.CallState
import life.fxs.purr.server.model.ScreenSharePurpose
import life.fxs.purr.server.model.ScreenShareStatus

class ScreenShareAuthorizationService(
    private val enabled: Boolean,
    private val tokenVerifier: ScreenShareTokenVerifier,
    private val screenShareStore: ScreenShareStore,
    private val callSessionStore: CallSessionStore,
    private val pairService: PairService,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun authorize(request: ScreenShareAuthorizationRequest): Boolean {
        if (!enabled) return false
        val purpose = request.toPurpose() ?: return false
        if (!request.protocolSupports(purpose)) return false
        val credential = request.token?.takeIf(String::isNotBlank)
            ?: request.password?.takeIf(String::isNotBlank)
            ?: return false
        val claims = tokenVerifier.verify(credential) ?: return false
        val normalizedPath = request.path.trim().trimStart('/')
        if (claims.purpose != purpose || claims.mediaPath != normalizedPath) return false
        if (claims.expiresAtEpochMillis <= nowProvider().toEpochMilli()) return false

        val share = screenShareStore.findByShareId(claims.shareId) ?: return false
        if (share.callId != claims.callId || share.mediaPath != claims.mediaPath) return false
        if (share.status != ScreenShareStatus.AUTHORIZED && share.status != ScreenShareStatus.LIVE) return false
        val call = callSessionStore.find(claims.callId) ?: return false
        if (call.state != CallState.ACTIVE) return false
        val participants = runCatching { pairService.requirePairUserIds(call.pairId) }.getOrNull() ?: return false
        if (claims.userId !in participants) return false
        return purpose != ScreenSharePurpose.PUBLISH || claims.userId == share.ownerUserId
    }

    private fun ScreenShareAuthorizationRequest.toPurpose(): ScreenSharePurpose? = when (action.lowercase()) {
        "publish" -> ScreenSharePurpose.PUBLISH
        "read" -> ScreenSharePurpose.READ
        else -> null
    }

    private fun ScreenShareAuthorizationRequest.protocolSupports(purpose: ScreenSharePurpose): Boolean = when (purpose) {
        ScreenSharePurpose.PUBLISH -> protocol.equals("webrtc", true) || protocol.equals("srt", true)
        ScreenSharePurpose.READ -> protocol.equals("webrtc", true)
    }
}

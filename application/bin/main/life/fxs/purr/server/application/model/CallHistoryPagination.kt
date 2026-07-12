package life.fxs.purr.server.application.model

import java.nio.charset.StandardCharsets
import java.util.Base64

data class CallHistoryCursor(
    val startedAtEpochMillis: Long,
    val callId: String,
)

object CallHistoryCursorCodec {
    fun encode(cursor: CallHistoryCursor): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString("${cursor.startedAtEpochMillis}\n${cursor.callId}".toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): CallHistoryCursor? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        val parts = decoded.split('\n', limit = 2)
        val startedAt = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val callId = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        CallHistoryCursor(startedAt, callId)
    }.getOrNull()
}

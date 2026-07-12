package life.fxs.purr.server.application.model

import java.nio.charset.StandardCharsets
import java.util.Base64

data class RecordingPageCursor(
    val createdAtEpochMillis: Long,
    val recordingId: String,
)

object RecordingPageCursorCodec {
    fun encode(cursor: RecordingPageCursor): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString("${cursor.createdAtEpochMillis}\n${cursor.recordingId}".toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): RecordingPageCursor? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        val parts = decoded.split('\n', limit = 2)
        val createdAt = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val recordingId = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return null
        RecordingPageCursor(createdAt, recordingId)
    }.getOrNull()
}

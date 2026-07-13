package life.fxs.purr.server.api

import io.ktor.utils.io.core.Input
import io.ktor.utils.io.core.readAvailable
import java.io.ByteArrayOutputStream

/**
 * Reads no more than [maxBytes] from a multipart packet.
 *
 * Ktor's `Input.readAvailable(ByteArray, ...)` returns zero at end of input, so
 * zero is terminal rather than a signal to retry. Retrying would spin forever
 * on the request thread after a normally completed upload.
 */
internal fun Input.readAtMost(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    if (maxBytes == 0) return ByteArray(0)

    val output = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_BYTES))
    val buffer = ByteArray(minOf(maxBytes, READ_BUFFER_BYTES))
    var total = 0
    while (total < maxBytes) {
        val read = readAvailable(buffer, 0, minOf(buffer.size, maxBytes - total))
        if (read <= 0) break
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private const val READ_BUFFER_BYTES = 8 * 1024

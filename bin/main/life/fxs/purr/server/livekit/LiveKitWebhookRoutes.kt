package life.fxs.purr.server.livekit

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import life.fxs.purr.server.api.ApiException
import life.fxs.purr.server.coroutines.onBlockingIo

fun Route.registerLiveKitWebhookRoutes(webhookService: LiveKitWebhookService) {
    post("/webhooks/livekit") {
        val body = call.receiveLimitedText(MAX_WEBHOOK_BODY_BYTES)
        val authorization = call.request.header("Authorization")
        call.respond(HttpStatusCode.OK, onBlockingIo { webhookService.handle(body, authorization) })
    }
}

private suspend fun ApplicationCall.receiveLimitedText(maxBytes: Int): String {
    val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (contentLength != null && contentLength > maxBytes) {
        throw ApiException(
            statusCode = HttpStatusCode(413, "Payload Too Large"),
            message = "Webhook payload exceeds the maximum size",
            code = "payload_too_large",
        )
    }

    val output = ByteArrayOutputStream(contentLength?.toInt()?.coerceAtMost(maxBytes) ?: 8_192)
    val buffer = ByteArray(8_192)
    var totalBytes = 0
    val channel = receiveChannel()
    while (!channel.isClosedForRead) {
        val bytesToRead = minOf(buffer.size, maxBytes + 1 - totalBytes)
        val read = channel.readAvailable(buffer, 0, bytesToRead)
        if (read == -1) break
        if (read == 0) continue
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw ApiException(
                statusCode = HttpStatusCode(413, "Payload Too Large"),
                message = "Webhook payload exceeds the maximum size",
                code = "payload_too_large",
            )
        }
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private const val MAX_WEBHOOK_BODY_BYTES = 1_048_576

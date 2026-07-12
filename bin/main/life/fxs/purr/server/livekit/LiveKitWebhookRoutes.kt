package life.fxs.purr.server.livekit

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import life.fxs.purr.server.coroutines.onBlockingIo

fun Route.registerLiveKitWebhookRoutes(webhookService: LiveKitWebhookService) {
    post("/webhooks/livekit") {
        val body = call.receiveText()
        val authorization = call.request.header("Authorization")
        call.respond(HttpStatusCode.OK, onBlockingIo { webhookService.handle(body, authorization) })
    }
}

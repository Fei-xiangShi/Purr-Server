package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 15_000L
        maxFrameSize = 64 * 1024
        masking = false
    }
}

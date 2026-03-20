package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import life.fxs.purr.server.api.registerPurrRoutes
import life.fxs.purr.server.livekit.registerLiveKitWebhookRoutes
import life.fxs.purr.server.service.ServerDependencies

fun Application.configureRouting(dependencies: ServerDependencies) {
    routing {
        registerPurrRoutes(dependencies)
        registerLiveKitWebhookRoutes(dependencies.liveKitWebhookService)
    }
}

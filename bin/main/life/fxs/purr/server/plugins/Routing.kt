package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import life.fxs.purr.server.api.registerPurrRoutes
import life.fxs.purr.server.livekit.registerLiveKitWebhookRoutes
import life.fxs.purr.server.realtime.registerRealtimeRoutes
import life.fxs.purr.server.service.ServerDependencies
import io.micrometer.prometheus.PrometheusMeterRegistry

fun Application.configureRouting(
    dependencies: ServerDependencies,
    meterRegistry: PrometheusMeterRegistry,
) {
    routing {
        registerPurrRoutes(dependencies, meterRegistry)
        registerLiveKitWebhookRoutes(dependencies.liveKitWebhookService)
        registerRealtimeRoutes(dependencies)
    }
}

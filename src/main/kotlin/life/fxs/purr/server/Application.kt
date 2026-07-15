package life.fxs.purr.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import life.fxs.purr.server.config.PurrConfigLoader
import life.fxs.purr.server.plugins.configureAuth
import life.fxs.purr.server.plugins.configureCallId
import life.fxs.purr.server.plugins.configureCallLogging
import life.fxs.purr.server.plugins.configureRouting
import life.fxs.purr.server.plugins.configureSerialization
import life.fxs.purr.server.plugins.configureStatusPages
import life.fxs.purr.server.plugins.configureRateLimiting
import life.fxs.purr.server.plugins.configureRequestValidation
import life.fxs.purr.server.plugins.configureWebSockets
import life.fxs.purr.server.plugins.configureProxyHeaders
import life.fxs.purr.server.plugins.configureMonitoring
import life.fxs.purr.server.monitoring.ServerMetrics
import life.fxs.purr.server.service.ServerDependenciesFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module(config: ApplicationConfig = environment.config) {
    val purrConfig = PurrConfigLoader.load(config)
    val serverMetrics = ServerMetrics()
    val dependencies = try {
        ServerDependenciesFactory.create(purrConfig, serverMetrics)
    } catch (error: Throwable) {
        serverMetrics.close()
        throw error
    }
    environment.monitor.subscribe(ApplicationStopped) {
        try {
            dependencies.close()
        } finally {
            serverMetrics.close()
        }
    }

    configureCallId()
    configureProxyHeaders()
    configureCallLogging()
    configureMonitoring(serverMetrics.registry)
    configureSerialization()
    configureStatusPages()
    configureRequestValidation()
    configureRateLimiting(dependencies.authRateLimiter)
    configureWebSockets()
    configureAuth(dependencies)
    configureRouting(dependencies, serverMetrics.registry)
}

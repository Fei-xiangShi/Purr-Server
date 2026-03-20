package life.fxs.purr.server

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import life.fxs.purr.server.config.PurrConfigLoader
import life.fxs.purr.server.plugins.configureAuth
import life.fxs.purr.server.plugins.configureCallLogging
import life.fxs.purr.server.plugins.configureRouting
import life.fxs.purr.server.plugins.configureSerialization
import life.fxs.purr.server.plugins.configureStatusPages
import life.fxs.purr.server.service.ServerDependenciesFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module(config: ApplicationConfig = environment.config) {
    val purrConfig = PurrConfigLoader.load(config)
    val dependencies = ServerDependenciesFactory.create(purrConfig)

    configureCallLogging()
    configureSerialization()
    configureStatusPages()
    configureAuth(dependencies)
    configureRouting(dependencies)
}

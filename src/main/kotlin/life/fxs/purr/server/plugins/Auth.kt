package life.fxs.purr.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import life.fxs.purr.server.service.ServerDependencies

fun Application.configureAuth(dependencies: ServerDependencies) {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(dependencies.jwtTokenService.verifier())
            validate { credential ->
                val subject = credential.payload.subject ?: return@validate null
                val sessionId = credential.payload.getClaim("sessionId").asString() ?: return@validate null
                dependencies.authContextResolver.resolve(credential.payload)
                    .takeIf { it.userId == subject && it.sessionId == sessionId }
            }
        }
    }
}

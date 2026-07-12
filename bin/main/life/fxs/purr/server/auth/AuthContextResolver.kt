package life.fxs.purr.server.auth

import com.auth0.jwt.interfaces.Payload
import io.ktor.server.auth.Principal

data class AuthenticatedUser(
    val userId: String,
    val sessionId: String,
) : Principal

class AuthContextResolver {
    fun resolve(jwt: Payload): AuthenticatedUser = AuthenticatedUser(
        userId = jwt.subject,
        sessionId = jwt.getClaim("sessionId").asString(),
    )
}

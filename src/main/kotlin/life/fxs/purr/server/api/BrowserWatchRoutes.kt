package life.fxs.purr.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Public player shell; media remains protected by the existing READ authorization. */
fun Route.registerBrowserWatchRoutes() {
    val resources = mapOf(
        "/watch" to ("web/watch.html" to ContentType.Text.Html),
        "/watch.js" to ("web/watch.js" to ContentType.Application.JavaScript),
    )
    resources.forEach { (path, resource) ->
        val body = checkNotNull(object {}.javaClass.classLoader.getResource(resource.first)).readText()
        get(path) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            call.respondText(body, resource.second)
        }
    }
}

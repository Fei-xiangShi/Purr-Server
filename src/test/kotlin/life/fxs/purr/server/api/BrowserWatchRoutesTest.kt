package life.fxs.purr.server.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserWatchRoutesTest {
    @Test fun `public player shell contains no credentials and disallows framing`() = testApplication {
        application { routing { registerBrowserWatchRoutes() } }
        val page = client.get("/watch")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("no-store", page.headers["Cache-Control"])
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertTrue(page.bodyAsText().contains("<video"))
        val script = client.get("/watch.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.bodyAsText().contains("recvonly"))
    }
}

package life.fxs.purr.server.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.core.writeFully
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvatarRoutesTest {
    @Test
    fun `small avatar multipart upload completes and leaves server responsive`() {
        MockS3Server().use { objectStore ->
            testApplication {
                environment {
                    config = ApplicationConfig("application.yaml").mergeWith(
                        MapApplicationConfig(
                            "purr.recording.endpoint" to objectStore.endpoint,
                            "purr.recording.publicEndpoint" to objectStore.endpoint,
                        ),
                    )
                }
                val token = client.login()
                val avatar = pngBytes()

                val response = client.uploadAvatar(token, avatar)

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("\"avatarUrl\":\"${objectStore.endpoint}/purr-recordings/avatars/user-a/"))
                val storedRequestBody = assertNotNull(objectStore.uploadedBytes.get()).asList()
                assertTrue(storedRequestBody.windowed(avatar.size).any { it == avatar.asList() })
                assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
            }
        }
    }

    @Test
    fun `empty avatar multipart file is rejected and leaves server responsive`() = testApplication {
        val token = client.login()

        val response = client.uploadAvatar(token, ByteArray(0))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Avatar must be between 1 byte and 10 MB"))
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `avatar multipart file over limit is rejected and leaves server responsive`() = testApplication {
        val token = client.login()

        val response = client.uploadAvatar(token, ByteArray(MAX_AVATAR_BYTES + 1))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Avatar must not exceed 10 MB"))
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    private suspend fun HttpClient.login(): String {
        val response = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        return Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("Missing accessToken in $body")
    }

    private suspend fun HttpClient.uploadAvatar(
        token: String,
        bytes: ByteArray,
    ) = put("/me/avatar") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        key = "avatar",
                        filename = "avatar.png",
                        contentType = ContentType.Image.PNG,
                        size = bytes.size.toLong(),
                    ) {
                        writeFully(bytes)
                    }
                },
            ),
        )
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private class MockS3Server : AutoCloseable {
        val uploadedBytes = AtomicReference<ByteArray?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", ::handleRequest)
            start()
        }
        val endpoint: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }

        private fun handleRequest(exchange: HttpExchange) {
            try {
                if (exchange.requestMethod != "PUT") {
                    exchange.sendResponseHeaders(HttpStatusCode.MethodNotAllowed.value, NO_RESPONSE_BODY)
                    return
                }
                uploadedBytes.set(exchange.requestBody.use { it.readBytes() })
                exchange.responseHeaders.add(HttpHeaders.ETag, "\"test-etag\"")
                exchange.sendResponseHeaders(HttpStatusCode.OK.value, NO_RESPONSE_BODY)
            } finally {
                exchange.close()
            }
        }
    }

    private companion object {
        const val MAX_AVATAR_BYTES = 10 * 1024 * 1024
        const val NO_RESPONSE_BODY = -1L
    }
}

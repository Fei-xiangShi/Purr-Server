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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
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
                            "purr.avatar.endpoint" to objectStore.endpoint,
                            "purr.avatar.publicEndpoint" to objectStore.endpoint,
                        ),
                    )
                }
                val token = client.login()
                val avatar = pngBytes()

                val response = client.uploadAvatar(token, avatar)

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("\"avatarUrl\":\"${objectStore.endpoint}/purr-avatars/avatars/user-a/"))
                val storedRequestBody = assertNotNull(objectStore.uploadedBytes.get()).asList()
                assertTrue(
                    storedRequestBody.windowed(3).any {
                        it == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                    },
                )
                assertTrue(storedRequestBody.windowed(avatar.size).none { it == avatar.asList() })
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

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains("Avatar"))
        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    }

    @Test
    fun `truncated image with valid signature is rejected`() = testApplication {
        val token = client.login()
        val signatureOnly = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val response = client.uploadAvatar(token, signatureOnly)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Avatar"))
    }

    @Test
    fun `unsupported avatar media type returns 415 before image processing`() = testApplication {
        val token = client.login()

        val response = client.uploadAvatar(
            token = token,
            bytes = byteArrayOf(1, 2, 3),
            contentType = ContentType.Application.OctetStream,
        )

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `avatar reference survives a complete application restart`() {
        MockS3Server().use { objectStore ->
            val databaseUrl =
                "jdbc:h2:mem:avatar-restart-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            lateinit var avatarUrl: String

            testApplication {
                environment { config = avatarTestConfig(objectStore, databaseUrl) }
                val token = client.login()
                val response = client.uploadAvatar(token, pngBytes())
                assertEquals(HttpStatusCode.OK, response.status)
                avatarUrl = Regex("\\\"avatarUrl\\\":\\\"([^\\\"]+)\\\"")
                    .find(response.bodyAsText())
                    ?.groupValues
                    ?.get(1)
                    ?: error("Missing avatar URL")
            }

            testApplication {
                environment { config = avatarTestConfig(objectStore, databaseUrl) }
                val token = client.login()
                val response = client.get("/me") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.bodyAsText().contains("\"avatarUrl\":\"$avatarUrl\""))
            }
        }
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

    private fun avatarTestConfig(objectStore: MockS3Server, databaseUrl: String): ApplicationConfig =
        ApplicationConfig("application.yaml").mergeWith(
            MapApplicationConfig(
                "purr.avatar.endpoint" to objectStore.endpoint,
                "purr.avatar.publicEndpoint" to objectStore.endpoint,
                "purr.database.jdbcUrl" to databaseUrl,
            ),
        )

    private suspend fun HttpClient.uploadAvatar(
        token: String,
        bytes: ByteArray,
        contentType: ContentType = ContentType.Image.PNG,
    ) = put("/me/avatar") {
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        key = "avatar",
                        filename = when (contentType) {
                            ContentType.Image.JPEG -> "avatar.jpg"
                            ContentType.Image.PNG -> "avatar.png"
                            else -> "avatar.bin"
                        },
                        contentType = contentType,
                        size = bytes.size.toLong(),
                    ) {
                        writeFully(bytes)
                    }
                },
            ),
        )
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        return try {
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(30, 90, 180)
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        } finally {
            image.flush()
        }
    }

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
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(HttpStatusCode.OK.value, NO_RESPONSE_BODY)
                    return
                }
                if (exchange.requestMethod != "PUT" && exchange.requestMethod != "DELETE") {
                    exchange.sendResponseHeaders(HttpStatusCode.MethodNotAllowed.value, NO_RESPONSE_BODY)
                    return
                }
                if (exchange.requestMethod == "PUT") {
                    uploadedBytes.set(exchange.requestBody.use { it.readBytes() })
                }
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

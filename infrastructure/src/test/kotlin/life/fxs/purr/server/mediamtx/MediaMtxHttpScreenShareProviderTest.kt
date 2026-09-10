package life.fxs.purr.server.mediamtx

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.config.MediaMtxConfig
import life.fxs.purr.server.model.ScreenShareSource
import life.fxs.purr.server.model.ScreenShareStatus

class MediaMtxHttpScreenShareProviderTest {
    @Test
    fun `provider uses v3 path and session APIs and returns only complete snapshots`() {
        MediaMtxApiStub().use { stub ->
            val provider = MediaMtxHttpScreenShareProvider(
                MediaMtxConfig(
                    apiBaseUrl = stub.endpoint,
                    requestTimeoutMillis = 2_000,
                ),
            )
            val record = record()

            provider.ensurePath(record, "0123456789-passphrase")
            val snapshot = provider.snapshot()
            provider.cleanup(record)

            assertEquals(true, snapshot.paths[record.mediaPath]?.online)
            assertEquals("webrtcSession", snapshot.paths[record.mediaPath]?.sourceType)
            assertEquals("source-1", snapshot.paths[record.mediaPath]?.sourceId)
            assertEquals(false, snapshot.paths["screen-other"]?.online)

            val add = stub.requests.first { it.path == "/v3/config/paths/add/${record.mediaPath}" }
            assertEquals("POST", add.method)
            assertTrue(add.body.contains("\"srtPublishPassphrase\":\"0123456789-passphrase\""))
            assertTrue(add.body.contains("\"maxReaders\":2"))
            assertTrue(stub.requests.any { it.path == "/v3/webrtc/sessions/kick/webrtc-1" })
            assertTrue(stub.requests.none { it.path == "/v3/webrtc/sessions/kick/webrtc-other" })
            assertTrue(stub.requests.any { it.path == "/v3/srt/conns/kick/srt-1" })
            assertTrue(stub.requests.any {
                it.method == "DELETE" && it.path == "/v3/config/paths/delete/${record.mediaPath}"
            })
        }
    }

    private fun record() = ScreenShareRecord(
        shareId = "share-1",
        callId = "call-1",
        ownerUserId = "user-a",
        source = ScreenShareSource.OBS,
        mediaPath = "screen-share-1",
        status = ScreenShareStatus.AUTHORIZED,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        expiresAtEpochMillis = 60_000,
    )

    private class MediaMtxApiStub : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                requests += Request(exchange.requestMethod, exchange.requestURI.path, body)
                val response = responseFor(exchange.requestURI.path, exchange.requestURI.query)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        val endpoint = "http://127.0.0.1:${server.address.port}"

        private fun responseFor(path: String, query: String?): String = when (path) {
            "/v3/paths/list" -> if (query?.contains("page=0") == true) {
                """
                    {"items":[{"name":"screen-share-1","online":true,
                    "source":{"type":"webrtcSession","id":"source-1"}}],"pageCount":2}
                """.trimIndent()
            } else {
                """{"items":[{"name":"screen-other","online":false}],"pageCount":2}"""
            }
            "/v3/webrtclk/sessions/list" -> error("unexpected legacy endpoint")
            "/v3/webrtc/sessions/list" ->
                """{"items":[{"id":"webrtc-1","path":"screen-share-1"},{"id":"webrtc-other","path":"screen-other"}],"pageCount":1}"""
            "/v3/srt/conns/list" ->
                """{"items":[{"id":"srt-1","path":"screen-share-1"}],"pageCount":1}"""
            else -> "{}"
        }

        override fun close() {
            server.stop(0)
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: String,
    )
}

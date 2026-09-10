package life.fxs.purr.server.mediamtx

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import life.fxs.purr.server.application.port.ScreenShareProvider
import life.fxs.purr.server.application.port.ScreenShareProviderPath
import life.fxs.purr.server.application.port.ScreenShareProviderSnapshot
import life.fxs.purr.server.application.port.ScreenShareRecord
import life.fxs.purr.server.config.MediaMtxConfig

class MediaMtxHttpScreenShareProvider(
    private val config: MediaMtxConfig,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.requestTimeoutMillis))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ScreenShareProvider {
    override fun ensurePath(record: ScreenShareRecord, srtPublishPassphrase: String) {
        val body = json.encodeToString(
            PathConfiguration(
                source = "publisher",
                overridePublisher = false,
                maxReaders = 2,
                record = false,
                srtPublishPassphrase = srtPublishPassphrase,
            ),
        )
        send(
            method = "POST",
            path = "/v3/config/paths/add/${record.mediaPath.pathSegment()}",
            body = body,
        )
    }

    override fun snapshot(): ScreenShareProviderSnapshot {
        val paths = readAllPages<PathListResponse, PathResponse>("/v3/paths/list") { it.items to it.pageCount }
            .associate { path ->
                path.name to ScreenShareProviderPath(
                    mediaPath = path.name,
                    online = path.online,
                    sourceType = path.source?.type,
                    sourceId = path.source?.id,
                    inboundBytes = path.inboundBytes,
                )
            }
        return ScreenShareProviderSnapshot(paths)
    }

    override fun cleanup(record: ScreenShareRecord) {
        readAllPages<WebRtcSessionListResponse, ProviderSession>("/v3/webrtc/sessions/list") {
            it.items to it.pageCount
        }.filter { it.path == record.mediaPath }
            .forEach { session -> send("POST", "/v3/webrtc/sessions/kick/${session.id.pathSegment()}") }

        readAllPages<SrtSessionListResponse, ProviderSession>("/v3/srt/conns/list") {
            it.items to it.pageCount
        }.filter { it.path == record.mediaPath }
            .forEach { session -> send("POST", "/v3/srt/conns/kick/${session.id.pathSegment()}") }

        send(
            method = "DELETE",
            path = "/v3/config/paths/delete/${record.mediaPath.pathSegment()}",
            allowNotFound = true,
        )
    }

    private inline fun <reified T, R> readAllPages(
        path: String,
        projection: (T) -> Pair<List<R>, Int>,
    ): List<R> {
        val first = json.decodeFromString<T>(send("GET", "$path?page=0&itemsPerPage=$ITEMS_PER_PAGE"))
        val (firstItems, pageCount) = projection(first)
        if (pageCount <= 1) return firstItems
        return buildList {
            addAll(firstItems)
            for (page in 1 until pageCount) {
                val response = json.decodeFromString<T>(
                    send("GET", "$path?page=$page&itemsPerPage=$ITEMS_PER_PAGE"),
                )
                addAll(projection(response).first)
            }
        }
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        allowNotFound: Boolean = false,
    ): String {
        val requestBuilder = HttpRequest.newBuilder(URI.create(config.apiBaseUrl + path))
            .timeout(Duration.ofMillis(config.requestTimeoutMillis))
            .header("Accept", "application/json")
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        if (body != null) requestBuilder.header("Content-Type", "application/json")
        val response = try {
            client.send(requestBuilder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("MediaMTX request interrupted", error)
        }
        if (response.statusCode() in 200..299 || (allowNotFound && response.statusCode() == 404)) {
            return response.body()
        }
        throw IllegalStateException("MediaMTX API returned HTTP ${response.statusCode()}")
    }

    private fun String.pathSegment(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

    @Serializable
    private data class PathConfiguration(
        val source: String,
        val overridePublisher: Boolean,
        val maxReaders: Int,
        val record: Boolean,
        val srtPublishPassphrase: String,
    )

    @Serializable
    private data class PathListResponse(
        val items: List<PathResponse> = emptyList(),
        val pageCount: Int = 0,
    )

    @Serializable
    private data class PathResponse(
        val name: String,
        val online: Boolean = false,
        val inboundBytes: Long? = null,
        val source: PathSourceResponse? = null,
    )

    @Serializable
    private data class PathSourceResponse(
        val type: String? = null,
        val id: String? = null,
    )

    @Serializable
    private data class WebRtcSessionListResponse(
        val items: List<ProviderSession> = emptyList(),
        val pageCount: Int = 0,
    )

    @Serializable
    private data class SrtSessionListResponse(
        val items: List<ProviderSession> = emptyList(),
        val pageCount: Int = 0,
    )

    @Serializable
    private data class ProviderSession(
        val id: String,
        val path: String? = null,
    )

    private companion object {
        const val ITEMS_PER_PAGE = 1_000
    }
}

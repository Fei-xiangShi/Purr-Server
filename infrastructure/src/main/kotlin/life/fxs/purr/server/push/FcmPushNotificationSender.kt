package life.fxs.purr.server.push

import com.google.auth.oauth2.GoogleCredentials
import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import life.fxs.purr.server.application.port.IncomingCallPushMessage
import life.fxs.purr.server.application.port.PushDeliveryResult
import life.fxs.purr.server.application.port.PushDeviceRecord
import life.fxs.purr.server.application.port.PushNotificationSender
import life.fxs.purr.server.application.port.PushProvider
import life.fxs.purr.server.config.PushConfig

class FcmPushNotificationSender(
    private val config: PushConfig,
    private val json: Json = Json { explicitNulls = false },
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
    credentialsProvider: () -> GoogleCredentials = {
        FileInputStream(config.fcmServiceAccountPath).use { input ->
            GoogleCredentials.fromStream(input).createScoped(FCM_SCOPE)
        }
    },
) : PushNotificationSender {
    private val credentials = credentialsProvider()
    private val endpoint = URI.create(
        "https://fcm.googleapis.com/v1/projects/${config.fcmProjectId}/messages:send",
    )

    override suspend fun send(
        device: PushDeviceRecord,
        message: IncomingCallPushMessage,
    ): PushDeliveryResult {
        require(device.provider == PushProvider.FCM) { "Unsupported push provider: ${device.provider}" }
        val requestBody = FcmRequestEncoder(json).encode(
            token = device.token,
            message = message,
            messageTtlSeconds = config.messageTtlSeconds,
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer ${accessToken()}")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        FcmResponseClassifier(json).classify(response.statusCode(), response.body())?.let { return it }

        throw FcmDeliveryException(
            statusCode = response.statusCode(),
            responseSummary = response.body().take(MAX_ERROR_BODY_LENGTH),
        )
    }

    override fun isReady(): Boolean = config.enabled

    private suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        synchronized(credentials) {
            credentials.refreshIfExpired()
            credentials.accessToken?.tokenValue
                ?: credentials.refreshAccessToken().tokenValue
        }
    }

    private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel(true) }
            whenComplete { value, error ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(value)
            }
        }

    private companion object {
        const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 15L
        const val MAX_ERROR_BODY_LENGTH = 2_048
    }
}

internal class FcmRequestEncoder(
    private val json: Json,
) {
    fun encode(
        token: String,
        message: IncomingCallPushMessage,
        messageTtlSeconds: Long,
    ): String = json.encodeToString(
        FcmSendRequest(
            message = FcmMessage(
                token = token,
                data = mapOf(
                    "type" to INCOMING_CALL_TYPE,
                    "callId" to message.callId,
                    "startedAtEpochMillis" to message.startedAtEpochMillis.toString(),
                ),
                android = FcmAndroidConfig(
                    priority = "HIGH",
                    ttl = "${messageTtlSeconds}s",
                    collapseKey = INCOMING_CALL_COLLAPSE_KEY,
                ),
            ),
        ),
    )

    private companion object {
        const val INCOMING_CALL_TYPE = "incoming_call"
        const val INCOMING_CALL_COLLAPSE_KEY = "incoming-call"
    }
}

internal class FcmResponseClassifier(
    private val json: Json,
) {
    fun classify(statusCode: Int, responseBody: String): PushDeliveryResult? {
        if (statusCode in 200..299) return PushDeliveryResult.Delivered
        return if (responseBody.fcmErrorCode() == UNREGISTERED) {
            PushDeliveryResult.DeviceUnregistered
        } else {
            null
        }
    }

    private fun String.fcmErrorCode(): String? {
        val error = runCatching { json.parseToJsonElement(this) as? JsonObject }
            .getOrNull()
            ?.get("error") as? JsonObject
        val details = error?.get("details") as? JsonArray ?: return null
        return details.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstNotNullOfOrNull { detail -> detail["errorCode"]?.jsonPrimitive?.content }
    }

    private companion object {
        const val UNREGISTERED = "UNREGISTERED"
    }
}

class FcmDeliveryException(
    val statusCode: Int,
    responseSummary: String,
) : IllegalStateException("FCM delivery failed with HTTP $statusCode: $responseSummary")

@Serializable
private data class FcmSendRequest(
    val message: FcmMessage,
)

@Serializable
private data class FcmMessage(
    val token: String,
    val data: Map<String, String>,
    val android: FcmAndroidConfig,
)

@Serializable
private data class FcmAndroidConfig(
    val priority: String,
    val ttl: String,
    @SerialName("collapse_key") val collapseKey: String,
)

package life.fxs.purr.server.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import life.fxs.purr.server.application.port.IncomingCallPushMessage
import life.fxs.purr.server.application.port.PushDeliveryResult

class FcmPushNotificationSenderTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `request is a high priority data-only incoming call wake signal`() {
        val body = FcmRequestEncoder(json).encode(
            token = "device-token",
            message = IncomingCallPushMessage("call-123", 1_752_580_800_000L),
            messageTtlSeconds = 60,
        )

        val payload = json.parseToJsonElement(body).toString()
        assertTrue(payload.contains("\"type\":\"incoming_call\""))
        assertTrue(payload.contains("\"callId\":\"call-123\""))
        assertTrue(payload.contains("\"startedAtEpochMillis\":\"1752580800000\""))
        assertTrue(payload.contains("\"priority\":\"HIGH\""))
        assertTrue(payload.contains("\"ttl\":\"60s\""))
        assertTrue(payload.contains("\"collapse_key\":\"incoming-call\""))
        assertTrue(!payload.contains("notification"))
    }

    @Test
    fun `only explicit structured unregistered errors disable a device`() {
        val classifier = FcmResponseClassifier(json)
        val unregistered =
            """
                {
                  "error": {
                    "code": 404,
                    "status": "NOT_FOUND",
                    "details": [{
                      "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
                      "errorCode": "UNREGISTERED"
                    }]
                  }
                }
            """.trimIndent()

        assertEquals(PushDeliveryResult.Delivered, classifier.classify(200, "{}"))
        assertEquals(PushDeliveryResult.DeviceUnregistered, classifier.classify(404, unregistered))
        assertNull(classifier.classify(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}"))
        assertNull(classifier.classify(503, "UNREGISTERED"))
    }
}

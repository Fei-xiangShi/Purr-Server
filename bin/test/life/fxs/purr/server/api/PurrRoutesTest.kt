package life.fxs.purr.server.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PurrRoutesTest {
    @Test
    fun `health readiness and request ids are exposed`() = testApplication {
        val response = client.get("/health/ready") {
            header(HttpHeaders.XRequestId, "request-123")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("request-123", response.headers[HttpHeaders.XRequestId])
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `Prometheus metrics expose HTTP and JVM telemetry`() = testApplication {
        client.get("/health/live")

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("jvm_memory_used_bytes"))
        assertTrue(body.contains("ktor_http_server_requests"))
    }

    @Test
    fun `invalid requests return structured validation errors`() = testApplication {
        val response = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"","password":"password"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"validation_error\""))
        assertTrue(!response.headers[HttpHeaders.XRequestId].isNullOrBlank())
    }

    @Test
    fun `authentication endpoints are rate limited`() = testApplication {
        val responses = List(11) {
            client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"missing","password":"password"}""")
            }
        }

        assertEquals(10, responses.count { it.status == HttpStatusCode.Unauthorized })
        assertEquals(HttpStatusCode.TooManyRequests, responses.last().status)
        assertEquals("10", responses.last().headers["RateLimit-Limit"])
        assertTrue(responses.last().headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it >= 1L } == true)
    }

    @Test
    fun `login returns tokens and authenticated routes work`() = testApplication {
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val loginBody = login.bodyAsText()
        assertTrue(loginBody.contains("\"accessToken\":"))
        assertTrue(loginBody.contains("\"refreshToken\":"))
        assertTrue(loginBody.contains("\"userId\":\"user-a\""))

        val accessToken = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(loginBody)?.groupValues?.get(1)
        check(!accessToken.isNullOrBlank())

        val meResponse = client.get("/me") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
        assertTrue(meResponse.bodyAsText().contains("\"userId\":\"user-a\""))

        val pairResponse = client.get("/pair") {
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, pairResponse.status)
        assertTrue(pairResponse.bodyAsText().contains("\"pairId\":\"pair-demo\""))
        assertTrue(pairResponse.bodyAsText().contains("\"userId\":\"user-b\""))
    }

    @Test
    fun `refresh rotates session and logout revokes user sessions`() = testApplication {
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        val loginBody = login.bodyAsText()
        val accessToken = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(loginBody)?.groupValues?.get(1)
        val refreshToken = Regex("\\\"refreshToken\\\":\\\"([^\\\"]+)\\\"").find(loginBody)?.groupValues?.get(1)
        check(!accessToken.isNullOrBlank())
        check(!refreshToken.isNullOrBlank())

        val refresh = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, refresh.status)
        val refreshBody = refresh.bodyAsText()
        val refreshedAccessToken = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(refreshBody)?.groupValues?.get(1)
        val refreshedRefreshToken = Regex("\\\"refreshToken\\\":\\\"([^\\\"]+)\\\"").find(refreshBody)?.groupValues?.get(1)
        check(!refreshedAccessToken.isNullOrBlank())
        check(!refreshedRefreshToken.isNullOrBlank())

        val logout = client.post("/auth/logout") {
            header("Authorization", "Bearer $refreshedAccessToken")
        }
        assertEquals(HttpStatusCode.OK, logout.status)

        val accessAfterLogout = client.get("/me") {
            header("Authorization", "Bearer $refreshedAccessToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, accessAfterLogout.status)

        val refreshAfterLogout = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshedRefreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshAfterLogout.status)
    }

    @Test
    fun `refresh token can only be consumed once under concurrency`() = testApplication {
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        val refreshToken = Regex("\\\"refreshToken\\\":\\\"([^\\\"]+)\\\"")
            .find(login.bodyAsText())
            ?.groupValues
            ?.get(1)
        check(!refreshToken.isNullOrBlank())

        val responses = coroutineScope {
            List(2) {
                async {
                    client.post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$refreshToken"}""")
                    }
                }
            }.awaitAll()
        }

        assertEquals(
            listOf(HttpStatusCode.OK.value, HttpStatusCode.Unauthorized.value),
            responses.map { it.status.value }.sorted(),
        )
    }

    @Test
    fun `recording starts when second participant joins and stops after everyone leaves`() = testApplication {
        val userAToken = client.login("user-a", "pass-a")
        val userBToken = client.login("user-b", "pass-b")

        val sessionA = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, sessionA.status)
        val sessionABody = sessionA.bodyAsText()
        assertTrue(sessionABody.contains("\"pairId\":\"pair-demo\""))

        val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"").find(sessionABody)?.groupValues?.get(1)
        val roomName = Regex("\\\"roomName\\\":\\\"([^\\\"]+)\\\"").find(sessionABody)?.groupValues?.get(1)
        assertTrue(!callId.isNullOrBlank())
        assertTrue(!roomName.isNullOrBlank())
        assertTrue(sessionABody.contains("\"participantIdentity\":\"user-a-$callId\""))

        val sessionB = client.post("/calls/session") {
            header("Authorization", "Bearer $userBToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        val sessionBBody = sessionB.bodyAsText()
        assertEquals(HttpStatusCode.OK, sessionB.status)
        assertTrue(sessionBBody.contains("\"callId\":\"$callId\""))
        assertTrue(sessionBBody.contains("\"roomName\":\"$roomName\""))
        assertTrue(sessionBBody.contains("\"participantIdentity\":\"user-b-$callId\""))

        val initialCallStatus = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, initialCallStatus.status)
        assertTrue(initialCallStatus.bodyAsText().contains("\"recordingStatus\":\"idle\""))

        client.postLiveKitWebhook(
            body = """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-a-join",
                  "room":{"name":"$roomName","numParticipants":1},
                  "participant":{
                    "identity":"user-a-$callId",
                    "state":"ACTIVE",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val afterFirstJoin = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, afterFirstJoin.status)
        assertTrue(afterFirstJoin.bodyAsText().contains("\"recordingStatus\":\"idle\""))

        client.postLiveKitWebhook(
            body = """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-b-join",
                  "room":{"name":"$roomName","numParticipants":2},
                  "participant":{
                    "identity":"user-b-$callId",
                    "state":"ACTIVE",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val afterSecondJoin = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, afterSecondJoin.status)
        assertTrue(afterSecondJoin.bodyAsText().contains("\"recordingStatus\":\"recording\""))

        client.postLiveKitWebhook(
            body = """
                {
                  "event":"participant_left",
                  "id":"event-$callId-a-left",
                  "room":{"name":"$roomName","numParticipants":1},
                  "participant":{
                    "identity":"user-a-$callId",
                    "state":"DISCONNECTED",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val afterFirstLeave = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, afterFirstLeave.status)
        assertTrue(afterFirstLeave.bodyAsText().contains("\"recordingStatus\":\"recording\""))

        client.postLiveKitWebhook(
            body = """
                {
                  "event":"participant_left",
                  "id":"event-$callId-b-left",
                  "room":{"name":"$roomName","numParticipants":0},
                  "participant":{
                    "identity":"user-b-$callId",
                    "state":"DISCONNECTED",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val afterEveryoneLeaves = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, afterEveryoneLeaves.status)
        assertTrue(afterEveryoneLeaves.bodyAsText().contains("\"recordingStatus\":\"stopped\""))

        val recordings = client.get("/calls/$callId/recordings") {
            header("Authorization", "Bearer $userBToken")
        }
        val recordingsBody = recordings.bodyAsText()
        assertEquals(HttpStatusCode.OK, recordings.status)
        assertTrue(recordingsBody.contains("\"status\":\"stopped\""))
        assertTrue(recordingsBody.contains("\"downloadAvailable\":true"))
        assertTrue(!recordingsBody.contains("objectKey"))
        assertTrue(recordingsBody.contains("\"durationMillis\":"))

        val recordingId = Regex("\\\"recordingId\\\":\\\"([^\\\"]+)\\\"")
            .find(recordingsBody)
            ?.groupValues
            ?.get(1)
        check(!recordingId.isNullOrBlank())
        val download = client.post("/calls/$callId/recordings/$recordingId/download") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals("no-store", download.headers[HttpHeaders.CacheControl])
        assertTrue(download.bodyAsText().contains("\"url\":\"http://localhost:9000/"))
        assertTrue(download.bodyAsText().contains("X-Amz-Signature"))

        val secondStart = client.post("/calls/$callId/recording/start") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, secondStart.status)
        val secondStop = client.post("/calls/$callId/recording/stop") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, secondStop.status)

        val firstPage = client.get("/recordings?limit=1") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, firstPage.status)
        val firstPageBody = firstPage.bodyAsText()
        assertEquals(1, Regex("\"recordingId\"").findAll(firstPageBody).count())
        assertTrue(!firstPageBody.contains("objectKey"))
        val nextCursor = Regex("\\\"nextCursor\\\":\\\"([^\\\"]+)")
            .find(firstPageBody)
            ?.groupValues
            ?.get(1)
        check(!nextCursor.isNullOrBlank())

        val secondPage = client.get("/recordings?limit=1&before=$nextCursor") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, secondPage.status)
        assertEquals(1, Regex("\"recordingId\"").findAll(secondPage.bodyAsText()).count())

        val invalidPage = client.get("/recordings?limit=51") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPage.status)

        val endCall = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endCall.status)
    }

    @Test
    fun `recording does not start without both recorded consents`() = testApplication {
        val userAToken = client.login("user-a", "pass-a")

        val session = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, session.status)
        val sessionBody = session.bodyAsText()
        val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"").find(sessionBody)?.groupValues?.get(1)
        val roomName = Regex("\\\"roomName\\\":\\\"([^\\\"]+)\\\"").find(sessionBody)?.groupValues?.get(1)
        check(!callId.isNullOrBlank())
        check(!roomName.isNullOrBlank())

        client.postLiveKitWebhook(
            """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-a-join",
                  "room":{"name":"$roomName","numParticipants":1},
                  "participant":{
                    "identity":"user-a-$callId",
                    "state":"ACTIVE",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val afterFirstJoin = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, afterFirstJoin.status)
        assertTrue(afterFirstJoin.bodyAsText().contains("\"recordingStatus\":\"idle\""))

        client.postLiveKitWebhook(
            """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-b-join",
                  "room":{"name":"$roomName","numParticipants":2},
                  "participant":{
                    "identity":"user-b-$callId",
                    "state":"ACTIVE",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )
        client.postLiveKitWebhook(
            """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-b-join-repeat",
                  "room":{"name":"$roomName","numParticipants":2},
                  "participant":{
                    "identity":"user-b-$callId",
                    "state":"ACTIVE",
                    "kind":"STANDARD"
                  }
                }
            """.trimIndent(),
        )

        val callStatus = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, callStatus.status)
        val body = callStatus.bodyAsText()
        assertTrue(body.contains("\"recordingStatus\":\"idle\""))

        val endCall = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endCall.status)
    }

    @Test
    fun `recording stop is rejected while egress is still starting`() = testApplication {
        val userAToken = client.login("user-a", "pass-a")
        val userBToken = client.login("user-b", "pass-b")

        val sessionA = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, sessionA.status)
        val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"").find(sessionA.bodyAsText())?.groupValues?.get(1)
        check(!callId.isNullOrBlank())

        val sessionB = client.post("/calls/session") {
            header("Authorization", "Bearer $userBToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, sessionB.status)

        val startRecording = client.post("/calls/$callId/recording/start") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, startRecording.status)
        assertTrue(startRecording.bodyAsText().contains("\"status\":\"recording\"") || startRecording.bodyAsText().contains("\"status\":\"starting\""))

        val stopRecording = client.post("/calls/$callId/recording/stop") {
            header("Authorization", "Bearer $userAToken")
        }
        if (startRecording.bodyAsText().contains("\"status\":\"starting\"")) {
            assertEquals(HttpStatusCode.BadRequest, stopRecording.status)
            assertTrue(stopRecording.bodyAsText().contains("still starting"))
        }

        val endCall = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endCall.status)
    }

    @Test
    fun `call session rejects missing recording consent`() = testApplication {
        val token = client.login("user-a", "pass-a")
        val response = client.post("/calls/session") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Explicit recording consent is required"))
    }

    @Test
    fun `rejects unknown pair`() = testApplication {
        val token = client.login("user-a", "pass-a")
        val response = client.post("/calls/session") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"wrong-pair"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unknown pairId"))
    }

    private suspend fun HttpClient.login(username: String, password: String): String {
        val response = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        return Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("Missing accessToken in $body")
    }

    private suspend fun HttpClient.postLiveKitWebhook(body: String) {
        val response = post("/webhooks/livekit") {
            contentType(ContentType.Application.Json)
            header("Authorization", signLiveKitWebhook(body))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private fun signLiveKitWebhook(body: String): String {
        val sha256 = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray()),
        )
        return JWT.create()
            .withIssuer("devkey")
            .withClaim("sha256", sha256)
            .sign(Algorithm.HMAC256("devsecret"))
    }
}

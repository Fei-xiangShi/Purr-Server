package life.fxs.purr.server.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.sql.DriverManager
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
    fun `password change verifies current password and revokes all sessions`() = testApplication {
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        val loginBody = login.bodyAsText()
        val accessToken = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(loginBody)?.groupValues?.get(1)
        check(!accessToken.isNullOrBlank())

        val incorrect = client.put("/me/password") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"wrong-password","newPassword":"new-password"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, incorrect.status)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/me") { header(HttpHeaders.Authorization, "Bearer $accessToken") }.status,
        )

        val changed = client.put("/me/password") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"pass-a","newPassword":"new-password"}""")
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/me") { header(HttpHeaders.Authorization, "Bearer $accessToken") }.status,
        )

        val oldPasswordLogin = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"pass-a"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, oldPasswordLogin.status)

        val newPasswordLogin = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user-a","password":"new-password"}""")
        }
        assertEquals(HttpStatusCode.OK, newPasswordLogin.status)
    }

    @Test
    fun `authenticated user can update display name`() = testApplication {
        val accessToken = client.login("user-a", "pass-a")

        val updated = client.put("/me/profile") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"  New Display Name  "}""")
        }

        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.bodyAsText().contains("\"displayName\":\"New Display Name\""))
        val me = client.get("/me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertTrue(me.bodyAsText().contains("\"displayName\":\"New Display Name\""))
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
    fun `push registration is validated and removed when its auth session rotates`() = testApplication {
        val tokens = client.loginWithRefreshToken("user-a", "pass-a")
        val installationId = "550e8400-e29b-41d4-a716-446655440000"
        val token = "fcm-token-abcdefghijklmnopqrstuvwxyz-0123456789"

        val registered = client.put("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"FCM","token":"$token"}""")
        }
        assertEquals(HttpStatusCode.NoContent, registered.status)
        assertEquals(1, countPushDevices(installationId))

        val invalidProvider = client.put("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"APNS","token":"$token"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidProvider.status)

        val invalidToken = client.put("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"FCM","token":"short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidToken.status)

        val invalidInstallation = client.put("/devices/push/short") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"FCM","token":"$token"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidInstallation.status)

        val refreshed = client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${tokens.refreshToken}"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        assertEquals(0, countPushDevices(installationId))
    }

    @Test
    fun `push unregister is scoped to the authenticated user`() = testApplication {
        val userAToken = client.login("user-a", "pass-a")
        val userBToken = client.login("user-b", "pass-b")
        val installationId = "550e8400-e29b-41d4-a716-446655440001"
        val token = "fcm-token-bcdefghijklmnopqrstuvwxyz-0123456789"

        val registered = client.put("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"provider":"FCM","token":"$token"}""")
        }
        assertEquals(HttpStatusCode.NoContent, registered.status)

        val otherUserDelete = client.delete("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.NoContent, otherUserDelete.status)
        assertEquals(1, countPushDevices(installationId))

        val ownerDelete = client.delete("/devices/push/$installationId") {
            header(HttpHeaders.Authorization, "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.NoContent, ownerDelete.status)
        assertEquals(0, countPushDevices(installationId))
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
    fun `livekit webhook rejects oversized payload before signature processing`() = testApplication {
        val body = "x".repeat(1_048_577)
        val response = client.post("/webhooks/livekit") {
            contentType(ContentType.Application.Json)
            header("Authorization", signLiveKitWebhook(body))
            setBody(body)
        }

        assertEquals(HttpStatusCode(413, "Payload Too Large"), response.status)
        assertTrue(response.bodyAsText().contains("payload_too_large"))
    }

    @Test
    fun `recording starts only after thirty seconds and valid calls remain pageable`() = testApplication {
        val userAToken = client.login("user-a", "pass-a")
        val userBToken = client.login("user-b", "pass-b")

        val sessionA = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, sessionA.status)
        val sessionABody = sessionA.bodyAsText()
        assertTrue(sessionABody.contains("\"pairId\":\"pair-demo\""), "first session pair")

        val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"").find(sessionABody)?.groupValues?.get(1)
        val roomName = Regex("\\\"roomName\\\":\\\"([^\\\"]+)\\\"").find(sessionABody)?.groupValues?.get(1)
        assertTrue(!callId.isNullOrBlank(), "first session call id")
        assertTrue(!roomName.isNullOrBlank(), "first session room")
        assertTrue(sessionABody.contains("\"participantIdentity\":\"user-a-$callId\""), "caller identity")

        val sessionB = client.post("/calls/session") {
            header("Authorization", "Bearer $userBToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        val sessionBBody = sessionB.bodyAsText()
        assertEquals(HttpStatusCode.OK, sessionB.status)
        assertTrue(sessionBBody.contains("\"callId\":\"$callId\""), "callee joins call")
        assertTrue(sessionBBody.contains("\"roomName\":\"$roomName\""), "callee joins room")
        assertTrue(sessionBBody.contains("\"participantIdentity\":\"user-b-$callId\""), "callee identity")

        val initialCallStatus = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, initialCallStatus.status)
        assertTrue(initialCallStatus.bodyAsText().contains("\"recordingStatus\":\"idle\""), "initial recording idle")

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
        assertTrue(afterFirstJoin.bodyAsText().contains("\"state\":\"waiting\""), "waiting after first join")
        assertTrue(afterFirstJoin.bodyAsText().contains("\"recordingStatus\":\"idle\""), "idle after first join")

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
        val afterSecondJoinBody = afterSecondJoin.bodyAsText()
        assertEquals(HttpStatusCode.OK, afterSecondJoin.status)
        assertTrue(afterSecondJoinBody.contains("\"state\":\"active\""), "active after second join: $afterSecondJoinBody")
        assertTrue(afterSecondJoinBody.contains("\"startedAtEpochMillis\":"), "started timestamp after join: $afterSecondJoinBody")
        assertTrue(afterSecondJoinBody.contains("\"durationMillis\":"), "duration after join: $afterSecondJoinBody")
        assertTrue(afterSecondJoinBody.contains("\"recordingStatus\":\"idle\""), "recording remains idle before threshold: $afterSecondJoinBody")

        makeCallRecordingEligible(callId, releaseStartCommand = true)
        val startRecording = client.post("/calls/$callId/recording/start") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, startRecording.status)
        assertTrue(startRecording.bodyAsText().contains("\"status\":\"recording\""))

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
        assertTrue(afterFirstLeave.bodyAsText().contains("\"recordingStatus\":\"recording\""), "recording after first leave")

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
        assertTrue(afterEveryoneLeaves.bodyAsText().contains("\"state\":\"ended\""), "ended after all leave")
        assertTrue(afterEveryoneLeaves.bodyAsText().contains("\"recordingStatus\":\"stopped\""), "recording stopped after all leave")

        val recordings = client.get("/calls/$callId/recordings") {
            header("Authorization", "Bearer $userBToken")
        }
        val recordingsBody = recordings.bodyAsText()
        assertEquals(HttpStatusCode.OK, recordings.status)
        assertTrue(recordingsBody.contains("\"status\":\"stopped\""), "recording history stopped")
        assertTrue(recordingsBody.contains("\"downloadAvailable\":true"), "recording download available")
        assertTrue(!recordingsBody.contains("objectKey"), "recording key hidden")
        assertTrue(recordingsBody.contains("\"durationMillis\":"), "recording duration")

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
        assertTrue(download.bodyAsText().contains("\"url\":\"http://localhost:9000/"), "download URL")
        assertTrue(download.bodyAsText().contains("X-Amz-Signature"), "signed download URL")

        val nextSessionA = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, nextSessionA.status)
        val nextSessionBody = nextSessionA.bodyAsText()
        val nextCallId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"")
            .find(nextSessionBody)
            ?.groupValues
            ?.get(1)
        val nextRoomName = Regex("\\\"roomName\\\":\\\"([^\\\"]+)\\\"")
            .find(nextSessionBody)
            ?.groupValues
            ?.get(1)
        check(!nextCallId.isNullOrBlank())
        check(!nextRoomName.isNullOrBlank())
        assertTrue(nextCallId != callId)
        assertTrue(nextRoomName != roomName)

        val nextSessionB = client.post("/calls/session") {
            header("Authorization", "Bearer $userBToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, nextSessionB.status)
        assertTrue(nextSessionB.bodyAsText().contains("\"callId\":\"$nextCallId\""), "second call join")

        listOf(
            Triple("a-join", 1, "user-a-$nextCallId"),
            Triple("b-join", 2, "user-b-$nextCallId"),
        ).forEach { (eventSuffix, participantCount, identity) ->
            client.postLiveKitWebhook(
                body = """
                    {
                      "event":"participant_joined",
                      "id":"event-$nextCallId-$eventSuffix",
                      "room":{"name":"$nextRoomName","numParticipants":$participantCount},
                      "participant":{"identity":"$identity","state":"ACTIVE","kind":"STANDARD"}
                    }
                """.trimIndent(),
            )
        }
        makeCallRecordingEligible(nextCallId, releaseStartCommand = false)
        listOf(
            Triple("a-left", 1, "user-a-$nextCallId"),
            Triple("b-left", 0, "user-b-$nextCallId"),
        ).forEach { (eventSuffix, participantCount, identity) ->
            client.postLiveKitWebhook(
                body = """
                    {
                      "event":"participant_left",
                      "id":"event-$nextCallId-$eventSuffix",
                      "room":{"name":"$nextRoomName","numParticipants":$participantCount},
                      "participant":{"identity":"$identity","state":"DISCONNECTED","kind":"STANDARD"}
                    }
                """.trimIndent(),
            )
        }

        val firstPage = client.get("/calls/history?limit=1") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, firstPage.status)
        val firstPageBody = firstPage.bodyAsText()
        assertEquals(1, Regex("\"callId\"").findAll(firstPageBody).count())
        assertTrue(firstPageBody.contains("\"startedAtEpochMillis\":"), "history start timestamp")
        assertTrue(firstPageBody.contains("\"durationMillis\":"), "history duration")
        assertTrue(!firstPageBody.contains("recordingId"), "history hides recording id")
        val nextCursor = Regex("\\\"nextCursor\\\":\\\"([^\\\"]+)")
            .find(firstPageBody)
            ?.groupValues
            ?.get(1)
        check(!nextCursor.isNullOrBlank())

        val secondPage = client.get("/calls/history?limit=1&before=$nextCursor") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, secondPage.status)
        assertEquals(1, Regex("\"callId\"").findAll(secondPage.bodyAsText()).count())

        val invalidPage = client.get("/calls/history?limit=51") {
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

        client.postLiveKitWebhook(
            """
                {
                  "event":"participant_joined",
                  "id":"event-$callId-b-join",
                  "room":{"name":"$roomName","numParticipants":2},
                  "participant":{"identity":"user-b-$callId","state":"ACTIVE","kind":"STANDARD"}
                }
            """.trimIndent(),
        )

        val callStatus = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, callStatus.status)

        val endCall = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endCall.status)
    }

    @Test
    fun `explicit recording start is rejected before thirty seconds`() = testApplication {
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

        listOf(
            "user-a-$callId" to 1,
            "user-b-$callId" to 2,
        ).forEach { (identity, count) ->
            client.postLiveKitWebhook(
                """
                    {
                      "event":"participant_joined",
                      "id":"event-$callId-$count",
                      "room":{"name":"pair-demo-$callId","numParticipants":$count},
                      "participant":{"identity":"$identity","state":"ACTIVE","kind":"STANDARD"}
                    }
                """.trimIndent(),
            )
        }

        val startRecording = client.post("/calls/$callId/recording/start") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.Conflict, startRecording.status)
        assertTrue(startRecording.bodyAsText().contains("after 30 seconds"))

        val endCall = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endCall.status)

        val endedCall = client.get("/calls/$callId") {
            header("Authorization", "Bearer $userAToken")
        }
        assertEquals(HttpStatusCode.OK, endedCall.status)
        assertTrue(endedCall.bodyAsText().contains("\"state\":\"ended\""))
        assertTrue(endedCall.bodyAsText().contains("\"recordingStatus\":\"idle\""))

        val repeatedEnd = client.post("/calls/$callId/end") {
            header("Authorization", "Bearer $userBToken")
        }
        assertEquals(HttpStatusCode.OK, repeatedEnd.status)

        val nextSession = client.post("/calls/session") {
            header("Authorization", "Bearer $userAToken")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, nextSession.status)
        val nextCallId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"")
            .find(nextSession.bodyAsText())
            ?.groupValues
            ?.get(1)
        check(!nextCallId.isNullOrBlank())
        assertTrue(nextCallId != callId)
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
    fun `call session rejects reconnection requests`() = testApplication {
        val token = client.login("user-a", "pass-a")
        val response = client.post("/calls/session") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"pairId":"pair-demo","resumeCallId":"call-old","recordingConsent":true}""",
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Call reconnection is not supported"))
    }

    @Test
    fun `unanswered short call is hidden from history but detail keeps telemetry`() = testApplication {
        val token = client.login("user-a", "pass-a")
        val session = client.post("/calls/session") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"pairId":"pair-demo","recordingConsent":true}""")
        }
        assertEquals(HttpStatusCode.OK, session.status)
        val callId = Regex("\\\"callId\\\":\\\"([^\\\"]+)\\\"")
            .find(session.bodyAsText())
            ?.groupValues
            ?.get(1)
        check(!callId.isNullOrBlank())

        val sampledAt = System.currentTimeMillis()
        val telemetry = client.post("/calls/$callId/telemetry") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """
                    {
                      "sampledAtEpochMillis":$sampledAt,
                      "roundTripTimeMs":42.0,
                      "jitterMs":8.0,
                      "uplinkPacketLossPercent":1.0,
                      "downlinkPacketLossPercent":2.0,
                      "uplinkBitrateKbps":64.0,
                      "downlinkBitrateKbps":96.0,
                      "networkTransport":"wifi",
                      "sendCodec":"audio/opus",
                      "receiveCodec":"audio/opus",
                      "networkValidated":true,
                      "networkMetered":false
                    }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.NoContent, telemetry.status)

        val ended = client.post("/calls/$callId/end") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, ended.status)

        val from = sampledAt - 43_200_000L
        val to = sampledAt + 43_200_000L
        val calendar = client.get("/calls/history/calendar?from=$from&to=$to&zoneId=UTC") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, calendar.status)
        assertTrue(calendar.bodyAsText().contains("\"days\":[]"), "calendar hides unanswered call")

        val day = client.get("/calls/history/day?from=$from&to=$to&limit=50") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val dayBody = day.bodyAsText()
        assertEquals(HttpStatusCode.OK, day.status)
        assertTrue(dayBody.contains("\"calls\":[]"), "day hides unanswered call")

        val detail = client.get("/calls/$callId/details") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val detailBody = detail.bodyAsText()
        assertEquals(HttpStatusCode.OK, detail.status)
        assertTrue(detailBody.contains("\"direction\":\"outgoing\""), "detail direction")
        assertTrue(detailBody.contains("\"sampleCount\":1"), "detail telemetry count")
        assertTrue(detailBody.contains("\"averageRoundTripTimeMs\":42.0"), "detail RTT summary")
        assertTrue(!detailBody.contains("roomName"), "detail hides provider room")
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

    private suspend fun HttpClient.loginWithRefreshToken(username: String, password: String): AuthTokens {
        val response = post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        return AuthTokens(
            accessToken = body.requireJsonString("accessToken"),
            refreshToken = body.requireJsonString("refreshToken"),
        )
    }

    private fun String.requireJsonString(name: String): String =
        Regex("\\\"$name\\\":\\\"([^\\\"]+)\\\"").find(this)?.groupValues?.get(1)
            ?: error("Missing $name in $this")

    private fun countPushDevices(installationId: String): Int = DriverManager.getConnection(
        "jdbc:h2:mem:purr;MODE=PostgreSQL;DB_CLOSE_DELAY=0",
        "sa",
        "",
    ).use { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM push_devices WHERE installation_id = ?",
        ).use { statement ->
            statement.setString(1, installationId)
            statement.executeQuery().use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }

    private data class AuthTokens(
        val accessToken: String,
        val refreshToken: String,
    )

    private fun makeCallRecordingEligible(callId: String, releaseStartCommand: Boolean) {
        val nowEpochMillis = System.currentTimeMillis()
        DriverManager.getConnection(
            "jdbc:h2:mem:purr;MODE=PostgreSQL;DB_CLOSE_DELAY=0",
            "sa",
            "",
        ).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "UPDATE call_sessions SET connected_at_epoch_millis = ?, updated_at_epoch_millis = ? WHERE call_id = ?",
            ).use { statement ->
                statement.setLong(1, nowEpochMillis - 30_000L)
                statement.setLong(2, nowEpochMillis)
                statement.setString(3, callId)
                check(statement.executeUpdate() == 1)
            }
            if (releaseStartCommand) {
                connection.prepareStatement(
                    "UPDATE recording_commands SET available_at_epoch_millis = ? WHERE call_id = ? AND command_type = 'START'",
                ).use { statement ->
                    statement.setLong(1, nowEpochMillis)
                    statement.setString(2, callId)
                    check(statement.executeUpdate() == 1)
                }
            }
            connection.commit()
        }
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

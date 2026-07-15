package life.fxs.purr.server.api

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class AvatarUploadAdmissionTest {
    @Test
    fun `rejects excess work before it enters the upload pipeline and releases permits`() = runBlocking {
        val admission = AvatarUploadAdmission(maxConcurrentUploads = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            admission.execute {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val rejected = assertFailsWith<ApiException> {
            admission.execute { error("must not enter") }
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, rejected.statusCode)
        assertEquals("avatar_capacity_exhausted", rejected.code)

        release.complete(Unit)
        first.await()
        assertEquals("accepted", admission.execute { "accepted" })
    }
}

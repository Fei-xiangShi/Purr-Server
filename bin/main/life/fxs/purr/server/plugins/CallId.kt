package life.fxs.purr.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import java.util.UUID

fun Application.configureCallId() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        verify(String::isValidRequestId)
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }
}

internal fun String.isValidRequestId(): Boolean = REQUEST_ID_PATTERN.matches(this)

private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._:-]{8,128}")

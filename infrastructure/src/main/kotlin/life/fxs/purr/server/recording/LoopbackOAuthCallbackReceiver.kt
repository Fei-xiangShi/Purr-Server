package life.fxs.purr.server.recording

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class LoopbackOAuthCallbackReceiver(
    private val expectedState: String,
) : OAuthCallbackReceiver {
    private val result = CompletableFuture<String>()
    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0),
        0,
    ).apply {
        createContext(CALLBACK_PATH, ::handleCallback)
        start()
    }

    override val callbackBaseUri: URI = URI(
        "http",
        null,
        LOOPBACK_HOST,
        server.address.port,
        "/",
        null,
        null,
    )
    internal val callbackUri: URI = callbackBaseUri.resolve(CALLBACK_PATH.removePrefix("/"))

    override fun awaitCode(timeout: Duration): String = try {
        result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while waiting for the Google OAuth callback", error)
    } catch (error: TimeoutException) {
        throw IllegalStateException("Timed out waiting for the Google OAuth callback", error)
    } catch (error: ExecutionException) {
        throw IllegalStateException("Google OAuth callback was rejected", error.cause ?: error)
    }

    private fun handleCallback(exchange: HttpExchange) {
        val callback = runCatching { validateCallback(exchange) }
        val message = callback.fold(
            onSuccess = { "Google Drive authorization received. You can close this tab." },
            onFailure = { "Google Drive authorization failed. Return to the terminal." },
        )
        val body = "<html><body><p>$message</p></body></html>".toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(if (callback.isSuccess) 200 else 400, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        callback.fold(result::complete) { result.completeExceptionally(it) }
    }

    private fun validateCallback(exchange: HttpExchange): String {
        require(exchange.requestMethod == "GET") { "Google OAuth callback must use GET" }
        val parameters = parseQuery(exchange.requestURI.rawQuery)
        require(parameters["state"] == expectedState) { "Google OAuth callback state did not match" }
        parameters["error"]?.let { throw IllegalStateException("Google OAuth authorization failed: $it") }
        return requireNotNull(parameters["code"]?.takeIf(String::isNotBlank)) {
            "Google OAuth callback did not include an authorization code"
        }
    }

    override fun close() = server.stop(0)
}

private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery
    ?.split('&')
    ?.filter(String::isNotBlank)
    ?.associate { parameter ->
        val separator = parameter.indexOf('=')
        val key = if (separator >= 0) parameter.substring(0, separator) else parameter
        val value = if (separator >= 0) parameter.substring(separator + 1) else ""
        URLDecoder.decode(key, StandardCharsets.UTF_8) to
            URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
    .orEmpty()

private const val LOOPBACK_HOST = "127.0.0.1"
private const val CALLBACK_PATH = "/oauth2callback"

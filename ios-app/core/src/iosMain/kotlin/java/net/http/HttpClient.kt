// java.net.http's synchronous send, over the NSURLSession HttpURLConnection (the desktop app's model downloads).
package java.net.http

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

class HttpHeaders internal constructor(private val connection: HttpURLConnection) {
    fun firstValue(name: String): Optional<String> = Optional(connection.getHeaderField(name))
    fun firstValueAsLong(name: String): OptionalLong = OptionalLong(connection.getHeaderField(name)?.toLongOrNull())
}
class Optional<T : Any>(private val value: T?) { fun orElse(other: T): T = value ?: other; fun isPresent() = value != null; fun get(): T = value!! }
class OptionalLong(private val value: Long?) { fun orElse(other: Long) = value ?: other; fun isPresent() = value != null }

class HttpRequest private constructor(val uri: URI, internal val headers: Map<String, String>, internal val method: String, internal val timeoutMs: Int) {
    class Builder(private val uri: URI) {
        private val headers = LinkedHashMap<String, String>()
        private var method = "GET"
        private var timeoutMs = 60_000
        fun header(name: String, value: String) = apply { headers[name] = value }
        fun GET() = apply { method = "GET" }
        fun timeout(d: java.time.Duration) = apply { timeoutMs = d.toMillis().toInt() }
        fun build() = HttpRequest(uri, headers, method, timeoutMs)
    }
    companion object { fun newBuilder(uri: URI) = Builder(uri) }
}

interface HttpResponse<T> {
    fun statusCode(): Int
    fun headers(): HttpHeaders
    fun body(): T
    fun interface BodyHandler<T> { fun handle(connection: HttpURLConnection): T }
    object BodyHandlers {
        fun ofInputStream(): BodyHandler<InputStream> = BodyHandler { c -> if (c.responseCode >= 400) (c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))) else c.inputStream }
        fun ofString(): BodyHandler<String> = BodyHandler { c -> ofInputStream().handle(c).readAllBytes().decodeToString() }
        fun ofByteArray(): BodyHandler<ByteArray> = BodyHandler { c -> ofInputStream().handle(c).readAllBytes() }
    }
}

class HttpClient private constructor(private val follow: Boolean, private val connectTimeoutMs: Int) {
    enum class Redirect { NEVER, ALWAYS, NORMAL }
    class Builder {
        private var follow = true; private var connect = 30_000
        fun followRedirects(r: Redirect) = apply { follow = r != Redirect.NEVER }
        fun connectTimeout(d: java.time.Duration) = apply { connect = d.toMillis().toInt() }
        fun build() = HttpClient(follow, connect)
    }
    fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val c = HttpURLConnection(request.uri.toURL())
        c.requestMethod = request.method; c.instanceFollowRedirects = follow
        c.connectTimeout = connectTimeoutMs; c.readTimeout = request.timeoutMs
        request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val status = c.responseCode
        val body = handler.handle(c)
        return object : HttpResponse<T> {
            override fun statusCode() = status
            override fun headers() = HttpHeaders(c)
            override fun body() = body
        }
    }
    companion object {
        fun newBuilder() = Builder()
        fun newHttpClient() = Builder().build()
    }
}

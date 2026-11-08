package java.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.NSObject

abstract class URLConnection(val url: URL) {
    var connectTimeout = 0
    var readTimeout = 0
    var doOutput = false
    var doInput = true
    var useCaches = true
    abstract fun connect()
    abstract val inputStream: InputStream
    abstract val outputStream: OutputStream
    abstract fun setRequestProperty(key: String, value: String)
    open fun addRequestProperty(key: String, value: String) = setRequestProperty(key, value)
    abstract fun getHeaderField(name: String): String?
    abstract val headerFields: Map<String, List<String>>
    open val contentLengthLong: Long get() = getHeaderField("Content-Length")?.toLongOrNull() ?: -1
    open val contentLength: Int get() = contentLengthLong.let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
    open val contentType: String? get() = getHeaderField("Content-Type")
    fun getURL() = url
}

/**
 * HttpURLConnection over NSURLSession. The body streams: NSURLSession's delegate appends chunks to a queue that
 * [inputStream] reads with blocking calls, so a multi-hundred-megabyte download never sits in memory. The shared
 * code calls this from background coroutines, exactly as on Android.
 */
open class HttpURLConnection(url: URL) : URLConnection(url) {
    var requestMethod = "GET"
    var instanceFollowRedirects = true
    private val headers = LinkedHashMap<String, String>()
    private val body = ByteArrayOutputStream()
    private var fixedLength = -1L

    private val lock = NSCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var response: NSHTTPURLResponse? = null
    private var failure: IOException? = null
    private var finished = false
    private var task: NSURLSessionDataTask? = null
    private var session: NSURLSession? = null

    override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    fun setFixedLengthStreamingMode(length: Int) { fixedLength = length.toLong() }
    fun setFixedLengthStreamingMode(length: Long) { fixedLength = length }
    fun setChunkedStreamingMode(@Suppress("UNUSED_PARAMETER") size: Int) {}

    override val outputStream: OutputStream get() = object : OutputStream() {
        override fun write(b: Int) = body.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = body.write(b, off, len)
    }

    private inner class Delegate : NSObject(), NSURLSessionDataDelegateProtocol {
        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveResponse: NSURLResponse,
                                completionHandler: (NSURLSessionResponseDisposition) -> Unit) {
            lock.lock(); response = didReceiveResponse as? NSHTTPURLResponse; lock.broadcast(); lock.unlock()
            completionHandler(NSURLSessionResponseAllow)
        }
        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            val bytes = ByteArray(didReceiveData.length.toInt())
            if (bytes.isNotEmpty()) bytes.usePinned { platform.posix.memcpy(it.addressOf(0), didReceiveData.bytes, didReceiveData.length) }
            lock.lock(); chunks.addLast(bytes); lock.broadcast(); lock.unlock()
        }
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse,
                                newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) {
            completionHandler(if (instanceFollowRedirects) newRequest else null)
        }
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            lock.lock()
            if (didCompleteWithError != null && didCompleteWithError.code != NSURLErrorCancelled) {
                val message = didCompleteWithError.localizedDescription
                failure = when (didCompleteWithError.code) {
                    NSURLErrorTimedOut -> SocketTimeoutException(message)
                    NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed -> UnknownHostException(message)
                    NSURLErrorCannotConnectToHost, NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost -> ConnectException(message)
                    else -> IOException(message)
                }
            }
            if (response == null) response = task.response as? NSHTTPURLResponse
            finished = true; lock.broadcast(); lock.unlock()
            session.finishTasksAndInvalidate()
        }
    }

    override fun connect() {
        if (task != null) return
        val request = NSMutableURLRequest(uRL = NSURL.URLWithString(url.toString()) ?: throw MalformedURLException(url.toString()))
        request.HTTPMethod = requestMethod
        request.setTimeoutInterval((if (readTimeout > 0) readTimeout else 60_000) / 1000.0)
        if (!useCaches) request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        headers.forEach { (k, v) -> request.setValue(v, forHTTPHeaderField = k) }
        if (doOutput && requestMethod != "GET") {
            val bytes = body.toByteArray()
            request.HTTPBody = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        }
        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration
        config.timeoutIntervalForRequest = (if (readTimeout > 0) readTimeout else 60_000) / 1000.0
        config.HTTPShouldSetCookies = false
        config.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        val s = NSURLSession.sessionWithConfiguration(config, Delegate(), queue)
        session = s
        task = s.dataTaskWithRequest(request).also { it.resume() }
    }

    private fun awaitResponse(): NSHTTPURLResponse {
        connect()
        lock.lock()
        try {
            while (response == null && !finished) lock.wait()
            failure?.let { if (response == null) throw it }
            return response ?: throw IOException("No response from ${url.host}")
        } finally { lock.unlock() }
    }

    val responseCode: Int get() = awaitResponse().statusCode.toInt()
    fun getResponseCode() = responseCode
    val responseMessage: String get() = NSHTTPURLResponse.localizedStringForStatusCode(awaitResponse().statusCode)

    override fun getHeaderField(name: String): String? {
        val all = awaitResponse().allHeaderFields
        return all.entries.firstOrNull { (it.key as? String)?.equals(name, ignoreCase = true) == true }?.value as? String
    }
    override val headerFields: Map<String, List<String>> get() =
        awaitResponse().allHeaderFields.entries.associate { (it.key as String) to listOf(it.value as String) }

    private val bodyStream = object : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                current?.let { c ->
                    if (pos < c.size) { val n = minOf(len, c.size - pos); c.copyInto(b, off, pos, pos + n); pos += n; return n }
                }
                lock.lock()
                try {
                    while (chunks.isEmpty() && !finished) lock.wait()
                    if (chunks.isEmpty()) { failure?.let { throw it }; return -1 }
                    current = chunks.removeFirst(); pos = 0
                } finally { lock.unlock() }
            }
        }
        override fun available(): Int { lock.lock(); try { return (current?.size ?: 0) - pos + chunks.sumOf { it.size } } finally { lock.unlock() } }
        override fun close() = disconnect()
    }

    override val inputStream: InputStream get() {
        val code = responseCode
        if (code >= 400) throw if (code == 404) java.io.FileNotFoundException(url.toString()) else IOException("Server returned HTTP response code: $code for URL: $url")
        return bodyStream
    }
    val errorStream: InputStream? get() = if (responseCode >= 400) bodyStream else null

    fun disconnect() { task?.cancel(); session?.invalidateAndCancel() }

    companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_MOVED_PERM = 301
        const val HTTP_MOVED_TEMP = 302
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}

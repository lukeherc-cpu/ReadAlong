package com.example.readalong.audible

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Audible refused or failed a request. [status] is the HTTP status, or null when the answer itself said no. */
open class AudibleException(message: String, val status: Int? = null) : IOException(message)
/** Audible is refusing download licences for this account for now; retrying straight away makes it worse. */
class AudibleThrottledException(message: String) : AudibleException(message)

/** The identity the iPhone profile presents (mkb79/Audible and LibriSync use the same one). */
internal const val AUDIBLE_USER_AGENT = "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0"

internal class AudibleResponse(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    val text get() = body.toString(Charsets.UTF_8)
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** A tiny HttpURLConnection client: the same code runs on Android and on desktop Java. */
internal object AudibleHttp {
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, followRedirects: Boolean = true): AudibleResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = followRedirects
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", AUDIBLE_USER_AGENT)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return AudibleResponse(status, bytes, connection.headerFields.filterKeys { it != null })
        } finally { connection.disconnect() }
    }
}

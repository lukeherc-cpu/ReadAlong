// java.net for iOS: URL encoding, URI parsing and resolution, and HttpURLConnection over NSURLSession.
package java.net

import java.io.IOException

class MalformedURLException(message: String?) : IOException(message)
class URISyntaxException(input: String, reason: String) : Exception("$reason: $input")
open class SocketTimeoutException(message: String? = null) : IOException(message)
class UnknownHostException(message: String? = null) : IOException(message)
open class ConnectException(message: String? = null) : IOException(message)
open class SocketException(message: String? = null) : IOException(message)

object URLEncoder {
    fun encode(s: String, @Suppress("UNUSED_PARAMETER") enc: String = "UTF-8"): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> append(c.toChar())
                c == ' '.code -> append('+')
                else -> { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
            }
        }
    }
    fun encode(s: String, charset: com.example.readalong.compat.Charset) = encode(s)
}

object URLDecoder {
    fun decode(s: String, @Suppress("UNUSED_PARAMETER") enc: String = "UTF-8"): String {
        val out = java.io.ByteArrayOutputStream(); var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.write(' '.code); i++ }
                c == '%' -> {
                    require(i + 2 < s.length) { "URLDecoder: Incomplete trailing escape (%) pattern" }
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
                }
                else -> { out.writeBytes(c.toString().encodeToByteArray()); i++ }
            }
        }
        return out.toByteArray().decodeToString()
    }
    fun decode(s: String, charset: com.example.readalong.compat.Charset) = decode(s)
}

/** RFC 3986 URI with the parts and the resolve/normalize behaviour the EPUB and Audible code rely on. */
class URI private constructor(
    val scheme: String?, private val rawAuthorityPart: String?, val rawPath: String?, val rawQuery: String?, val rawFragment: String?,
    private val opaque: String? = null,
) {
    constructor(str: String) : this(parse(str))
    private constructor(p: URI) : this(p.scheme, p.rawAuthorityPart, p.rawPath, p.rawQuery, p.rawFragment, p.opaque)

    val path: String get() = rawPath?.let(::decodePercent) ?: ""
    val query: String? get() = rawQuery?.let(::decodePercent)
    val fragment: String? get() = rawFragment?.let(::decodePercent)
    val rawAuthority: String? get() = rawAuthorityPart
    val authority: String? get() = rawAuthorityPart?.let(::decodePercent)
    val host: String? get() = rawAuthorityPart?.substringAfter('@')?.let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }?.ifEmpty { null }
    val port: Int get() = rawAuthorityPart?.substringAfter('@')?.substringAfterLast(':', "")?.toIntOrNull() ?: -1
    val isAbsolute: Boolean get() = scheme != null
    val isOpaque: Boolean get() = opaque != null

    fun resolve(str: String) = resolve(URI(str))
    fun resolve(ref: URI): URI {
        if (opaque != null || ref.opaque != null) return ref
        if (ref.scheme != null) return ref
        if (ref.rawAuthorityPart != null) return URI(scheme, ref.rawAuthorityPart, ref.rawPath, ref.rawQuery, ref.rawFragment)
        if (ref.rawPath.isNullOrEmpty()) {
            return if (ref.rawQuery == null && ref.rawFragment != null) URI(scheme, rawAuthorityPart, rawPath, rawQuery, ref.rawFragment)
            else URI(scheme, rawAuthorityPart, rawPath, ref.rawQuery ?: rawQuery, ref.rawFragment)
        }
        val path = if (ref.rawPath.startsWith("/")) ref.rawPath else {
            val base = rawPath ?: ""
            val dir = if ('/' in base) base.substringBeforeLast('/') + "/" else if (rawAuthorityPart != null) "/" else ""
            removeDots(dir + ref.rawPath)
        }
        return URI(scheme, rawAuthorityPart, path, ref.rawQuery, ref.rawFragment)
    }
    fun normalize(): URI = if (rawPath == null || opaque != null) this else URI(scheme, rawAuthorityPart, removeDots(rawPath), rawQuery, rawFragment)
    fun toURL() = URL(toString())

    override fun toString() = opaque?.let { "$scheme:$it" + (rawFragment?.let { f -> "#$f" } ?: "") } ?: buildString {
        scheme?.let { append(it).append(':') }
        rawAuthorityPart?.let { append("//").append(it) }
        rawPath?.let { append(it) }
        rawQuery?.let { append('?').append(it) }
        rawFragment?.let { append('#').append(it) }
    }
    override fun equals(other: Any?) = other is URI && other.toString() == toString()
    override fun hashCode() = toString().hashCode()

    companion object {
        fun create(str: String) = URI(str)
        private val legal = Regex("^[^\\s\"<>\\\\^`{|}]*$")

        private fun parse(s: String): URI {
            if (!legal.matches(s)) throw URISyntaxException(s, "Illegal character")
            var rest = s
            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let { fragment = rest.substring(it + 1); rest = rest.substring(0, it) }
            var scheme: String? = null
            Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(rest)?.let { scheme = it.groupValues[1]; rest = rest.substring(it.value.length) }
            if (scheme != null && !rest.startsWith("/")) {
                if (rest.isEmpty()) throw URISyntaxException(s, "Expected scheme-specific part")
                return URI(scheme, null, null, null, fragment, opaque = rest)
            }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
            var authority: String? = null
            if (rest.startsWith("//")) {
                val end = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
                authority = rest.substring(2, end); rest = rest.substring(end)
            }
            return URI(scheme, authority, rest, query, fragment)
        }

        private fun removeDots(path: String): String {
            val input = path.split('/')
            val out = ArrayList<String>()
            val absolute = path.startsWith("/")
            for ((i, seg) in input.withIndex()) {
                when (seg) {
                    "." -> if (i == input.lastIndex) out += ""
                    ".." -> { if (out.isNotEmpty() && out.last() != ".." && out.last().isNotEmpty()) out.removeAt(out.lastIndex) else if (!absolute) out += ".."; if (i == input.lastIndex) out += "" }
                    else -> out += seg
                }
            }
            val joined = out.joinToString("/")
            return if (absolute && !joined.startsWith("/")) "/$joined" else joined
        }

        internal fun decodePercent(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream(); var i = 0
            while (i < s.length) {
                if (s[i] == '%' && i + 2 < s.length) {
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { out.writeBytes(s[i].toString().encodeToByteArray()); i++ }
            }
            return out.toByteArray().decodeToString()
        }
    }
}

class URL(private val spec: String) {
    init { if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(spec)) throw MalformedURLException("no protocol: $spec") }
    private val uri by lazy { URI(spec) }
    val protocol: String get() = spec.substringBefore(':')
    val host: String get() = uri.host ?: ""
    val path: String get() = uri.rawPath ?: ""
    val query: String? get() = uri.rawQuery
    fun openConnection(): URLConnection = HttpURLConnection(this)
    fun toURI() = uri
    fun readText(): String = (openConnection() as HttpURLConnection).let { c -> try { c.inputStream.readAllBytes().decodeToString() } finally { c.disconnect() } }
    fun readBytes(): ByteArray = (openConnection() as HttpURLConnection).let { c -> try { c.inputStream.readAllBytes() } finally { c.disconnect() } }
    override fun toString() = spec
}

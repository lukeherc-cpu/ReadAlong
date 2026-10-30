package com.example.readalong.audible

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A title in the person's Audible library. [parts] lists the part ASINs of an old multi-part book, in order. */
data class AudibleBook(val asin: String, val title: String, val authors: String, val narrators: String,
    val runtimeMinutes: Int, val coverUrl: String?, val purchaseDate: String, val parts: List<String> = emptyList()) {
    val downloadAsins get() = parts.ifEmpty { listOf(asin) }

    fun toJson(): JSONObject = JSONObject().put("asin", asin).put("title", title).put("authors", authors).put("narrators", narrators)
        .put("runtimeMinutes", runtimeMinutes).put("coverUrl", coverUrl ?: JSONObject.NULL).put("purchaseDate", purchaseDate).put("parts", JSONArray(parts))

    companion object {
        /** The library as last synced, so it shows straight away next time. */
        fun listToJson(books: List<AudibleBook>) = JSONArray(books.map { it.toJson() }).toString()
        fun listFromJson(text: String): List<AudibleBook> = JSONArray(text).let { list -> (0 until list.length()).map { index ->
            list.getJSONObject(index).run {
                AudibleBook(getString("asin"), getString("title"), optString("authors"), optString("narrators"), optInt("runtimeMinutes"),
                    if (isNull("coverUrl")) null else optString("coverUrl"), optString("purchaseDate"),
                    optJSONArray("parts")?.let { parts -> (0 until parts.length()).map(parts::getString) }.orEmpty())
            }
        } }
    }
}

/** Where to fetch a book and how it's locked. [key] and [iv] are null for a plain MP3; [activationBytes] is set for AAX. */
class AudibleLicense internal constructor(val url: String, val format: Format, internal val key: ByteArray? = null,
    internal val iv: ByteArray? = null, internal val activationBytes: ByteArray? = null) {
    enum class Format(val extension: String) { AAXC("aaxc"), AAX("aax"), MP3("mp3") }
}

/**
 * Talks to Audible as the registered device: the library list and download licences. Every request
 * is signed with the device key, so no access token has to be kept fresh.
 *
 * Ported from LibriSync (GPL-3.0, itself a port of Libation): native/rust-core/src/api/library.rs and
 * license.rs.
 */
class AudibleClient(account: AudibleAccount, private val onAccountChanged: (AudibleAccount) -> Unit = {}) {
    var account = account
        private set

    /** The whole library, newest purchase first. Podcast shows are left out: only their episodes can be downloaded. */
    fun library(onPage: (Int) -> Unit = {}): List<AudibleBook> {
        val books = mutableListOf<AudibleBook>()
        var page = 1
        while (true) {
            val json = getJson("/1.0/library", linkedMapOf("num_results" to "$PAGE_SIZE", "page" to "$page",
                "response_groups" to "media,relationships,product_desc,contributors,product_attrs,series",
                "sort_by" to "-PurchaseDate", "image_sizes" to "500"))
            val items = json.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) parseBook(items.getJSONObject(index))?.let(books::add)
            onPage(books.size)
            if (items.length() < PAGE_SIZE) return books
            page++
        }
    }

    /**
     * A download licence for one ASIN. Audible counts every licence request, so this never retries
     * on its own. When Audible refuses the current AAXC format it falls back to the older AAX file,
     * which the same purchase can still be downloaded as.
     */
    fun license(asin: String): AudibleLicense {
        val denial = try { return requestLicense(asin, listOf("Adrm", "Mpeg")) }
        catch (error: AudibleException) {
            if (error.message.orEmpty().let { "Sable(AssetInfos)" in it || "asset details" in it || "non_audio" in it })
                return requestLicense(asin, listOf("Mpeg"))
            if (error.status != null && error !is AudibleThrottledException && error !is LicenseDenied) throw error
            error
        }
        return try { aaxLicense(asin) }
        catch (fallback: Exception) { throw (denial as? AudibleThrottledException) ?: AudibleException("${denial.message} The older AAX download didn't work either.") }
    }

    private class LicenseDenied(message: String) : AudibleException(message)

    private fun requestLicense(asin: String, drmTypes: List<String>): AudibleLicense {
        val body = JSONObject()
            .put("supported_media_features", JSONObject().put("drm_types", JSONArray(drmTypes)).put("codecs", JSONArray(listOf("mp4a.40.2")))
                .put("chapter_titles_type", "Tree").put("previews", false).put("catalog_samples", false))
            .put("spatial", false).put("consumption_type", "Download").put("tenant_id", "Audible").put("quality", "High")
            .put("response_groups", "last_position_heard,pdf_url,content_reference,chapter_info")
        val json = signed("POST", account.locale.api, "/1.0/content/$asin/licenserequest", body.toString())
        val license = json.optJSONObject("content_license") ?: json
        when (license.optString("status_code").lowercase()) {
            "granted" -> Unit
            "denied" -> throw denied(license)
            else -> throw AudibleException(license.optString("message").ifEmpty { "Audible didn't grant a download for this book." })
        }
        val url = license.optJSONObject("content_metadata")?.optJSONObject("content_url")?.optString("offline_url")?.ifEmpty { null }
            ?: throw AudibleException("Audible didn't say where to download this book.")
        if (license.optString("drm_type") == "Mpeg") return AudibleLicense(url, AudibleLicense.Format.MP3)
        val voucher = license.optJSONObject("voucher") ?: license.optString("license_response").ifEmpty { null }?.let { decryptVoucher(it, asin) }
            ?: throw AudibleException("Audible's licence has no key for this book.")
        val key = voucherBytes(voucher.getString("key"))
        val iv = voucherBytes(voucher.getString("iv"))
        if (key.size != 16 || iv.size != 16) throw AudibleException("Audible's licence key has an unexpected size.")
        return AudibleLicense(url, AudibleLicense.Format.AAXC, key, iv)
    }

    /** The voucher is AES-128-CBC encrypted with SHA-256(deviceType + serial + customerId + asin) as key and IV. */
    internal fun decryptVoucher(encrypted: String, asin: String): JSONObject {
        val hash = MessageDigest.getInstance("SHA-256").digest("${account.deviceType}${account.deviceSerial}${account.customerId}$asin".toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hash.copyOfRange(0, 16), "AES"), IvParameterSpec(hash.copyOfRange(16, 32)))
        val data = Base64.getDecoder().decode(encrypted)
        val plain = cipher.doFinal(data, 0, data.size / 16 * 16)
        return JSONObject(String(plain.copyOf(plain.indexOf(0).takeIf { it >= 0 } ?: plain.size), Charsets.UTF_8))
    }

    private fun voucherBytes(value: String) =
        if (value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        else Base64.getDecoder().decode(value)

    private fun denied(license: JSONObject): AudibleException {
        val reasons = license.optJSONArray("license_denial_reasons") ?: JSONArray()
        val list = (0 until reasons.length()).map { reasons.getJSONObject(it) }
        val detail = list.joinToString("; ") { "${it.optString("validationType", "Unknown")}: ${it.optString("rejectionReason", "denied")}" }
        return if (list.any { it.optString("rejectionReason").equals("CustomerThrottled", ignoreCase = true) })
            AudibleThrottledException("Audible is limiting downloads for this account right now. Wait an hour, then try again.")
        else LicenseDenied("Audible didn't allow this download${if (detail.isEmpty()) "." else " ($detail)."}")
    }

    /** The legacy AAX route: Audible's Kindle-era download service, unlocked with the account's activation bytes. */
    private fun aaxLicense(asin: String): AudibleLicense {
        val item = getJson("/1.0/library/$asin", mapOf("response_groups" to "product_attrs,relationships")).let { it.optJSONObject("item") ?: it }
        val codecs = item.optJSONArray("available_codecs")?.let { list -> (0 until list.length()).mapNotNull { list.getJSONObject(it).optString("enhanced_codec").ifEmpty { null } } }.orEmpty()
        val codec = AAX_CODECS.firstOrNull { preferred -> codecs.any { it.equals(preferred, ignoreCase = true) } } ?: codecs.firstOrNull()
            ?: throw AudibleException("Audible lists no download formats for this book.")
        val path = "/FionaCDEServiceEngine/FSDownloadContent?type=AUDI&currentTransportMethod=WIFI&key=$asin&codec=$codec"
        val response = AudibleHttp.request("GET", "https://cde-ta-g7g.amazon.com$path", AudibleSigning.headers(account, "GET", path), followRedirects = false)
        val location = response.header("Location")
        if (response.status !in 300..399 || location == null) throw AudibleException("Audible's AAX download didn't start (HTTP ${response.status}).", response.status)
        return AudibleLicense(location.replace("cds.audible.com", "cds.${account.locale.audibleDomain}"), AudibleLicense.Format.AAX, activationBytes = activationBytes())
    }

    /** The four-byte AAX key for this account, fetched once and kept with the account. */
    private fun activationBytes(): ByteArray {
        account.activationBytes?.let { return hex(it) }
        val path = "/license/token?action=register&player_manuf=Audible,iPhone&player_model=iPhone"
        val response = AudibleHttp.request("GET", "https://www.${account.locale.audibleDomain}$path", AudibleSigning.headers(account, "GET", path))
        val blob = response.body
        if (response.status !in 200..299 || blob.size < ACTIVATION_BLOB) throw AudibleException("Audible didn't send the AAX key (HTTP ${response.status}).", response.status)
        val start = blob.size - ACTIVATION_BLOB
        val value = (0 until 4).map { blob[start + 3 - it] }.toByteArray()
        account = account.copy(activationBytes = value.joinToString("") { "%02x".format(it) })
        onAccountChanged(account)
        return value
    }

    private fun getJson(path: String, query: Map<String, String>): JSONObject {
        val pathAndQuery = path + "?" + query.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        return signed("GET", account.locale.api, pathAndQuery, null)
    }

    private fun signed(method: String, host: String, pathAndQuery: String, body: String?): JSONObject {
        val headers = AudibleSigning.headers(account, method, pathAndQuery, body.orEmpty()) + mapOf("Accept" to "application/json") +
            (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
        val response = AudibleHttp.request(method, host + pathAndQuery, headers, body?.toByteArray())
        if (response.status == 401 || response.status == 403) throw AudibleException("Audible no longer accepts this sign-in. Sign out and sign in again.", response.status)
        if (response.status !in 200..299) {
            val message = runCatching { JSONObject(response.text).optString("message") }.getOrNull().orEmpty()
            throw AudibleException("Audible returned HTTP ${response.status}${if (message.isEmpty()) "" else ": $message"}", response.status)
        }
        return JSONObject(response.text)
    }

    companion object {
        private const val PAGE_SIZE = 250
        private const val ACTIVATION_BLOB = 0x238
        private val AAX_CODECS = listOf("LC_128_44100_stereo", "LC_64_44100_stereo", "LC_64_22050_stereo", "LC_32_22050_stereo", "AAX")

        internal fun parseBook(item: JSONObject): AudibleBook? {
            val type = item.optString("content_delivery_type")
            if (type == "PodcastParent" || item.optString("asin").isEmpty()) return null
            fun names(key: String) = item.optJSONArray(key)?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("name")?.ifEmpty { null } } }.orEmpty().joinToString(", ")
            val parts = if (type != "MultiPartBook") emptyList() else item.optJSONArray("relationships")?.let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }
                    .filter { it.optString("relationship_to_product") == "child" && it.optString("relationship_type") == "component" }
                    .sortedBy { it.optString("sort").toIntOrNull() ?: Int.MAX_VALUE }.map { it.getString("asin") }
            }.orEmpty()
            val title = item.optString("title").ifEmpty { item.getString("asin") }
            return AudibleBook(item.getString("asin"), title, names("authors"), names("narrators"), item.optInt("runtime_length_min"),
                item.optJSONObject("product_images")?.optString("500")?.ifEmpty { null }, item.optString("purchase_date"), parts)
        }

        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

package com.example.readalong.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * The phone's cover search: public audiobook artwork from Apple Books, with Open Library as a
 * fallback. No account or API key.
 */
object InternetCovers {
    data class Cover(val title: String, val author: String, val url: String, val source: String)

    private fun fetch(url: String, limit: Int): ByteArray {
        require(URI(url).scheme == "https") { "Cover images must use HTTPS." }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "ReadAlong (audiobook cover search)")
        try {
            require(connection.responseCode in 200..299) { "The cover service is unavailable. Please try again." }
            return connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "This cover image is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    fun parseApple(json: String): List<Cover> {
        val rows = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val url = row.optString("artworkUrl100").replace("100x100bb", "600x600bb")
            if (!url.startsWith("https://")) null else Cover(row.optString("collectionName"), row.optString("artistName"), url, "Apple Books")
        }.distinctBy { it.url }
    }

    suspend fun search(query: String): List<Cover> = withContext(Dispatchers.IO) {
        val term = query.trim()
        require(term.isNotBlank()) { "Enter a book title." }
        val encoded = URLEncoder.encode(term, "UTF-8")
        val apple = runCatching { parseApple(fetch("https://itunes.apple.com/search?term=$encoded&media=audiobook&entity=audiobook&limit=40", 2_000_000).toString(Charsets.UTF_8)) }
        if (apple.getOrNull()?.isNotEmpty() == true) return@withContext apple.getOrThrow()
        val data = JSONObject(fetch("https://openlibrary.org/search.json?title=$encoded&fields=title,author_name,cover_i&limit=30", 2_000_000).toString(Charsets.UTF_8)).getJSONArray("docs")
        (0 until data.length()).mapNotNull { i ->
            val row = data.getJSONObject(i); val id = row.optLong("cover_i", -1)
            if (id <= 0) null else Cover(row.optString("title"), row.optJSONArray("author_name")?.optString(0).orEmpty(), "https://covers.openlibrary.org/b/id/$id-L.jpg", "Open Library")
        }.distinctBy { it.url }
    }

    /** The image's bytes, checked to be a picture. */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val bytes = fetch(url, 12_000_000)
        require(readable(bytes)) { "This cover image couldn't be read." }
        bytes
    }

    /** Whether [bytes] decode as an image (Java's decoders, then Skia's for WebP and the like). */
    fun readable(bytes: ByteArray): Boolean = runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull() != null ||
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes); true }.getOrDefault(false)

    /**
     * Keeps a chosen cover for [book] in ReadAlong's covers folder, under a new name each time so the
     * library's image cache never shows the old one. Returns the file to store as the book's cover.
     */
    fun save(book: LibraryBook, bytes: ByteArray, extension: String): File {
        val ext = extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif") } ?: "jpg"
        AppPaths.covers.listFiles { f -> f.name.startsWith("${book.id}-") }?.forEach { it.delete() }
        val file = File(AppPaths.covers, "${book.id}-${System.currentTimeMillis()}.$ext")
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return file
    }
}

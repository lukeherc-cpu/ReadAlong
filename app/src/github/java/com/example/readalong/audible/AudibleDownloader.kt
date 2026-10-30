package com.example.readalong.audible

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one Audible title and leaves a plain, playable file behind: "Title.m4b" (or ".mp3" for
 * podcast episodes). A multi-part book becomes a folder of parts. Downloads resume where they
 * stopped, and nothing appears under the final name until it's fully decrypted.
 */
object AudibleDownloader {
    enum class Stage { LICENSING, DOWNLOADING, UNLOCKING }

    /** [onProgress] gets the stage and the fraction of the whole book done; it may throw to cancel. Returns the playable files in order. */
    fun download(client: AudibleClient, book: AudibleBook, directory: File, onProgress: (Stage, Float) -> Unit = { _, _ -> }): List<File> {
        val asins = book.downloadAsins
        val name = safeName(book.title)
        val home = if (asins.size > 1) File(directory, name) else directory
        home.mkdirs()
        return asins.mapIndexed { index, asin ->
            fun report(stage: Stage, fraction: Float) = onProgress(stage, (index + fraction) / asins.size)
            existing(home, partName(name, index, asins.size))?.let { return@mapIndexed it }
            report(Stage.LICENSING, 0f)
            val license = client.license(asin)
            val partial = File(home, ".${partName(name, index, asins.size)}.${license.format.extension}.part")
            fetch(license.url, partial) { report(Stage.DOWNLOADING, it * .9f) }
            report(Stage.UNLOCKING, .9f)
            AudibleDecrypter.decrypt(partial, license) { report(Stage.UNLOCKING, .9f + it * .1f) }
            val target = File(home, "${partName(name, index, asins.size)}.${if (license.format == AudibleLicense.Format.MP3) "mp3" else "m4b"}")
            if (!partial.renameTo(target)) throw IOException("Couldn't save ${target.name}.")
            target
        }
    }

    private fun existing(home: File, base: String) = listOf("m4b", "mp3").map { File(home, "$base.$it") }.firstOrNull { it.isFile && it.length() > 0 }
    private fun partName(name: String, index: Int, count: Int) = if (count == 1) name else "$name - Part ${"%02d".format(index + 1)}"
    internal fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120).ifEmpty { "Audiobook" }

    /** Resumable download: a partial file continues with an HTTP range request. Audible's content URLs are pre-signed. */
    private fun fetch(url: String, file: File, onProgress: (Float) -> Unit) {
        var attempt = 0
        while (true) {
            try { return fetchOnce(url, file, onProgress) }
            catch (error: IOException) {
                if (error is AudibleException || ++attempt >= 4) throw error
                Thread.sleep(2_000L shl attempt)
            }
        }
    }

    private fun fetchOnce(url: String, file: File, onProgress: (Float) -> Unit) {
        val have = if (file.exists()) file.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", AUDIBLE_USER_AGENT)
            if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
            val status = connection.responseCode
            if (status == 416 && have > 0) return // Already complete.
            if (status !in 200..299) throw AudibleException("The download failed (HTTP $status).", status)
            val resumed = status == 206
            val start = if (resumed) have else 0L
            val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { it + start }
            RandomAccessFile(file, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(start)
                var written = start
                val buffer = ByteArray(256 * 1024)
                var lastReport = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        written += count
                        if (total != null && written - lastReport >= 1_000_000) { lastReport = written; onProgress(written.toFloat() / total) }
                    }
                }
                if (total != null && written < total) throw IOException("The download stopped early.")
            }
            onProgress(1f)
        } finally { connection.disconnect() }
    }
}

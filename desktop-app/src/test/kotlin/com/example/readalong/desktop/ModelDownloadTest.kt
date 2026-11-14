package com.example.readalong.desktop

import com.example.readalong.desktop.transcription.ModelStore
import com.example.readalong.desktop.transcription.SpeechModels
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

/** Model downloads: the redirect's checksum is checked, and a download that was cut off carries on where it stopped. */
class ModelDownloadTest {
    private val model = SpeechModels.all.first()
    // A "model": the ggml magic and some bytes that don't repeat.
    private val bytes = byteArrayOf(0x6c, 0x6d, 0x67, 0x67) + ByteArray(3_000_000) { (it * 31 + it / 7).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val ranges = mutableListOf<String?>()

    /** Serves /model as a redirect (with [etag]) to /file, which honours Range and stops after [cutAt] bytes the first time. */
    private fun server(etag: String, cutAt: Int? = null): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        var cut = cutAt
        createContext("/model") { exchange ->
            exchange.responseHeaders.add("Location", "/file"); exchange.responseHeaders.add("X-Linked-Etag", "\"$etag\"")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges += range }
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (bytes.size - from).toLong())
            val stop = cut?.also { cut = null }
            runCatching { exchange.responseBody.use { it.write(bytes, from, (stop ?: bytes.size) - from); if (stop != null) it.flush() } }
            exchange.close()
        }
        start()
    }

    @Test fun downloadsThroughTheRedirectAndChecksTheChecksum() = runBlocking {
        val folder = Files.createTempDirectory("models").toFile()
        val http = server(sha)
        try {
            val file = ModelStore(folder).download(model, "http://127.0.0.1:${http.address.port}/model")
            assertContentEquals(bytes, file.readBytes())
        } finally { http.stop(0); folder.deleteRecursively() }
    }

    @Test fun aDamagedDownloadIsRefused() = runBlocking {
        val folder = Files.createTempDirectory("models").toFile()
        val http = server("0".repeat(64))
        try {
            val store = ModelStore(folder)
            assertFails { store.download(model, "http://127.0.0.1:${http.address.port}/model") }
            assertFalse(store.isInstalled(model))
            assertFalse(File(folder, model.fileName + ".part").exists())
        } finally { http.stop(0); folder.deleteRecursively() }
    }

    @Test fun aCutOffDownloadCarriesOn() = runBlocking {
        val folder = Files.createTempDirectory("models").toFile()
        val http = server(sha, cutAt = 1_000_000)
        try {
            val store = ModelStore(folder, stallMs = 1_000)
            val address = "http://127.0.0.1:${http.address.port}/model"
            assertFails { store.download(model, address) }
            val kept = File(folder, model.fileName + ".part").length()
            assertTrue(kept in 1 until bytes.size, "the part is kept: $kept")
            assertContentEquals(bytes, store.download(model, address).readBytes())
            assertEquals("bytes=$kept-", synchronized(ranges) { ranges.last() })
        } finally { http.stop(0); folder.deleteRecursively() }
    }

    @Test fun readsTheChecksumHeader() {
        assertEquals(sha, ModelStore.sha256Header("\"$sha\""))
        assertNull(ModelStore.sha256Header("\"abc\""))
        assertNull(ModelStore.sha256Header(null))
    }
}

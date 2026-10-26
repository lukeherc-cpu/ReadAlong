package com.example.readalong.data.processing

import com.example.readalong.data.local.*
import com.example.readalong.data.reading.DemoTranscript
import com.example.readalong.domain.transcription.TranscriptionInput

object TranscriptionPlan {
    const val CHUNK_MS = 30_000L
    suspend fun inputs(database: ReadAlongDatabase, bookId: String): List<TranscriptionInput> {
        val book = requireNotNull(database.bookDao().findBook(bookId))
        if (book.isDemo) return DemoTranscript.segments(bookId).groupBy { it.chapterId }.values.map { segments ->
            TranscriptionInput(bookId, segments.first().chapterId, null, segments.first().startMs, segments.last().endMs, true)
        }
        val files = database.mediaDao().audioFiles(bookId)
        check(files.isNotEmpty()) { "No audio is available for this book. Restore its file access." }
        val chapters = database.mediaDao().chapters(bookId)
        val inputs = mutableListOf<TranscriptionInput>()
        var bookOffset = 0L
        for (file in files) {
            val duration = requireNotNull(file.durationMs) { "ReadAlong couldn't read this book's length. Play it for a moment, then try again." }
            check(duration > 0)
            var position = 0L
            while (position < duration) {
                val chapter = chapters.filter { it.fileId == file.id && it.startMs <= position }.lastOrNull()
                val nextChapter = chapters.firstOrNull { it.fileId == file.id && it.startMs > position }?.startMs ?: duration
                val end = minOf(position + CHUNK_MS, duration, nextChapter)
                inputs += TranscriptionInput(bookId, chapter?.id ?: file.id, file.uri, bookOffset + position, bookOffset + end, false, position)
                position = end
            }
            bookOffset += duration
        }
        return inputs
    }
    suspend fun fingerprint(database: ReadAlongDatabase, bookId: String): String {
        val identity = org.json.JSONArray().apply {
            database.mediaDao().audioFiles(bookId).forEach { put(org.json.JSONArray(listOf(it.id, it.uri, it.ordinal, it.durationMs))) }
            database.mediaDao().chapters(bookId).forEach { put(org.json.JSONArray(listOf(it.id, it.fileId, it.ordinal, it.startMs, it.endMs))) }
        }.toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    /**
     * Compact v2 plan. The v1 form repeated the book ID and full content URI in every 30-second
     * unit (about 2.5 MB for a 55-hour book), and the row is rewritten on every status update
     * and reread by every observer. v2 stores each string once and the units as a flat number
     * array (about a tenth of the size). [decode] still reads v1 so saved checkpoints resume.
     */
    fun encode(inputs: List<TranscriptionInput>): String {
        val books = LinkedHashMap<String, Int>(); val chapters = LinkedHashMap<String, Int>(); val uris = LinkedHashMap<String, Int>()
        val units = org.json.JSONArray()
        inputs.forEach { input ->
            units.put(books.getOrPut(input.bookId) { books.size })
            units.put(chapters.getOrPut(input.chapterId) { chapters.size })
            units.put(input.audioUri?.let { uris.getOrPut(it) { uris.size } } ?: -1)
            units.put(input.startMs); units.put(input.endMs); units.put(if (input.isDemo) 1 else 0); units.put(input.fileStartMs)
        }
        return org.json.JSONObject().put("v", 2).put("books", org.json.JSONArray(books.keys.toList()))
            .put("chapters", org.json.JSONArray(chapters.keys.toList())).put("uris", org.json.JSONArray(uris.keys.toList()))
            .put("units", units).toString()
    }
    fun decode(json: String): List<TranscriptionInput> {
        if (json.trimStart().startsWith("{")) {
            val root = org.json.JSONObject(json)
            require(root.optInt("v") == 2) { "Unsupported transcription plan." }
            fun strings(name: String) = root.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
            val books = strings("books"); val chapters = strings("chapters"); val uris = strings("uris")
            val units = root.getJSONArray("units")
            require(units.length() % 7 == 0) { "Damaged transcription plan." }
            return List(units.length() / 7) { i -> val o = i * 7
                TranscriptionInput(books[units.getInt(o)], chapters[units.getInt(o + 1)], units.getInt(o + 2).let { if (it < 0) null else uris[it] },
                    units.getLong(o + 3), units.getLong(o + 4), units.getInt(o + 5) == 1, units.getLong(o + 6))
            }
        }
        return org.json.JSONArray(json).let { array ->
            (0 until array.length()).map { i -> val v = array.getJSONObject(i)
                TranscriptionInput(v.getString("book"), v.getString("chapter"), if (v.has("uri") && !v.isNull("uri")) v.getString("uri") else null,
                    v.getLong("start"), v.getLong("end"), v.getBoolean("demo"), v.getLong("fileStart")) }
        }
    }
}

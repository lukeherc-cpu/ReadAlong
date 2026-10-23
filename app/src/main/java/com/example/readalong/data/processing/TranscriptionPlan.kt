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
            val duration = requireNotNull(file.durationMs) { "Audio duration is unknown. Play or rescan the file before preparing its readable version." }
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
    fun encode(inputs: List<TranscriptionInput>): String = org.json.JSONArray().apply { inputs.forEach { input ->
        put(org.json.JSONObject().put("book", input.bookId).put("chapter", input.chapterId).put("uri", input.audioUri)
            .put("start", input.startMs).put("end", input.endMs).put("demo", input.isDemo).put("fileStart", input.fileStartMs))
    } }.toString()
    fun decode(json: String): List<TranscriptionInput> = org.json.JSONArray(json).let { array ->
        (0 until array.length()).map { i -> val v = array.getJSONObject(i)
            TranscriptionInput(v.getString("book"), v.getString("chapter"), if (v.has("uri")) v.getString("uri") else null,
                v.getLong("start"), v.getLong("end"), v.getBoolean("demo"), v.getLong("fileStart")) }
    }
}

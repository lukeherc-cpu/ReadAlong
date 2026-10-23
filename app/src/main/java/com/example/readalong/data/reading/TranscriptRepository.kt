package com.example.readalong.data.reading

import androidx.room.withTransaction
import com.example.readalong.data.local.*
import com.example.readalong.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TranscriptRepository(private val database: ReadAlongDatabase) {
    private val dao = database.readingDao()
    fun version(bookId: String): Flow<ReadableVersionEntity?> = flow {
        prepareDemo(bookId)
        emitAll(dao.version(bookId))
    }
    fun window(bookId: String, positionMs: Long) = dao.observeWindow(bookId, positionMs)
        .map { rows -> rows.map { it.toSegment() } }
    fun bookmarks(bookId: String) = dao.bookmarks(bookId)
    suspend fun addBookmark(bookId: String, positionMs: Long, label: String? = null) {
        dao.putBookmark(BookmarkEntity(UUID.randomUUID().toString(), bookId, positionMs,
            dao.at(bookId, positionMs)?.id, null, label?.trim()?.take(120), System.currentTimeMillis()))
    }
    suspend fun removeBookmark(id: String) = dao.deleteBookmark(id)
    suspend fun prepareDemo(bookId: String) {
        if (database.bookDao().findBook(bookId)?.isDemo != true) return
        database.withTransaction {
            if (dao.findVersion(bookId) == null) {
                dao.putSegments(DemoTranscript.segments(bookId).map { it.toEntity() })
                dao.putVersion(ReadableVersionEntity(bookId, "DEMO_READY", "Original sample excerpt · synthetic timestamps · no narration", 3, 3, null, System.currentTimeMillis()))
            }
        }
    }
}

fun TranscriptEntity.toSegment() = TranscriptSegment(id, bookId, chapterId, startMs, endMs, text, confidence,
    wordsJson?.let { json -> JSONArray(json).let { array -> (0 until array.length()).map { index ->
        val word = array.getJSONObject(index)
        TranscriptWord(word.getString("word"), word.getLong("startMs"), word.getLong("endMs"),
            if (word.has("confidence") && !word.isNull("confidence")) word.getDouble("confidence").toFloat() else null)
    } } } ?: emptyList(), anchorsJson?.let { json -> JSONArray(json).let { array ->
        (0 until array.length()).map { i -> val a = array.getJSONObject(i)
            TranscriptAnchor(a.getString("id"), a.getLong("startMs"), a.getLong("endMs"), a.getInt("start"), a.getInt("end"),
                if (a.has("sourceStart")) a.getInt("sourceStart") else null, if (a.has("sourceEnd")) a.getInt("sourceEnd") else null, a.optBoolean("estimated", false)) }
    } } ?: emptyList())

fun TranscriptSegment.toEntity() = TranscriptEntity(id, bookId, chapterId, startMs, endMs, text, confidence,
    words.takeIf { it.isNotEmpty() }?.let { list -> JSONArray().apply { list.forEach { word -> put(JSONObject().apply {
        put("word", word.word); put("startMs", word.startMs); put("endMs", word.endMs); put("confidence", word.confidence)
    }) } }.toString() })

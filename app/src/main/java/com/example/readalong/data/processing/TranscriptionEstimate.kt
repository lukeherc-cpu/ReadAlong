package com.example.readalong.data.processing

import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.local.ProcessingJobEntity
import org.json.JSONObject
import kotlin.math.ceil

/** Successful work time only: pauses, thermal waits and scheduler delays never inflate the rate. */
object TranscriptionEstimate {
    private fun key(book: String) = "transcription_estimate:$book"
    suspend fun record(database: ReadAlongDatabase, job: ProcessingJobEntity, audioMs: Long, elapsedMs: Long) {
        if (audioMs <= 0 || elapsedMs <= 0) return
        val old = database.bookDao().metadata(key(job.bookId))?.let { runCatching { JSONObject(it) }.getOrNull() }
        val same = old?.optString("fingerprint") == job.sourceFingerprint && old?.optString("engine") == job.engineId
        val rate = elapsedMs.toDouble() / audioMs
        val average = if (same) old!!.optDouble("rate", rate) * .75 + rate * .25 else rate
        val count = if (same) old!!.optInt("count") + 1 else 1
        database.bookDao().putMetadata(LibraryMetadataEntity(key(job.bookId), JSONObject()
            .put("fingerprint", job.sourceFingerprint).put("engine", job.engineId).put("rate", average).put("count", count).toString()))
    }
    suspend fun label(database: ReadAlongDatabase, job: ProcessingJobEntity?): String {
        if (job == null) return "Estimating time remaining…"
        if (job.state == "READY") return "Transcription complete"
        if (job.state == "WAITING_QUEUE") return "Waiting in queue"
        val value = database.bookDao().metadata(key(job.bookId))?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (value == null || value.optInt("count") < 2 || value.optString("fingerprint") != job.sourceFingerprint || value.optString("engine") != job.engineId) return "Estimating time remaining…"
        val units = cachedPlan(job.planJson)
        val remaining = units.drop(job.completedUnits)
        if (remaining.isEmpty()) return "Finishing readable version…"
        val ms = remaining.sumOf { it.endMs - it.startMs } * value.optDouble("rate", 1.0) + remaining.size * 3000.0
        return format(ms.toLong())
    }
    internal fun format(ms: Long): String {
        val minutes = ceil(ms.coerceAtLeast(0) / 60000.0).toLong().coerceAtLeast(1)
        return "About " + if (minutes < 60) "$minutes min remaining" else "${minutes / 60} h ${minutes % 60} min remaining"
    }
}

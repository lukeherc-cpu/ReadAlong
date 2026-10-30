package com.example.readalong.data.processing

import com.example.readalong.data.local.LibraryMetadataEntity
import com.example.readalong.data.local.ReadAlongDatabase
import com.example.readalong.data.local.ProcessingJobEntity
import org.json.JSONObject
import kotlin.math.ceil

/** Successful work time only: pauses, thermal waits and scheduler delays never inflate the rate. */
object TranscriptionEstimate {
    private fun key(book: String) = "transcription_estimate:$book"
    /** This phone's average speed across books (wall time per audio ms), for estimates before a book starts. */
    private const val DEVICE_KEY = "transcription_rate:device"
    /** Before anything is measured: roughly a fifth of real time on a recent phone. */
    private const val DEFAULT_RATE = 0.2
    suspend fun record(database: ReadAlongDatabase, job: ProcessingJobEntity, audioMs: Long, elapsedMs: Long) {
        if (audioMs <= 0 || elapsedMs <= 0) return
        val old = database.bookDao().metadata(key(job.bookId))?.let { runCatching { JSONObject(it) }.getOrNull() }
        val same = old?.optString("fingerprint") == job.sourceFingerprint && old?.optString("engine") == job.engineId
        val rate = elapsedMs.toDouble() / audioMs
        val average = if (same) old!!.optDouble("rate", rate) * .75 + rate * .25 else rate
        val count = if (same) old!!.optInt("count") + 1 else 1
        database.bookDao().putMetadata(LibraryMetadataEntity(key(job.bookId), JSONObject()
            .put("fingerprint", job.sourceFingerprint).put("engine", job.engineId).put("rate", average).put("count", count).toString()))
        val device = database.bookDao().metadata(DEVICE_KEY)?.toDoubleOrNull()
        database.bookDao().putMetadata(LibraryMetadataEntity(DEVICE_KEY, (if (device == null) rate else device * .9 + rate * .1).toString()))
    }
    /** "About 6 hours" before a ReadAlong starts, from this phone's measured speed. */
    suspend fun beforeStart(database: ReadAlongDatabase, durationMs: Long?): String? {
        val audio = durationMs?.takeIf { it > 0 } ?: return null
        val rate = database.bookDao().metadata(DEVICE_KEY)?.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_RATE
        return "About ${duration((audio * rate).toLong())}"
    }
    internal fun duration(ms: Long): String {
        val minutes = ceil(ms.coerceAtLeast(0) / 60000.0).toLong().coerceAtLeast(1)
        if (minutes < 60) return if (minutes == 1L) "1 minute" else "$minutes minutes"
        val hours = Math.round(minutes / 60.0)
        return if (hours == 1L) "1 hour" else "$hours hours"
    }
    suspend fun label(database: ReadAlongDatabase, job: ProcessingJobEntity?): String {
        if (job == null) return "Estimating time remaining…"
        if (job.state == "READY") return "ReadAlong ready"
        if (job.state == "WAITING_QUEUE") return "Waiting in queue"
        val value = database.bookDao().metadata(key(job.bookId))?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (value == null || value.optInt("count") < 2 || value.optString("fingerprint") != job.sourceFingerprint || value.optString("engine") != job.engineId) return "Estimating time remaining…"
        val units = cachedPlan(job.planJson)
        val remaining = units.drop(job.completedUnits)
        if (remaining.isEmpty()) return "Finishing your ReadAlong…"
        // The rate already covers each unit's whole cycle (see ProcessingRunner). The small allowance is for
        // the restart between batches of units; the old 3 s per unit doubled estimates on a fast phone.
        val ms = remaining.sumOf { it.endMs - it.startMs } * value.optDouble("rate", 1.0) + remaining.size * 300.0
        return format(ms.toLong())
    }
    internal fun format(ms: Long): String {
        val minutes = ceil(ms.coerceAtLeast(0) / 60000.0).toLong().coerceAtLeast(1)
        return "About " + if (minutes < 60) "$minutes min remaining" else "${minutes / 60} h ${minutes % 60} min remaining"
    }
}

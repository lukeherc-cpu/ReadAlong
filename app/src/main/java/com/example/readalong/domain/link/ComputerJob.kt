package com.example.readalong.domain.link

import org.json.JSONObject

/**
 * A ReadAlong the computer is making (or has made, or gave up on), as it tells a linked phone in answer to
 * "jobs". The book is named by the shared folder ([folder], the id both sides use) and the path of its first
 * audio file inside it ([path]), which is the same on both devices once the folder has synced.
 */
data class ComputerJob(val folder: String, val path: String, val state: String, val done: Int = 0, val total: Int = 0,
    val model: String? = null, val error: String? = null) {
    val percent: Int get() = if (state == DONE) 100 else if (total <= 0) 0 else (done * 100L / total).toInt().coerceIn(0, 99)
    /** The computer has this book in hand: the phone needn't transcribe it too. */
    val pending: Boolean get() = state == QUEUED || state == WORKING

    fun toJson(): JSONObject = JSONObject().put("folder", folder).put("path", path).put("state", state).put("done", done).put("total", total)
        .apply { model?.let { put("model", it) }; error?.let { put("error", it) } }

    companion object {
        const val QUEUED = "queued"
        const val WORKING = "working"
        const val DONE = "done"
        const val STOPPED = "stopped"
        /** The answer to "transcribe" for a book whose audio hasn't reached the computer yet. */
        const val MISSING = "missing"

        fun fromJson(json: JSONObject) = ComputerJob(json.getString("folder"), json.getString("path"), json.optString("state"), json.optInt("done"), json.optInt("total"),
            json.optString("model").ifBlank { null }, json.optString("error").ifBlank { null })
    }
}

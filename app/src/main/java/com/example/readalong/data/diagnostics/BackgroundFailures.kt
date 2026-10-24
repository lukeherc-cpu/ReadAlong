package com.example.readalong.data.diagnostics

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File

/**
 * Last-resort handler for app-owned background coroutine scopes (application, services,
 * playback). Without it, one uncaught database, storage or notification exception in any
 * background launch terminated the whole process, including playback and transcription.
 * Ordinary exceptions are logged and recorded for Settings → Copy crash details; JVM
 * errors (out of memory, linkage) still go to the default crash handler.
 */
object BackgroundFailures {
    @Volatile private var appContext: Context? = null
    fun install(context: Context) { appContext = context.applicationContext }

    fun handler(owner: String) = CoroutineExceptionHandler { _, failure ->
        if (failure !is Exception) {
            val thread = Thread.currentThread()
            (thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler())?.uncaughtException(thread, failure)
            return@CoroutineExceptionHandler
        }
        android.util.Log.e("ReadAlong", "$owner background task failed; continuing.", failure)
        record(owner, failure)
    }

    private fun record(owner: String, failure: Throwable) {
        val context = appContext ?: return
        try {
            val report = buildString {
                append("${System.currentTimeMillis()} · $owner · ${failure.javaClass.name}\n")
                failure.stackTrace.take(16).forEach { append(it.toString()).append('\n') }
            }
            val file = File(context.filesDir, FILE)
            // Bounded: keep the most recent reports only. No book text is ever included.
            val previous = runCatching { file.readText() }.getOrDefault("").takeLast(6_000)
            file.writeText((previous + report).takeLast(8_000))
        } catch (_: Throwable) { }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.length() in 1..16_000 }?.readText()
    }.getOrNull()

    private const val FILE = "background-errors.txt"
}

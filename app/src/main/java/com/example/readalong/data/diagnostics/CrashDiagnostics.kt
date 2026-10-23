package com.example.readalong.data.diagnostics

import android.content.Context
import java.io.File

/** Save bounded stack locations locally, then let Android handle the crash normally. No book text. */
object CrashDiagnostics {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val report = buildString {
                    append("${System.currentTimeMillis()} · ${android.os.Build.MODEL} · API ${android.os.Build.VERSION.SDK_INT}\n")
                    generateSequence(failure) { it.cause }.take(8).forEach { cause ->
                        append(cause.javaClass.name).append('\n')
                        cause.stackTrace.take(32).forEach { append(it.toString()).append('\n') }
                    }
                }.take(16000)
                File(context.filesDir, "last-crash.txt").writeText(report)
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, failure)
        }
    }
    fun read(context: Context): String = runCatching {
        File(context.filesDir, "last-crash.txt").takeIf { it.length() in 1..16000 }?.readText() ?: "No captured crash."
    }.getOrDefault("Crash report unavailable.")
}

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
        val crash = File(context.filesDir, "last-crash.txt").takeIf { it.length() in 1..16000 }?.readText() ?: "No captured crash."
        val background = BackgroundFailures.read(context)
        if (background == null) crash else "$crash\nRecovered background errors:\n$background"
    }.getOrDefault("Crash report unavailable.")
    /** The last saved transcription error of each book, which isn't a crash and so isn't in last-crash.txt. */
    suspend fun transcriptionFailures(database: com.example.readalong.data.local.ReadAlongDatabase): String = runCatching {
        val failures = database.bookDao().metadataWithPrefix("preparation_failure:")
        if (failures.isEmpty()) "" else failures.joinToString("\n\n", "\nTranscription errors:\n") { entry ->
            val bookId = entry.key.removePrefix("preparation_failure:")
            val title = database.bookDao().findBook(bookId)?.title ?: bookId.take(12)
            "$title\n${entry.value}"
        }.take(8000)
    }.getOrDefault("")
}

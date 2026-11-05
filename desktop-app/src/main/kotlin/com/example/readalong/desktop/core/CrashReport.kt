package com.example.readalong.desktop.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Settings → Copy crash details: everything needed to tell why transcription or the app stopped, as plain text to
 * paste into a message. Book titles appear only in the per-book errors and the transcriber's crash log, never the
 * books' text.
 */
object CrashReport {
    val appLog get() = File(AppPaths.data, "app-crash.log")
    val transcriberLog get() = File(AppPaths.data, "transcriber-crash.log")

    /** Keeps uncaught exceptions from the app's own threads in [appLog] (the last few only). */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            record("uncaught on ${thread.name}", failure)
            previous?.uncaughtException(thread, failure)
        }
    }

    fun record(where: String, failure: Throwable) = runCatching {
        val trace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
        val entry = "== ${java.time.LocalDateTime.now()} · $where · ${version()}\n$trace\n"
        val previous = if (appLog.isFile) appLog.readText().takeLast(KEEP) else ""
        appLog.writeAtomically(previous + entry)
    }

    /**
     * The report. [devices] are the transcriber's devices as "type label memory" lines; [settings] the transcription
     * settings that matter; [bookErrors] each stopped book's title and last error.
     */
    fun build(devices: List<String>, settings: List<String>, bookErrors: List<Pair<String, String>>): String = buildString {
        appendLine("ReadAlong for ${AppPaths.platformName} ${version()}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})" +
            (System.getenv("FLATPAK_ID")?.let { " · Flatpak" } ?: ""))
        appendLine("Java: ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}")
        appendLine("Processors: ${Runtime.getRuntime().availableProcessors()}")
        devices.forEach { appendLine("Device: $it") }
        settings.forEach { appendLine(it) }
        appendLine()
        appendLine("## Stopped books")
        if (bookErrors.isEmpty()) appendLine("None.") else bookErrors.forEach { (title, error) -> appendLine("$title: $error") }
        section("Transcriber crashes (${transcriberLog.name})", transcriberLog)
        section("App crashes (${appLog.name})", appLog)
    }

    private fun StringBuilder.section(title: String, file: File) {
        appendLine()
        appendLine("## $title")
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        if (text.isNullOrBlank()) appendLine("None.") else appendLine(text.takeLast(REPORT_TAIL).trimEnd())
    }

    private fun version() = System.getProperty("readalong.version") ?: "dev"

    private const val KEEP = 60_000
    /** Per log in the report: the latest crashes, short enough to paste into a chat. */
    private const val REPORT_TAIL = 20_000
}

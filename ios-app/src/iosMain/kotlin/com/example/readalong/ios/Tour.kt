package com.example.readalong.ios

import com.example.readalong.compat.*

import com.example.readalong.desktop.ui.AppState
import com.example.readalong.desktop.ui.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSProcessInfo

/**
 * CI's walk through the app on the simulator (READALONG_TOUR=1): the library with a sample book, playback,
 * making a ReadAlong with whisper.cpp, the reader and its search, and Settings. Each step prints
 * "TOUR-SHOT <name>" when the screen is ready, and the workflow takes a screenshot then.
 */
object Tour {
    val requested: Boolean get() = NSProcessInfo.processInfo.environment["READALONG_TOUR"] != null

    // The app's console output reaches the workflow late, so the steps also go to Documents/tour.log, which it polls.
    private val log = java.io.File(platform.Foundation.NSHomeDirectory() + "/Documents/tour.log")
    private fun mark(line: String) { println(line); runCatching { log.appendText(line + "\n") } }
    private fun say(text: String) = mark("TOUR $text")
    private suspend fun shot(name: String, settle: Long = 4_000) { delay(settle); mark("TOUR-SHOT $name"); delay(3_000) }

    fun run(app: AppState) = app.scope.launch {
        say("started")
        val book = withTimeoutOrNull(60_000) {
            while (app.library.books.value.isEmpty()) { runCatching { app.importer.rescan() }; delay(2_000) }
            app.library.books.value.first()
        } ?: run { say("FAILED no book in the library"); mark("TOUR-DONE"); return@launch }
        say("book ${book.title} (${book.durationMs} ms, ${book.chapters.size} chapters)")
        shot("1-library", 6_000)

        app.open(Screen.Player(book.id))
        app.play(book.id)
        shot("2-player")
        val position = app.player.state.value
        say("playing=${position.playing} position=${position.positionMs}")

        app.player.pause()
        app.transcription.start(book.id)
        app.open(Screen.Queue)
        shot("3-queue", 8_000)
        val done = withTimeoutOrNull(300_000) {
            while (app.transcripts.info(book.id)?.complete != true) delay(2_000)
            true
        } == true
        say(if (done) "transcript complete" else "FAILED transcript not complete after 5 minutes")
        say("transcript ${app.transcripts.info(book.id)}")
        shot("4-queue-done", 2_000)

        app.open(Screen.Reader(book.id))
        app.play(book.id)
        shot("5-reader", 6_000)
        app.player.pause()
        app.searchRequests++
        shot("6-reader-search")

        app.home(); app.open(Screen.Settings)
        shot("7-settings")
        app.open(Screen.PhoneSettings)
        shot("8-link-computer")
        app.home(); app.open(Screen.Audible)
        shot("9-audible")
        app.home()
        shot("10-library-after", 3_000)
        mark("TOUR-DONE")
    }
}

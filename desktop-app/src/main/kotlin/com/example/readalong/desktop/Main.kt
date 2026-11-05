package com.example.readalong.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.readalong.desktop.transcription.Worker
import com.example.readalong.desktop.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main(args: Array<String>) {
    when {
        "--transcriber" in args -> { Worker.run(); return }
        "--self-test" in args -> { kotlin.system.exitProcess(SelfTest.run(args)) }
        "--screenshots" in args -> { Screenshots.run(args[args.indexOf("--screenshots") + 1]); kotlin.system.exitProcess(0) }
    }
    com.example.readalong.desktop.core.CrashReport.install()
    System.setProperty("skiko.vsync.enabled", "true")
    // macOS: a dark title bar to match the app, and "ReadAlong" in the menu bar rather than the launcher's name.
    System.setProperty("apple.awt.application.appearance", if (com.example.readalong.desktop.core.SettingsStore.savedLight()) "NSAppearanceNameAqua" else "NSAppearanceNameDarkAqua")
    System.setProperty("apple.awt.application.name", "ReadAlong")
    // Before anything loads: keep a copy of the library and settings when a different version starts.
    System.getProperty("readalong.version")?.let { com.example.readalong.desktop.core.VersionUpgrade.run(it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val app = AppState(scope)
    application(exitProcessOnExit = true) {
        val state = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        Window(onCloseRequest = { app.shutdown(); scope.cancel(); exitApplication() }, state = state, title = "ReadAlong",
            icon = androidx.compose.ui.res.painterResource("icon/readalong.png"),
            onKeyEvent = { handleKey(app, it) }) {
            window.minimumSize = java.awt.Dimension(720, 560)
            val settings by app.settingsStore.settings.collectAsState()
            LaunchedEffect(settings.light, settings.accent) { styleTitleBar(window, windowBackground(settings.accent, settings.light), dark = !settings.light) }
            ReadAlongApp(app)
        }
    }
}

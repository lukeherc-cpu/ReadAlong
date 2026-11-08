package com.example.readalong.ios

import com.example.readalong.compat.*
import androidx.compose.ui.window.ComposeUIViewController
import com.example.readalong.desktop.core.AppPaths
import com.example.readalong.desktop.platform.FolderAccess
import com.example.readalong.desktop.ui.AppState
import com.example.readalong.desktop.ui.ReadAlongApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

private var app: AppState? = null

/**
 * The app's only UIKit entry point; iOSApp.swift installs the whisper.cpp engine (WhisperNative.engine) and then
 * shows this controller full screen. The library starts as the app's own Audiobooks folder, which the Files app
 * shows under On My iPhone › ReadAlong.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val state = app ?: run {
        com.example.readalong.compat.System.setProperty("readalong.version", runCatching { AppPaths.resource("readalong-version.txt").readText().trim() }.getOrDefault("dev"))
        // Folders chosen outside the app keep working across launches through their security-scoped bookmarks.
        FolderAccess.restoreAll()
        AppState(CoroutineScope(SupervisorJob() + Dispatchers.Main)).also { created ->
            if (created.library.folders.value.isEmpty()) {
                created.library.setFolders(listOf(AppPaths.audiobooks.absolutePath))
                created.scope.launch(com.example.readalong.compat.IoDispatcher) { runCatching { created.importer.rescan() } }
            }
            app = created
            if (Tour.requested) Tour.run(created)
        }
    }
    return ComposeUIViewController { ReadAlongApp(state) }
}

/** Called by iOSApp.swift when the app goes to the background: saves the library and the listening position. */
@Suppress("unused")
fun appWillResignActive() { app?.let { runCatching { it.library.flush(); it.syncProgress() } } }

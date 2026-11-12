package com.example.readalong

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.readalong.ui.ReadAlongApp
import com.example.readalong.ui.library.LibraryViewModel
import com.example.readalong.ui.theme.ReadAlongTheme

open class MainActivity : ComponentActivity() {
    private val bookRepository get() = (application as ReadAlongApplication).bookRepository
    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.factory(bookRepository)
    }

    private val importViewModel: com.example.readalong.ui.importing.ImportViewModel by viewModels {
        com.example.readalong.ui.importing.ImportViewModel.factory((application as ReadAlongApplication).audioImporter, (application as ReadAlongApplication).importedEpub, (application as ReadAlongApplication).processing)
    }

    override fun onDestroy() {
        if (isFinishing) (application as ReadAlongApplication).playback.pause()
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val launchSettings = com.example.readalong.ui.launch.LaunchAppearance.read(this)
        setTheme(com.example.readalong.ui.launch.LaunchAppearance.theme(this, launchSettings))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { !launchSettings.libraryLight },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { !launchSettings.libraryLight },
        )
        // A linked computer: while ReadAlong is on screen, check every minute whether it's reachable, and sync when it
        // has something new (a finished ReadAlong, an edit, "Sync to phone") or the last sync is getting old.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val app = application as ReadAlongApplication
                runCatching { app.folderLink.watch { com.example.readalong.data.link.LinkSyncWorker.syncNow(app) } }
            }
        }
        // Books dropped into a library folder while ReadAlong is on screen are added without a manual refresh.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { importViewModel.watchFolders() }
        }
        val animateLaunch = savedInstanceState == null
        setContent {
            var launching by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(animateLaunch) }
            androidx.compose.foundation.layout.Box {
            val app = application as ReadAlongApplication
            val settings by app.appPreferences.preferences.collectAsStateWithLifecycle<com.example.readalong.data.preferences.AppPreferences?>(null)
            // Light/dark can change while the app is open (no restart): keep the system bar icons in step.
            val lightNow = settings?.libraryLight ?: launchSettings.libraryLight
            androidx.compose.runtime.LaunchedEffect(lightNow) {
                if (lightNow != launchSettings.libraryLight || settings != null) enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { !lightNow },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { !lightNow },
                )
            }
            settings?.let { preferences ->
                androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.theme.LocalAppSettings provides preferences) {
                    ReadAlongTheme(light = preferences.libraryLight) {
                        ReadAlongApp(libraryViewModel, app, importViewModel, showPrompts = !launching)
                    }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(com.example.readalong.ui.theme.LocalAppSettings provides (settings ?: launchSettings)) {
                com.example.readalong.ui.launch.LaunchLogo(ready = settings != null, animate = launching, onFinished = { launching = false })
            }
            }
        }
    }
}


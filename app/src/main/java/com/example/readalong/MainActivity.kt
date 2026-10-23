package com.example.readalong

import android.os.Bundle
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
        val animateLaunch = savedInstanceState == null
        setContent {
            var launching by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(animateLaunch) }
            androidx.compose.foundation.layout.Box {
            val app = application as ReadAlongApplication
            val settings by app.appPreferences.preferences.collectAsStateWithLifecycle<com.example.readalong.data.preferences.AppPreferences?>(null)
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


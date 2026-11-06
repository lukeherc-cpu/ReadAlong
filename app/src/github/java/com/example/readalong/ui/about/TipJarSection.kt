package com.example.readalong.ui.about

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import com.example.readalong.BuildConfig

/** Only the GitHub distribution contains external tipping UI: a plain row under Settings › About. */
@Composable
fun TipJarRow() {
    val uriHandler = LocalUriHandler.current
    var unavailable by remember { mutableStateOf(false) }
    SettingsRow("Tip jar", Modifier.testTag("tip_jar_card"), summary = "Support ReadAlong's development", onClick = {
        if (BuildConfig.KOFI_URL.isNotBlank()) {
            try { uriHandler.openUri(BuildConfig.KOFI_URL) }
            catch (_: android.content.ActivityNotFoundException) { unavailable = true }
            catch (_: IllegalArgumentException) { unavailable = true }
        } else unavailable = true
    })
    if (unavailable) AlertDialog(onDismissRequest = { unavailable = false },
        title = { Text("Tip jar") },
        text = { Text(if (BuildConfig.KOFI_URL.isBlank()) "Tips aren’t available yet. All ReadAlong features remain free."
            else "Open the Ko-fi page in a browser to leave an optional tip. All ReadAlong features remain free.") },
        confirmButton = { TextButton(onClick = { unavailable = false }) { Text("Close") } })
}

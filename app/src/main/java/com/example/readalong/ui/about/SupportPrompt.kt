package com.example.readalong.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.BuildConfig
import kotlinx.coroutines.launch

private const val GITHUB_URL = "https://github.com/lukeherc-cpu/ReadAlong"

/**
 * "Enjoying ReadAlong?": shown after every 10 hours of listening (counted by the playback service) until
 * "Don't show this again" is ticked. Donate only appears in the GitHub build, like the tip jar.
 */
@Composable
fun SupportPrompt(app: com.example.readalong.ReadAlongApplication) {
    val settings by app.appPreferences.preferences.collectAsStateWithLifecycle(null)
    val s = settings ?: return
    // Keyed on the due time, so the card can come back once the next 10 hours are reached.
    var closed by remember(s.supportPromptAtMs) { mutableStateOf(false) }
    if (closed || s.supportPromptOff || s.listenedMs < s.supportPromptAtMs) return
    var never by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val donate = BuildConfig.TIP_JAR_ENABLED && BuildConfig.KOFI_URL.isNotBlank()
    fun close() {
        closed = true
        scope.launch { app.appPreferences.update {
            if (never) it.copy(supportPromptOff = true)
            else it.copy(supportPromptAtMs = it.listenedMs + com.example.readalong.data.preferences.SUPPORT_PROMPT_EVERY_MS)
        } }
    }
    fun open(url: String) { runCatching { uri.openUri(url) }; close() }
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = ::close) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, tonalElevation = 2.dp) {
            Box {
                FilledTonalIconButton(::close, Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp)) { Text("✕") }
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    com.example.readalong.ui.launch.PageTurnLogo(0f, Modifier.size(104.dp))
                    Text("Enjoying ReadAlong?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("You've listened to ${s.listenedMs / 3_600_000} hours of audiobooks. ReadAlong is free and open source, so " +
                        (if (donate) "a small donation or a star really helps." else "a star really helps."),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    if (donate) {
                        Button({ open(BuildConfig.KOFI_URL) }, Modifier.fillMaxWidth().height(48.dp)) { Text("♥  Donate", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton({ open(GITHUB_URL) }, Modifier.fillMaxWidth().height(48.dp)) { Text("★  Give us a star on GitHub", fontWeight = FontWeight.Bold) }
                    } else Button({ open(GITHUB_URL) }, Modifier.fillMaxWidth().height(48.dp)) { Text("★  Give us a star on GitHub", fontWeight = FontWeight.Bold) }
                    Row(Modifier.padding(top = 6.dp).toggleable(never, role = Role.Checkbox) { never = it }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(never, null)
                        Text("Don't show this again", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

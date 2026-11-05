package com.example.readalong.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

const val DONATE_URL = "https://ko-fi.com/readalong"
const val GITHUB_URL = "https://github.com/lukeherc-cpu/ReadAlong"

/** "Enjoying ReadAlong?": shown after every 10 hours of listening until "Don't show this again" is ticked. */
@Composable
fun SupportPrompt(app: AppState) {
    var never by remember { mutableStateOf(false) }
    AnimatedVisibility(app.supportPrompt, enter = fadeIn(), exit = fadeOut()) {
        val close = { app.closeSupportPrompt(never) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))
            .clickable(remember { MutableInteractionSource() }, null) { close() }, contentAlignment = Alignment.Center) {
            Surface(Modifier.width(360.dp).clickable(remember { MutableInteractionSource() }, null) {},
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 12.dp) {
                Box {
                    FilledTonalIconButton(close, Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp)) {
                        Icon(Icons.Default.Close, "Close", Modifier.size(18.dp))
                    }
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        PageTurnLogo(0f, Modifier.size(104.dp))
                        Text("Enjoying ReadAlong?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("You've listened to ${app.settingsStore.value.listenedMs / 3_600_000} hours of audiobooks. ReadAlong is free and open source, so a small donation or a star really helps.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        Button({ openBrowser(DONATE_URL); close() }, Modifier.fillMaxWidth().height(48.dp)) {
                            Icon(Icons.Default.Favorite, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Donate", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton({ openBrowser(GITHUB_URL); close() }, Modifier.fillMaxWidth().height(48.dp)) {
                            Icon(Icons.Default.Star, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Give us a star on GitHub", fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.padding(top = 6.dp).clickable { never = !never }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(never, { never = it })
                            Text("Don't show this again", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

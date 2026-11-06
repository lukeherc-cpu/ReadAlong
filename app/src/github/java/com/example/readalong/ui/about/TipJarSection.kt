package com.example.readalong.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.readalong.BuildConfig
import com.example.readalong.R

/** Only the GitHub distribution contains external tipping UI. */
@Composable
fun TipJarSection(inOther: Boolean, onMove: (Boolean) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var unavailable by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag(if (inOther) "tip_jar_other" else "tip_jar_top")) {
        Surface(onClick = {
            if (BuildConfig.KOFI_URL.isNotBlank()) {
                try { uriHandler.openUri(BuildConfig.KOFI_URL) }
                catch (_: android.content.ActivityNotFoundException) { unavailable = true }
                catch (_: IllegalArgumentException) { unavailable = true }
            } else unavailable = true
        }, modifier = Modifier.fillMaxWidth().testTag("tip_jar_card"), shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Row(Modifier.background(Brush.horizontalGradient(listOf(colors.primaryContainer.copy(alpha = .65f), colors.primaryContainer.copy(alpha = .38f))))
                .padding(horizontal = 20.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_tip_jar), null, Modifier.size(width = 48.dp, height = 64.dp), tint = colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tip jar", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Text("Support the development of ReadAlong", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
            }
        }
        Row(Modifier.fillMaxWidth().testTag("tip_jar_move")
            .toggleable(value = inOther, role = Role.Checkbox, onValueChange = onMove)
            .padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(inOther, onCheckedChange = null)
            Text("Move tip jar to Other settings", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (unavailable) AlertDialog(onDismissRequest = { unavailable = false },
        title = { Text("Tip jar") },
        text = { Text(if (BuildConfig.KOFI_URL.isBlank()) "Tips aren’t available yet. All ReadAlong features remain free."
            else "Open the Ko-fi page in a browser to leave an optional tip. All ReadAlong features remain free.") },
        confirmButton = { TextButton(onClick = { unavailable = false }) { Text("Close") } })
}

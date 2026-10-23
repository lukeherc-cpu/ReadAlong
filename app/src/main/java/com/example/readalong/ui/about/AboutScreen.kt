package com.example.readalong.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.readalong.R

@Composable
fun AboutScreen(onBack: () -> Unit, onSpeechModel: () -> Unit) {
    var showLicenses by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val notices = androidx.compose.runtime.remember { listOf("readalong-notice.txt", "readalong-GPL-3.0.txt", "NOTICE.txt", "cactus-whistle.txt", "jsoup-LICENSE.txt").joinToString("\n\n") { name ->
        context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
    } }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.back_to_library),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(stringResource(R.string.about_readalong), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.about_headline),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyLarge)
                Text("Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.TextButton(onClick = { uriHandler.openUri("https://github.com/lukeherc-cpu/ReadAlong") }) { Text("Source code · GPLv3") }
                androidx.compose.material3.TextButton(onClick = { showLicenses = true }) { Text("Software licenses") }
                androidx.compose.material3.OutlinedButton(onClick = onSpeechModel) { Text("Local speech model") }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.preview_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showLicenses) androidx.compose.material3.AlertDialog(onDismissRequest = { showLicenses = false },
        title = { Text("Software licenses") },
        text = { Text(notices, modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { showLicenses = false }) { Text("Close") } })

}

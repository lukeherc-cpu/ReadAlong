package com.example.readalong.ui.about

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.readalong.BuildConfig
import com.example.readalong.domain.update.AppRelease
import com.example.readalong.domain.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Update checks for the GitHub download (the Play edition is updated by the store). Once a day the app asks
 * GitHub for the latest release and offers it; Download opens the APK in the browser, and it installs over
 * this one because both are signed with the same key. Library, ReadAlongs and progress are kept by Android.
 */
private object PhoneUpdates {
    private const val FILE = "update_checks"
    /** The release offered this session, shared by the prompt and Settings. */
    val offer = MutableStateFlow<AppRelease?>(null)
    val manual = MutableStateFlow<String?>(null)
    @Volatile private var checking = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("enabled", value).apply()
    fun skip(context: Context, release: AppRelease) { prefs(context).edit().putString("skipped", release.version.toString()).apply(); offer.value = null }

    suspend fun check(context: Context, manualCheck: Boolean) {
        val prefs = prefs(context)
        if (checking) return
        if (!manualCheck && (!enabled(context) || !UpdateCheck.due(prefs.getLong("last_check", 0), System.currentTimeMillis()))) return
        checking = true
        if (manualCheck) manual.value = "Checking…"
        try {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateCheck.fetchLatest("ReadAlong-Android/${BuildConfig.VERSION_NAME}") } }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            result.onSuccess { latest ->
                val available = UpdateCheck.offer(BuildConfig.VERSION_NAME, latest, skipped = if (manualCheck) null else prefs.getString("skipped", null))
                offer.value = available
                if (manualCheck) manual.value = if (available != null) null else "ReadAlong ${BuildConfig.VERSION_NAME} is the latest version."
            }.onFailure { if (manualCheck) manual.value = "Couldn't reach GitHub. Check your connection and try again." }
        } finally { checking = false }
    }
}

/** Shown over the library when a newer release is out; the app calls this only when no other prompt is showing. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PhoneUpdates.check(context, manualCheck = false) }
    val release by PhoneUpdates.offer.collectAsState()
    release?.let { UpdateDialog(it) }
}

/** Styled like the app's other prompts: the ReadAlong logo, a short title and two full-width pill buttons in the current theme. */
@Composable
private fun UpdateDialog(release: AppRelease) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(onDismissRequest = { PhoneUpdates.offer.value = null }) {
        Surface(Modifier.fillMaxWidth().testTag("update_dialog"), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(androidx.compose.ui.res.painterResource(com.example.readalong.R.drawable.ic_readalong), null, Modifier.size(72.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Text("Update available", style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Button(onClick = {
                    runCatching { uriHandler.openUri(release.apkUrl ?: release.pageUrl) }
                    PhoneUpdates.offer.value = null
                }, Modifier.fillMaxWidth().height(52.dp).testTag("update_download")) { Text("Download", style = MaterialTheme.typography.titleMedium) }
                FilledTonalButton(onClick = { PhoneUpdates.offer.value = null }, Modifier.fillMaxWidth().height(52.dp).testTag("update_later")) {
                    Text("Later", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The Updates rows in Settings. */
@Composable
fun UpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(PhoneUpdates.enabled(context)) }
    val status by PhoneUpdates.manual.collectAsState()
    val release by PhoneUpdates.offer.collectAsState()
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates automatically", style = MaterialTheme.typography.bodyLarge)
                Text("Once a day, from GitHub. Nothing downloads until you choose to.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, onCheckedChange = { enabled = it; PhoneUpdates.setEnabled(context, it) }, Modifier.testTag("update_auto"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { PhoneUpdates.check(context, manualCheck = true) } }, enabled = status != "Checking…",
                modifier = Modifier.testTag("update_check")) { Text("Check for updates") }
            val found = release
            if (status == null && found != null) TextButton(onClick = { runCatching { uriHandler.openUri(found.apkUrl ?: found.pageUrl) } }) { Text("Download ${found.version}") }
            else Text(status ?: "Version ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

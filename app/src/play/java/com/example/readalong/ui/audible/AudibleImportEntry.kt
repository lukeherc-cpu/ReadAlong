package com.example.readalong.ui.audible

import com.example.readalong.domain.i18n.tr

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.ui.importing.PickerStart
import com.example.readalong.ui.link.LinkScreen

/**
 * "Import from Audible" in the Play build. Google Play doesn't allow ReadAlong to sign in to Audible or unlock
 * its files, so this is a short guide instead: get playable copies with ReadAlong for computer, LibriSync, or
 * another converter, then ReadAlong picks them up. Every outside link goes to [GUIDE], a page we control,
 * never to a converter directly.
 */
@Composable
fun AudibleImportEntry(onChooseFolder: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AudibleGuide(onChooseFolder, onDismiss) }
    }
}

/** The web guide, with a section per path. */
private const val GUIDE = "https://readalongapp.github.io/ReadAlong/audible/"
private const val LIBRISYNC_PACKAGE = "tech.henning.librisync"

private enum class Path(val anchor: String) { COMPUTER("computer"), LIBRISYNC("android"), OTHER("other"), HAVE_FILES("") }
private enum class Stage { CHOOSE, STEPS, LINK }

/** The chosen path is remembered, so coming back to the guide later picks up where the person left off. */
private fun saved(context: Context) = context.getSharedPreferences("audible_guide", Context.MODE_PRIVATE)

@Composable
private fun AudibleGuide(onChooseFolder: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var path by remember { mutableStateOf(saved(context).getString("path", null)?.let { name -> Path.entries.firstOrNull { it.name == name } }) }
    var stage by remember { mutableStateOf(if (path != null && path != Path.HAVE_FILES) Stage.STEPS else Stage.CHOOSE) }
    fun pick(p: Path) { path = p; saved(context).edit().putString("path", p.name).apply() }
    /** Opens the folder picker in the folder the guide told the person to use. */
    fun chooseFolder(start: String) { PickerStart.sharedFolder(start); onChooseFolder() }
    fun back() {
        when (stage) {
            Stage.LINK -> stage = Stage.STEPS
            Stage.STEPS -> stage = Stage.CHOOSE
            Stage.CHOOSE -> onClose()
        }
    }
    BackHandler { back() }

    if (stage == Stage.LINK) {
        LinkScreen(context.applicationContext as ReadAlongApplication, onBack = { back() })
        return
    }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }) { Icon(painterResource(R.drawable.ic_back), tr("Back")) }
            Text(tr("Import from Audible"), style = MaterialTheme.typography.titleLarge)
        }
        Progress(if (stage == Stage.CHOOSE) 1 else 2)
        AnimatedContent(stage to path, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "guide") { (shown, chosen) ->
            when {
                shown == Stage.CHOOSE || chosen == null -> Choose(chosen, onContinue = { p ->
                    pick(p)
                    if (p == Path.HAVE_FILES) chooseFolder("Download") else stage = Stage.STEPS
                })
                chosen == Path.COMPUTER -> Computer(onLink = { stage = Stage.LINK })
                chosen == Path.LIBRISYNC -> LibriSync(onFound = { chooseFolder("Audiobooks") })
                else -> Converter(onFound = { chooseFolder("Audiobooks") })
            }
        }
    }
}

@Composable
private fun Progress(done: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Surface(Modifier.weight(1f).height(4.dp), shape = CircleShape,
                color = if (i < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)) {}
        }
    }
}

/** A step screen: "Step n of 3", a heading, scrolling content, and the buttons pinned at the bottom. */
@Composable
private fun Page(step: Int, title: String, buttons: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Step $step of 3"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(tr(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

@Composable
private fun Choose(chosen: Path?, onContinue: (Path) -> Unit) {
    var selected by remember { mutableStateOf(chosen ?: Path.COMPUTER) }
    Page(1, "Bring your Audible books into ReadAlong", buttons = {
        Button(onClick = { onContinue(selected) }, Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Continue")) }
    }) {
        Text(tr("Audible locks its downloads to its own app. Pick how to get playable copies of the books you own."),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Option(R.drawable.ic_computer, "ReadAlong for computer", "Sign in to Audible there. Books and their transcripts come to this phone over Wi-Fi.",
            badge = "Easiest", selected = selected == Path.COMPUTER) { selected = Path.COMPUTER }
        Option(R.drawable.ic_phone, "LibriSync on this phone", "No computer needed. A free Android app gets your books, and ReadAlong picks them up.",
            selected = selected == Path.LIBRISYNC) { selected = Path.LIBRISYNC }
        Option(R.drawable.ic_waveform, "Another converter tool", "Make M4B files yourself, then copy them to this phone.",
            selected = selected == Path.OTHER) { selected = Path.OTHER }
        Option(R.drawable.ic_folder, "I already have the files", "M4B or MP3 files on this phone. Go straight to choosing the folder.",
            selected = selected == Path.HAVE_FILES) { selected = Path.HAVE_FILES }
    }
}

@Composable
private fun Option(@DrawableRes icon: Int, title: String, body: String, badge: String? = null, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(title), style = MaterialTheme.typography.titleMedium)
                    badge?.let {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(tr(it), Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Text(tr(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Steps(vararg steps: Pair<String, String>, extra: @Composable ColumnScope.() -> Unit = {}) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            steps.forEachIndexed { i, (title, body) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(Modifier.size(26.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr(title), style = MaterialTheme.typography.titleSmall)
                        Text(tr(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            extra()
        }
    }
}

/** "Open the guide" and "Send this link to myself", both pointing at [GUIDE]'s section for [path]. */
@Composable
private fun GuideLinks(path: Path) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val url = GUIDE + "#" + path.anchor
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Step-by-step guide with links"), style = MaterialTheme.typography.labelLarge)
            Text("readalongapp.github.io/ReadAlong/audible", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { runCatching { links.openUri(url) } }) {
                    Icon(painterResource(R.drawable.ic_open_external), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Open"))
                }
                TextButton(onClick = { share(context, url) }) {
                    Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Send to myself"))
                }
            }
        }
    }
}

private fun share(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, tr("Bring your Audible books into ReadAlong"))
        .putExtra(Intent.EXTRA_TEXT, url)
    runCatching { context.startActivity(Intent.createChooser(send, tr("Send the guide")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Computer(onLink: () -> Unit) {
    Page(2, "On your computer", buttons = {
        Button(onClick = onLink, Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Link this phone")) }
    }) {
        Steps(
            "Install ReadAlong for computer" to "Free for Windows, Mac and Linux. The guide has the download links.",
            "Choose Import from Audible and sign in" to "Your password goes only to Amazon.",
            "Tick the books you want" to "Your computer downloads them and makes the transcripts, much faster than a phone.",
            "Link this phone" to "On the computer, open Settings › Phone › Link phone, then tap Link this phone below and scan the code. Choose the Audible Audiobooks folder to sync.",
        ) { GuideLinks(Path.COMPUTER) }
        Text(tr("Keep both on the same Wi-Fi. Books you buy later come across on their own."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun libriSyncInstalled(context: Context) = context.packageManager.getLaunchIntentForPackage(LIBRISYNC_PACKAGE) != null

@Composable
private fun LibriSync(onFound: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var installed by remember { mutableStateOf(libriSyncInstalled(context)) }
    // Back from the browser, the installer or LibriSync: look again, so the guide moves on by itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { installed = libriSyncInstalled(context) }
    if (!installed) Page(2, "Install LibriSync", buttons = {
        OutlinedButton(onClick = { installed = libriSyncInstalled(context) }, Modifier.fillMaxWidth().height(48.dp)) { Text(tr("I already have LibriSync")) }
        Button(onClick = { runCatching { links.openUri(GUIDE + "#android") } }, Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Get LibriSync")) }
    }) {
        Text(tr("LibriSync is a free app that downloads the Audible books you own. It isn't on Google Play, so Android asks once before installing it."),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Steps(
            "Tap Get LibriSync" to "The guide opens. Tap its Get LibriSync button, then the file that ends in .apk.",
            "Open the download and tap Install" to "If Android says “For your security…”, tap Settings, turn on Allow from this source, then go back.",
            "Come back here" to "ReadAlong notices LibriSync is installed and moves on.",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(tr("Waiting for LibriSync to be installed…"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else Page(2, "Get your books in LibriSync", buttons = {
        OutlinedButton(onClick = onFound, Modifier.fillMaxWidth().height(48.dp)) { Text(tr("My books are downloaded")) }
        Button(onClick = { context.packageManager.getLaunchIntentForPackage(LIBRISYNC_PACKAGE)?.let { runCatching { context.startActivity(it) } } },
            Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Open LibriSync")) }
    }) {
        Steps(
            "Sign in with your Amazon account" to "Your password goes only to Amazon.",
            "Download the books you want" to "Wi-Fi is quicker. Larger books can take a few minutes each.",
            "Save them as M4B in the Audiobooks folder" to "Any folder works, but ReadAlong opens there first.",
            "Come back and tap My books are downloaded" to "Choose the folder once. ReadAlong adds the books and starts their transcripts, and books you download later show up on their own.",
        ) { GuideLinks(Path.LIBRISYNC) }
    }
}

@Composable
private fun Converter(onFound: () -> Unit) {
    Page(2, "On your computer", buttons = {
        Button(onClick = onFound, Modifier.fillMaxWidth().height(52.dp)) { Text(tr("My files are on the phone")) }
    }) {
        Steps(
            "Open the guide on your computer" to "It has step-by-step pictures for a free converter.",
            "Sign in to Audible in the converter and pick your books" to "Your password goes only to Amazon.",
            "Choose M4B and convert" to "Keeps chapters and covers.",
            "Copy the files to this phone" to "Into the Audiobooks folder, with a USB cable, Google Drive or any cloud folder.",
        ) { GuideLinks(Path.OTHER) }
        Text(tr("Files that end in .aax or .aaxc came straight from the Audible app and won't play. Convert them first."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package com.example.readalong.ui.importing

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.data.importing.FolderMode
import kotlinx.coroutines.delay

/**
 * First launch: what ReadAlong does, then where the audiobooks are. Replaces the old "Set folder"
 * pop-up. [onDone] runs once a folder or files were picked, or the person skips.
 */
@Composable
fun WelcomeScreen(model: ImportViewModel, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step == 1) { step = 0 }
    Surface(Modifier.fillMaxSize().testTag("welcome"), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(step, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "Welcome step") { shown ->
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (shown == 0) WelcomeIntro { step = 1 } else WelcomeSources(model, onDone, onSkip = onDone)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeIntro(onNext: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    ReadAlongDemo(Modifier.fillMaxWidth())
    Text("Listen to your audiobooks and read along", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    WelcomePoint(R.drawable.ic_play, "Your audiobooks, with the words on screen as they're read to you.")
    WelcomePoint(R.drawable.ic_highlighted_book, "ReadAlong makes the text on your phone. Nothing is uploaded.")
    WelcomePoint(R.drawable.ic_book, "Have the ebook (EPUB) too? Add it to get the book's exact text.")
    Spacer(Modifier.height(4.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome_next")) { Text("Get started") }
}

@Composable
private fun ColumnScope.WelcomeSources(model: ImportViewModel, onAdded: () -> Unit, onSkip: () -> Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    Spacer(Modifier.height(8.dp))
    Text("Where are your audiobooks?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("Pick the folder that holds your audiobooks. ReadAlong looks for new books there each time you come back.", style = MaterialTheme.typography.bodyLarge)
    FolderExample()
    AudiobookSourceButtons(model, onAdded)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip for now") }
}

/** Folder, files and Audible: shared by the welcome screen and the empty library. */
@Composable
fun AudiobookSourceButtons(model: ImportViewModel, onAdded: () -> Unit = {}) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var audible by rememberSaveable { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.folder(it, FolderMode.LIBRARY, onAdded) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { model.files(uris); onAdded() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { folder.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("choose_folder")) {
            Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Choose audiobook folder")
        }
        OutlinedButton(onClick = { files.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Add audio files")
        }
        OutlinedButton(onClick = { audible = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Import from Audible")
        }
    }
    if (audible) AudibleImportDialog(onChooseFolder = { audible = false; folder.launch(null) }, onDismiss = { audible = false })
}

/** A tiny picture of a folder layout that works. */
@Composable
fun FolderExample(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("For example", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FolderLine(0.dp, true, "Audiobooks")
            FolderLine(20.dp, true, "Dune")
            FolderLine(40.dp, false, "Part 1.mp3")
            FolderLine(40.dp, false, "Part 2.mp3")
            FolderLine(20.dp, false, "Project Hail Mary.m4b")
            Text("A folder for each book, or single audio files: both work.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun FolderLine(indent: Dp, folder: Boolean, name: String) {
    Row(Modifier.padding(start = indent), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(if (folder) R.drawable.ic_folder else R.drawable.ic_play), null, Modifier.size(18.dp),
            tint = if (folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (folder) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun WelcomePoint(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** A book cover beside its text, one sentence lit at a time: what a ReadAlong looks like. */
@Composable
fun ReadAlongDemo(modifier: Modifier = Modifier) {
    val lines = listOf(1f, .86f, .94f, .7f, 1f, .62f)
    val sentences = listOf(0..1, 2..3, 4..5)
    var sentence by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        while (true) { delay(1400); sentence = (sentence + 1) % sentences.size }
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier.semantics(mergeDescendants = true) { contentDescription = "Example: the book's text, highlighted sentence by sentence as it plays" }, shape = RoundedCornerShape(24.dp), color = colors.surfaceVariant) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(78.dp).aspectRatio(.68f).clip(RoundedCornerShape(10.dp)).background(colors.primary), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_book), null, Modifier.size(30.dp), tint = colors.onPrimary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                lines.forEachIndexed { index, width ->
                    val lit = index in sentences[sentence]
                    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(320), label = "Demo line")
                    Box(Modifier.fillMaxWidth(width).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant.copy(alpha = .22f), colors.primary, glow)))
                }
            }
        }
    }
}

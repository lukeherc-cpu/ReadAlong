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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
            if (shown == 0) Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) { WelcomeIntro { step = 1 } }
            // The sources step fills the screen without scrolling: the buttons share the spare height.
            // If the text doesn't fit (small screens, large fonts), the page scrolls instead.
            else BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val viewport = maxHeight
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { WelcomeSources(model, onDone, onSkip = onDone, viewport) }
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
private fun WelcomeSources(model: ImportViewModel, onAdded: () -> Unit, onSkip: () -> Unit, viewport: Dp) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    FillingColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), viewport - 32.dp, 12.dp) {
        Text("Where are your audiobooks?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text("ReadAlong checks there for new books each time you come back.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AudiobookSourceButtons(model, onAdded, large = true, stacked = true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Box(Modifier.fillMaxWidth().layoutId(BottomOfPage), contentAlignment = Alignment.Center) {
            TextButton(onClick = onSkip, enabled = !busy) { Text("Skip for now") }
        }
    }
}

/** A child of [FillingColumn] that grows into spare height, by [weight], up to [max]. */
private data class Grow(val weight: Float, val min: Dp, val max: Dp)
private const val BottomOfPage = "bottom"

/**
 * A column that fills [viewport]: children marked [Grow] start at the height their content needs and
 * share the spare height (up to their maximum); anything left goes above the [BottomOfPage] child.
 * When the content needs more than the viewport, it is simply taller (the caller scrolls).
 */
@Composable
private fun FillingColumn(modifier: Modifier, viewport: Dp, gap: Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val grows = measurables.map { it.layoutId as? Grow }
        val loose = androidx.compose.ui.unit.Constraints(maxWidth = width)
        val fixed = measurables.mapIndexed { i, m -> if (grows[i] == null) m.measure(loose) else null }
        val natural = measurables.mapIndexed { i, m ->
            grows[i]?.let { g -> m.maxIntrinsicHeight(width).coerceAtLeast(g.min.roundToPx()) } ?: fixed[i]!!.height
        }
        val used = natural.sum() + gapPx * (measurables.size - 1).coerceAtLeast(0)
        val spare = (viewport.roundToPx() - used).coerceAtLeast(0)
        val totalWeight = grows.sumOf { (it?.weight ?: 0f).toDouble() }.toFloat()
        val heights = natural.mapIndexed { i, h ->
            val g = grows[i] ?: return@mapIndexed h
            (h + (spare * g.weight / totalWeight).toInt()).coerceAtMost(g.max.roundToPx().coerceAtLeast(h))
        }
        val placeables = measurables.mapIndexed { i, m -> fixed[i] ?: m.measure(androidx.compose.ui.unit.Constraints.fixed(width, heights[i])) }
        val content = heights.sum() + gapPx * (measurables.size - 1).coerceAtLeast(0)
        val height = maxOf(content, viewport.roundToPx(), constraints.minHeight)
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { i, p ->
                if (measurables[i].layoutId == BottomOfPage) y = maxOf(y, height - p.height)
                p.place(0, y); y += p.height + gapPx
            }
        }
    }
}

/**
 * The three kinds of source, each with its own shade of the accent, used by both the example and the
 * buttons so they read as a key: library folder (strongest), audiobook folder, audio file (lightest).
 */
@Composable
internal fun sourceShades(): List<Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>> {
    val colors = MaterialTheme.colorScheme
    return listOf(0f, .3f, .55f).map { mix ->
        val shade = androidx.compose.ui.graphics.lerp(colors.primary, colors.background, mix)
        // Whichever of white or near-black text has more contrast on this shade.
        val l = shade.luminance()
        shade to (if ((l + .05f) / .066f > 1.05f / (l + .05f)) androidx.compose.ui.graphics.Color(0xFF1B2229) else androidx.compose.ui.graphics.Color.White)
    }
}

/** Library folder, audiobook folder, files and Audible: shared by the welcome screen and the empty library. */
@Composable
fun AudiobookSourceButtons(model: ImportViewModel, onAdded: () -> Unit = {}, large: Boolean = false, stacked: Boolean = false, modifier: Modifier = Modifier) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var audible by rememberSaveable { mutableStateOf(false) }
    val shades = sourceShades()
    // Reading a folder can take a while: go on to the library at once, which shows placeholders
    // (and "Reading folder" under the logo) until the books arrive.
    val library = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.folder(it, FolderMode.LIBRARY); onAdded() }
    }
    val book = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.folder(it, FolderMode.ONE_BOOK); onAdded() }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { model.files(listOf(uri)); onAdded() }
    }
    // Stacked: the buttons are placed straight into the welcome page's FillingColumn, which grows them.
    val grow = if (stacked) Modifier.layoutId(Grow(1f, 72.dp, 150.dp)) else Modifier
    val buttons = @Composable {
        SourceButton(shades[0], R.drawable.ic_bookshelf, "Choose library folder", "A folder with all your audiobook folders", !busy, grow.testTag("choose_folder"), large) { library.launch(null) }
        SourceButton(shades[1], R.drawable.ic_folder, "Choose audiobook folder", "A folder with one audiobook's files", !busy, grow, large) { book.launch(null) }
        SourceButton(shades[2], R.drawable.ic_play, "Add audio file", "One file that holds a whole audiobook", !busy, grow, large) { file.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4")) }
        OutlinedButton(onClick = { audible = true }, enabled = !busy, modifier = (if (stacked) Modifier.layoutId(Grow(.5f, 56.dp, 92.dp)) else Modifier).fillMaxWidth().heightIn(min = if (large) 56.dp else 48.dp)) {
            Text("Import from Audible", style = if (large) MaterialTheme.typography.titleMedium else LocalTextStyle.current)
        }
    }
    if (stacked) buttons()
    else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp)) { buttons() }
    if (audible) AudibleImportDialog(onChooseFolder = { audible = false; library.launch(null) }, onDismiss = { audible = false })
}

@Composable
private fun SourceButton(shade: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, icon: Int, title: String, subtitle: String?, enabled: Boolean,
    modifier: Modifier = Modifier, large: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = if (large) RoundedCornerShape(com.example.readalong.ui.theme.Corners.large) else ButtonDefaults.shape,
        colors = ButtonDefaults.buttonColors(containerColor = shade.first, contentColor = shade.second),
        contentPadding = PaddingValues(horizontal = if (large) 22.dp else 20.dp, vertical = 8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(if (large) 28.dp else 20.dp)); Spacer(Modifier.width(if (large) 18.dp else 14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (large) 2.dp else 0.dp)) {
            Text(title, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, color = shade.second.copy(alpha = .8f)) }
        }
    }
}

/**
 * A small picture of a folder layout, drawn like a file manager's tree (down arrows on folders,
 * connector lines); each kind wears the same shade as its button. [onDismiss] adds a close button.
 */
@Composable
fun FolderExample(modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null, compact: Boolean = false) {
    val shades = sourceShades()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box {
            Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 12.dp)) {
                Text("For example", style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = if (compact) 2.dp else 4.dp))
                CompositionLocalProvider(LocalTreeCompact provides compact) {
                TreeLine(0, shades[0], "Audiobooks", folder = true, open = true)
                TreeLine(1, shades[1], "Dune", folder = true, open = true, last = false)
                TreeLine(2, shades[2], "Part 1.mp3", continuing = listOf(true), last = false)
                TreeLine(2, shades[2], "Part 2.mp3", continuing = listOf(true), last = true)
                TreeLine(1, shades[1], "Project Hail Mary", folder = true, open = true, last = true)
                TreeLine(2, shades[2], "Project Hail Mary.m4b", continuing = listOf(false), last = true)
                }
            }
            if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                com.example.readalong.ui.components.CloseGlyph(MaterialTheme.colorScheme.onSurfaceVariant, "Hide example")
            }
        }
    }
}

/** "Help" with a chevron (down while folded); tapping it shows the [FolderExample]. Folded by default. */
@Composable
fun FolderHelp(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "Help chevron")
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("folder_help")
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }) {
            Text("Help")
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_down), null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
        }
        androidx.compose.animation.AnimatedVisibility(open,
            enter = androidx.compose.animation.expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(220)),
            exit = androidx.compose.animation.shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(140))) {
            FolderExample(Modifier.padding(top = 4.dp))
        }
    }
}

private val TreeStep = 24.dp
private val LocalTreeCompact = staticCompositionLocalOf { false }
private val ArrowSize = 16.dp

/**
 * One row of the tree. [continuing] says, for each ancestor level between the root and this row's
 * parent, whether that ancestor's line carries on below; [last] ends this row's own branch.
 */
@Composable
private fun TreeLine(depth: Int, shade: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, name: String,
    folder: Boolean = false, open: Boolean = false, continuing: List<Boolean> = emptyList(), last: Boolean = true) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
    val compact = LocalTreeCompact.current
    Row(Modifier.fillMaxWidth().height(if (compact) 24.dp else 32.dp).drawBehind {
        val stroke = 1.5.dp.toPx(); val step = TreeStep.toPx(); val mid = size.height / 2
        fun guide(level: Int) = (level - 1) * step + ArrowSize.toPx() / 2   // under the parent's arrow
        // Ancestors whose branches continue past this row.
        continuing.forEachIndexed { i, more -> if (more) drawLine(line, Offset(guide(i + 1), 0f), Offset(guide(i + 1), size.height), stroke) }
        if (depth > 0) {
            val x = guide(depth)
            drawLine(line, Offset(x, 0f), Offset(x, if (last) mid else size.height), stroke)
            drawLine(line, Offset(x, mid), Offset(depth * step - 3.dp.toPx(), mid), stroke)
        }
        // An open folder's own line runs down to its children.
        if (folder && open) { val x = depth * step + ArrowSize.toPx() / 2; drawLine(line, Offset(x, mid + (if (compact) 6.dp else 8.dp).toPx()), Offset(x, size.height), stroke) }
    }, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(TreeStep * depth))
        if (folder) Icon(painterResource(R.drawable.ic_down), null, Modifier.size(if (compact) 14.dp else ArrowSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else Spacer(Modifier.width(4.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, Modifier.clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.small)).background(shade.first).padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 1.dp else 2.dp),
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, color = shade.second, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun WelcomePoint(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
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
    Surface(modifier.semantics(mergeDescendants = true) { contentDescription = "Example: the book's text, highlighted sentence by sentence as it plays" }, shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = colors.surfaceVariant) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(78.dp).aspectRatio(.68f).clip(RoundedCornerShape(com.example.readalong.ui.theme.Corners.small)).background(colors.primary), contentAlignment = Alignment.Center) {
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

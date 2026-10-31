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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readalong.R
import com.example.readalong.data.importing.FolderMode
import kotlinx.coroutines.delay

/**
 * First launch: how to set up the library (local files or Audible), then that source's page. Each page
 * is centered; the title inks in word by word, like a ReadAlong, and the line under it all at once.
 * [onDone] runs once a folder or file was added, or the person skips.
 */
@Composable
fun WelcomeScreen(model: ImportViewModel, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step != 0) { step = 0 }
    Surface(Modifier.fillMaxSize().testTag("welcome"), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(step, transitionSpec = {
            val forward = targetState > initialState
            (androidx.compose.animation.slideInHorizontally(tween(460, easing = WelcomeEasing)) { if (forward) it else -it / 4 } + fadeIn(tween(320))) togetherWith
                (androidx.compose.animation.slideOutHorizontally(tween(460, easing = WelcomeEasing)) { if (forward) -it / 4 else it } + fadeOut(tween(240)))
        }, label = "Welcome step") { shown ->
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                when (shown) {
                    0 -> WelcomeChoose(onLocal = { step = 1 }, onAudible = { step = 2 }, onSkip = onDone)
                    1 -> WelcomeLocal(model, onDone, onBack = { step = 0 })
                    else -> WelcomeAudible(model, onDone, onBack = { step = 0 })
                }
            }
        }
    }
}

private val WelcomeEasing = androidx.compose.animation.core.CubicBezierEasing(.2f, .8f, .2f, 1f)

/** A centered page that scrolls only when it doesn't fit (small screens, large fonts). */
@Composable
private fun CenteredPage(onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) { Icon(painterResource(R.drawable.ic_back), "Back") }
            Column(Modifier.fillMaxWidth().heightIn(min = height - if (onBack != null) 56.dp else 0.dp).padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), content = content)
        }
    }
}

@Composable
private fun WelcomeLogo(size: Dp = 104.dp) =
    androidx.compose.foundation.Image(painterResource(R.drawable.ic_readalong), "ReadAlong", Modifier.size(size))

/** Words go from grey to ink one after another, then the line under them all at once: the full-screen ReadAlong's look. */
@Composable
private fun InkTitle(title: String, subtitle: String) {
    val words = remember(title) { title.split(" ") }
    val ink = MaterialTheme.colorScheme.onBackground
    val grey = androidx.compose.ui.graphics.lerp(ink, MaterialTheme.colorScheme.background, .45f)
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val spoken = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else words.size + 1.5f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        delay(650)
        spoken.animateTo(words.size + 1.5f, tween(320 * (words.size + 2), easing = androidx.compose.animation.core.LinearEasing))
    }
    fun shade(at: Float) = androidx.compose.ui.graphics.lerp(grey, ink, (spoken.value - at).coerceIn(0f, 1f))
    val text = androidx.compose.ui.text.buildAnnotatedString {
        words.forEachIndexed { i, word ->
            if (i > 0) append(" ")
            withStyle(androidx.compose.ui.text.SpanStyle(color = shade(i.toFloat()))) { append(word) }
        }
    }
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.semantics { heading() })
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = shade(words.size + .5f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp))
}

/** One of the two set-up choices: a tall rounded button with an icon tile, title, line and chevron. */
@Composable
private fun SetupChoice(shade: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, icon: Int, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 84.dp), shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = shade.first, contentColor = shade.second), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(shade.second.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = shade.second.copy(alpha = .85f))
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(22.dp))
    }
}

@Composable
private fun WelcomeChoose(onLocal: () -> Unit, onAudible: () -> Unit, onSkip: () -> Unit) {
    val shades = sourceShades()
    CenteredPage(onBack = null) {
        WelcomeLogo(112.dp)
        InkTitle("Listen and read along", "Your audiobooks, with the words on screen as they're read to you.")
        Text("How would you like to set up your library?", style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        SetupChoice(shades[0], R.drawable.ic_folder, "Local files", "Audiobooks already on this phone", Modifier.testTag("welcome_next"), onLocal)
        SetupChoice(shades[1], R.drawable.ic_headphones, "Import from Audible", "Books you own on Audible", onClick = onAudible)
        TextButton(onClick = onSkip, modifier = Modifier.padding(top = 4.dp).heightIn(min = 44.dp)) { Text("Skip for now") }
    }
}

@Composable
private fun WelcomeStatus(model: ImportViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

@Composable
private fun WelcomeLocal(model: ImportViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val add = rememberAddAudiobooks(model, "sources", onDone)
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { model.files(listOf(uri)); onDone() }
    }
    CenteredPage(onBack) {
        WelcomeLogo()
        Text("Add your audiobooks", style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text("Pick a folder. It can hold one book or your whole library. ReadAlong works out which and shows you before adding.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp))
        Button(onClick = add, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("choose_folder"), shape = RoundedCornerShape(30.dp)) {
            Text("Choose folder", style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { file.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4")) }, enabled = !busy, modifier = Modifier.heightIn(min = 44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)) {
            Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Or add a single audio file")
        }
        WelcomeStatus(model)
    }
}

@Composable
private fun WelcomeAudible(model: ImportViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var steps by rememberSaveable { mutableStateOf(false) }
    val add = rememberAddAudiobooks(model, "sources", onDone)
    CenteredPage(onBack) {
        WelcomeLogo()
        Text("Import from Audible", style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text("Audible books need converting before ReadAlong can play them. We'll walk you through it, then you pick the folder they were saved to.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp))
        Button(onClick = { steps = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(30.dp)) {
            Text("Show me how", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = add, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(28.dp)) {
            Text("I've converted them, choose folder")
        }
        WelcomeStatus(model)
    }
    if (steps) com.example.readalong.ui.audible.AudibleImportEntry(onChooseFolder = { steps = false; add() }, onDismiss = { steps = false })
}

/** A child of [FillingColumn] that grows into spare height, by [weight], up to [max]. */
private data class Grow(val weight: Float, val min: Dp, val max: Dp)
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

/** Add audiobooks (a book or library folder), files and Audible: shared by the welcome screen and the empty library. */
@Composable
fun AudiobookSourceButtons(model: ImportViewModel, onAdded: () -> Unit = {}, large: Boolean = false, stacked: Boolean = false, modifier: Modifier = Modifier) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var audible by rememberSaveable { mutableStateOf(false) }
    val shades = sourceShades()
    // One folder picker for a book or a whole library: the preview says which it found. After adding,
    // go on to the library at once, which shows placeholders (and "Reading folder" under the logo).
    val add = rememberAddAudiobooks(model, "sources", onAdded)
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { model.files(listOf(uri)); onAdded() }
    }
    // Stacked: the buttons are placed straight into the welcome page's FillingColumn, which grows them.
    val grow = if (stacked) Modifier.layoutId(Grow(1f, 72.dp, 150.dp)) else Modifier
    val buttons = @Composable {
        SourceButton(shades[0], R.drawable.ic_bookshelf, "Add audiobooks", "Choose a folder with one book or your whole library", !busy, grow.testTag("choose_folder"), large) { add() }
        SourceButton(shades[2], R.drawable.ic_play, "Add audio file", "One file that holds a whole audiobook", !busy, grow, large) { file.launch(arrayOf("audio/*", "application/octet-stream", "video/mp4")) }
        OutlinedButton(onClick = { audible = true }, enabled = !busy, modifier = (if (stacked) Modifier.layoutId(Grow(.5f, 56.dp, 92.dp)) else Modifier).fillMaxWidth().heightIn(min = if (large) 56.dp else 48.dp)) {
            Text("Import from Audible", style = if (large) MaterialTheme.typography.titleMedium else LocalTextStyle.current)
        }
    }
    if (stacked) buttons()
    else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (large) 10.dp else 8.dp)) { buttons() }
    if (audible) com.example.readalong.ui.audible.AudibleImportEntry(onChooseFolder = { audible = false; add() }, onDismiss = { audible = false })
}

@Composable
internal fun SourceButton(shade: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, icon: Int, title: String, subtitle: String?, enabled: Boolean,
    modifier: Modifier = Modifier, large: Boolean = false, hero: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = if (large) RoundedCornerShape(com.example.readalong.ui.theme.Corners.large) else ButtonDefaults.shape,
        colors = ButtonDefaults.buttonColors(containerColor = shade.first, contentColor = shade.second),
        contentPadding = PaddingValues(horizontal = if (large) 22.dp else 20.dp, vertical = 8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(if (hero) 36.dp else if (large) 28.dp else 20.dp)); Spacer(Modifier.width(if (large) 18.dp else 14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (large) 2.dp else 0.dp)) {
            Text(title, style = if (hero) MaterialTheme.typography.titleLarge else if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
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

package com.example.readalong.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The ReadAlong watch app: now playing with a progress ring, the chapter list, speed and sleep timer,
 * and the Started books one swipe to the left. It controls the phone's player; the bezel sets the volume.
 */
class WatchActivity : ComponentActivity() {
    private lateinit var phone: Phone

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = Phone(applicationContext, lifecycleScope)
        setContent { WatchApp(phone) }
    }
    override fun onStart() { super.onStart(); phone.start() }
    override fun onStop() { phone.stop(); super.onStop() }
}

private val SpeedChoices = listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
private fun WatchApp(phone: Phone) {
    val state by phone.state.collectAsState()
    val accent = Color(state.accent)
    val background = Color(state.background)
    val nav = rememberSwipeDismissableNavController()
    MaterialTheme(colors = Colors(primary = accent, onPrimary = background, surface = background.copy(alpha = .6f).compositeOverBlack(), background = Color.Black)) {
        SwipeDismissableNavHost(nav, startDestination = "home") {
            composable("home") {
                val pager = rememberPagerState { 2 }
                val scope = rememberCoroutineScope()
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    if (page == 0) NowPlaying(state, phone, onChapters = { nav.navigate("chapters") }, onSpeed = { nav.navigate("speed") }, onSleep = { nav.navigate("sleep") })
                    else Library(state) { id -> phone.send("book", id); scope.launch { pager.animateScrollToPage(0) } }
                }
            }
            composable("chapters") { Chapters(state) { index -> phone.send("chapter", index.toString()); nav.popBackStack() } }
            composable("speed") { Choices("Speed", SpeedChoices.map { speedLabel(it) to it.toString() }, selected = speedLabel(state.speed)) { phone.send("speed", it); nav.popBackStack() } }
            composable("sleep") {
                Choices("Sleep timer", listOf("Off" to "0", "15 minutes" to "15", "30 minutes" to "30", "45 minutes" to "45", "60 minutes" to "60", "End of chapter" to "-1"),
                    selected = null) { phone.send("sleep", it); nav.popBackStack() }
            }
        }
    }
}

private fun Color.compositeOverBlack() = Color(red * alpha, green * alpha, blue * alpha)

private fun speedLabel(value: Float): String {
    val text = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0')
    return "$text×"
}

private fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun NowPlaying(state: PhoneState, phone: Phone, onChapters: () -> Unit, onSpeed: () -> Unit, onSleep: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.playing) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val position = state.positionAt(now)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The bezel (or crown) turns the phone's volume up and down, one step per notch.
    var turned by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(Color(state.background))
        .onRotaryScrollEvent { event ->
            turned += event.verticalScrollPixels
            if (kotlin.math.abs(turned) > 48f) { phone.send("volume", if (turned > 0) "up" else "down"); turned = 0f }
            true
        }.focusRequester(focus).focusable(), contentAlignment = Alignment.Center) {
        state.cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(.22f), contentScale = ContentScale.Crop) }
        CircularProgressIndicator(progress = if (state.durationMs > 0) position.toFloat() / state.durationMs else 0f, Modifier.fillMaxSize().padding(3.dp),
            indicatorColor = Color(state.accent), trackColor = Color.White.copy(alpha = .15f), strokeWidth = 5.dp)
        if (state.title.isEmpty()) {
            Text(if (state.connected) "Pick a book in ReadAlong on your phone" else "Open ReadAlong on your phone", Modifier.padding(24.dp), textAlign = TextAlign.Center)
            return@Box
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.clickable(onClick = onChapters).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(listOf(state.chapter, clock(position)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp,
                    color = Color.White.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { phone.send("back") }, Modifier.size(ButtonDefaults.SmallButtonSize), colors = ButtonDefaults.secondaryButtonColors()) {
                    Icon(painterResource(R.drawable.ic_rewind), "Rewind", Modifier.size(18.dp))
                }
                Button(onClick = { phone.send("toggle") }, Modifier.size(ButtonDefaults.LargeButtonSize)) {
                    Icon(painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play), if (state.playing) "Pause" else "Play", Modifier.size(26.dp))
                }
                Button(onClick = { phone.send("forward") }, Modifier.size(ButtonDefaults.SmallButtonSize), colors = ButtonDefaults.secondaryButtonColors()) {
                    Icon(painterResource(R.drawable.ic_forward), "Forward", Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(onClick = onSpeed, label = { Text(speedLabel(state.speed)) }, colors = ChipDefaults.secondaryChipColors())
                val sleepLeft = (state.sleepEndsAt - now).takeIf { state.sleepEndsAt > 0 && it > 0 }
                CompactChip(onClick = onSleep, label = { Text(when { state.sleepAtChapterEnd -> "Ch end"; sleepLeft != null -> "${(sleepLeft + 59_999) / 60_000}m"; else -> "Sleep" }) },
                    colors = ChipDefaults.secondaryChipColors())
            }
        }
    }
}

@Composable
private fun Library(state: PhoneState, onBook: (String) -> Unit) {
    val list = rememberScalingLazyListState()
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list) {
        item { ListHeader { Text("Started") } }
        if (state.started.isEmpty()) item { Text("Books you start show here.", textAlign = TextAlign.Center, color = Color.White.copy(alpha = .7f)) }
        items(state.started) { (id, title) ->
            Chip(onClick = { onBook(id) }, label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.fillMaxWidth(),
                colors = if (id == state.bookId) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors())
        }
    }
}

@Composable
private fun Chapters(state: PhoneState, onChapter: (Int) -> Unit) {
    // Opens on the chapter playing now.
    val list = rememberScalingLazyListState(initialCenterItemIndex = state.chapterIndex + 1)
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list) {
        item { ListHeader { Text("Chapters") } }
        if (state.chapters.isEmpty()) item { Text("This book has no chapters.", textAlign = TextAlign.Center) }
        itemsIndexed(state.chapters) { index, title ->
            val start = state.chapterStarts.getOrNull(index)
            Chip(onClick = { onChapter(index) }, label = { Text(title.ifBlank { "Chapter ${index + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (start != null) ({ Text(clock(start)) }) else null, modifier = Modifier.fillMaxWidth(),
                colors = if (index == state.chapterIndex) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors())
        }
    }
}

@Composable
private fun Choices(title: String, choices: List<Pair<String, String>>, selected: String?, onChoose: (String) -> Unit) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader { Text(title) } }
        items(choices) { (label, value) ->
            Chip(onClick = { onChoose(value) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                colors = if (label == selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors())
        }
    }
}

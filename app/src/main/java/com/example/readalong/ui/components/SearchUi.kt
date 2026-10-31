package com.example.readalong.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch

/** One search look for the library, ReadAlong and read-only pages. Motion follows the reader transition curve. */
internal val SearchEasing = CubicBezierEasing(.2f, 0f, .1f, 1f)

/** Outlined in the accent color; grows in from 85% width and fades in when shown, then takes focus. */
@Composable
fun ReadAlongSearchField(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, accent: Color, modifier: Modifier = Modifier,
    placeholder: String = "Search for a word or sentence…", testTag: String = "search_field", autoFocus: Boolean = true) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = SearchEasing)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    val content = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)
    Row(modifier.graphicsLayer { scaleX = .85f + .15f * appear.value; alpha = appear.value }
        .heightIn(min = 56.dp).clip(shape).background(MaterialTheme.colorScheme.surface).border(2.dp, accent, shape).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = content.copy(alpha = .5f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), maxLines = 1)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).testTag(testTag), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = content, fontSize = 18.sp), cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        }
        IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close search" }) {
            // Drawn, not a "×" glyph: font metrics put the glyph below the optical centre.
            CloseGlyph(content.copy(alpha = .75f))
        }
    }
}

/** Thin indeterminate line shown while a search runs. */
@Composable
fun SearchProgressLine(active: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "Search progress")
    if (shown <= 0f) { Spacer(modifier.height(4.dp)); return }
    val sweep by rememberInfiniteTransition(label = "Searching").animateFloat(-.35f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "Sweep")
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).graphicsLayer { alpha = shown }.clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = .14f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(.35f).graphicsLayer { translationX = sweep * constraints.maxWidth }.background(accent))
    }
}

/** "1 RESULT" / "12 RESULTS" in small capitals. */
@Composable
fun SearchCountLabel(count: Int, modifier: Modifier = Modifier, capped: Boolean = false) {
    val label = (if (capped) "$count+" else "$count") + if (count == 1) " result" else " results"
    Text(label.uppercase(), modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .6.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
}

/** A result card: the passage in the reader's font with the match in bold accent color, then where it is. Rises in, staggered. */
@Composable
fun SearchResultCard(text: AnnotatedString, meta: String, index: Int, fontFamily: FontFamily?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(40L * index.coerceAtMost(6)); appear.animateTo(1f, tween(250, easing = SearchEasing)) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) .97f else 1f, tween(120), label = "Result press")
    val shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium)
    Column(modifier.fillMaxWidth().graphicsLayer {
            alpha = appear.value; translationY = (1f - appear.value) * 20.dp.toPx(); scaleX = press; scaleY = press
        }.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))
        .clickable(interaction, null, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(text, style = TextStyle(fontFamily = fontFamily, fontSize = 18.sp, lineHeight = 26.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
        Text(meta, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
    }
}

/** Matches in bold accent color (no filled background), as in the search cards. */
fun highlightMatches(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val needle = query.trim()
    if (needle.isNotEmpty()) {
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), at, at + needle.length)
            from = at + needle.length
        }
    }
}

/** A drawn X, optically centred (a "×" glyph sits low). [description] labels it when it isn't inside a labelled button. */
@Composable
fun CloseGlyph(color: Color, description: String? = null) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val w = 2.dp.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/**
 * The library search bar. Opening: a pill the size of the search icon springs out leftwards from under the icon
 * with a small bounce. Closing is one quick move with no bounce: the bar shrinks back towards the icon and fades as it goes.
 * [anchor] is the search icon's centre in this bar's coordinates (null until measured).
 */
@Composable
fun SpringSearchBar(visible: Boolean, anchor: androidx.compose.ui.geometry.Offset?, query: String, onQuery: (String) -> Unit, onClose: () -> Unit,
    accent: Color, modifier: Modifier = Modifier, placeholder: String = "Search", testTag: String = "search_field", topGap: androidx.compose.ui.unit.Dp = 0.dp) {
    val stretch = remember { Animatable(if (visible) 1f else 0f) }
    val lift = remember { Animatable(if (visible) 0f else 1f) }   // 1 = sitting on the icon
    val fade = remember { Animatable(if (visible) 1f else 0f) }   // the bar itself
    val text = remember { Animatable(if (visible) 1f else 0f) }
    val space = remember { Animatable(if (visible) 1f else 0f) }  // fraction of the bar's height the layout gives it
    var shown by remember { mutableStateOf(visible) }
    var closing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            closing = false
            if (!shown) { stretch.snapTo(0f); lift.snapTo(1f); fade.snapTo(0f); text.snapTo(0f); space.snapTo(0f) }
            shown = true
            kotlinx.coroutines.coroutineScope {
                launch { space.animateTo(1f, tween(420, easing = SearchEasing)) }
                launch { fade.animateTo(1f, tween(60)) }
                launch { lift.animateTo(0f, tween(230, easing = SearchEasing)) }
                launch { stretch.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .58f, stiffness = 360f)) }
                launch { kotlinx.coroutines.delay(300); text.animateTo(1f, tween(260, easing = SearchEasing)) }
                launch { kotlinx.coroutines.delay(120); runCatching { focus.requestFocus() } }
            }
        } else if (shown) {
            closing = true
            // One quick move, no second step: the bar shrinks back towards the icon and fades as it goes,
            // while the text vanishes first and the list closes the gap in step.
            kotlinx.coroutines.coroutineScope {
                val quick = CubicBezierEasing(.3f, 0f, .1f, 1f)
                launch { text.animateTo(0f, tween(80)) }
                launch { stretch.animateTo(0f, tween(190, easing = quick)) }
                launch { kotlinx.coroutines.delay(50); fade.animateTo(0f, tween(140, easing = LinearEasing)) }
                launch { space.animateTo(0f, tween(190, easing = quick)) }
            }
            shown = false; closing = false
        }
    }
    if (!shown) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barH = with(density) { 56.dp.toPx() }
    val gap = with(density) { topGap.toPx() }
    val content = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(50)
    Box(modifier.fillMaxWidth().layout { m, c ->
        val w = c.maxWidth.toFloat()
        val ax = anchor?.x ?: (w - barH / 2)
        val ay = (anchor?.y ?: -barH * .7f) - gap
        val l0 = ax - barH / 2; val r0 = ax + barH / 2
        val s = stretch.value
        val left = l0 * (1f - s); val right = r0 + (w - r0) * s
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed((right - left).toInt().coerceAtLeast(1), barH.toInt()))
        layout(c.maxWidth, ((barH + gap) * space.value).toInt()) {
            p.placeWithLayer(left.toInt(), gap.toInt()) {
                translationY = (ay - barH / 2) * lift.value * lift.value
                alpha = fade.value
            }
        }
    }.clip(shape).background(MaterialTheme.colorScheme.surface).border(2.dp, accent, shape).padding(start = 18.dp)) {
        Row(Modifier.fillMaxSize().graphicsLayer { alpha = text.value }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(placeholder, color = content.copy(alpha = .5f), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp), maxLines = 1, softWrap = false)
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus).testTag(testTag), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = content, fontSize = 18.sp), cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            }
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = "Close search" }) { CloseGlyph(content.copy(alpha = .75f)) }
        }
    }
}

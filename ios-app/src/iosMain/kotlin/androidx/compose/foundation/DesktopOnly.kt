package androidx.compose.foundation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.withTimeoutOrNull

// The desktop's hover tooltips and right-click menus, for the shared screens on iPhone: there is no hover, so a
// tooltip is left out (the controls it explains have their own labels), and a context menu opens on a long press.

class TooltipPlacement
@Composable
fun TooltipArea(tooltip: @Composable () -> Unit, modifier: Modifier = Modifier, delayMillis: Int = 500,
    tooltipPlacement: TooltipPlacement? = null, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

class ContextMenuItem(val label: String, val onClick: () -> Unit)

@Composable
fun ContextMenuArea(items: () -> List<ContextMenuItem>, enabled: Boolean = true, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    Box(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        // Watches in the Initial pass without consuming, so taps still reach the content; a press held past the
        // long-press time opens the menu, and the rest of that press is consumed so it doesn't also tap.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var ended = false
            withTimeoutOrNull(timeout) {
                while (!ended) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.all { !it.pressed } ||
                        event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) ended = true
                }
            }
            if (!ended) {
                open = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.all { !it.pressed }) break
                }
            }
        }
    }) {
        content()
        if (open) DropdownMenu(true, { open = false }) {
            items().forEach { item -> DropdownMenuItem({ Text(item.label) }, { open = false; item.onClick() }) }
        }
    }
}

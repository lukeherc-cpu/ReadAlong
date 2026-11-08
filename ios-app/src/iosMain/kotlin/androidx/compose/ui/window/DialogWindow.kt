package androidx.compose.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

// The desktop opens some lists (chapters and bookmarks) in their own window; on iPhone they open as a dialog
// that fits the screen.

class DialogState(val size: DpSize)

@Composable
fun rememberDialogState(size: DpSize = DpSize(400.dp, 300.dp)) = remember { DialogState(size) }

interface DialogWindowScope
private object Scope : DialogWindowScope

@Composable
fun DialogWindow(onCloseRequest: () -> Unit, state: DialogState = rememberDialogState(), visible: Boolean = true, title: String = "",
    content: @Composable DialogWindowScope.() -> Unit) {
    if (!visible) return
    Dialog(onDismissRequest = onCloseRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth(.94f).widthIn(max = state.size.width).fillMaxHeight(.86f).heightIn(max = state.size.height).clip(RoundedCornerShape(24.dp))) {
            Scope.content()
        }
    }
}

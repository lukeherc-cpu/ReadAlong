package com.example.readalong.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/**
 * The app's list pop-up: a bottom sheet with a title (and optional quiet meta on the right) over
 * full-width [SheetRow]s. Swiping down, Back or a tap outside closes it, so it has no Close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    meta: String? = null,
    listState: LazyListState = rememberLazyListState(),
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            if (title != null) Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                Text(title, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.titleLarge)
                if (meta != null) Text(meta, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            header?.invoke(this)
            LazyColumn(Modifier.fillMaxWidth(), state = listState, content = content)
        }
    }
}

/**
 * One row in an [ActionSheet]: optional icon, a label with an optional second line, and quiet meta
 * (a time, a count) on the right. [selected] marks the current choice with a tint and a tick; pass
 * it only for rows that are choices, so screen readers hear them as one.
 */
@Composable
fun SheetRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    supporting: String? = null,
    meta: String? = null,
    selected: Boolean? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    weight: FontWeight? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val on = selected == true
    val ink = when { destructive -> colors.error; on -> colors.primary; else -> colors.onSurface }
    val muted = when { destructive -> colors.error; on -> colors.primary; else -> colors.onSurfaceVariant }
    val press = if (selected != null) Modifier.selectable(on, enabled, role = Role.RadioButton, onClick = onClick)
        else Modifier.clickable(enabled, role = Role.Button, onClick = onClick)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (supporting != null) 64.dp else 52.dp)
            .background(if (on) colors.primary.copy(alpha = .12f) else Color.Transparent)
            .then(press).alpha(if (enabled) 1f else .38f)
            .padding(start = 24.dp, end = if (trailing != null) 12.dp else 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(22.dp), tint = muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = ink, fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (meta != null) Text(meta, style = MaterialTheme.typography.bodyMedium, color = muted)
        if (on) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(22.dp), tint = colors.primary)
        trailing?.invoke()
    }
}

/** A quiet line between groups of [SheetRow]s. */
@Composable
fun SheetDivider() = HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))

package com.example.readalong.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Reference-style rounded card; all copy and controls remain native, accessible elements. */
@Composable
fun IllustratedDialog(title: String, onDismiss: () -> Unit, artwork: (@Composable () -> Unit)? = null, centered: Boolean = false,
    actions: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val colors = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, colors.outline.copy(alpha = .15f))) {
            Column(Modifier.background(Brush.linearGradient(listOf(colors.surfaceVariant, colors.surface))).padding(22.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                artwork?.invoke()
                Text(title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

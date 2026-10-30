package com.example.readalong.ui.reading

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/** The knob and the audio controls below move on one clock, so switching modes reads as one gesture. */
internal const val ModeSwitchMillis = 260

@Composable
internal fun ReaderModeSwitch(bookMode: Boolean, select: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large)
    val x by animateDpAsState(if (bookMode) 0.dp else 52.dp, tween(ModeSwitchMillis, easing = ReaderMotionEasing), label = "Reading mode selection")
    Box(Modifier.width(104.dp).height(44.dp).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .7f), shape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .2f), shape).selectableGroup().clickable { select(!bookMode) }) {
        Box(Modifier.offset(x = x).size(52.dp, 44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), shape))
        Row {
            listOf(true, false).forEach { book ->
                Box(Modifier.size(52.dp, 44.dp).selectable(selected = bookMode == book, role = Role.Tab, onClick = { select(!bookMode) }), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (book) R.drawable.ic_read_only else R.drawable.ic_readalong), if (book) "Read only" else "ReadAlong",
                        Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (bookMode == book) 1f else .5f))
                }
            }
        }
    }
}

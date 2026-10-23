package com.example.readalong.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

@Composable
fun MarqueeTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleSmall) {
    Text(text, modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 1200),
        style = style, maxLines = 1, softWrap = false)
}

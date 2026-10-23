package com.example.readalong.ui.reading

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle

/** Paint-only emphasis: keeps the EPUB's font metrics, weight, italics and line breaks. */
internal fun readingHighlight(color: Color) = SpanStyle(
    color = color,
    shadow = Shadow(color, Offset(0.6f, 0f), 0.25f)
)

package com.example.readalong.ui.reading

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import com.example.readalong.domain.model.TranscriptSegment

/** Preview and reader use the same paragraph and inline typography. */
internal fun paragraphText(paragraph: TranscriptSegment, family: String, useEpubFonts: Boolean,
    epubFonts: Map<String, FontFamily>, focus: Pair<Int, Int>?, ink: Color, highlightBackground: Color = Color.Transparent) = buildAnnotatedString {
    append(paragraph.text)
    paragraph.formatting?.styles?.forEach { span ->
        if (span.start >= 0 && span.end <= length && span.end > span.start) addStyle(SpanStyle(
            fontFamily = if (paragraph.imported && useEpubFonts && family == "EPUB") span.fontFamily?.let { name -> epubFonts[name] ?: when (name) {
                "sans-serif" -> FontFamily.SansSerif; "monospace" -> FontFamily.Monospace; "serif" -> FontFamily.Serif; else -> null
            } } else null,
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = if (span.underline) TextDecoration.Underline else null), span.start, span.end)
    }
    if (focus != null && focus.second > focus.first) addStyle(readingHighlight(ink).copy(background = highlightBackground), focus.first, focus.second)
}

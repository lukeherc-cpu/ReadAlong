package com.example.readalong.ui.reading

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import com.example.readalong.domain.model.TranscriptSegment

/** Preview and reader use the same paragraph and inline typography. */
internal fun paragraphText(paragraph: TranscriptSegment, family: String, useEpubFonts: Boolean,
    epubFonts: Map<String, FontFamily>, focus: Pair<Int, Int>?, ink: Color, highlightBackground: Color = Color.Transparent,
    fade: SentenceFade? = null, base: Color = Color.Unspecified) = buildAnnotatedString {
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
    // With a fade, the outgoing sentence dims while the incoming one brightens over the same 120 ms.
    fun emphasise(range: Pair<Int, Int>?, amount: Float) {
        if (range == null || range.second <= range.first || range.second > length || amount <= 0f) return
        val color = if (amount >= 1f || base == Color.Unspecified) ink else androidx.compose.ui.graphics.lerp(base, ink, amount)
        val background = if (highlightBackground == Color.Transparent) highlightBackground else highlightBackground.copy(alpha = highlightBackground.alpha * amount)
        addStyle(readingHighlight(color).copy(background = background), range.first, range.second)
    }
    if (fade == null) emphasise(focus, 1f)
    else if (fade.current != focus) emphasise(fade.current, 1f) // the hand-off starts next frame; keep the old one lit
    else {
        if (fade.previous != focus) emphasise(fade.previous, 1f - fade.progress)
        emphasise(focus, fade.progress)
    }
}

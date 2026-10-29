package com.example.readalong.desktop.reading

import com.example.readalong.desktop.core.LibraryBook
import com.example.readalong.domain.alignment.EpubAligner
import com.example.readalong.domain.epub.EpubParser
import com.example.readalong.domain.epub.ParagraphFormatter
import com.example.readalong.domain.model.PassageFormatting
import com.example.readalong.domain.model.TranscriptAnchor
import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.reading.SentenceCue
import com.example.readalong.domain.reading.estimatedGapAnchors
import com.example.readalong.domain.reading.sentenceCues
import com.example.readalong.domain.reading.timedAnchorWords
import java.io.File

/** A paragraph of the reader with its sentences' audio times. */
data class ReadingParagraph(val segment: TranscriptSegment, val cues: List<SentenceCue>) {
    val heading get() = segment.formatting?.kind?.startsWith("H") == true
    val startMs: Long? = cues.mapNotNull { it.audioStartMs }.minOrNull()
    val endMs: Long? = cues.mapNotNull { it.audioEndMs }.maxOrNull()
}
data class ContentsEntry(val title: String, val paragraph: Int, val startMs: Long?)

/** Everything the reader shows for one book: generated from the transcript, or an EPUB matched to it. */
class ReadingText(val paragraphs: List<ReadingParagraph>, val sources: Map<String, TranscriptSegment>,
    val contents: List<ContentsEntry>, val fromEpub: Boolean, val coverage: Float?) {
    private val timed = paragraphs.withIndex().filter { it.value.startMs != null }.map { it.value.startMs!! to it.index }

    /** The paragraph being read at [positionMs]: the last one that started by then. */
    fun paragraphAt(positionMs: Long): Int {
        var low = 0; var high = timed.lastIndex; var found = -1
        while (low <= high) { val mid = (low + high) ushr 1; if (timed[mid].first <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1 }
        return if (found < 0) -1 else timed[found].second
    }
    val isEmpty get() = paragraphs.none { !it.heading }

    companion object {
        val Empty = ReadingText(emptyList(), emptyMap(), emptyList(), false, null)
    }
}

object BookTextBuilder {
    /** The transcript as paragraphs (the phone's ParagraphFormatter), with a heading at each audio chapter. */
    fun generated(book: LibraryBook, segments: List<TranscriptSegment>): ReadingText {
        val paragraphs = mutableListOf<TranscriptSegment>()
        val contents = mutableListOf<ContentsEntry>()
        var chapter = -2
        fun heading(index: Int) {
            val title = book.chapters.getOrNull(index)?.title ?: return
            contents += ContentsEntry(title, paragraphs.size, book.chapters[index].startMs)
            paragraphs += TranscriptSegment("h:$index", book.id, "c$index", -1, -1, title, formatting = PassageFormatting("H2", emptyList(), false))
        }
        val formatter = ParagraphFormatter { group ->
            val first = group.first()
            val at = book.chapterAt(first.startMs)
            if (at != chapter) { if (at >= 0) heading(at); chapter = at }
            val text = StringBuilder(); val anchors = mutableListOf<TranscriptAnchor>()
            group.forEach { segment ->
                if (text.isNotEmpty()) text.append(' ')
                val start = text.length
                text.append(segment.text.trim())
                anchors += TranscriptAnchor(segment.id, segment.startMs, segment.endMs, start, text.length, 0, segment.text.trim().length)
            }
            paragraphs += TranscriptSegment(first.id, book.id, first.chapterId, first.startMs, group.last().endMs, text.toString(), anchors = anchors)
        }
        // A new chapter always starts a new paragraph.
        var previousChapter: Int? = null
        segments.forEach { segment ->
            val c = book.chapterAt(segment.startMs)
            if (previousChapter != null && c != previousChapter) formatter.flush()
            previousChapter = c
            formatter.add(segment)
        }
        formatter.flush()
        val sources = segments.associateBy { it.id }
        return ReadingText(paragraphs.map { ReadingParagraph(it, if (it.startMs < 0) emptyList() else sentenceCues(it, sources)) }, sources, contents, false, null)
    }

    /** The EPUB's own text, matched to the transcript with the phone's EpubAligner. */
    fun epub(book: LibraryBook, epub: File, segments: List<TranscriptSegment>, checkpoint: () -> Unit = {}): ReadingText {
        val parsed = EpubParser().parse(epub, checkpoint)
        val sources = segments.associateBy { it.id }
        val result = if (segments.isEmpty()) null else EpubAligner().align(parsed.blocks, segments.map { it.copy(words = emptyList()) }, checkpoint)
        val paragraphs = parsed.blocks.mapIndexed { index, block ->
            val anchors = result?.blocks?.getOrNull(index)?.anchors.orEmpty().mapNotNull { anchor ->
                // Narrow each matched passage to the times of the words actually shown (as the phone does).
                val source = sources[anchor.id]
                val words = timedAnchorWords(block.text, anchor, source).map { it.second }
                if (words.isNotEmpty()) anchor.copy(startMs = words.minOf { it.startMs }, endMs = words.maxOf { it.endMs }).takeIf { it.endMs > it.startMs }
                else anchor.takeIf { source?.words.isNullOrEmpty() }
            }
            TranscriptSegment("epub:${block.ordinal}", book.id, "epub:${block.spine}", anchors.minOfOrNull { it.startMs } ?: -1,
                anchors.maxOfOrNull { it.endMs } ?: -1, block.text, anchors = anchors,
                formatting = PassageFormatting(block.kind, block.styles, block.centered), imported = true)
        }
        var cues = paragraphs.map { sentenceCues(it, sources) }
        // Sentences between two matched ones get estimated times, so the highlight doesn't stall on a misheard passage.
        val estimates = estimatedGapAnchors(paragraphs, cues)
        val completed = paragraphs.map { p -> estimates[p.id]?.let { p.copy(anchors = p.anchors + it) } ?: p }
        cues = completed.mapIndexed { i, p -> if (p.id in estimates) sentenceCues(p, sources) else cues[i] }
        val reading = completed.mapIndexed { i, p -> ReadingParagraph(p, cues[i]) }
        val contents = parsed.contents.mapNotNull { entry ->
            val index = paragraphs.indexOfFirst { it.id == "epub:${entry.ordinal}" }.takeIf { it >= 0 } ?: return@mapNotNull null
            ContentsEntry(entry.title, index, reading.drop(index).firstNotNullOfOrNull { it.startMs })
        }
        return ReadingText(reading, sources, contents, true, result?.coverage)
    }
}

package com.example.readalong.domain

import com.example.readalong.domain.model.*
import com.example.readalong.domain.reading.*
import org.junit.Assert.*
import org.junit.Test

class EstimatedSentenceTimingTest {
    private fun paragraph(id: String, text: String, start: Long? = null, end: Long? = null) =
        TranscriptSegment(id, "book", "chapter", start ?: -1, end ?: -1, text, imported = true,
            anchors = if (start == null) emptyList() else listOf(TranscriptAnchor("MANUAL:$id", start, end!!, 0, text.length)))
    private fun fill(rows: List<TranscriptSegment>): List<TranscriptSegment> {
        val estimates = estimatedGapAnchors(rows, rows.map { sentenceCues(it) })
        return rows.map { it.copy(anchors = it.anchors + estimates[it.id].orEmpty()) }
    }
    @Test fun unknownStartsExactlyAtPreviousEndInAllTapLocationsAndYieldsToNext() {
        val rows = listOf(paragraph("a", "A known sentence.", 1000, 3000), paragraph("b", "The unrecognized sentence."),
            paragraph("c", "The next known sentence.", 7000, 9000))
        val result = fill(rows)
        assertEquals(rows[0], result[0]); assertEquals(rows[2], result[2])
        val cue = sentenceCues(result[1]).single()
        assertTrue(cue.estimated); assertEquals(3000L, cue.audioStartMs); assertEquals(7000L, cue.audioEndMs)
        val source = rows[0].copy(words = listOf(TranscriptWord("next", 3000, 3200)))
        for (offset in 0 until rows[1].text.length) assertEquals(3000L, sentenceSeekStart(sentenceAt(listOf(cue), offset)!!, listOf(source)))
        assertTrue(cue.activeAt(3000)); assertTrue(cue.activeAt(6999)); assertFalse(cue.activeAt(7000))
    }
    @Test fun dividesMultipleUnknownSentencesAcrossParagraphsWithoutGapsOrDriftingKnownTimes() {
        val rows = listOf(paragraph("a", "Known.", 1000, 3000), paragraph("b", "One unknown. Another unknown sentence."),
            paragraph("c", "Third unknown."), paragraph("d", "Known again.", 15000, 17000))
        val filled = fill(rows)
        val cues = filled.flatMap { sentenceCues(it) }.filter { it.estimated }
        assertEquals(3, cues.size); assertEquals(3000L, cues.first().audioStartMs); assertEquals(15000L, cues.last().audioEndMs)
        cues.zipWithNext().forEach { (a,b) -> assertEquals(a.audioEndMs, b.audioStartMs) }
        assertEquals(rows.first(), filled.first()); assertEquals(rows.last(), filled.last())
    }
    @Test fun noGuessesOutsideTwoBoundsAcrossChaptersOrForLargeOrReversedGaps() {
        val a = paragraph("a", "Known.", 1000, 3000); val b = paragraph("b", "Unknown."); val c = paragraph("c", "Next.", 7000, 9000)
        for (rows in listOf(listOf(a,b), listOf(b,c), listOf(a,b,c.copy(chapterId="other")),
            listOf(a,b,paragraph("c","Next.", 80000,90000)), listOf(a,b,paragraph("c","Next.", 2000,2500)),
            listOf(a,b.copy(formatting=PassageFormatting("H1", emptyList(), false)),c))) {
            assertTrue(estimatedGapAnchors(rows,rows.map { sentenceCues(it) }).isEmpty())
        }
    }
    @Test fun preciseOpeningAutomaticallyReplacesAnOldEstimate() {
        val source = TranscriptSegment("raw","book","chapter",4000,5000,"Unknown.",words=listOf(TranscriptWord("Unknown.",4000,5000)))
        val p=paragraph("p","Unknown.").copy(anchors=listOf(TranscriptAnchor("raw",4000,5000,0,8),
            TranscriptAnchor("estimate",3000,7000,0,8,estimated=true)))
        val cue=sentenceCues(p,mapOf("raw" to source)).single()
        assertFalse(cue.estimated);assertEquals(4000L,cue.audioStartMs)
    }
}

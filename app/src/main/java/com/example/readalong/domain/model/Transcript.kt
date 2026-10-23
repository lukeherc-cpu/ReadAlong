package com.example.readalong.domain.model

data class TranscriptSegment(val id: String, val bookId: String, val chapterId: String,
    val startMs: Long, val endMs: Long, val text: String, val confidence: Float? = null,
    val words: List<TranscriptWord> = emptyList(), val anchors: List<TranscriptAnchor> = emptyList(),
    val formatting: PassageFormatting? = null, val imported: Boolean = false)
data class PassageFormatting(val kind: String, val styles: List<com.example.readalong.domain.epub.EpubTextStyle>, val centered: Boolean)
data class TranscriptAnchor(val id: String, val startMs: Long, val endMs: Long, val start: Int, val end: Int,
    val sourceStart: Int? = null, val sourceEnd: Int? = null, val estimated: Boolean = false)
data class TranscriptWord(val word: String, val startMs: Long, val endMs: Long, val confidence: Float? = null)

/** Half-open intervals: boundaries, gaps and unprocessed audio never highlight the wrong passage. */
class TranscriptIndex(val segments: List<TranscriptSegment>) {
    init {
        segments.forEachIndexed { index, segment ->
            require(segment.startMs >= 0 && segment.endMs > segment.startMs && segment.text.isNotBlank())
            require(index == 0 || segments[index - 1].endMs <= segment.startMs)
        }
    }
    fun at(positionMs: Long): TranscriptSegment? {
        var low = 0; var high = segments.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val item = segments[middle]
            when {
                positionMs < item.startMs -> high = middle - 1
                positionMs >= item.endMs -> low = middle + 1
                else -> return item
            }
        }
        return null
    }
    fun audioPosition(segmentId: String): Long? = segments.firstOrNull { it.id == segmentId }?.startMs
}

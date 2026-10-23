package com.example.readalong.data.reading

import com.example.readalong.domain.model.TranscriptSegment

/** Original prose, synthetic timing, demo books only. This is never a user's transcription. */
object DemoTranscript {
    private val passages = listOf(
        "The gate to the orchard opened with a quiet click. Mara lifted her lantern and stepped onto the narrow path between the trees.",
        "All day the branches had been still. Now, under the first stars, their leaves stirred as though someone had called them by name.",
        "She stopped beside the oldest tree. A small wooden box rested between its roots, wrapped in a ribbon the color of rain.",
        "Inside lay a map, a brass key, and a note written in her grandfather's careful hand. The map showed a footbridge that no one in the village remembered.",
        "Mara folded the note into her pocket. Beyond the wall, the river moved through the dark, carrying the distant sound of a bell.",
        "For the first time that evening, she smiled. There was still a path to follow, and enough light to take the next step.",
        "At the riverbank, the grass was silver with dew. Mara found the footbridge exactly where the map had promised, hidden behind a curtain of willow leaves.",
        "The bridge creaked beneath her boots. Halfway across, she turned to look back at the orchard, where her lantern had left a little trail of warmth.",
        "A window shone on the opposite bank. The house around it was smaller than she expected, with a blue door and a roof patched in three different colors.",
        "The brass key fitted the lock. Inside, shelves held notebooks, jars of seeds, and a clock whose hands had stopped at sunrise.",
        "On the table stood another lantern. Beside it was an unfinished letter, addressed to whoever would come looking when the orchard began to wake.",
        "Mara sat down and read the first line slowly. It was an invitation to notice the small things that others hurried past.",
        "By morning the rain had cleared. She returned to the village with the notebooks carefully wrapped and the map tucked into her coat.",
        "At the market, she bought paper and a new bottle of ink. The shopkeeper asked if she had found what she was looking for.",
        "Not all of it, Mara said. But enough to begin. She carried her purchases home along the river, listening to the waking birds.",
        "That afternoon she planted the first seeds. She marked each row with a name from the notebooks and left room for the names she had yet to learn.",
        "The orchard would take its own time. Some stories, she thought, could be heard only by staying long enough for the next quiet part.",
        "When evening came, she set the lantern in the window. Across the river, another small light answered."
    )
    fun segments(bookId: String): List<TranscriptSegment> {
        require(bookId.startsWith("demo-")) { "Synthetic transcripts are restricted to fictional sample books." }
        return passages.mapIndexed { index, text -> TranscriptSegment("$bookId-sample-$index", bookId,
            "sample-${index / 6 + 1}", index * 12_000L, (index + 1) * 12_000L, text) }
    }
}

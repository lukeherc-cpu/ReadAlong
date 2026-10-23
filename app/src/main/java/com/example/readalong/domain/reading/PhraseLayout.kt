package com.example.readalong.domain.reading

/** Whole-word excerpts measured against the actual available width. Original offsets are retained. */
fun fittingPhrases(text: String, fits: (String) -> Boolean): List<IntRange> {
    val words = Regex("\\S+").findAll(text).toList()
    if (words.isEmpty()) return emptyList()
    val result = mutableListOf<IntRange>()
    var start = words.first().range.first
    var end = start
    for (word in words) {
        val next = word.range.last + 1
        if (end > start && !fits(text.substring(start, next))) {
            result += start until end
            start = word.range.first
        }
        end = next
    }
    result += start until end
    return result
}

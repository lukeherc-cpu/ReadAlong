package com.example.readalong.data.transcription

import com.example.readalong.domain.model.TranscriptSegment
import com.example.readalong.domain.transcription.TranscriptionInput
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** Adapt actual attention word times to the existing canonical timeline; never space words by text length. */
object WhistleResult {
    fun segments(json: String, input: TranscriptionInput): List<TranscriptSegment> {
        val result = JSONObject(json)
        val text = result.getString("text").trim()
        if (text.isEmpty()) return emptyList()
        val words = result.getJSONArray("words")
        require(words.length() in 1..5000) { "Whistle returned text without usable word timestamps." }
        val duration = input.endMs - input.startMs
        val tokens = JSONArray()
        var previous = 0L
        for (i in 0 until words.length()) {
            val word = words.getJSONObject(i)
            val rawStart = word.getDouble("start"); val rawEnd = word.getDouble("end")
            require(rawStart.isFinite() && rawEnd.isFinite() && rawStart >= 0 && rawEnd >= rawStart)
            val start = (rawStart * 1000).roundToLong()
            val end = (rawEnd * 1000).roundToLong().coerceAtMost(duration)
            require(start >= previous) { "Whistle word timestamps are out of order." }
            previous = start
            // Never save padding/lookahead words beyond the decoded audio window.
            if (start >= duration) continue
            require(end >= start)
            tokens.put(JSONObject().put("text", " " + word.getString("word").trim()).put("startMs", start)
                .put("endMs", end).put("dtwMs", start).apply {
                    val confidence = word.optDouble("probability", Double.NaN)
                    if (confidence.isFinite()) put("confidence", confidence)
                })
        }
        if (tokens.length() == 0) return emptyList()
        val row = JSONObject().put("text", text).put("startMs", tokens.getJSONObject(0).getLong("startMs"))
            .put("endMs", tokens.getJSONObject(tokens.length()-1).getLong("endMs")).put("tokens", tokens)
        val normalized = TimedRecognitionResult.segments(JSONArray().put(row).toString(), input).singleOrNull() ?: return emptyList()
        var cursor = 0
        val located = normalized.words.map { word ->
            val offset = text.indexOf(word.word, cursor, ignoreCase = true)
            require(offset >= cursor) { "Whistle's word list doesn't correspond to its transcript." }
            cursor = offset + word.word.length
            offset to word
        }
        // A long chunk can span several EPUB paragraphs or dramatized omissions. Match
        // recognized sentences independently, retaining each word's actual acoustic time.
        return com.example.readalong.domain.reading.sentenceRanges(text).mapNotNull { range ->
            val spoken = located.filter { it.first in range }.map { it.second }
            val start = spoken.firstOrNull()?.startMs ?: return@mapNotNull null
            val end = spoken.last().endMs
            if (end <= start) return@mapNotNull null
            TranscriptSegment("${input.bookId}:${input.chapterId}:$start", input.bookId, input.chapterId,
                start, end, text.substring(range), spoken.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat(), spoken)
        }
    }
}

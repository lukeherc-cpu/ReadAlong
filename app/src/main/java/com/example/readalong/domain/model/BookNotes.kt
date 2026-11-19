package com.example.readalong.domain.model

import java.util.Locale

/** What moved the listening position away from where it was. */
enum class JumpKind(val label: String) {
    SKIP_BACK("Before you rewound"),
    SKIP_FORWARD("Before you skipped forward"),
    CHAPTER("Before you changed chapter"),
    SEEK("Before you jumped"),
    SENTENCE("Before you tapped a sentence"),
    BOOKMARK("Before you went to a bookmark"),
    HISTORY("Before you went back in history"),
}

/** One place the listener was, saved just before a jump, so it can be returned to. */
data class HistoryEntry(val positionMs: Long, val kind: JumpKind, val chapterTitle: String, val savedAt: Long)

object ListeningHistory {
    const val LIMIT = 10
    /** Skips (or sentence taps) this close together are one jump: the first entry keeps where the listener started. */
    const val SKIP_RUN_MS = 8_000L

    /** [entries] (newest first) with [entry] added; repeated skips merge, and only the last [LIMIT] are kept. */
    fun add(entries: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> {
        val last = entries.firstOrNull()
        // Taps on several sentences in a row, like a run of skips, are one jump from where the listener was.
        val skip = entry.kind == JumpKind.SKIP_BACK || entry.kind == JumpKind.SKIP_FORWARD || entry.kind == JumpKind.SENTENCE
        if (last != null && skip && last.kind == entry.kind && entry.savedAt - last.savedAt in 0..SKIP_RUN_MS)
            return listOf(last.copy(savedAt = entry.savedAt)) + entries.drop(1)
        return (listOf(entry) + entries).take(LIMIT)
    }

    fun encode(entries: List<HistoryEntry>) = entries.joinToString("\n") { listOf(it.savedAt, it.positionMs, it.kind.name, clean(it.chapterTitle)).joinToString("\t") }

    fun decode(text: String?): List<HistoryEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4) return@mapNotNull null
        val kind = JumpKind.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
        HistoryEntry(parts[1].toLongOrNull() ?: return@mapNotNull null, kind, parts[3], parts[0].toLongOrNull() ?: return@mapNotNull null)
    }.take(LIMIT).toList()
}

/** A person in the book's Character List: written by the listener, or added from a suggestion. */
data class CharacterEntry(val id: String, val name: String, val note: String = "", val firstMs: Long? = null)

object CharacterList {
    fun encode(entries: List<CharacterEntry>) = entries.joinToString("\n") { listOf(it.id, clean(it.name), clean(it.note), it.firstMs?.toString().orEmpty()).joinToString("\t") }

    fun decode(text: String?): List<CharacterEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 4 || parts[1].isBlank()) null else CharacterEntry(parts[0], parts[1], parts[2], parts[3].toLongOrNull())
    }.toList()

    /** Listed by name, so the list reads like a cast list. */
    fun sorted(entries: List<CharacterEntry>) = entries.sortedBy { it.name.lowercase(Locale.ROOT) }
}

/** A name the transcript suggests adding, with how often it came up and where it was first heard. */
data class NameSuggestion(val name: String, val count: Int, val firstMs: Long)

/**
 * Names that keep coming up in the text heard so far ([segments] as start time and text, in order).
 *
 * A name is a run of one to three capitalised words that isn't at the start of a sentence (where every
 * word is capitalised). Words that also turn up in lower case (like "Hope" and "hope") and common words
 * (days, months, titles) are left out, and a single word that is mostly part of a longer name ("Stratt"
 * in "Eva Stratt") is folded into it. Names already in [known] are skipped.
 */
fun suggestNames(segments: List<Pair<Long, String>>, known: Collection<String> = emptyList(), minCount: Int = 3, limit: Int = 12): List<NameSuggestion> {
    val counts = HashMap<String, Int>()
    val first = HashMap<String, Long>()
    val lower = HashMap<String, Int>()
    val word = Regex("[\\p{L}][\\p{L}'’-]*|[.!?…:;\"“”]")
    for ((startMs, text) in segments) {
        var sentenceStart = true
        val run = ArrayList<String>()
        fun flush() {
            if (run.isNotEmpty()) {
                val name = run.joinToString(" ")
                counts[name] = (counts[name] ?: 0) + 1
                first.putIfAbsent(name, startMs)
                run.clear()
            }
        }
        for (match in word.findAll(text)) {
            val token = match.value.trimEnd('\'', '’', '-')
            if (token.isEmpty()) continue
            if (token.length == 1 && !token[0].isLetter()) { flush(); if (token != ":" && token != ";") sentenceStart = true; continue }
            val possessive = token.removeSuffix("'s").removeSuffix("’s")
            val capital = possessive[0].isUpperCase() && possessive.drop(1).none { it.isUpperCase() }
            if (!capital) {
                flush()
                lower[possessive.lowercase(Locale.ROOT)] = (lower[possessive.lowercase(Locale.ROOT)] ?: 0) + 1
            } else if (sentenceStart || possessive.length < 2 || possessive.lowercase(Locale.ROOT) in COMMON) {
                flush()
            } else if (run.size < 3) run += possessive else { flush(); run += possessive }
            sentenceStart = false
        }
        flush()
    }
    val knownWords = known.flatMap { it.lowercase(Locale.ROOT).split(' ') }.toSet()
    fun ordinary(name: String) = name.split(' ').all { (lower[it.lowercase(Locale.ROOT)] ?: 0) * 2 >= (counts[it] ?: 1) }
    // A single word mostly heard inside a longer name counts toward that name instead.
    val merged = HashMap(counts)
    for ((name, count) in counts) if (' ' in name) for (part in name.split(' ')) merged[part]?.let { single ->
        if (single <= count * 2) { merged.remove(part); merged[name] = (merged[name] ?: count) + single; first[name] = minOf(first[name]!!, first[part]!!) }
    }
    return merged.filter { (name, count) -> count >= minCount && !ordinary(name) && name.split(' ').none { it.lowercase(Locale.ROOT) in knownWords } }
        .map { (name, count) -> NameSuggestion(name, count, first.getValue(name)) }
        .sortedWith(compareByDescending<NameSuggestion> { it.count }.thenBy { it.name })
        .take(limit)
}

private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

private val COMMON = setOf(
    "i", "i'm", "i've", "i'd", "i'll", "mr", "mrs", "ms", "dr", "sir", "madam", "lord", "lady", "god", "oh", "ok", "okay", "yes", "no",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    "english", "chapter", "part", "book", "the", "a", "an", "and", "but", "or", "so", "then", "he", "she", "they", "we", "you", "it",
)

/**
 * "Make with AI": the app asks ChatGPT, Gemini or Claude for the book's characters up to where the
 * listener is, and reads the answer back. Only the title, author and chapter leave the phone.
 */
/**
 * Where the listener is, as exactly as the app knows it. Audio parts can be hours long (Pride and Prejudice
 * comes as four), so the part alone isn't enough: the matching EPUB's chapter, how far through the book
 * they are and the last words they heard pin it down.
 */
data class ListeningPlace(
    /** The audio file or chapter playing, such as "Part 1", with its number and the count. */
    val part: String? = null, val partNumber: Int = 0, val partCount: Int = 0,
    /** Time into that part, such as "0:42:10". */
    val partElapsed: String? = null,
    /** Percent of the whole audiobook heard, when its length is known. */
    val percent: Int? = null,
    /** The matching EPUB's chapter at this point, such as "Chapter 3". */
    val bookChapter: String? = null,
    /** The book's last sentences before this point, from the EPUB or the transcript. */
    val lastHeard: String? = null,
)

object AiCharacters {
    fun prompt(title: String, author: String?, place: ListeningPlace): String {
        val by = author?.takeIf { it.isNotBlank() }?.let { " by $it" }.orEmpty()
        val part = place.part?.takeIf { it.isNotBlank() }?.let { name ->
            val count = if (place.partCount > 1) " (${place.partNumber} of ${place.partCount})" else ""
            val into = place.partElapsed?.let { ", $it in" }.orEmpty()
            "Audio: \"$name\"$count$into"
        } ?: place.partElapsed?.let { "Audio: $it in" }
        val where = listOfNotNull(
            place.bookChapter?.takeIf { it.isNotBlank() }?.let { "Book chapter: $it" },
            place.percent?.let { "About $it% of the way through the book" },
            part,
            place.lastHeard?.takeIf { it.isNotBlank() }?.let { "The last words I heard: \"$it\"" },
        )
        val cutoff = if (place.lastHeard.isNullOrBlank()) "this exact point" else "those last words"
        return buildString {
            append("I'm listening to the audiobook \"$title\"$by and want a spoiler-free list of the characters I've met so far.\n\n")
            if (where.isNotEmpty()) append("Exactly where I am:\n").append(where.joinToString("\n") { "- $it" }).append("\n\n")
            append("Strict rules:\n")
            append("- Only include characters who have appeared or been mentioned by name in the text up to $cutoff. ")
            append("Audiobook parts and chapters can be long, so leave out anyone who first appears later in this part or chapter, however important they become.\n")
            append("- If you're not sure someone has appeared yet, leave them out.\n")
            append("- Describe each person only as they are known at this point: nothing about later events, relationships, reveals or fates.\n")
            append("- Put each person on their own line as: Name — who they are and how they connect to the others, in one sentence.\n\n")
            append("Reply with the list only.")
        }
    }

    /** The end of [text], about [limit] characters, starting at a sentence where it can, for [ListeningPlace.lastHeard]. */
    fun tail(text: String, limit: Int = 320): String? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        if (clean.length <= limit) return clean
        val cut = clean.takeLast(limit)
        val sentence = Regex("[.!?…][\"'”’)]?\\s+").find(cut)?.let { it.range.last + 1 }?.takeIf { it < cut.length - 40 }
        return "…" + (if (sentence != null) cut.substring(sentence) else cut.substringAfter(' ')).trim()
    }

    private val bullet = Regex("""^\s*(?:[-*•·]+|\d+[.)])\s*""")
    /** Labels an answer sometimes adds around the list, which look like "Name: note" lines. */
    private val notNames = setOf("note", "notes", "here", "characters", "main characters", "cast", "spoilers", "spoiler warning", "summary", "also", "tip", "disclaimer")
    private val separators = listOf(" — ", " – ", " - ", ": ", " —", "— ", " –")

    /** Name and note pairs from an AI's answer, tolerating bullets, numbering and bold names. */
    fun parse(answer: String): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        return answer.lines().mapNotNull { raw ->
            val line = raw.replace("**", "").replace("__", "").replace(bullet, "").trim()
            val cut = separators.mapNotNull { sep -> line.indexOf(sep).takeIf { it > 0 }?.let { it to sep.length } }.minByOrNull { it.first } ?: return@mapNotNull null
            val name = line.substring(0, cut.first).trim().trim('"', '*', '_').removeSuffix(":").trim()
            val note = line.substring(cut.first + cut.second).trim().take(300)
            val words = name.split(Regex("\\s+"))
            if (name.length !in 2..60 || words.size > 6 || note.isEmpty() || name.none(Char::isLetter) || !name.first().isUpperCase()) return@mapNotNull null
            if (name.lowercase() in notNames) return@mapNotNull null
            if (!seen.add(name.lowercase())) null else name to note
        }
    }
}

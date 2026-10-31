package com.example.readalong.desktop.core

import com.example.readalong.domain.model.audioNameComparator
import java.io.File

/** One audiobook found on disk, before its files are probed. */
data class FoundBook(val key: String, val name: String, val folder: File?, val audio: List<File>, val epubs: List<File>, val transcripts: List<File>, val images: List<File>)

/**
 * The phone's library scan rules (AudioImporter): each subfolder with audio is a book, and its own
 * subfolders (CD1/CD2) belong to it. A subfolder without audio of its own is a shelf (an author, a
 * series) and is searched for books. Loose files in a folder are one book, except several .m4b files
 * side by side, which are one book each unless they're named "Part 1", "Part 2".
 */
object Scanner {
    val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4")
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val DISC = Regex("(?i)^(cd|disc|disk|part)\\s*[-_ ]?\\d+.*")
    private val PART = Regex("(?i)\\b(part|pt|disc|disk|cd)\\s*[-_.]?\\s*\\d+")

    fun isAudio(file: File) = file.isFile && file.extension.lowercase() in AUDIO
    private fun children(folder: File) = (folder.listFiles() ?: emptyArray()).filterNot { it.isHidden || it.name.startsWith(".") }
        .sortedWith { a, b -> audioNameComparator.compare(a.name, b.name) }

    /** A file, one book's folder, or a library folder of many books. */
    fun scan(path: File): List<FoundBook> {
        val root = path.absoluteFile
        if (root.isFile) return if (isAudio(root)) listOf(single(root, root.parentFile)) else emptyList()
        if (!root.isDirectory) return emptyList()
        val inside = children(root)
        val subfolders = inside.filter { it.isDirectory }
        if (subfolders.isNotEmpty() && subfolders.all { DISC.matches(it.name) }) return listOfNotNull(collect(root))
        val books = mutableListOf<FoundBook>()
        val loose = inside.filter(::isAudio)
        books += singleFileBooks(loose, root) ?: listOfNotNull(if (loose.isNotEmpty()) collect(root, recursive = false) else null)
        val shelves = ArrayDeque(subfolders.map { it to 1 })
        while (shelves.isNotEmpty()) {
            val (folder, depth) = shelves.removeFirst()
            val content = children(folder)
            val audio = content.filter(::isAudio)
            val folders = content.filter { it.isDirectory }
            val discs = folders.isNotEmpty() && folders.all { DISC.matches(it.name) }
            if (audio.isNotEmpty() || discs || depth >= 4 || folders.isEmpty()) {
                books += (if (folders.isEmpty()) singleFileBooks(audio, folder) else null) ?: listOfNotNull(collect(folder))
            } else shelves += folders.map { it to depth + 1 }
        }
        return books
    }

    /** Every audio file under [folder] as one book, in path order: for "It's really one book". */
    fun whole(folder: File): FoundBook? = collect(folder.absoluteFile)

    private fun single(file: File, folder: File): FoundBook {
        val stem = file.nameWithoutExtension
        val siblings = children(folder)
        fun same(other: File) = other.nameWithoutExtension.equals(stem, true)
        return FoundBook("file:${file.path}", stem, folder, listOf(file),
            siblings.filter { it.extension.equals("epub", true) && same(it) },
            siblings.filter { it.extension.equals("readalong", true) && same(it) },
            siblings.filter { it.extension.lowercase() in IMAGES && same(it) })
    }

    private fun singleFileBooks(audio: List<File>, folder: File): List<FoundBook>? {
        if (audio.size < 2 || !audio.all { it.extension.equals("m4b", true) }) return null
        if (audio.any { PART.containsMatchIn(it.name) }) return null
        return audio.map { single(it, folder) }
    }

    private fun collect(folder: File, recursive: Boolean = true): FoundBook? {
        val audio = mutableListOf<File>(); val epubs = mutableListOf<File>(); val transcripts = mutableListOf<File>(); val images = mutableListOf<File>()
        val pending = ArrayDeque(listOf(folder to 0))
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            if (depth > 32 || audio.size > 20_000) break
            for (child in children(directory)) {
                if (child.isDirectory) { if (recursive) pending += child to depth + 1 }
                else when (child.extension.lowercase()) {
                    in AUDIO -> audio += child
                    "epub" -> epubs += child
                    "readalong" -> transcripts += child
                    in IMAGES -> images += child
                }
            }
        }
        if (audio.isEmpty()) return null
        // Disc folders sort by their path: CD1/01.mp3 before CD2/01.mp3.
        val ordered = audio.sortedWith { a, b -> audioNameComparator.compare(a.relativeTo(folder).path, b.relativeTo(folder).path) }
        return FoundBook("folder:${folder.path}", folder.name, folder, ordered, epubs, transcripts, images)
    }

    fun bookId(key: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .take(10).joinToString("") { "%02x".format(it) }
}

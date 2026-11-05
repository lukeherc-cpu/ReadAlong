package com.example.readalong.desktop.core

import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * A book's chosen cover and EPUB, kept beside its audio as "<book>.cover.jpg" and "<book>.epub" (the same base
 * name as its `.readalong` and `.readalong-progress`), so they travel with the folder to the phone and back.
 * The phone's BookFolderFiles reads and writes the same files.
 *
 * Each rescan takes a companion file whose size or time changed since this computer last wrote or took it,
 * so a cover or EPUB chosen on the phone replaces this computer's, and the other way round.
 */
class Companions(private val library: LibraryStore, private val file: File = File(AppPaths.data, "companions.json")) {
    private val seen: MutableMap<String, String> = load()

    /** Copies [chosen] beside the book as its EPUB and reads that; where the folder can't be written, the chosen file is used where it is. */
    fun attachEpub(book: LibraryBook, chosen: File): File {
        val target = book.companionFile("epub")
        val used = if (chosen.canonicalPath == target.canonicalPath) target else runCatching { copy(chosen, target) }.getOrElse {
            System.err.println("ReadAlong: ${target.name} wasn't saved beside the book: ${it.message}"); chosen
        }
        if (used == target) remember("epub", book.id, target)
        library.update(book.id) { it.copy(epubPath = used.path, textSource = "EPUB") }
        return used
    }

    /**
     * Makes [image] (any format ImageIO or Skia reads) the book's cover, saved beside the book as a JPEG so it
     * syncs. Returns the file the cover is read from. For the cover menus (search the internet, choose a file).
     */
    fun saveCover(book: LibraryBook, image: ByteArray): File {
        val target = book.companionFile(COVER)
        val written = runCatching { writeJpeg(image, target); target }.getOrElse {
            System.err.println("ReadAlong: ${target.name} wasn't saved beside the book: ${it.message}")
            File(AppPaths.covers, "${book.id}-custom.img").apply { writeBytes(image) }
        }
        if (written == target) remember(COVER, book.id, target)
        library.update(book.id) { it.copy(coverPath = written.path) }
        return written
    }

    /** A rescan found the companion cover: true when it is new here, and was recorded as seen. */
    fun coverChanged(book: LibraryBook, cover: File): Boolean = changed(COVER, book.id, cover)

    /**
     * A rescan found the companion EPUB: true when it should become the book's EPUB. One that already was the
     * book's EPUB before this computer kept track is only recorded, so an upgrade never redoes matching.
     */
    @Synchronized fun epubChanged(book: LibraryBook, epub: File): Boolean {
        val first = seen[key("epub", book.id)] == null
        return changed("epub", book.id, epub) && (!first || book.epubPath == null || File(book.epubPath).canonicalPath != epub.canonicalPath)
    }

    /** A rescan found a `.readalong` beside the book: true the first time this version of it is seen. */
    fun packageChanged(bookId: String, pkg: File): Boolean = changed("readalong", bookId, pkg)

    @Synchronized private fun changed(kind: String, bookId: String, file: File): Boolean {
        val now = signature(file)
        if (seen[key(kind, bookId)] == now) return false
        seen[key(kind, bookId)] = now; save()
        return true
    }

    @Synchronized private fun remember(kind: String, bookId: String, file: File) { seen[key(kind, bookId)] = signature(file); save() }
    private fun key(kind: String, bookId: String) = "$kind:$bookId"
    private fun signature(file: File) = "${file.length()}:${file.lastModified()}"

    private fun copy(source: File, target: File): File {
        val partial = File(target.parentFile, ".${target.name}.partial")
        try {
            Files.copy(source.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
        return target
    }

    private fun writeJpeg(bytes: ByteArray, target: File) {
        val partial = File(target.parentFile, ".${target.name}.partial")
        try {
            val decoded = ImageIO.read(bytes.inputStream())
            if (decoded == null) partial.writeBytes(bytes) // WebP and others ImageIO can't read: the phone reads them anyway.
            else {
                // JPEG has no transparency: flatten onto white, at most 1600 px on a side like the phone's copies.
                val scale = minOf(1.0, 1600.0 / maxOf(decoded.width, decoded.height))
                val w = (decoded.width * scale).toInt().coerceAtLeast(1); val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                rgb.createGraphics().apply {
                    color = java.awt.Color.WHITE; fillRect(0, 0, w, h)
                    setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    drawImage(decoded, 0, 0, w, h, null); dispose()
                }
                check(ImageIO.write(rgb, "jpg", partial)) { "No JPEG writer." }
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }

    private fun load(): MutableMap<String, String> = runCatching {
        val o = JSONObject(file.readText())
        o.keySet().associateWithTo(HashMap()) { o.getString(it) }
    }.getOrDefault(HashMap())

    private fun save() = runCatching { file.writeAtomically(JSONObject(seen as Map<*, *>).toString()) }

    companion object {
        /** companionFile's extension for the cover: "<book>.cover.jpg". */
        const val COVER = "cover.jpg"
    }
}

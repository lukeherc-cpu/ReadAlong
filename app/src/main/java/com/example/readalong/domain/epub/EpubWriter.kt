package com.example.readalong.domain.epub

import com.example.readalong.domain.model.TranscriptSegment
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EpubMetadata(val id: String, val title: String, val author: String?, val language: String = "en", val description: String? = null)
data class EpubChapter(val id: String, val title: String)
fun epubFragment(segmentId: String) = "s" + MessageDigest.getInstance("SHA-256").digest(segmentId.toByteArray()).joinToString("") { "%02x".format(it) }

/** Small EPUB 3 packager. Text is preserved verbatim; paragraphs derive from canonical segments. */
object EpubWriter {
    suspend fun write(output: OutputStream, book: EpubMetadata, chapters: List<EpubChapter>,
        fetch: suspend (chapterId: String, offset: Int, limit: Int) -> List<TranscriptSegment>) {
        require(chapters.isNotEmpty()) { "No readable chapters are available." }
        ZipOutputStream(output).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value })
            zip.write(mime); zip.closeEntry()
            fun text(value: String) { zip.write(value.toByteArray(Charsets.UTF_8)) }
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); text(content); zip.closeEntry() }
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest = chapters.indices.joinToString("") { """<item id="c$it" href="chapter-$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
            val creator = book.author?.let { "<dc:creator>${xml(it)}</dc:creator>" }.orEmpty()
            val description = book.description?.let { "<dc:description>${xml(it)}</dc:description>" }.orEmpty()
            entry("EPUB/package.opf", """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="${xml(book.language)}"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:readalong:${xml(book.id)}</dc:identifier><dc:title>${xml(book.title)}</dc:title><dc:language>${xml(book.language)}</dc:language>$creator$description<meta property="dcterms:modified">${Instant.now().truncatedTo(ChronoUnit.SECONDS)}</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/><item id="sync" href="sync.json" media-type="application/json"/>$manifest</manifest><spine>$spine</spine></package>""")
            val links = chapters.mapIndexed { index, chapter -> """<li><a href="chapter-$index.xhtml">${xml(chapter.title)}</a></li>""" }.joinToString("")
            entry("EPUB/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${xml(book.language)}" xml:lang="${xml(book.language)}"><head><title>Contents</title></head><body><nav epub:type="toc" id="toc"><h1>Contents</h1><ol>$links</ol></nav></body></html>""")
            entry("EPUB/style.css", "body { font-family: serif; line-height: 1.6; margin: 5%; } p { margin: 0 0 1em; } h1 { break-before: page; }")
            suspend fun passages(chapter: EpubChapter, action: (TranscriptSegment) -> Unit) {
                var offset = 0
                while (true) {
                    val batch = fetch(chapter.id, offset, 80)
                    if (batch.isEmpty()) break
                    batch.forEach(action); offset += batch.size
                }
            }
            chapters.forEachIndexed { index, chapter ->
                zip.putNextEntry(ZipEntry("EPUB/chapter-$index.xhtml"))
                text("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" lang="${xml(book.language)}" xml:lang="${xml(book.language)}"><head><title>${xml(chapter.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head><body><section data-chapter-id="${xml(chapter.id)}"><h1>${xml(chapter.title)}</h1>""")
                val formatter = ParagraphFormatter { paragraph ->
                    text("<p>")
                    paragraph.forEachIndexed { index, segment ->
                        if (index > 0) text(" ")
                        text("""<span id="${epubFragment(segment.id)}" data-segment-id="${xml(segment.id)}" data-chapter-id="${xml(segment.chapterId)}" data-start-ms="${segment.startMs}" data-end-ms="${segment.endMs}">${xml(segment.text)}</span>""")
                    }
                    text("</p>")
                }
                passages(chapter, formatter::add)
                formatter.flush()
                text("</section></body></html>"); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("EPUB/sync.json"))
            text("{\"version\":1,\"bookId\":${json(book.id)},\"segments\":[")
            var first = true
            chapters.forEachIndexed { index, chapter -> passages(chapter) { segment ->
                if (!first) text(","); first = false
                text("{\"id\":${json(segment.id)},\"startMs\":${segment.startMs},\"endMs\":${segment.endMs},\"href\":${json("chapter-$index.xhtml#${epubFragment(segment.id)}")}}")
            } }
            text("]}"); zip.closeEntry()
        }
    }
    private fun xml(value: String): String = value.filter { it >= ' ' || it in "\n\r\t" }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun json(value: String) = buildString {
        append('"')
        value.forEach { char -> when (char) { '"' -> append("\\\""); '\\' -> append("\\\\"); else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char) } }
        append('"')
    }
}

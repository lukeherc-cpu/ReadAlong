package com.example.readalong.domain

import com.example.readalong.domain.epub.*
import com.example.readalong.domain.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class EpubWriterTest {
    @Test fun epubRoundTripHasRequiredContainerNavigationSpineAndTimestampLinks() = runBlocking {
        val book = EpubMetadata("book-123", "A map & a lantern", "ReadAlong", description = "Test publication")
        val text = "She read: \"A < B & C\". Curly quotes ’ and an em dash — stayed intact. Musical symbols ♩ ♪ ♫ remain literal Unicode."
        val segment = TranscriptSegment("book-123-one", book.id, "first", 108_000_000, 108_004_000, text)
        val file = File("build/verification/epub-structure.epub").apply { parentFile.mkdirs() }
        file.outputStream().use { output -> EpubWriter.write(output, book, listOf(EpubChapter("first", "A beginning"))) { _, offset, _ -> if (offset == 0) listOf(segment) else emptyList() } }
        ZipFile(file).use { archive ->
            val entries = archive.entries().asSequence().toList()
            assertEquals("mimetype", entries.first().name)
            assertEquals(java.util.zip.ZipEntry.STORED, entries.first().method)
            assertEquals("application/epub+zip", archive.getInputStream(entries.first()).reader().readText())
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            fun document(name: String) = archive.getInputStream(archive.getEntry(name)).use { factory.newDocumentBuilder().parse(it) }
            assertEquals("EPUB/package.opf", document("META-INF/container.xml").getElementsByTagNameNS("*", "rootfile").item(0).attributes.getNamedItem("full-path").nodeValue)
            assertEquals(book.title, document("EPUB/package.opf").getElementsByTagNameNS("*", "title").item(0).textContent)
            assertEquals(1, document("EPUB/package.opf").getElementsByTagNameNS("*", "itemref").length)
            assertEquals("chapter-0.xhtml", document("EPUB/nav.xhtml").getElementsByTagNameNS("*", "a").item(0).attributes.getNamedItem("href").nodeValue)
            val paragraph = document("EPUB/chapter-0.xhtml").getElementsByTagNameNS("*", "p").item(0)
            assertEquals(text, paragraph.textContent)
            assertEquals(epubFragment(segment.id), document("EPUB/chapter-0.xhtml").getElementsByTagNameNS("*", "span").item(0).attributes.getNamedItem("id").nodeValue)
            val mapping = archive.getInputStream(archive.getEntry("EPUB/sync.json")).reader().readText()
            assertTrue(mapping.contains("\"startMs\":108000000"))
            assertTrue(mapping.contains("chapter-0.xhtml#${epubFragment(segment.id)}"))
        }
    }
}

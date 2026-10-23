package com.example.readalong.domain

import com.example.readalong.domain.epub.EpubParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubParserTest {
    @Test fun privateReferenceWhenProvidedPreservesMusicalGlyphsWithoutBundlingItsText() {
        val path = System.getProperty("privateEpub")
        org.junit.Assume.assumeTrue("Optional read-only local EPUB verification", path != null)
        val parsed = EpubParser().parse(File(path!!))
        val notes = parsed.blocks.sumOf { block -> block.text.count { it in "♪♫♬♩♭♮♯" } }
        assertTrue("Reference notation should survive parsing", notes > 0)
        File(System.getProperty("epubVerificationDir"), "private-epub-parser-summary.json").writeText(
            "{\"blocks\":${parsed.blocks.size},\"spineItems\":${parsed.blocks.map { it.spine }.distinct().size},\"noteGlyphs\":$notes,\"styledBlocks\":${parsed.blocks.count { it.styles.isNotEmpty() }},\"embeddedFonts\":${parsed.fonts.size}}\n")
    }
    private fun fixture(extra: Map<String, String> = emptyMap(), binary: Map<String, ByteArray> = emptyMap()): File {
        val file = File.createTempFile("readalong-original-epub", ".epub")
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
            "OPS/package.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Original fixture</dc:title></metadata><manifest><item id="second" href="second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="first.xhtml" media-type="application/xhtml+xml"/><item id="css" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>""",
            "OPS/style.css" to ".soft {font-style: italic} .center {text-align:center}",
            "OPS/first.xhtml" to """<html><body><h1>At the river</h1><p id="p1">The <span class="soft">quiet</span> traveler saw <b>three</b> boats.</p><p class="center">♪ ♫<br/>♬</p><script>do not render this</script></body></html>""",
            "OPS/second.xhtml" to """<html><body><blockquote><p>The garden gate opened.</p></blockquote><ul><li>A lantern</li><li>A basket</li></ul></body></html>""",
        )
        files.putAll(extra)
        ZipOutputStream(file.outputStream()).use { zip -> files.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(binary[path] ?: text.toByteArray()); zip.closeEntry() } }
        return file
    }
    @Test fun frontMatterKeepsIdentityDedicationAndEpigraphAndNeverDeletesNarrativeLegalWords() {
        val file = fixture(mapOf(
            "OPS/first.xhtml" to """<html><body><section epub:type="copyright-page"><p>Original fixture</p><p>Copyright 2025. All rights reserved.</p></section><section epub:type="dedication"><p>For everyone who listens.</p></section><section epub:type="epigraph"><p>The copyright of our dreams belongs to nobody.</p></section><section epub:type="chapter"><h1>Chapter One</h1><p>He read the copyright notice: all rights reserved.</p></section></body></html>"""))
        try {
            val texts = EpubParser().parse(file).blocks.map { it.text }
            assertTrue("Original fixture" in texts)
            assertFalse(texts.any { it == "Copyright 2025. All rights reserved." })
            assertTrue("For everyone who listens." in texts)
            assertTrue("The copyright of our dreams belongs to nobody." in texts)
            assertTrue("He read the copyright notice: all rights reserved." in texts)
        } finally { file.delete() }
    }
    @Test fun unlabelledNumberedFrontContentsIsHiddenWithoutRemovingItsNavigationTargets() {
        val links = (1..12).joinToString(" ") { "<a href='second.xhtml#c$it'>$it</a>" }
        val file = fixture(mapOf("OPS/first.xhtml" to "<html><body><p>PART ONE: THE LONG ROAD HOME $links</p></body></html>"))
        try { assertFalse(EpubParser().parse(file).blocks.any { it.text.contains("PART ONE") }); assertTrue(EpubParser().parse(file).blocks.any { it.text == "The garden gate opened." }) }
        finally { file.delete() }
    }
    @Test fun mixedLegacyCopyrightPageKeepsTitleAndRetainsUnmarkedDedicationInNextFile() {
        val file = fixture(mapOf("OPS/first.xhtml" to "<html><body><p>Original fixture</p><p>A publisher imprint</p><p><span>Copyright 2025. All rights reserved. ISBN 12345.</span></p></body></html>",
            "OPS/second.xhtml" to "<html><body><p>For Jane, who believed.</p><blockquote>Beyond the horizon lies another dawn.</blockquote></body></html>"))
        try { assertEquals(listOf("Original fixture", "For Jane, who believed.", "Beyond the horizon lies another dawn."), EpubParser().parse(file).blocks.map { it.text }) }
        finally { file.delete() }
    }
    @Test fun fragmentGuideRemovesOnlyALinkedContentsContainerAndKeepsNarration() {
        val file = fixture(mapOf(
            "OPS/package.opf" to "<package><manifest><item id='first' href='first.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='first'/></spine><guide><reference type='toc' href='first.xhtml#contents'/></guide></package>",
            "OPS/first.xhtml" to "<html><body><div id='contents'><h1>Contents</h1><a href='#one'>The first chapter</a><a href='#two'>The second chapter</a></div><h1 id='one'>A real chapter</h1><p>This narration must not be removed with the linked contents.</p></body></html>"))
        try {
            val parsed = EpubParser().parse(file)
            assertEquals(listOf("A real chapter", "This narration must not be removed with the linked contents."), parsed.blocks.map { it.text })
        } finally { file.delete() }
    }
    @Test fun navigationAndFragmentGuideNeverDeleteTheStoryInASingleFileEpub() {
        val file = fixture(mapOf(
            "OPS/package.opf" to "<package><manifest><item id='first' href='first.xhtml' properties='nav' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='first'/></spine><guide><reference type='toc' href='first.xhtml#contents'/></guide></package>",
            "OPS/first.xhtml" to "<html><body><nav id='contents' epub:type='toc'><a href='#one'>One</a><a href='#two'>Two</a></nav><h1 id='one'>First chapter</h1><p>The original first chapter must remain fully readable.</p><h1 id='two'>Second chapter</h1><p>The original second chapter must also remain fully readable.</p></body></html>"))
        try {
            val parsed = EpubParser().parse(file)
            assertEquals(4, parsed.blocks.size)
            assertTrue(parsed.blocks.any { it.text.contains("original first chapter") })
            assertTrue(parsed.blocks.any { it.text.contains("original second chapter") })
            assertEquals(listOf("One", "Two"), parsed.contents.map { it.title })
            assertEquals(listOf(0, 2), parsed.contents.map { it.ordinal })
        } finally { file.delete() }
    }
    @Test fun epubNavigationReplacesLinkedContentsAndLocatesChaptersWithinOneFile() {
        val file = fixture(mapOf(
            "OPS/package.opf" to "<package><manifest><item id='nav' href='toc.xhtml' properties='nav' media-type='application/xhtml+xml'/><item id='first' href='first.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='nav'/><itemref idref='first'/></spine></package>",
            "OPS/toc.xhtml" to "<html><body><nav epub:type='toc'><a href='first.xhtml#prologue'>Prologue</a><a href='first.xhtml#one'>Chapter One</a></nav></body></html>",
            "OPS/first.xhtml" to "<html><body><section id='prologue'><p>A quiet beginning.</p></section><section id='one'><p>The first chapter.</p></section></body></html>"))
        try {
            val parsed = EpubParser().parse(file)
            assertEquals(listOf("A quiet beginning.", "The first chapter."), parsed.blocks.map { it.text })
            assertEquals(listOf("Prologue", "Chapter One"), parsed.contents.map { it.title })
            assertEquals(listOf(0, 1), parsed.contents.map { it.ordinal })
        } finally { file.delete() }
    }
    @Test fun spineOrderParagraphsEmphasisLineBreaksAndMusicArePreserved() {
        val file = fixture()
        try {
            val parsed = EpubParser().parse(file)
            assertEquals("Original fixture", parsed.title)
            assertEquals(listOf("At the river", "The quiet traveler saw three boats.", "♪ ♫\n♬", "The garden gate opened.", "A lantern", "A basket"), parsed.blocks.map { it.text })
            assertEquals("H1", parsed.blocks.first().kind)
            assertEquals("quiet", parsed.blocks[1].styles.first { it.italic }.let { parsed.blocks[1].text.substring(it.start, it.end) })
            assertEquals("three", parsed.blocks[1].styles.first { it.bold }.let { parsed.blocks[1].text.substring(it.start, it.end) })
            assertTrue(parsed.blocks[2].centered); assertEquals("QUOTE", parsed.blocks[3].kind); assertEquals("LIST", parsed.blocks[4].kind)
        } finally { file.delete() }
    }
    @Test fun drmAndUnsafeZipPathsFailWithActionableMessages() {
        listOf(mapOf("META-INF/encryption.xml" to """<encryption><EncryptionMethod Algorithm="unknown-drm"/></encryption>"""), mapOf("../escape.txt" to "unsafe")).forEach { extra ->
            val file = fixture(extra)
            try { assertTrue(runCatching { EpubParser().parse(file) }.isFailure) } finally { file.delete() }
        }
    }
    @Test fun entitiesNeverFetchExternalFilesOrExpandDocumentTypeDeclarations() {
        val file = fixture(mapOf("OPS/first.xhtml" to """<!DOCTYPE html [<!ENTITY secret SYSTEM "file:///etc/passwd">]><html><body><p>Original safe words &secret;</p></body></html>"""))
        try { assertFalse(EpubParser().parse(file).blocks.any { "root:" in it.text }); assertTrue(EpubParser().parse(file).blocks.any { "Original safe words" in it.text }) } finally { file.delete() }
    }
    @Test fun malformedAndOversizedTextFailWithoutExtractingAnything() {
        val invalid = File.createTempFile("readalong-invalid", ".epub").apply { writeText("not a zip") }
        try { assertTrue(runCatching { EpubParser().parse(invalid) }.isFailure) } finally { invalid.delete() }
        val large = fixture(mapOf("OPS/first.xhtml" to "<html><body><p>${"word ".repeat(21_000)}</p></body></html>"))
        try { assertTrue(runCatching { EpubParser().parse(large) }.isFailure) } finally { large.delete() }
    }
    @Test fun inheritedAndNestedFontFamiliesPreserveCascadeWithoutFontsBeingRequired() {
        val file = fixture(mapOf("OPS/style.css" to "body {font-family: 'Book Serif', serif} .other {font-family: sans-serif}",
            "OPS/first.xhtml" to "<html><body><p>One <span class='other'>two <span style='font-family:monospace'>three</span></span></p></body></html>"))
        try {
            val block = EpubParser().parse(file).blocks.first()
            assertEquals(listOf("book serif", "sans-serif", "monospace"), block.styles.map { it.fontFamily })
            assertEquals("three", block.styles.last().let { block.text.substring(it.start, it.end) })
        } finally { file.delete() }
    }
    @Test fun standardFontObfuscationIsDecodedAndMalformedTablesAreRejected() {
        val bytes = java.nio.ByteBuffer.allocate(32).apply {
            putInt(0x00010000); putShort(1); putShort(0); putInt(0)
            putInt(0x68656164); putInt(0); putInt(28); putInt(4); putInt(0)
        }.array()
        val identifier = "urn:uuid:12345678-1234-5678-1234-567812345678"
        listOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC").forEach { algorithm ->
            val key = if (algorithm.contains("idpf")) java.security.MessageDigest.getInstance("SHA-1").digest(identifier.toByteArray())
                else java.util.UUID.fromString(identifier.removePrefix("urn:uuid:")).let { java.nio.ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() }
            val encoded = bytes.mapIndexed { i, b -> (b.toInt() xor key[i % key.size].toInt()).toByte() }.toByteArray()
            val packageText = "<package unique-identifier='uid'><metadata><identifier id='uid'>$identifier</identifier></metadata><manifest><item id='first' href='first.xhtml' media-type='application/xhtml+xml'/><item id='css' href='style.css' media-type='text/css'/></manifest><spine><itemref idref='first'/></spine></package>"
            val file = fixture(mapOf("OPS/package.opf" to packageText, "OPS/font.ttf" to "", "OPS/style.css" to "@font-face {font-family: Test;src:url('font.ttf')}",
                "META-INF/encryption.xml" to "<encryption><EncryptedData><EncryptionMethod Algorithm='$algorithm'/><CipherData><CipherReference URI='OPS/font.ttf'/></CipherData></EncryptedData></encryption>"), mapOf("OPS/font.ttf" to encoded))
            try { assertArrayEquals(bytes, EpubParser().parse(file).fonts.single().bytes) } finally { file.delete() }
        }
        val damaged = bytes.copyOf().apply { java.nio.ByteBuffer.wrap(this).putInt(20, Int.MAX_VALUE) }
        val file = fixture(mapOf("OPS/font.ttf" to "", "OPS/style.css" to "@font-face {font-family: Test;src:url('font.ttf')}"), mapOf("OPS/font.ttf" to damaged))
        try { assertTrue(EpubParser().parse(file).fonts.isEmpty()) } finally { file.delete() }
    }
    @Test fun invalidAndExternalFontSourcesFallBackWhileTextAndNotesRemainReadable() {
        listOf("bad.ttf", "https://example.invalid/font.ttf", "../../escape.ttf").forEach { source ->
            val file = fixture(mapOf("OPS/style.css" to "@font-face {font-family: Book;src:url('$source')} body {font-family:Book}", "OPS/bad.ttf" to "invalid font bytes"))
            try {
                val parsed = EpubParser().parse(file)
                assertTrue(parsed.fonts.isEmpty()); assertTrue(parsed.blocks.any { "♪" in it.text })
                assertEquals("book", parsed.blocks.first().styles.first().fontFamily)
            } finally { file.delete() }
        }
    }
    @Test fun incompleteLongStylesheetDoesNotTrapTheParserInRegexBacktracking() {
        val file = fixture(mapOf("OPS/style.css" to "unclosed ".repeat(50_000)))
        try { assertTrue(EpubParser().parse(file).blocks.any { it.text == "♪ ♫\n♬" }) } finally { file.delete() }
    }
}

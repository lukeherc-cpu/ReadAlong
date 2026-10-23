package com.example.readalong.domain.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/** A bounded, offline reflowable EPUB text reader. No ZIP extraction, external fetches or executable content. */
class EpubParser {
    fun parse(file: File, checkpoint: () -> Unit = {}): ParsedEpub {
        require(file.length() <= 128L * 1024 * 1024) { "This EPUB is too large (limit 128 MB)." }
        return ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 10_000) { "This EPUB has too many files." }
            require(entries.map { it.name }.toSet().size == entries.size) { "This EPUB contains duplicate file paths." }
            entries.forEach { entry ->
                require(!entry.name.startsWith('/') && '\\' !in entry.name && '\u0000' !in entry.name && entry.name.split('/').none { it == ".." }) { "This EPUB contains unsafe file paths." }
            }
            var readBytes = 0L
            fun readBinary(path: String, textBudget: Boolean = true): ByteArray {
                checkpoint()
                val entry = requireNotNull(zip.getEntry(path)) { "A required EPUB file is missing." }
                require(entry.size in 0..8L * 1024 * 1024) { "An EPUB text file is too large or invalid." }
                val bytes = zip.getInputStream(entry).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (textBudget) readBytes += n
                        require(out.size() + n <= (if (textBudget) 8 else 4) * 1024 * 1024 && readBytes <= 48L * 1024 * 1024) { "This EPUB exceeds the text processing limit." }
                        out.write(buffer, 0, n); checkpoint()
                    }
                    out.toByteArray()
                }
                return bytes
            }
            fun read(path: String): String {
                val bytes = readBinary(path)
                // EPUB XML defaults to UTF-8; a BOM or explicit UTF-16 encoding is also supported.
                val encoding = when {
                    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
                    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
                return String(bytes, encoding).removePrefix("\uFEFF")
            }
            require(zip.getEntry("mimetype")?.let { read("mimetype").trim() == "application/epub+zip" } == true) { "Choose a valid EPUB file." }
            val obfuscation = mutableMapOf<String, String>()
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                val encryption = Jsoup.parse(read("META-INF/encryption.xml"), "", Parser.xmlParser())
                val methods = encryption.getAllElements().filter { it.tagName().substringAfter(':') == "EncryptionMethod" }
                encryption.getAllElements().filter { it.tagName().substringAfter(':') == "EncryptedData" }.forEach { data ->
                    val algorithm = data.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "EncryptionMethod" }?.attr("Algorithm")
                    val path = data.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "CipherReference" }?.attr("URI")
                    if (algorithm != null && path != null) runCatching { resolve("", path) }.getOrNull()?.let { obfuscation[it] = algorithm }
                }
                require(methods.all { it.attr("Algorithm") in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC") }) { "This EPUB is DRM protected. Choose a DRM-free EPUB." }
            }
            val container = Jsoup.parse(read("META-INF/container.xml"), "", Parser.xmlParser())
            val opfPath = requireNotNull(container.getAllElements().firstOrNull { it.tagName().substringAfter(':') == "rootfile" }?.attr("full-path")?.takeIf { it.isNotBlank() }) { "The EPUB package could not be found." }
            val opf = Jsoup.parse(read(resolve("", opfPath)), "", Parser.xmlParser())
            val all = opf.getAllElements()
            val manifest = all.filter { it.tagName().substringAfter(':') == "item" }.associateBy { it.attr("id") }
            require(all.none { it.tagName().substringAfter(':') == "meta" && it.attr("property") == "rendition:layout" && it.text() == "pre-paginated" }) { "Fixed-layout EPUBs are not supported yet. Choose a reflowable edition." }
            val css = mutableMapOf<String, String>()
            val fonts = mutableListOf<EpubFont>()
            val packageElement = all.firstOrNull { it.tagName().substringAfter(':') == "package" }
            val identifier = all.firstOrNull { it.attr("id") == packageElement?.attr("unique-identifier") }?.text().orEmpty()
            var fontBytes = 0L
            var fontAttempts = 0
            val seenFonts = mutableSetOf<String>()
            manifest.values.filter { it.attr("media-type") == "text/css" }.take(50).forEach { item ->
                val sheetPath = resolve(opfPath, item.attr("href"))
                val sheet = read(sheetPath)
                var offset = 0; var rules = 0
                while (offset < sheet.length && rules++ < 10_000) {
                    checkpoint()
                    val open = sheet.indexOf('{', offset); if (open < 0) break
                    val close = sheet.indexOf('}', open + 1); if (close < 0) break
                    val declarations = sheet.substring(open + 1, close).take(8192)
                    val selectors = sheet.substring(offset, open).take(4096)
                    if (selectors.trim().endsWith("@font-face") && fonts.size < 24 && fontAttempts < 48) {
                        val family = family(declarations)
                        val source = Regex("url\\(\\s*['\"]?([^'\")]+)").find(property(declarations, "src").orEmpty())?.groupValues?.get(1)?.trim()
                        val fontPath = source?.let { runCatching { resolve(sheetPath, it) }.getOrNull() }
                        if (family != null && fontPath != null && fontBytes < 24L * 1024 * 1024 &&
                            seenFonts.add("$family|$fontPath|${property(declarations, "font-weight")}|${property(declarations, "font-style")}")) {
                            fontAttempts++
                            // Fonts are optional. Unsupported URLs, obfuscation or damage never reject readable text.
                            val font = runCatching {
                                val path = fontPath
                                val size = zip.getEntry(path)?.size ?: 0
                                require(size in 12..4L * 1024 * 1024 && fontBytes + size <= 24L * 1024 * 1024)
                                fontBytes += size
                                val bytes = readBinary(path, textBudget = false)
                                require(bytes.size.toLong() == size)
                                when (obfuscation[path]) {
                                    "http://www.idpf.org/2008/embedding" -> {
                                        require(identifier.isNotBlank())
                                        val key = java.security.MessageDigest.getInstance("SHA-1").digest(identifier.filterNot { it in " \t\r\n" }.toByteArray(Charsets.UTF_8))
                                        repeat(minOf(1040, bytes.size)) { i -> bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
                                    }
                                    "http://ns.adobe.com/pdf/enc#RC" -> {
                                        val uuid = java.util.UUID.fromString(identifier.removePrefix("urn:uuid:"))
                                        val key = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
                                        repeat(minOf(1024, bytes.size)) { i -> bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
                                    }
                                }
                                require(validSfnt(bytes))
                                EpubFont(family, property(declarations, "font-weight")?.toIntOrNull()?.coerceIn(100, 900)
                                    ?: if (property(declarations, "font-weight") == "bold") 700 else 400,
                                    property(declarations, "font-style") in setOf("italic", "oblique"), bytes)
                            }.getOrNull()
                            if (font != null) fonts += font
                            checkpoint()
                        }
                    }
                    selectors.split(',').forEach { selector ->
                        val key = selector.trim()
                        if (key.matches(Regex("(?:[a-zA-Z][a-zA-Z0-9-]*)?\\.[a-zA-Z0-9_-]+|[a-zA-Z][a-zA-Z0-9-]*"))) css[key] = ((css[key] ?: "") + ";" + declarations).takeLast(8192)
                    }
                    offset = close + 1
                }
            }
            val navigation = mutableListOf<Pair<String, String>>()
            fun navigationTarget(base: String, target: String): String {
                val resolved = resolve(base, target)
                val fragment = URI(target).fragment
                return resolved + if (fragment.isNullOrBlank()) "" else "#$fragment"
            }
            manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }?.let { item ->
                val path = resolve(opfPath, item.attr("href"))
                val doc = Jsoup.parse(read(path))
                val toc = doc.select("nav").firstOrNull { "toc" in it.attr("epub:type").split(' ') || it.attr("role") == "doc-toc" }
                toc?.select("a[href]")?.forEach { link ->
                    runCatching { navigationTarget(path, link.attr("href")) }.getOrNull()?.let { navigation += link.text() to it }
                }
            }
            if (navigation.isEmpty()) manifest.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }?.let { item ->
                val path = resolve(opfPath, item.attr("href"))
                val doc = Jsoup.parse(read(path), "", Parser.xmlParser())
                doc.getAllElements().filter { it.tagName().substringAfter(':') == "navPoint" }.forEach { point ->
                    val label = point.children().firstOrNull { it.tagName().substringAfter(':') == "navLabel" }?.text()
                    val target = point.children().firstOrNull { it.tagName().substringAfter(':') == "content" }?.attr("src")
                    if (!label.isNullOrBlank() && !target.isNullOrBlank()) runCatching { navigationTarget(path, target) }.getOrNull()?.let { navigation += label to it }
                }
            }
            val tocPaths = all.filter { it.tagName().substringAfter(':') == "reference" && it.attr("type") == "toc" }
                .filter { runCatching { URI(it.attr("href")).fragment.isNullOrBlank() }.getOrDefault(false) }
                .mapNotNull { runCatching { resolve(opfPath, it.attr("href")) }.getOrNull() }.toSet()
            val fragmentOrdinals = mutableMapOf<String, Int>()
            val guideFragments = all.filter { it.tagName().substringAfter(':') == "reference" && it.attr("type") == "toc" }.mapNotNull { ref ->
                runCatching { URI(ref.attr("href")).fragment?.takeIf { it.isNotBlank() }?.let { resolve(opfPath, ref.attr("href")) to it } }.getOrNull()
            }
            val linkedPaths = navigation.map { it.second.substringBefore('#') }.toSet().ifEmpty {
                manifest.values.filter { it.attr("media-type") in setOf("application/xhtml+xml", "text/html") }.map { resolve(opfPath, it.attr("href")) }.toSet()
            }
            val bookTitle = all.firstOrNull { it.tagName().substringAfter(':') == "title" }?.text().orEmpty()
            val authors = all.filter { it.tagName().substringAfter(':') == "creator" }.map { it.text() }
            var storyStarted = false
            val blocks = mutableListOf<EpubBlock>()
            var characters = 0
            all.filter { it.tagName().substringAfter(':') == "itemref" && it.attr("linear") != "no" }.forEachIndexed { spine, ref ->
                checkpoint()
                val item = requireNotNull(manifest[ref.attr("idref")]) { "An EPUB chapter is missing from its manifest." }
                require(item.attr("media-type") in setOf("application/xhtml+xml", "text/html")) { "This EPUB uses an unsupported chapter format." }
                val href = resolve(opfPath, item.attr("href"))
                val document = Jsoup.parse(read(href))
                fun localLinks(element: Element) = element.select("a[href]").filter { link -> runCatching { resolve(href, link.attr("href")) }.getOrNull() in linkedPaths }
                guideFragments.filter { it.first == href }.forEach { (_, fragment) ->
                    document.getElementById(fragment)?.takeIf { it.normalName() !in setOf("html", "body") }?.let { element ->
                        val links = localLinks(element)
                        if (links.size >= 2 && links.sumOf { it.text().length } >= element.text().length * .5) element.remove()
                    }
                }
                val opening = !storyStarted && spine < 24
                val guideTypes = all.filter { it.tagName().substringAfter(':') == "reference" &&
                    runCatching { resolve(opfPath, it.attr("href")) == href && URI(it.attr("href")).fragment.isNullOrBlank() }.getOrDefault(false) }
                    .map { it.attr("type") }.toSet()
                EpubFrontMatter.clean(document, guideTypes, bookTitle, authors, opening)
                val bodyText = document.body().text()
                val links = localLinks(document)
                // Use EPUB semantics first. Legacy HTML contents pages also need an explicit contents label and a link-heavy body.
                val labelledToc = document.select("h1,h2,h3,title").any { it.text().trim().lowercase() in setOf("contents", "table of contents") } ||
                    href.substringAfterLast('/').substringBefore('.').lowercase() in setOf("toc", "contents")
                val navDocument = "nav" in item.attr("properties").split(' ')
                // Older conversions sometimes have an unlabelled grid of numbered
                // chapter links. Character ratios undercount single-digit link labels.
                val numberedContents = opening && bodyText.length <= 4000 && links.size >= 10 &&
                    links.count { it.text().trim().matches(Regex("(?i)([0-9]+|[ivxlcdm]+)")) } >= 5 &&
                    document.select("p").all { paragraph ->
                        paragraph.clone().apply { select("a").remove() }.text().length < 200
                    }
                if (numberedContents) return@forEachIndexed
                // A navigation document/guide may also contain the actual book in one XHTML file.
                // Omit a whole file only when its body is demonstrably a linked contents list.
                if ((href in tocPaths || navDocument || labelledToc || (opening && links.size >= 3 && links.sumOf { it.text().length } >= bodyText.length * .75)) && links.size >= 2 &&
                    links.sumOf { it.text().length } >= bodyText.length * .5) return@forEachIndexed
                if (navDocument || labelledToc || href in tocPaths) document.select("h1,h2,h3").filter {
                    it.text().trim().lowercase() in setOf("contents", "table of contents")
                }.forEach { it.remove() }
                document.select("script,style,iframe,object,embed,audio,video,svg,math,nav").remove()
                val body = document.body()
                fun declarations(element: Element): String = css[element.normalName()].orEmpty() + element.classNames().joinToString(";") { css[".$it"].orEmpty() + ";" + css["${element.normalName()}.$it"].orEmpty() } + ";" + element.attr("style")
                val blockTags = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "ul", "ol", "table", "tr", "td", "figure", "figcaption", "hr")
                fun add(nodes: List<Node>, parent: Element, depth: Int) {
                    require(depth < 128) { "This EPUB has excessively nested text." }
                    val builder = StringBuilder(); val styles = mutableListOf<EpubTextStyle>()
                    val preserve = parent.normalName() == "pre" || declarations(parent).contains(Regex("white-space\\s*:\\s*pre"))
                    fun inline(node: Node, level: Int) {
                        require(level < 128) { "This EPUB has excessively nested text." }
                        when (node) {
                            is TextNode -> if (preserve) builder.append(node.wholeText) else node.wholeText.forEach { c ->
                                if (c.isWhitespace() || c == '\u00A0') { if (builder.isNotEmpty() && !builder.last().isWhitespace()) builder.append(' ') } else builder.append(c)
                            }
                            is Element -> {
                                val start = builder.length
                                when (node.normalName()) {
                                    "br" -> { while (builder.isNotEmpty() && builder.last() == ' ') builder.setLength(builder.length - 1); builder.append('\n') }
                                    "img" -> if (node.attr("alt").isNotBlank()) builder.append("[${node.attr("alt")}]")
                                    else -> node.childNodes().forEach { inline(it, level + 1) }
                                }
                                val d = declarations(node)
                                val bold = node.normalName() in setOf("b", "strong") || d.contains(Regex("font-weight\\s*:\\s*(bold|[6-9]00)"))
                                val italic = node.normalName() in setOf("i", "em", "cite") || d.contains(Regex("font-style\\s*:\\s*(italic|oblique)"))
                                val underline = node.normalName() == "u" || d.contains(Regex("text-decoration[^:]*\\s*:[^;]*underline"))
                                val font = family(d)
                                if (builder.length > start && (bold || italic || underline || font != null)) styles.add(0, EpubTextStyle(start, builder.length, bold, italic, underline, font))
                            }
                        }
                    }
                    nodes.forEach { inline(it, depth + 1) }
                    val text = builder.toString().trimEnd()
                    if (text.isBlank()) return
                    val parentStyle = declarations(parent)
                    val parentBold = parentStyle.contains(Regex("font-weight\\s*:\\s*(bold|[6-9]00)"))
                    val parentItalic = parentStyle.contains(Regex("font-style\\s*:\\s*(italic|oblique)"))
                    val parentFont = (listOf(parent) + parent.parents()).firstNotNullOfOrNull { family(declarations(it)) }
                    if (parentBold || parentItalic || parentFont != null) styles.add(0, EpubTextStyle(0, text.length, parentBold, parentItalic, fontFamily = parentFont))
                    require(text.length <= 100_000) { "An EPUB paragraph is too large." }
                    characters += text.length
                    require(characters <= 8_000_000 && blocks.size < 100_000) { "This EPUB contains too much text." }
                    val ancestors = listOf(parent) + parent.parents()
                    val kind = when {
                        parent.normalName().matches(Regex("h[1-6]")) -> parent.normalName().uppercase()
                        ancestors.any { it.normalName() == "li" } -> "LIST"
                        ancestors.any { it.normalName() == "blockquote" } -> "QUOTE"
                        preserve -> "VERSE"
                        else -> "P"
                    }
                    (listOf(parent) + parent.parents() + nodes.filterIsInstance<Element>().flatMap { it.getAllElements() }).forEach { element ->
                        if (element.id().isNotBlank()) fragmentOrdinals.putIfAbsent("$href#${element.id()}", blocks.size)
                    }
                    blocks += EpubBlock(blocks.size, spine, href + "#" + (parent.id().takeIf { it.isNotBlank() } ?: "readalong-${blocks.size}"), text, kind,
                        styles.mapNotNull { s -> s.copy(end = s.end.coerceAtMost(text.length)).takeIf { it.end > it.start } }, declarations(parent).contains(Regex("text-align\\s*:\\s*center")))
                    checkpoint()
                }
                fun walk(element: Element, depth: Int) {
                    require(depth < 128) { "This EPUB has excessively nested text." }
                    var run = mutableListOf<Node>()
                    element.childNodes().forEach { child ->
                        if (child is Element && child.normalName() in blockTags) {
                            add(run, element, depth); run = mutableListOf()
                            if (child.normalName() == "hr") blocks += EpubBlock(blocks.size, spine, "$href#readalong-${blocks.size}", "⁂", "BREAK")
                            else walk(child, depth + 1)
                        } else run += child
                    }
                    add(run, element, depth)
                }
                walk(body, 0)
                if (body.text().length > 2400 || body.select("h1,h2,h3").any {
                    it.text().matches(Regex("(?i)(chapter\\s+.+|prologue)")) }) storyStarted = true
            }
            require(blocks.isNotEmpty()) { "This EPUB does not contain readable text." }
            val contents = navigation.distinct().mapNotNull { (title, target) ->
                val block = fragmentOrdinals[target]?.let { blocks.getOrNull(it) } ?: blocks.firstOrNull { it.href == target } ?: blocks.firstOrNull { it.href.substringBefore('#') == target.substringBefore('#') }
                block?.let { EpubContentsEntry(title.take(500), it.ordinal) }
            }.distinctBy { it.ordinal }
            ParsedEpub(all.firstOrNull { it.tagName().substringAfter(':') == "title" }?.text()?.takeIf { it.isNotBlank() } ?: "My EPUB", blocks, fonts, contents)
        }
    }

    private fun property(declarations: String, name: String): String? = declarations.split(';').lastOrNull {
        it.substringBefore(':').trim().equals(name, ignoreCase = true)
    }?.substringAfter(':')?.trim()?.removeSuffix("!important")?.trim()
    private fun family(declarations: String): String? = property(declarations, "font-family")
        ?.substringBefore(',')?.trim()?.trim('\'', '"')?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotBlank() }?.take(256)

    /** SFNT table bounds checked before any native font loader sees a file. WOFF/WOFF2 safely fall back. */
    private fun validSfnt(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (buffer.int !in setOf(0x00010000, 0x4f54544f, 0x74727565)) return false
        val tables = buffer.short.toInt() and 0xffff
        if (tables !in 1..256 || 12L + tables * 16L > bytes.size) return false
        return (0 until tables).all { i ->
            val offset = buffer.getInt(12 + i * 16 + 8).toLong() and 0xffffffffL
            val length = buffer.getInt(12 + i * 16 + 12).toLong() and 0xffffffffL
            offset >= 12L + tables * 16L && offset + length <= bytes.size
        }
    }

    private fun resolve(base: String, href: String): String {
        val uri = URI(base).resolve(URI(href)).normalize()
        require(uri.scheme == null && uri.authority == null && !uri.path.startsWith('/') && uri.path.split('/').none { it == ".." } && '\\' !in uri.path) { "This EPUB references unsafe or external files." }
        return uri.path
    }
}

data class ParsedEpub(val title: String, val blocks: List<EpubBlock>, val fonts: List<EpubFont> = emptyList(), val contents: List<EpubContentsEntry> = emptyList())
data class EpubContentsEntry(val title: String, val ordinal: Int)
data class EpubFont(val family: String, val weight: Int, val italic: Boolean, val bytes: ByteArray)
data class EpubBlock(val ordinal: Int, val spine: Int, val href: String, val text: String, val kind: String = "P", val styles: List<EpubTextStyle> = emptyList(), val centered: Boolean = false)
data class EpubTextStyle(val start: Int, val end: Int, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val fontFamily: String? = null)

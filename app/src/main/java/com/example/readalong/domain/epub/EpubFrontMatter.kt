package com.example.readalong.domain.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Presentation filtering only. Never changes wording inside retained narrative or quotations. */
internal object EpubFrontMatter {
    private val kept = setOf("titlepage", "halftitlepage", "dedication", "epigraph")
    private val omitted = setOf("copyright-page", "copyright", "acknowledgments", "acknowledgements", "imprint", "colophon", "other-books", "loi", "lot")
    private fun semantics(e: Element) = (e.attr("epub:type") + " " + e.attr("role").removePrefix("doc-")).split(Regex("\\s+")).toSet()
    private fun protected(e: Element) = semantics(e).any { it in kept }
    private fun key(text: String) = text.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    fun clean(document: Document, guideTypes: Set<String>, title: String, authors: List<String>, opening: Boolean) {
        val body = document.body()
        val identity = (authors + title).map(::key).filter { it.isNotEmpty() }.toSet()
        fun identityLine(e: Element) = key(e.text()) in identity
        fun preserveInside(e: Element) {
            // Mixed title/copyright pages occur in legacy EPUBs. Keep exact metadata
            // identity lines and explicitly marked dedications/epigraphs, not legal text.
            val retain = e.getAllElements().filter { it !== e && (protected(it) || identityLine(it)) }
                .filter { node -> node.parents().none { it !== e && (protected(it) || identityLine(it)) } }
                .map { it.clone() }
            e.empty(); retain.forEach(e::appendChild)
        }
        body.getAllElements().toList().asReversed().forEach { e ->
            if (!protected(e) && semantics(e).any { it in omitted }) preserveInside(e)
        }
        if (!opening || protected(body)) return
        val text = body.text()
        val lower = text.lowercase(java.util.Locale.ROOT)
        val legalLabel = body.select("p,h1,h2,h3,div").any {
            it.text().trim().matches(Regex("(?is)(copyright\\b.*|©.*|this is a work of fiction.*)"))
        }
        val legal = legalLabel && ("copyright" in lower || '©' in lower) &&
            ("all rights reserved" in lower || "cataloging-in-publication" in lower || "isbn" in lower)
        val heading = body.select("h1,h2,h3,p").firstOrNull { it.text().isNotBlank() }?.text()?.trim()?.lowercase()
        val unwantedHeading = heading in setOf("acknowledgments", "acknowledgements", "also by", "praise for", "about the publisher")
        // A chapter/epigraph in the same XHTML file must survive a copyright subsection.
        // Only classify the entire legacy page when it is compact and lacks story sections.
        val storySection = body.getAllElements().any { semantics(it).any { type -> type in setOf("chapter", "prologue", "epilogue", "bodymatter") } } ||
            body.select("h1,h2,h3").any { it.text().matches(Regex("(?i)(chapter\\s+.+|prologue|epilogue)")) }
        if (!storySection && (guideTypes.any { it in omitted } || unwantedHeading || (legal && text.length < 12_000))) preserveInside(body)
    }
}

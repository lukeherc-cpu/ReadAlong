// jsoup for iOS: Ksoup is a Kotlin Multiplatform port of jsoup with the same API, under another package.
package org.jsoup

import com.fleeksoft.ksoup.Ksoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

object Jsoup {
    fun parse(html: String): Document = Ksoup.parse(html)
    fun parse(html: String, baseUri: String): Document = Ksoup.parse(html, baseUri)
    fun parse(html: String, baseUri: String, parser: Parser): Document = Ksoup.parse(html, parser, baseUri)
    fun parseBodyFragment(html: String): Document = Ksoup.parseBodyFragment(html)
    fun clean(html: String, safelist: com.fleeksoft.ksoup.safety.Safelist): String = Ksoup.clean(html, safelist)
}

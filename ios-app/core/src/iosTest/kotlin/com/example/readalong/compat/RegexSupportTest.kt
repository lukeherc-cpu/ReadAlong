package com.example.readalong.compat

import kotlin.test.Test
import kotlin.test.fail

/** Temporary: which Unicode class spellings Kotlin/Native's regex accepts. */
class RegexSupportTest {
    @Test fun unicodeClasses() {
        val candidates = listOf("\\p{L}", "\\p{IsL}", "\\p{N}", "\\p{IsN}", "\\p{M}", "\\p{IsM}", "\\p{Lu}", "\\p{Nd}", "\\p{Mn}", "\\p{Alpha}", "\\p{IsAlphabetic}",
            "\\p{javaLetter}", "\\p{javaLetterOrDigit}", "[\\p{Lu}\\p{Ll}]", "[\\p{IsL}\\p{N}]+(?:['’][\\p{IsL}\\p{N}]+)*", "\\p{Cntrl}", "\\p{L}+", "[\\p{L}]")
        val report = candidates.joinToString("\n") { p -> p + " -> " + (runCatching { Regex(p); "ok" }.getOrElse { it.message ?: it.toString() }) }
        fail("\n" + report)
    }
}

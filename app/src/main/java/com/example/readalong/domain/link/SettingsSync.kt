package com.example.readalong.domain.link

import org.json.JSONObject

/**
 * The app and reader settings the phone and ReadAlong Desktop share, sent over the link on each sync (unless
 * "App and reader settings" is unticked on either). Values travel as text under fixed keys; each app turns
 * them into its own settings and ignores what it has no use for.
 *
 * The phone keeps the values both last agreed on. A setting changed on one side since then is taken by the
 * other; changed on both, the phone's wins. The first sync after linking takes the phone's.
 */
object SettingsSync {
    const val ACCENT = "accent"
    const val SKIP_BACK = "skipBack"
    const val SKIP_FORWARD = "skipForward"
    const val READER_THEME = "readerTheme"
    const val READER_FONT = "readerFont"
    // The reader's text size is not shared: a phone and a computer screen want different ones.
    val keys = listOf(ACCENT, SKIP_BACK, SKIP_FORWARD, READER_THEME, READER_FONT)

    /** The values both sides should have now. */
    fun merge(phone: Map<String, String>, computer: Map<String, String>, agreed: Map<String, String>?): Map<String, String> =
        keys.mapNotNull { key ->
            val mine = phone[key]; val theirs = computer[key]
            val value = when {
                agreed == null -> mine ?: theirs
                mine != null && mine != agreed[key] -> mine
                theirs != null && theirs != agreed[key] -> theirs
                else -> mine ?: theirs
            }
            value?.let { key to it }
        }.toMap()

    fun encode(values: Map<String, String>) = JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }
    fun decode(json: JSONObject?): Map<String, String> = json?.let { o -> keys.filter { o.has(it) && !o.isNull(it) }.associateWith { o.get(it).toString() } }.orEmpty()
}

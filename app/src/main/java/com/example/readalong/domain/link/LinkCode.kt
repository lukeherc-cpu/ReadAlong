package com.example.readalong.domain.link

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What the computer's pairing QR code carries: who it is, where to reach it on the home network,
 * and the key the two devices will share. The same text can be copied and pasted on a phone without a camera.
 */
data class LinkCode(val deviceId: String, val name: String, val hosts: List<String>, val port: Int, val key: ByteArray) {
    fun encode(): String = "readalong-link:1?d=${enc(deviceId)}&n=${enc(name)}&h=${enc(hosts.joinToString(","))}&p=$port&k=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    override fun equals(other: Any?) = other is LinkCode && other.encode() == encode()
    override fun hashCode() = encode().hashCode()

    companion object {
        private const val PREFIX = "readalong-link:1?"
        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        /** Null when [text] isn't a ReadAlong pairing code. */
        fun parse(text: String): LinkCode? {
            val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val fields = body.split('&').mapNotNull { part ->
                val at = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                part.substring(0, at) to URLDecoder.decode(part.substring(at + 1), "UTF-8")
            }.toMap()
            return runCatching {
                val key = Base64.getUrlDecoder().decode(fields.getValue("k")).also { require(it.size == 32) }
                LinkCode(fields.getValue("d"), fields["n"].orEmpty().ifBlank { "Computer" }, fields.getValue("h").split(',').filter { it.isNotBlank() },
                    fields.getValue("p").toInt().also { require(it in 1..65535) }, key)
            }.getOrNull()
        }
    }
}

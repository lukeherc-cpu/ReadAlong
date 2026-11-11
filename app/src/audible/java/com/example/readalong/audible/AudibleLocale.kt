package com.example.readalong.audible

/**
 * An Audible store. Accounts belong to the Amazon marketplace they signed in on; the books come
 * from the Audible store of the same country.
 *
 * Ported from LibriSync (GPL-3.0, a port of Libation): native/rust-core/src/api/auth.rs.
 */
enum class AudibleLocale(val code: String, val label: String, val audibleDomain: String, val amazonDomain: String, val marketplace: String) {
    US("us", "United States", "audible.com", "amazon.com", "AF2M0KC94RCEA"),
    UK("uk", "United Kingdom", "audible.co.uk", "amazon.co.uk", "A2I9A3Q2GNFNGQ"),
    CA("ca", "Canada", "audible.ca", "amazon.ca", "A2CQZ5RBY40XE"),
    AU("au", "Australia", "audible.com.au", "amazon.com.au", "AN7EY7DTAW63G"),
    DE("de", "Germany", "audible.de", "amazon.de", "AN7V1F1VY261K"),
    FR("fr", "France", "audible.fr", "amazon.fr", "A2728XDNODOQ8T"),
    IT("it", "Italy", "audible.it", "amazon.it", "A2N7FU2W2BU2ZC"),
    ES("es", "Spain", "audible.es", "amazon.es", "ALMIKO4SZCSAR"),
    IN("in", "India", "audible.in", "amazon.in", "AJO3FBRUE6J4S"),
    JP("jp", "Japan", "audible.co.jp", "amazon.co.jp", "A1QAP3MOU4173J"),
    BR("br", "Brazil", "audible.com.br", "amazon.com.br", "A10J1VAYUDTYRN");

    val api get() = "https://api.$audibleDomain"
    val amazonApi get() = "https://api.$amazonDomain"

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: US
    }
}

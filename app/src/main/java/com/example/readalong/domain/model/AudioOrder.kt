package com.example.readalong.domain.model

/** Compares digit runs by value without overflowing on very long file numbers. */
val audioNameComparator: Comparator<String> = Comparator { left, right ->
    val tokens = Regex("[0-9]+|[^0-9]+")
    val a = tokens.findAll(left.lowercase()).map { it.value }.toList()
    val b = tokens.findAll(right.lowercase()).map { it.value }.toList()
    var result = 0
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]; val y = b[i]
        result = if (x.first().isDigit() && y.first().isDigit()) {
            val n = x.trimStart('0').ifEmpty { "0" }; val m = y.trimStart('0').ifEmpty { "0" }
            n.length.compareTo(m.length).takeIf { it != 0 } ?: n.compareTo(m)
        } else x.compareTo(y)
        if (result != 0) break
    }
    if (result != 0) result else a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
}

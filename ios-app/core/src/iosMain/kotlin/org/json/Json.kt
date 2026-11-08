// org.json for iOS: the same object model, coercions and output as org.json on Android and desktop.
package org.json

class JSONException(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

private object Null {
    override fun equals(other: Any?) = other == null || other === this
    override fun hashCode() = 0
    override fun toString() = "null"
}

internal fun wrapValue(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double -> value
    is Float -> value.toDouble()
    is Short -> value.toInt()
    is Byte -> value.toInt()
    is Char -> value.toString()
    is Number -> value.toDouble()
    is Map<*, *> -> JSONObject(value)
    is Collection<*> -> JSONArray(value)
    is Array<*> -> JSONArray(value.toList())
    is IntArray -> JSONArray(value.toList())
    is LongArray -> JSONArray(value.toList())
    is DoubleArray -> JSONArray(value.toList())
    is Enum<*> -> value.name
    else -> value.toString()
}

private fun checkDouble(d: Double): Double { if (d.isNaN() || d.isInfinite()) throw JSONException("JSON does not allow non-finite numbers."); return d }

internal fun numberToString(n: Number): String = when (n) {
    is Int, is Long, is Short, is Byte -> n.toString()
    else -> {
        val d = checkDouble(n.toDouble())
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString()
        else d.toString().let { s -> if ('E' in s) s.replace("E", "E") else s }
    }
}

internal fun quote(s: String, out: StringBuilder) {
    out.append('"')
    var prev = ' '
    for (c in s) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '/' -> if (prev == '<') out.append("\\/") else out.append(c)
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ' || c in '\u0080'..'\u009f' || c in ' '..'⃿') {
                out.append("\\u"); out.append(c.code.toString(16).padStart(4, '0'))
            } else out.append(c)
        }
        prev = c
    }
    out.append('"')
}

internal fun write(value: Any?, out: StringBuilder, indent: Int, level: Int) {
    when (value) {
        null, JSONObject.NULL -> out.append("null")
        is JSONObject -> value.write(out, indent, level)
        is JSONArray -> value.write(out, indent, level)
        is String -> quote(value, out)
        is Number -> out.append(numberToString(value))
        is Boolean -> out.append(value)
        else -> quote(value.toString(), out)
    }
}

private fun newline(out: StringBuilder, indent: Int, level: Int) { if (indent > 0) { out.append('\n'); repeat(indent * level) { out.append(' ') } } }

private fun toBool(v: Any?): Boolean? = when (v) { is Boolean -> v; is String -> when (v.lowercase()) { "true" -> true; "false" -> false; else -> null }; else -> null }
private fun toNumber(v: Any?): Number? = when (v) { is Number -> v; is String -> v.trim().let { s -> s.toLongOrNull() ?: s.toDoubleOrNull() }; else -> null }
private fun toStr(v: Any?): String? = when (v) { null, JSONObject.NULL -> null; is String -> v; is Number -> numberToString(v); else -> v.toString() }

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(source: String) : this(JSONTokener(source))
    constructor(tokener: JSONTokener) : this() {
        val v = tokener.nextValue()
        if (v !is JSONObject) throw JSONException("A JSONObject text must begin with '{'")
        map.putAll(v.map)
    }
    constructor(source: Map<*, *>) : this() { source.forEach { (k, v) -> if (v != null) map[k.toString()] = wrapValue(v) } }
    constructor(source: JSONObject, names: Array<String>) : this() { names.forEach { n -> source.opt(n)?.let { map[n] = it } } }

    fun length() = map.size
    fun has(name: String) = map.containsKey(name)
    fun isNull(name: String) = map[name].let { it == null || it === NULL }
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun keySet(): MutableSet<String> = map.keys
    fun names(): JSONArray? = if (map.isEmpty()) null else JSONArray(map.keys.toList())
    fun remove(name: String): Any? = map.remove(name)
    fun toMap(): Map<String, Any?> = map.mapValues { (_, v) -> unwrap(v) }
    fun isEmpty() = map.isEmpty()

    fun put(name: String, value: Any?): JSONObject {
        if (value == null) map.remove(name)
        else { val w = wrapValue(value); if (w is Double) checkDouble(w); map[name] = w }
        return this
    }
    fun put(name: String, value: Boolean) = put(name, value as Any)
    fun put(name: String, value: Int) = put(name, value as Any)
    fun put(name: String, value: Long) = put(name, value as Any)
    fun put(name: String, value: Double) = put(name, value as Any)
    fun put(name: String, value: Float) = put(name, value.toDouble() as Any)
    fun put(name: String, value: Collection<*>) = put(name, JSONArray(value) as Any)
    fun put(name: String, value: Map<*, *>) = put(name, JSONObject(value) as Any)
    fun putOpt(name: String?, value: Any?): JSONObject = if (name == null || value == null) this else put(name, value)
    fun accumulate(name: String, value: Any?): JSONObject {
        val cur = map[name]
        when (cur) {
            null -> put(name, if (value is JSONArray) JSONArray().put(value) else value)
            is JSONArray -> cur.put(value)
            else -> map[name] = JSONArray().put(cur).put(value)
        }
        return this
    }
    fun append(name: String, value: Any?): JSONObject {
        val cur = map[name]
        when (cur) { null -> map[name] = JSONArray().put(value); is JSONArray -> cur.put(value); else -> throw JSONException("JSONObject[$name] is not a JSONArray.") }
        return this
    }
    fun increment(name: String): JSONObject = put(name, (optLong(name, 0)) + 1)

    fun opt(name: String): Any? = map[name]
    fun get(name: String): Any = map[name] ?: throw JSONException("JSONObject[\"$name\"] not found.")
    private fun fail(name: String, type: String): Nothing = throw JSONException("JSONObject[\"$name\"] is not a $type.")

    fun getString(name: String): String = get(name).let { if (it === NULL) fail(name, "string") else toStr(it)!! }
    fun getBoolean(name: String): Boolean = toBool(get(name)) ?: fail(name, "Boolean")
    fun getInt(name: String): Int = toNumber(get(name))?.toInt() ?: fail(name, "int")
    fun getLong(name: String): Long = toNumber(get(name))?.toLong() ?: fail(name, "long")
    fun getDouble(name: String): Double = toNumber(get(name))?.toDouble() ?: fail(name, "double")
    fun getJSONObject(name: String): JSONObject = get(name) as? JSONObject ?: fail(name, "JSONObject")
    fun getJSONArray(name: String): JSONArray = get(name) as? JSONArray ?: fail(name, "JSONArray")

    fun optString(name: String): String = optString(name, "")
    fun optString(name: String, fallback: String): String = map[name].let { if (it == null || it === NULL) fallback else toStr(it)!! }
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = toBool(map[name]) ?: fallback
    fun optInt(name: String, fallback: Int = 0): Int = toNumber(map[name])?.toInt() ?: fallback
    fun optLong(name: String, fallback: Long = 0L): Long = toNumber(map[name])?.toLong() ?: fallback
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = toNumber(map[name])?.toDouble() ?: fallback
    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray
    fun optNumber(name: String): Number? = toNumber(map[name])

    internal fun write(out: StringBuilder, indent: Int, level: Int) {
        out.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) out.append(',')
            first = false
            newline(out, indent, level + 1)
            quote(k, out); out.append(':'); if (indent > 0) out.append(' ')
            write(v, out, indent, level + 1)
        }
        if (!first) newline(out, indent, level)
        out.append('}')
    }
    override fun toString(): String = StringBuilder().also { write(it, 0, 0) }.toString()
    fun toString(indentFactor: Int): String = StringBuilder().also { write(it, indentFactor, 0) }.toString()
    override fun equals(other: Any?) = other is JSONObject && other.map == map
    override fun hashCode() = map.hashCode()

    companion object {
        val NULL: Any = Null
        fun quote(s: String?): String = if (s == null) "\"\"" else StringBuilder().also { quote(s, it) }.toString()
        fun numberToString(n: Number) = org.json.numberToString(n)
        fun wrap(o: Any?): Any? = wrapValue(o)
        fun getNames(o: JSONObject): Array<String>? = if (o.length() == 0) null else o.keySet().toTypedArray()
        internal fun unwrap(v: Any?): Any? = when (v) { NULL -> null; is JSONObject -> v.toMap(); is JSONArray -> v.toList(); else -> v }
    }
}

class JSONArray() : Iterable<Any?> {
    private val list = ArrayList<Any?>()

    constructor(source: String) : this(JSONTokener(source))
    constructor(tokener: JSONTokener) : this() {
        val v = tokener.nextValue()
        if (v !is JSONArray) throw JSONException("A JSONArray text must start with '['")
        list.addAll(v.list)
    }
    constructor(source: Collection<*>) : this() { source.forEach { list += wrapValue(it) } }
    constructor(source: Iterable<*>) : this() { source.forEach { list += wrapValue(it) } }

    fun length() = list.size
    fun isEmpty() = list.isEmpty()
    override fun iterator(): Iterator<Any?> = list.iterator()
    fun toList(): List<Any?> = list.map { JSONObject.unwrap(it) }
    fun remove(index: Int): Any? = if (index in list.indices) list.removeAt(index) else null
    fun clear() = list.clear()

    fun put(value: Any?): JSONArray { list += wrapValue(value).also { if (it is Double) checkDouble(it) }; return this }
    fun put(value: Boolean) = put(value as Any)
    fun put(value: Int) = put(value as Any)
    fun put(value: Long) = put(value as Any)
    fun put(value: Double) = put(value as Any)
    fun put(value: Collection<*>) = put(JSONArray(value) as Any)
    fun put(value: Map<*, *>) = put(JSONObject(value) as Any)
    fun put(index: Int, value: Any?): JSONArray {
        if (index < 0) throw JSONException("JSONArray[$index] not found.")
        while (list.size <= index) list += JSONObject.NULL
        list[index] = wrapValue(value); return this
    }
    fun putAll(values: Collection<*>): JSONArray { values.forEach { put(it) }; return this }

    fun opt(index: Int): Any? = list.getOrNull(index)
    fun get(index: Int): Any = list.getOrNull(index) ?: throw JSONException("JSONArray[$index] not found.")
    fun isNull(index: Int) = opt(index).let { it == null || it === JSONObject.NULL }
    private fun fail(index: Int, type: String): Nothing = throw JSONException("JSONArray[$index] is not a $type.")
    fun getString(index: Int): String = get(index).let { if (it === JSONObject.NULL) fail(index, "String") else toStr(it)!! }
    fun getBoolean(index: Int): Boolean = toBool(get(index)) ?: fail(index, "boolean")
    fun getInt(index: Int): Int = toNumber(get(index))?.toInt() ?: fail(index, "int")
    fun getLong(index: Int): Long = toNumber(get(index))?.toLong() ?: fail(index, "long")
    fun getDouble(index: Int): Double = toNumber(get(index))?.toDouble() ?: fail(index, "double")
    fun getJSONObject(index: Int): JSONObject = get(index) as? JSONObject ?: fail(index, "JSONObject")
    fun getJSONArray(index: Int): JSONArray = get(index) as? JSONArray ?: fail(index, "JSONArray")
    fun optString(index: Int, fallback: String = ""): String = opt(index).let { if (it == null || it === JSONObject.NULL) fallback else toStr(it)!! }
    fun optBoolean(index: Int, fallback: Boolean = false) = toBool(opt(index)) ?: fallback
    fun optInt(index: Int, fallback: Int = 0) = toNumber(opt(index))?.toInt() ?: fallback
    fun optLong(index: Int, fallback: Long = 0L) = toNumber(opt(index))?.toLong() ?: fallback
    fun optDouble(index: Int, fallback: Double = Double.NaN) = toNumber(opt(index))?.toDouble() ?: fallback
    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray
    fun join(separator: String): String = list.joinToString(separator) { StringBuilder().also { b -> write(it, b, 0, 0) }.toString() }

    internal fun write(out: StringBuilder, indent: Int, level: Int) {
        out.append('[')
        list.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            newline(out, indent, level + 1)
            write(v, out, indent, level + 1)
        }
        if (list.isNotEmpty()) newline(out, indent, level)
        out.append(']')
    }
    override fun toString(): String = StringBuilder().also { write(it, 0, 0) }.toString()
    fun toString(indentFactor: Int): String = StringBuilder().also { write(it, indentFactor, 0) }.toString()
    override fun equals(other: Any?) = other is JSONArray && other.list == list
    override fun hashCode() = list.hashCode()
}

class JSONTokener(private val s: String) {
    private var i = 0
    private fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == '﻿')) i++ }
    private fun error(msg: String): Nothing = throw JSONException("$msg at $i [character ${i + 1}]")
    fun more(): Boolean { skip(); return i < s.length }

    fun nextValue(): Any {
        skip()
        if (i >= s.length) error("End of input")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"', '\'' -> str(c)
            else -> literal()
        }
    }
    private fun obj(): JSONObject {
        i++; val o = JSONObject()
        skip(); if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            skip()
            val key = when (val c = s.getOrNull(i)) { '"', '\'' -> str(c); null -> error("A JSONObject text must end with '}'"); else -> literal().toString() }
            skip(); if (s.getOrNull(i) != ':') error("Expected a ':' after a key"); i++
            val v = nextValue()
            if (o.has(key)) error("Duplicate key \"$key\"")
            o.put(key, v)
            skip()
            when (s.getOrNull(i)) { ',', ';' -> { i++; skip(); if (s.getOrNull(i) == '}') { i++; return o } }; '}' -> { i++; return o }; else -> error("Expected a ',' or '}'") }
        }
    }
    private fun arr(): JSONArray {
        i++; val a = JSONArray()
        skip(); if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            skip()
            if (s.getOrNull(i) == ',') { a.put(JSONObject.NULL); i++; continue }
            a.put(nextValue())
            skip()
            when (s.getOrNull(i)) { ',' -> { i++; skip(); if (s.getOrNull(i) == ']') { i++; return a } }; ']' -> { i++; return a }; else -> error("Expected a ',' or ']'") }
        }
    }
    private fun str(quote: Char): String {
        i++
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) error("Unterminated string")
            val c = s[i++]
            when {
                c == quote -> return b.toString()
                c == '\\' -> {
                    if (i >= s.length) error("Unterminated string")
                    when (val e = s[i++]) {
                        'b' -> b.append('\b'); 't' -> b.append('\t'); 'n' -> b.append('\n'); 'f' -> b.append('\u000C'); 'r' -> b.append('\r')
                        'u' -> { if (i + 4 > s.length) error("Illegal escape."); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        '"', '\'', '\\', '/' -> b.append(e)
                        else -> error("Illegal escape.")
                    }
                }
                c == '\n' || c == '\r' -> error("Unterminated string")
                else -> b.append(c)
            }
        }
    }
    private fun literal(): Any {
        val start = i
        while (i < s.length && s[i] !in ",:]}/\\\"[{;=#" && !s[i].isWhitespace()) i++
        val t = s.substring(start, i)
        if (t.isEmpty()) error("Missing value")
        return when {
            t.equals("true", true) -> true
            t.equals("false", true) -> false
            t.equals("null", true) -> JSONObject.NULL
            else -> t.toIntOrNull() ?: t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { t[0].isDigit() || t[0] == '-' || t[0] == '.' } ?: t
        }
    }
}

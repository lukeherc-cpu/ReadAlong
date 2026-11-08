// The desktop app's JVM-only calls, for the iPhone (the shared files import this package).
package com.example.readalong.compat

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.reflect.KClass
import platform.Foundation.NSBundle

/** `Foo::class.java.getResourceAsStream("/fonts/x.ttf")`: the desktop app's jar resources are the app bundle's files here. */
class JavaClass internal constructor(val kotlin: KClass<*>) {
    fun getResourceAsStream(path: String): InputStream? =
        File((NSBundle.mainBundle.resourcePath ?: "") + "/" + path.removePrefix("/")).takeIf { it.isFile }?.let(::FileInputStream)
    val simpleName: String? get() = kotlin.simpleName
}
val KClass<*>.java: JavaClass get() = JavaClass(this)

object Runtime {
    private val runtime = RuntimeInfo()
    fun getRuntime() = runtime
    class RuntimeInfo {
        fun availableProcessors(): Int = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt()
        fun maxMemory(): Long = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong()
        fun totalMemory(): Long = maxMemory()
        fun freeMemory(): Long = maxMemory() / 2
        fun addShutdownHook(@Suppress("UNUSED_PARAMETER") hook: Any) {}
    }
}

fun System.getenv(@Suppress("UNUSED_PARAMETER") name: String): String? = null

/** There are no other programs to start on iOS; the desktop's fallbacks that would start one fail instead. */
class ProcessBuilder(vararg val command: String) {
    fun start(): Nothing = throw java.io.IOException("iOS can't start ${command.firstOrNull() ?: "programs"}.")
}

/**
 * LinkedHashMap in access order with a size limit, for the desktop's `object : LinkedHashMap(n, .75f, true)`
 * caches (Kotlin/Native's LinkedHashMap can't be subclassed or put in access order). The build rewrites them to this.
 */
class LruMap<K, V>(private val max: Int) : MutableMap<K, V> {
    private val map = LinkedHashMap<K, V>()
    override val size get() = map.size
    override fun isEmpty() = map.isEmpty()
    override fun containsKey(key: K) = map.containsKey(key)
    override fun containsValue(value: V) = map.containsValue(value)
    override fun get(key: K): V? {
        if (!map.containsKey(key)) return null
        val value = map.remove(key)
        @Suppress("UNCHECKED_CAST")
        map[key] = value as V
        return value
    }
    override fun put(key: K, value: V): V? {
        val old = map.remove(key)
        map[key] = value
        while (map.size > max) map.remove(map.keys.first())
        return old
    }
    override fun remove(key: K) = map.remove(key)
    override fun putAll(from: Map<out K, V>) = from.forEach { (k, v) -> put(k, v) }
    override fun clear() = map.clear()
    override val keys get() = map.keys
    override val values get() = map.values
    override val entries get() = map.entries
}

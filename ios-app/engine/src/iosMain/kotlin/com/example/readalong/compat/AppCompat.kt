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

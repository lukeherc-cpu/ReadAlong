// org.junit.Assert as a package, so `import org.junit.Assert.*` works in Kotlin.
@file:Suppress("PackageDirectoryMismatch")
package org.junit.Assert

import org.junit.Class

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)
fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)
fun assertFalse(message: String?, condition: Boolean) = kotlin.test.assertFalse(condition, message)
fun assertNull(value: Any?) = kotlin.test.assertNull(value)
fun assertNull(message: String?, value: Any?) = kotlin.test.assertNull(value, message)
fun assertNotNull(value: Any?) { kotlin.test.assertNotNull(value) }
fun assertNotNull(message: String?, value: Any?) { kotlin.test.assertNotNull(value, message) }
fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
fun assertEquals(message: String?, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
fun assertEquals(expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual)
fun assertEquals(message: String?, expected: Long, actual: Long) = kotlin.test.assertEquals(expected, actual, message)
fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)
fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta, message)
fun assertEquals(expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta)
fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) = kotlin.test.assertEquals(expected, actual, delta, message)
fun assertNotEquals(unexpected: Any?, actual: Any?) = kotlin.test.assertNotEquals(unexpected, actual)
fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) = kotlin.test.assertNotEquals(unexpected, actual, message)
fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)
fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = kotlin.test.assertContentEquals(expected, actual)
fun assertArrayEquals(expected: IntArray, actual: IntArray) = kotlin.test.assertContentEquals(expected, actual)
fun assertArrayEquals(expected: LongArray, actual: LongArray) = kotlin.test.assertContentEquals(expected, actual)
fun assertArrayEquals(expected: Array<out Any?>, actual: Array<out Any?>) = kotlin.test.assertContentEquals(expected.toList(), actual.toList())
fun assertArrayEquals(message: String?, expected: ByteArray, actual: ByteArray) = kotlin.test.assertContentEquals(expected, actual, message)
fun fail(message: String? = null): Nothing = kotlin.test.fail(message)
fun <T : Throwable> assertThrows(type: kotlin.reflect.KClass<T>, block: () -> Unit): T = kotlin.test.assertFailsWith(type, block = block)
fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit): T = kotlin.test.assertFailsWith(type.kotlin, block = block)

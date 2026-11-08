package java.util.concurrent.atomic

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong as KAtomicLong
import kotlin.concurrent.AtomicReference as KAtomicReference

class AtomicBoolean(initial: Boolean = false) {
    private val v = AtomicInt(if (initial) 1 else 0)
    fun get() = v.value == 1
    fun set(value: Boolean) { v.value = if (value) 1 else 0 }
    fun getAndSet(value: Boolean) = v.getAndSet(if (value) 1 else 0) == 1
    fun compareAndSet(expect: Boolean, update: Boolean) = v.compareAndSet(if (expect) 1 else 0, if (update) 1 else 0)
}
class AtomicInteger(initial: Int = 0) {
    private val v = AtomicInt(initial)
    fun get() = v.value
    fun set(value: Int) { v.value = value }
    fun incrementAndGet() = v.incrementAndGet()
    fun decrementAndGet() = v.decrementAndGet()
    fun getAndIncrement() = v.getAndIncrement()
    fun addAndGet(d: Int) = v.addAndGet(d)
    fun getAndSet(value: Int) = v.getAndSet(value)
    fun compareAndSet(e: Int, u: Int) = v.compareAndSet(e, u)
}
class AtomicLong(initial: Long = 0) {
    private val v = KAtomicLong(initial)
    fun get() = v.value
    fun set(value: Long) { v.value = value }
    fun incrementAndGet() = v.incrementAndGet()
    fun addAndGet(d: Long) = v.addAndGet(d)
    fun getAndSet(value: Long) = v.getAndSet(value)
    fun compareAndSet(e: Long, u: Long) = v.compareAndSet(e, u)
}
class AtomicReference<T>(initial: T) {
    private val v = KAtomicReference(initial)
    fun get(): T = v.value
    fun set(value: T) { v.value = value }
    fun getAndSet(value: T): T = v.getAndSet(value)
    fun compareAndSet(e: T, u: T) = v.compareAndSet(e, u)
}

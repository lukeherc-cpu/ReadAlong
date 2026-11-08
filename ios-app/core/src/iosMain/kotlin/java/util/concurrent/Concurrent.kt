// java.util.concurrent for iOS: lock-protected collections and a blocking queue on NSCondition.
package java.util.concurrent

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.NSRecursiveLock

enum class TimeUnit(private val nanos: Long) {
    NANOSECONDS(1), MICROSECONDS(1_000), MILLISECONDS(1_000_000), SECONDS(1_000_000_000), MINUTES(60_000_000_000), HOURS(3_600_000_000_000), DAYS(86_400_000_000_000);
    fun toMillis(d: Long) = d * nanos / 1_000_000
    fun toSeconds(d: Long) = d * nanos / 1_000_000_000
    fun toNanos(d: Long) = d * nanos
    fun toMinutes(d: Long) = d * nanos / 60_000_000_000
    fun sleep(d: Long) { platform.posix.usleep((toMillis(d) * 1000).toUInt()) }
}

class TimeoutException(message: String? = null) : Exception(message)
class ExecutionException(message: String?, cause: Throwable?) : Exception(message, cause)
class CancellationException(message: String? = null) : IllegalStateException(message)

internal inline fun <R> NSRecursiveLock.locked(block: () -> R): R { lock(); try { return block() } finally { unlock() } }

class ConcurrentHashMap<K, V>(initial: Int = 16) : MutableMap<K, V> {
    private val map = HashMap<K, V>(initial)
    private val lock = NSRecursiveLock()
    override val size: Int get() = lock.locked { map.size }
    override fun isEmpty() = lock.locked { map.isEmpty() }
    override fun containsKey(key: K) = lock.locked { map.containsKey(key) }
    override fun containsValue(value: V) = lock.locked { map.containsValue(value) }
    override fun get(key: K): V? = lock.locked { map[key] }
    override fun put(key: K, value: V): V? = lock.locked { map.put(key, value) }
    override fun remove(key: K): V? = lock.locked { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.locked { map.putAll(from) }
    override fun clear() = lock.locked { map.clear() }
    // Snapshots, so iterating never races a writer.
    override val keys: MutableSet<K> get() = lock.locked { map.keys.toMutableSet() }
    override val values: MutableCollection<V> get() = lock.locked { map.values.toMutableList() }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = lock.locked { LinkedHashMap(map).entries }
    fun putIfAbsent(key: K, value: V): V? = lock.locked { map[key] ?: run { map[key] = value; null } }
    fun getOrPut(key: K, defaultValue: () -> V): V = lock.locked { map.getOrPut(key, defaultValue) }
    fun computeIfAbsent(key: K, f: (K) -> V): V = lock.locked { map.getOrPut(key) { f(key) } }
    fun compute(key: K, f: (K, V?) -> V?): V? = lock.locked { val v = f(key, map[key]); if (v == null) map.remove(key) else map[key] = v; v }
    fun merge(key: K, value: V, f: (V, V) -> V?): V? = lock.locked { val old = map[key]; val v = if (old == null) value else f(old, value); if (v == null) map.remove(key) else map[key] = v; v }
    override fun equals(other: Any?) = lock.locked { map == other }
    override fun hashCode() = lock.locked { map.hashCode() }
    override fun toString() = lock.locked { map.toString() }
}

class CopyOnWriteArrayList<E>(initial: Collection<E> = emptyList()) : MutableList<E> {
    private var items: List<E> = initial.toList()
    private val lock = NSRecursiveLock()
    private fun write(change: (MutableList<E>) -> Unit) = lock.locked { items = items.toMutableList().also(change) }
    override val size get() = items.size
    override fun contains(element: E) = items.contains(element)
    override fun containsAll(elements: Collection<E>) = items.containsAll(elements)
    override fun get(index: Int) = items[index]
    override fun indexOf(element: E) = items.indexOf(element)
    override fun isEmpty() = items.isEmpty()
    override fun iterator(): MutableIterator<E> = items.toMutableList().iterator()
    override fun lastIndexOf(element: E) = items.lastIndexOf(element)
    override fun listIterator(): MutableListIterator<E> = items.toMutableList().listIterator()
    override fun listIterator(index: Int): MutableListIterator<E> = items.toMutableList().listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = items.subList(fromIndex, toIndex).toMutableList()
    override fun add(element: E): Boolean { write { it.add(element) }; return true }
    override fun add(index: Int, element: E) = write { it.add(index, element) }
    override fun addAll(index: Int, elements: Collection<E>): Boolean { write { it.addAll(index, elements) }; return true }
    override fun addAll(elements: Collection<E>): Boolean { write { it.addAll(elements) }; return true }
    override fun clear() = write { it.clear() }
    override fun remove(element: E): Boolean { var r = false; write { r = it.remove(element) }; return r }
    override fun removeAll(elements: Collection<E>): Boolean { var r = false; write { r = it.removeAll(elements) }; return r }
    override fun removeAt(index: Int): E { var r: E? = null; write { r = it.removeAt(index) }; @Suppress("UNCHECKED_CAST") return r as E }
    override fun retainAll(elements: Collection<E>): Boolean { var r = false; write { r = it.retainAll(elements) }; return r }
    override fun set(index: Int, element: E): E { var r: E? = null; write { r = it.set(index, element) }; @Suppress("UNCHECKED_CAST") return r as E }
    fun removeIf(predicate: (E) -> Boolean): Boolean { var r = false; write { r = it.removeAll(predicate) }; return r }
}

open class LinkedBlockingQueue<E> {
    private val items = ArrayDeque<E>()
    private val condition = NSCondition()
    fun put(e: E) { condition.lock(); items.addLast(e); condition.signal(); condition.unlock() }
    fun offer(e: E): Boolean { put(e); return true }
    fun add(e: E): Boolean { put(e); return true }
    operator fun plusAssign(e: E) { put(e) }
    fun take(): E { condition.lock(); try { while (items.isEmpty()) condition.wait(); return items.removeFirst() } finally { condition.unlock() } }
    fun poll(): E? { condition.lock(); try { return items.removeFirstOrNull() } finally { condition.unlock() } }
    fun poll(timeout: Long, unit: TimeUnit): E? {
        condition.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(unit.toMillis(timeout) / 1000.0)
            while (items.isEmpty()) if (!condition.waitUntilDate(deadline)) break
            return items.removeFirstOrNull()
        } finally { condition.unlock() }
    }
    fun isEmpty(): Boolean { condition.lock(); try { return items.isEmpty() } finally { condition.unlock() } }
    fun isNotEmpty() = !isEmpty()
    fun clear() { condition.lock(); items.clear(); condition.unlock() }
    val size: Int get() { condition.lock(); try { return items.size } finally { condition.unlock() } }
}

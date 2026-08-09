package io.relite.home.util

/**
 * A plain-Kotlin, framework-independent LRU cache bounded by total entry
 * *byte size* rather than entry count. Extracted out of [IconCache] so the
 * eviction behavior that actually matters (see [IconCache]'s file
 * comment — RMX5303's Native Heap regression) is directly unit-testable
 * on the JVM without Robolectric or a device: `android.util.LruCache`
 * cannot be exercised in a plain `app/src/test` unit test since the
 * Android SDK jar used there only stubs framework methods.
 */
class BoundedByteCache<K, V>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {

    private val entries = LinkedHashMap<K, V>(16, 0.75f, true) // access-order for LRU
    private var currentBytes = 0

    val size: Int get() = entries.size
    val bytesUsed: Int get() = currentBytes

    fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries.remove(key)?.let { currentBytes -= sizeOf(it) }
        entries[key] = value
        currentBytes += sizeOf(value)
        evictToFit()
    }

    fun remove(key: K) {
        entries.remove(key)?.let { currentBytes -= sizeOf(it) }
    }

    fun keys(): Set<K> = entries.keys.toSet()

    fun clear() {
        entries.clear()
        currentBytes = 0
    }

    private fun evictToFit() {
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val (_, value) = iterator.next()
            currentBytes -= sizeOf(value)
            iterator.remove()
        }
    }
}

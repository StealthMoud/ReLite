package io.relite.home.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake "icon" whose size stands in for a real Bitmap's byteCount. */
private data class FakeIcon(val sizeBytes: Int)

class BoundedByteCacheTest {

    @Test
    fun `entries within budget are all retained`() {
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 1000) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.put("b", FakeIcon(100))
        cache.put("c", FakeIcon(100))

        assertEquals(FakeIcon(100), cache.get("a"))
        assertEquals(FakeIcon(100), cache.get("b"))
        assertEquals(FakeIcon(100), cache.get("c"))
        assertEquals(300, cache.bytesUsed)
    }

    @Test
    fun `real-device regression - hundreds of full-res icons must not fit an unbounded cache`() {
        // RMX5303 finding: caching ~385 full-resolution AdaptiveIconDrawables
        // (bounded only by entry count, not size) pushed Native Heap to
        // ~136 MB. A single decoded icon at that device's density was on
        // the order of ~350 KB; this asserts a byte-bounded cache never
        // lets that scale unbounded with the number of installed apps.
        val iconSizeBytes = 350_000
        val maxBytes = 6 * 1024 * 1024 // matches IconCache.DEFAULT_MAX_BYTES
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes) { it.sizeBytes }

        repeat(385) { i -> cache.put("app$i", FakeIcon(iconSizeBytes)) }

        assertTrue("cache must stay within its byte budget", cache.bytesUsed <= maxBytes)
        assertTrue("cache must not hold all 385 icons", cache.size < 385)
    }

    @Test
    fun `never exceeds byte budget as entries are added`() {
        val cache = BoundedByteCache<Int, FakeIcon>(maxBytes = 500) { it.sizeBytes }
        for (i in 0 until 50) {
            cache.put(i, FakeIcon(100))
            assertTrue("bytesUsed (${cache.bytesUsed}) must never exceed maxBytes", cache.bytesUsed <= 500)
        }
    }

    @Test
    fun `evicts least-recently-used entry first`() {
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 250) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.put("b", FakeIcon(100))
        cache.get("a") // touch "a" so "b" becomes the least-recently-used
        cache.put("c", FakeIcon(100)) // forces an eviction: total would be 300 > 250

        assertNull("least-recently-used entry should have been evicted", cache.get("b"))
        assertEquals(FakeIcon(100), cache.get("a"))
        assertEquals(FakeIcon(100), cache.get("c"))
    }

    @Test
    fun `re-putting the same key updates size accounting instead of double-counting`() {
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 1000) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.put("a", FakeIcon(200))

        assertEquals(200, cache.bytesUsed)
        assertEquals(1, cache.size)
    }

    @Test
    fun `remove frees its byte accounting`() {
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 1000) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.remove("a")

        assertEquals(0, cache.bytesUsed)
        assertNull(cache.get("a"))
    }

    @Test
    fun `keys reflects current contents after eviction`() {
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 150) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.put("b", FakeIcon(100)) // evicts "a"

        assertEquals(setOf("b"), cache.keys())
    }

    @Test
    fun `clear drops every entry and resets byte accounting`() {
        // Backs IconCache.clear(), called from onTrimMemory() under real
        // memory pressure (section 13, v0.2.0) — icons are cheap to
        // re-render from LauncherApps, so there's no reason to keep any of
        // them once the system says memory is tight.
        val cache = BoundedByteCache<String, FakeIcon>(maxBytes = 1000) { it.sizeBytes }
        cache.put("a", FakeIcon(100))
        cache.put("b", FakeIcon(100))

        cache.clear()

        assertEquals(0, cache.bytesUsed)
        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}

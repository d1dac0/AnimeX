package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the #72 bounds the provider clients now sit behind: filling past the cap evicts the
 * oldest entry, expired entries are dropped on write, and a hit never extends an entry's life.
 * The clock is injected, so every case is deterministic — no sleeps.
 */
class BoundedMapsTest {
    private var now = 0L

    // ---- BoundedTtlMap -----------------------------------------------------------------------

    @Test
    fun `ttl map evicts the oldest entry when written past the cap`() {
        val map = BoundedTtlMap<String, Int>(capacity = 3, ttlMs = 60_000, nowMs = { now })
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        map.put("d", 4)
        assertNull(map.get("a"))
        assertEquals(2, map.get("b"))
        assertEquals(3, map.get("c"))
        assertEquals(4, map.get("d"))
        assertEquals(3, map.size)
    }

    @Test
    fun `ttl map drops expired entries on write`() {
        val map = BoundedTtlMap<String, Int>(capacity = 8, ttlMs = 60_000, nowMs = { now })
        map.put("stale", 1)
        // At exactly the TTL the entry is stale, as the old read checks took it — still held,
        // though: nothing has written since it did.
        now = 60_000L
        assertEquals(1, map.size)
        map.put("fresh", 2)
        assertNull(map.get("stale"))
        assertEquals(1, map.size)
        assertEquals(2, map.get("fresh"))
    }

    @Test
    fun `ttl map answers a live entry but a hit never refreshes it`() {
        val map = BoundedTtlMap<String, Int>(capacity = 8, ttlMs = 60_000, nowMs = { now })
        map.put("k", 1)
        now = 59_999L
        assertEquals(1, map.get("k"))
        now = 60_000L
        assertNull(map.get("k"))
    }

    @Test
    fun `ttl map overwrite is a fresh write that evicts nothing`() {
        val map = BoundedTtlMap<String, Int>(capacity = 2, ttlMs = 60_000, nowMs = { now })
        map.put("a", 1)
        map.put("b", 2)
        now = 50_000L
        map.put("a", 3)
        // The old pair was re-put with a new timestamp; neither entry leaves the map for it.
        assertEquals(2, map.size)
        now = 105_000L
        assertEquals(3, map.get("a"))
        assertNull(map.get("b"))
    }

    @Test
    fun `ttl map remove drops the entry whatever its age`() {
        val map = BoundedTtlMap<String, Int>(capacity = 8, ttlMs = 60_000, nowMs = { now })
        map.put("k", 1)
        assertEquals(1, map.remove("k"))
        assertNull(map.get("k"))
        assertEquals(0, map.size)
    }

    // ---- BoundedLruMap -----------------------------------------------------------------------

    @Test
    fun `lru map evicts the oldest entry when written past the cap`() {
        val map = BoundedLruMap<String, Int>(capacity = 2)
        map.put("a", 1)
        map.put("b", 2)
        map.put("c", 3)
        assertNull(map.get("a"))
        assertEquals(2, map.get("b"))
        assertEquals(3, map.get("c"))
        assertEquals(2, map.size)
    }

    @Test
    fun `lru map a read refreshes recency so the touched entry survives`() {
        val map = BoundedLruMap<String, Int>(capacity = 2)
        map.put("a", 1)
        map.put("b", 2)
        assertEquals(1, map.get("a"))
        map.put("c", 3)
        // `a` was read after `b` was written, so `b` is the one that leaves.
        assertNull(map.get("b"))
        assertEquals(1, map.get("a"))
        assertEquals(3, map.get("c"))
    }

    @Test
    fun `lru map overwrite updates the value and evicts nothing`() {
        val map = BoundedLruMap<String, Int>(capacity = 2)
        map.put("a", 1)
        map.put("b", 2)
        map.put("a", 3)
        assertEquals(2, map.size)
        assertEquals(3, map.get("a"))
        assertEquals(2, map.get("b"))
    }

    @Test
    fun `lru map remove drops the entry`() {
        val map = BoundedLruMap<String, Int>(capacity = 2)
        map.put("a", 1)
        assertEquals(1, map.remove("a"))
        assertNull(map.get("a"))
        assertEquals(0, map.size)
    }
}

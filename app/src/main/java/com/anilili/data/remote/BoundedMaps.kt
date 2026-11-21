package com.anilili.data.remote

/**
 * The two bounded in-memory cache shapes the provider clients keep (#72).
 *
 * Before #72 these were bare `ConcurrentHashMap`s whose entries lived for the whole process — and
 * a TV box can stay up for days, so every entry a viewer caused was held for all of it: a full
 * parsed play response per episode, up to twenty thousand episode rows per title. The obvious
 * tool, `android.util.LruCache`, would only have half-worked: it is a null-returning stub in the
 * unit-test JVM (`unitTests.returnDefaultValues = true`), so nothing there could prove it evicts.
 * Both shapes here are plain collections with plain JUnit tests (see `BoundedMapsTest`), and both
 * guard their internals with one lock, because the clients that use them hand their work to
 * `Dispatchers.IO` and touch the same maps from several threads at once.
 */

/**
 * A bounded map whose entries also expire, for a value whose freshness is part of its meaning:
 * signed stream URLs, an episode list an airing title gains a row on.
 *
 * Writes are what bound it. A [put] first drops every expired entry — reads answer null for the
 * key they were asked about and sweep only that one, so a map nobody rewrites still empties —
 * then evicts the entry written longest ago until the new one fits. A live hit never refreshes
 * its timestamp: `PipeClient.plays` has always measured its two-minute TTL from the write, and a
 * read that extended a signed URL's life past its signing would change what the caller gets.
 * Overwriting a key with [put] is a fresh write and starts its TTL again, exactly as re-putting
 * the timestamped pair always did.
 *
 * [nowMs] is the clock. Production callers pass whichever clock the old read check used —
 * `SystemClock.elapsedRealtime()` or `System.currentTimeMillis()` — and a test passes one it can
 * move, so expiry is proven deterministically, without sleeps.
 */
internal class BoundedTtlMap<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long,
) {
    private class Entry<V>(val value: V, val writtenAtMs: Long)

    private val lock = Any()

    /** Insertion order: iteration runs oldest-write first, which is the eviction order. */
    private val entries = LinkedHashMap<K, Entry<V>>()

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(ttlMs > 0) { "ttlMs must be positive, was $ttlMs" }
    }

    /** Entries held, live and stale. A stale one stays until a write or a read of its key drops it. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** The value for [key] if it is present and still fresh, else null. */
    operator fun get(key: K): V? = synchronized(lock) {
        val entry = entries[key] ?: return null
        if (nowMs() - entry.writtenAtMs >= ttlMs) {
            entries.remove(key)
            return null
        }
        entry.value
    }

    /** Drops [key] whatever its age, returning its value when it was present and fresh. */
    fun remove(key: K): V? = synchronized(lock) {
        val entry = entries.remove(key) ?: return null
        if (nowMs() - entry.writtenAtMs >= ttlMs) null else entry.value
    }

    /**
     * Writes [value] under [key] with a fresh timestamp, after dropping every expired entry and
     * evicting the oldest live ones the map needs room for. Overwriting the key itself evicts
     * nothing: one entry leaves the map only because another arrived.
     */
    fun put(key: K, value: V) = synchronized(lock) {
        val now = nowMs()
        entries.entries.removeAll { now - it.value.writtenAtMs >= ttlMs }
        entries.remove(key)
        while (entries.size >= capacity) {
            entries.remove(entries.keys.first())
        }
        entries[key] = Entry(value, now)
    }
}

/**
 * A bounded least-recently-used map, for values whose only bound is how many distinct keys the
 * app touches in a session — one episode list per title, one server list per episode probed.
 *
 * A [get] makes its entry the next to survive an eviction, which is what keeps the title a viewer
 * is actually watching ahead of ones they only browsed past. Nothing before #72 was ever evicted,
 * so this refresh is new semantics with nothing older to preserve. [put] evicts only to make room
 * for a genuinely new key — overwriting a key's own value never evicts — and [remove] drops a key
 * outright, which is how the clients invalidate.
 */
internal class BoundedLruMap<K : Any, V : Any>(private val capacity: Int) {
    private val lock = Any()

    /** Access order: iteration runs least-recently-used first, which is the eviction order. */
    private val entries = LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true)

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    /** The value for [key], marking it most recently used when present. */
    operator fun get(key: K): V? = synchronized(lock) { entries[key] }

    /** Drops [key], returning its value when it was present. */
    fun remove(key: K): V? = synchronized(lock) { entries.remove(key) }

    fun put(key: K, value: V) = synchronized(lock) {
        entries.remove(key)
        while (entries.size >= capacity) {
            entries.remove(entries.keys.first())
        }
        entries[key] = value
    }
}

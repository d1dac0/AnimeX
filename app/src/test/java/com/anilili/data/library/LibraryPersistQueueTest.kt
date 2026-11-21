package com.anilili.data.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class LibraryPersistQueueTest {
    private val written = ConcurrentHashMap<String, String>()
    private val writeThreads = CopyOnWriteArrayList<String>()
    private val queue = LibraryPersistQueue(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) { key, encoded ->
        writeThreads += Thread.currentThread().name
        written[key] = encoded
    }

    private fun flushAll() = runBlocking { queue.flush() }

    /** The playback poll's guarantee: whatever persist call was accepted last is what lands. */
    @Test
    fun theLastWriteAcceptedForAKeyIsTheOneThatLands() {
        repeat(50) { round -> queue.persist("history") { "v$round" } }
        flushAll()
        assertEquals("v49", written["history"])
    }

    @Test
    fun keysAreIndependent() {
        queue.persist("history") { "h" }
        queue.persist("watchlist") { "w" }
        flushAll()
        assertEquals("h", written["history"])
        assertEquals("w", written["watchlist"])
    }

    @Test
    fun encodeAndWriteLeaveTheCallerThread() {
        val caller = Thread.currentThread().name
        queue.persist("history") { "h" }
        flushAll()
        assertTrue(writeThreads.isNotEmpty())
        assertTrue(writeThreads.none { it == caller })
    }

    @Test
    fun flushWithNothingPendingReturnsAtOnce() {
        flushAll()
        assertTrue(written.isEmpty())
    }
}

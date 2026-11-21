package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTrackerTest {
    private var now = 1_000_000L
    private val tracker = PlaybackSessionTracker { now }
    private val quietMs = 30_000L

    @Test
    fun quietWhenNoSessionHasBeenOpenInThisProcess() {
        assertFalse(tracker.active.value)
        assertEquals(0L, tracker.quietRemainingMs(quietMs))
    }

    @Test
    fun aNativeSessionBlocksUntilTheQuietWindowHasPassed() {
        tracker.setNative(true)
        assertTrue(tracker.active.value)
        assertNull(tracker.quietRemainingMs(quietMs))

        tracker.setNative(false)
        assertFalse(tracker.active.value)
        assertEquals(quietMs, tracker.quietRemainingMs(quietMs))

        now += 10_000L
        assertEquals(20_000L, tracker.quietRemainingMs(quietMs))

        now += 20_000L
        assertEquals(0L, tracker.quietRemainingMs(quietMs))
    }

    @Test
    fun aStopAndRePrepareWithinTheWindowRestartsIt() {
        tracker.setNative(true)
        tracker.setNative(false)
        now += 25_000L
        // A rebuffer's stop-and-prepare, or the next episode starting.
        tracker.setNative(true)
        assertNull(tracker.quietRemainingMs(quietMs))
        tracker.setNative(false)
        now += 5_000L
        assertEquals(25_000L, tracker.quietRemainingMs(quietMs))
    }

    @Test
    fun anEmbedPlayerCountsWhileMounted() {
        tracker.embedMounted()
        assertTrue(tracker.active.value)
        tracker.embedUnmounted()
        assertFalse(tracker.active.value)
    }

    @Test
    fun overlappingEmbedsDoNotClearEachOther() {
        tracker.embedMounted()
        tracker.embedMounted()
        tracker.embedUnmounted()
        assertTrue(tracker.active.value)
        tracker.embedUnmounted()
        assertFalse(tracker.active.value)
        // An unbalanced unmount cannot drive the count negative and hide the next mount.
        tracker.embedUnmounted()
        tracker.embedMounted()
        assertTrue(tracker.active.value)
    }

    @Test
    fun anExternalHandOffCountsUntilItEnds() {
        tracker.setExternal(true)
        assertTrue(tracker.active.value)
        tracker.setNative(false)
        assertTrue(tracker.active.value)
        tracker.setExternal(false)
        assertFalse(tracker.active.value)
    }

    @Test
    fun anySourceKeepsTheSessionOpen() {
        tracker.setNative(true)
        tracker.embedMounted()
        tracker.setNative(false)
        assertTrue(tracker.active.value)
        tracker.embedUnmounted()
        assertFalse(tracker.active.value)
    }
}

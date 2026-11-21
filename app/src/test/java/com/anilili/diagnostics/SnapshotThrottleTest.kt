package com.anilili.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim-memory snapshot throttle decision (#82), driven with an injected clock so no test
 * sleeps and none depends on `SystemClock`, which reads as 0 on the unit-test JVM.
 */
class SnapshotThrottleTest {
    private var now = 1_000_000L
    private val throttle = SnapshotThrottle(minIntervalMs = 30_000L) { now }

    @Test
    fun theFirstSnapshotForALabelIsAccepted() {
        assertTrue(throttle.accepts("trimMemory"))
    }

    @Test
    fun aRepeatWithinTheWindowIsRefused() {
        assertTrue(throttle.accepts("trimMemory"))
        now += 29_999L
        assertFalse(throttle.accepts("trimMemory"))
    }

    @Test
    fun aRepeatAfterTheWindowIsAcceptedAgain() {
        assertTrue(throttle.accepts("trimMemory"))
        now += 30_000L
        assertTrue(throttle.accepts("trimMemory"))
    }

    @Test
    fun theWindowIsMeasuredFromTheLastAcceptedSnapshotNotTheLastCall() {
        assertTrue(throttle.accepts("trimMemory"))
        now += 10_000L
        assertFalse(throttle.accepts("trimMemory"))
        now += 10_000L
        // Still refused: the refused calls do not push the window out.
        assertFalse(throttle.accepts("trimMemory"))
        now += 10_000L
        assertTrue(throttle.accepts("trimMemory"))
    }

    @Test
    fun labelsThrottleIndependently() {
        assertTrue(throttle.accepts("trimMemory"))
        now += 1_000L
        // A burst on the throttled label says nothing about another label's snapshot.
        assertTrue(throttle.accepts("lowMemory"))
    }
}

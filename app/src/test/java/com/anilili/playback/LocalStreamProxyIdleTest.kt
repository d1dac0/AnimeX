package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamProxyIdleTest {
    private val idleMs = 20 * 60 * 1000L

    @Test
    fun reapsOnceNoConnectionIsOpenAndNothingHappenedForTheIdleWindow() {
        assertTrue(localStreamProxyIdle(nowMs = idleMs, lastActivityMs = 0L, openConnections = 0, idleShutdownMs = idleMs))
    }

    @Test
    fun keepsRunningWithinTheIdleWindow() {
        assertFalse(
            localStreamProxyIdle(nowMs = idleMs - 1, lastActivityMs = 0L, openConnections = 0, idleShutdownMs = idleMs),
        )
    }

    @Test
    fun anOpenConnectionKeepsItRunningHoweverOldTheLastRequest() {
        assertFalse(
            localStreamProxyIdle(
                nowMs = 6 * 60 * 60 * 1000L,
                lastActivityMs = 0L,
                openConnections = 1,
                idleShutdownMs = idleMs,
            ),
        )
    }
}

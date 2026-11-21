package com.anilili.data.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #76: the release-sync worker must not complete a sync while a playback session is open. The
 * decision is extracted as a pure function of `PlaybackStatus.sessionActive` because the worker
 * itself is only reachable through WorkManager; reading the live flag in `doWork` is covered by
 * that one-line wiring, not by a test here.
 */
class ReleaseSyncPlaybackGuardTest {
    @Test
    fun `an open playback session defers the release sync`() {
        assertTrue(releaseSyncYieldsToPlayback(sessionActive = true))
    }

    @Test
    fun `with no session open the release sync runs`() {
        assertFalse(releaseSyncYieldsToPlayback(sessionActive = false))
    }
}

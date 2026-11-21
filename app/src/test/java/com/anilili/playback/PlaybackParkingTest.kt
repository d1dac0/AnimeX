package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving a player screen on television parks the player (pause, then stop) so the codec and the
 * buffer are not held behind Home; every other form factor keeps the loaded pause that powers the
 * notification's resume and the mini player (#61).
 */
class PlaybackParkingTest {
    @Test
    fun televisionParks() {
        assertTrue(shouldParkPlaybackOnExit(isTv = true))
    }

    /** The phone's loaded pause is what the notification's resume button and PiP stand on. */
    @Test
    fun thePhoneKeepsTheLoadedPause() {
        assertFalse(shouldParkPlaybackOnExit(isTv = false))
    }
}

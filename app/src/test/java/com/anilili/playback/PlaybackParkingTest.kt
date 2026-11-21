package com.anilili.playback

import androidx.media3.common.Player
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

    /**
     * A parked player is idle but still holds its item, and play() alone does nothing from idle —
     * it was Play after Home dead until the toggle prepared first, and the same decision now
     * answers the remote's media Play and the ON_START revival after the screensaver (#107).
     */
    @Test
    fun playOnAParkedPlayerPreparesFirst() {
        assertTrue(playerPreparesWhenIdle(Player.STATE_IDLE, hasMediaItem = true))
        assertFalse(playerPreparesWhenIdle(Player.STATE_READY, hasMediaItem = true))
        assertFalse(playerPreparesWhenIdle(Player.STATE_ENDED, hasMediaItem = true))
        assertFalse(playerPreparesWhenIdle(Player.STATE_BUFFERING, hasMediaItem = true))
    }

    /** An idle player with nothing held has nothing to revive; the next setMediaItem owns it. */
    @Test
    fun anIdlePlayerWithoutAnItemDoesNotPrepare() {
        assertFalse(playerPreparesWhenIdle(Player.STATE_IDLE, hasMediaItem = false))
    }
}

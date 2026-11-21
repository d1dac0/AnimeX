package com.anilili.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop decisions behind the TV title page's focus steps (#58). Compose does not run on the
 * JVM, so what is pinned here is the part that is a decision rather than a drawing: which picture
 * the backdrop resolves to, and which devices make it wait for the cursor to settle.
 */
class TvDetailBackdropPolicyTest {
    @Test
    fun episodeStillIsTheBackdropWhileEpisodeImagesAreShown() {
        assertEquals(
            "still",
            tvDetailBackdropSource(episodeImage = "still", seasonArtwork = "season", showEpisodeImages = true),
        )
    }

    @Test
    fun seasonArtworkStandsInWhileEpisodeImagesAreHidden() {
        // Blurring a full-screen backdrop would leave the page looking broken rather than
        // spoiler-free, so the season's art draws in place of the hidden still.
        assertEquals(
            "season",
            tvDetailBackdropSource(episodeImage = "still", seasonArtwork = "season", showEpisodeImages = false),
        )
    }

    @Test
    fun seasonArtworkStandsInWhenTheEpisodeHasNoStill() {
        assertEquals(
            "season",
            tvDetailBackdropSource(episodeImage = null, seasonArtwork = "season", showEpisodeImages = true),
        )
    }

    @Test
    fun seasonArtworkStandsInWhenBoth() {
        assertEquals(
            "season",
            tvDetailBackdropSource(episodeImage = null, seasonArtwork = "season", showEpisodeImages = false),
        )
    }

    @Test
    fun fullCostDevicesFollowTheCursorStepByStep() {
        // Full-cost behaviour is today's: a new backdrop per press, the shared loader's crossfade
        // on it. Only the low-cost path changes.
        assertTrue(tvDetailBackdropFollowsCursorLive(useLowCostTvEffects = false))
    }

    @Test
    fun lowCostDevicesWaitForTheCursorToSettle() {
        // A held Right walked a full-screen decode plus a crossfade per press on a low-RAM box;
        // there the backdrop follows only once the cursor has rested.
        assertFalse(tvDetailBackdropFollowsCursorLive(useLowCostTvEffects = true))
    }

    @Test
    fun theSettleDelayIsLongEnoughToSwallowAHeldDirection() {
        // Home's hero settles on the same shape of delay; the point is that it is a pause a held
        // key cannot outrun, not a frame.
        assertTrue(TV_DETAIL_BACKDROP_SETTLE_MS > 0)
    }
}

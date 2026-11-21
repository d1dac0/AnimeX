package com.anilili.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The television Detail page keeps one marker for the card the cursor was last on, so Back from a
 * player or another title can put the cursor there again. The marker has to say which kind of card
 * it names, because an episode and a Related card are restored through different rows.
 */
class TvFocusMarkerTest {
    @Test
    fun `an episode marker names its pipe id and no Related card`() {
        val marker = tvEpisodeFocusMarker("anilist:21:3")

        assertEquals("anilist:21:3", tvFocusMarkerEpisode(marker))
        assertNull(tvFocusMarkerRelated(marker))
    }

    @Test
    fun `a Related marker names its media id and no episode`() {
        val marker = tvRelatedFocusMarker(154587)

        assertEquals(154587, tvFocusMarkerRelated(marker))
        assertNull(tvFocusMarkerEpisode(marker))
    }

    @Test
    fun `no marker names nothing`() {
        assertNull(tvFocusMarkerEpisode(null))
        assertNull(tvFocusMarkerRelated(null))
        assertNull(tvFocusMarkerEpisode("episode:"))
        assertNull(tvFocusMarkerRelated("related:not-a-number"))
    }
}

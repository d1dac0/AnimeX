package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeToHandBackTest {
    @Test
    fun autoPlayedEpisodeWinsOverTheOneTheRouteStartedOn() {
        // Continue Watching E5, auto-play to E7, Back: the page focuses E7.
        assertEquals("7", episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = "5", playingEpisode = "7"))
    }

    @Test
    fun episodeStartedFromTheStripReplacesTheStaleArrivalLabel() {
        // The rebuilt detail/21?episode=5 entry, E8 played from its strip, Back: E8, not E5.
        assertEquals("8", episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = "8", playingEpisode = "8"))
    }

    @Test
    fun nothingLoadedYetFallsBackToTheRouteEpisode() {
        assertEquals("5", episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = "5", playingEpisode = null))
        assertEquals("5", episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = "5", playingEpisode = ""))
    }

    @Test
    fun blankEverywhereHandsBackNothing() {
        assertNull(episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = "", playingEpisode = null))
        assertNull(episodeToHandBack(detailAnimeId = 21, watchAnimeId = 21, routeEpisode = null, playingEpisode = " "))
    }

    @Test
    fun anotherAnimesPageGetsNoLabel() {
        // A page that started another season's player: its strip has no use for this label.
        assertNull(episodeToHandBack(detailAnimeId = 20, watchAnimeId = 21, routeEpisode = "5", playingEpisode = "7"))
    }
}

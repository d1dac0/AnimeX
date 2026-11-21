package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryProgressTest {
    private val current = HistoryEntry(
        anilistId = 197754,
        title = "Liar Game",
        cover = null,
        episodeNumber = 1.0,
        episodeTitle = "First Game",
        provider = "auto",
        category = "sub",
        positionMs = 1_200_000,
        durationMs = 1_440_000,
        fromRemote = true,
    )

    @Test
    fun `watched episode advances continue watching and clears resume position`() {
        val updated = historyAfterEpisodeWatched(
            existing = current,
            watchedEpisode = 1.0,
            nextEpisode = 2.0,
            nextEpisodeTitle = "Second Game",
            seriesCompleted = false,
        )

        requireNotNull(updated)
        assertEquals(2.0, updated.episodeNumber, 0.0)
        assertEquals("Second Game", updated.episodeTitle)
        assertEquals(0, updated.positionMs)
        assertEquals(0, updated.durationMs)
        assertEquals(false, updated.fromRemote)
    }

    @Test
    fun `known final episode removes continue watching`() {
        assertNull(
            historyAfterEpisodeWatched(
                existing = current,
                watchedEpisode = 1.0,
                nextEpisode = null,
                nextEpisodeTitle = null,
                seriesCompleted = true,
            ),
        )
    }

    @Test
    fun `unknown next episode keeps the current row`() {
        val updated = historyAfterEpisodeWatched(
            existing = current,
            watchedEpisode = 1.0,
            nextEpisode = null,
            nextEpisodeTitle = null,
            seriesCompleted = false,
        )

        // Nothing to advance to — the row stays put, keeping its episode and resume position.
        // It does gain the watch itself, so finishing the latest aired episode still counts.
        requireNotNull(updated)
        assertEquals(current.episodeNumber, updated.episodeNumber, 0.0)
        assertEquals(current.positionMs, updated.positionMs)
        assertEquals(current.durationMs, updated.durationMs)
        assertEquals(current.copy(watchedEpisodes = updated.watchedEpisodes), updated)
    }

    @Test
    fun `late callback cannot move an already advanced row`() {
        val advanced = current.copy(episodeNumber = 2.0, positionMs = 0, durationMs = 0)
        val updated = historyAfterEpisodeWatched(
            existing = advanced,
            watchedEpisode = 1.0,
            nextEpisode = 2.0,
            nextEpisodeTitle = "Second Game",
            seriesCompleted = false,
        )

        assertSame(advanced, updated)
    }

    @Test
    fun `finishing an episode records it rather than implying the ones below it`() {
        val updated = historyAfterEpisodeWatched(
            existing = current.copy(episodeNumber = 3.0),
            watchedEpisode = 3.0,
            nextEpisode = 4.0,
            nextEpisodeTitle = null,
            seriesCompleted = false,
        )

        requireNotNull(updated)
        assertEquals(setOf(3.0), updated.watchedEpisodes)
        assertEquals(4.0, updated.episodeNumber, 0.0)
    }

    @Test
    fun `a skipped episode is not counted as watched`() {
        // Watch 1, jump to 3, finish 3. Episode 2 was never played and must stay unmarked —
        // the whole point of recording rather than inferring from the episode number.
        val afterOne = requireNotNull(
            historyAfterEpisodeWatched(current, 1.0, 2.0, null, seriesCompleted = false),
        )
        val jumpedToThree = afterOne.copy(episodeNumber = 3.0, positionMs = 0, durationMs = 0)
        val afterThree = requireNotNull(
            historyAfterEpisodeWatched(jumpedToThree, 3.0, 4.0, null, seriesCompleted = false),
        )

        assertEquals(setOf(1.0, 3.0), afterThree.watchedEpisodes)
        assertEquals(
            "a skipped episode draws no progress bar",
            0f,
            com.anilili.ui.components.episodeWatchFraction(afterThree, 2.0),
            0f,
        )
        assertEquals(
            "a watched episode is full",
            1f,
            com.anilili.ui.components.episodeWatchFraction(afterThree, 1.0),
            0f,
        )
    }

    @Test
    fun `another episode of the same series cannot erase this one's resume position`() {
        // The reported bug: leave episode 5 halfway, watch episode 4, come back to 5 and it
        // restarted from zero because the row held one position for the whole series.
        val halfway = current.copy(episodeNumber = 5.0)
            .withEpisodeProgress(5.0, positionMs = 700_000, durationMs = 1_400_000)
        val movedOn = halfway.copy(episodeNumber = 4.0, positionMs = 0, durationMs = 0)

        assertEquals(700_000L, movedOn.resumeFor(5.0)?.positionMs)
        assertEquals(0.5f, com.anilili.ui.components.episodeWatchFraction(movedOn, 5.0), 0.001f)
    }

    @Test
    fun `confirmed playback of a later episode moves the row onto it and keeps the earlier clock`() {
        // The reported bug: the row pointed at episode 5, the viewer watched episode 7 for ten
        // minutes, and after a restart Continue Watching went back to 5 — the row's episode only
        // moved on resolve, so a session that never re-resolved left it behind forever.
        val row = current.copy(episodeNumber = 5.0)
            .withEpisodeProgress(5.0, positionMs = 700_000, durationMs = 1_400_000)
        val playing = row.withEpisodeProgress(
            episodeNumber = 7.0,
            positionMs = 600_000,
            durationMs = 1_440_000,
            followsPlayback = true,
        )

        assertEquals(7.0, playing.episodeNumber, 0.0)
        assertEquals(600_000L, playing.positionMs)
        assertEquals(1_440_000L, playing.durationMs)
        assertEquals("episode 5's clock is not thrown away by the move", 700_000L, playing.resumeFor(5.0)?.positionMs)
    }

    @Test
    fun `a bare clock for another episode still never moves the row`() {
        // The evidence a plain progress report carries is one report: the tail of a torn-down
        // player can produce it, and that must not walk the row backwards. Only playback observed
        // actually running earns the move, which is what the flag is for.
        val row = current.copy(episodeNumber = 5.0)
        val moved = row.withEpisodeProgress(7.0, positionMs = 600_000, durationMs = 1_440_000)

        assertEquals(5.0, moved.episodeNumber, 0.0)
        assertEquals(600_000L, moved.resumeFor(7.0)?.positionMs)
        assertEquals(
            "the row itself stayed where it was, the clock went into the resume points",
            row.copy(resumePoints = moved.resumePoints),
            moved,
        )
    }

    @Test
    fun `an episode already watched is not moved onto by even confirmed playback`() {
        val watched = current.copy(watchedEpisodes = setOf(7.0))
        assertSame(
            watched,
            watched.withEpisodeProgress(
                episodeNumber = 7.0,
                positionMs = 600_000,
                durationMs = 1_440_000,
                followsPlayback = true,
            ),
        )
    }

    @Test
    fun `advancing past a watched episode keeps every other episode's position`() {
        val entry = current.copy(episodeNumber = 1.0)
            .withEpisodeProgress(1.0, positionMs = 1_200_000, durationMs = 1_440_000)
            .withEpisodeProgress(4.0, positionMs = 300_000, durationMs = 1_440_000)
        val updated = requireNotNull(
            historyAfterEpisodeWatched(entry, 1.0, 2.0, "Second Game", seriesCompleted = false),
        )

        assertNull("a finished episode has nothing left to resume", updated.resumeFor(1.0))
        assertEquals(300_000L, updated.resumeFor(4.0)?.positionMs)
    }

    @Test
    fun `advancing onto an episode already left partway restores its clock`() {
        val entry = current.copy(episodeNumber = 1.0)
            .withEpisodeProgress(2.0, positionMs = 300_000, durationMs = 1_440_000)
        val updated = requireNotNull(
            historyAfterEpisodeWatched(entry, 1.0, 2.0, "Second Game", seriesCompleted = false),
        )

        assertEquals(2.0, updated.episodeNumber, 0.0)
        assertEquals(300_000L, updated.positionMs)
        assertEquals(1_440_000L, updated.durationMs)
    }

    @Test
    fun `a late callback for an episode already watched is ignored`() {
        val watched = current.copy(watchedEpisodes = setOf(1.0))
        assertSame(watched, watched.withEpisodeProgress(1.0, 1_300_000, 1_440_000))
        assertNull(watched.resumeFor(1.0))
    }

    @Test
    fun `restarting an episode from the beginning clears its stored clock`() {
        val entry = current.withEpisodeProgress(1.0, positionMs = 700_000, durationMs = 1_440_000)
        val restarted = entry.withEpisodeProgress(1.0, positionMs = 0, durationMs = 1_440_000)

        assertNull(restarted.resumeFor(1.0))
    }

    @Test
    fun `a row stored before resume points existed still resumes`() {
        // No map at all, just the legacy pair — the upgrade must not throw the position away.
        assertEquals(1_200_000L, current.resumeFor(1.0)?.positionMs)
        assertNull(current.resumeFor(2.0))
    }

    @Test
    fun `a long series does not accumulate a clock per episode forever`() {
        val base = current.copy(episodeNumber = 999.0, positionMs = 0, durationMs = 0)
        val entry = (1..MAX_RESUME_POINTS + 10).fold(base) { row, episode ->
            row.withEpisodeProgress(episode.toDouble(), positionMs = 60_000, durationMs = 1_440_000)
        }

        assertEquals(MAX_RESUME_POINTS, entry.resumePoints.size)
        assertNull("the oldest episodes are the ones given up", entry.resumeFor(1.0))
        assertEquals(60_000L, entry.resumeFor((MAX_RESUME_POINTS + 10).toDouble())?.positionMs)
    }

    @Test
    fun `the episode in progress shows its own position, not a full bar`() {
        val watching = current.copy(
            episodeNumber = 5.0,
            positionMs = 360_000,
            durationMs = 1_440_000,
            watchedEpisodes = setOf(1.0, 2.0),
        )
        assertEquals(0.25f, com.anilili.ui.components.episodeWatchFraction(watching, 5.0), 0.001f)
        assertEquals(0f, com.anilili.ui.components.episodeWatchFraction(watching, 6.0), 0f)
    }

    @Test
    fun `the final episode is recorded before the row is retired`() {
        val finished = historyAfterEpisodeWatched(
            existing = current.copy(episodeNumber = 12.0),
            watchedEpisode = 12.0,
            nextEpisode = null,
            nextEpisodeTitle = null,
            seriesCompleted = true,
        )
        assertNull("a completed series drops out of Continue Watching", finished)
    }

    @Test
    fun `a last episode with no successor still records the watch`() {
        val updated = requireNotNull(
            historyAfterEpisodeWatched(
                existing = current.copy(episodeNumber = 9.0),
                watchedEpisode = 9.0,
                nextEpisode = null,
                nextEpisodeTitle = null,
                seriesCompleted = false,
            ),
        )
        assertEquals(setOf(9.0), updated.watchedEpisodes)
        assertEquals("the row stays on the episode just finished", 9.0, updated.episodeNumber, 0.0)
        assertEquals(
            "an explicitly watched current episode draws as complete",
            1f,
            com.anilili.ui.components.episodeWatchFraction(updated, 9.0),
            0f,
        )
    }
}

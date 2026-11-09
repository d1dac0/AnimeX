package com.anilili.data.library

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Continue Watching card says about the next episode: which one it is, and how many days are
 * left.
 *
 * The instants are the ones the row's own tests use — 2026-09-24 09:00 UTC is "now", and an episode
 * airing 2026-09-25 01:30 UTC is the next morning in Tokyo but still the same evening in Bogotá,
 * which is what makes a zone-sensitive day label worth pinning.
 */
class NextEpisodeReleaseTest {
    private val title = 21

    /** 2026-09-24 09:00 UTC — a Thursday morning. */
    private val nowSec = 1_790_240_400L

    /** 2026-09-25 01:30 UTC — the next episode. */
    private val airsTomorrowAt = 1_790_299_800L

    /** 2026-09-27 09:00 UTC — three days out. */
    private val airsInThreeDaysAt = 1_790_499_600L

    private val bogota: ZoneId = ZoneId.of("America/Bogota")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    @Test
    fun `the soonest episode still ahead is the next release`() {
        val upcoming: UpcomingAiring = mapOf(
            title to mapOf(1177 to airsInThreeDaysAt, 1178 to airsTomorrowAt),
        )

        val release = nextReleasesByTitle(upcoming, nowSec).getValue(title)

        assertEquals(1178, release.episode)
        assertEquals(airsTomorrowAt, release.airingAt)
    }

    /**
     * The window reaches back to yesterday so the row's filter can see an episode that aired late
     * last night. That episode is something to play, not something to wait for.
     */
    @Test
    fun `an episode that has already aired is not the next release`() {
        val airedYesterday = nowSec - 12 * 60 * 60
        val upcoming: UpcomingAiring = mapOf(title to mapOf(1176 to airedYesterday, 1177 to airsTomorrowAt))

        assertEquals(1177, nextReleasesByTitle(upcoming, nowSec).getValue(title).episode)
    }

    @Test
    fun `the moment an episode airs the card moves on to the following one`() {
        val upcoming: UpcomingAiring = mapOf(title to mapOf(1177 to nowSec, 1178 to airsInThreeDaysAt))

        assertEquals(1178, nextReleasesByTitle(upcoming, nowSec).getValue(title).episode)
    }

    @Test
    fun `a title with nothing left in the window says nothing`() {
        val upcoming: UpcomingAiring = mapOf(title to mapOf(1176 to nowSec - 60 * 60))

        assertNull(nextReleasesByTitle(upcoming, nowSec)[title])
    }

    @Test
    fun `an empty window is an empty answer`() {
        assertTrue(nextReleasesByTitle(emptyMap(), nowSec).isEmpty())
    }

    @Test
    fun `titles are read separately`() {
        val upcoming: UpcomingAiring = mapOf(
            title to mapOf(1177 to airsTomorrowAt),
            999 to mapOf(3 to airsInThreeDaysAt),
        )

        val releases = nextReleasesByTitle(upcoming, nowSec)

        assertEquals(1177, releases.getValue(title).episode)
        assertEquals(3, releases.getValue(999).episode)
    }

    @Test
    fun `a release later today is today`() {
        assertEquals("TODAY", releaseDayLabel(nowSec + 6 * 60 * 60, nowSec, bogota))
    }

    @Test
    fun `the next calendar day is tomorrow`() {
        assertEquals("TOMORROW", releaseDayLabel(airsTomorrowAt, nowSec, tokyo))
    }

    @Test
    fun `days are counted on the clock the viewer reads`() {
        // 01:30 UTC is Friday morning in Tokyo and still Thursday evening in Bogotá.
        assertEquals("TOMORROW", releaseDayLabel(airsTomorrowAt, nowSec, tokyo))
        assertEquals("TODAY", releaseDayLabel(airsTomorrowAt, nowSec, bogota))
    }

    @Test
    fun `further out releases say how many days`() {
        assertEquals("IN 3 DAYS", releaseDayLabel(airsInThreeDaysAt, nowSec, bogota))
    }

    @Test
    fun `the card line names the episode and the wait`() {
        val release = NextEpisodeRelease(episode = 5, airingAt = airsInThreeDaysAt)

        assertEquals("EP 5 · IN 3 DAYS", nextReleaseLabel(release, nowSec, bogota))
    }

    @Test
    fun `the card line says tomorrow rather than in one days`() {
        val release = NextEpisodeRelease(episode = 1177, airingAt = airsTomorrowAt)

        assertEquals("EP 1177 · TOMORROW", nextReleaseLabel(release, nowSec, tokyo))
    }

    @Test
    fun `the published schedule wins over the list service where both know a title`() {
        val scheduled = mapOf(title to NextEpisodeRelease(1177, airsTomorrowAt))
        val service = mapOf(
            title to NextEpisodeRelease(1177, airsInThreeDaysAt),
            999 to NextEpisodeRelease(3, airsInThreeDaysAt),
        )

        val merged = mergeNextReleases(scheduled, service)

        assertEquals(airsTomorrowAt, merged.getValue(title).airingAt)
        assertEquals(3, merged.getValue(999).episode)
    }

    @Test
    fun `the spoken line is a sentence`() {
        assertEquals(
            "Episode 5 releases in 3 days",
            spokenNextReleaseLabel(NextEpisodeRelease(5, airsInThreeDaysAt), nowSec, bogota),
        )
        assertEquals(
            "Episode 1177 releases tomorrow",
            spokenNextReleaseLabel(NextEpisodeRelease(1177, airsTomorrowAt), nowSec, tokyo),
        )
        assertEquals(
            "Episode 9 releases today",
            spokenNextReleaseLabel(NextEpisodeRelease(9, nowSec + 60 * 60), nowSec, bogota),
        )
    }
}

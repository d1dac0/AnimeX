package com.anilili.data.library

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The next episode of a followed title to land, and the moment it airs.
 *
 * Only what a Continue Watching card has room for: which episode, and when. [airingAt] is the
 * broadcast instant AniList reports, not the moment the servers carry it — the card says when the
 * episode is out, and the two hours the servers need are the player's problem.
 */
data class NextEpisodeRelease(
    val episode: Int,
    val airingAt: Long,
)

/**
 * Which episode of each title in [upcoming] is the next one out, keyed by AniList id.
 *
 * The window Home reads reaches back to yesterday and holds whole titles, so the answer is the
 * soonest episode whose broadcast is still ahead. Episodes already out stay in the map the filter
 * reads — they are something to play, not something to wait for — and a title whose every episode in
 * the window has aired, or one the window does not reach at all, is simply absent: a card then says
 * nothing rather than guessing.
 */
internal fun nextReleasesByTitle(
    upcoming: UpcomingAiring,
    nowSec: Long = System.currentTimeMillis() / 1000,
): Map<Int, NextEpisodeRelease> = upcoming.mapNotNull { (anilistId, episodes) ->
    episodes.entries
        .filter { (_, airingAt) -> airingAt > nowSec }
        .minByOrNull { (_, airingAt) -> airingAt }
        ?.let { (episode, airingAt) -> anilistId to NextEpisodeRelease(episode, airingAt) }
}.toMap()

/**
 * How many days are left, as the card says it: `TODAY`, `TOMORROW`, or `IN 3 DAYS`.
 *
 * Calendar days on the device's own clock, not 24-hour blocks: an episode airing at 01:30 tomorrow
 * is "tomorrow" whether it is two hours or twenty-six away, because that is the day the viewer is
 * waiting for. The detail page dates episodes on the same clock, so the two agree.
 */
internal fun releaseDayLabel(
    airingAt: Long,
    nowSec: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val days = daysUntil(airingAt, nowSec, zone)
    return when {
        days <= 0L -> "TODAY"
        days == 1L -> "TOMORROW"
        else -> "IN $days DAYS"
    }
}

/** `EP 5 · IN 3 DAYS` — the whole line a Continue Watching card adds. */
internal fun nextReleaseLabel(
    release: NextEpisodeRelease,
    nowSec: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault(),
): String = "EP ${release.episode} · ${releaseDayLabel(release.airingAt, nowSec, zone)}"

/**
 * The same line for a screen reader, spelled out: `Episode 5 releases in 3 days`.
 *
 * The card's own text is abbreviated for width, and "EP" read aloud is "ee pee". This is what
 * TalkBack announces instead.
 */
internal fun spokenNextReleaseLabel(
    release: NextEpisodeRelease,
    nowSec: Long = System.currentTimeMillis() / 1000,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val days = daysUntil(release.airingAt, nowSec, zone)
    val whenText = when {
        days <= 0L -> "today"
        days == 1L -> "tomorrow"
        else -> String.format(Locale.US, "in %d days", days)
    }
    return "Episode ${release.episode} releases $whenText"
}

/** Whole calendar days from the day [nowSec] falls on to the day [airingAt] falls on. */
private fun daysUntil(airingAt: Long, nowSec: Long, zone: ZoneId): Long =
    ChronoUnit.DAYS.between(
        Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate(),
        Instant.ofEpochSecond(airingAt).atZone(zone).toLocalDate(),
    )

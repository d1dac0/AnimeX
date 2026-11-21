package com.anilili.data.library

import com.anilili.data.model.MediaListEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** Provider sentinel for a row with no server picked yet; the watch screen resolves one itself. */
internal const val AUTO_PROVIDER = "auto"

/**
 * How many part-watched episodes one title remembers.
 *
 * Only episodes left partway are stored at all, so this is generous in practice; the cap exists so
 * a thousand-episode series cannot grow its row without bound. The highest-numbered ones are kept,
 * because a viewer resumes forwards.
 */
internal const val MAX_RESUME_POINTS = 24

/** Where one episode was left, and how long it is. */
@Serializable
data class EpisodeResume(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Stable map key for an episode number: "7" for a whole one, "7.5" for a special. */
internal fun episodeKey(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

/** One "continue watching" record per anime — the last episode watched + resume position. */
@Serializable
data class HistoryEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val episodeNumber: Double,
    val episodeTitle: String? = null,
    val provider: String,
    val category: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
    /**
     * Episodes actually played to the watched threshold on this device.
     *
     * The row itself only knows the episode in progress, so "watched" used to be inferred as
     * "numbered lower than the current one" — jumping straight to episode 94 marked 1–93 as
     * complete and there was no way to see what you had skipped. Entries stored before this
     * existed are seeded from that same inference on load, so nothing appears to reset; every
     * episode finished from then on is recorded rather than guessed.
     */
    val watchedEpisodes: Set<Double> = emptySet(),
    /**
     * Seeded from AniList/MAL progress rather than actual playback here. Refreshed wholesale on
     * every remote sync (so remote progress moves it forward), replaced by a real record the
     * moment the user plays the title, and dropped on logout.
     */
    val fromRemote: Boolean = false,
    /**
     * Where each part-watched episode was left, keyed by [episodeKey].
     *
     * [positionMs] can only ever describe [episodeNumber], and a title has one row — so watching
     * anything else in the same series, or the row advancing past the watched threshold, threw the
     * half-watched episode's clock away and it restarted from zero when the viewer came back to it.
     * Finished episodes are dropped rather than stored: there is nothing to resume, and it keeps
     * this to the handful of episodes actually abandoned midway.
     */
    val resumePoints: Map<String, EpisodeResume> = emptyMap(),
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val episodeLabel: String
        get() = episodeKey(episodeNumber)
}

/**
 * Where [episodeNumber] should pick up, or null to start it from the beginning.
 *
 * An episode already counted as watched always restarts: there is nothing left of it to resume, and
 * this is also what keeps a late player callback from reopening one the row has moved past.
 *
 * Rows written before [HistoryEntry.resumePoints] existed carry a clock only for the episode they
 * point at, so that pair is the fallback — upgrading keeps the position the viewer left behind.
 */
fun HistoryEntry.resumeFor(episodeNumber: Double): EpisodeResume? {
    if (episodeNumber in watchedEpisodes) return null
    resumePoints[episodeKey(episodeNumber)]?.let { stored ->
        return stored.takeIf { it.positionMs > 0 }
    }
    if (episodeNumber != this.episodeNumber || positionMs <= 0) return null
    return EpisodeResume(positionMs, durationMs)
}

/**
 * Record how far [episodeNumber] has played, without moving which episode the row points at.
 *
 * Progress for an episode already counted as watched is ignored, and a zero position clears whatever
 * was stored, so an episode restarted from the beginning does not keep offering its old clock.
 *
 * [followsPlayback] is the exception, and it is earned rather than assumed: it says the player has
 * been observed genuinely running on this episode — the same two-reports bar the held-back Continue
 * Watching row waits on. A bare clock never has that evidence, which is why it defaults to false:
 * a late callback from a torn-down player would otherwise walk the row backwards onto whatever was
 * playing before. Without the exception the row's episode only moves on resolve, so a session that
 * never re-resolved onto the newer episode left Continue Watching pointing at the older one no
 * matter how long the newer one played, and a restart sent the viewer back to it. The clocks stay
 * per episode either way: the row moves, and the abandoned episode's position does not go with it.
 */
internal fun HistoryEntry.withEpisodeProgress(
    episodeNumber: Double,
    positionMs: Long,
    durationMs: Long,
    followsPlayback: Boolean = false,
): HistoryEntry {
    if (episodeNumber in watchedEpisodes) return this
    val key = episodeKey(episodeNumber)
    val points = if (positionMs <= 0) {
        resumePoints - key
    } else {
        capResumePoints(resumePoints + (key to EpisodeResume(positionMs, durationMs)))
    }
    return when {
        episodeNumber == this.episodeNumber ->
            copy(positionMs = positionMs, durationMs = durationMs, resumePoints = points)
        // The stored title belongs to the episode being left behind, and nothing here knows the new
        // one's name; the next resolve fills it in, the same way the remote advance leaves it.
        followsPlayback ->
            copy(
                episodeNumber = episodeNumber,
                episodeTitle = null,
                positionMs = positionMs,
                durationMs = durationMs,
                resumePoints = points,
            )
        else -> copy(resumePoints = points)
    }
}

private fun capResumePoints(points: Map<String, EpisodeResume>): Map<String, EpisodeResume> =
    if (points.size <= MAX_RESUME_POINTS) {
        points
    } else {
        points.entries
            .sortedByDescending { it.key.toDoubleOrNull() ?: 0.0 }
            .take(MAX_RESUME_POINTS)
            .associate { it.key to it.value }
    }

/**
 * The line under a Continue Watching tile: "EP 4 · kiwi", or just "EP 6" for a row seeded from the
 * list service.
 *
 * A seeded row has no server yet — the watch screen picks one when it opens — and [AUTO_PROVIDER]
 * is the sentinel that says so. Printing it put a literal "auto" on the tile, which a viewer
 * reported as the app showing "auto" where a source name belongs.
 */
internal fun HistoryEntry.continueWatchingCaption(): String =
    if (provider == AUTO_PROVIDER || provider.isBlank()) {
        "EP $episodeLabel"
    } else {
        "EP $episodeLabel  ·  $provider"
    }

/** Canonical watch-history order used by every screen: the latest playback comes first. */
internal fun sortHistoryLatestFirst(entries: List<HistoryEntry>): List<HistoryEntry> =
    entries.sortedByDescending(HistoryEntry::updatedAt)

/**
 * Continue Watching, newest first, with one row per title.
 *
 * The list-service entries this merges are not guaranteed unique per anime: AniList can return a
 * title under more than one status, and two MyAnimeList ids can map to a single AniList id. Those
 * ids are LazyColumn keys, and a repeat is fatal — Compose throws
 * IllegalArgumentException("Key ... was already used") from inside measure. Three device reports
 * on 0.1.65 died that way on the library screen, all in the second after an account refresh.
 *
 * Deduplicating before the cap also stops a repeat spending one of the [max] slots.
 */
internal fun mergedHistory(
    local: List<HistoryEntry>,
    seeded: List<HistoryEntry>,
    max: Int,
): List<HistoryEntry> = sortHistoryLatestFirst(local + seeded)
    .distinctBy(HistoryEntry::anilistId)
    .take(max)

/**
 * Move Continue Watching past an episode that crossed the watched threshold.
 *
 * The current record is only changed when it still points at the episode being completed. This
 * makes late player callbacks harmless after the row has already advanced to the next episode.
 * A null result means the known final episode was completed and the row should be removed.
 */
internal fun historyAfterEpisodeWatched(
    existing: HistoryEntry,
    watchedEpisode: Double,
    nextEpisode: Double?,
    nextEpisodeTitle: String?,
    seriesCompleted: Boolean,
): HistoryEntry? {
    if (existing.episodeNumber != watchedEpisode) return existing
    val watched = existing.watchedEpisodes + watchedEpisode
    // Finished: its clock is spent, whatever the rest of the series still has stored.
    val points = existing.resumePoints - episodeKey(watchedEpisode)
    if (seriesCompleted) return null
    val next = nextEpisode?.takeIf { it > watchedEpisode }
        ?: return existing.copy(watchedEpisodes = watched, resumePoints = points)
    // The episode being moved to may itself have been left partway earlier; the row's own clock is
    // what draws its progress bar, so it has to say the same thing the resume point does.
    val resume = points[episodeKey(next)]
    return existing.copy(
        episodeNumber = next,
        episodeTitle = nextEpisodeTitle,
        positionMs = resume?.positionMs ?: 0,
        durationMs = resume?.durationMs ?: 0,
        fromRemote = false,
        watchedEpisodes = watched,
        resumePoints = points,
    )
}

/** One episode a hand-made mark can move Continue Watching onto: it has aired, so it can be played. */
data class EpisodeStop(val number: Double, val title: String? = null)

/**
 * Mark one episode watched or unwatched by hand, without any playback.
 *
 * Marking watched spends the episode's resume clock — a full bar should not keep offering
 * "resume at 12:04". A null result means nothing changed.
 *
 * The row moves too, given the season's [airedEpisodes]. It used to stay put on the grounds that a
 * mark is a correction and choosing the row's episode is playback's job, and that is not how the
 * eye is used: a viewer who watched 2 and 3 somewhere else marks them, and Home kept offering 2 —
 * through refreshes and restarts, because nothing else would ever move it. So a mark at or past the
 * row's episode does what finishing that episode in the player does, and moves the row to the next
 * aired episode not already marked; with none left, the row settles on the marked episode, caught
 * up, which is also where playback leaves it. A mark *behind* the row is filling in a gap, and
 * leaves the row alone.
 *
 * Unmarking is the undo, because the eye is one tap away from a slip: unmarking the episode just
 * before the row, before the row's own episode is started, puts the row back on it. A row with its own
 * clock running is somewhere the viewer actually went, and is left there.
 *
 * Without [airedEpisodes] — a caller with no episode list — the row is never moved.
 */
internal fun historyAfterEpisodeToggled(
    existing: HistoryEntry,
    episodeNumber: Double,
    watched: Boolean,
    airedEpisodes: List<EpisodeStop> = emptyList(),
): HistoryEntry? {
    val isWatched = episodeNumber in existing.watchedEpisodes
    if (watched == isWatched) return null
    val stops = airedEpisodes.sortedBy(EpisodeStop::number)
    return if (watched) {
        val marked = existing.watchedEpisodes + episodeNumber
        val points = existing.resumePoints - episodeKey(episodeNumber)
        val recorded = existing.copy(watchedEpisodes = marked, resumePoints = points)
        when {
            stops.isNotEmpty() && episodeNumber >= existing.episodeNumber -> {
                val target = stops.firstOrNull { it.number > episodeNumber && it.number !in marked }
                    ?: stops.firstOrNull { it.number == episodeNumber }
                    ?: EpisodeStop(episodeNumber)
                recorded.onEpisode(target)
            }
            // If the row itself points at this episode, its own clock is spent too.
            existing.episodeNumber == episodeNumber -> recorded.copy(positionMs = 0, durationMs = 0)
            else -> recorded
        }
    } else {
        val unmarked = existing.copy(watchedEpisodes = existing.watchedEpisodes - episodeNumber)
        val rowWasMovedPastIt = stops.isNotEmpty() &&
            episodeNumber < existing.episodeNumber &&
            existing.positionMs == 0L &&
            // The very next episode only. Unmarking 3 on a row at 10 with 4–9 marked is a correction
            // to the record, not an undo of the press that moved the row.
            stops.firstOrNull { it.number > episodeNumber }?.number == existing.episodeNumber
        if (rowWasMovedPastIt) {
            unmarked.onEpisode(stops.firstOrNull { it.number == episodeNumber } ?: EpisodeStop(episodeNumber))
        } else {
            unmarked
        }
    }
}

/** This row pointed at [stop], with that episode's own clock if it was left part-way. */
private fun HistoryEntry.onEpisode(stop: EpisodeStop): HistoryEntry {
    if (stop.number == episodeNumber && stop.number in watchedEpisodes) {
        return copy(positionMs = 0, durationMs = 0)
    }
    if (stop.number == episodeNumber) return this
    val resume = resumePoints[episodeKey(stop.number)]
    return copy(
        episodeNumber = stop.number,
        episodeTitle = stop.title,
        positionMs = resume?.positionMs ?: 0,
        durationMs = resume?.durationMs ?: 0,
        fromRemote = false,
    )
}

/**
 * Fold the account's episode count into a row this device wrote itself.
 *
 * Seeding used to skip any title already present in local history, and [HistoryEntry]s are written
 * on every resolve — so the first time a device *opened* a title, that title's row was frozen
 * against the account forever. Watch episodes 3–8 on the television and the phone still offered
 * episode 3, with no way back: replaying it pushes nothing, because the list services refuse a
 * progress regression. Sync appeared to work on a fresh install and silently stopped working for
 * everything the viewer had touched, which is what the reports were describing.
 *
 * [remoteProgress] is a count of finished episodes, so the row belongs on the one after it. The
 * local row wins whenever it is level or ahead: this device is then the newer writer and the
 * account catches up on its own next push. Advancing clears the resume position because it belongs
 * to an episode the viewer has since finished somewhere else.
 *
 * [airedEpisodes] is what actually exists to watch, and is not the same bound as [totalEpisodes]:
 * a releasing series carries its whole planned run there long before it has aired. Bounding only by
 * the planned run advanced a caught-up row onto an episode that does not exist yet, and every
 * server was then tried in turn for it — so finishing the latest episode of an airing show left
 * Continue Watching reporting "no playable source on any server" for something simply not out.
 * Null when unknown, which keeps the old behaviour for callers that cannot say.
 *
 * Caught up on an airing series, the row moves to the latest aired episode rather than staying
 * where it was. It used to stay put: watch 6-8 on another service, mark them on the account, and a
 * row on 5 kept saying "Episode 5" until 9 aired, which a viewer read as sync not working at all.
 * The latest aired episode is where finishing it on this device leaves a row too.
 *
 * A null result means nothing changed.
 */
internal fun historyAfterRemoteProgress(
    local: HistoryEntry,
    remoteProgress: Int,
    remoteUpdatedAt: Long,
    totalEpisodes: Int?,
    airedEpisodes: Int? = null,
): HistoryEntry? {
    if (remoteProgress < 1) return null
    // The account says the series is finished; removal is the caller's decision, not this one's.
    val target = remoteRowEpisode(remoteProgress, totalEpisodes, airedEpisodes)?.toDouble() ?: return null
    // Everything up to the count counts as watched — that is all AniList and MAL store — so the
    // set grows even when the row itself is already far enough along.
    val watched = local.watchedEpisodes + generateSequence(1.0) { it + 1.0 }
        .takeWhile { it <= remoteProgress }
        .toSet()
    // A clock for an episode the account says is finished belongs to a watch that completed
    // somewhere else; anything above the count is still genuinely part-watched here.
    val points = local.resumePoints.filterKeys { key ->
        val number = key.toDoubleOrNull() ?: return@filterKeys true
        number > remoteProgress
    }
    if (target <= local.episodeNumber) {
        if (watched == local.watchedEpisodes && points == local.resumePoints) return null
        return local.copy(watchedEpisodes = watched, resumePoints = points)
    }
    return local.copy(
        episodeNumber = target,
        // The stored title belongs to the episode being left behind, and this device has no
        // catalogue here; the watch screen fills it in on the next resolve.
        episodeTitle = null,
        positionMs = points[episodeKey(target)]?.positionMs ?: 0,
        durationMs = points[episodeKey(target)]?.durationMs ?: 0,
        // Ordering is by updatedAt, so a row advanced by another device has to carry that device's
        // timestamp or Continue Watching would claim the viewer watched it just now.
        updatedAt = maxOf(local.updatedAt, remoteUpdatedAt),
        watchedEpisodes = watched,
        resumePoints = points,
    )
}

/**
 * The episode a Continue Watching row should point at when the account has finished [progress]
 * episodes, or null when there is nothing left to continue.
 *
 * Normally the one after the count. Past the planned run ([totalEpisodes]) the series is finished.
 * Past what has aired ([airedEpisodes]) the viewer is caught up, and the row rests on the latest
 * aired episode — already watched, but the only one of this series a server can have — until the
 * next sync after the next episode airs moves it on.
 */
internal fun remoteRowEpisode(progress: Int, totalEpisodes: Int?, airedEpisodes: Int?): Int? {
    val next = progress + 1
    if (totalEpisodes != null && totalEpisodes > 0 && next > totalEpisodes) return null
    if (airedEpisodes != null && airedEpisodes > 0 && next > airedEpisodes) {
        return minOf(progress, airedEpisodes).takeIf { it >= 1 }
    }
    return next
}

/**
 * The Continue Watching rows to take off because the account moved them off Watching elsewhere.
 *
 * Only a *move* counts: [previous] said CURRENT or REPEATING at the last refresh and [current] now
 * says COMPLETED, DROPPED or PAUSED. A status that has merely stayed finished cannot tell "finished
 * everywhere" from "finished once and being rewatched here", which is why such rows were left
 * alone. A move between two refreshes can tell them apart, because nothing this app pushes makes
 * that move behind the viewer's back: progress pushes never touch a COMPLETED entry and keep
 * DROPPED and PAUSED as they are, and a status chosen inside the app reaches [previous] at once
 * through `updateRemoteStatus`, so it never shows up as a move here.
 *
 * A title the account no longer lists at all is not counted: that is a deletion, and a deletion
 * says nothing about whether the viewer is still watching.
 */
internal fun remoteMovesOffWatching(previous: Map<Int, String>, current: Map<Int, String>): Set<Int> =
    current.filter { (id, status) ->
        status in OFF_WATCHING_STATUSES && previous[id] in WATCHING_STATUSES
    }.keys

private val WATCHING_STATUSES = setOf("CURRENT", "REPEATING")
private val OFF_WATCHING_STATUSES = setOf("COMPLETED", "DROPPED", "PAUSED")

/**
 * Whether a local Continue Watching row is what a finished series left behind: the account says
 * COMPLETED, and the row sits on the series' last episode ([totalEpisodes]) with that episode
 * already watched here.
 *
 * [remoteMovesOffWatching] cannot see these. The progress push that finishes a series writes
 * COMPLETED into the status snapshot the moment the account confirms it, so the next refresh finds
 * a status that was already finished rather than one that moved. A finale watched from downloads
 * left its row exactly like that, for good: the offline player did not know the series' length, so
 * it advanced nothing, while the push looked the length up and completed the title on the account.
 *
 * A rewatch is not mistaken for one: it sits on an earlier episode, or on a final episode not yet
 * finished, and either keeps its row.
 */
internal fun isFinishedSeriesLeftover(row: HistoryEntry, status: String?, totalEpisodes: Int?): Boolean {
    if (status != "COMPLETED") return false
    val total = totalEpisodes?.takeIf { it > 0 } ?: return false
    return row.episodeNumber >= total && row.episodeNumber in row.watchedEpisodes
}

/** The stored name of [HistoryEntry.watchedEpisodes]; its absence is what marks a legacy row. */
private const val WATCHED_EPISODES_KEY = "watchedEpisodes"

/**
 * Gives rows written before [HistoryEntry.watchedEpisodes] existed the history the old inference
 * implied — every whole episode below the one in progress.
 *
 * Without this, a viewer 90 episodes into a series would open the app after updating and find
 * nothing marked watched. It preserves the previous (over-generous) picture for those rows and
 * lets accurate recording take over from the next episode finished.
 *
 * **Which rows those are is read off the stored JSON, not off the decoded set.** Seeding every row
 * whose set was empty is what this used to do, and it could not tell a row saved before the field
 * existed from one created today by starting at episode 7 — both decode to an empty set. So
 * starting a series at episode 7 marked 1–6 watched on the next launch, and the seeding then
 * persisted, which is the very guess the field was added to stop. The field and `encodeDefaults =
 * true` arrived in the same release (`5997a108`, v0.1.56), so every build that has ever written
 * the key writes it even when the set is empty: no key means older than that, and a key means the
 * row's own answer, empty or not.
 *
 * [raw] is the JSON [entries] was decoded from. A shape that does not line up with it is left
 * alone — a wrong guess here invents watched episodes, and an un-seeded row still says which
 * episode the viewer is on.
 */
internal fun seedLegacyWatchedEpisodes(
    entries: List<HistoryEntry>,
    raw: String?,
): List<HistoryEntry> = seedLegacyHistory(entries, raw, alreadySeeded = false).entries

/**
 * What loading the stored history found, for deciding whether the legacy seed ever needs to run
 * again.
 *
 * [settled] means the stored JSON was read in full and every row lined up with [entries], so once
 * [entries] is written back — with `encodeDefaults`, which writes the key on every row — no stored
 * row predates [HistoryEntry.watchedEpisodes] and a later seed could not change anything.
 * [legacyRows] is how many rows had no key; while it is above zero the store must write the
 * history back before it records the seed as done, or the rows would lose the seed on the next
 * launch.
 */
internal class LegacyHistorySeed(
    val entries: List<HistoryEntry>,
    val legacyRows: Int,
    val settled: Boolean,
)

/**
 * [seedLegacyWatchedEpisodes] with what the store needs to stop paying for it.
 *
 * The seed builds a second full JSON tree of the history — up to a hundred rows whose watched sets
 * can run to thousands of entries — on the main thread at every launch, for a migration that has
 * nothing left to do once the rows are written back (#86). [alreadySeeded] is the store's record
 * that this happened; with it set nothing is parsed and [entries] come back as they are.
 *
 * Nothing on disk is treated as settled only when nothing was decoded either, and a history that
 * did not decode or does not line up with its JSON is never settled: it is left alone, as before,
 * and looked at again next launch.
 */
internal fun seedLegacyHistory(
    entries: List<HistoryEntry>,
    raw: String?,
    alreadySeeded: Boolean,
): LegacyHistorySeed {
    if (alreadySeeded) return LegacyHistorySeed(entries, legacyRows = 0, settled = true)
    if (raw.isNullOrBlank()) return LegacyHistorySeed(entries, legacyRows = 0, settled = entries.isEmpty())
    val unsettled = LegacyHistorySeed(entries, legacyRows = 0, settled = false)
    if (entries.isEmpty()) return unsettled
    val stored = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return unsettled
    if (stored.size != entries.size) return unsettled
    var legacyRows = 0
    val seeded = entries.mapIndexed { index, entry ->
        val written = stored[index] as? JsonObject
        if (written == null || WATCHED_EPISODES_KEY in written) {
            entry
        } else {
            legacyRows++
            entry.seededWatchedEpisodes()
        }
    }
    return LegacyHistorySeed(seeded, legacyRows, settled = stored.all { it is JsonObject })
}

/** Every whole episode below the one in progress. A row on episode 1 has nothing to seed. */
private fun HistoryEntry.seededWatchedEpisodes(): HistoryEntry {
    if (episodeNumber <= 1.0) return this
    return copy(
        watchedEpisodes = generateSequence(1.0) { it + 1.0 }
            .takeWhile { it < episodeNumber }
            .toSet(),
    )
}

/** A saved series the user wants to watch. */
@Serializable
data class WatchlistEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val format: String? = null,
    val averageScore: Int? = null,
    val addedAt: Long = 0,
)

/**
 * A Continue Watching row the viewer removed here, remembered so account sync cannot immediately
 * put it back.
 *
 * [progressAtDismissal] is what makes the dismissal lapse. It used to be a bare set of ids, which
 * blocked re-seeding for good: clearing history on the tablet opted it out of the account
 * permanently, and finishing a series dismissed it on the device that finished it, so a rewatch
 * begun on the phone never reached either. Only playing the title on that same device cleared it.
 *
 * A time limit would be the wrong instrument — it resurrects rows the viewer deliberately removed,
 * just later. What actually makes a dismissal stale is the account moving, so that is what is
 * recorded. A null means the dismissal was stored before this field existed; the next refresh
 * adopts whatever the account says rather than lapsing it, so upgrading doesn't undo dismissals.
 */
@Serializable
data class DismissedRemoteRow(
    val anilistId: Int,
    val progressAtDismissal: Int? = null,
)

/**
 * Drop the dismissals the account has since moved past, and adopt a baseline for any that predate
 * [DismissedRemoteRow.progressAtDismissal].
 *
 * Movement in *either* direction lapses a dismissal. A rewatch resets the count downwards, and
 * that is as much a signal that something happened on another device as watching further is.
 *
 * [remoteProgressById] holds only the titles the account currently calls CURRENT or REPEATING. A
 * dismissal for anything else is kept untouched: there is nothing to seed, so it costs nothing,
 * and discarding it would resurface the row the moment the title came back.
 */
internal fun retainedDismissals(
    dismissed: Collection<DismissedRemoteRow>,
    remoteProgressById: Map<Int, Int>,
): List<DismissedRemoteRow> = dismissed.mapNotNull { row ->
    val remote = remoteProgressById[row.anilistId] ?: return@mapNotNull row
    when (row.progressAtDismissal) {
        null -> row.copy(progressAtDismissal = remote)
        remote -> row
        else -> null
    }
}

/** Persisted snapshot of a title's status on the signed-in list service. */
@Serializable
data class RemoteListStatus(
    val anilistId: Int,
    val status: String,
)

/** Persisted snapshot of the viewer's own score for a title, out of ten, on the signed-in service. */
@Serializable
data class RemoteListScore(
    val anilistId: Int,
    val score: Double,
)

/**
 * The viewer's own scores from an account read, out of ten. An unscored entry is 0 on both
 * services, which is "no score" and not a score of nothing, so it is left out.
 */
internal fun remoteListScores(entries: List<MediaListEntry>): Map<Int, Double> = entries
    .mapNotNull { entry ->
        val id = entry.media?.id ?: return@mapNotNull null
        entry.score.takeIf { it > 0.0 }?.let { id to it }
    }
    .toMap()

/**
 * What a score out of ten means, in MyAnimeList's own words — the scale its score menu shows, and
 * close enough to AniList's that one set of labels serves both.
 */
internal fun listScoreLabel(score: Int): String? = when (score) {
    10 -> "Masterpiece"
    9 -> "Great"
    8 -> "Very good"
    7 -> "Good"
    6 -> "Fine"
    5 -> "Average"
    4 -> "Bad"
    3 -> "Very bad"
    2 -> "Horrible"
    1 -> "Appalling"
    else -> null
}

/** A score as a viewer reads it: "8", or "7.5" for an AniList score set on the website. */
internal fun formatListScore(score: Double): String =
    if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, score)

internal fun remoteListStatuses(entries: List<MediaListEntry>): Map<Int, String> = entries
    .mapNotNull { entry ->
        val id = entry.media?.id ?: return@mapNotNull null
        val status = entry.status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        id to status
    }
    .toMap()

internal fun mediaListStatusLabel(status: String?): String? = when (status?.uppercase()) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Rewatching"
    "PLANNING" -> "Plan to watch"
    "PAUSED" -> "Paused"
    "COMPLETED" -> "Completed"
    "DROPPED" -> "Dropped"
    else -> null
}

/**
 * Merge the account's Plan-to-watch list into this device's saves, in both directions.
 *
 * This used to be additive only, which left the Saved list half-synced: adding a title reached
 * every device, removing one reached the account and stopped there. Unsave on the phone and the
 * television kept offering it forever, with no way to clear it but unsaving again on each device.
 *
 * A save is dropped only when the account **used** to know the title and now does not know it under
 * any status at all — [previousRemoteIds] minus [knownRemoteIds]. That is the one signal that
 * genuinely means "removed somewhere else", and phrasing it as a disappearance rather than an
 * absence is what makes the dangerous cases safe:
 *
 * - A save made while signed out has never appeared in a remote snapshot, so it can never be
 *   dropped — it survives until the push that carries it up.
 * - The first refresh after a fresh install or login has an empty [previousRemoteIds], so it can
 *   only add.
 * - Catalogue-native hanime ids are never on the account at all, so they are never removable.
 * - A title merely *moved* elsewhere (Planning to Watching) is still known, so the heart stays
 *   filled rather than silently clearing on a title the viewer never unsaved.
 *
 * [knownRemoteIds] covers every status, not just Planning, and an empty one is treated as no
 * information rather than as an empty account: a partial or degraded fetch must never be read as
 * "the viewer deleted everything".
 */
internal fun mergeWatchlistEntries(
    local: List<WatchlistEntry>,
    fromAniList: List<WatchlistEntry>,
    knownRemoteIds: Set<Int> = emptySet(),
    previousRemoteIds: Set<Int> = emptySet(),
    addedAt: Long = System.currentTimeMillis(),
): List<WatchlistEntry> {
    val removable = if (knownRemoteIds.isEmpty()) emptySet() else previousRemoteIds - knownRemoteIds
    if (fromAniList.isEmpty() && removable.isEmpty()) return local
    val remoteById = fromAniList.associateBy { it.anilistId }
    val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
    return buildList {
        local.forEach { saved ->
            if (saved.anilistId in removable) return@forEach
            val remote = remoteById[saved.anilistId]
            add(remote?.copy(addedAt = saved.addedAt) ?: saved)
        }
        fromAniList.forEach { remote ->
            if (remote.anilistId !in localIds) add(remote.copy(addedAt = addedAt))
        }
    }.distinctBy { it.anilistId }
}

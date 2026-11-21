package com.anilili.data.library

import android.content.Context
import android.content.SharedPreferences
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.model.MediaListEntry
import com.anilili.data.recs.TasteStore
import com.anilili.data.remote.ListProgressSyncWorker
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * On-device library: watch history (continue-watching + resume position) and watchlist.
 * Persisted as JSON in SharedPreferences; exposed as StateFlows so the UI reacts. No login.
 */
object LibraryStore {
    private const val KEY_HISTORY = "history"
    private const val KEY_WATCHLIST = "watchlist"
    private const val KEY_REMOTE_STATUSES = "remote_statuses"
    private const val KEY_REMOTE_SCORES = "remote_scores"
    private const val KEY_DISMISSED_REMOTE_HISTORY = "dismissed_remote_history"
    private const val KEY_PENDING_SAVED_SYNC = "pending_saved_sync"
    private const val MAX_HISTORY = 100
    private const val REMOTE_REFRESH_INTERVAL_MS = 30_000L

    /**
     * How many stalled saves one retry pass pushes. The MAL path spends three requests per title,
     * and AniList is paced against a shared per-minute quota, so a long offline stretch is worked
     * off over a few launches rather than as one burst at startup.
     */
    private const val MAX_SAVED_SYNC_RETRIES_PER_RUN = 40

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aniListSyncMutex = Mutex()
    private var remoteRefreshJob: Job? = null
    @Volatile private var lastRemoteRefreshAt = 0L

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    private val _remoteStatuses = MutableStateFlow<Map<Int, String>>(emptyMap())
    val remoteStatuses = _remoteStatuses.asStateFlow()

    /** The viewer's own score out of ten for each scored title on the account, from the same reads. */
    private val _remoteScores = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val remoteScores = _remoteScores.asStateFlow()
    private var dismissedRemoteHistory = emptyMap<Int, DismissedRemoteRow>()

    /**
     * Saves whose push to the account failed, in the order they failed.
     *
     * A save made with no signal, or during an AniList outage, used to be logged and forgotten:
     * nothing pushed it afterwards, because the only full push runs at login. The device kept the
     * title and no other device ever heard about it. Retrying is deliberately driven off what is
     * saved *now* rather than off the failed operation, so a save-then-unsave collapses to one
     * correct push instead of replaying both.
     */
    private var pendingSavedSync = emptySet<Int>()

    /**
     * Episode counts from the last account refresh, for the titles it calls CURRENT or REPEATING.
     * A dismissal is stamped with the count at the time, so this is what it is stamped from.
     */
    private var remoteProgress = emptyMap<Int, Int>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_library", Context.MODE_PRIVATE)
        val rawHistory = prefs.getString(KEY_HISTORY, null)
        val storedHistory = seedLegacyWatchedEpisodes(
            decodeList(rawHistory, HistoryEntry.serializer()),
            rawHistory,
        )
        // A list persisted by an older build can already contain a repeat; drop it on the way in.
        val orderedHistory = mergedHistory(storedHistory, emptyList(), MAX_HISTORY)
        _history.value = orderedHistory
        if (orderedHistory != storedHistory) {
            persist(KEY_HISTORY, orderedHistory, HistoryEntry.serializer())
        }
        _watchlist.value = decodeList(prefs.getString(KEY_WATCHLIST, null), WatchlistEntry.serializer())
        _remoteStatuses.value = decodeList(
            prefs.getString(KEY_REMOTE_STATUSES, null),
            RemoteListStatus.serializer(),
        ).associate { it.anilistId to it.status }
        _remoteScores.value = decodeList(
            prefs.getString(KEY_REMOTE_SCORES, null),
            RemoteListScore.serializer(),
        ).associate { it.anilistId to it.score }
        dismissedRemoteHistory = loadDismissedRemoteHistory()
        pendingSavedSync = decodeList(
            prefs.getString(KEY_PENDING_SAVED_SYNC, null),
            Int.serializer(),
        ).toSet()
        CustomListStore.init(prefs)
        LocalWatchedStore.init(prefs)
        WatchActivityStore.init(prefs)
        TasteStore.init(prefs)
    }

    /**
     * Dismissals were a StringSet of bare ids before they carried the account's progress. Reading
     * the old shape leaves [DismissedRemoteRow.progressAtDismissal] null, which the next refresh
     * fills in from the account — so an upgrade keeps every dismissal the viewer had rather than
     * lapsing them all at once.
     */
    private fun loadDismissedRemoteHistory(): Map<Int, DismissedRemoteRow> {
        // Both accessors throw ClassCastException when the key holds the other type, and on an
        // install written by an older build it holds the StringSet, so each attempt is guarded.
        val encoded = runCatching { prefs.getString(KEY_DISMISSED_REMOTE_HISTORY, null) }.getOrNull()
        if (encoded != null) {
            return decodeList(encoded, DismissedRemoteRow.serializer())
                .associateBy(DismissedRemoteRow::anilistId)
        }
        return runCatching { prefs.getStringSet(KEY_DISMISSED_REMOTE_HISTORY, emptySet()) }
            .getOrNull()
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .associateWith { DismissedRemoteRow(anilistId = it) }
    }

    // ---- history ----

    /** Insert/replace the anime's record (keeps one per anime, most-recent first). */
    fun upsertHistory(entry: HistoryEntry) {
        if (entry.anilistId in dismissedRemoteHistory) {
            dismissedRemoteHistory = dismissedRemoteHistory - entry.anilistId
            persistDismissedRemoteHistory()
        }
        val stamped = entry.copy(updatedAt = System.currentTimeMillis())
        val updated = sortHistoryLatestFirst(
            buildList {
                add(stamped)
                addAll(_history.value.filter { it.anilistId != entry.anilistId })
            },
        ).take(MAX_HISTORY)
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        // TV launchers surface in-progress titles in their Continue Watching row; publishing is
        // throttled inside the manager and a no-op off Android TV.
        scope.launch { WatchNextManager.publish(appContext, stamped) }
    }

    /**
     * Record a playback clock against the episode it actually belongs to.
     *
     * This used to ignore anything but the episode the row points at, which is why a half-watched
     * episode restarted from zero the moment the viewer played a different one in the same series
     * or the row advanced past the watched threshold. [HistoryEntry.withEpisodeProgress] keeps the
     * clock per episode and still refuses a late callback for one already counted as watched, which
     * is what the old episode check was really guarding against.
     *
     * [followsPlayback] moves the row itself onto [episodeNumber] once playback has confirmed it is
     * the one actually running — see [HistoryEntry.withEpisodeProgress] for what earns it.
     */
    fun updateProgress(
        anilistId: Int,
        episodeNumber: Double,
        positionMs: Long,
        durationMs: Long,
        followsPlayback: Boolean = false,
    ) {
        val existing = _history.value.firstOrNull { it.anilistId == anilistId } ?: return
        val updated = existing.withEpisodeProgress(episodeNumber, positionMs, durationMs, followsPlayback)
        if (updated == existing) return
        upsertHistory(updated)
    }

    /**
     * Advance the local Continue Watching row as soon as playback counts an episode as watched.
     * This is intentionally independent from account sync: local history remains correct even
     * while offline, logged out, or when a remote service rejects an update.
     *
     * A [seriesCompleted] episode also marks the title watched when nobody is signed in, which is
     * what the account's progress sync does for a viewer who is ([LocalWatchedStore.markFinished]).
     * [totalEpisodes] is the series' length, recorded so the account gets the full count later.
     */
    fun markEpisodeWatched(
        anilistId: Int,
        watchedEpisode: Double,
        nextEpisode: Double?,
        nextEpisodeTitle: String?,
        seriesCompleted: Boolean,
        totalEpisodes: Int? = null,
    ) {
        val existing = historyFor(anilistId) ?: return
        // Before the row logic, which ignores an episode the row is not on: the series is finished
        // whichever episode the row happened to be showing.
        if (seriesCompleted) LocalWatchedStore.markFinished(anilistId, existing.title, existing.cover, totalEpisodes)
        val updated = historyAfterEpisodeWatched(
            existing = existing,
            watchedEpisode = watchedEpisode,
            nextEpisode = nextEpisode,
            nextEpisodeTitle = nextEpisodeTitle,
            seriesCompleted = seriesCompleted,
        )
        if (updated == existing) return
        // A caught-up row can advance when a next episode becomes available even though this one
        // was already watched. The activity log also checks the old watched set before counting.
        WatchActivityStore.recordEpisode(existing, watchedEpisode)
        if (updated == null) {
            DiagnosticsLog.event(
                "Continue Watching completed id=$anilistId episode=${existing.episodeLabel}",
            )
            // Prevent a still-stale remote CURRENT record from immediately recreating the row.
            removeHistory(anilistId, dismissRemoteSeed = AccountService.active != null)
        } else {
            DiagnosticsLog.event(
                "Continue Watching advanced id=$anilistId from=${existing.episodeLabel} " +
                    "to=${updated.episodeLabel}",
            )
            upsertHistory(updated)
        }
    }

    /**
     * Persist progress delivery across navigation, process death, and lost connectivity — to the
     * signed-in list service, and to Simkl when that is connected beside it (or instead of one).
     */
    fun enqueueProgressSync(anilistId: Int, progress: Int, totalEpisodes: Int?) {
        if (AccountService.active != null) {
            ListProgressSyncWorker.enqueue(appContext, anilistId, progress, totalEpisodes)
        }
        com.anilili.data.remote.SimklTracker.progress(anilistId, progress)
    }

    /**
     * Mark one episode watched or unwatched by hand, from the detail screen's episode list.
     *
     * The change is local only — remote delivery, when wanted, is the caller's
     * [enqueueProgressSync]. A title with no row yet gets a minimal one pointing at the toggled
     * episode: without a row the mark would be invisible everywhere, since every episode picker
     * reads watched state from here. Unmarking a title with no row is a no-op.
     *
     * [airedEpisodes] is the season's playable list, and lets the mark move the row the way
     * finishing the episode would — see [historyAfterEpisodeToggled].
     */
    fun setEpisodeWatched(
        anilistId: Int,
        episodeNumber: Double,
        watched: Boolean,
        title: String? = null,
        cover: String? = null,
        airedEpisodes: List<EpisodeStop> = emptyList(),
    ) {
        val existing = historyFor(anilistId)
        if (existing == null) {
            if (!watched || title == null) return
            // Built on the marked episode unmarked, so the mark itself goes through the same rule as
            // on an existing row and the new row lands on the next episode rather than this one.
            val seed = HistoryEntry(
                anilistId = anilistId,
                title = title,
                cover = cover,
                episodeNumber = episodeNumber,
                provider = AUTO_PROVIDER,
                category = if (SettingsStore.preferDub.value) "dub" else "sub",
            )
            val created = historyAfterEpisodeToggled(seed, episodeNumber, true, airedEpisodes) ?: return
            upsertHistory(created)
            DiagnosticsLog.event(
                "Episode marked watched id=$anilistId episode=${episodeKey(episodeNumber)} " +
                    "(new row on ${created.episodeLabel})",
            )
            return
        }
        val updated = historyAfterEpisodeToggled(existing, episodeNumber, watched, airedEpisodes) ?: return
        DiagnosticsLog.event(
            "Episode marked ${if (watched) "watched" else "unwatched"} " +
                "id=$anilistId episode=${episodeKey(episodeNumber)}" +
                if (updated.episodeNumber != existing.episodeNumber) {
                    " row ${existing.episodeLabel}->${updated.episodeLabel}"
                } else {
                    ""
                },
        )
        upsertHistory(updated)
    }

    /**
     * Whether a finished episode of this title should reach the signed-in list service at all.
     *
     * Every player asks the same three questions, so they live here rather than in each screen —
     * the offline player was missing them, and with them the sync call itself.
     *
     * Catalogue-native entries (hanime, One Pace) carry a negative, app-owned id. Posting one to
     * AniList or MAL would be a request about somebody else's anime, so they never sync — the same
     * rule the Save button applies in [toggleWatchlist].
     *
     * Simkl counts as somewhere to send it: it tracks beside AniList or MyAnimeList, or alone.
     */
    fun canSyncProgress(anilistId: Int): Boolean =
        (AccountService.active != null || com.anilili.data.auth.SimklAuthManager.isLoggedIn) &&
            !com.anilili.data.remote.isCatalogueNativeMediaId(anilistId) &&
            SettingsStore.autoSyncAniList.value

    fun historyFor(anilistId: Int): HistoryEntry? = _history.value.firstOrNull { it.anilistId == anilistId }

    /**
     * Remove one Continue Watching row. With [dismissRemoteSeed], account sync cannot immediately
     * recreate it; actually playing the title again clears that dismissal.
     */
    fun removeHistory(anilistId: Int, dismissRemoteSeed: Boolean = true) {
        val updated = _history.value.filter { it.anilistId != anilistId }
        if (updated == _history.value) return
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        if (dismissRemoteSeed && AccountService.active != null) {
            dismiss(listOf(anilistId))
        }
        scope.launch { WatchNextManager.remove(appContext, anilistId) }
    }

    fun clearHistory() {
        if (AccountService.active != null) {
            dismiss(_history.value.map(HistoryEntry::anilistId))
        }
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        scope.launch { WatchNextManager.removeAll(appContext) }
    }

    /**
     * Merge Continue Watching rows from a device backup, and report how many rows it changed.
     *
     * A restore adds to this device rather than replacing it, and where both devices know a title
     * the newer record wins — [mergedHistory] already resolves that by [HistoryEntry.updatedAt],
     * which is the same rule account sync uses. Restoring a backup taken before this evening's
     * viewing therefore cannot rewind anything watched since.
     *
     * The launcher's own Continue Watching row is left alone: publishing a hundred titles at once
     * is not what that row is for, and the next episode actually played republishes normally.
     *
     * A restored row's watched episodes are taken as written. They used to be back-filled the way
     * [seedLegacyWatchedEpisodes] does it for rows read off this device's own disk, but that guess
     * needs the stored JSON to tell a pre-v0.1.56 row from an accurate empty one, and a backup
     * document arrives here already decoded. Guessing without it marks episodes watched that the
     * other device knew were not.
     */
    fun restoreHistory(entries: List<HistoryEntry>): Int {
        if (entries.isEmpty()) return 0
        val before = _history.value
        val merged = mergedHistory(local = before, seeded = entries, max = MAX_HISTORY)
        if (merged == before) return 0
        _history.value = merged
        persist(KEY_HISTORY, merged, HistoryEntry.serializer())
        val previous = before.associateBy(HistoryEntry::anilistId)
        return merged.count { it != previous[it.anilistId] }
    }

    // ---- watchlist ----

    fun isInWatchlist(anilistId: Int): Boolean = _watchlist.value.any { it.anilistId == anilistId }

    fun toggleWatchlist(entry: WatchlistEntry) {
        val updated = if (isInWatchlist(entry.anilistId)) {
            _watchlist.value.filter { it.anilistId != entry.anilistId }
        } else {
            listOf(entry.copy(addedAt = System.currentTimeMillis())) + _watchlist.value
        }
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        val service = AccountService.active
        // Catalogue-native entries (hanime, One Pace) carry a negative, app-owned id. Posting one
        // to AniList or MAL would be a request about somebody else's anime, so they never sync.
        val syncable = !com.anilili.data.remote.isCatalogueNativeMediaId(entry.anilistId)
        if (syncable && SettingsStore.syncSavedToAniList.value) {
            com.anilili.data.remote.SimklTracker.saved(
                entry.anilistId,
                saved = updated.any { it.anilistId == entry.anilistId },
            )
        }
        if (service != null && syncable && SettingsStore.syncSavedToAniList.value) {
            val saved = updated.any { it.anilistId == entry.anilistId }
            scope.launch {
                aniListSyncMutex.withLock {
                    runCatching { pushSavedState(service, entry.anilistId, saved) }
                        .onSuccess {
                            markSavedSyncSettled(setOf(entry.anilistId))
                            refreshRemoteLibrary(force = true)
                        }
                        .onFailure {
                            // Held for the next launch to retry; without this the account simply
                            // never learns about this save.
                            markSavedSyncPending(setOf(entry.anilistId))
                            com.anilili.diagnostics.DiagnosticsLog.throwable(
                                "${service.label} saved sync failed id=${entry.anilistId} saved=$saved",
                                it,
                            )
                        }
                }
            }
        }
    }

    private suspend fun pushSavedState(service: AccountService, anilistId: Int, saved: Boolean) {
        when (service) {
            AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(anilistId, saved)
            AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(anilistId, saved)
        }
    }

    /** Push the whole device watchlist to whichever list service is signed in. */
    fun syncSavedToRemote() {
        val service = AccountService.active ?: return
        if (!SettingsStore.syncSavedToAniList.value) return
        val savedIds = _watchlist.value.map { it.anilistId }
        scope.launch {
            aniListSyncMutex.withLock {
                runCatching {
                    when (service) {
                        AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(savedIds)
                        AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(savedIds)
                    }
                }.onSuccess {
                    markSavedSyncSettled(savedIds.toSet())
                    refreshRemoteLibrary(force = true)
                }.onFailure {
                    markSavedSyncPending(savedIds)
                    com.anilili.diagnostics.DiagnosticsLog.throwable(
                        "${service.label} watchlist sync failed (${savedIds.size} titles)",
                        it,
                    )
                }
            }
        }
    }

    /**
     * Re-push the saves whose last attempt failed. Safe to call on every launch: it does nothing
     * at all unless something is actually outstanding.
     *
     * Each title is pushed as whatever the device says *now*, so an unsave that failed is delivered
     * as an unsave rather than replayed as the save that preceded it.
     */
    fun retryPendingSavedSync() {
        // Custom lists keep their own queue, and it is owed whatever the watchlist setting says.
        CustomListStore.pushPending()
        val service = AccountService.active ?: return
        if (!SettingsStore.syncSavedToAniList.value) return
        val batch = pendingSavedSync.take(MAX_SAVED_SYNC_RETRIES_PER_RUN)
        if (batch.isEmpty()) return
        scope.launch {
            aniListSyncMutex.withLock {
                DiagnosticsLog.event(
                    "${service.label} saved sync retry starting titles=${batch.size} " +
                        "outstanding=${pendingSavedSync.size}",
                )
                val settled = mutableSetOf<Int>()
                batch.forEach { anilistId ->
                    val saved = _watchlist.value.any { it.anilistId == anilistId }
                    runCatching { pushSavedState(service, anilistId, saved) }
                        .onSuccess { settled += anilistId }
                        .onFailure {
                            DiagnosticsLog.throwable(
                                "${service.label} saved sync retry failed id=$anilistId saved=$saved",
                                it,
                            )
                        }
                }
                if (settled.isNotEmpty()) {
                    markSavedSyncSettled(settled)
                    DiagnosticsLog.event(
                        "${service.label} saved sync retry delivered titles=${settled.size} " +
                            "outstanding=${pendingSavedSync.size}",
                    )
                    refreshRemoteLibrary(force = true)
                }
            }
        }
    }

    private fun markSavedSyncPending(anilistIds: Collection<Int>) {
        val updated = pendingSavedSync + anilistIds
        if (updated == pendingSavedSync) return
        pendingSavedSync = updated
        persistPendingSavedSync()
    }

    private fun markSavedSyncSettled(anilistIds: Set<Int>) {
        val updated = pendingSavedSync - anilistIds
        if (updated == pendingSavedSync) return
        pendingSavedSync = updated
        persistPendingSavedSync()
    }

    private fun persistPendingSavedSync() {
        persist(KEY_PENDING_SAVED_SYNC, pendingSavedSync.toList(), Int.serializer())
    }

    /**
     * Bulk add from a MAL XML import or a device backup. Existing saves keep their position and
     * addedAt; the whole batch syncs to the signed-in list service in one push rather than per
     * title. Returns how many entries were actually new.
     */
    fun importWatchlist(entries: List<WatchlistEntry>): Int {
        val current = _watchlist.value
        val existing = current.mapTo(mutableSetOf()) { it.anilistId }
        val now = System.currentTimeMillis()
        val newEntries = entries
            .distinctBy { it.anilistId }
            .filter { it.anilistId !in existing }
            .map { it.copy(addedAt = now) }
        if (newEntries.isEmpty()) return 0
        val updated = newEntries + current
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        syncSavedToRemote()
        return newEntries.size
    }

    /**
     * Merge the account's Planning list into this device, keeping device-only saves and dropping
     * the ones the account has since lost. [knownRemoteIds] and [previousRemoteIds] are what let
     * [mergeWatchlistEntries] tell a removal apart from a save that has simply never been pushed.
     */
    fun hydrateWatchlistFromAniList(
        entries: List<WatchlistEntry>,
        knownRemoteIds: Set<Int> = emptySet(),
        previousRemoteIds: Set<Int> = emptySet(),
    ) {
        val merged = mergeWatchlistEntries(
            local = _watchlist.value,
            fromAniList = entries,
            knownRemoteIds = knownRemoteIds,
            previousRemoteIds = previousRemoteIds,
        )
        if (merged == _watchlist.value) return
        val dropped = _watchlist.value.map(WatchlistEntry::anilistId) - merged.map(WatchlistEntry::anilistId).toSet()
        if (dropped.isNotEmpty()) {
            DiagnosticsLog.event("Watchlist removals from account ids=${dropped.joinToString(",")}")
        }
        _watchlist.value = merged
        persist(KEY_WATCHLIST, merged, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
    }

    /**
     * Publish a freshly fetched remote collection to every screen immediately. Unlike the local
     * watchlist, this includes every list state so Detail can distinguish Watching, Completed,
     * Paused, and Dropped from a title that truly is not tracked.
     */
    fun hydrateRemoteLibrary(
        allEntries: List<MediaListEntry>,
        aniListCustomListNames: List<String>? = null,
        service: AccountService? = AccountService.active,
    ) {
        service?.let { CustomListStore.hydrateFromAccount(it, allEntries, aniListCustomListNames) }
        // An entry filed in custom lists only is not on any status list — the viewer put it in a list,
        // they did not plan it — so the watchlist, Continue Watching and the status snapshot never
        // see it. Its status is a placeholder AniList insists on, not a statement.
        val entries = allEntries.filterNot { it.hiddenFromStatusLists }
        val statuses = remoteListStatuses(entries)
        // Captured before the snapshot is replaced: the difference between the two is how a save
        // the account lost is told apart from one it has never been told about, and how a title
        // finished or dropped elsewhere is told apart from one that was always finished.
        val previousStatuses = _remoteStatuses.value
        val previousRemoteIds = previousStatuses.keys
        _remoteStatuses.value = statuses
        persist(
            KEY_REMOTE_STATUSES,
            statuses.map { (id, status) -> RemoteListStatus(id, status) },
            RemoteListStatus.serializer(),
        )
        val scores = remoteListScores(entries)
        _remoteScores.value = scores
        persistScores(scores)
        lastRemoteRefreshAt = System.currentTimeMillis()
        seedHistoryFromRemote(entries, movedOffWatching = remoteMovesOffWatching(previousStatuses, statuses))
        TasteStore.recordAccount(entries)
        // Titles marked watched before signing in become Completed on the account, after the
        // snapshot above so a mark the account already agrees with is recognised as settled.
        service?.let { LocalWatchedStore.deliverToAccount(it, entries) }

        if (SettingsStore.syncSavedToAniList.value) {
            hydrateWatchlistFromAniList(
                entries.mapNotNull { entry ->
                    if (entry.status != "PLANNING") return@mapNotNull null
                    val media = entry.media ?: return@mapNotNull null
                    WatchlistEntry(
                        anilistId = media.id,
                        title = media.title.preferred,
                        cover = media.coverImage.best,
                        format = media.format,
                        averageScore = media.averageScore,
                    )
                },
                knownRemoteIds = statuses.keys,
                previousRemoteIds = previousRemoteIds,
            )
        }
    }

    /**
     * Reconcile Continue Watching with the signed-in service, in two parts.
     *
     * A title with no local row is *seeded*: it becomes a resumable row pointing at the next
     * unwatched episode, so a fresh install isn't blank. Seeded rows are replaced wholesale on
     * every refresh, superseded by a real record the moment the title is played here, and vanish
     * on logout. The "auto" provider is the same sentinel Watch Now uses when nothing local
     * exists: the watch screen resolves a source itself.
     *
     * A title that *does* have a local row is reconciled through [historyAfterRemoteProgress] —
     * this is the half that makes sync work across devices rather than only on a fresh install.
     * Remote progress can carry that row forward, but never backwards, and it changes nothing
     * else about it.
     *
     * A title the account *calls* COMPLETED or DROPPED is left alone: neither status distinguishes
     * "finished everywhere" from "finished once and being rewatched here", and the services refuse
     * the progress regression a rewatch would push. A title the account has *moved* there since the
     * last refresh — [movedOffWatching], see [remoteMovesOffWatching] — is different: the viewer
     * finished, dropped or paused it somewhere else, and its row comes off. Before this, a series
     * finished on another app stayed in Continue Watching on the episode this device last saw.
     * So does a COMPLETED title whose row already sits on its final episode, watched
     * ([isFinishedSeriesLeftover]): nothing is left to continue, and no rewatch looks like that.
     */
    private fun seedHistoryFromRemote(entries: List<MediaListEntry>, movedOffWatching: Set<Int> = emptySet()) {
        val preferDub = SettingsStore.preferDub.value
        val completedLength = entries
            .filter { it.status == "COMPLETED" }
            .mapNotNull { entry -> entry.media?.let { it.id to it.episodes } }
            .toMap()
        val (finishedElsewhere, stored) = _history.value
            .filterNot { it.fromRemote }
            .partition { row ->
                row.anilistId in movedOffWatching ||
                    (row.anilistId in completedLength &&
                        isFinishedSeriesLeftover(row, "COMPLETED", completedLength[row.anilistId]))
            }
        if (finishedElsewhere.isNotEmpty()) {
            DiagnosticsLog.event(
                "Continue Watching finished or moved off Watching on the account " +
                    "ids=${finishedElsewhere.joinToString(",") { it.anilistId.toString() }}",
            )
            // Not dismissed: a dismissal is for a row the viewer removed, and stamping one here
            // would keep a rewatch begun on the account from ever coming back.
            finishedElsewhere.forEach { row -> scope.launch { WatchNextManager.remove(appContext, row.anilistId) } }
        }
        val watching = entries
            .filter { it.status == "CURRENT" || it.status == "REPEATING" }
            .mapNotNull { entry -> entry.media?.let { it.id to entry } }
            .toMap()
        remoteProgress = watching.mapValues { (_, entry) -> entry.progress }
        // A row the viewer removed here stays removed until another device moves the account past
        // the point it was removed at; then the dismissal is stale and the row comes back.
        val retained = retainedDismissals(dismissedRemoteHistory.values, remoteProgress)
            .associateBy(DismissedRemoteRow::anilistId)
        if (retained != dismissedRemoteHistory) {
            val lapsed = dismissedRemoteHistory.keys - retained.keys
            if (lapsed.isNotEmpty()) {
                DiagnosticsLog.event("Continue Watching dismissals lapsed ids=${lapsed.joinToString(",")}")
            }
            dismissedRemoteHistory = retained
            persistDismissedRemoteHistory()
        }
        // A row this device wrote still has to hear about episodes finished on another one. Only
        // its episode count moves; the provider, category and title it chose here are its own.
        val local = stored.map { row ->
            val remote = watching[row.anilistId] ?: return@map row
            historyAfterRemoteProgress(
                local = row,
                remoteProgress = remote.progress,
                remoteUpdatedAt = remote.updatedAt * 1_000L,
                totalEpisodes = remote.media?.episodes,
                airedEpisodes = remote.media?.airedEpisodes,
            )?.also {
                DiagnosticsLog.event(
                    "Continue Watching reconciled id=${row.anilistId} from=${row.episodeLabel} " +
                        "to=${it.episodeLabel} remoteProgress=${remote.progress}",
                )
            } ?: row
        }
        val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
        val seeded = entries.mapNotNull { entry ->
            if (entry.status != "CURRENT" && entry.status != "REPEATING") return@mapNotNull null
            val media = entry.media ?: return@mapNotNull null
            if (media.id in dismissedRemoteHistory) return@mapNotNull null
            if (media.id in localIds) return@mapNotNull null
            // Caught up on an airing series, the row rests on the latest aired episode. It used to
            // be skipped until the next one aired, so a show followed week by week somewhere else
            // was missing from Continue Watching for most of its run.
            val episode = remoteRowEpisode(entry.progress, media.episodes, media.airedEpisodes)
                ?: return@mapNotNull null
            HistoryEntry(
                anilistId = media.id,
                title = media.title.preferred,
                cover = media.coverImage.best,
                episodeNumber = episode.toDouble(),
                provider = AUTO_PROVIDER,
                category = if (preferDub) "dub" else "sub",
                // Continue Watching is ordered by updatedAt, so a seeded row left at 0 sank below
                // every locally played title and, past MAX_HISTORY, fell off the list entirely —
                // a fresh install signed into an existing account looked like it had synced
                // nothing. The list service's own timestamp is exactly the "last watched" this
                // ordering wants; it is in seconds.
                updatedAt = entry.updatedAt * 1_000L,
                fromRemote = true,
                // AniList and MAL only store a count, not which episodes were played, so the
                // count is the best statement available: everything up to it counts as watched.
                watchedEpisodes = generateSequence(1.0) { it + 1.0 }
                    .takeWhile { it <= entry.progress }
                    .toSet(),
            )
        }
        val updated = mergedHistory(local, seeded, MAX_HISTORY)
        if (updated == _history.value) return
        _history.value = updated
        // No WatchNextManager publish for seeded rows: the launcher's Continue Watching channel
        // is reserved for titles actually played on this device.
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
    }

    /**
     * The account has just confirmed a progress push that took [anilistId] to Completed: the
     * series is finished, so its Continue Watching row comes off.
     *
     * The player normally does this itself at the finale, but only when it knows the series' length,
     * and the push looks the length up on its own when it was not given one. A finale played from
     * downloads with nothing cached was exactly that case: the account went to Completed and the row
     * stayed on the last episode, with the snapshot already saying COMPLETED so no later refresh saw
     * a move to act on either. A row the push did not reach — on an episode past [progress] — is
     * left alone. Not dismissed: a rewatch begun on another device should still be able to bring it
     * back.
     */
    fun finishedOnAccount(anilistId: Int, progress: Int) {
        val row = historyFor(anilistId) ?: return
        if (row.episodeNumber > progress) return
        DiagnosticsLog.event(
            "Continue Watching completed by the account id=$anilistId episode=${row.episodeLabel} progress=$progress",
        )
        removeHistory(anilistId, dismissRemoteSeed = false)
    }

    /** Record a score the account has just confirmed, so every screen shows it before the next read. */
    fun updateRemoteScore(anilistId: Int, score: Int) {
        val updated = _remoteScores.value.toMutableMap().apply {
            if (score > 0) put(anilistId, score.toDouble()) else remove(anilistId)
        }
        _remoteScores.value = updated
        persistScores(updated)
    }

    private fun persistScores(scores: Map<Int, Double>) {
        persist(
            KEY_REMOTE_SCORES,
            scores.map { (id, score) -> RemoteListScore(id, score) },
            RemoteListScore.serializer(),
        )
    }

    /** Update the snapshot synchronously after playback changes an AniList/MAL list state. */
    fun updateRemoteStatus(anilistId: Int, status: String?) {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val updated = _remoteStatuses.value.toMutableMap().apply {
            if (normalized == null) remove(anilistId) else put(anilistId, normalized)
        }
        _remoteStatuses.value = updated
        persist(
            KEY_REMOTE_STATUSES,
            updated.map { (id, value) -> RemoteListStatus(id, value) },
            RemoteListStatus.serializer(),
        )
    }

    /** Refresh on cold start/foreground, while bounding AniList's shared API rate limit. */
    @Synchronized
    fun refreshRemoteLibrary(force: Boolean = false) {
        val service = AccountService.active
        if (service == null) {
            if (_remoteStatuses.value.isNotEmpty()) clearRemoteLibrary()
            return
        }
        val now = System.currentTimeMillis()
        if (remoteRefreshJob?.isActive == true) return
        if (!force && now - lastRemoteRefreshAt < REMOTE_REFRESH_INTERVAL_MS) return
        lastRemoteRefreshAt = now
        remoteRefreshJob = scope.launch {
            runCatching {
                aniListSyncMutex.withLock {
                    var customListNames: List<String>? = null
                    val entries = when (service) {
                        AccountService.ANILIST -> {
                            val viewerId = AuthManager.viewerId() ?: AppGraph.repository.viewer()?.id
                                ?: error("Couldn't load your AniList account")
                            val collection = AppGraph.repository.userAnimeList(viewerId)
                                ?: error("Couldn't load your AniList library")
                            customListNames = collection.customListNames
                            collection.lists
                                .flatMap { it.entries }
                                .distinctBy { it.id }
                        }
                        AccountService.MAL -> AppGraph.repository.malAnimeList()
                    }
                    hydrateRemoteLibrary(entries, customListNames, service)
                    com.anilili.diagnostics.DiagnosticsLog.event(
                        "${service.label} library refreshed statuses=${_remoteStatuses.value.size}",
                    )
                }
            }.onFailure {
                lastRemoteRefreshAt = 0L
                com.anilili.diagnostics.DiagnosticsLog.throwable(
                    "${service.label} library refresh failed",
                    it,
                )
            }
        }
    }

    /**
     * Forget everything that describes the account being left — on logout, and on a switch between
     * the two services *before* the replacement's first refresh can read it as its own.
     *
     * Every one of these is keyed by AniList media id, which the MAL path shares, so none of it
     * announces which account wrote it. Left in place across a switch, the stale snapshot became
     * [mergeWatchlistEntries]'s `previousRemoteIds`: a title the old account knew and the new one
     * does not looks exactly like a save the viewer deleted elsewhere, and the device dropped it.
     * Clearing first leaves that set empty, so the first refresh on a new account can only add.
     */
    fun clearRemoteLibrary() {
        remoteRefreshJob?.cancel()
        remoteRefreshJob = null
        lastRemoteRefreshAt = 0L
        _remoteStatuses.value = emptyMap()
        _remoteScores.value = emptyMap()
        TasteStore.clearAccount()
        dismissedRemoteHistory = emptyMap()
        remoteProgress = emptyMap()
        // Outstanding pushes were owed to the account being left. A switch re-pushes the whole
        // watchlist to the replacement anyway, so nothing is lost by dropping them here.
        pendingSavedSync = emptySet()
        prefs.edit()
            .remove(KEY_REMOTE_STATUSES)
            .remove(KEY_REMOTE_SCORES)
            .remove(KEY_DISMISSED_REMOTE_HISTORY)
            .remove(KEY_PENDING_SAVED_SYNC)
            .apply()
        CustomListStore.onAccountLeft()
        // Seeded Continue Watching rows belong to the account that just signed out.
        val localOnly = sortHistoryLatestFirst(_history.value.filterNot { it.fromRemote })
        if (localOnly != _history.value) {
            _history.value = localOnly
            persist(KEY_HISTORY, localOnly, HistoryEntry.serializer())
        }
    }

    // ---- persistence ----

    private fun <T> persist(key: String, list: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer), list)
        prefs.edit().putString(key, encoded).apply()
    }

    /**
     * Stamp each dismissal with what the account currently says, so it lapses if that moves.
     * Titles the account isn't tracking are stamped null and adopt a baseline on the next refresh.
     */
    private fun dismiss(anilistIds: Collection<Int>) {
        dismissedRemoteHistory = dismissedRemoteHistory + anilistIds.associateWith { id ->
            DismissedRemoteRow(anilistId = id, progressAtDismissal = remoteProgress[id])
        }
        persistDismissedRemoteHistory()
    }

    private fun persistDismissedRemoteHistory() {
        persist(
            KEY_DISMISSED_REMOTE_HISTORY,
            dismissedRemoteHistory.values.toList(),
            DismissedRemoteRow.serializer(),
        )
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }
}

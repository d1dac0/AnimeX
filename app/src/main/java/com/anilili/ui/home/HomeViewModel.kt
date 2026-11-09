package com.anilili.ui.home

import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.NextEpisodeRelease
import com.anilili.data.library.UPCOMING_AIRING_DAYS
import com.anilili.data.library.UpcomingAiring
import com.anilili.data.library.hideFromContinueUntilAired
import com.anilili.data.library.mergeNextReleases
import com.anilili.data.library.nextReleasesByTitle
import com.anilili.data.library.returnsToContinueAt
import com.anilili.data.model.HomeCollections
import com.anilili.data.model.Media
import com.anilili.data.recs.RecommendationRow
import com.anilili.data.recs.Recommendations
import com.anilili.data.recs.TasteStore
import com.anilili.data.settings.SettingsStore
import com.anilili.errors.AnililiError
import kotlinx.coroutines.flow.drop
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

private const val HOME_LOAD_TIMEOUT_MS = 15_000L

/** Long enough for a pull-to-refresh spinner to register as an answer rather than a flicker. */
private const val MIN_REFRESH_INDICATOR_MS = 550L

/**
 * Rows fetched longer ago than this are swapped for live ones behind the screen that drew them.
 * Younger rows are left alone, so reopening the app often does not spend a rate-limited AniList
 * request on each open.
 */
private const val HOME_REVALIDATE_AFTER_MS = 2L * 60 * 60 * 1000

/**
 * A pageable Home row.
 *
 * Every one of the six is a rail on both form factors now — the phone's catalogue tabs are gone,
 * and with them the reason this was ever split between a tab enum and a section enum.
 */
enum class HomeSection {
    NEWEST,
    POPULAR,
    TRENDING,
    TOP_RATED,
    MOVIES,
    UPCOMING,
}

data class HomeData(
    val spotlight: List<Media>,
    val newest: List<Media>,
    val popular: List<Media>,
    val movies: List<Media>,
    val topRated: List<Media>,
    val upcoming: List<Media> = emptyList(),
) {
    fun section(section: HomeSection): List<Media> = when (section) {
        HomeSection.NEWEST -> newest
        HomeSection.POPULAR -> popular
        HomeSection.TRENDING -> spotlight
        HomeSection.TOP_RATED -> topRated
        HomeSection.MOVIES -> movies
        HomeSection.UPCOMING -> upcoming
    }

    fun withSection(section: HomeSection, items: List<Media>): HomeData = when (section) {
        HomeSection.NEWEST -> copy(newest = items)
        HomeSection.POPULAR -> copy(popular = items)
        HomeSection.TRENDING -> copy(spotlight = items)
        HomeSection.TOP_RATED -> copy(topRated = items)
        HomeSection.MOVIES -> copy(movies = items)
        HomeSection.UPCOMING -> copy(upcoming = items)
    }
}

/**
 * Home's two personal rows: "For you" from the whole list, and "Because you watched X" from the
 * latest favourite. Both empty for a viewer with nothing watched yet, and the rows then simply
 * aren't drawn.
 */
data class PersonalRows(
    val forYou: List<Media> = emptyList(),
    val because: RecommendationRow? = null,
)

/** Recomputing is cheap but asks AniList for cards; coming back to Home more often than this reuses the rows. */
private const val PERSONAL_REFRESH_AFTER_MS = 5L * 60 * 1000

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()

    private val _personal = MutableStateFlow(PersonalRows())
    val personal = _personal.asStateFlow()
    private var personalJob: Job? = null
    private var personalAt = 0L
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0

    /** Per-section paging cursor. Rebuilt on every load so a refresh starts from page 1 again. */
    private class Paging {
        var page = 1
        var loading = false
        var hasMore = true
    }

    private val paging = HomeSection.entries.associateWith { Paging() }

    /** Sections with a page in flight, so a rail can show progress and a button can disable. */
    private val _loadingMore = MutableStateFlow<Set<HomeSection>>(emptySet())
    val loadingMore = _loadingMore.asStateFlow()

    /** Sections AniList says are exhausted, so the Load More control can retire itself. */
    private val _exhausted = MutableStateFlow<Set<HomeSection>>(emptySet())
    val exhausted = _exhausted.asStateFlow()

    /**
     * The airing schedule from yesterday to a fortnight out, keyed by title: which episodes air, and
     * when. See [UpcomingAiring].
     *
     * Held in the state rather than in a field the predicate reads, so the row recomposes when a
     * read lands — and so the answer cannot be a stale closure over an earlier day's schedule.
     */
    private val upcoming = MutableStateFlow<UpcomingAiring>(emptyMap())

    /** The day [upcoming] was read on, so the window is moved on when the date has. */
    private var upcomingLoadedFor: LocalDate? = null

    /**
     * AniList's next-airing answer for titles the published schedule does not carry.
     *
     * The corpus is built for planning and misses some airings entirely, which left those cards with
     * no countdown while their neighbours had one. Fetched only for titles the corpus lacks, in one
     * batched request, and kept beside it rather than mixed into it, so the schedule keeps winning
     * wherever it has an answer.
     */
    private val serviceReleases = MutableStateFlow<Map<Int, NextEpisodeRelease>>(emptyMap())

    /**
     * A read already on its way.
     *
     * Starting the app asks twice — once from `init` and once from the foreground observer, which
     * fires as the first Activity starts — and neither has finished by the time the other arrives,
     * so the day alone does not guard it. The second read is a cache hit rather than a second
     * request, but the repository still has to be reached to find that out.
     */
    private var upcomingJob: Job? = null

    /**
     * The time the filter last judged against, in epoch seconds.
     *
     * Whether an episode is out is a question about *now*, and nothing else about a hidden row changes
     * at the moment its episode becomes watchable — so without this the row would stay away until
     * some unrelated change happened to re-run the filter. It is moved on by the wake-up below at
     * exactly that moment, and on every return to the app.
     */
    private val clock = MutableStateFlow(nowSec())

    /**
     * Continue Watching as Home draws it: the device's own rows, less the ones with nothing left to
     * play until their next episode is out.
     *
     * Only Home. My List and the account sheet keep drawing the title, which is a record of what the
     * viewer is watching rather than a row of things to press play on.
     */
    val continueWatching = combine(
        LibraryStore.history,
        upcoming,
        SettingsStore.hideAiringTomorrowFromContinue,
        clock,
    ) { history, airing, hideUntilOut, now ->
        if (!hideUntilOut) {
            history
        } else {
            history.filterNot { hideFromContinueUntilAired(it, airing, now) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Each followed title's next episode to air, and when, for the Continue Watching cards.
     *
     * Derived from the schedule the row is already filtered by, so a card can say "EP 5 · IN 3 DAYS"
     * without a request of its own. Recomputed on the same clock the filter judges against, which
     * moves at midnight and whenever the app is returned to — the two moments "days left" can change.
     */
    val nextReleases: StateFlow<Map<Int, NextEpisodeRelease>> =
        combine(upcoming, clock, serviceReleases) { airing, now, service ->
            // The published schedule first; the list service only fills the titles it lacks, because
            // the row's own "step aside" rule reads the schedule and the two must agree.
            mergeNextReleases(nextReleasesByTitle(airing, now), service)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Read the upcoming schedule, once a day.
     *
     * Cheap to call on every return to Home: the day it already holds short-circuits, and the read
     * underneath is one or two published month files, shared with the Schedule tab's cache.
     */
    fun refreshContinueWatching(force: Boolean = false) {
        clock.value = nowSec()
        val today = LocalDate.now()
        // A viewer who has just pulled to refresh is asking for a live answer, so a read already on
        // its way is abandoned for theirs rather than left to answer with what it cached.
        if (force) upcomingJob?.cancel()
        if (!force && upcomingLoadedFor == today) return
        if (upcomingJob?.isActive == true) return
        upcomingJob = viewModelScope.launch {
            // From yesterday: an episode that aired late last night may not be watchable yet.
            val from = today.minusDays(1)
            val entries = runCatching { repo.upcomingAirings(from, UPCOMING_AIRING_DAYS, force = force) }
                .onFailure {
                    it.rethrowIfCancellation()
                    DiagnosticsLog.throwable("Continue Watching schedule read failed from=$from", it)
                }
                .getOrNull() ?: return@launch
            // Nothing is recorded until the read has landed, so a failure leaves the row judged
            // against whatever is already known rather than against an empty window that hides nothing.
            val byTitle = entries
                .mapNotNull { airing ->
                    val id = airing.media?.id ?: return@mapNotNull null
                    id to (airing.episode to airing.airingAt)
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, pairs) -> pairs.toMap() }
            upcomingLoadedFor = today
            clock.value = nowSec()
            upcoming.value = byTitle
            DiagnosticsLog.event(
                "Continue Watching read upcoming from=$from days=$UPCOMING_AIRING_DAYS titles=${byTitle.size} " +
                    "hidden=${LibraryStore.history.value.count { hideFromContinueUntilAired(it, byTitle) }}",
            )
        }
    }

    /**
     * Bumped on every completed load so in-flight paging can tell it belongs to a previous
     * catalog. Without it a Load More that started before a pull-to-refresh would finish
     * afterwards and republish the pre-refresh list it had captured.
     */
    private var catalogGeneration = 0

    private var revalidateJob: Job? = null

    /** Coming back to the app is "opening" it as far as the viewer can tell. */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            revalidate()
            // A day spent in the background is a day the schedule was read for the wrong date, and
            // the row has to be judged against the one the viewer came back to.
            refreshContinueWatching()
        }
    }

    init {
        load()
        refreshContinueWatching()
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        // The published schedule is built for planning and does not carry every airing, so a title
        // it misses would draw a card with no countdown at all. Those titles ask AniList, once, when
        // they first appear on the row — never per render, and never for a title the schedule
        // already answers.
        viewModelScope.launch {
            combine(
                LibraryStore.history,
                upcoming,
            ) { rows, airing -> rows.map(HistoryEntry::anilistId).distinct() to airing }
                .distinctUntilChanged()
                .collectLatest { (ids, airing) ->
                    val answered = nextReleasesByTitle(airing, nowSec()).keys
                    val unknown = ids.filterNot { it in answered || it in serviceReleases.value }
                    if (unknown.isEmpty()) return@collectLatest
                    val found = repo.nextAiringReleases(unknown)
                    if (found.isNotEmpty()) serviceReleases.value = serviceReleases.value + found
                }
        }
        // Wake when the first hidden row's episode becomes watchable, and judge again then. Restarted
        // whenever anything it depends on changes, including its own wake-up, which arms the next.
        viewModelScope.launch {
            combine(
                LibraryStore.history,
                upcoming,
                SettingsStore.hideAiringTomorrowFromContinue,
                clock,
            ) { history, airing, hideUntilOut, now ->
                if (hideUntilOut) history.mapNotNull { returnsToContinueAt(it, airing, now) }.minOrNull() else null
            }.collectLatest { returnsAt ->
                returnsAt ?: return@collectLatest
                delay(((returnsAt - nowSec()) * 1000).coerceAtLeast(1_000L))
                clock.value = nowSec()
            }
        }
        // The one thing coming back to the app cannot cover: a device left open across midnight,
        // which is when the fortnight the schedule covers has to move on by a day. Waiting for the
        // boundary rather than polling for it is one wake-up a day.
        viewModelScope.launch {
            while (true) {
                delay(millisUntilNextDay())
                refreshContinueWatching()
            }
        }
        // The account's list lands after Home on a fresh sign-in, and changes as the viewer rates
        // things elsewhere; either way the rows were computed from the old list.
        viewModelScope.launch {
            TasteStore.ratings.drop(1).collect { if (_state.value is UiState.Success) loadPersonal(force = true) }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
    }

    fun load(force: Boolean = false) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            DiagnosticsLog.event("Home load start force=$force")
            val startedAtMs = SystemClock.elapsedRealtime()
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                // The timeout covers the network attempt only. The offline fallback is asked for
                // afterwards, outside it, because reading the bundled catalogue is slow enough on a
                // cold start to consume the whole window on its own.
                val remote = runCatching {
                    withTimeoutOrNull(HOME_LOAD_TIMEOUT_MS) { repo.homeCollections(force) }
                }
                remote.exceptionOrNull()?.rethrowIfCancellation()
                val collections = remote.getOrNull()
                    ?: repo.offlineHomeCollections()?.also {
                        DiagnosticsLog.event(
                            "Home served from bundled catalogue: " +
                                (remote.exceptionOrNull()?.message ?: "request timed out"),
                        )
                    }
                    ?: remote.exceptionOrNull()?.let { throw it }
                    ?: error("Home request timed out. Check the connection and try again.")
                val data = collections.toHomeData()
                catalogGeneration++
                paging.values.forEach { it.page = 1; it.hasMore = true }
                _exhausted.value = emptySet()
                _loadingMore.value = emptySet()
                DiagnosticsLog.event(
                    "Home load success spotlight=${data.spotlight.size} newest=${data.newest.size} " +
                        "popular=${data.popular.size} movies=${data.movies.size} topRated=${data.topRated.size} " +
                        "upcoming=${data.upcoming.size}",
                )
                _state.value = UiState.Success(data)
                // What was just drawn is usually the cached copy — up to a week old, since stale
                // rows are served while a refresh runs out of sight and that refresh never reaches
                // the screen. Replace it with the live rows as soon as they arrive.
                if (!force) launchRevalidation()
                // After the catalogue rails, never before: they are what the screen is waiting on.
                loadPersonal(force = force)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // No separate DiagnosticsLog call: the report is written by toFailure, under the
                // code the viewer is looking at, which is the only way the two cannot disagree.
                _state.value = UiState.Error(
                    e.toFailure(AnililiError.HOME_LOAD_FAILED, attributes = mapOf("forced" to force)),
                )
            } finally {
                if (generation == loadGeneration) {
                    // A cached or fast refresh can finish in a couple of hundred milliseconds, and
                    // the spinner appears and vanishes inside one blink — which reads as "nothing
                    // happened" rather than "already up to date". Hold it long enough to be seen.
                    val shown = SystemClock.elapsedRealtime() - startedAtMs
                    if (_isRefreshing.value && shown < MIN_REFRESH_INDICATOR_MS) {
                        withContext(NonCancellable) { delay(MIN_REFRESH_INDICATOR_MS - shown) }
                    }
                    _isRefreshing.value = false
                }
                // Settled either way. Optional startup work waits on this rather than a fixed
                // delay, so it cannot land on top of the first screen still fetching.
                com.anilili.ui.FirstContent.mark()
            }
        }
    }

    /**
     * Fetch the next page of one row and append it.
     *
     * Safe to call on every scroll frame: an in-flight page and a row AniList has already declared
     * finished both return immediately. Television relies on that — its rails ask for more from a
     * scroll listener rather than from a button, so the guard is what keeps one long press on Right
     * from queuing a page per frame.
     */
    fun loadMore(section: HomeSection) {
        val cursor = paging.getValue(section)
        if (cursor.loading || !cursor.hasMore) return
        if (_state.value !is UiState.Success) return
        cursor.loading = true
        _loadingMore.value = _loadingMore.value + section

        viewModelScope.launch {
            val generation = catalogGeneration
            try {
                val nextPage = cursor.page + 1
                val page = when (section) {
                    HomeSection.POPULAR -> repo.popular(nextPage)
                    HomeSection.NEWEST -> repo.recentlyReleased(nextPage)
                    HomeSection.TRENDING -> repo.trending(nextPage)
                    HomeSection.TOP_RATED -> repo.topRated(nextPage)
                    HomeSection.MOVIES -> repo.movies(nextPage)
                    HomeSection.UPCOMING -> repo.upcoming(nextPage)
                }
                // A refresh landed while this page was in flight; its results belong to a catalog
                // that no longer exists, and appending them would resurrect the old list.
                if (generation != catalogGeneration) return@launch
                val current = (_state.value as? UiState.Success)?.data ?: return@launch

                cursor.page = nextPage
                // AniList tells us directly when a section runs out — no need to guess from an
                // empty page, and no need to leave a button that can no longer do anything.
                cursor.hasMore = page.hasNextPage && page.items.isNotEmpty()
                if (!cursor.hasMore) _exhausted.value = _exhausted.value + section

                if (page.items.isNotEmpty()) {
                    val updatedList = (current.section(section) + page.items).distinctBy { it.id }
                    _state.value = UiState.Success(current.withSection(section, updatedList))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // The page stays un-advanced, so the row remains live for another attempt.
                DiagnosticsLog.throwable("Home load more failed section=${section.name}", e)
            } finally {
                cursor.loading = false
                _loadingMore.value = _loadingMore.value - section
            }
        }
    }

    /**
     * A pull-to-refresh is the viewer asking for everything on this screen to be current, and the
     * Continue Watching row is judged against a schedule that moves: a broadcaster who reschedules
     * an episode tonight should not need the app restarted to be believed.
     */
    fun refresh() {
        load(force = true)
        refreshContinueWatching(force = true)
    }

    /**
     * Swap the rows on screen for live ones, silently: no spinner, and a failure keeps what is
     * shown. Rows the viewer has paged further into are left alone — replacing them would throw
     * away those pages, and on a television the focused card with them.
     */
    fun revalidate() {
        if (loadJob?.isActive == true) return // it revalidates when it lands
        launchRevalidation()
        loadPersonal()
    }

    /**
     * Recompute the personal rows. Failure leaves whatever rows are showing: they are a bonus on
     * top of Home, and an AniList hiccup should not make them blink out.
     */
    fun loadPersonal(force: Boolean = false) {
        if (personalJob?.isActive == true) return
        if (!force && SystemClock.elapsedRealtime() - personalAt < PERSONAL_REFRESH_AFTER_MS && personalAt != 0L) return
        personalJob = viewModelScope.launch {
            val rows = runCatching {
                val because = Recommendations.becauseYouWatched()
                // A title the "Because" row already offers is not repeated in "For you": the
                // favourite's neighbours score highly in both, and on the TV they sat one above the
                // other (Delicious in Dungeon in each).
                val inBecause = because?.items?.mapTo(HashSet()) { it.id }.orEmpty()
                PersonalRows(
                    forYou = Recommendations.forYou()?.items.orEmpty().filterNot { it.id in inBecause },
                    because = because,
                )
            }.onFailure {
                it.rethrowIfCancellation()
                DiagnosticsLog.throwable("Home personal rows failed", it)
            }.getOrNull() ?: return@launch
            personalAt = SystemClock.elapsedRealtime()
            DiagnosticsLog.event(
                "Home personal rows forYou=${rows.forYou.size} because=${rows.because?.seed?.id}:${rows.because?.items?.size ?: 0}",
            )
            _personal.value = rows
        }
    }

    private fun launchRevalidation() {
        if (revalidateJob?.isActive == true) return
        if (_state.value !is UiState.Success) return
        revalidateJob = viewModelScope.launch {
            val generation = loadGeneration
            val live = runCatching {
                withTimeoutOrNull(HOME_LOAD_TIMEOUT_MS) {
                    repo.homeCollections(maxAgeMs = HOME_REVALIDATE_AFTER_MS)
                }
            }.onFailure(Throwable::rethrowIfCancellation).getOrNull() ?: return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            // A load or refresh started meanwhile owns the screen now.
            if (generation != loadGeneration) return@launch
            if (paging.values.any { it.page > 1 || it.loading }) return@launch
            val data = live.toHomeData()
            if (data == current) return@launch
            catalogGeneration++
            paging.values.forEach { it.page = 1; it.hasMore = true }
            _exhausted.value = emptySet()
            DiagnosticsLog.event(
                "Home revalidated newest=${data.newest.size} " +
                    "newestChanged=${data.newest.map(Media::id) != current.newest.map(Media::id)}",
            )
            _state.value = UiState.Success(data)
        }
    }
}

private fun HomeCollections.toHomeData() = HomeData(
    spotlight = spotlight,
    newest = newest,
    popular = popular,
    movies = movies,
    topRated = topRated,
    upcoming = upcoming,
)

/**
 * How long until the device's next midnight.
 *
 * Resolved through the calendar rather than as "24 hours minus the time", because a day is not
 * always 24 hours: a clock change would otherwise wake the schedule an hour early or an hour late,
 * and the answer here decides which day the schedule window starts from. Never zero, so a device whose clock is
 * already a day ahead cannot spin.
 */
private fun millisUntilNextDay(now: Long = System.currentTimeMillis()): Long {
    val zone = java.time.ZoneId.systemDefault()
    val midnight = java.time.Instant.ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
    return (midnight - now).coerceAtLeast(1_000L)
}

private fun nowSec(): Long = System.currentTimeMillis() / 1000

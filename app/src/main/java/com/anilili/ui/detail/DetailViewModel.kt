package com.anilili.ui.detail

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.DubIndex
import com.anilili.data.AppGraph
import com.anilili.data.ProviderCatalog
import com.anilili.data.SERIES_AIRING_ORDER
import com.anilili.data.seasonNeighbors
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.remote.TvdbRef
import com.anilili.data.model.episodeAirDate
import java.time.LocalDate
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.UiState
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.anilili.data.model.EPISODE_WATCHABLE_AFTER_SEC
import com.anilili.data.model.NextAiringEpisode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Long enough for a refresh indicator to register as an answer rather than a flicker. */
private const val MIN_REFRESH_INDICATOR_MS = 550L

/**
 * An airing title's record fetched longer ago than this is asked for again once the page is up, so
 * an episode that aired since is listed on opening rather than a day later. A younger record is
 * kept, so revisiting a title does not re-spend a rate-limited AniList request each time; an
 * episode whose air time passes inside that window is still caught by the cache's own `outdated`
 * check on the record.
 */
private const val DETAIL_REVALIDATE_AFTER_MS = 2L * 60 * 60 * 1000

data class DetailData(
    val info: Media,
    /** Provider-independent episode catalog derived from cached/AniList anime metadata. */
    val episodes: List<EpisodeItem>,
    /** Playback preference only; server and audio controls belong to the watch screen. */
    val preferredCategory: Category,
    val series: List<Media> = listOf(info),
    val seriesLoading: Boolean = true,
    /** AniList's community picks for this title, shown under the series entries. */
    val recommendations: List<Media> = emptyList(),
    val recommendationsLoading: Boolean = true,
    /** Season whose episodes the Episodes tab currently shows; defaults to this page's anime. */
    val selectedSeasonId: Int = info.id,
    val seasonEpisodesLoading: Boolean = false,
    /**
     * The selected season's own typography, once the metadata tree has answered for it — or,
     * where the tree has none for that season, the series' logo from another of its seasons.
     *
     * Follows the season rather than the page, because a series may set each season's name in its
     * own lockup and a header showing the first season's logo over the fourth season's episodes is
     * the same mismatch the pane was already fixed for. Null while the lookup is in flight and null
     * forever for the titles no season of has artwork, so every reader needs the title to fall
     * back to.
     */
    val logo: String? = null,
    /**
     * The logo for the page's own season — its own, or the series' borrowed from a sibling — kept
     * whichever season chip is selected.
     *
     * The phone's header always describes the page's own entry (its title, its cover, its list
     * button), so it cannot take [logo], which follows the selection: it used to hide the logo the
     * moment any other chip was picked and set the title in type instead, where the television,
     * whose pane follows the selection, kept a logo. This is the page's logo held apart from the
     * selection, so the phone keeps one too without putting another season's lockup beside its
     * own cover.
     */
    val pageLogo: String? = null,
) {
    // The derived lists below are computed once per instance, on first read, and held. They were
    // getters, and the television page reads them on every recomposition — once per D-pad step
    // along the episode strip — rebuilding the season chain, its labels (through the bundled
    // cross-reference's lock) and the watch order each time, and handing the rows below a new list
    // every time so none of them could skip. A DetailData is immutable apart from these caches, and
    // `copy` makes a new instance with empty ones, so a value never outlives its inputs. Body
    // properties, so equality and `copy` are exactly what they were.

    /**
     * Episodic entries of the prequel/sequel chain, in airing order — one detail page serves the
     * whole series and the Episodes tab filters between its seasons. Movies and specials stay on
     * the Related tab.
     */
    val seasons: List<Media> by lazy { seasonChain(info, series) }

    /**
     * The season the page is describing. The season chips swap it, so everything season-scoped —
     * the episode rows, the watch action, the library entry and, on television, the whole info
     * pane — reads it rather than [info], which stays the anime the page was opened on.
     */
    val selectedSeason: Media
        get() = seasons.firstOrNull { it.id == selectedSeasonId } ?: info

    /** What each of [seasons] is called in a picker, by id; see [seasonLabels]. */
    val seasonLabels: Map<Int, String> by lazy { seasonLabels(seasons, ::bundledTvdbRef) }

    /** [watchOrder] with the adult setting it was built under; rebuilt when that setting changes. */
    @Volatile private var watchOrderMemo: Pair<Boolean, List<WatchOrderEntry>>? = null

    /** [suggestions] with the [watchOrder] instance it was filtered against. */
    @Volatile private var suggestionsMemo: Pair<List<WatchOrderEntry>, List<Media>>? = null

    /**
     * The franchise as a running order — seasons, films, OVAs and specials — for the Related tab.
     *
     * Derived rather than fetched: everything it needs is already on the records the page holds.
     * See [buildWatchOrder] for why the series walk alone cannot answer this.
     *
     * Held per value of the adult-content setting rather than once, because the getter it replaced
     * read that setting on every call and a page held across a change to it must follow it.
     */
    val watchOrder: List<WatchOrderEntry>
        get() {
            val hideAdult = SettingsStore.hideAdultContent.value
            watchOrderMemo?.let { (builtFor, order) -> if (builtFor == hideAdult) return order }
            return buildWatchOrder(
                info = info,
                series = series,
                seasons = seasons,
                hideAdult = hideAdult,
            ).also { watchOrderMemo = hideAdult to it }
        }

    /**
     * Recommendations worth listing separately — a season of this very series is already on the
     * tab under Related, and repeating it would also hand the lazy list a duplicate key.
     */
    val suggestions: List<Media>
        get() {
            val order = watchOrder
            suggestionsMemo?.let { (builtFrom, picks) -> if (builtFrom === order) return picks }
            val franchise = order.mapTo(mutableSetOf()) { it.media.id }
            return recommendations.filter { it.id != info.id && it.id !in franchise }
                .also { suggestionsMemo = order to it }
        }
}

private val SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")

/**
 * Episodic entries of [series] that count as numbered seasons of [info], in season order.
 *
 * Shared with the player, which uses it to find the season after the one that just ended: the
 * season it moves on to has to be the one the anime page's chips call the next season, or the
 * binge lands on a spin-off the page never listed.
 */
internal fun seasonChain(info: Media, series: List<Media>): List<Media> =
    tvdbSeasonChain(info, series, ::bundledTvdbRef)
        ?: series.filter { it.id == info.id || it.format in SEASON_FORMATS }

/** [seasonChain]'s picker names ("Season 2", "Part 2"), by id. */
internal fun seasonChainLabels(seasons: List<Media>): Map<Int, String> =
    seasonLabels(seasons, ::bundledTvdbRef)

/**
 * A season that has not started airing. The pickers keep it — knowing another season is coming is
 * worth a chip — but grey it out and refuse to switch to it, because there is nothing behind it to
 * watch: Aware! Meisaku-kun's page offered "Season 3 · 2027" as an equal to the two that aired,
 * and choosing it led to an empty episode list.
 *
 * Status rather than date, because AniList often knows only the year of an announced season, and
 * NOT_YET_RELEASED flips the day the first episode airs, which is exactly when it becomes worth
 * opening.
 */
internal fun Media.isUpcoming(): Boolean = status == "NOT_YET_RELEASED"

/**
 * Whether a season's chip can be chosen. An upcoming season stays choosable only when it is the one
 * on show — the page was opened on it — because a greyed-out chip for the season the page is about
 * would describe nothing else on screen.
 */
internal fun seasonChoosable(season: Media, selectedSeasonId: Int): Boolean =
    !season.isUpcoming() || season.id == selectedSeasonId

/**
 * The bundled cross-reference's placement of a title, or null when there is no graph to ask.
 *
 * [DetailData] is a plain value that tests build directly, so it cannot assume the object graph
 * exists — reading `AppGraph.repository` from a getter threw `UninitializedPropertyAccessException`
 * in four of them. No graph means no cross-reference, which is the same answer as an unmapped
 * title, so the chain falls back to the relation list exactly as it does on a device.
 */
private fun bundledTvdbRef(anilistId: Int): TvdbRef? =
    if (AppGraph.isReady) AppGraph.repository.tvdbRefFor(anilistId) else null

/**
 * The relation chain reduced to the entries TheTVDB files as numbered seasons of the same series,
 * in season order — or null when the cross-reference cannot place this title.
 *
 * AniList has no season concept. "Season two" is a separate entry joined by a PREQUEL/SEQUEL
 * relation, and that chain also drags in spin-offs, recaps, shorts and side stories with nothing to
 * tell them apart by except `format` — which is why the fallback is a format filter, and why it
 * keeps a spin-off that happens to be a TV series while dropping a season released as an ONA.
 * TheTVDB groups a series the way viewers talk about it, so its numbering answers both questions
 * the format filter only guesses at: which entries belong, and what order they go in.
 *
 * Entries TheTVDB files outside the numbered run — films, OVAs, specials, season 0 — are left out
 * and stay on the Related tab, which is where the format filter put them too.
 *
 * Null rather than an empty list when the title is unmapped: about a fifth of the catalogue is, and
 * an empty chain would read as "this has no seasons" and hide the picker entirely.
 */
internal fun tvdbSeasonChain(
    info: Media,
    series: List<Media>,
    ref: (Int) -> TvdbRef?,
): List<Media>? {
    val anchor = ref(info.id) ?: return null
    if (anchor.season == null) return null
    val placed = series.mapNotNull { media ->
        val entry = ref(media.id) ?: return@mapNotNull null
        if (entry.id != anchor.id || entry.season == null || entry.season < 1) {
            null
        } else {
            entry.season to media
        }
    }
    // The page's own entry must be in its own chain. If the cross-reference disagrees with the
    // relation chain about that, trust neither and fall back rather than render a picker that
    // cannot select the season being shown.
    if (placed.none { (_, media) -> media.id == info.id }) return null
    return placed
        .sortedBy { (season, _) -> season }
        .map { (_, media) -> media }
        .distinctBy { it.id }
        .takeIf { it.size > 1 }
}

class DetailViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val catalogs = mutableMapOf<Int, EpisodesResult>()
    private val catalogMutex = Mutex()

    private val _state = MutableStateFlow<UiState<DetailData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private var loadedId: Int? = null
    private var airingWatch: Job? = null

    fun load(id: Int, force: Boolean = false) {
        if (!force && loadedId == id && _state.value is UiState.Success) return
        loadedId = id
        viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            if (force && _state.value is UiState.Success) {
                _isRefreshing.value = true
            } else {
                _state.value = UiState.Loading
            }
            try {
                // animeInfo is cache-first and fetches AniList only when local metadata is absent
                // or stale. The episode tab therefore follows the same source of truth as Home.
                SettingsStore.awaitLoaded()
                val info = repo.animeInfo(id, force = force) ?: error("Anime not found")
                // Seasons show instantly: the anime's own relations (already part of the cached
                // info) seed the list with its direct prequel/sequel neighbors, and the full
                // walked chain replaces it below once animeSeries returns (cached per member,
                // so revisits of any season resolve it immediately too).
                val seededSeries = (listOf(info) + info.seasonNeighbors())
                    .distinctBy(Media::id)
                    .sortedWith(SERIES_AIRING_ORDER)
                val initial = DetailData(
                    info = info,
                    episodes = anilistEpisodeCatalog(info),
                    preferredCategory = if (SettingsStore.preferDub.value) Category.DUB else Category.SUB,
                    series = seededSeries,
                    seasonEpisodesLoading = info.episodeCountUnknown(),
                )
                _state.value = UiState.Success(initial)
                if (!force && !repo.isSettled(info)) launchRevalidation(info)
                launchEpisodeEnrichment(info.id, info.idMal)
                launchUncountedEpisodes(info)
                launchRecommendations(info.id)
                launchLogo(info.id)

                val series = runCatching { repo.animeSeries(info) }.getOrDefault(seededSeries)
                if (loadedId == id) {
                    val current = (_state.value as? UiState.Success)?.data ?: initial
                    // The walk started from the record first drawn; if a revalidation has replaced
                    // that since, keep the live one in the chain the season pane reads from.
                    val walked = series.map { if (it.id == current.info.id) current.info else it }
                    _state.value = UiState.Success(current.copy(series = walked, seriesLoading = false))
                    // The first lookup ran against the season's neighbours alone; a series logo
                    // held only by a season further along is reachable now.
                    if (current.logo == null) launchLogo(current.selectedSeasonId)
                    // And the page's own, if a chip had moved the selection off it by then.
                    if (current.pageLogo == null && current.selectedSeasonId != info.id) launchLogo(info.id)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(
                    e.toFailure(AnililiError.TITLE_LOAD_FAILED, attributes = mapOf("anilistId" to id)),
                )
            } finally {
                // A page whose info is already cached comes back in a couple of hundred
                // milliseconds, and the indicator appears and vanishes inside one blink — which
                // reads as "nothing happened" rather than "already up to date". The television has
                // nothing else to show for the press at all: there is no gesture under a thumb to
                // acknowledge it, only this. Held exactly as Home holds its own.
                val shown = SystemClock.elapsedRealtime() - startedAtMs
                if (_isRefreshing.value && shown < MIN_REFRESH_INDICATOR_MS) {
                    withContext(NonCancellable) { delay(MIN_REFRESH_INDICATOR_MS - shown) }
                }
                _isRefreshing.value = false
            }
        }
    }

    fun refresh(id: Int) = load(id, force = true)

    /**
     * Replaces the cached record [shown] was drawn from with AniList's live one, silently: no
     * indicator, and a failure keeps the page as it is.
     *
     * The record is cached for a day and served for a week past that, so without this an episode
     * that aired since the last visit was missing from the list until the page was pulled or held
     * to reload. Episodes already listed are kept as they are — the titles and stills merged onto
     * them — and only the newly aired ones are appended, then enriched like the rest.
     */
    private fun launchRevalidation(shown: Media) {
        val id = shown.id
        viewModelScope.launch {
            val live = runCatching { repo.animeInfo(id, maxAgeMs = DETAIL_REVALIDATE_AFTER_MS) }
                .onFailure(Throwable::rethrowIfCancellation)
                .getOrNull() ?: return@launch
            if (live == shown || loadedId != id) return@launch
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            if (data.info.id != id) return@launch
            val known = data.episodes.maxOfOrNull(EpisodeItem::number) ?: 0.0
            val aired = anilistEpisodeCatalog(live).filter { it.number > known }
            val onShow = data.selectedSeasonId == id
            DiagnosticsLog.event("Detail revalidated anilistId=$id newEpisodes=${aired.size} onShow=$onShow")
            _state.value = UiState.Success(
                data.copy(
                    info = live,
                    series = data.series.map { if (it.id == id) live else it },
                    episodes = if (onShow) data.episodes + aired else data.episodes,
                ),
            )
            if (onShow && aired.isNotEmpty()) launchEpisodeEnrichment(id, live.idMal)
        }
    }

    /**
     * The stream providers' own episode listing for a season, which is what source resolution
     * needs and what [DetailData.episodes] deliberately is not — those rows are synthesised from
     * AniList counts and carry no provider ids at all.
     *
     * Held per season behind a lock: downloading a run from the list is one tap per episode, and
     * without it every tap races another two-provider merge for a listing already in hand.
     */
    suspend fun providerCatalog(seasonId: Int): EpisodesResult = catalogMutex.withLock {
        catalogs.getOrPut(seasonId) { repo.episodes(seasonId) }
    }

    /** Swaps the Episodes tab to another season of the same series without leaving this page. */
    fun selectSeason(seasonId: Int) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (current.selectedSeasonId == seasonId) return
        if (seasonId == current.info.id) {
            _state.value = UiState.Success(
                current.copy(
                    selectedSeasonId = seasonId,
                    episodes = anilistEpisodeCatalog(current.info),
                    seasonEpisodesLoading = current.info.episodeCountUnknown(),
                    // Dropped with the episodes it belonged to: the outgoing season's name must
                    // not sit over the incoming season's rows for the length of a lookup.
                    logo = null,
                ),
            )
            launchEpisodeEnrichment(seasonId, current.info.idMal)
            launchUncountedEpisodes(current.info)
            launchLogo(seasonId)
            return
        }
        _state.value = UiState.Success(
            current.copy(
                selectedSeasonId = seasonId,
                episodes = emptyList(),
                seasonEpisodesLoading = true,
                logo = null,
            ),
        )
        viewModelScope.launch {
            // animeInfo is cache-first; season entries discovered by animeSeries are usually
            // already cached, so this rarely hits the network.
            val fetched = runCatching { repo.animeInfo(seasonId) }.getOrNull()
            val seasonInfo = fetched ?: current.series.firstOrNull { it.id == seasonId }
            val episodes = seasonInfo?.let(::anilistEpisodeCatalog).orEmpty()
            val latest = (_state.value as? UiState.Success)?.data ?: return@launch
            if (latest.selectedSeasonId == seasonId) {
                _state.value = UiState.Success(
                    latest.copy(
                        episodes = episodes,
                        seasonEpisodesLoading = seasonInfo?.episodeCountUnknown() == true,
                        // The full record replaces whatever the chain held for this season. A
                        // seeded chain comes from the anime's relation edges, and a relation node
                        // carries no description — so a page describing that season had nothing to
                        // print under its title until the series walk happened to land.
                        series = if (fetched == null) {
                            latest.series
                        } else {
                            latest.series.map { if (it.id == seasonId) fetched else it }
                        },
                    ),
                )
                launchEpisodeEnrichment(seasonId, seasonInfo?.idMal)
                seasonInfo?.let(::launchUncountedEpisodes)
                launchLogo(seasonId)
            }
        }
    }

    /**
     * Lists a season AniList cannot count (see [episodeCountUnknown]) from the stream providers'
     * own listing instead — the same [providerCatalog] playback asks first, so the lookup is held
     * for the episode tapped rather than spent twice.
     *
     * Rows the metadata pass already supplied are kept: a title the tree knows has its stills and
     * names there, and the providers' numbers would only replace them with bare ones.
     */
    private fun launchUncountedEpisodes(season: Media) {
        if (!season.episodeCountUnknown()) return
        val seasonId = season.id
        viewModelScope.launch {
            val catalog = runCatching { providerCatalog(seasonId) }
                .onFailure(Throwable::rethrowIfCancellation)
                .onFailure { DiagnosticsLog.throwable("Uncounted season catalogue failed id=$seasonId", it) }
                .getOrNull()
            val rows = catalog?.let { providerEpisodeCatalog(seasonId, it, adult = season.isAdult) }.orEmpty()
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            if (data.selectedSeasonId != seasonId) return@launch
            DiagnosticsLog.event(
                "Uncounted season listed from providers id=$seasonId rows=${rows.size} " +
                    "providers=${catalog?.providers?.count { it.sub.isNotEmpty() || it.dub.isNotEmpty() } ?: 0}",
            )
            _state.value = UiState.Success(
                data.copy(
                    episodes = data.episodes.ifEmpty { rows },
                    seasonEpisodesLoading = false,
                ),
            )
        }
    }

    /**
     * Fetches the season's typography and applies it only if that season is still the one on show.
     *
     * Guarded like the other late arrivals here: the chips can move the page on twice before a
     * lookup returns, and a logo that landed on whatever season was selected by then would put the
     * wrong name across the header — louder than any of the other stale-arrival bugs, because the
     * header is the one thing on the page a viewer reads from across the room.
     */
    private fun launchLogo(seasonId: Int) {
        viewModelScope.launch {
            val logo = repo.seriesLogo(seasonId) ?: siblingSeasonLogo(seasonId)
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            // The page's own season's answer is kept whatever is selected by now; see pageLogo.
            val pageLogo = if (seasonId == data.info.id && logo != null) logo else data.pageLogo
            val selectedLogo = if (data.selectedSeasonId == seasonId) logo else data.logo
            if (pageLogo == data.pageLogo && selectedLogo == data.logo) return@launch
            _state.value = UiState.Success(data.copy(logo = selectedLogo, pageLogo = pageLogo))
        }
    }

    /**
     * The logo another season of the same series carries, for a season the metadata tree has none
     * for.
     *
     * The tree is filled per AniList entry, and its gaps fall on individual cours: Mushoku Tensei's
     * second season and its Part 2 have no logo while its first and third do, and they are the same
     * TMDB series logo in every case measured. Without this the header set the series in type for
     * exactly those seasons. The first season in running order that has one is asked for, so the
     * answer does not depend on which season the page happened to be opened on.
     */
    private suspend fun siblingSeasonLogo(seasonId: Int): String? {
        val seasons = (_state.value as? UiState.Success)?.data?.seasons ?: return null
        return seasons.asSequence()
            .filter { it.id != seasonId }
            .firstNotNullOfOrNull { repo.seriesLogo(it.id) }
    }

    /**
     * Loads the community recommendations alongside the series walk. The repository swallows its
     * own failures, so the tab simply stops waiting rather than reporting an error.
     */
    private fun launchRecommendations(id: Int) {
        viewModelScope.launch {
            val recommendations = repo.animeRecommendations(id)
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            if (data.info.id != id) return@launch
            _state.value = UiState.Success(
                data.copy(recommendations = recommendations, recommendationsLoading = false),
            )
        }
    }

    /**
     * Overlays Konoha titles/stills and MAL filler flags onto the count-based catalog. Fetching
     * them together avoids a race when AniList has no episode count and Konoha has to create the
     * rows before filler flags can be applied.
     */
    private fun launchEpisodeEnrichment(seasonId: Int, malId: Int?) {
        launchRecentAirings(seasonId)
        launchAiringWatch(seasonId)
        viewModelScope.launch {
            val metadataRequest = async {
                runCatching { repo.episodeMetadata(seasonId) }
                    .onFailure(Throwable::rethrowIfCancellation)
                    .getOrDefault(emptyList())
            }
            val fillerRequest = async {
                withTimeoutOrNull(DETAIL_FILLER_TIMEOUT_MS) {
                    runCatching { repo.fillerEpisodes(seasonId, malId) }
                        .onFailure(Throwable::rethrowIfCancellation)
                        .getOrDefault(emptySet())
                }.orEmpty()
            }
            val meta = metadataRequest.await()
            val fillers = fillerRequest.await()
            val dubbedThrough = DubIndex.dubbedThrough(seasonId)
            if (meta.isEmpty() && fillers.isEmpty() && dubbedThrough == null) return@launch
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            if (data.selectedSeasonId != seasonId) return@launch
            // With no rows to overlay, the catalogue's answer becomes the list — and for a season
            // not yet airing that answer is the series' past episodes: The Apothecary Diaries'
            // Season 3 chip listed all 36 episodes of Seasons 1 and 2.
            if (data.selectedSeason.isUpcoming()) return@launch
            val withMetadata = mergeEpisodeMetadata(data.episodes, meta, seasonId)
            _state.value = UiState.Success(
                data.copy(
                    episodes = markDubbedEpisodes(mergeEpisodeFillerMarks(withMetadata, fillers), dubbedThrough),
                ),
            )
        }
    }

    /**
     * Stamps the season's just-aired episodes with their airing time, so they stay greyed out until
     * they are [EPISODE_WATCHABLE_AFTER_SEC] old (see [episodeIsUnaired]), then clears the stamps
     * when that passes — the page is often left open across it, and a row that only unlocked on
     * the next visit would be the same complaint the other way round.
     */
    private fun launchRecentAirings(seasonId: Int) {
        viewModelScope.launch {
            val airings = runCatching { repo.recentAirings(seasonId) }
                .onFailure(Throwable::rethrowIfCancellation)
                .getOrDefault(emptyMap())
            if (airings.isEmpty()) return@launch
            applyAirings(seasonId, airings)
            val opensAt = airings.values.max() + EPISODE_WATCHABLE_AFTER_SEC
            delay(((opensAt - System.currentTimeMillis() / 1000) * 1000).coerceAtLeast(0) + 1_000)
            applyAirings(seasonId, emptyMap())
        }
    }

    /**
     * Lists the season's next episode the moment it airs, for a page left open across it.
     *
     * The television counts down to that episode in the slot it will take (see the strip's upcoming
     * card), and when the clock ran out nothing replaced it: the list is counted from AniList's
     * schedule, the record behind it was only refetched on the next visit, so a viewer watching
     * the clock saw it stop at zero and the episode never arrive. So at the airing time the row is
     * added here — stamped with its airing time, which keeps it locked and counting for
     * [EPISODE_WATCHABLE_AFTER_SEC] while the servers post it (see [episodeIsUnaired]) — and the
     * stamp is cleared when that passes, the same unlock [launchRecentAirings] does.
     *
     * The schedule is moved on to the following episode with its time unknown, which removes the
     * countdown card without claiming the rows after it have aired. The record is then asked for
     * once AniList has had time to move its own schedule on, and the next countdown appears from
     * that. One request per airing, on a page that is open at the time — nothing for a closed one.
     */
    private fun launchAiringWatch(seasonId: Int) {
        airingWatch?.cancel()
        val season = (_state.value as? UiState.Success)?.data?.selectedSeason ?: return
        if (season.id != seasonId) return
        val number = season.nextAiringEpisode?.episode ?: return
        val airingAt = season.nextAiringEpisode.airingAt ?: return
        airingWatch = viewModelScope.launch {
            delay(((airingAt - System.currentTimeMillis() / 1000) * 1000).coerceAtLeast(0))
            updateSeason(seasonId) { data, current ->
                if (current.nextAiringEpisode?.episode != number) return@updateSeason null
                val stamped = if (data.episodes.any { it.number == number.toDouble() }) {
                    data.episodes.map { if (it.number == number.toDouble()) it.copy(airingAt = airingAt) else it }
                } else {
                    data.episodes + EpisodeItem(
                        pipeId = anilistEpisodeId(seasonId, number.toDouble()),
                        number = number.toDouble(),
                        title = null,
                        image = null,
                        filler = false,
                        airingAt = airingAt,
                    )
                }
                current.copy(nextAiringEpisode = NextAiringEpisode(episode = number + 1)) to stamped
            }
            DiagnosticsLog.event("Detail episode aired anilistId=$seasonId episode=$number")
            // Beside the unlock rather than ahead of it: a page opened in the last minutes of the
            // wait would otherwise hold the episode locked until the request had come back.
            val schedule = launch {
                delay(AIRED_SCHEDULE_SETTLE_MS)
                val live = runCatching { repo.animeInfo(seasonId) }
                    .onFailure(Throwable::rethrowIfCancellation)
                    .getOrNull()
                if (live != null && (live.nextAiringEpisode?.episode ?: Int.MAX_VALUE) > number) {
                    updateSeason(seasonId) { data, _ -> live to data.episodes }
                }
            }
            val opensAt = airingAt + EPISODE_WATCHABLE_AFTER_SEC
            delay(((opensAt - System.currentTimeMillis() / 1000) * 1000).coerceAtLeast(0) + 1_000)
            updateSeason(seasonId) { data, current ->
                current to data.episodes.map {
                    if (it.number == number.toDouble() && it.airingAt == airingAt) it.copy(airingAt = null) else it
                }
            }
            // The following episode's countdown, once the record names it.
            schedule.join()
            launchAiringWatch(seasonId)
        }
    }

    /**
     * Replaces the season [seasonId]'s record and the rows on show, when that season is still the
     * one selected. [change] answers null to leave the page as it is.
     */
    private fun updateSeason(
        seasonId: Int,
        change: (DetailData, Media) -> Pair<Media, List<EpisodeItem>>?,
    ) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.selectedSeasonId != seasonId) return
        val (season, episodes) = change(data, data.selectedSeason) ?: return
        _state.value = UiState.Success(
            data.copy(
                info = if (data.info.id == seasonId) season else data.info,
                series = data.series.map { if (it.id == seasonId) season else it },
                episodes = episodes,
            ),
        )
    }

    private fun applyAirings(seasonId: Int, airings: Map<Int, Long>) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.selectedSeasonId != seasonId) return
        _state.value = UiState.Success(
            data.copy(
                episodes = data.episodes.map { episode ->
                    val airedAt = airings[episode.number.toInt()]?.takeIf { episode.number % 1.0 == 0.0 }
                    if (episode.airingAt == airedAt) episode else episode.copy(airingAt = airedAt)
                },
            ),
        )
    }
}

private const val DETAIL_FILLER_TIMEOUT_MS = 3_500L

/**
 * How long after an episode airs before its record is asked for the following episode's time.
 * AniList moves `nextAiringEpisode` on by its own schedule job, not on the second; asked at once,
 * it can still name the episode that just aired.
 */
private const val AIRED_SCHEDULE_SETTLE_MS = 5L * 60 * 1000

/** Marks every episode up to [dubbedThrough] as having a dub; unchanged when that is unknown. */
internal fun markDubbedEpisodes(episodes: List<EpisodeItem>, dubbedThrough: Int?): List<EpisodeItem> {
    if (dubbedThrough == null || episodes.isEmpty()) return episodes
    return episodes.map { episode ->
        val covered = episode.number >= 1 && episode.number <= dubbedThrough
        if (covered && !episode.dubbed) episode.copy(dubbed = true) else episode
    }
}

/** Applies integer episode filler flags without discarding flags supplied by a stream provider. */
internal fun mergeEpisodeFillerMarks(
    episodes: List<EpisodeItem>,
    fillerEpisodes: Set<Int>,
): List<EpisodeItem> {
    if (episodes.isEmpty() || fillerEpisodes.isEmpty()) return episodes
    return episodes.map { episode ->
        val isKnownFiller = episode.number % 1.0 == 0.0 && episode.number.toInt() in fillerEpisodes
        if (isKnownFiller && !episode.filler) episode.copy(filler = true) else episode
    }
}

/**
 * [episodes] with a picture on the rows that have none, borrowed from whichever provider in
 * [catalog] does hold one for that episode.
 *
 * The list on screen is one provider's rows, and whether they carry artwork is that provider's
 * business rather than the episode's: for a title the metadata catalogues have never heard of — an
 * airing season, which is exactly where Konoha's backfill stopped and TMDB is a request that can
 * fail — the provider's own thumbnail is the only picture there is. It is also inconsistent within
 * a single answer. A viewer watching the current season on kiwi's dub list saw empty frames on
 * every row while bonk's rows for those same episodes, already fetched and sitting in the same
 * catalog, carried real stills.
 *
 * Matched on episode number, which is what the whole app keys episodes by and what merging the
 * providers into one catalog already assumes. The picture is the episode's, not the server's, so
 * any row holding that number can supply it, dub rows from sub ones included.
 *
 * Only ever fills a gap: a row that came with its own artwork keeps it, and so does a row the
 * metadata overlay is about to put a catalogue still on, since this runs before that.
 */
internal fun fillEpisodeArtFromCatalog(
    episodes: List<EpisodeItem>,
    catalog: EpisodesResult,
): List<EpisodeItem> {
    if (episodes.isEmpty() || episodes.none { it.image.isNullOrBlank() }) return episodes
    val artByNumber = buildMap {
        catalog.providers.forEach { provider ->
            (provider.sub.asSequence() + provider.dub.asSequence()).forEach { episode ->
                val art = episode.image?.takeIf { it.isNotBlank() } ?: return@forEach
                putIfAbsent(episode.number, art)
            }
        }
    }
    if (artByNumber.isEmpty()) return episodes
    return episodes.map { episode ->
        if (!episode.image.isNullOrBlank()) return@map episode
        artByNumber[episode.number]?.let { episode.copy(image = it) } ?: episode
    }
}

/**
 * [episodes] with the filler badge on every row some provider in [catalog] marks as filler.
 *
 * Only Miruro's servers say which episodes are filler. The player's list is whichever server is
 * playing, so on anikoto, anipm or any other server Naruto's list showed no filler at all — only
 * MAL's list could mark it, and that fetch rarely finished inside its wait on a long series. The
 * flag is the episode's, not the server's, exactly like the picture [fillEpisodeArtFromCatalog]
 * borrows, and the same number match applies. It only ever adds a badge.
 */
internal fun fillEpisodeFillerFromCatalog(
    episodes: List<EpisodeItem>,
    catalog: EpisodesResult,
): List<EpisodeItem> {
    if (episodes.isEmpty() || episodes.all { it.filler }) return episodes
    val fillerNumbers = catalog.providers.asSequence()
        .flatMap { provider -> provider.sub.asSequence() + provider.dub.asSequence() }
        .filter { it.filler }
        .mapTo(HashSet()) { it.number }
    if (fillerNumbers.isEmpty()) return episodes
    return episodes.map { episode ->
        if (!episode.filler && episode.number in fillerNumbers) episode.copy(filler = true) else episode
    }
}

/**
 * The catalogue's episode numbers by title, normalised so punctuation cannot hide a match.
 *
 * Used to catch a provider putting a title on the wrong episode. Miruro's catalogue for Frieren
 * (154587) names episode 1 "Like a Fairy Tale", which is episode 7, and shifts 1-6 down one to
 * make room — on bee, hop and kiwi alike, checked against miruro.anifake.com and MegaPlay's own
 * pages on 2026-09-24. The videos are right; only the labels moved, so the player's header named
 * the wrong episode. Episodes 8 onward line up, so this cannot be judged for a list as a whole.
 */
internal fun catalogueNumberByTitle(meta: List<EpisodeMetadata>): Map<String, Double> =
    meta.mapNotNull { m -> m.title?.let(::normalEpisodeTitle)?.takeIf { it.isNotEmpty() }?.to(m.number) }
        .toMap()

/**
 * True when [episode]'s own title is, word for word, the catalogue's title for a different episode.
 *
 * A provider's wording normally wins (see [mergeEpisodeMetadata]), which is only sound while it is
 * this episode's title; one that names another episode is not, whatever the rest of the list does.
 */
internal fun titleBelongsElsewhere(episode: EpisodeItem, numberByTitle: Map<String, Double>): Boolean {
    val title = episode.distinctTitle?.let(::normalEpisodeTitle)?.takeIf { it.isNotEmpty() } ?: return false
    val catalogueNumber = numberByTitle[title] ?: return false
    return catalogueNumber != episode.number
}

private fun normalEpisodeTitle(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

/**
 * Fills titles and thumbnails from the episode catalogues into the synthetic AniList catalog by
 * episode number. When AniList had no usable count (e.g. RELEASING with unknown totals), the aired
 * catalogue entries become the catalog themselves.
 */
internal fun mergeEpisodeMetadata(
    base: List<EpisodeItem>,
    meta: List<EpisodeMetadata>,
    animeId: Int,
): List<EpisodeItem> {
    if (meta.isEmpty()) return base
    val byNumber = meta.associateBy { it.number }
    if (base.isNotEmpty()) {
        val numberByTitle = catalogueNumberByTitle(meta)
        return base.map { episode ->
            val extra = byNumber[episode.number] ?: return@map episode
            episode.copy(
                // A provider that filled the title with "Episode 5" counts as having none, or its
                // placeholder would outrank the real title this overlay exists to supply. A
                // provider whose titles sit on the wrong numbers has none worth keeping either.
                title = if (titleBelongsElsewhere(episode, numberByTitle)) {
                    extra.title
                } else {
                    episode.distinctTitle ?: extra.title ?: episode.title
                },
                // The catalogue still wins over the provider's. A provider thumbnail belongs to
                // whichever server happens to be selected, so the same episode changed picture
                // when the viewer switched servers, and servers that ship no art at all left rows
                // blank next to a perfectly good TMDB still. The provider's art is kept only for
                // episodes no catalogue could place — mostly the current season, where Konoha has
                // not backfilled and TMDB may have nothing to map onto yet.
                image = extra.still?.takeIf { it.isNotBlank() } ?: episode.image,
                // Kept so the row can say whether it is watchable yet. The provider never states
                // this and AniList only states the next one, so the catalogue is the only source.
                airDate = extra.airDate ?: episode.airDate,
                // Only the overlay ever knows these; a stream provider states none of them.
                overview = extra.overview ?: episode.overview,
                runtime = extra.runtime ?: episode.runtime,
                rating = extra.rating ?: episode.rating,
            )
        }
    }
    val today = LocalDate.now()
    return meta
        .filter { ep ->
            // Konoha writes air dates both as a bare day and as a full timestamp; a row it dated in
            // the second shape used to parse as nothing at all and be kept however far off it aired.
            ep.number >= 1 && episodeAirDate(ep.airDate)?.isAfter(today) != true
        }
        // Konoha concatenates every part it holds for a title without restarting the numbering
        // per part, so a multi-part entry answers with the same number several times over —
        // AniList 179068 returns 1,2,3,4,5,6,1,1,1,1,1. Each one became the same synthetic id and
        // Compose killed the title page outright: `Key "anilist:179068:1" was already used`.
        // First row per number wins, which is the one that carries the real part.
        .distinctBy { it.number }
        .sortedBy { it.number }
        .map { ep ->
            val number = ep.number
            EpisodeItem(
                pipeId = anilistEpisodeId(animeId, number),
                number = number,
                title = ep.title,
                image = ep.still,
                filler = false,
                airDate = ep.airDate,
                overview = ep.overview,
                runtime = ep.runtime,
                rating = ep.rating,
            )
        }
}

/**
 * The synthetic, UI-only id for an episode no stream provider supplied.
 *
 * Formatted like [EpisodeItem.displayNumber] rather than truncated to an Int, so a recap numbered
 * 1.5 keeps an id of its own instead of colliding with episode 1 the way `toInt()` made it.
 */
private fun anilistEpisodeId(animeId: Int, number: Double): String {
    val label = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
    return "anilist:$animeId:$label"
}

/**
 * AniList exposes series-level episode counts rather than stream-provider rows. For currently
 * airing anime, the next scheduled episode gives the released count; completed shows use the
 * total. The stable synthetic id is UI-only and is never sent to a playback provider.
 */
internal fun anilistEpisodeCatalog(info: Media): List<EpisodeItem> {
    // An announced season often already carries its planned count, and none of it has aired.
    if (info.isUpcoming()) return emptyList()
    val releasedBeforeNext = info.nextAiringEpisode?.episode?.minus(1)?.coerceAtLeast(0)
    val count = when {
        releasedBeforeNext != null && info.episodes != null -> minOf(releasedBeforeNext, info.episodes)
        releasedBeforeNext != null -> releasedBeforeNext
        else -> info.episodes ?: 0
    }.coerceAtLeast(0)

    return (1..count).map { number ->
        EpisodeItem(
            pipeId = anilistEpisodeId(info.id, number.toDouble()),
            number = number.toDouble(),
            title = null,
            image = null,
            filler = false,
        )
    }
}

/**
 * Whether AniList's record cannot say how many episodes are out: a released title with no total
 * and no schedule, which [anilistEpisodeCatalog] can only answer with nothing.
 *
 * Nearly every hentai OVA series is in this state for as long as it runs — AniList files it as
 * RELEASING with `episodes` null and no next airing, because its episodes have no broadcast
 * schedule to count — and the metadata tree declines adult titles, so the page said "No episode
 * information is available yet." and greyed out Watch on series the adult servers carry in full.
 */
internal fun Media.episodeCountUnknown(): Boolean =
    !isUpcoming() && episodes == null && nextAiringEpisode == null

/**
 * The season's rows counted from the episode numbers the stream providers list, for a season
 * AniList cannot count (see [episodeCountUnknown]). Numbered rows only, under the same synthetic
 * ids as [anilistEpisodeCatalog], so playback resolves them exactly as it does a counted season's.
 *
 * For an [adult] title only the adult servers are counted when any of them answered. A general
 * catalogue "finding" a hentai OVA is a title-matcher accident, not coverage: ReAnime matched
 * Imaizumin-chi (132203) to Super GALS! Kotobuki Ran and listed 50 episodes beside hstream's and
 * hanime's six, and a union of the three put 50 rows on the page.
 */
internal fun providerEpisodeCatalog(
    animeId: Int,
    catalog: EpisodesResult,
    adult: Boolean = false,
): List<EpisodeItem> {
    val answered = catalog.providers.filter { it.sub.isNotEmpty() || it.dub.isNotEmpty() }
    val counted = answered.filter { ProviderCatalog.isAdultOnly(it.name) }
        .takeIf { adult && it.isNotEmpty() }
        ?: answered
    return counted
        .flatMap { it.sub + it.dub }
        .map(EpisodeItem::number)
        .filter { it > 0.0 }
        .distinct()
        .sorted()
        .map { number ->
            EpisodeItem(
                pipeId = anilistEpisodeId(animeId, number),
                number = number,
                title = null,
                image = null,
                filler = false,
            )
        }
}

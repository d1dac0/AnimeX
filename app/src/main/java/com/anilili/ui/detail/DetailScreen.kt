package com.anilili.ui.detail

import com.anilili.ui.nav.AnililiLink
import com.anilili.ui.theme.canvasColor
import com.anilili.ui.theme.appCanvasFade
import com.anilili.ui.theme.appCanvas
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.ui.theme.ShowColourTheme
import coil.compose.AsyncImage
import com.anilili.data.library.EpisodeStop
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.library.localWatchedLabel
import com.anilili.data.library.mediaListStatusLabel
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.FuzzyDate
import com.anilili.data.model.Media
import com.anilili.data.model.StudioNode
import com.anilili.data.model.Trailer
import com.anilili.data.model.episodeAirDate
import com.anilili.data.model.episodeIsUnaired
import com.anilili.data.settings.EpisodeLayout
import com.anilili.data.settings.RelatedLayout
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import com.anilili.playback.EpisodeDownloadUi
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.OnTvRefreshRequest
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.TvRefreshRequest
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.compactMemberCount
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.AnimeListActionsDialog
import com.anilili.ui.components.BulkDownloadStatus
import com.anilili.ui.components.CoverImagePreview
import com.anilili.ui.components.DownloadCoverBadge
import com.anilili.ui.components.EPISODE_BROWSER_MIN_EPISODES
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.EpisodeBrowserBar
import com.anilili.ui.components.EpisodeDownloadAction
import com.anilili.ui.components.EpisodeDownloadButton
import com.anilili.ui.components.EpisodeFillerBadge
import com.anilili.ui.components.EpisodeNumberChip
import com.anilili.ui.components.EpisodeWatchedToggle
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.NextEpisodeCountdown
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.components.SeriesLogo
import com.anilili.ui.components.ShrinkingTitle
import com.anilili.ui.components.TvTopNavigationHeight
import com.anilili.ui.components.UNAIRED_ALPHA
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.WatchedToggleButton
import com.anilili.ui.components.blockIndexOf
import com.anilili.ui.components.openingEpisode
import com.anilili.ui.components.chromeFade
import com.anilili.ui.components.episodeArtworkFallback
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeMarkedWatched
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.filterEpisodes
import com.anilili.ui.components.orderEpisodeBlocks
import com.anilili.ui.components.orderEpisodes
import com.anilili.ui.components.toggleEpisodeWatched
import java.time.format.DateTimeFormatter
import com.anilili.util.cached
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.glassEnabled

private enum class DetailTab(val label: String) {
    HOME("Home"),
    EPISODES("Episodes"),
    RELATED("Related"),
}

/**
 * The shelves of the Related tab. The whole franchise and the community picks used to be one
 * undivided list, which on a long-running series is a scroll with no landmarks in it — the films
 * were somewhere in the middle of forty entries and nothing said which was which.
 *
 * [group] is the watch-guide shelf this chip narrows to; the two chips with none are the "All"
 * default and the recommendations, which are not part of the franchise at all. The TV page draws the
 * same chips beside its tabs.
 */
internal enum class RelatedFilter(val label: String, val group: WatchOrderGroup?) {
    ALL("All", null),
    SEASONS("Seasons", WatchOrderGroup.SEASONS),
    MOVIES("Movies", WatchOrderGroup.MOVIES),
    OVAS("OVAs", WatchOrderGroup.OVAS),
    SPECIALS("Specials", WatchOrderGroup.SPECIALS),
    OTHER("Other", WatchOrderGroup.OTHER),
    SUGGESTIONS("More like this", null),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    /**
     * The episode the player's Back was left on, when that is how the viewer arrived.
     *
     * Television only: the page is where Back from the player lands, and the viewer is looking
     * for the episode they just left rather than for the Watch button. A page opened any other
     * way passes null and behaves exactly as before.
     */
    focusEpisode: String? = null,
    /** The television's reload, raised by a hold on the lit tab in the navigation bar above. */
    tvRefreshRequest: TvRefreshRequest? = null,
    vm: DetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    // The page's own colour, not the selected season's: switching seasons re-draws the hero, and
    // re-colouring every control under the cursor with it would read as the page flickering.
    ShowColourTheme((state as? UiState.Success)?.data?.info?.coverImage?.color) {
        DetailScreenBody(
            animeId = animeId,
            onBack = onBack,
            onPlay = onPlay,
            onAnimeClick = onAnimeClick,
            onStudioClick = onStudioClick,
            onSeasonWatch = onSeasonWatch,
            focusEpisode = focusEpisode,
            tvRefreshRequest = tvRefreshRequest,
            vm = vm,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun DetailScreenBody(
    animeId: Int,
    onBack: () -> Unit,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    focusEpisode: String?,
    tvRefreshRequest: TvRefreshRequest?,
    vm: DetailViewModel,
) {
    LaunchedEffect(animeId) { vm.load(animeId) }
    OnTvRefreshRequest(tvRefreshRequest) { vm.refresh(animeId) }
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val localWatched by LocalWatchedStore.entries.collectAsState()
    val backFocusRequester = remember { FocusRequester() }
    val primaryActionFocusRequester = remember { FocusRequester() }
    val detailActionsReady = state is UiState.Success
    val device = LocalAppDeviceProfile.current
    var backFocusEnabled by remember(animeId, device.isTv) { mutableStateOf(!device.isTv) }

    // The television's first focus — the Watch button, the list button when Watch is disabled, or
    // the episode the player's Back named — is placed by TvDetailContent, which is the only part
    // of the page that knows whether Watch can take it. A single request from here, swallowed when
    // it failed, left an announced title with nothing focused at all.

    if (device.isTv) {
        when (val s = state) {
            is UiState.Loading -> LoadingBox(Modifier.padding(top = TvTopNavigationHeight))
            is UiState.Error -> ErrorBox(
                failure = s.failure,
                onRetry = { vm.load(animeId, force = true) },
                modifier = Modifier.padding(top = TvTopNavigationHeight),
            )
            is UiState.Success -> {
                // The television pane describes whichever season its chips have selected, so the
                // heart and the list status beside that title are the selected season's too — a
                // page showing season three that saved season one is the same fault reported for
                // the title and the synopsis, one control further along.
                val season = s.data.selectedSeason
                val saved = watchlist.any { it.anilistId == season.id }
                val listStatusLabel = mediaListStatusLabel(remoteStatuses[season.id])
                    ?: localWatchedLabel(localWatched, season.id)
                PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh(animeId) },
                    modifier = Modifier.fillMaxSize(),
                    // The hero fills the window with the navigation drawn over it, so this box
                    // begins behind the bar. Left at zero the puck spins up there, out of sight,
                    // and the hold that asked for it looks like a key the remote does not have.
                    indicatorTopPadding = TvTopNavigationHeight,
                ) {
                    TvDetailContent(
                        data = s.data,
                        saved = saved,
                        listStatusLabel = listStatusLabel,
                        history = history,
                        onPlay = onPlay,
                        onAnimeClick = onAnimeClick,
                        onStudioClick = onStudioClick,
                        onSelectSeason = vm::selectSeason,
                        loadProviderCatalog = vm::providerCatalog,
                        primaryActionFocusRequester = primaryActionFocusRequester,
                        onPrimaryFocusAcquired = { backFocusEnabled = true },
                        focusEpisode = focusEpisode,
                    )
                }
            }
        }
        return
    }

    // Held here rather than inside the bar because the list it watches lives further down the tree
    // than the scaffold slot that draws it — the same reason Home hoists its own.
    val detailListState = rememberLazyListState()
    // What the header frosts once it is back over the page; null with Details' glass switched off.
    val detailBackdrop = if (glassEnabled(GlassSection.DETAILS)) remember { HazeState() } else null
    val chromeOverHero by remember {
        derivedStateOf { detailListState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = Modifier.appCanvas(),
        containerColor = canvasColor(),
        topBar = {
            ScrollAwareTopBar {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        val controlFocus1 = rememberFocusFill()
                        IconButton(
                            colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = onBack,
                            modifier = Modifier
                                .focusRequester(backFocusRequester)
                                .focusProperties {
                                    if (device.isTv && !backFocusEnabled) canFocus = false
                                    if (detailActionsReady) down = primaryActionFocusRequester
                                }
                                .focusFill(controlFocus1, RoundedCornerShape(24.dp)),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val context = LocalContext.current
                        val shareFocus = rememberFocusFill()
                        val title = (state as? UiState.Success)?.data?.info?.title?.preferred
                        IconButton(
                            colors = shareFocus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = { shareAnimeLink(context, animeId, title) },
                            modifier = Modifier.focusFill(shareFocus, RoundedCornerShape(24.dp)),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share link")
                        }
                    },
                    // Same treatment as Home: the artwork runs to the top edge and the back arrow
                    // stays legible because the fade is darkest exactly where it sits. An opaque
                    // bar here spent the top of the window on nothing and made the banner read as a
                    // picture pasted below the chrome rather than as the top of the page.
                    modifier = Modifier.chromeFade(chromeOverHero, detailBackdrop),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        },
    ) { scaffoldPadding ->
        // The floating tab bar covers the foot of this page now, so the list and the pull-up
        // gauge clear whichever is taller: the bar, or the system navigation area alone.
        val chromeBottom = LocalAppChromeBottomInset.current
        val padding = PaddingValues(
            start = scaffoldPadding.calculateStartPadding(LayoutDirection.Ltr),
            top = scaffoldPadding.calculateTopPadding(),
            end = scaffoldPadding.calculateEndPadding(LayoutDirection.Ltr),
            bottom = maxOf(scaffoldPadding.calculateBottomPadding(), chromeBottom),
        )
        when (val s = state) {
            is UiState.Loading -> LoadingBox(Modifier.padding(padding))
            is UiState.Error -> ErrorBox(
                failure = s.failure,
                onRetry = { vm.load(animeId, force = true) },
                modifier = Modifier.padding(padding),
            )
            is UiState.Success -> {
                val info = s.data.info
                val saved = watchlist.any { it.anilistId == info.id }
                val listStatusLabel = mediaListStatusLabel(remoteStatuses[info.id])
                    ?: localWatchedLabel(localWatched, info.id)
                PullRefreshContainer(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh(animeId) },
                    modifier = Modifier.fillMaxSize().then(if (detailBackdrop != null) Modifier.hazeSource(detailBackdrop) else Modifier),
                    indicatorTopPadding = padding.calculateTopPadding(),
                    indicatorBottomPadding = padding.calculateBottomPadding(),
                    // The page is one list with the episodes at the end of it, so a viewer who has
                    // scrolled to the last row is looking at the place a just-aired episode would
                    // appear — and is as far from the pull at the top as this page ever gets.
                    // `canScrollBackward` is what keeps a short tab, scrolled nowhere, from
                    // reloading on an upward flick that only meant "is there more below".
                    pullUpAtEnd = {
                        detailListState.canScrollBackward && !detailListState.canScrollForward
                    },
                ) {
                    DetailContent(
                        contentPadding = padding,
                        detailListState = detailListState,
                        data = s.data,
                        saved = saved,
                        listStatusLabel = listStatusLabel,
                        resume = history.firstOrNull { it.anilistId == animeId },
                        history = history,
                        saveEntry = WatchlistEntry(
                            anilistId = info.id,
                            title = info.title.preferred,
                            cover = info.coverImage.best,
                            format = info.format,
                            averageScore = info.averageScore,
                        ),
                        onPlay = onPlay,
                        onAnimeClick = onAnimeClick,
                        onStudioClick = onStudioClick,
                        onSeasonWatch = onSeasonWatch,
                        onSelectSeason = vm::selectSeason,
                        loadProviderCatalog = vm::providerCatalog,
                        backFocusRequester = backFocusRequester,
                        primaryActionFocusRequester = primaryActionFocusRequester,
                        onPrimaryFocusAcquired = { backFocusEnabled = true },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(
    data: DetailData,
    saved: Boolean,
    listStatusLabel: String?,
    resume: HistoryEntry?,
    history: List<HistoryEntry> = emptyList(),
    saveEntry: WatchlistEntry,
    onPlay: (Int, String, String, String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSeasonWatch: (Int) -> Unit,
    onSelectSeason: (Int) -> Unit,
    /** The providers' episode listing for a season, fetched only when a download needs it. */
    loadProviderCatalog: suspend (Int) -> EpisodesResult,
    backFocusRequester: FocusRequester,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
    /** Hoisted so the scaffold's bar, which sits above this in the tree, can watch it. */
    detailListState: LazyListState,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val info = data.info
    // The episode rows belong to the season the chips selected, not to the anime the page was
    // opened on, so what has aired has to be read from that one — `info.nextAiringEpisode` is the
    // schedule of a different season as soon as the viewer switches.
    val episodeSeason = data.selectedSeason
    val device = LocalAppDeviceProfile.current
    val episodes = data.episodes
    val isMovie = isMovieFormat(info.format)
    var selectedTab by remember(info.id) { mutableStateOf(DetailTab.HOME) }
    val visibleTabs = remember(isMovie) {
        DetailTab.entries.filterNot { isMovie && it == DetailTab.EPISODES }
    }
    var relatedFilter by remember(info.id) { mutableStateOf(RelatedFilter.ALL) }
    val canWatch = episodes.isNotEmpty()
    val primaryActionIsWatch = canWatch || resume != null
    val playCurrent: () -> Unit = {
        when {
            resume != null -> onPlay(info.id, resume.provider, resume.category, resume.episodeLabel)
            canWatch -> onPlay(
                data.selectedSeasonId,
                "auto",
                data.preferredCategory.api,
                episodes.first().displayNumber,
            )
        }
    }
    val playEpisode: (EpisodeItem) -> Unit = { episode ->
        onPlay(data.selectedSeasonId, "auto", data.preferredCategory.api, episode.displayNumber)
    }

    // An episode mark belongs to the season whose list is on screen, not to the page's own id:
    // each season of a series carries its own row, its own progress and its own account entry,
    // and the episode numbers start again in every one of them.
    val seasonMedia = data.seasons.firstOrNull { it.id == data.selectedSeasonId } ?: info
    val seasonResume = history.firstOrNull { it.anilistId == data.selectedSeasonId }
    val toggleWatched: (EpisodeItem) -> Unit = { episode ->
        toggleEpisodeWatched(
            anilistId = data.selectedSeasonId,
            episodeNumber = episode.number,
            currentlyWatched = episodeMarkedWatched(seasonResume, episode.number),
            title = seasonMedia.title.preferred,
            cover = seasonMedia.coverImage.best,
            totalEpisodes = seasonMedia.episodes,
            airedEpisodes = episodes
                .filterNot { episodeIsUnaired(it, episodeSeason) }
                .map { EpisodeStop(it.number, it.distinctTitle) },
        )
    }

    // Episode browsing. Query and range reset per season; a null range means "not chosen yet",
    // which is what lets the resumed episode's block be the one that opens.
    val episodeLayout by SettingsStore.episodeLayout.collectAsState()
    val episodeOrder by SettingsStore.episodeOrder.collectAsState()
    val relatedLayout by SettingsStore.relatedLayout.collectAsState()
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    var episodeQuery by remember(data.selectedSeasonId) { mutableStateOf("") }
    // Keyed on the order too: an index into the ranges names a different range once they turn round.
    var chosenBlockIndex by remember(data.selectedSeasonId, episodeOrder) { mutableStateOf<Int?>(null) }
    val blocks = remember(episodes, episodeOrder) { orderEpisodeBlocks(episodeBlocks(episodes), episodeOrder) }
    // The bar is up wherever there are episodes, because the layout toggle lives in it and a short
    // season chooses grid or list too; the range picker and the filter are the long-list halves.
    val browserVisible = selectedTab == DetailTab.EPISODES && episodes.isNotEmpty()
    val episodeFilterVisible = episodes.size > EPISODE_BROWSER_MIN_EPISODES
    val browserFocus = remember { FocusRequester() }

    // Which episodes the list is showing, hoisted out of the Episodes tab because the batch
    // download acts on exactly this set — that scope is the whole point of it.
    //
    // Until the viewer picks a range, open on the one holding their resume point — otherwise a
    // thousand-episode series always drops them back at episode 1. With no resume point a long
    // series opens on its newest aired episode instead (see [openingEpisode]).
    val startEpisode = remember(episodes, seasonResume?.episodeNumber, seasonMedia) {
        openingEpisode(episodes, seasonResume?.episodeNumber, isUnaired = { episodeIsUnaired(it, seasonMedia) })
    }
    val blockIndex = (chosenBlockIndex ?: blockIndexOf(blocks, startEpisode))
        .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    // A search spans the whole season; only an unfiltered list is bounded by the block.
    val filtering = episodeQuery.isNotBlank()
    val shown = if (filtering) {
        orderEpisodes(filterEpisodes(episodes, episodeQuery), episodeOrder)
    } else {
        blocks.getOrNull(blockIndex)?.episodes.orEmpty()
    }

    val downloads = rememberSeasonDownloads(
        data = data,
        seasonMedia = seasonMedia,
        // Turned back into running order, so a batch still queues episode 1 before episode 2
        // whichever way round the list is drawn.
        shown = orderEpisodes(shown, episodeOrder),
        scopeLabel = seasonDownloadScopeLabel(
            seasonLabel = data.seasonLabels[data.selectedSeasonId]
                .takeIf { data.seasons.size > 1 },
            blockLabel = blocks.getOrNull(blockIndex)?.label.takeIf { blocks.size > 1 },
            filterQuery = episodeQuery,
            shownCount = shown.size,
        ),
        loadProviderCatalog = loadProviderCatalog,
    )

    var coverPreviewVisible by remember(info.id) { mutableStateOf(false) }

    if (coverPreviewVisible) {
        CoverImagePreview(
            imageUrl = info.coverImage.best,
            title = info.title.preferred,
            onDismiss = { coverPreviewVisible = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = detailListState,
            modifier = Modifier.fillMaxSize(),
            // Scroll padding, not layout padding, so rows travel under the bar as it retreats. The
            // hero takes that height into itself instead, so the banner starts at the top edge
            // rather than below the bar; everything under it keeps the room it had.
            contentPadding = PaddingValues(
                bottom = contentPadding.calculateBottomPadding() + 30.dp,
            ),
        ) {
            item {
                DetailHero(
                    info = info,
                    // This hero always shows the page's own title and cover, so it takes the page's
                    // logo rather than the selected season's — which used to leave it with none at
                    // all as soon as another season chip was picked. See DetailData.pageLogo.
                    logo = data.pageLogo,
                    onStudioClick = onStudioClick,
                    onCoverClick = { coverPreviewVisible = true },
                    topInset = contentPadding.calculateTopPadding(),
                )
            }
            item {
                DetailActions(
                    saved = saved,
                    title = info.title.preferred,
                    cover = info.coverImage.best,
                    metadata = listOfNotNull(
                        info.format?.replace('_', ' '),
                        info.seasonYear?.toString(),
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                    listStatusLabel = listStatusLabel,
                    canWatch = primaryActionIsWatch,
                    resolving = false,
                    resume = resume,
                    format = info.format,
                    anilistId = info.id,
                    totalEpisodes = info.episodes ?: if (isMovieFormat(info.format)) 1 else null,
                    saveEntry = saveEntry,
                    onWatch = playCurrent,
                    backFocusRequester = backFocusRequester,
                    primaryActionFocusRequester = primaryActionFocusRequester,
                    onPrimaryFocusAcquired = onPrimaryFocusAcquired,
                )
            }
            resume?.let { entry ->
                item {
                    SeriesWatchProgress(
                        resume = entry,
                        totalEpisodes = maxOf(episodes.size, info.episodes ?: 0),
                        isMovie = isMovie,
                    )
                }
            }
            stickyHeader {
                DetailTabs(
                    selected = selectedTab,
                    tabs = visibleTabs,
                    onSelect = { selectedTab = it },
                    downFocus = browserFocus.takeIf { browserVisible },
                )
            }

            when (selectedTab) {
                DetailTab.HOME -> {
                    item { QuickFacts(info) }
                    info.nextAiringEpisode?.airingAt?.let { airingAt ->
                        item { NextAiringCard(airingAt, info.nextAiringEpisode.episode) }
                    }
                    if (info.genres.isNotEmpty()) item { GenreRow(info.genres) }
                    item { Description(info.description) }
                    info.trailer?.takeIf { it.watchUrl != null }?.let { trailer ->
                        item(key = "trailer") { TrailerCard(trailer) }
                    }
                    item { MetadataCard(info) }
                }

                DetailTab.EPISODES -> {
                    val seasons = data.seasons
                    if (seasons.size > 1) {
                        item {
                            SeasonFilterRow(
                                seasons = seasons,
                                labels = data.seasonLabels,
                                selectedSeasonId = data.selectedSeasonId,
                                onSelect = onSelectSeason,
                            )
                        }
                    }
                    val seasonCover = seasons.firstOrNull { it.id == data.selectedSeasonId }
                        ?.let { it.bannerImage ?: it.coverImage.best }
                    if (browserVisible) {
                        item {
                            EpisodeBrowserBar(
                                blocks = blocks,
                                selectedBlockIndex = blockIndex,
                                onSelectBlock = { chosenBlockIndex = it },
                                query = episodeQuery,
                                onQueryChange = { episodeQuery = it },
                                layout = episodeLayout,
                                onToggleLayout = { SettingsStore.setEpisodeLayout(episodeLayout.toggled()) },
                                order = episodeOrder.takeIf { episodes.size > 1 },
                                onToggleOrder = { SettingsStore.setEpisodeOrder(episodeOrder.toggled()) },
                                showFilter = episodeFilterVisible,
                                focusRequester = browserFocus,
                                modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 6.dp),
                            )
                        }
                    }
                    if (shown.isNotEmpty()) {
                        item(key = "episode-list-actions") {
                            EpisodeListActions(
                                shownCount = shown.size,
                                pendingCount = downloads.batchCandidateCount,
                                preparing = downloads.batchPreparing,
                                onDownload = downloads.openBatch,
                            )
                        }
                    }
                    // Only the first row needs redirecting; the rest reach the bar through it.
                    val firstItemUp: Modifier = if (browserVisible) {
                        Modifier.focusProperties { up = browserFocus }
                    } else {
                        Modifier
                    }
                    when {
                        shown.isNotEmpty() && episodeLayout == EpisodeLayout.GRID -> itemsIndexed(
                            items = shown.chunked(device.episodeColumns),
                            key = { _, row -> row.first().pipeId },
                        ) { rowIndex, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp)
                                    .then(if (rowIndex == 0) firstItemUp else Modifier),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { episode ->
                                    EpisodeNumberChip(
                                        episode = episode,
                                        selected = false,
                                        watchedFraction = episodeWatchFraction(seasonResume, episode.number),
                                        modifier = Modifier.weight(1f),
                                        onClick = { playEpisode(episode) },
                                        // A chip is too small to carry the eye the list rows show,
                                        // so the hold does what the eye does there.
                                        onLongClick = { toggleWatched(episode) },
                                        unaired = episodeIsUnaired(episode, episodeSeason),
                                    )
                                }
                                repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        shown.isNotEmpty() -> itemsIndexed(
                            items = shown,
                            key = { _, episode -> episode.pipeId },
                        ) { index, episode ->
                            val downloadTarget = downloads.targetFor(episode)
                            val episodeWatched = episodeMarkedWatched(seasonResume, episode.number)
                            DetailEpisodeRow(
                                episode = episode,
                                fallbackImage = episodeArtworkFallback(
                                    shown,
                                    seasonCover ?: info.bannerImage ?: info.coverImage.best,
                                ),
                                blurred = blurEpisodeImages,
                                watchedFraction = episodeWatchFraction(seasonResume, episode.number),
                                watched = episodeWatched,
                                downloadState = downloadTarget.state,
                                downloadPending = downloads.isPending(episode),
                                onDownloadAction = { action ->
                                    downloads.onEpisodeAction(downloadTarget, action)
                                },
                                onClick = { playEpisode(episode) },
                                // The eye marks it too. The hold stays for the viewer who learned it
                                // while the eye was away, and for the grid chips, which have no room
                                // for one.
                                onLongClick = { toggleWatched(episode) },
                                onToggleWatched = { toggleWatched(episode) },
                                modifier = if (index == 0) firstItemUp else Modifier,
                                unaired = episodeIsUnaired(episode, episodeSeason),
                            )
                        }
                        filtering -> item {
                            InlineStatus("No episode matches “$episodeQuery”.", loading = false)
                        }
                        data.seasonEpisodesLoading -> item { InlineStatus("Loading episodes…", loading = true) }
                        else -> item { InlineStatus("No episode information is available yet.", loading = false) }
                    }
                }

                DetailTab.RELATED -> {
                    // The franchise as one running order first — the films and extras between the
                    // seasons they aired between — and AniList's community picks for titles outside
                    // it behind them. The chips narrow to one shelf; the numbers on the rows are
                    // franchise-wide either way, so they still say what comes first.
                    // A franchise of one is this page and nothing else, and a guide to it would be
                    // a heading over a row for the title already on screen.
                    val watchOrder = data.watchOrder.takeIf { it.size > 1 }.orEmpty()
                    val suggestions = data.suggestions
                    val shelves = WatchOrderGroup.entries.associateWith { group ->
                        watchOrder.filter { it.group == group }
                    }
                    val filters = RelatedFilter.entries.filter { filter ->
                        when (filter) {
                            RelatedFilter.ALL -> true
                            RelatedFilter.SUGGESTIONS -> suggestions.isNotEmpty()
                            else -> shelves[filter.group]?.isNotEmpty() == true
                        }
                    }
                    // A chip can lose its content between loads (the series walk replaces the
                    // seeded chain), and a filter pinned to an empty shelf shows an empty tab.
                    val active = if (relatedFilter in filters) relatedFilter else RelatedFilter.ALL
                    // One shelf and nothing else is the whole tab; chips that only ever say "All"
                    // are furniture.
                    if (filters.size > 2) {
                        item(key = "related-filters") {
                            RelatedFilterRow(
                                filters = filters,
                                selected = active,
                                onSelect = { relatedFilter = it },
                            )
                        }
                    }
                    // One switch for the whole tab, on whichever shelf heading comes first, so it is
                    // at the top whatever the chips have narrowed the tab to.
                    val layoutToggle: @Composable () -> Unit = {
                        RelatedLayoutToggle(
                            layout = relatedLayout,
                            onToggle = { SettingsStore.setRelatedLayout(relatedLayout.toggled()) },
                        )
                    }
                    var layoutToggleShown = false
                    fun headerAction(): (@Composable () -> Unit)? {
                        if (layoutToggleShown) return null
                        layoutToggleShown = true
                        return layoutToggle
                    }
                    val titlesOnly = relatedLayout == RelatedLayout.TITLES
                    if (active != RelatedFilter.SUGGESTIONS) {
                        val shelf = active.group
                        val entries = if (shelf == null) watchOrder else shelves[shelf].orEmpty()
                        if (entries.isNotEmpty()) {
                            val action = headerAction()
                            item(key = "shelf-${shelf?.name ?: "ALL"}") {
                                DetailSectionHeader(
                                    shelf?.label ?: "Release order",
                                    trailing = entries.size.toString(),
                                    action = action,
                                )
                            }
                            items(entries, key = { entry -> "watch-order-${entry.media.id}" }) { entry ->
                                if (titlesOnly) {
                                    RelatedTitleRow(
                                        title = entry.media.title.preferred,
                                        meta = entry.metaLine(),
                                        position = entry.position,
                                        isCurrent = entry.isCurrent,
                                        onOpen = { onAnimeClick(entry.media.id) },
                                    )
                                } else {
                                    WatchOrderRow(
                                        entry = entry,
                                        onOpen = { onAnimeClick(entry.media.id) },
                                        onWatch = { onSeasonWatch(entry.media.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (suggestions.isNotEmpty() &&
                        (active == RelatedFilter.ALL || active == RelatedFilter.SUGGESTIONS)
                    ) {
                        val action = headerAction()
                        item(key = "suggested-header") {
                            DetailSectionHeader("More like this", trailing = suggestions.size.toString(), action = action)
                        }
                        items(suggestions, key = { media -> "suggested-${media.id}" }) { media ->
                            if (titlesOnly) {
                                RelatedTitleRow(
                                    title = media.title.preferred,
                                    meta = (listOfNotNull(media.format?.pretty()) + media.yearAndEpisodes())
                                        .joinToString(" · "),
                                    onOpen = { onAnimeClick(media.id) },
                                )
                            } else {
                                RelatedRow(
                                    media = media,
                                    onOpen = { onAnimeClick(media.id) },
                                    onWatch = { onSeasonWatch(media.id) },
                                )
                            }
                        }
                    }
                    if (watchOrder.isEmpty() && suggestions.isEmpty()) {
                        item {
                            if (data.seriesLoading || data.recommendationsLoading) {
                                InlineStatus("Finding titles to recommend…", loading = true)
                            } else {
                                InlineStatus("No recommendations found.", loading = false)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(device.pagePadding)) }
        }
    }
}

@Composable
private fun DetailHero(
    info: Media,
    /** The series' own wordmark, drawn in place of the title where the metadata tree has one. */
    logo: String?,
    onStudioClick: (StudioNode) -> Unit,
    onCoverClick: () -> Unit,
    /**
     * The height of the chrome the banner runs beneath. Added to the hero rather than taken out of
     * it, so the artwork gains that much height and the cover and title block below keep the room
     * they had. Zero leaves the hero exactly as it was.
     */
    topInset: Dp = 0.dp,
) {
    val device = LocalAppDeviceProfile.current
    val banner = info.bannerImage ?: info.coverImage.best
    val bannerHeight = topInset + if (device.isExpanded) 230.dp else 190.dp
    Box(Modifier.fillMaxWidth().height(topInset + if (device.isExpanded) 330.dp else 286.dp)) {
        AsyncImage(
            model = banner,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(bannerHeight),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .appCanvasFade { canvas -> Brush.verticalGradient(listOf(Color.Transparent, canvas)) },
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = device.pagePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .width(if (device.isExpanded) 126.dp else 98.dp)
                    .aspectRatio(2f / 3f)
                    .focusHighlight(RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "View larger cover", onClick = onCoverClick),
            ) {
                AsyncImage(
                    model = info.coverImage.best,
                    contentDescription = info.title.preferred,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(4.dp),
                )
            }
            Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                // A fixed height rather than wrapping the image, so the row does not jump when the
                // bitmap lands; bottom-aligned so a short wordmark sits on the studio badge rather
                // than floating mid-box.
                SeriesLogo(
                    url = logo,
                    contentDescription = info.title.preferred,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (device.isExpanded) 76.dp else 60.dp)
                        .padding(bottom = 4.dp),
                ) {
                    ShrinkingTitle(
                        text = info.title.preferred,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                        maxLines = 3,
                        minFontSize = 15.sp,
                    )
                }
                info.studios.nodes.firstOrNull { it.isAnimationStudio && !it.name.isNullOrBlank() }?.let { studio ->
                    StudioBadge(
                        name = studio.name.orEmpty(),
                        enabled = studio.id > 0,
                        onClick = { onStudioClick(studio) },
                    )
                }
                Text(
                    text = listOfNotNull(
                        info.averageScore?.let { "$it% score" },
                        info.popularity?.takeIf { it > 0 }?.let { "${compactMemberCount(it)} members" },
                        if (isMovieFormat(info.format)) {
                            info.duration?.let { "$it min" }
                        } else {
                            info.episodes?.let { "$it episodes" }
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The animation studio as a tappable badge in the app's accent.
 *
 * It used to be a neutral outlined chip, which read as one more piece of metadata beside the score
 * and episode count — nothing about it said the name led to that studio's catalog. The accent fill
 * and the chevron are what make the tap discoverable.
 */
@Composable
private fun StudioBadge(name: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fillFocus2 = rememberFocusFill()
    Row(
        modifier = Modifier
            .padding(top = 5.dp)
            .focusFill(fillFocus2, StudioBadgeShape)
            .clip(StudioBadgeShape)
            .background(fillFocus2.background(tint.copy(alpha = 0.16f)))
            .border(1.dp, tint.copy(alpha = 0.55f), StudioBadgeShape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = "Browse $name titles", onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 10.dp, end = if (enabled) 4.dp else 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = fillFocus2.content(tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Studio names run long ("Studio Pierrot Film Kyoto"); the chevron keeps its place.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (enabled) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = fillFocus2.content(tint),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val StudioBadgeShape = RoundedCornerShape(50)

@Composable
private fun DetailActions(
    saved: Boolean,
    title: String,
    cover: String?,
    metadata: String?,
    listStatusLabel: String?,
    canWatch: Boolean,
    resolving: Boolean,
    resume: HistoryEntry?,
    format: String?,
    anilistId: Int,
    totalEpisodes: Int?,
    saveEntry: WatchlistEntry,
    onWatch: () -> Unit,
    backFocusRequester: FocusRequester,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val pad = LocalAppDeviceProfile.current.pagePadding
    // This button used to be a plain save toggle, which meant the only route from the detail page to
    // "watching", "paused", "finished" or "dropped" was to start an episode and use the player's
    // list picker. It opens the shared list menu instead — the same one a poster's long press opens
    // everywhere else — so every destination, and the save itself, is one press away from here.
    var listMenuVisible by remember(anilistId) { mutableStateOf(false) }
    if (listMenuVisible) {
        AnimeListActionsDialog(
            anilistId = anilistId,
            title = title,
            cover = cover,
            metadata = metadata,
            kicker = "This anime",
            onDismiss = { listMenuVisible = false },
            saveEntry = saveEntry,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val controlFocus4 = rememberFocusFill()
        OutlinedButton(
            colors = controlFocus4.buttonColors(ButtonDefaults.outlinedButtonColors()),
            onClick = { listMenuVisible = true },
            modifier = Modifier
                .weight(1f)
                .then(
                    if (!canWatch) Modifier.focusRequester(primaryActionFocusRequester)
                    else Modifier,
                )
                .onFocusChanged { state ->
                    if (state.isFocused || state.hasFocus) onPrimaryFocusAcquired()
                }
                .focusProperties { up = backFocusRequester }
                .focusFill(controlFocus4, RoundedCornerShape(24.dp)),
        ) {
            Icon(
                imageVector = if (saved || listStatusLabel != null) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                listStatusLabel ?: if (saved) "In library" else "Add to list",
                Modifier.padding(start = 6.dp),
                maxLines = 1,
            )
            // The chevron is the only thing distinguishing this from the toggle it replaced, and a
            // viewer who presses expecting a save still gets one, from the menu's own footer.
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 2.dp).size(18.dp),
            )
        }
        WatchedToggleButton(
            anilistId = anilistId,
            totalEpisodes = totalEpisodes,
            markEntry = saveEntry,
            modifier = Modifier,
        )
        val controlFocus6 = rememberFocusFill()
        Button(
            colors = controlFocus6.buttonColors(ButtonDefaults.buttonColors()),
            onClick = onWatch,
            enabled = canWatch,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (canWatch) Modifier.focusRequester(primaryActionFocusRequester)
                    else Modifier,
                )
                .onFocusChanged { state ->
                    if (state.isFocused || state.hasFocus) onPrimaryFocusAcquired()
                }
                .focusProperties { up = backFocusRequester }
                .focusFill(controlFocus6, RoundedCornerShape(24.dp)),
        ) {
            if (resolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = controlFocus6.content(MaterialTheme.colorScheme.onPrimary),
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(19.dp))
            }
            Text(
                text = if (isMovieFormat(format)) {
                    movieAwareWatchLabel(format, resume != null)
                } else if (resume != null) {
                    "Continue ${resume.episodeLabel}"
                } else {
                    "Watch"
                },
                modifier = Modifier.padding(start = 5.dp),
                maxLines = 1,
            )
        }
    }
}

/** Series-level progress like list apps show: how far through the whole anime the user is. */
@Composable
private fun SeriesWatchProgress(resume: HistoryEntry, totalEpisodes: Int, isMovie: Boolean) {
    if (totalEpisodes <= 0) return
    val watched = ((resume.episodeNumber - 1).coerceAtLeast(0.0) + resume.progressFraction).toFloat()
    val fraction = (watched / totalEpisodes).coerceIn(0f, 1f)
    val pad = LocalAppDeviceProfile.current.pagePadding
    Column(Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isMovie) "Movie progress" else "Episode ${resume.episodeLabel} of $totalEpisodes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        WatchProgressBar(
            fraction = fraction,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun DetailTabs(
    selected: DetailTab,
    tabs: List<DetailTab>,
    onSelect: (DetailTab) -> Unit,
    // The episode browser below is invisible to 2D focus search, so the way down is named.
    downFocus: FocusRequester? = null,
) {
    // Centred while the pills fit, so three short tabs do not hug the left edge of a wide row; the
    // box keeps the sticky header's canvas full width behind them. Too many to fit, they scroll.
    Box(
        modifier = Modifier.fillMaxWidth().appCanvas(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .then(downFocus?.let { target -> Modifier.focusProperties { down = target } } ?: Modifier)
                .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                val active = tab == selected
                val fillFocus4 = rememberFocusFill()
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = fillFocus4.content(if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier
                        .focusFill(fillFocus4, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            fillFocus4.background(if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface),
                        )
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun QuickFacts(info: Media) {
    val facts = listOfNotNull(
        info.status?.pretty()?.let { it to "Status" },
        info.format?.pretty()?.let { it to "Format" },
        info.seasonYear?.toString()?.let { it to "Year" },
        info.duration?.let { "$it min" to "Duration" },
    )
    // Centred like the tabs above while the cards fit; a fourth (Duration) on a narrow phone scrolls.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(facts) { (value, label) ->
            // At least 112dp, wider when the value needs it: at a fixed width "Not yet released"
            // wrapped, lost its second line to maxLines, and left "Not yet" at the card's left edge.
            Column(
                modifier = Modifier
                    .widthIn(min = 112.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun NextAiringCard(airingAt: Long, episode: Int?) {
    NextEpisodeCountdown(
        airingAtEpochSeconds = airingAt,
        episode = episode,
        modifier = Modifier.padding(
            horizontal = LocalAppDeviceProfile.current.pagePadding,
            vertical = 8.dp,
        ),
    )
}

@Composable
private fun GenreRow(genres: List<String>) {
    // Centred while the chips fit, scrolling once they do not — the same rule as the tabs.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            genres.forEach { genre ->
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Description(description: String?) {
    if (description.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    val clean = remember(description) { description.replace(Regex.cached("<[^>]*>"), "").trim() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp)
            .clickable { expanded = !expanded },
    ) {
        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = clean,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            text = if (expanded) "Show less" else "More",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * Hands the title's `anilili://anime/<id>` link to the share sheet, whose own Copy is the "copy
 * link" (Android 10 and later). Phones and tablets only: a television has nothing to share to.
 *
 * The link opens this page in either Anilili app. Most chat apps don't make a custom scheme
 * tappable, so on the other end it is often something to paste rather than to tap.
 */
private fun shareAnimeLink(context: Context, animeId: Int, title: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, AnililiLink.Anime(animeId).toUrl())
        title?.let { putExtra(Intent.EXTRA_TITLE, it) }
    }
    runCatching { context.startActivity(Intent.createChooser(send, title ?: "Share link")) }
        .onFailure { DiagnosticsLog.throwable("Detail share link failed id=$animeId", it) }
}

/**
 * The official trailer, opened in whatever app claims the link — YouTube, normally.
 *
 * Not played in-app: YouTube refuses its embed in a WebView without an origin it trusts, and the
 * YouTube app is on nearly every phone anyway. The still is AniList's hqdefault, which is 4:3 with
 * letterbox bars baked in, so it is cropped to 16:9 rather than fitted.
 */
@Composable
private fun TrailerCard(trailer: Trailer) {
    val url = trailer.watchUrl ?: return
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp),
    ) {
        Text("Trailer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure { error ->
                        AnililiError.BROWSER_MISSING.report(
                            cause = error,
                            attributes = mapOf("surface" to "detail-trailer"),
                        )
                        Toast.makeText(context, "No app here can play the trailer.", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (trailer.thumbnail != null) {
                AsyncImage(
                    model = trailer.thumbnail,
                    contentDescription = "Trailer",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun MetadataCard(info: Media) {
    val rows = listOfNotNull(
        info.startDate.display()?.let { "Start date" to it },
        info.endDate.display()?.let { "End date" to it },
        info.season?.pretty()?.let { "Season" to it },
        // A head count, not a rank: the old "#123456" read as the title's position on a chart.
        info.popularity?.let { "Members" to String.format(java.util.Locale.US, "%,d", it) },
        info.favourites?.let { "Favorites" to it.toString() },
    )
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Season chips for series with multiple episodic entries (JJK-style): the whole prequel/sequel
 * chain lives on one page and this row swaps which season's episodes are listed below.
 */
@Composable
private fun SeasonFilterRow(
    seasons: List<Media>,
    labels: Map<Int, String>,
    selectedSeasonId: Int,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = LocalAppDeviceProfile.current.pagePadding,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(seasons.size) { index ->
            val season = seasons[index]
            val active = season.id == selectedSeasonId
            val choosable = seasonChoosable(season, selectedSeasonId)
            val fillFocus5 = rememberFocusFill()
            Column(
                modifier = Modifier
                    // An upcoming season is shown, faded, and cannot be chosen; see seasonChoosable.
                    .alpha(if (choosable) 1f else 0.38f)
                    .focusFill(fillFocus5, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        fillFocus5.background(if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface),
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = choosable) { onSelect(season.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = labels[season.id] ?: "Season ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = fillFocus5.content(if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface),
                )
                (season.seasonYear ?: season.startDate?.year)?.let { year ->
                    Text(
                        text = if (season.isUpcoming()) "$year · Upcoming" else year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = fillFocus5.content(if (active) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

/**
 * The line above the episode list: what is on screen, and the way to download it in one go.
 *
 * It also carries the shared batch status, so a run started here — or in the player — reports
 * itself against the list it belongs to rather than only in My List.
 */
@Composable
private fun EpisodeListActions(
    shownCount: Int,
    pendingCount: Int,
    preparing: Boolean,
    onDownload: () -> Unit,
) {
    val pad = LocalAppDeviceProfile.current.pagePadding
    Column(Modifier.fillMaxWidth().padding(horizontal = pad)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(if (shownCount == 1) "1 episode" else "$shownCount episodes")
                    val held = shownCount - pendingCount
                    if (held > 0) append(" · $held downloaded")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val controlFocus9 = rememberFocusFill()
            TextButton(
                colors = controlFocus9.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = onDownload,
                // A saved sub copy must not prevent opening the dialog to download the dub.
                enabled = shownCount > 0 && !preparing,
                modifier = Modifier.focusFill(controlFocus9, RoundedCornerShape(20.dp)),
            ) {
                if (preparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = controlFocus9.content(MaterialTheme.colorScheme.primary),
                    )
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    text = "Download",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        BulkDownloadStatus()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailEpisodeRow(
    episode: EpisodeItem,
    fallbackImage: String?,
    blurred: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    watchedFraction: Float = 0f,
    /** Whether the episode carries a watched mark, for the eye in the row's top corner. */
    watched: Boolean = false,
    onToggleWatched: (() -> Unit)? = null,
    downloadState: EpisodeDownloadUi? = null,
    /** Pressed, but no provider has answered yet — there is no download to describe. */
    downloadPending: Boolean = false,
    onDownloadAction: ((EpisodeDownloadAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
    unaired: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 6.dp)
            .alpha(if (unaired) UNAIRED_ALPHA else 1f)
            .then(
                if (unaired) {
                    // No click and no focus stop: the row is here to say the episode exists and
                    // when it lands, not to be opened.
                    Modifier.semantics { disabled() }
                } else {
                    Modifier
                        .focusHighlight(RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(vertical = 4.dp)
            // As tall as the artwork, so the download control can sit in the row's bottom corner.
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            EpisodeArtwork(
                image = episodeArtworkImage(episode.image, fallbackImage),
                blurred = blurred,
                modifier = Modifier.fillMaxSize(),
            )
            if (episode.filler) {
                EpisodeFillerBadge(
                    compact = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                )
            }
            com.anilili.ui.components.PosterTag(
                text = "EP ${episode.displayNumber}",
                modifier = Modifier
                    .align(if (watchedFraction > 0.01f) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(5.dp),
            )
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
            DownloadCoverBadge(
                state = downloadState,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)),
                compact = true,
            )
        }
        // The title takes the whole width beside the artwork; the download control tucks into the
        // bottom-right corner under it, where only the short subtitle line could reach, and the eye
        // sits in the corner above it.
        val showDownload = !unaired && onDownloadAction != null
        // Nothing to mark on an episode nobody can watch yet, for the same reason the row has no
        // click: it is here to say the episode exists and when it lands.
        val showEye = !unaired && onToggleWatched != null
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 13.dp)) {
            Column(Modifier.align(Alignment.CenterStart)) {
                val title = episode.distinctTitle
                Text(
                    text = title ?: "Episode ${episode.displayNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Clear of the eye sharing the top of the row. The whole block is inset rather
                    // than its first line alone, which a single Text cannot do — and a title that
                    // wrapped past the icon anyway would run under the pressed mark.
                    modifier = Modifier.padding(end = if (showEye) EYE_TITLE_INSET else 0.dp),
                )
                // An unreleased row says when instead of repeating the number the thumbnail already
                // shows — "greyed out" on its own reads as broken, and the date is the answer to the
                // question being asked.
                val subtitle = when {
                    unaired -> unairedEpisodeLabel(episode.airDate)
                    title != null -> "Episode ${episode.displayNumber}" + if (episode.dubbed) "  •  Dub" else ""
                    episode.dubbed -> "Dub"
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Clear of the download control sharing the bottom of the row.
                        modifier = Modifier.padding(top = 3.dp, end = if (showDownload) 34.dp else 0.dp),
                    )
                }
            }
            if (showEye) {
                EpisodeWatchedToggle(
                    watched = watched,
                    onToggle = onToggleWatched,
                    // Mirrors the download control: pulled out past the row's edges by its own touch
                    // padding, so the icon lines up with the row's top-right corner while the tap
                    // target stays whole. The two are then stacked in one column with nothing
                    // between them — at this row height a pair left inside the edges would overlap,
                    // and the download, drawn later, would answer for the eye's lower half.
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-11).dp),
                )
            }
            // Nothing to download from an episode that has not aired, so no control is offered.
            if (showDownload) {
                EpisodeDownloadButton(
                    state = downloadState,
                    pending = downloadPending,
                    onAction = onDownloadAction!!,
                    // Pulled out by the button's own touch padding, so the icon itself lines up with
                    // the row's right and bottom edges while the whole 40dp stays pressable.
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 11.dp, y = 11.dp),
                )
            }
        }
    }
}

/**
 * What an unreleased row says under its title: the air date when a catalogue stated one, and a
 * plain "Not released yet" when the only thing known is that AniList has not reached it.
 */
private fun unairedEpisodeLabel(airDate: String?): String {
    val day = episodeAirDate(airDate) ?: return "Not released yet"
    return "Airs ${day.format(UNAIRED_DATE_FORMAT)}"
}

private val UNAIRED_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * How much of an episode row's title is inset to clear the watched eye in its top corner.
 *
 * The eye's icon is 18dp wide and sits 4dp in from the row's right edge, plus a 4dp gap. A whole
 * block rather than the first line, because one [androidx.compose.material3.Text] has no way to
 * inset a line of its own.
 */
private val EYE_TITLE_INSET = 26.dp

@Composable
private fun DetailSectionHeader(
    title: String,
    trailing: String? = null,
    /** A control at the end of the header, after the count. */
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LocalAppDeviceProfile.current.pagePadding,
                end = LocalAppDeviceProfile.current.pagePadding,
                top = 14.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}

/**
 * The shelf chips of the Related tab. Deliberately the same pill as [DetailTabs] one size down, so
 * the row below the tabs reads as a narrowing of the tab rather than a second navigation.
 */
@Composable
private fun RelatedFilterRow(
    filters: List<RelatedFilter>,
    selected: RelatedFilter,
    onSelect: (RelatedFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = LocalAppDeviceProfile.current.pagePadding,
            vertical = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(filters, key = { it.name }) { filter ->
            val active = filter == selected
            val fillFocus9 = rememberFocusFill()
            Text(
                text = filter.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = fillFocus9.content(if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .focusFill(fillFocus9, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        fillFocus9.background(if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface),
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * One entry of the watch guide: its place in the running order, its artwork, and what it is.
 *
 * The number is the point of the row — it is what makes a shelf of films answer "and when do I
 * watch these", which a bare list of titles never did. The page's own entry is listed too, marked
 * and inert: leaving it out made the numbering skip, and opening it would push a second copy of
 * the page onto the back stack.
 */
@Composable
private fun WatchOrderRow(entry: WatchOrderEntry, onOpen: () -> Unit, onWatch: () -> Unit) {
    val media = entry.media
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    val meta = entry.metaLine()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 5.dp)
            .focusHighlight(shape)
            .clip(shape)
            .background(
                if (entry.isCurrent) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
            )
            .border(1.dp, if (entry.isCurrent) accent else MaterialTheme.colorScheme.outline, shape)
            .then(if (entry.isCurrent) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (entry.isCurrent) accent else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.position.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.isCurrent) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Same fallback chain as RelatedRow: AniList has covers it does not always hand to a
        // relation node, and an entry without one drew as a hole beside its title.
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .width(48.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = media.coverImage.best ?: media.bannerImage
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = media.title.preferred,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                media.title.preferred,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (entry.isCurrent) {
                Text(
                    "You are here",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        val controlFocus15 = rememberFocusFill()
        IconButton(
            colors = controlFocus15.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onWatch, modifier = Modifier.focusFill(controlFocus15, RoundedCornerShape(20.dp))) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Watch ${media.title.preferred}")
        }
    }
}

/** Switches the Related tab between rows with artwork and bare titles. */
@Composable
private fun RelatedLayoutToggle(layout: RelatedLayout, onToggle: () -> Unit) {
    val showsTitlesNext = layout == RelatedLayout.LIST
    val controlFocus = rememberFocusFill()
    IconButton(
        colors = controlFocus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = onToggle,
        modifier = Modifier.size(40.dp).focusFill(controlFocus, CircleShape),
    ) {
        // The glyph shows the layout the press switches TO, as the episode toggle's does.
        Icon(
            imageVector = if (showsTitlesNext) Icons.Default.ViewHeadline else Icons.Default.ViewAgenda,
            contentDescription = if (showsTitlesNext) "Show titles only" else "Show with artwork",
        )
    }
}

/**
 * The Related tab's text-only row: the title and one line of format, year and episode count. Watch
 * guide entries keep their running-order number, and the page's own entry its accent, because those
 * are what the shelf is for; the artwork and the play button are what this view leaves out.
 */
@Composable
private fun RelatedTitleRow(
    title: String,
    meta: String,
    onOpen: () -> Unit,
    position: Int? = null,
    isCurrent: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 3.dp)
            .focusHighlight(shape)
            .clip(shape)
            .background(if (isCurrent) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (isCurrent) accent else MaterialTheme.colorScheme.outline, shape)
            .then(if (isCurrent) Modifier else Modifier.clickable(onClick = onOpen))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (position != null) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(26.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val line = if (isCurrent) listOf(meta, "You are here").filter(String::isNotEmpty).joinToString(" · ") else meta
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RelatedRow(media: Media, onOpen: () -> Unit, onWatch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 6.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fall back to the banner, then to a plain tile. A related entry that reaches this row
        // without a cover — reported on One Piece's "MONSTERS: 103 Mercies Dragon Damnation",
        // whose art AniList does have — otherwise drew as an empty hole with a title beside it.
        Box(
            modifier = Modifier
                .width(58.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = media.coverImage.best ?: media.bannerImage
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = media.title.preferred,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(media.title.preferred, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(media.format?.pretty(), media.seasonYear?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val controlFocus17 = rememberFocusFill()
        IconButton(
            colors = controlFocus17.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onWatch, modifier = Modifier.focusFill(controlFocus17, RoundedCornerShape(20.dp))) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Watch ${media.title.preferred}")
        }
    }
}

@Composable
private fun InlineStatus(message: String, loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) com.anilili.ui.components.WaterFillLogoIndicator(size = 48.dp)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * An AniList enum as a label: `NOT_YET_RELEASED` to "Not yet released", `TV` to "TV".
 *
 * Sentence case over the whole string, except that an acronym stays an acronym. Lowercasing
 * everything and capitalising the first letter is right for a status and wrong for a format —
 * "Tv" is not a word, and it sat in the facts row next to "ONA" and "OVA" written correctly by
 * every other screen, which take the raw value.
 */
internal fun String.pretty(): String = split('_')
    .mapIndexed { index, word ->
        when {
            word.uppercase() in MEDIA_ACRONYMS -> word.uppercase()
            index == 0 -> word.lowercase().replaceFirstChar { it.uppercase() }
            else -> word.lowercase()
        }
    }
    .joinToString(" ")

/** AniList format values that are initialisms rather than words. */
private val MEDIA_ACRONYMS = setOf("TV", "ONA", "OVA")

private fun FuzzyDate?.display(): String? {
    this ?: return null
    val values = listOfNotNull(month?.toString(), day?.toString(), year?.toString())
    return values.takeIf { it.isNotEmpty() }?.joinToString("/")
}

/** How long the download dialog waits on a settled choice before resolving a server to check it. */

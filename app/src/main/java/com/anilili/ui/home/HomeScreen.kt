package com.anilili.ui.home





import com.anilili.ui.theme.canvasColor
import com.anilili.ui.theme.appCanvasFade
import com.anilili.ui.theme.appCanvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.anilili.data.settings.SettingsStore
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.anilili.ui.tour.RegisterTourScroller
import com.anilili.ui.tour.StartWhatsNewTourWhenSettled
import com.anilili.ui.tour.TourTarget
import com.anilili.ui.tour.tourTarget
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anilili.R
import com.anilili.data.auth.AccountService
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.NextEpisodeRelease
import com.anilili.data.library.nextReleaseLabel
import com.anilili.data.library.spokenNextReleaseLabel
import com.anilili.data.AppGraph
import com.anilili.data.model.Media
import com.anilili.diagnostics.DiagnosticTrigger
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsShareManager
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.CaptionedCardShape
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.OnTvRefreshRequest
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.TvRefreshRequest
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AnililiWordmark
import com.anilili.ui.components.AnimeCard
import com.anilili.ui.components.AppChromeListBottomGap
import com.anilili.ui.components.CardCornerAction
import com.anilili.ui.components.ContinueWatchingActionsDialog
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.components.SeriesLogo
import com.anilili.ui.components.ShrinkingTitle
import com.anilili.ui.components.TvTopNavigationHeight
import com.anilili.ui.components.chromeFade
import com.anilili.ui.components.wideHeroArtwork
import com.anilili.ui.search.SearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.anilili.ui.components.backdropBlurAvailable
import com.anilili.ui.components.backdropBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.glassEnabled
import com.anilili.ui.dialogs.MidnightConfirmDialog

private const val HERO_AUTO_ADVANCE_MS = 7_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAnimeClick: (Int) -> Unit,
    onWatchNow: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    onSearchClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onGenreClick: (String?) -> Unit,
    onNotificationsClick: () -> Unit,
    tvPrimaryFocusTarget: TvFocusTarget? = null,
    /** Raised by a hold on the television's Home tab; a phone refreshes by pulling instead. */
    tvRefreshRequest: TvRefreshRequest? = null,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(),
) {
    OnTvRefreshRequest(tvRefreshRequest) { vm.refresh() }
    val state by vm.state.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val exhausted by vm.exhausted.collectAsState()
    val personal by vm.personal.collectAsState()
    // The ViewModel's copy, not the store's: Home is where a title whose next episode is still a day
    // away steps aside, and the two screens that draw this row take it from here.
    val history by vm.continueWatching.collectAsState()
    // The same schedule the row is filtered by, reduced to each title's next episode: the cards say
    // how long the wait is without opening the show.
    val nextReleases by vm.nextReleases.collectAsState()
    val device = LocalAppDeviceProfile.current
    val storedLayout by SettingsStore.homeLayout.collectAsState()
    val layout = remember(storedLayout, device.isTv) { HomeLayout.decode(storedLayout, device.isTv) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    if (arranging) HomeLayoutDialog(onDismiss = { arranging = false })
    val context = androidx.compose.ui.platform.LocalContext.current
    val diagnosticsScope = rememberCoroutineScope()
    var slowStartup by remember { mutableStateOf(false) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }

    // Continue Watching lives on this screen, and the account was only consulted in
    // MainActivity.onStart — so a device left running showed progress from the moment it was
    // last launched. Finish an episode on the phone and the television, still foregrounded on
    // Home, kept offering the episode before it until the app was restarted. Coming back to Home
    // is the moment the viewer expects the row to be current. The 30s throttle inside the store
    // absorbs both the repeat at cold start and any rapid tab-switching.
    LaunchedEffect(Unit) { LibraryStore.refreshRemoteLibrary() }

    LaunchedEffect(state) {
        slowStartup = false
        diagnosticsMessage = null
        if (state is UiState.Loading) {
            delay(10_000)
            slowStartup = true
            DiagnosticsLog.event("Home still loading after 10 seconds")
        }
    }

    if (device.isTv) {
        when (val s = state) {
            is UiState.Loading -> {
                if (slowStartup) {
                    StartupStillLoading(
                        message = diagnosticsMessage,
                        onRetry = { vm.load(force = true) },
                        onShareDiagnostics = {
                            diagnosticsScope.launch {
                                diagnosticsMessage = "Preparing diagnostics…"
                                diagnosticsMessage = DiagnosticsShareManager
                                    .share(context, DiagnosticTrigger.SLOW_START)
                                    .userMessage()
                            }
                        },
                        modifier = modifier.padding(top = TvTopNavigationHeight),
                    )
                } else {
                    LoadingBox(modifier.padding(top = TvTopNavigationHeight))
                }
            }
            is UiState.Error -> ErrorBox(
                s.failure,
                vm::load,
                modifier.padding(top = TvTopNavigationHeight),
            )
            is UiState.Success -> PullRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = vm::refresh,
                modifier = modifier.fillMaxSize(),
                // This box spans the window so rows pass under the bar, which puts its top edge
                // *behind* the navigation. Left at zero the spinner is drawn up there, hidden, and
                // a hold on the Home tab looks like it did nothing at all.
                indicatorTopPadding = TvTopNavigationHeight,
            ) {
                TvHomeContent(
                    data = s.data,
                    personal = personal,
                    layout = layout,
                    history = history,
                    nextReleases = nextReleases,
                    onAnimeClick = onAnimeClick,
                    onResume = onResume,
                    onOpenDetails = onOpenDetails,
                    loadingMore = loadingMore,
                    onLoadMore = vm::loadMore,
                    primaryActionFocusTarget = tvPrimaryFocusTarget,
                )
            }
        }
        return
    }

    // The chrome only gets to be a bare gradient while the spotlight is what lies beneath it. The
    // hero carries its own scrim and is dark where the wordmark sits; a poster grid is not, and the
    // first version of this had the mark landing on One Piece's sky with a score badge showing
    // through it. Held here rather than inside the bar because the list it watches lives further
    // down the tree than the scaffold slot that draws it.
    val homeListState = rememberLazyListState()
    val hasSpotlight = (state as? UiState.Success)?.data?.spotlight?.isNotEmpty() == true
    // What the header and its genre pills frost: the feed that scrolls under them. Null with Home's
    // glass switched off, which takes the recording away along with the blur.
    val homeGlass = glassEnabled(GlassSection.HOME)
    val homeBackdrop = if (homeGlass) remember { HazeState() } else null
    val chromeOverHero by remember(hasSpotlight) {
        derivedStateOf { hasSpotlight && homeListState.firstVisibleItemIndex == 0 }
    }

    Scaffold(
        modifier = modifier.appCanvas(),
        containerColor = canvasColor(),
        topBar = {
            ScrollAwareTopBar {
                HomeTopChrome(
                    showWordmark = !device.useNavigationRail,
                    overHero = chromeOverHero,
                    onNotificationsClick = onNotificationsClick,
                    onSearchClick = onSearchClick,
                    onScheduleClick = onScheduleClick,
                    onGenreClick = onGenreClick,
                    backdrop = homeBackdrop,
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            is UiState.Loading -> {
                if (slowStartup) {
                    StartupStillLoading(
                        message = diagnosticsMessage,
                        onRetry = { vm.load(force = true) },
                        onShareDiagnostics = {
                            diagnosticsScope.launch {
                                diagnosticsMessage = "Preparing diagnostics…"
                                diagnosticsMessage = DiagnosticsShareManager
                                    .share(context, DiagnosticTrigger.SLOW_START)
                                    .userMessage()
                            }
                        },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LoadingBox(Modifier.padding(padding))
                }
            }
            is UiState.Error -> ErrorBox(s.failure, vm::load, Modifier.padding(padding))
            // The grid fills the window and reserves the chrome as scroll padding, so rows pass
            // under the bars as they retreat instead of being shunted about by them.
            is UiState.Success -> PullRefreshContainer(
                isRefreshing = isRefreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize().then(if (homeBackdrop != null) Modifier.hazeSource(homeBackdrop) else Modifier),
                // This box spans the window so rows scroll under the bars; without this the
                // spinner would sit behind the header where nobody can see it.
                indicatorTopPadding = padding.calculateTopPadding(),
            ) {
                HomeContent(
                    data = s.data,
                    personal = personal,
                    layout = layout,
                    onArrange = { arranging = true },
                    onLoadMore = vm::loadMore,
                    loadingMore = loadingMore,
                    exhausted = exhausted,
                    history = history,
                    nextReleases = nextReleases,
                    onAnimeClick = onAnimeClick,
                    onResume = onResume,
                    onOpenDetails = onOpenDetails,
                    listState = homeListState,
                    contentPadding = padding,
                )
            }
        }
    }
}

/**
 * Home's top chrome — wordmark, notifications, search, and the genre shortcuts under them — drawn
 * as a fade over the artwork rather than as a band above it.
 *
 * This is the television header brought to the phone. There, the bar has always been a gradient
 * laid over the hero: the mark stays legible because the fade is darkest exactly where the type
 * sits, and the artwork still runs unbroken to the top edge of the screen. The phone had the
 * opposite — an opaque background-coloured bar with a row of pills on the same flat black under it,
 * which spent about a sixth of the window before a single frame of the spotlight appeared and made
 * the hero read as a picture pasted into the page rather than as the page itself. §2.1: the content
 * is the hero, the chrome is what stays quiet.
 *
 * The block keeps bar and pills in one layer so [ScrollAwareTopBar] takes them away together.
 * Pinning the bar and letting the pills scroll reads as two things leaving at two speeds.
 *
 * The header uses the same transparent wordmark as television. Keeping it as one image prevents
 * the launcher mark and live type from drifting apart in size, weight, or spacing again.
 *
 * [overHero] is what keeps the fade honest. Off the hero, a backing in the page's own colour comes
 * up underneath and the bar goes back to being a bar — translucent chrome over a grid of posters is
 * illegible, not clean. It still lets its last stretch go to nothing, so the edge stays soft and
 * rows pass under it instead of being cut off by a line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopChrome(
    showWordmark: Boolean,
    overHero: Boolean,
    onNotificationsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onGenreClick: (String?) -> Unit,
    backdrop: HazeState? = null,
) {
    Column(Modifier.fillMaxWidth().chromeFade(overHero, backdrop)) {
        TopAppBar(
            title = {
                if (showWordmark) {
                    AnililiWordmark(modifier = Modifier.height(22.dp))
                }
            },
            actions = {
                val unread by com.anilili.data.reminder.NotificationCenter.unread.collectAsState()
                val controlFocus1 = rememberFocusFill()
                IconButton(
                    colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onNotificationsClick,
                    modifier = Modifier.focusFill(controlFocus1, CircleShape),
                ) {
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (unread > 0) {
                                androidx.compose.material3.Badge {
                                    Text(if (unread > 99) "99+" else unread.toString())
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                        )
                    }
                }
                // Schedule, where search used to be. Search left the navigation capsule for a disc
                // of its own beside it, and this is the space that freed up — Schedule is a view of
                // what is airing, which is Home's subject, so it reads better hanging off Home than
                // as a fifth destination in the bar.
                val controlFocus2 = rememberFocusFill()
                IconButton(
                    colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onScheduleClick,
                    modifier = Modifier.focusFill(controlFocus2, CircleShape),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = "Airing schedule")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        GenrePillRow(
            onGenreClick = onGenreClick,
            backdrop = backdrop,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun StartupStillLoading(
    message: String?,
    onRetry: () -> Unit,
    onShareDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.anilili.ui.components.WaterFillLogoIndicator(size = 72.dp)
            Text(
                "Still loading",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "If the screen stays blank, send diagnostics from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val controlFocus3 = rememberFocusFill()
                TextButton(
                    colors = controlFocus3.buttonColors(ButtonDefaults.textButtonColors()),onClick = onRetry, modifier = Modifier.focusFill(controlFocus3, RoundedCornerShape(20.dp))) {
                    Text("Retry")
                }
                val controlFocus4 = rememberFocusFill()
                Button(
                    colors = controlFocus4.buttonColors(ButtonDefaults.buttonColors()),onClick = onShareDiagnostics, modifier = Modifier.focusFill(controlFocus4, RoundedCornerShape(20.dp))) {
                    Text("Send diagnostics")
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    personal: PersonalRows,
    layout: HomeLayout,
    onArrange: () -> Unit,
    onLoadMore: (HomeSection) -> Unit,
    loadingMore: Set<HomeSection>,
    exhausted: Set<HomeSection>,
    history: List<HistoryEntry>,
    nextReleases: Map<Int, NextEpisodeRelease>,
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val device = LocalAppDeviceProfile.current
    val chromeBottomInset = LocalAppChromeBottomInset.current
    val continueFocusRequester = remember { FocusRequester() }
    val showContinue = HomeRow.CONTINUE in layout.visible && history.isNotEmpty()
    // One rail at a time may be laid out as a grid, held by name so it survives a rotation. Letting
    // several expand at once turns the feed into several screens of poster grid with a rail lost
    // somewhere between them, which is the state the tabs existed to prevent.
    var expandedRail by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(data, history.size) {
        DiagnosticsLog.event(
            "HomeContent rendered spotlight=${data.spotlight.size} history=${history.size}",
        )
    }
    val gridSpacing = if (device.isTv) 16.dp else 9.dp

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val available = maxWidth - device.pagePadding * 2f
        // Rails carry the poster at its own width rather than at a share of the window: a rail is
        // read across, so the card only has to be legible, not to tile. The grid an expanded rail
        // opens is what the column count is for, and it comes from the profile so Home lands on the
        // same poster grid Search and Browse use instead of inventing a second one.
        val columns = if (device.isTv) 7 else device.posterColumns
        val cardWidth = if (device.isTv) {
            device.posterWidth
        } else {
            minOf(device.posterWidth, available / 2.2f)
        }

        // The hero starts at the very top of the window and wears the chrome's height as its own
        // padding, so the artwork runs behind the fade instead of starting below it. Scroll padding
        // is only reserved when there is no spotlight to run underneath — without that, a feed that
        // came back without one would open with its first row already behind the wordmark.
        val spotlight = data.spotlight.take(6)
        val topOverlay = contentPadding.calculateTopPadding()

        // The rows drawn right now, each with its LazyColumn key: shown, and with something in
        // them. While a row is held, in the order the drag has made so far (see HomeRowDrag.kt).
        val drawnKeys = remember(layout, showContinue, personal, data) {
            HomeRow.entries.associateWith { row ->
                when (row) {
                    HomeRow.CONTINUE -> "continue".takeIf { showContinue }
                    HomeRow.FOR_YOU -> "for-you".takeIf { personal.forYou.isNotEmpty() }
                    HomeRow.BECAUSE -> personal.because?.seed?.let { "because-${it.id}" }
                    else -> row.catalogSection?.takeIf { data.section(it).isNotEmpty() }?.name
                }
            }
        }
        val drag = rememberHomeRowDragState(
            listState = listState,
            keyToRow = drawnKeys.entries.mapNotNull { (row, key) -> key?.let { it to row } }.toMap(),
            topInset = topOverlay,
            bottomInset = contentPadding.calculateBottomPadding() + chromeBottomInset,
        )
        val shown = drag.order ?: layout
        val drawnRows = shown.visible.filter { drawnKeys[it] != null }

        // What's new (ui/tour): starts once Home has settled, and scrolls the row a step points at
        // to just below the top bar. Item 0 is the spotlight; the rows follow in drawn order.
        StartWhatsNewTourWhenSettled()
        val tourTopPx = with(LocalDensity.current) { (topOverlay + 12.dp).roundToPx() }
        RegisterTourScroller { target ->
            val row = when (target) {
                TourTarget.FOR_YOU -> HomeRow.FOR_YOU
                TourTarget.BECAUSE -> HomeRow.BECAUSE
                TourTarget.ROW_HANDLE -> drawnRows.firstOrNull()
                else -> null
            }
            val at = row?.let(drawnRows::indexOf)?.takeIf { it >= 0 } ?: return@RegisterTourScroller
            listState.animateScrollToItem(1 + at, -tourTopPx)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = if (spotlight.isEmpty()) topOverlay else 0.dp,
                    bottom = contentPadding.calculateBottomPadding() + chromeBottomInset +
                        AppChromeListBottomGap,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    HeroPager(
                        items = spotlight,
                        onAnimeClick = onAnimeClick,
                        topInset = topOverlay,
                        onMoveDown = if (showContinue) {
                            { runCatching { continueFocusRequester.requestFocus() }.isSuccess }
                        } else {
                            null
                        },
                    )
                }
                // In the viewer's order (Settings → Arrange Home). Every catalogue list is a rail:
                // three of them used to share one strip of tabs and a grid underneath, which meant
                // two of the three were always invisible. Expand is what the tabs were for — it lays
                // a single rail out as the same paged grid, so nothing reachable before is gone.
                // Hold a row's title (or its handle) to drag the row to another place; see
                // HomeRowDrag.kt. Rows animate their moves only while a drag is on, so loading and
                // expanding behave exactly as they did.
                drawnRows.forEach { row ->
                    val key = drawnKeys.getValue(row)!!
                    item(key = key) {
                        val handle = Modifier.homeRowDragHandle(drag, row, key, layout, drawnRows)
                            // The first row's title line, handle and all, is what the tour's
                            // "Make Home yours" step points at.
                            .then(if (row == drawnRows.first()) Modifier.tourTarget(TourTarget.ROW_HANDLE) else Modifier)
                        Box(
                            Modifier
                                .then(
                                    when (row) {
                                        HomeRow.FOR_YOU -> Modifier.tourTarget(TourTarget.FOR_YOU)
                                        HomeRow.BECAUSE -> Modifier.tourTarget(TourTarget.BECAUSE)
                                        else -> Modifier
                                    },
                                )
                                .animateItem(
                                    fadeInSpec = null,
                                    placementSpec = if (drag.active && !drag.isLifted(row)) {
                                        spring(stiffness = Spring.StiffnessMediumLow)
                                    } else {
                                        null
                                    },
                                    fadeOutSpec = null,
                                )
                                .homeRowDragItem(drag, row),
                        ) {
                            when (row) {
                                HomeRow.CONTINUE -> ContinueRail(
                                    history.take(12),
                                    nextReleases,
                                    onResume,
                                    onOpenDetails,
                                    continueFocusRequester,
                                    dragHandle = handle,
                                )
                                HomeRow.FOR_YOU -> MediaRail(
                                    title = row.label(isTv = false),
                                    media = personal.forYou,
                                    onAnimeClick = onAnimeClick,
                                    cardWidth = cardWidth,
                                    dragHandle = handle,
                                )
                                HomeRow.BECAUSE -> personal.because?.let { because ->
                                    MediaRail(
                                        title = "Because you watched ${because.seed?.title?.preferred.orEmpty()}",
                                        media = because.items,
                                        onAnimeClick = onAnimeClick,
                                        cardWidth = cardWidth,
                                        dragHandle = handle,
                                    )
                                }
                                else -> row.catalogSection?.let { section ->
                                    MediaRail(
                                        title = row.label(isTv = false),
                                        media = data.section(section),
                                        onAnimeClick = onAnimeClick,
                                        cardWidth = cardWidth,
                                        isExpanded = expandedRail == section.name,
                                        onToggleExpand = {
                                            expandedRail = section.name.takeIf { it != expandedRail }
                                        },
                                        isLoadingMore = section in loadingMore,
                                        onLoadMore = { onLoadMore(section) }.takeIf { section !in exhausted },
                                        columns = columns,
                                        gridSpacing = gridSpacing,
                                        dragHandle = handle,
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "arrange-home") {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        val arrangeFocus = rememberFocusFill()
                        TextButton(
                            colors = arrangeFocus.buttonColors(ButtonDefaults.textButtonColors()),
                            onClick = onArrange,
                            modifier = Modifier.focusFill(arrangeFocus, RoundedCornerShape(50)),
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Arrange Home")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Genre shortcuts pinned to the top of the home feed, one horizontally scrolling line. "All"
 * (null genre) returns to the unfiltered browse tab; every other pill opens Search with that
 * genre pre-applied, mirroring the studio-navigation pattern.
 */
@Composable
private fun GenrePillRow(
    onGenreClick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
) {
    val device = LocalAppDeviceProfile.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = device.pagePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            GenrePill(label = "All", highlighted = true, onClick = { onGenreClick(null) })
        }
        items(SearchViewModel.DEFAULT_GENRES, key = { it }) { genre ->
            GenrePill(label = genre, highlighted = false, onClick = { onGenreClick(genre) }, backdrop = backdrop)
        }
    }
}

/**
 * One genre shortcut.
 *
 * The unselected fill is smoked glass rather than [MaterialTheme.colorScheme.surface]: these pills
 * no longer sit on flat background, they sit on whatever the spotlight happens to be showing, and
 * an opaque surface chip over cover art reads as a hole punched in the picture. The type is white
 * for the same reason — `onSurfaceVariant` is mixed for a dark panel behind it and greys out over a
 * bright frame. Purple still means selected, per §2.2, and keeps its solid fill, so the chosen pill
 * is the one thing in the row that is not translucent.
 */
@Composable
private fun GenrePill(label: String, highlighted: Boolean, onClick: () -> Unit, backdrop: HazeState? = null) {
    val shape = RoundedCornerShape(50)
    // Frosted on Android 12+: the blur does the separating the heavier smoke did, so the tint over
    // it can be lighter and more of the spotlight's colour comes through.
    val frosted = !highlighted && backdrop != null && backdropBlurAvailable
    val fillFocus1 = rememberFocusFill()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = fillFocus1.content(if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = .92f)),
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier
            .focusFill(fillFocus1, shape)
            .clip(shape)
            .then(if (frosted) Modifier.backdropBlur(backdrop, tint = Color.Black.copy(alpha = .30f)) else Modifier)
            .background(
                fillFocus1.background(
                    when {
                        highlighted -> MaterialTheme.colorScheme.primary
                        frosted -> Color.Transparent
                        else -> Color.Black.copy(alpha = .52f)
                    },
                ),
            )
            .border(
                1.dp,
                if (highlighted) Color.Transparent else Color.White.copy(alpha = .18f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The spotlight carousel.
 *
 * [topInset] is the height of the chrome the hero runs beneath on the phone. It is added to the hero
 * rather than taken out of it: the artwork gains that much height so the title block underneath
 * keeps the room it had, and the corner pills are pushed clear of the genre row so nothing lands on
 * top of anything. Passing 0 leaves the hero exactly as it was, which is what the television gets.
 */
@Composable
private fun HeroPager(
    items: List<Media>,
    onAnimeClick: (Int) -> Unit,
    onMoveDown: (() -> Boolean)?,
    topInset: Dp = 0.dp,
) {
    if (items.isEmpty()) return
    val device = LocalAppDeviceProfile.current
    val pagerState = rememberPagerState(pageCount = { items.size })
    val heroIds = items.map(Media::id)
    var tvPage by remember(heroIds) { mutableIntStateOf(0) }
    var tvHeroHasFocus by remember(heroIds) { mutableStateOf(false) }
    val safeTvPage = tvPage.coerceIn(0, items.lastIndex)
    val pagerIsDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val heroHeight = topInset + when {
        device.isTv -> 420.dp
        device.isExpanded -> 360.dp
        device.isTablet -> 320.dp
        // Fifteen per cent taller than the 270dp this used to be, counted across the hero as a
        // whole — the chrome inset is part of what a viewer sees as the spotlight, so the extra
        // goes on top of the pair. The height is what decides how much of the cover survives:
        // a portrait cover fills this box by its width and is trimmed top and bottom, so every dp
        // added here is artwork kept rather than cut. At 270dp it lost a fifth of its height; at
        // this it loses about a ninth.
        else -> 334.dp
    }
    if (device.isTv) {
        LaunchedEffect(heroIds, safeTvPage, tvHeroHasFocus) {
            if (items.size <= 1 || tvHeroHasFocus) return@LaunchedEffect
            delay(HERO_AUTO_ADVANCE_MS)
            tvPage = nextHeroPage(safeTvPage, items.size)
        }
    } else {
        LaunchedEffect(heroIds, pagerState.settledPage, pagerIsDragged) {
            if (items.size <= 1 || pagerIsDragged) return@LaunchedEffect
            delay(HERO_AUTO_ADVANCE_MS)
            pagerState.animateScrollToPage(nextHeroPage(pagerState.settledPage, items.size))
        }
    }
    // The series' logos in place of the typed titles, as on the title page and the desktop app's
    // spotlight. Every slide's is asked for up front, one at a time, so swiping on does not swap a
    // title for art while it is being read; a null is "looked, none" and keeps the typed title.
    val showSeriesLogos by SettingsStore.showSeriesLogos.collectAsState()
    val heroLogos = remember { mutableStateMapOf<Int, String?>() }
    LaunchedEffect(heroIds, showSeriesLogos) {
        if (!showSeriesLogos) return@LaunchedEffect
        items
            .filter { it.id !in heroLogos }
            .forEach { heroLogos[it.id] = AppGraph.repository.seriesLogo(it.id) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (device.isTv) device.pagePadding else 0.dp)
            .height(heroHeight)
            .clip(if (device.isTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp)),
    ) {
        if (device.isTv) {
            val page = safeTvPage
            HeroCard(
                media = items[page],
                logo = heroLogos[items[page].id],
                pageIndex = page,
                pageCount = items.size,
                onAnimeClick = onAnimeClick,
                canGoPrevious = page > 0,
                canGoNext = page < items.lastIndex,
                navigationInProgress = false,
                onPrevious = { tvPage = (page - 1).coerceAtLeast(0) },
                onNext = { tvPage = (page + 1).coerceAtMost(items.lastIndex) },
                onMoveDown = onMoveDown,
                topInset = topInset,
                onFocusChanged = { tvHeroHasFocus = it },
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Compose only the adjacent page. AsyncImage then requests at the actual hero
                // constraints instead of an eager preload downloading three original-size files.
                beyondViewportPageCount = 1,
                key = { page -> items[page].id },
            ) { page ->
                HeroCard(
                    media = items[page],
                    logo = heroLogos[items[page].id],
                    pageIndex = page,
                    pageCount = items.size,
                    onAnimeClick = onAnimeClick,
                    canGoPrevious = page > 0,
                    canGoNext = page < items.lastIndex,
                    navigationInProgress = false,
                    onPrevious = {},
                    onNext = {},
                    onMoveDown = onMoveDown,
                    topInset = topInset,
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier
                        .height(5.dp)
                        .width(if (i == if (device.isTv) safeTvPage else pagerState.currentPage) 18.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == if (device.isTv) safeTvPage else pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(.4f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    media: Media,
    logo: String?,
    pageIndex: Int,
    pageCount: Int,
    onAnimeClick: (Int) -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    navigationInProgress: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMoveDown: (() -> Boolean)?,
    topInset: Dp = 0.dp,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val device = LocalAppDeviceProfile.current
    val focusManager = LocalFocusManager.current
    // The corner pills frost this page's own artwork rather than the feed: the hero is inside the
    // feed's source, and an effect drawing the source it sits in would sample itself.
    val heroArt = if (glassEnabled(GlassSection.HOME)) remember { HazeState() } else null
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { if (device.isTv) onFocusChanged(it.isFocused) }
            // The spotlight used to carry two buttons, and they were what a remote landed on and
            // steered from. With the artwork itself as the only target, its own key handler has to
            // do the steering the buttons did — otherwise Left, Right and Down all leave the hero.
            .onPreviewKeyEvent { event ->
                if (!device.isTv || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft ->
                        if (event.nativeKeyEvent.repeatCount > 0 || navigationInProgress) {
                            true
                        } else if (canGoPrevious) {
                            onPrevious()
                            true
                        } else {
                            false
                        }
                    Key.DirectionRight ->
                        if (event.nativeKeyEvent.repeatCount > 0 || navigationInProgress) {
                            true
                        } else if (canGoNext) {
                            onNext()
                            true
                        } else {
                            false
                        }
                    // Returning false hands the press back to Compose's own spatial search rather
                    // than swallowing it, so a hand-off that cannot land still moves focus.
                    Key.DirectionDown ->
                        onMoveDown?.invoke() == true || focusManager.moveFocus(FocusDirection.Down)
                    else -> false
                }
            }
            .clickable(
                onClickLabel = "Open details for ${media.title.preferred}",
                role = Role.Button,
            ) { onAnimeClick(media.id) },
    ) {
        Box(Modifier.fillMaxSize().then(if (heroArt != null) Modifier.hazeSource(heroArt) else Modifier)) {
            HeroBackdrop(media, topInset)
            Box(
                Modifier.fillMaxSize().appCanvasFade { canvas ->
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(.30f),
                        0.42f to Color.Black.copy(.12f),
                        0.72f to Color.Black.copy(.45f),
                        1f to canvas,
                    )
                },
            )
        }

        // Top corners: airing countdown on the left, pager position on the right — below whatever
        // chrome is fading over the top of the artwork.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topInset)
                .padding(horizontal = device.pagePadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            heroCountdownLabel(media)?.let { label ->
                HeroPill(backdrop = heroArt) {
                    Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(label, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } ?: Spacer(Modifier)
            if (pageCount > 1) {
                HeroPill(backdrop = heroArt) {
                    Text("${pageIndex + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        " / $pageCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(.7f),
                    )
                }
            }
        }

        // Centered content block: metadata strip, big title, genres. It sits lower than it used to
        // because the buttons it used to stand on are gone — with nothing below it, keeping the old
        // 26dp would have left it floating above an empty foot of artwork. The remaining clearance
        // is what the pager dots need underneath.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(if (device.isExpanded) 0.8f else 1f)
                .padding(horizontal = device.pagePadding)
                .padding(top = 26.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeroMetaRow(media)
            // A fixed box about the height of the two title lines it replaces, so the genre pill
            // does not jump while the logo decodes; centred, like the title.
            SeriesLogo(
                url = logo,
                contentDescription = media.title.preferred,
                alignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth(if (device.isExpanded) 0.6f else 0.82f)
                    .height(if (device.isTv || device.isExpanded) 88.dp else 72.dp),
            ) {
                ShrinkingTitle(
                    text = media.title.preferred,
                    style = (
                        if (device.isTv || device.isExpanded) {
                            MaterialTheme.typography.displaySmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        }
                    ).copy(
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = if (device.isTv || device.isExpanded) 42.sp else 34.sp,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            val genreLabel = media.genres.take(3).joinToString("  ·  ")
            if (genreLabel.isNotBlank()) {
                HeroPill(Modifier.padding(top = 12.dp), backdrop = heroArt) {
                    Text(genreLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * How wide the sharp banner band is relative to its own height.
 *
 * AniList banners are 1900x400, so 3:1 crops a third of the width off the sides and — on a phone
 * about 411dp wide at three times density — asks for a band 411px tall from a 400px source, which
 * is a scale of roughly 1.0. Going any taller starts magnifying the banner again for no more
 * detail; going wider leaves a strip too thin to read as artwork inside the hero.
 */
private const val HERO_BANNER_ASPECT = 3f

/**
 * Dissolves the top and bottom edges of the sharp band into the blurred fill behind it.
 *
 * Without this the band ends on a hard horizontal line across the screen. The two layers are the
 * same picture at different crops, so they cannot be registered against each other — but the fill
 * is blurred past the point of holding any structure, so the overlap reads as colour rather than a
 * doubled image.
 */
private val HERO_BANNER_FADE = Brush.verticalGradient(
    0f to Color.Transparent,
    0.09f to Color.Black,
    0.80f to Color.Black,
    1f to Color.Transparent,
)

/**
 * The hero artwork: the cover filling a phone's box, a banner where the box is genuinely landscape.
 *
 * A phone hero is the 270dp of artwork plus every bit of the chrome it runs beneath, which makes it
 * close to square. Filling that with a banner meant scaling a 1900x400 source about three times up
 * and keeping a fifth of its width, which is why a spotlight title looked like a soft, over-zoomed
 * detail of its own key art. The cover is the far better fit for a shape like that: it is the same
 * portrait art the rest of the feed is made of, a box this tall takes only about an eighth off its
 * top and bottom, and at 1500px wide it is scaled *down* to fill rather than up.
 *
 * A tablet or television box really is landscape, so those keep the banner — and there the banner is
 * not asked to cover anything either. It is drawn at its own scale in a band under the chrome with a
 * cheap, heavily blurred copy of itself filling the box behind. Only that fill is magnified, and the
 * fill is blur. [Modifier.blur] does nothing below API 31, where it degrades to a plain crop with
 * the sharp band still on top of it.
 */
@Composable
private fun HeroBackdrop(media: Media, topInset: Dp, modifier: Modifier = Modifier) {
    val device = LocalAppDeviceProfile.current
    val cover = media.coverImage.best
    if (!device.isTv && !device.isTablet && !device.isExpanded && cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // Centred, so the crop takes an even slice off the top and the bottom rather than
            // beheading the artwork at one end.
            alignment = Alignment.Center,
        )
        return
    }
    // Never the trailer frame: YouTube's hqdefault is 480x360, so a hero magnifies it several times
    // over into an unrecognisable close-up — the television dropped it for that reason. A large box
    // with no banner falls back to covering with the cover, which is what it did before.
    val banner = media.wideHeroArtwork()
    if (banner == null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
        return
    }
    Box(modifier.fillMaxSize().clipToBounds()) {
        HeroAmbientFill(banner, width = 480, height = 270)
        AsyncImage(
            model = banner,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Under the chrome rather than behind it. Starting at the top would put the band's
                // best part beneath the app bar and force the crop back up to cover the inset.
                .padding(top = topInset)
                .fillMaxWidth()
                .aspectRatio(HERO_BANNER_ASPECT)
                // The mask erases pixels the band has already drawn, so it needs a layer of its own
                // to erase from — otherwise DstIn punches through the blurred fill as well.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = HERO_BANNER_FADE, blendMode = BlendMode.DstIn)
                },
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * The blurred wash that fills the hero box behind whichever artwork is drawn whole on top of it.
 *
 * Requested deliberately small: it is about to be blurred past recognition, so decoding it at hero
 * size would be a second full bitmap bought for nothing. This is the only layer that gets magnified,
 * and magnifying blur costs nothing to look at.
 *
 * The overscale below draws outside the node, and a pager does not clip its pages — only its own
 * viewport — so the page waiting off-screen spilled a seventh of this wash back over the page on
 * screen, as a strip down the right-hand edge painted on top of its pager pill. Every caller has to
 * clip its own bounds.
 */
@Composable
private fun HeroAmbientFill(url: String, width: Int, height: Int) {
    val context = LocalContext.current
    val request = remember(url, width, height) {
        ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            // The overscale hides the transparent margin a blur leaves around its own edges.
            .graphicsLayer {
                scaleX = 1.14f
                scaleY = 1.14f
            }
            .blur(36.dp),
        contentScale = ContentScale.Crop,
    )
}

/** Warm gold accent for the hero's star-rating icon; the title itself stays white. */
private val HERO_STAR_COLOR = Color(0xFFEFC66B)

/** A translucent rounded chip used for the hero's corner pills, genre tag, and metadata frame. */
@Composable
private fun HeroPill(
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val frosted = backdrop != null && backdropBlurAvailable
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .backdropBlur(backdrop, tint = Color.Black.copy(alpha = .22f), radius = 12.dp)
            .background(Color.Black.copy(if (frosted) 0f else .42f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One entry of the hero metadata strip: optional leading icon (its own tint) plus a label. */
private data class HeroMetaCell(val icon: ImageVector?, val text: String, val iconTint: Color? = null)

/** Centered format / episodes / score / runtime strip, each present only when known. */
@Composable
private fun HeroMetaRow(media: Media) {
    val cells = buildList {
        media.format?.let { add(HeroMetaCell(icon = null, text = heroFormatLabel(it))) }
        // For an airing show the CC count is what's actually out, not the season's planned total.
        val airedEpisodes = media.nextAiringEpisode?.episode?.minus(1) ?: media.episodes
        airedEpisodes?.takeIf { it > 0 }?.let { add(HeroMetaCell(Icons.Default.ClosedCaption, "$it")) }
        media.averageScore?.takeIf { it > 0 }?.let {
            add(HeroMetaCell(Icons.Default.Star, "$it", iconTint = HERO_STAR_COLOR))
        }
        media.duration?.takeIf { it > 0 }?.let { add(HeroMetaCell(Icons.Default.Schedule, "$it mins")) }
    }
    if (cells.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Text(
                    "•",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(.45f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                cell.icon?.let { icon ->
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 3.dp).size(15.dp),
                        tint = cell.iconTint ?: LocalContentColor.current,
                    )
                }
                Text(cell.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun heroFormatLabel(format: String): String = when (format) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.replaceFirstChar { it.uppercase() }
}

/**
 * "EP 4 · 5D 8H"-style countdown for a title with a scheduled next episode, or null when nothing
 * is airing. Coarse by design — the biggest two non-zero units (days/hours, or hours/minutes) are
 * enough for a spotlight and read cleanly at a glance.
 */
private fun heroCountdownLabel(media: Media): String? {
    val next = media.nextAiringEpisode ?: return null
    val episode = next.episode ?: return null
    val seconds = next.timeUntilAiring?.takeIf { it > 0 } ?: return "EP $episode SOON"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    val time = when {
        days > 0 -> "${days}D ${hours}H"
        hours > 0 -> "${hours}H ${minutes}M"
        else -> "${minutes}M"
    }
    return "EP $episode  $time"
}
internal fun nextHeroPage(currentPage: Int, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (currentPage.coerceIn(0, pageCount - 1) + 1) % pageCount

/**
 * The page a Left or Right press lands on, wrapping either way.
 *
 * Separate from [nextHeroPage] only because it takes a direction; a remote held on Left at the
 * first slide should reach the last one rather than stop, the same as holding Right at the end.
 */
internal fun slideHeroPage(currentPage: Int, pageCount: Int, step: Int): Int =
    if (pageCount <= 1) {
        0
    } else {
        val from = currentPage.coerceIn(0, pageCount - 1)
        (((from + step) % pageCount) + pageCount) % pageCount
    }

/**
 * A rail's title line. With a [dragHandle], the whole line — title, the space after it and the
 * handle icon at its end — is one place to hold the row and drag it (see [homeRowDragHandle]); the
 * icon is what says it can be done.
 */
@Composable
private fun RailTitle(title: String, dragHandle: Modifier?, modifier: Modifier = Modifier) {
    Row(
        // A finger-sized target when it can be held; otherwise exactly the title, as before.
        modifier = modifier.then(if (dragHandle != null) Modifier.heightIn(min = 40.dp).then(dragHandle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            // All the width the handle leaves, so the title keeps one line and the space after a
            // short one is still something to hold.
            modifier = if (dragHandle != null) Modifier.weight(1f) else Modifier,
        )
        if (dragHandle != null) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                modifier = Modifier.padding(horizontal = 8.dp).size(22.dp),
            )
        }
    }
}

@Composable
private fun MediaRail(
    title: String,
    media: List<Media>,
    onAnimeClick: (Int) -> Unit,
    cardWidth: Dp,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    columns: Int = 3,
    gridSpacing: Dp = 9.dp,
    /** Hold-to-drag on the title and the handle; see [homeRowDragHandle]. Null draws neither. */
    dragHandle: Modifier? = null,
) {
    val device = LocalAppDeviceProfile.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = device.pagePadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailTitle(title, dragHandle, Modifier.weight(1f))
            if (onToggleExpand != null) {
                val controlFocus6 = rememberFocusFill()
                TextButton(
                    colors = controlFocus6.buttonColors(ButtonDefaults.textButtonColors()),
                    onClick = onToggleExpand,
                    modifier = Modifier.focusFill(controlFocus6, RoundedCornerShape(50)),
                ) {
                    Icon(
                        if (isExpanded) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isExpanded) "Carousel" else "Expand")
                }
            }
        }
        if (isExpanded) {
            val cardRows = media.chunked(columns)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(gridSpacing),
            ) {
                cardRows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = device.pagePadding),
                        horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                    ) {
                        row.forEach { item ->
                            AnimeCard(
                                media = item,
                                onClick = { onAnimeClick(item.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (onLoadMore != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val controlFocus7 = rememberFocusFill()
                        OutlinedButton(
                            colors = controlFocus7.buttonColors(ButtonDefaults.outlinedButtonColors()),
                            onClick = onLoadMore,
                            enabled = !isLoadingMore,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.focusFill(controlFocus7, RoundedCornerShape(20.dp)),
                        ) {
                            if (isLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            // Follows the rail's own title: this is a generic rail, and hardcoding
                            // one section's name here is what made it read "Trending" after the
                            // rail started showing something else.
                            Text(
                                if (isLoadingMore) "Loading…" else "Load More $title",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.focusGroup(),
                contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 10.dp),
            ) {
                items(media, key = { it.id }) { item ->
                    AnimeCard(
                        media = item,
                        onClick = { onAnimeClick(item.id) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
                if (onLoadMore != null) {
                    item {
                        val fillFocus1 = rememberFocusFill()
                        Box(
                            modifier = Modifier
                                .height(cardWidth * 1.4f)
                                .width(cardWidth * 0.85f)
                                .focusFill(fillFocus1, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .background(fillFocus1.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)))
                                .clickable(enabled = !isLoadingMore) { onLoadMore() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Load More",
                                        tint = fillFocus1.content(MaterialTheme.colorScheme.primary),
                                    )
                                }
                                Text(
                                    if (isLoadingMore) "Loading…" else "Load More",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = fillFocus1.content(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Continue Watching row's scroll state, kept at the start when a title arrives at the front.
 *
 * A lazy row holds on to the card it is showing when items are added before it, which is right for a
 * list being read and wrong here: a row coming back once its episode is out — or one played on
 * another device — goes in at the front, and the row kept showing the card that used to be first,
 * with the new one just past the left edge where nobody would think to look. So a viewer who was at
 * the start stays at the start. One scrolled along the row is left where they are.
 */
@Composable
internal fun rememberContinueRailState(
    leadId: Int?,
    state: LazyListState = rememberLazyListState(),
): LazyListState {
    var shownLead by remember { mutableStateOf(leadId) }
    val latestLead by androidx.compose.runtime.rememberUpdatedState(leadId)
    LaunchedEffect(leadId) {
        val previous = shownLead
        shownLead = leadId
        if (previous == null || previous == latestLead) return@LaunchedEffect
        // Either the row has not been measured since the change (still at 0) or it has and moved to
        // keep the old lead in view; both mean the viewer was at the start.
        val atStart = state.firstVisibleItemScrollOffset == 0 &&
            (state.firstVisibleItemIndex == 0 ||
                state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == previous)
        if (atStart) state.scrollToItem(0)
    }
    return state
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueRail(
    history: List<HistoryEntry>,
    nextReleases: Map<Int, NextEpisodeRelease>,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    firstItemFocusRequester: FocusRequester,
    dragHandle: Modifier? = null,
) {
    val device = LocalAppDeviceProfile.current
    val homeGlass = glassEnabled(GlassSection.HOME)
    var managedEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    managedEntry?.let { entry ->
        ContinueWatchingActionsDialog(
            entry = entry,
            onDismiss = { managedEntry = null },
            onOpenDetails = { onOpenDetails(entry) },
        )
    }
    // The card's ✕ sits beside its Info button and over the thumbnail that resumes the episode, so
    // a tap that lands a little wide took the title off the row with no way back to it. Asked first.
    var removingEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    removingEntry?.let { entry ->
        MidnightConfirmDialog(
            kicker = "Continue watching",
            title = "Remove ${entry.title}?",
            body = AccountService.active?.let {
                "It comes off this row only. Your ${it.label} list stays as it is."
            } ?: "It comes off this row only.",
            confirmText = "Remove",
            onConfirm = {
                removingEntry = null
                LibraryStore.removeHistory(entry.anilistId)
            },
            onDismiss = { removingEntry = null },
        )
    }
    val cardWidth = when {
        device.isTv -> 240.dp
        device.isExpanded -> 220.dp
        device.isTablet -> 200.dp
        else -> 174.dp
    }
    Column {
        RailTitle(
            "Continue Watching",
            dragHandle,
            Modifier.fillMaxWidth().padding(horizontal = device.pagePadding),
        )
        Text(
            "Long-press a title to move it on your anime list",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 2.dp),
        )
        LazyRow(
            state = rememberContinueRailState(history.firstOrNull()?.anilistId),
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 10.dp),
        ) {
            itemsIndexed(history, key = { _, item -> item.anilistId }) { index, entry ->
                Column(
                    Modifier
                        .width(cardWidth)
                        .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        // The episode line below sits on the card's bottom edge; a fully rounded
                        // highlight clips its first letter away.
                        .focusHighlight(CaptionedCardShape)
                        .combinedClickable(
                            onClickLabel = "Resume ${entry.title}",
                            onLongClickLabel = "Manage Continue Watching",
                            onClick = { onResume(entry) },
                            onLongClick = { managedEntry = entry },
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        // Each card frosts its own thumbnail under its corner buttons: a source
                        // this small is cheap to record, and the buttons never need the feed.
                        val thumbnail = if (homeGlass) remember { HazeState() } else null
                        Box(Modifier.fillMaxSize().then(if (thumbnail != null) Modifier.hazeSource(thumbnail) else Modifier)) {
                            AsyncImage(model = entry.cover, contentDescription = "Resume ${entry.title}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(.3f)))
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                        // The two things anyone actually does to a Continue Watching card, on the
                        // card. They were reachable only behind a long press, which is a gesture
                        // nothing on this screen advertises and most viewers never tried; the
                        // menu stays for moving a title between lists, which needs a list to pick.
                        Row(
                            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CardCornerAction(
                                icon = Icons.Default.Info,
                                contentDescription = "Details for ${entry.title}",
                                onClick = { onOpenDetails(entry) },
                                backdrop = thumbnail,
                            )
                            CardCornerAction(
                                icon = Icons.Default.Close,
                                contentDescription = "Remove ${entry.title} from Continue Watching",
                                onClick = { removingEntry = entry },
                                backdrop = thumbnail,
                            )
                        }
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(.25f))) {
                            Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.03f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Text("Episode ${entry.episodeLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // When the next one lands, on the card rather than only behind the show's own
                    // page: this row is where the wait is felt. Silent when nothing is scheduled.
                    nextReleases[entry.anilistId]?.let { release ->
                        Text(
                            nextReleaseLabel(release),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                // "EP 5 · IN 3 DAYS" is abbreviations at a size this small; what
                                // the screen reader says is the sentence it stands for.
                                .clearAndSetSemantics { contentDescription = spokenNextReleaseLabel(release) },
                        )
                    }
                }
            }
        }
    }
}


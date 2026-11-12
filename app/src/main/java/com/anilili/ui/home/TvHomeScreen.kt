@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.home

import com.anilili.ui.theme.appCanvasFade
import com.anilili.ui.theme.hasAppBackdrop
import com.anilili.ui.theme.appCanvas
import com.anilili.ui.components.SeriesLogo
import com.anilili.ui.components.ShrinkingTitle
import com.anilili.data.settings.SettingsStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.anilili.ui.components.TvTopNavigationHeight
import com.anilili.ui.tour.RegisterTourScroller
import com.anilili.ui.tour.StartWhatsNewTourWhenSettled
import com.anilili.ui.tour.TourTarget
import com.anilili.ui.tour.tourTarget
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.anilili.R
import com.anilili.data.DubIndex
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.NextEpisodeRelease
import com.anilili.data.library.nextReleaseLabel
import com.anilili.data.library.spokenNextReleaseLabel
import com.anilili.data.model.Media
import com.anilili.data.settings.PosterElement
import com.anilili.ui.adaptive.TvCardCornerShape
import com.anilili.ui.adaptive.TvPanelCornerShape
import com.anilili.ui.adaptive.TvSmallCardCornerShape
import com.anilili.ui.adaptive.TvFocusMemory
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.FocusRingGap
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.tvFocusTarget
import com.anilili.ui.components.ContinueWatchingActionsDialog
import com.anilili.ui.components.DubBadge
import com.anilili.ui.components.RatingBadge
import com.anilili.ui.components.rememberPosterElements
import com.anilili.ui.components.TvHeroArtwork
import com.anilili.ui.components.wideHeroArtwork
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.anilili.util.cached

private val TvPagePadding = 48.dp
internal val TvMediaCardWidth = 134.dp
private val TvContinueCardWidth = 300.dp
private val TvContinueCardHeight = 118.dp
private val TvCardShape = TvCardCornerShape
private const val TV_HERO_AUTO_ADVANCE_MS = 7_000L
internal const val TV_MEDIA_CARD_ASPECT_RATIO = 2f / 3f

/**
 * How many cards short of the end of a rail the next page is asked for.
 *
 * A 1080p rail shows six or seven cards, so this is most of a screenful of warning — enough for
 * AniList to answer while the viewer is still travelling towards the gap. Any less and holding
 * Right walks into the end of the list and stops there until the page lands.
 */
private const val TV_RAIL_PREFETCH_DISTANCE = 5

/**
 * How far the spotlight recedes while focus is somewhere else on the screen.
 *
 * Enough to be unmistakable across the room without hiding what the slide is advertising — the
 * artwork is already sitting under two gradients, so this lands on top of a picture that is part
 * black to begin with.
 */
private const val TV_HERO_RESTING_DIM = .40f
private const val TV_HERO_RESTING_HINT_ALPHA = .35f

/** How long focus rests on a rail card before its missing backdrop is looked up. */
private const val TV_HERO_BACKDROP_SETTLE_MS = 600L

/**
 * Cinematic 10-foot Home surface. This deliberately lives beside the existing responsive Home
 * implementation so phone and tablet layouts remain unchanged.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun TvHomeContent(
    data: HomeData,
    history: List<HistoryEntry>,
    nextReleases: Map<Int, NextEpisodeRelease> = emptyMap(),
    personal: PersonalRows = PersonalRows(),
    layout: HomeLayout = HomeLayout.default(isTv = true),
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    loadingMore: Set<HomeSection> = emptySet(),
    onLoadMore: (HomeSection) -> Unit = {},
    primaryActionFocusTarget: TvFocusTarget? = null,
    modifier: Modifier = Modifier,
) {
    val spotlight = data.spotlight.take(7)
    var spotlightIndex by remember(spotlight.map(Media::id)) { mutableIntStateOf(0) }
    var heroHasFocus by remember { mutableStateOf(false) }
    var railPreview by remember(spotlight.map(Media::id)) { mutableStateOf<Media?>(null) }
    val spotlightHero = spotlight.getOrNull(spotlightIndex.coerceIn(0, (spotlight.size - 1).coerceAtLeast(0)))
    val hero = railPreview ?: spotlightHero

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val heroActionFocus = remember { FocusRequester() }
    // Compose's 2D focus search does not find the hero's buttons from the rail below it, so Up
    // out of the first rail landed on nothing at all and left the screen unable to move: the
    // remote was dead until the viewer left Home. Naming the target and scrolling it back into
    // view sidesteps the search entirely, which is the only thing that has ever worked here.
    val returnToHero: () -> Boolean = {
        scope.launch {
            listState.animateScrollToItem(0)
            withFrameNanos { }
            runCatching { heroActionFocus.requestFocus() }
                .onFailure { DiagnosticsLog.throwable("TvHome hero unreachable from rail", it) }
        }
        true
    }
    val because = personal.because?.takeIf { it.seed != null && it.items.isNotEmpty() }
    // The rails actually drawn, in the viewer's order. The first hands Up back to the hero; the
    // last blocks Down, which otherwise finds nothing below and drops the cursor off the screen.
    val rails = layout.present { row ->
        when (row) {
            HomeRow.CONTINUE -> history.isNotEmpty()
            HomeRow.FOR_YOU -> personal.forYou.isNotEmpty()
            HomeRow.BECAUSE -> because != null
            else -> row.catalogSection?.let { data.section(it).isNotEmpty() } == true
        }
    }
    val firstRail = rails.firstOrNull()
    val lastRail = rails.lastOrNull()

    // What's new (ui/tour): starts once Home has settled. A row a step points at is scrolled to just
    // under the floating bar; the dice and Settings live in that bar, which shows at the top.
    StartWhatsNewTourWhenSettled()
    val tourTopPx = with(LocalDensity.current) { (TvTopNavigationHeight + 12.dp).roundToPx() }
    RegisterTourScroller { target ->
        val row = when (target) {
            TourTarget.FOR_YOU -> HomeRow.FOR_YOU
            TourTarget.BECAUSE -> HomeRow.BECAUSE
            else -> null
        }
        if (row == null) {
            listState.animateScrollToItem(0)
            return@RegisterTourScroller
        }
        val at = rails.indexOf(row).takeIf { it >= 0 } ?: return@RegisterTourScroller
        listState.animateScrollToItem((if (hero != null) 1 else 0) + at, -tourTopPx)
    }

    // Nothing claims focus on arrival. Home used to take it for the hero's play button whenever the
    // hero appeared, which was a second answer to a question the top navigation bar already
    // answers: it focuses the tab for the route on every route change. Both fired, and this one
    // fired later — after a scroll animation and a frame — so it always won, and selecting the Home
    // tab pulled the cursor out of the bar and down onto the banner. Down from the bar reaches the
    // hero on purpose (see the `homeContentFocusTarget` redirect), which is the same journey made
    // by choice rather than for the viewer.
    //
    // What that claim was really covering was the bar's own request failing silently on a slow box
    // and leaving the remote dead. That is fixed where it happens, by retrying the request, rather
    // than by a second screen grabbing focus on every visit.

    LaunchedEffect(heroHasFocus) {
        if (heroHasFocus) {
            railPreview = null
            listState.animateScrollToItem(0)
        }
    }

    // Landscape pictures for the titles AniList has no banner for, by id; a null is "looked, none",
    // and the poster treatment stays. The spotlight's are fetched up front, one at a time, so a
    // slide never arrives as a poster and turns into a backdrop a moment later. A rail card's is
    // asked for only once focus has rested on it: holding Right across a rail would otherwise be a
    // burst of lookups against a keyless service, for previews nobody stopped to look at.
    val heroBackdrops = remember { mutableStateMapOf<Int, String?>() }
    LaunchedEffect(spotlight.map(Media::id)) {
        spotlight
            .filter { it.wideHeroArtwork() == null && it.id !in heroBackdrops }
            .forEach { heroBackdrops[it.id] = AppGraph.repository.heroBackdrop(it) }
    }
    LaunchedEffect(railPreview?.id) {
        val preview = railPreview ?: return@LaunchedEffect
        if (preview.wideHeroArtwork() != null || preview.id in heroBackdrops) return@LaunchedEffect
        delay(TV_HERO_BACKDROP_SETTLE_MS)
        heroBackdrops[preview.id] = AppGraph.repository.heroBackdrop(preview)
    }

    // The series' logos, set in place of the typed title as the title page and the desktop app's
    // spotlight already do. Looked up the same way as the backdrops — the spotlight's up front so
    // a slide does not swap its text for art while it is being read, a rail card's once focus has
    // rested on it — but on their own effects, so a slow backdrop never holds a logo back. A null
    // is "looked, none", and the typed title stays. Nothing is asked while logos are switched off.
    val showSeriesLogos by SettingsStore.showSeriesLogos.collectAsState()
    val heroLogos = remember { mutableStateMapOf<Int, String?>() }
    LaunchedEffect(spotlight.map(Media::id), showSeriesLogos) {
        if (!showSeriesLogos) return@LaunchedEffect
        spotlight
            .filter { it.id !in heroLogos }
            .forEach { heroLogos[it.id] = AppGraph.repository.seriesLogo(it.id) }
    }
    LaunchedEffect(railPreview?.id, showSeriesLogos) {
        val preview = railPreview ?: return@LaunchedEffect
        if (!showSeriesLogos || preview.id in heroLogos) return@LaunchedEffect
        delay(TV_HERO_BACKDROP_SETTLE_MS)
        heroLogos[preview.id] = AppGraph.repository.seriesLogo(preview.id)
    }

    LaunchedEffect(spotlight.map(Media::id), spotlightIndex, heroHasFocus, railPreview) {
        if (spotlight.size <= 1 || heroHasFocus || railPreview != null) return@LaunchedEffect
        delay(TV_HERO_AUTO_ADVANCE_MS)
        spotlightIndex = nextHeroPage(spotlightIndex, spotlight.size)
    }

    // Up a rail used to park it flush against the top of the list, which on Home is the top of the
    // screen, under the floating bar: the row the cursor was on lost its upper half. Centring the
    // focused card in the room below the bar puts it in the same place going up as going down.
    TvCentredScrollSpec(topInset = TvTopNavigationHeight) {
        LazyColumn(
            state = listState,
            // Down out of the top bar lands on the list, not on the spotlight inside it. The
            // spotlight is the first item of a lazy list, so it exists only while it is on screen:
            // scroll past it and the hand-off has nothing attached to hand to. That is every return
            // from a title's page, which restores the scroll position Home was left at.
            //
            // Ordinary focus search cannot cover for that here, and the reason is geometric. Home
            // is the one route whose content is deliberately *not* padded below the floating bar,
            // so that the spotlight can run under it — which puts this list's top edge at y=0,
            // above the top edge of the focused tab. Compose's 2-D search only considers a
            // candidate that begins below where the focused item begins, so it rejects the scroll
            // container and every rail inside it. Down out of the bar has never worked by search on
            // Home; it has only ever worked by hand-off.
            //
            // Naming the list keeps that hand-off pointed at something that exists for as long as
            // Home has content. Compose's focus-enter then picks whichever child is on screen,
            // which is the spotlight at the top of the page and the nearest card anywhere else.
            modifier = modifier
                .tvFocusTarget(primaryActionFocusTarget)
                .fillMaxSize()
                .appCanvas(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (hero != null) {
                item(key = "tv-hero") {
                    TvHero(
                        media = hero,
                        backdrop = hero.wideHeroArtwork() ?: heroBackdrops[hero.id],
                        logo = heroLogos[hero.id],
                        pageIndex = spotlightIndex,
                        pageCount = spotlight.size,
                        onAnimeClick = onAnimeClick,
                        onSlide = { step ->
                            // Wraps both ways, so holding one direction walks the spotlight round
                            // rather than stopping dead at either end.
                            spotlightIndex = slideHeroPage(spotlightIndex, spotlight.size, step)
                        },
                        onFocusChanged = { heroHasFocus = it },
                        actionFocusRequester = heroActionFocus,
                    )
                }
            }
            rails.forEach { row ->
                // Up from the top rail belongs to the hero; from any other, to the rail above.
                val onFocusUp = returnToHero.takeIf { row == firstRail }
                val blockDown = row == lastRail
                when (row) {
                    HomeRow.CONTINUE -> item(key = "continue-watching") {
                        // Every title in progress, not the first handful. The row used to be cut at
                        // twelve here, which is not a row length but a hidden list: a thirteenth
                        // show was simply absent, and the only way to reach it was to remove
                        // another one. The rail is a LazyRow, so a card off screen costs nothing
                        // until the viewer scrolls to it, and the store's own bound (100 entries)
                        // is the only limit worth having.
                        TvContinueRail(
                            history = history,
                            nextReleases = nextReleases,
                            onResume = onResume,
                            onOpenDetails = onOpenDetails,
                            blockDown = blockDown,
                            onFocusUp = onFocusUp,
                        )
                    }
                    HomeRow.FOR_YOU -> item(key = "for-you") {
                        Box(Modifier.tourTarget(TourTarget.FOR_YOU)) {
                            TvMediaRail(
                                title = row.label(isTv = true),
                                media = personal.forYou,
                                onAnimeClick = onAnimeClick,
                                onPreview = { railPreview = it },
                                blockDown = blockDown,
                                onFocusUp = onFocusUp,
                            )
                        }
                    }
                    HomeRow.BECAUSE -> item(key = "because-${because?.seed?.id}") {
                        Box(Modifier.tourTarget(TourTarget.BECAUSE)) {
                            TvMediaRail(
                                title = "Because you watched ${because?.seed?.title?.preferred}",
                                media = because?.items.orEmpty(),
                                onAnimeClick = onAnimeClick,
                                onPreview = { railPreview = it },
                                blockDown = blockDown,
                                onFocusUp = onFocusUp,
                            )
                        }
                    }
                    else -> {
                        val section = row.catalogSection ?: return@forEach
                        item(key = row.key) {
                            TvMediaRail(
                                title = row.label(isTv = true),
                                media = data.section(section),
                                onAnimeClick = onAnimeClick,
                                onPreview = { railPreview = it },
                                blockDown = blockDown,
                                isLoadingMore = section in loadingMore,
                                onLoadMore = { onLoadMore(section) },
                                onFocusUp = onFocusUp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The spotlight, as one slide the remote drives directly.
 *
 * Left and Right move between the spotlight titles and OK opens the one on screen. It used to
 * carry a Watch now and a More info button instead, which meant the only thing Left and Right
 * could do was move between those two — and because the auto-advance stops as soon as anything in
 * the hero has focus, and Home puts focus here the moment it appears, a viewer never saw past the
 * first of the seven titles.
 */
@Composable
private fun TvHero(
    media: Media,
    backdrop: String?,
    logo: String?,
    pageIndex: Int,
    pageCount: Int,
    onAnimeClick: (Int) -> Unit,
    onSlide: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    actionFocusRequester: FocusRequester,
) {
    val resume = LibraryStore.historyFor(media.id)
    val description = remember(media.description) {
        media.description
            ?.replace(Regex.cached("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    // The slide is full-bleed, so it gets none of the treatment focusHighlight gives a card:
    // there is no room to lift it, and a rim around something that already touches three screen
    // edges traces the edge of the panel rather than the edge of the slide — it read as a box drawn
    // over the television picture, not as a thing being picked out.
    //
    // What large TV apps do with a banner this size is the opposite: leave the focused state plain
    // and let the *unfocused* one recede. So the picture dims when focus moves down into the rails
    // and comes back up when it returns, which is a whole-screen cue rather than a 3dp one and
    // costs a single alpha. The line explaining what OK and Left/Right do fades with it, since
    // those keys only do that while the slide holds focus.
    var focused by remember { mutableStateOf(false) }
    val lowCostTvEffects = LocalAppDeviceProfile.current.useLowCostTvEffects
    val restingDim = if (focused) 0f else TV_HERO_RESTING_DIM
    val dim = if (lowCostTvEffects) {
        restingDim
    } else {
        val animatedDim by animateFloatAsState(restingDim, label = "tv-hero-dim")
        animatedDim
    }
    // The focus target below starts under the top bar, and its default indication — the ripple's
    // focused state layer, a light wash — was drawn on the target's bounds: the slide lit up from
    // 66dp down and left a dark band across the top where the bar floats. The target keeps its
    // geometry for the focus search; the wash is drawn on the whole slide instead.
    val slideInteraction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            // A minimum, not a fixed height. The hero was exactly this tall with its content
            // clipped to it, so anything that made the text taller — a two-line title and a
            // description at a font scale above 1.0 — pushed Watch now and More info through the
            // bottom edge. All that stayed on screen was the top sliver of each button, which is
            // why a viewer photographed two empty pills and reported the play button as missing.
            // Letting the hero grow keeps the actions reachable at any text size.
            .heightIn(min = 284.dp)
            // The dim belongs to the whole slide, artwork included, and the focus target below
            // starts 66dp down — but a parent's onFocusChanged still reports a focused descendant,
            // so it can be read here while the target keeps the geometry the focus search needs.
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .indication(slideInteraction, LocalIndication.current),
    ) {
        TvHeroArtwork(
            media = media,
            // matchParentSize, not fillMaxSize: the hero's height now follows its text, and a
            // child that fills the incoming constraints instead of the settled size left the
            // artwork short of the bottom with a hard edge across the picture.
            modifier = Modifier.matchParentSize(),
            // The top bar floats over the hero, so a cover-only poster starts below it rather
            // than sliding its artwork under the clock and settings icons.
            posterTopPadding = 62,
            wideArtwork = backdrop,
        )
        // Faded into the page's own canvas, not black, so the Dark background meets the hero
        // without a seam where the artwork ends.
        // With a background picture the resting dim goes under the fades, on the artwork alone:
        // over them it would darken the picture the slide fades into, and leave a hard edge where
        // the slide ends and the undimmed picture below carries on. On the plain canvas it stays
        // where it was, over everything, which black absorbs.
        val dimUnderFades = hasAppBackdrop()
        if (dim > 0f && dimUnderFades) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
        }
        // With a background picture, both fade into the picture instead (see appCanvasFade).
        // Solid behind the text, which ends about 43% across, and clear soon after: a landscape
        // picture now starts about where the text ends (see heroPictureGeometry), and the old
        // stops — still 55% dark at 60% across — shaded the left third of it.
        Box(
            Modifier.matchParentSize().appCanvasFade { canvas ->
                Brush.horizontalGradient(
                    0f to canvas,
                    .40f to canvas.copy(.92f),
                    .55f to canvas.copy(.40f),
                    .75f to canvas.copy(.08f),
                    1f to Color.Transparent,
                )
            },
        )
        Box(
            Modifier.matchParentSize().appCanvasFade { canvas ->
                Brush.verticalGradient(
                    0f to canvas.copy(.24f),
                    .58f to Color.Transparent,
                    1f to canvas,
                )
            },
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .width(300.dp)
                .height(104.dp)
                .background(
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(.13f), Color.Transparent),
                    ),
                ),
        )
        // Over the picture and its gradients, under the text: the featured title is worth reading
        // whether or not it holds focus, and a slide whose own name had dimmed with it would look
        // like a loading state rather than like something focus had left.
        if (dim > 0f && !dimUnderFades) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
        }

        // The slide's focus target, deliberately not the whole hero: the top bar floats over the
        // hero's first 62dp, and a target that covered it left the bar with nothing above it to
        // find — Up from the hero stopped moving and the settings gear became unreachable from
        // Home. Starting below the bar puts the bar back in Up's path.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 66.dp)
                .focusRequester(actionFocusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused || it.hasFocus) }
                .onPreviewKeyEvent { event ->
                    // Claimed before the focus system sees them: there is nothing either side of
                    // the hero to move to, so Left and Right belong to the slides.
                    if (event.type != KeyEventType.KeyDown || pageCount <= 1) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionLeft -> { onSlide(-1); true }
                        Key.DirectionRight -> { onSlide(1); true }
                        else -> false
                    }
                }
                .clickable(
                    interactionSource = slideInteraction,
                    indication = null,
                    onClickLabel = "Open ${media.title.preferred}",
                    onClick = { onAnimeClick(media.id) },
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(430.dp)
                .padding(start = TvPagePadding, top = 58.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    // Brand as written; the eyebrow keeps its wide tracking without shouting it.
                    "${stringResource(R.string.app_name)} Featured",
                    color = Color.White.copy(.78f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            // A fixed box, so the text below does not jump while the logo decodes. Exactly as tall as
            // the two lines of title it replaces with their padding, so sliding between a logo and a
            // typed title does not move the rails either; a little narrower than the column, so a
            // wide wordmark reaches the description's width and a tall one stops short of the art.
            SeriesLogo(
                url = logo,
                contentDescription = media.title.preferred,
                alignment = Alignment.BottomStart,
                modifier = Modifier.padding(top = 8.dp).width(340.dp).height(76.dp),
            ) {
                ShrinkingTitle(
                    text = media.title.preferred,
                    style = LocalTextStyle.current.copy(
                        fontSize = 32.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Black,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    minFontSize = 21.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TvHeroMetadata(media)
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = Color.White.copy(.78f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(352.dp).padding(top = 6.dp),
                )
            }
            // What the play button used to say is still worth knowing; it is a fact about the
            // title now rather than a control, so it reads as a line instead of a pill.
            Row(
                modifier = Modifier
                    .padding(top = 14.dp)
                    // Faded rather than removed: the row is part of the hero's fixed height, and a
                    // line that came and went with focus would move the artwork under it.
                    .alpha(1f - dim / TV_HERO_RESTING_DIM * (1f - TV_HERO_RESTING_HINT_ALPHA)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = resume?.let { "Continue Episode ${it.episodeLabel}" } ?: "Open to watch",
                    color = Color.White.copy(.88f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                if (pageCount > 1) {
                    Text(
                        "· Left and Right to browse",
                        color = Color.White.copy(.42f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        if (pageCount > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = TvPagePadding, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { index ->
                    Box(
                        Modifier
                            .height(4.dp)
                            .width(if (index == pageIndex) 20.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pageIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(.32f)
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHeroMetadata(media: Media) {
    val cells = buildList {
        media.averageScore?.takeIf { it > 0 }?.let { add("score" to "$it%") }
        media.format?.let { add("text" to tvFormatLabel(it)) }
        (media.seasonYear ?: media.startDate?.year)?.let { add("text" to it.toString()) }
        media.duration?.takeIf { it > 0 }?.let { add("text" to "${it}m") }
        media.genres.take(2).forEach { add("text" to it) }
    }
    Row(
        modifier = Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        cells.forEachIndexed { index, (kind, label) ->
            if (index > 0) {
                Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(.38f)))
            }
            if (kind == "score") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        label,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            } else {
                Text(
                    label,
                    color = Color.White.copy(.76f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    // "Slice of Life" wrapped onto a second line and pushed the buttons through
                    // the bottom of the hero. The row is a fixed part of a fixed-height box, so a
                    // long genre gives up its tail rather than the layout giving up its shape.
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvMediaRail(
    title: String,
    media: List<Media>,
    onAnimeClick: (Int) -> Unit,
    onPreview: (Media?) -> Unit,
    blockDown: Boolean,
    isLoadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null,
    onFocusUp: (() -> Boolean)? = null,
) {
    // Keyed on the head of the list rather than on all of it, because [onLoadMore] appends to the
    // tail while the viewer is somewhere inside: keying on every id would rebuild the remembered
    // index on arrival of each page and send the next Up-and-back-down all the way to card one.
    // The targets do have to follow the length — indexing past the end of a stale list is a crash
    // rather than a misplaced cursor — and swapping a card's target is free, the focus lives on
    // the node.
    val railIdentity = media.firstOrNull()?.id
    val railKey = "media:$title:${railIdentity ?: -1}"
    var lastFocusedIndex by remember(railIdentity) { mutableIntStateOf(TvFocusMemory.indexOf(railKey)) }
    val itemFocusTargets = remember(railIdentity, media.size) { List(media.size) { TvFocusTarget() } }
    val railState = rememberTvRailState()

    // Back from a show returns to the row and the card the viewer opened it from, not to the first
    // card of the first rail. Consumed once, so a later recomposition does not drag the cursor back.
    LaunchedEffect(railKey) {
        val restore = TvFocusMemory.consumeRestore(railKey) ?: return@LaunchedEffect
        val index = restore.coerceIn(0, media.lastIndex.coerceAtLeast(0))
        railState.scrollToItem(index)
        withFrameNanos { }
        itemFocusTargets.getOrNull(index)
            ?.takeIf(TvFocusTarget::isAttached)
            ?.let { runCatching { it.requester.requestFocus() } }
    }

    if (onLoadMore != null) {
        // Asked for while there is still most of a screenful to travel, so the page is usually
        // already appended by the time the viewer arrives and the rail never visibly stops. The
        // request is a no-op unless a page is genuinely due, so firing it per scroll is fine.
        //
        // Watched from an effect rather than read here: read in composition, the index rebuilt the
        // whole rail the first time it was laid out, which is in the middle of Home's scroll.
        val latestOnLoadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(railState, media.size) {
            snapshotFlow { railState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .distinctUntilChanged()
                .collect { lastVisibleIndex ->
                    if (lastVisibleIndex >= 0 && lastVisibleIndex >= media.size - TV_RAIL_PREFETCH_DISTANCE) {
                        latestOnLoadMore()
                    }
                }
        }
    }

    TvRailScrollSpec {
        Column {
            TvRailTitle(title)
            LazyRow(
                state = railState,
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        val leavingUpwards = event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp
                        if (leavingUpwards && onFocusUp != null) onFocusUp() else false
                    }
                    .focusProperties {
                        enter = { direction ->
                            tvRailEnterRequester(direction, itemFocusTargets, lastFocusedIndex)
                        }
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(media, key = { _, item -> item.id }) { index, item ->
                    TvMediaCard(
                        media = item,
                        onClick = { onAnimeClick(item.id) },
                        onFocused = { focused ->
                            if (focused) {
                                lastFocusedIndex = index
                                TvFocusMemory.focused(railKey, index)
                                onPreview(item)
                            }
                        },
                        modifier = Modifier
                            .tvFocusTarget(itemFocusTargets[index])
                            .then(
                                if (blockDown) {
                                    Modifier.focusProperties { down = FocusRequester.Cancel }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                if (isLoadingMore) {
                    // Deliberately not focusable: it is a note that the rail continues, not somewhere
                    // to land. A remote that could stop on it would be stopping on something that
                    // disappears from under it the moment the page arrives.
                    item(key = "loading-more") {
                        Box(
                            Modifier
                                .width(TvMediaCardWidth)
                                .aspectRatio(TV_MEDIA_CARD_ASPECT_RATIO)
                                .clip(TvCardShape)
                                .background(Color.White.copy(.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvMediaCard(
    media: Media,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = TvMediaCardWidth,
) {
    var focused by remember { mutableStateOf(false) }
    val lowCostTvEffects = LocalAppDeviceProfile.current.useLowCostTvEffects
    val scale = if (lowCostTvEffects) {
        1f
    } else {
        val animatedScale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-media-card-scale")
        animatedScale
    }
    val poster = rememberPosterElements()
    val image = tvMediaCardArtwork(media)

    Column(
        modifier = modifier
            .width(cardWidth)
            .then(
                if (lowCostTvEffects) Modifier else Modifier.zIndex(if (focused) 1f else 0f).scale(scale),
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            // No indication and no clip. The default one painted a grey plate over the whole card,
            // caption included, which read as a box behind the title; the halo on the artwork is
            // the focus cue now, and a clip here would shave that halo off, since it draws
            // outside the poster's edge.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Open ${media.title.preferred}",
                onClick = onClick,
            )
            .padding(bottom = 12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TV_MEDIA_CARD_ASPECT_RATIO)
                .focusHalo(focused, TvCardShape)
                .clip(TvCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color.White.copy(.08f), TvCardShape),
        ) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(.34f),
                    ),
                ),
            )
            if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(media.id)) {
                DubBadge(Modifier.align(Alignment.BottomStart).padding(7.dp))
            }
            media.averageScore?.takeIf { it > 0 && poster.shows(PosterElement.SCORE) }?.let { score ->
                RatingBadge(score, Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
        }
        if (poster.shows(PosterElement.TITLE)) {
            Text(
                media.title.preferred,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                // Two lines, and a fixed height so a one-line title does not pull the row's metadata
                // up level with its neighbours' second line. Most titles that used to end in an
                // ellipsis fit here; a marquee was the other option and was not worth animating text
                // on every focused card on the cheap boxes this has to run on.
                maxLines = 2,
                minLines = 2,
                lineHeight = 18.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (poster.shows(PosterElement.DETAILS)) {
            Text(
                buildList {
                    media.format?.let { add(tvFormatLabel(it)) }
                    (media.seasonYear ?: media.startDate?.year)?.let { add(it.toString()) }
                }.joinToString("  •  ").ifBlank { media.genres.firstOrNull().orEmpty() },
                color = Color.White.copy(.52f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (poster.shows(PosterElement.TITLE)) 2.dp else 8.dp),
            )
        }
    }
}

/**
 * Scrolls a focused item only as far as it takes to show it whole, with [margin] px kept clear at
 * both ends. The rails need the margin: measured against the bare viewport, returning to a rail's
 * first card after scrolling along it parked that card flush against the screen edge, a page
 * padding left of every other rail's first card, and it stayed there.
 */
@OptIn(ExperimentalFoundationApi::class)
private class TvEdgeBringIntoViewSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // A window too narrow for the item falls back to the bare viewport.
        val inset = if (size <= containerSize - 2 * margin) margin else 0f
        val leading = offset - inset
        val window = containerSize - 2 * inset
        val trailingEdge = leading + size
        return when {
            leading >= 0f && trailingEdge <= window -> 0f
            leading < 0f && trailingEdge > window -> 0f
            abs(leading) < abs(trailingEdge - window) -> leading
            else -> trailingEdge - window
        }
    }
}

/**
 * Scrolls a focused item to the middle of the viewport below [topInset] px, which a floating bar
 * covers. An item too tall for that room is lined up just under the bar instead. The list clamps
 * the result, so the first and last rows settle against their ends rather than in the middle.
 */
@OptIn(ExperimentalFoundationApi::class)
private class TvCentredBringIntoViewSpec(private val topInset: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val window = containerSize - topInset
        val target = if (size >= window) topInset else topInset + (window - size) / 2f
        return offset - target
    }
}

/**
 * A TV page's vertical scroller, which keeps the focused card in the middle of the screen whether
 * the remote moved up or down. [topInset] is room a floating bar covers at the top of the scroller;
 * a page already laid out below the bar has none.
 *
 * Every scroller inside inherits this, so a horizontal row in [content] needs its own spec
 * ([TvRailScrollSpec]) or it centres its cards sideways too. No-op off TV.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvCentredScrollSpec(topInset: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!LocalAppDeviceProfile.current.isTv) {
        content()
        return
    }
    val insetPx = with(LocalDensity.current) { topInset.toPx() }
    val spec = remember(insetPx) { TvCentredBringIntoViewSpec(insetPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A rail's LazyRow, scrolled so its cards keep the page padding at either end. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvRailScrollSpec(content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { TvPagePadding.toPx() }
    val spec = remember(margin) { TvEdgeBringIntoViewSpec(margin) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Cards a rail builds ahead while it is still below the screen. Compose's default is two, so the
 * rail scrolling into view built the other four or five in the middle of the page's scroll, and
 * the glide stopped short of centre for those frames and then jumped the rest of the way. Seven
 * is a screen's width of portrait cards.
 */
private const val TV_RAIL_PREFETCH_CARDS = 7

/** A TV rail's LazyRow state, built a screen's width ahead so scrolling down to it does not stall. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberTvRailState(): LazyListState =
    rememberLazyListState(prefetchStrategy = remember { LazyListPrefetchStrategy(TV_RAIL_PREFETCH_CARDS) })

/** TV catalog rails use AniList's portrait cover; wide artwork is reserved for the hero. */
internal fun tvMediaCardArtwork(media: Media): String? = media.coverImage.best

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvContinueRail(
    history: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    blockDown: Boolean,
    onFocusUp: (() -> Boolean)? = null,
    /** Null where the host page heads the rail itself, as My List does with its Expand control. */
    title: String? = "Continue watching",
    /** Empty off Home: My List and the account sheet keep drawing the row without the countdown. */
    nextReleases: Map<Int, NextEpisodeRelease> = emptyMap(),
) {
    var managedEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    managedEntry?.let { entry ->
        ContinueWatchingActionsDialog(
            entry = entry,
            onDismiss = { managedEntry = null },
            onOpenDetails = { onOpenDetails(entry) },
        )
    }

    val historyIds = history.map(HistoryEntry::anilistId)
    val railKey = "continue:${title ?: "untitled"}"
    var lastFocusedIndex by remember(historyIds) { mutableIntStateOf(TvFocusMemory.indexOf(railKey)) }
    val itemFocusTargets = remember(historyIds) { List(history.size) { TvFocusTarget() } }
    // Each card's info button: Up from a card goes there before it leaves the rail.
    val actionRequesters = remember(historyIds) { List(history.size) { FocusRequester() } }
    var actionsFocused by remember { mutableStateOf(false) }
    val railState = rememberContinueRailState(history.firstOrNull()?.anilistId, rememberTvRailState())

    // The player's Back lands here when playback started from this row, so the cursor goes back to
    // the card it started from — see TvFocusMemory.
    LaunchedEffect(railKey) {
        val restore = TvFocusMemory.consumeRestore(railKey) ?: return@LaunchedEffect
        val index = restore.coerceIn(0, history.lastIndex.coerceAtLeast(0))
        railState.scrollToItem(index)
        withFrameNanos { }
        itemFocusTargets.getOrNull(index)
            ?.takeIf(TvFocusTarget::isAttached)
            ?.let { runCatching { it.requester.requestFocus() } }
    }

    TvRailScrollSpec {
        Column {
            if (title != null) TvRailTitle(title)
            LazyRow(
                state = railState,
                modifier = Modifier
                    .onPreviewKeyEvent { event ->
                        val leavingUpwards = event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionUp
                        if (!leavingUpwards) return@onPreviewKeyEvent false
                        // A card's corner buttons sit above its text, so Up is the way into them:
                        // Left and Right stay one press per card. Up again leaves the rail.
                        if (!actionsFocused) {
                            val moved = runCatching {
                                actionRequesters[lastFocusedIndex].requestFocus()
                            }.isSuccess
                            if (moved) return@onPreviewKeyEvent true
                        }
                        if (onFocusUp != null) onFocusUp() else false
                    }
                    .focusProperties {
                        enter = { direction ->
                            tvRailEnterRequester(direction, itemFocusTargets, lastFocusedIndex)
                        }
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = TvPagePadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(history, key = { _, item -> item.anilistId }) { index, entry ->
                    TvContinueCard(
                        entry = entry,
                        nextRelease = nextReleases[entry.anilistId],
                        onClick = { onResume(entry) },
                        onLongClick = { managedEntry = entry },
                        onOpenDetails = { onOpenDetails(entry) },
                        onRemove = { LibraryStore.removeHistory(entry.anilistId, dismissRemoteSeed = true) },
                        actionsRequester = actionRequesters[index],
                        cardRequester = itemFocusTargets[index].requester,
                        onActionsFocused = { focused -> actionsFocused = focused },
                        onFocused = { focused ->
                            if (focused) {
                                lastFocusedIndex = index
                                TvFocusMemory.focused(railKey, index)
                            }
                        },
                        modifier = Modifier
                            .tvFocusTarget(itemFocusTargets[index])
                            .then(
                                if (blockDown) {
                                    Modifier.focusProperties { down = FocusRequester.Cancel }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * A Continue watching card with the cover as its whole background: cropped wide, anchored high so
 * faces survive the crop, and darkened from the left so the text sitting there stays legible. The
 * old card put the same crop in a 16:9 box above a caption, with a chip reading UP NEXT on every
 * card; the text now sits on the art and says which episode and how much of it is left.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvContinueCard(
    entry: HistoryEntry,
    nextRelease: NextEpisodeRelease? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
    actionsRequester: FocusRequester,
    cardRequester: FocusRequester,
    onActionsFocused: (Boolean) -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var actionFocused by remember { mutableStateOf(false) }
    val removeRequester = remember { FocusRequester() }
    val lowCostTvEffects = LocalAppDeviceProfile.current.useLowCostTvEffects
    val scale = if (lowCostTvEffects) {
        1f
    } else {
        val animatedScale by animateFloatAsState(if (focused || actionFocused) 1.045f else 1f, label = "tv-continue-card-scale")
        animatedScale
    }
    val poster = rememberPosterElements()
    val accent = MaterialTheme.colorScheme.primary
    val episodeTitle = entry.episodeTitle
        ?.trim()
        // Titles the provider never named arrive as "Episode 11", which only repeats the "Ep 11" before it.
        ?.takeIf { it.isNotEmpty() && !it.equals("Episode ${entry.episodeLabel}", ignoreCase = true) }
    val remaining = tvContinueTimeLeft(entry)

    Box(
        modifier = modifier
            .width(TvContinueCardWidth)
            .height(TvContinueCardHeight)
            .then(
                if (lowCostTvEffects) Modifier else Modifier.zIndex(if (focused) 1f else 0f).scale(scale),
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            // Ahead of the clip, which would otherwise shave off a ring drawn outside the card.
            .focusHalo(focused, TvCardShape)
            .clip(TvCardShape)
            // Off while a corner button holds the cursor: this reads keys ahead of its children,
            // so OK on "remove" would otherwise resume the episode instead.
            .dpadClickable(enabled = !actionFocused, onClick = onClick, onLongClick = onLongClick)
            .combinedClickable(
                onClickLabel = "Resume ${entry.title}",
                onLongClickLabel = "Manage Continue Watching",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = entry.cover,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, -0.45f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(.94f),
                        .5f to Color.Black.copy(if (focused) .68f else .78f),
                        1f to Color.Black.copy(if (focused) .1f else .35f),
                    ),
                ),
        )
        Row(
            Modifier.padding(start = 18.dp, end = 72.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvContinueCardAction(
                icon = Icons.Outlined.Info,
                contentDescription = "Details for ${entry.title}",
                onClick = onOpenDetails,
                requester = actionsRequester,
                left = null,
                right = removeRequester,
                down = cardRequester,
                onFocusChange = { actionFocused = it; onActionsFocused(it) },
                modifier = Modifier.padding(end = 8.dp),
            )
            if (remaining != null) {
                Text(
                    remaining,
                    color = Color.White.copy(.75f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Stacked from the bottom, below the corner buttons' row: the episode line is measured first
        // and always shows; the series title takes what height is left (two lines at the default font
        // size, one under a larger system font) rather than pushing the episode off the card's foot.
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 72.dp, top = 42.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // The episode reads as one line under the series, "Ep 3 · Girl of Steel": it used to be a
            // pill up in the corner by the info button, away from the title it belongs to. With no
            // title to give, or titles turned off, the number stands alone.
            val shownTitle = episodeTitle?.takeIf { poster.shows(PosterElement.DETAILS) }
            if (poster.shows(PosterElement.TITLE)) {
                Text(
                    entry.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    maxLines = if (shownTitle != null || nextRelease != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                if (shownTitle != null) "Ep ${entry.episodeLabel} · $shownTitle" else "Ep ${entry.episodeLabel}",
                color = Color.White.copy(.72f),
                fontSize = 13.sp,
                // Its own: the theme's 24sp line under a 13sp size made this line 26dp, and with a
                // two-line title the card (118dp) ran out of height and cut it in half.
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // How long until the next episode: the question this row raises and only the show's own
            // page used to answer. The title gives up a line for it — two lines of title, the
            // episode line and this one do not fit the card's 118dp.
            nextRelease?.let { release ->
                Text(
                    nextReleaseLabel(release),
                    color = Color.White.copy(.88f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .clearAndSetSemantics { contentDescription = spokenNextReleaseLabel(release) },
                )
            }
        }
        TvContinueCardAction(
            icon = Icons.Default.Close,
            contentDescription = "Remove ${entry.title} from Continue watching",
            onClick = onRemove,
            requester = removeRequester,
            left = actionsRequester,
            right = null,
            down = cardRequester,
            onFocusChange = { actionFocused = it; onActionsFocused(it) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp),
        )
        if (focused) {
            Box(
                Modifier
                    // Down in the corner, clear of the remove button above it and just over the
                    // progress bar; centred, its top edge all but touched that button.
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 16.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        // Flush along the bottom edge, where a playback bar sits on the frame it belongs to.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(5.dp)
                .background(Color.White.copy(.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(entry.progressFraction.coerceAtLeast(.04f))
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, Color.White.copy(.08f), TvCardShape),
        )
    }
}

/**
 * A corner button on a Continue watching card: details on the left, remove on the right.
 *
 * Reached from the card with Up, so Left and Right along the rail stay one press per card; inside,
 * Left and Right move between the two and stop there, and Down goes back to the card. A pointer —
 * a TV box's mouse or air-mouse — clicks them directly. The disc keeps the glyph legible over
 * artwork that may be bright, and fills white under the cursor like every other TV control.
 */
@Composable
private fun TvContinueCardAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    requester: FocusRequester,
    left: FocusRequester?,
    right: FocusRequester?,
    down: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(30.dp)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionLeft -> left ?: return@onPreviewKeyEvent true
                    Key.DirectionRight -> right ?: return@onPreviewKeyEvent true
                    Key.DirectionDown -> down
                    else -> return@onPreviewKeyEvent false
                }
                runCatching { target.requestFocus() }
                true
            }
            // The television's ring rather than a white fill; ahead of the clip, which would
            // shave off a ring drawn outside the button.
            .focusHalo(focused, CircleShape, FocusRingGap)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (focused) .75f else .55f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * "18 min left" for a part-watched episode, or null when there is no clock to go by — rows
 * seeded from AniList/MAL carry no position, and a bare "EP 6" says as much as can be said.
 */
internal fun tvContinueTimeLeft(entry: HistoryEntry): String? {
    if (entry.durationMs <= 0 || entry.positionMs <= 0) return null
    val leftMinutes = ((entry.durationMs - entry.positionMs).coerceAtLeast(0) + 59_999) / 60_000
    return if (leftMinutes <= 1) "Almost done" else "$leftMinutes min left"
}

@Composable
private fun TvRailTitle(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TvPagePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(.7f)),
        )
    }
}

private fun tvFormatLabel(format: String): String = when (format) {
    "TV" -> "TV"
    "TV_SHORT" -> "TV Short"
    "MOVIE" -> "Movie"
    "SPECIAL" -> "Special"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "MUSIC" -> "Music"
    else -> format.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Which card a rail hands focus to when focus enters it travelling in [direction].
 *
 * Up and Down give the press straight back to the spatial search, so the cursor lands on the card
 * the viewer is looking at — the one directly above or below the card they are leaving. Restoring
 * the remembered card on a vertical press moved the cursor sideways as well: from the fourth card
 * of a rail, Down landed on the *first* card of the next one, because that rail had last been left
 * at its start. Two rails at different scroll offsets then had no path between them that a viewer
 * could predict, and walking down the page drifted back to the left edge on every row.
 *
 * Every other way in keeps the remembered card, because those arrivals carry no column to align
 * to: the top bar handing Home's content back, or a return from a title's page. There the card the
 * viewer last had is the only answer, and landing on it is what makes Back feel like a step back.
 */
internal fun tvRailEnterRequester(
    direction: FocusDirection,
    targets: List<TvFocusTarget>,
    index: Int,
): FocusRequester = when (direction) {
    FocusDirection.Up, FocusDirection.Down -> FocusRequester.Default
    else -> rememberedTvRailRequester(targets, index)
}

/**
 * A lazy rail can remember an index after that card has been recycled. Returning an unattached
 * [FocusRequester] from `focusProperties.enter` crashes inside Compose's focus owner, where the
 * request cannot be caught. Restore the remembered card only while it is still a real target;
 * otherwise let normal spatial focus choose a currently composed card.
 */
internal fun rememberedTvRailRequester(
    targets: List<TvFocusTarget>,
    index: Int,
): FocusRequester = targets.getOrNull(index)
    ?.takeIf(TvFocusTarget::isAttached)
    ?.requester
    ?: FocusRequester.Default

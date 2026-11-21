@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.detail

import com.anilili.ui.adaptive.FocusFillRest
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.anilili.R
import com.anilili.data.library.EpisodeStop
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.model.StudioNode
import com.anilili.data.model.EPISODE_WATCHABLE_AFTER_SEC
import com.anilili.data.model.episodeIsUnaired
import com.anilili.data.settings.EpisodeOrder
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.FocusFillColor
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.adaptive.TvCardCornerShape
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.TvPanelCornerShape
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.tvFocusHandoff
import com.anilili.ui.adaptive.tvFocusTarget
import com.anilili.ui.components.compactMemberCount
import com.anilili.ui.components.AnimeListActionsDialog
import com.anilili.ui.components.BulkDownloadStatus
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.EpisodeFillerBadge
import com.anilili.ui.components.EpisodeWatchedToggle
import com.anilili.ui.components.formatCompactCountdown
import com.anilili.ui.components.rememberTickingEpochSeconds
import com.anilili.ui.components.spokenAiringCountdown
import com.anilili.ui.components.spokenDuration
import com.anilili.ui.components.SeriesLogo
import com.anilili.ui.components.ShrinkingTitle
import com.anilili.ui.components.SynopsisDialog
import com.anilili.ui.components.TvHeroArtwork
import com.anilili.ui.components.UNAIRED_ALPHA
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.blockIndexOf
import com.anilili.ui.components.openingEpisode
import com.anilili.ui.home.rememberedTvRailRequester
import com.anilili.ui.components.episodeArtworkFallback
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeMarkedWatched
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.filterEpisodes
import com.anilili.ui.components.orderEpisodeBlocks
import com.anilili.ui.components.orderEpisodes
import com.anilili.ui.components.toggleEpisodeWatched
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch
import com.anilili.util.cached

private val TvDetailPadding = 48.dp

private val TvDetailCardShape = TvCardCornerShape
private val TvEpisodeCardWidth = 204.dp

@OptIn(ExperimentalFoundationApi::class)
/**
 * Which strip the foot of the page is showing.
 *
 * Tabs rather than one long column, because this layout gives the episodes the whole width and the
 * header the whole height above them: there is nowhere left to stack seasons and recommendations,
 * and a viewer looking for either is looking for them deliberately.
 */
private enum class TvDetailTab(val label: String) {
    EPISODES("Episodes"),
    RELATED("Related"),
}

/**
 * The card the cursor was last on, kept with the page while another destination is on top of it.
 *
 * A marker rather than state because it changes on every move along a strip and nothing draws it:
 * it is only read once, when the page is composed again after Back, to put the cursor where it was
 * instead of on Watch. See [tvEpisodeFocusMarker] and [tvRelatedFocusMarker] for what it holds.
 */
internal class TvFocusMemory(var marker: String? = null) {
    companion object {
        val Saver: Saver<TvFocusMemory, String> = Saver(
            save = { it.marker },
            restore = { TvFocusMemory(it) },
        )
    }
}

private const val EPISODE_FOCUS_MARKER = "episode:"
private const val RELATED_FOCUS_MARKER = "related:"

/** The focus marker for an episode card, by its pipe id. */
internal fun tvEpisodeFocusMarker(pipeId: String): String = EPISODE_FOCUS_MARKER + pipeId

/** The focus marker for a Related card, by its AniList id. */
internal fun tvRelatedFocusMarker(mediaId: Int): String = RELATED_FOCUS_MARKER + mediaId

/** The episode pipe id [marker] names, or null when it names none. */
internal fun tvFocusMarkerEpisode(marker: String?): String? =
    marker?.takeIf { it.startsWith(EPISODE_FOCUS_MARKER) }?.removePrefix(EPISODE_FOCUS_MARKER)?.ifEmpty { null }

/** The Related card's AniList id [marker] names, or null when it names none. */
internal fun tvFocusMarkerRelated(marker: String?): Int? =
    marker?.takeIf { it.startsWith(RELATED_FOCUS_MARKER) }?.removePrefix(RELATED_FOCUS_MARKER)?.toIntOrNull()

/**
 * How many repeats of a held Left/Right pass before the strip starts jumping instead of stepping,
 * and how many more between each jump after that.
 *
 * Android repeats a held key about every 50ms. Twelve of those is a little over half a second —
 * long enough that a viewer pressing Right twice quickly still gets two episodes, and short enough
 * that holding it feels like it took hold rather than stuck. The gap between jumps is what stops a
 * 1100-episode series scrolling past faster than anyone can read it.
 */
/**
 * How many episodes a season needs before the strip offers range chips and a search at all.
 *
 * A thirteen-episode season is four presses of Right from end to end, and a row reading "1 – 13"
 * over it is a control that can only select what is already selected — it says nothing and costs a
 * line of a screen that has none to spare. The phone browser's own threshold is lower because a
 * finger flicks through a vertical list differently than a remote walks a strip.
 */
private const val TV_EPISODE_CHIPS_MIN_EPISODES = 30

/** Frames to wait for the Find field or chip to be composed before handing it the cursor. */
private const val FIND_FOCUS_ATTEMPTS = 30

/**
 * Frames the page keeps asking for its first focus — the Watch button, or the card the player's
 * Back named — before it gives up. The same budget the navigation bar spends: slow Fire TV boxes
 * run the first request before the node is attached, and one swallowed attempt left nothing
 * focused and a remote that did nothing.
 */
private const val TV_DETAIL_FOCUS_ATTEMPTS = 10

/**
 * Asks [request] once a frame, up to [attempts] times, until it answers that focus landed.
 * Returns the attempt it landed on, or null when it never did.
 */
private suspend fun retryTvFocus(attempts: Int, request: () -> Boolean): Int? {
    repeat(attempts) { attempt ->
        if (request()) return attempt + 1
        withFrameNanos { }
    }
    return null
}

private const val TV_HOLD_STEP_AFTER = 12
private const val TV_HOLD_STEP_EVERY = 3

/** How far one jump travels, widening while the key stays down. */
private fun tvHoldStepSize(repeats: Int): Int = when {
    repeats < TV_HOLD_STEP_AFTER + 15 -> 3
    repeats < TV_HOLD_STEP_AFTER + 45 -> 8
    else -> 20
}

/**
 * Turns a held Left or Right into jumps of several items once it has been down long enough.
 *
 * A remote's own repeat moves the cursor one card per repeat, which on a series with a thousand
 * episodes is a viewer holding a button for four minutes. [step] is asked to travel instead, and
 * answers whether it could: at either end of the strip it says no and the press goes back to
 * ordinary focus search, so holding Right at the last episode still leaves the strip rather than
 * dying against its edge.
 *
 * The repeats *between* jumps are swallowed deliberately. Letting them through would move the
 * cursor a card at a time in the gaps, so the strip would crawl and leap at once.
 */
private fun Modifier.tvHoldToStep(step: (Int) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val direction = when (event.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> return@onPreviewKeyEvent false
    }
    val repeats = event.nativeKeyEvent.repeatCount
    if (repeats < TV_HOLD_STEP_AFTER) return@onPreviewKeyEvent false
    if ((repeats - TV_HOLD_STEP_AFTER) % TV_HOLD_STEP_EVERY != 0) return@onPreviewKeyEvent true
    step(direction * tvHoldStepSize(repeats))
}

/**
 * Cinematic 10-foot title page: one picture, one episode described over it, and a strip along the
 * foot for everything the page can move to.
 *
 * The two-pane layout this replaced put the title's own metadata on the left and a vertical episode
 * list on the right, which spent half the screen on text a viewer reads once and gave the episodes
 * — the reason the page is open — a column narrow enough for two of them to be on screen at a time.
 * Here the episodes run the full width, and the space above them describes whichever one the cursor
 * is on: its number, its name, when it aired, how long it runs, what it is about and what viewers
 * scored it. Nothing is hidden that was not hidden before; the series' own synopsis moves under the
 * header's title when no episode is selected yet, which is exactly when a viewer wants it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvDetailContent(
    data: DetailData,
    saved: Boolean,
    listStatusLabel: String?,
    history: List<HistoryEntry>,
    onPlay: (animeId: Int, provider: String, category: String, episode: String) -> Unit,
    onAnimeClick: (Int) -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    /** The providers' episode listing for a season, fetched only when a download needs it. */
    loadProviderCatalog: suspend (Int) -> EpisodesResult,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
    /** The episode the player's Back was left on, when that is how the viewer arrived. */
    focusEpisode: String? = null,
) {
    // The page opens on its own anime, but the season chips move it: everything here follows the
    // selected season — its artwork, its watch action, its library entry — while `data.info` stays
    // what the page was opened on and is only what the season chain is anchored to.
    val season = data.selectedSeason
    val isMovie = isMovieFormat(season.format)
    val episodes = data.episodes
    val seasonResume = history.firstOrNull { it.anilistId == data.selectedSeasonId }
    // Keyed on the rows rather than on the season id alone: enrichment replaces every EpisodeItem
    // with a copy carrying its title and still, and state memoised on the ids alone would hold the
    // blocks computed from the bare skeleton for as long as the page was open.
    val episodeOrder by SettingsStore.episodeOrder.collectAsState()
    val blocks = remember(episodes, episodeOrder) { orderEpisodeBlocks(episodeBlocks(episodes), episodeOrder) }
    // Back from the player names the episode it left, so the page can open exactly where the
    // viewer was even before their history write lands: the block holding it is chosen first, the
    // header describes it, and the strip puts the cursor on it.
    val arrivalEpisode = focusEpisode
        ?.takeUnless { isMovie }
        ?.let { label -> episodes.firstOrNull { it.displayNumber == label } }
    // Otherwise where the viewer left off, or a long series' newest aired episode for someone with
    // no history of it (see [openingEpisode]).
    val startEpisode = remember(episodes, seasonResume?.episodeNumber, season) {
        openingEpisode(episodes, seasonResume?.episodeNumber, isUnaired = { episodeIsUnaired(it, season) })
    }
    // Keyed on the order too: an index into the ranges names a different range once they turn round.
    // Not keyed on the rows themselves: a revalidation or an airing appends one a second or two
    // after an airing show opens, and that reset the range the viewer had chosen. Only the season
    // gaining its first rows (an uncounted or switched season finishing its load) re-chooses, so
    // the opening range still follows the resume point; the index is clamped below.
    //
    // This and the page state under it are saveable: navigation drops plain `remember` while another
    // page is on top, so Detail → a Related title → Back came back on the Episodes tab, the first
    // shelf and the opening range, and a typed search was gone.
    var selectedBlockIndex by rememberSaveable(data.selectedSeasonId, episodes.isEmpty(), episodeOrder) {
        mutableIntStateOf(
            arrivalEpisode
                ?.let { episode ->
                    blocks.indexOfFirst { block -> block.episodes.any { it.pipeId == episode.pipeId } }
                }
                ?.takeIf { it >= 0 }
                ?: blockIndexOf(blocks, startEpisode),
        )
    }
    val blockIndex = selectedBlockIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    // The season only, for the same reason: an appended row must not wipe what the viewer typed.
    var query by rememberSaveable(data.selectedSeasonId) { mutableStateOf("") }
    var searching by remember(data.selectedSeasonId) { mutableStateOf(false) }
    var tab by rememberSaveable(data.info.id) { mutableStateOf(TvDetailTab.EPISODES) }
    var relatedFilter by rememberSaveable(data.info.id) { mutableStateOf(RelatedFilter.ALL) }
    // What the cursor was last on — an episode card or a Related card — kept across a trip to
    // another page so Back can put it there again (see [TvFocusMemory]). A plain holder rather than
    // state: it is written on every move and must not recompose the page for it.
    val focusMemory = rememberSaveable(data.info.id, saver = TvFocusMemory.Saver) { TvFocusMemory() }
    // What it held when this page was composed: null on a first visit, the card to return to on Back.
    val restoreFocus = remember { focusMemory.marker }
    var restorePending by remember { mutableStateOf(restoreFocus != null) }
    // Remembered, as is everything below that is derived from the rows: the page used to recompose
    // on every step along the strip, and each pass re-filtered the season and handed the rows new
    // lists. It no longer recomposes for a step (only the header and backdrop read the cursor),
    // but a list kept to its inputs is still what lets the strip and the tab row skip.
    val shownEpisodes = remember(isMovie, query, episodes, episodeOrder, blocks, blockIndex) {
        when {
            isMovie -> emptyList()
            query.isNotBlank() -> orderEpisodes(filterEpisodes(episodes, query), episodeOrder)
            else -> blocks.getOrNull(blockIndex)?.episodes.orEmpty()
        }
    }
    val blockLabels = remember(blocks) { blocks.map { it.label } }
    val newestEpisodeNumber = remember(episodes) { episodes.maxOfOrNull(EpisodeItem::number) }
    // The next episode in the schedule, as a card of its own when no row stands for it yet — the
    // usual case, because the list is counted from AniList's schedule and stops at the last aired
    // episode. It takes the slot the episode will fill, after the newest one, and is only drawn
    // with the rows it follows: not beside another range, and not in the middle of a search.
    val upcomingEpisode = remember(season, episodes) { tvUpcomingEpisode(season, episodes) }
        ?.takeIf { !isMovie && query.isBlank() }
        ?.takeIf { episodes.isEmpty() || shownEpisodes.any { it.number == newestEpisodeNumber } }

    // Which episode the header is describing. Held by id rather than by value so the description
    // follows enrichment: the row under the cursor is replaced by a copy the moment its title and
    // synopsis land, and a header holding the old instance would go on describing the skeleton.
    var focusedId by rememberSaveable(data.selectedSeasonId) { mutableStateOf<String?>(null) }
    // Until the strip reports the cursor the arrival asked for, describe the episode it is about
    // to land on rather than the season. Also the moment the Back arrow is allowed into the focus
    // search: the strip, not the primary action, is what holds the cursor on this arrival.
    LaunchedEffect(arrivalEpisode?.pipeId) {
        arrivalEpisode?.let { arrival ->
            focusedId = arrival.pipeId
            onPrimaryFocusAcquired()
            // The arrival wins over whatever the page was left on. A page restored from the back
            // stack reopens on the tab, range and search it was left showing, and the player may
            // have moved on past that range (or been started from Watch on the Related tab), so
            // the episode it names is put on show before the strip looks for its card.
            tab = TvDetailTab.EPISODES
            if (shownEpisodes.none { it.pipeId == arrival.pipeId }) {
                query = ""
                blocks.indexOfFirst { block -> block.episodes.any { it.pipeId == arrival.pipeId } }
                    .takeIf { it >= 0 }
                    ?.let { selectedBlockIndex = it }
            }
        }
    }
    // Deliberately no fallback to the first episode. Until the cursor reaches the strip the page
    // is describing the season — its score, whether it is still airing, what it is — which is what
    // a viewer arriving from Home wants to read. Guessing at episode one instead would answer a
    // question nobody had asked yet and hide the one they had.
    //
    // A lambda rather than a value: [focusedId] changes on every step along the strip, and read
    // here it recomposed the whole page — the tabs, the strip, every derived list — per press. Read
    // inside the header and the backdrop, only those two recompose for it.
    val described: () -> EpisodeItem? = {
        focusedId?.let { id ->
            episodes.firstOrNull { it.pipeId == id } ?: upcomingEpisode?.takeIf { it.pipeId == id }
        }
    }

    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    val seasonArtwork = season.bannerImage ?: season.coverImage.best
    val fallbackImage = episodeArtworkFallback(episodes, seasonArtwork)
    // The still of the episode under the cursor becomes the page's backdrop — but only while the
    // viewer has asked to see episode art at all. Blurring a full-screen backdrop would leave the
    // page looking broken rather than spoiler-free, so the season's own artwork stands in instead.
    //
    // The season's artwork, not [fallbackImage]. That one is withheld when no episode has a still,
    // so the cards do not repeat one picture a dozen times — a rule about a list of cards, and it
    // was blanking the backdrop along with them: Aware! Meisaku-kun (2026), twelve episodes and no
    // stills anywhere, opened on a black page while its unreleased sequel, with no episodes to
    // trip the rule, showed its cover. One backdrop cannot look repeated.
    val backdrop: () -> Any? = { described()?.image?.takeIf { !blurEpisodeImages } ?: seasonArtwork }

    val saveActionFocusRequester = remember { FocusRequester() }
    val canWatch = episodes.isNotEmpty() || seasonResume != null
    val currentCanWatch by rememberUpdatedState(canWatch)
    // Whether anything on the page holds the cursor. Read straight after each request rather than
    // observed — focus events are dispatched inside requestFocus — so a request that did nothing
    // (a disabled button, a node not attached yet) is told apart from one that landed.
    val pageFocused = remember { mutableStateOf(false) }
    val inputModeManager = LocalInputModeManager.current
    val focusScope = rememberCoroutineScope()
    // Mouse navigation leaves Compose in Touch mode, so the page asks for Keyboard mode first.
    // Watch is disabled on an announced season and on an uncounted one still loading, and a request
    // on a disabled button lands nowhere; the list button beside it is always there to take it.
    val focusFirstAction: suspend () -> Unit = {
        val keyboardMode = inputModeManager.requestInputMode(InputMode.Keyboard)
        var target = "watch"
        val attempts = retryTvFocus(TV_DETAIL_FOCUS_ATTEMPTS) {
            val watch = currentCanWatch
            target = if (watch) "watch" else "list"
            runCatching { (if (watch) primaryActionFocusRequester else saveActionFocusRequester).requestFocus() }
            pageFocused.value
        }
        DiagnosticsLog.event(
            "Detail primary action focus requested keyboardMode=$keyboardMode target=$target " +
                "landed=${attempts != null} attempts=${attempts ?: TV_DETAIL_FOCUS_ATTEMPTS}",
        )
    }
    // A page returned to puts the cursor back on the card it was left on, through the strip's or
    // the rail's own targets, instead of on Watch. Only for the tab that card lives on, and only
    // until the first attempt is over; an arrival from the player wins over it.
    val restoreEpisodePipeId = tvFocusMarkerEpisode(restoreFocus)
        ?.takeIf { restorePending && arrivalEpisode == null && tab == TvDetailTab.EPISODES && !isMovie }
    val restoreRelatedId = tvFocusMarkerRelated(restoreFocus)
        ?.takeIf { restorePending && arrivalEpisode == null && tab == TvDetailTab.RELATED }
    val onRestoreResult: (Boolean) -> Unit = { landed ->
        restorePending = false
        if (!landed) focusScope.launch { focusFirstAction() }
    }
    // An arrival from the player hands the cursor to the episode strip instead, so this must not
    // fire alongside it: the two would race and whichever landed last would win. The strip and the
    // rail hand back here if their card never takes the cursor.
    LaunchedEffect(arrivalEpisode != null) {
        when {
            arrivalEpisode != null -> restorePending = false
            restoreEpisodePipeId != null || restoreRelatedId != null -> Unit
            else -> {
                restorePending = false
                focusFirstAction()
            }
        }
    }
    // Where Up leaves the episode strip for. Compose's own search out of the range chips finds no
    // candidate above and drops focus entirely — measured on device, the dump holds no focused node
    // at all afterwards — so the tab strip is named explicitly, the same hand-off the home rails use.
    // The target rides the chip for the tab on display, because that is the row Up is asking for.
    val tabsFocusTarget = remember { TvFocusTarget() }
    // The chip for the season on show, in the tab row. Held here because the chip is drawn by the
    // tab row while the range chips under it hand their Up to it.
    val seasonRowTarget = remember { TvFocusTarget() }
    val toggleWatched: (EpisodeItem) -> Unit = { episode ->
        toggleEpisodeWatched(
            anilistId = data.selectedSeasonId,
            episodeNumber = episode.number,
            currentlyWatched = episodeMarkedWatched(seasonResume, episode.number),
            title = season.title.preferred,
            cover = season.coverImage.best,
            totalEpisodes = season.episodes,
            airedEpisodes = episodes
                .filterNot { episodeIsUnaired(it, season) }
                .map { EpisodeStop(it.number, it.distinctTitle) },
        )
    }
    val playEpisode: (EpisodeItem) -> Unit = { episode ->
        onPlay(data.selectedSeasonId, "auto", data.preferredCategory.api, episode.displayNumber)
    }
    val playCurrent: () -> Unit = {
        when {
            seasonResume != null -> onPlay(
                data.selectedSeasonId,
                seasonResume.provider,
                seasonResume.category,
                seasonResume.episodeLabel,
            )
            episodes.isNotEmpty() -> onPlay(
                data.selectedSeasonId,
                "auto",
                data.preferredCategory.api,
                episodes.first().displayNumber,
            )
        }
    }

    // A movie has no strip to scope a batch by, so its one entry is the whole of it.
    // Turned back into running order, so a batch still queues episode 1 before episode 2 whichever
    // way round the strip is drawn.
    val downloadScope = remember(isMovie, episodes, shownEpisodes, episodeOrder) {
        if (isMovie) episodes else orderEpisodes(shownEpisodes, episodeOrder)
    }
    val downloads = rememberSeasonDownloads(
        data = data,
        seasonMedia = season,
        shown = downloadScope,
        scopeLabel = seasonDownloadScopeLabel(
            seasonLabel = data.seasonLabels[data.selectedSeasonId].takeIf { data.seasons.size > 1 },
            blockLabel = blocks.getOrNull(blockIndex)?.label
                .takeIf { blocks.size > 1 && query.isBlank() },
            filterQuery = query,
            shownCount = downloadScope.size,
        ),
        loadProviderCatalog = loadProviderCatalog,
        preselectAll = false,
    )
    // The Related tab as the phone has it: the rest of the franchise as one release order, the films
    // and extras between the seasons they aired between, then AniList's community picks, with a
    // chip per shelf. The season on show is not among them:
    // its own chip already selects it, and a card that opened a second page for what the header is
    // describing was only ever a way to lose the one you were on.
    val watchOrder = data.watchOrder
    val franchise = remember(watchOrder, season.id) { watchOrder.filter { it.media.id != season.id } }
    val allSuggestions = data.suggestions
    val suggestions = remember(allSuggestions, franchise, season.id) {
        val franchiseIds = franchise.mapTo(HashSet()) { it.media.id }
        allSuggestions.filter { it.id != season.id && it.id !in franchiseIds }
    }
    val relatedFilters = remember(franchise, suggestions) {
        RelatedFilter.entries.filter { filter ->
            when (filter) {
                RelatedFilter.ALL -> true
                RelatedFilter.SUGGESTIONS -> suggestions.isNotEmpty()
                else -> franchise.any { it.group == filter.group }
            }
        }
    }
    // A chip can lose its shelf between loads (the series walk replaces the seeded chain), and a
    // filter pinned to an empty shelf would show an empty rail.
    val activeRelatedFilter = if (relatedFilter in relatedFilters) relatedFilter else RelatedFilter.ALL
    val relatedCards = remember(franchise, suggestions, activeRelatedFilter) { buildList {
        if (activeRelatedFilter != RelatedFilter.SUGGESTIONS) {
            franchise
                .filter { activeRelatedFilter == RelatedFilter.ALL || it.group == activeRelatedFilter.group }
                // One shelf name for the whole run: it is interleaved, so a name per kind would
                // change at nearly every card. The kind pill says which is a film.
                .forEach { add(TvRelatedCard(it.media, "Release order", it.kindLabel(), it.detailLine())) }
        }
        if (activeRelatedFilter == RelatedFilter.ALL || activeRelatedFilter == RelatedFilter.SUGGESTIONS) {
            suggestions.forEach { media ->
                add(
                    TvRelatedCard(
                        media = media,
                        shelf = RelatedFilter.SUGGESTIONS.label,
                        kind = media.format?.let(::tvDetailPretty),
                        details = media.yearAndEpisodes().joinToString(" · "),
                    ),
                )
            }
        }
    }.distinctBy { it.media.id } }

    val canvas = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { pageFocused.value = it.hasFocus }
            .background(canvas),
    ) {
        TvDetailBackdrop(backdrop)
        // Two gradients rather than one: the horizontal keeps the header legible over whatever the
        // still happens to be, and the vertical darkens the lower half a little so the strip's
        // cards stand off the picture. Only a little: the strip ends well above the bottom edge, and
        // fading the foot almost to the canvas left a black band across the screen under it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to canvas.copy(alpha = 0.93f),
                    .52f to canvas.copy(alpha = 0.72f),
                    1f to canvas.copy(alpha = 0.38f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to canvas.copy(alpha = 0.46f),
                    .38f to canvas.copy(alpha = 0.30f),
                    .70f to canvas.copy(alpha = 0.42f),
                    1f to canvas.copy(alpha = 0.55f),
                ),
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // The top bar floats over this page as it does over Home, so the header starts
                // below it rather than sliding its logo under the clock.
                .padding(top = 62.dp, bottom = 12.dp),
        ) {
            TvDetailHeader(
                // The header takes the height its own text needs and no more, so everything under
                // it — the buttons, the tabs, the strip — rides up against a one-line summary and
                // steps back down for a two-line one. The weight is still what stops a long title
                // from squeezing the strip off the bottom of the screen.
                modifier = Modifier.weight(1f, fill = false),
                season = season,
                logo = data.logo,
                episode = { if (isMovie) null else described() },
                isMovie = isMovie,
                resume = seasonResume,
                saved = saved,
                listStatusLabel = listStatusLabel,
                canWatch = canWatch,
                onWatch = playCurrent,
                onStudioClick = onStudioClick,
                downloadPreparing = downloads.batchPreparing,
                // Only with the strip it orders on screen, and only when there is an order to turn.
                episodeOrder = episodeOrder.takeIf { tab == TvDetailTab.EPISODES && !isMovie && episodes.size > 1 },
                onToggleOrder = { SettingsStore.setEpisodeOrder(episodeOrder.toggled()) },
                onDownload = downloads.openBatch.takeIf { downloadScope.isNotEmpty() },
                primaryActionFocusRequester = primaryActionFocusRequester,
                saveActionFocusRequester = saveActionFocusRequester,
                onPrimaryFocusAcquired = {
                    // Watch is where a page opens anyway, so a page left from it keeps no marker.
                    focusMemory.marker = null
                    onPrimaryFocusAcquired()
                },
            )

            TvDetailTabs(
                selected = tab,
                // Related is only worth a stop when there is something behind it; the seasons ride
                // this same row, after the tabs, rather than behind a tab of their own.
                tabs = TvDetailTab.entries.filter { entry ->
                    when (entry) {
                        TvDetailTab.EPISODES -> !isMovie
                        TvDetailTab.RELATED -> franchise.isNotEmpty() || suggestions.isNotEmpty()
                    }
                },
                onSelect = { tab = it },
                selectedChipTarget = tabsFocusTarget,
                // Only beside the episodes they switch between: on Related a season chip would
                // change the header while nothing under it did.
                seasons = if (tab == TvDetailTab.EPISODES && !isMovie) data.seasons else emptyList(),
                seasonLabels = data.seasonLabels,
                selectedSeasonId = data.selectedSeasonId,
                onSelectSeason = onSelectSeason,
                selectedSeasonTarget = seasonRowTarget,
                // The shelves take the seasons' place on Related. One shelf and nothing else is the
                // whole rail, and chips that only ever say "All" are furniture.
                relatedFilters = relatedFilters.takeIf { tab == TvDetailTab.RELATED && it.size > 2 }.orEmpty(),
                selectedRelatedFilter = activeRelatedFilter,
                onSelectRelatedFilter = { relatedFilter = it },
            )

            when (tab) {
                TvDetailTab.EPISODES -> TvEpisodeStrip(
                    episodes = shownEpisodes,
                    allEpisodeCount = episodes.size,
                    season = season,
                    blocks = blockLabels,
                    selectedBlockIndex = blockIndex,
                    query = query,
                    searching = searching,
                    fallbackImage = fallbackImage,
                    resume = seasonResume,
                    loading = data.seasonEpisodesLoading,
                    onSelectBlock = {
                        query = ""
                        selectedBlockIndex = it
                    },
                    onQueryChange = { query = it },
                    onSearchingChange = { searching = it },
                    onEpisodeFocused = {
                        focusedId = it.pipeId
                        focusMemory.marker = tvEpisodeFocusMarker(it.pipeId)
                    },
                    onPlay = playEpisode,
                    onToggleWatched = toggleWatched,
                    arrivalFocusPipeId = arrivalEpisode?.pipeId,
                    restoreFocusPipeId = restoreEpisodePipeId,
                    onInitialFocusResult = { landed ->
                        if (restoreEpisodePipeId != null) {
                            onRestoreResult(landed)
                        } else if (!landed) {
                            focusScope.launch { focusFirstAction() }
                        }
                    },
                    openOnPipeId = startEpisode?.pipeId,
                    upFocusTarget = tabsFocusTarget,
                    seasons = data.seasons,
                    seasonLabels = data.seasonLabels,
                    selectedSeasonId = data.selectedSeasonId,
                    onSelectSeason = onSelectSeason,
                    selectedSeasonTarget = seasonRowTarget,
                    upcomingEpisode = upcomingEpisode,
                    upcomingFirst = episodeOrder == EpisodeOrder.NEWEST_FIRST,
                )
                TvDetailTab.RELATED -> TvRelatedCompactRail(
                    related = relatedCards,
                    // Under "All" the release order and the community picks run on in one rail, so
                    // each is named where it starts; a single shelf is already named by its chip.
                    labelShelves = activeRelatedFilter == RelatedFilter.ALL &&
                        relatedCards.distinctBy(TvRelatedCard::shelf).size > 1,
                    // A fresh shelf starts at its head rather than wherever the last one was left.
                    resetKey = activeRelatedFilter,
                    onAnimeClick = onAnimeClick,
                    onCardFocused = { focusMemory.marker = tvRelatedFocusMarker(it) },
                    restoreMediaId = restoreRelatedId,
                    onRestoreResult = onRestoreResult,
                )
            }
        }
    }
}

/**
 * The page's backdrop. Its own composable so that the cursor's episode, which [model] reads, only
 * recomposes the picture rather than the page around it.
 */
@Composable
private fun TvDetailBackdrop(model: () -> Any?) {
    AsyncImage(
        model = model(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
}

/**
 * The top half: the series' own typography, then whichever episode the cursor is on, then the
 * actions.
 *
 * The logo is the header's first choice and the title its fallback, because a show's name set in
 * its own lockup is recognisable across a room in a way the app's typeface is not — and where no
 * artwork source has one, a title still has to be legible rather than absent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvDetailHeader(
    modifier: Modifier = Modifier,
    season: Media,
    logo: String?,
    /**
     * The episode the cursor is on, or null for the season. Read where it is drawn rather than
     * passed by value, so a step along the strip recomposes only this summary.
     */
    episode: () -> EpisodeItem?,
    isMovie: Boolean,
    resume: HistoryEntry?,
    saved: Boolean,
    listStatusLabel: String?,
    canWatch: Boolean,
    onWatch: () -> Unit,
    onStudioClick: (StudioNode) -> Unit,
    downloadPreparing: Boolean,
    /** The episode strip's order, drawn as a sort button at the end of the row; null hides it. */
    episodeOrder: EpisodeOrder?,
    onToggleOrder: () -> Unit,
    /**
     * Opens the download picker over the episodes the strip is showing — one episode or many, ticked
     * in the one list. The page's only download control: a second focus stop on every card would sit
     * between each pair of cards on the way along the strip. Null with nothing to save.
     */
    onDownload: (() -> Unit)?,
    primaryActionFocusRequester: FocusRequester,
    saveActionFocusRequester: FocusRequester,
    onPrimaryFocusAcquired: () -> Unit,
) {
    val description = remember(season.description) {
        season.description
            ?.replace(Regex.cached("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.trim()
            .orEmpty()
    }
    var synopsisVisible by remember(season.id) { mutableStateOf(false) }
    if (synopsisVisible) {
        SynopsisDialog(title = season.title.preferred, description = description) {
            synopsisVisible = false
        }
    }
    var listMenuVisible by remember(season.id) { mutableStateOf(false) }
    if (listMenuVisible) {
        AnimeListActionsDialog(
            anilistId = season.id,
            title = season.title.preferred,
            cover = season.coverImage.best,
            metadata = listOfNotNull(
                season.format?.replace('_', ' '),
                season.seasonYear?.toString(),
            ).joinToString(" · ").takeIf { it.isNotBlank() },
            kicker = "This anime",
            onDismiss = { listMenuVisible = false },
            completedMarksWatched = true,
            totalEpisodes = season.episodes ?: if (isMovie) 1 else null,
            // Season-accurate, like every other control here: the chips move what the page is
            // describing, and a menu that filed the franchise's first season while the header
            // showed its third is the fault this page was corrected for once already.
            saveEntry = WatchlistEntry(
                anilistId = season.id,
                title = season.title.preferred,
                cover = season.coverImage.best,
                format = season.format,
                averageScore = season.averageScore,
            ),
        )
    }

    // Logo, summary, actions — stacked in that order, but measured actions first, then the
    // summary, then the logo, because that is the order they may not give way in. Stacked as a
    // plain Column, a tall logo plus a two-line synopsis ran the header out of room and the column
    // clipped its last child: Continue, Add to list and the icons all vanished under the
    // tabs whenever a synopsis wrapped. Here the buttons always get their height, the summary gets
    // the rest bar a minimum for the logo (its synopsis keeps to whole lines of it), and the logo
    // takes what is left, never more than its full 88dp.
    Layout(
        modifier = modifier.fillMaxWidth().padding(start = TvDetailPadding, end = TvDetailPadding),
        content = {
            // Bounded on both axes: a long wordmark is held to the width, a tall stacked one to the
            // height. SeriesLogo trims the transparent margin first, so the bound applies to the ink.
            // Filling the height it is handed keeps the box one size whether or not the image has
            // loaded, so the page does not jump when it arrives.
            Box {
                SeriesLogo(
                    url = logo,
                    contentDescription = season.title.preferred,
                    modifier = Modifier.fillMaxHeight().width(400.dp),
                ) {
                    ShrinkingTitle(
                        text = season.title.preferred,
                        style = LocalTextStyle.current.copy(
                            fontSize = 30.sp,
                            lineHeight = 33.sp,
                            fontWeight = FontWeight.Black,
                        ),
                        color = Color.White,
                        maxLines = 2,
                        minFontSize = 22.sp,
                        modifier = Modifier.width(560.dp),
                    )
                }
            }

            // One slot either way, so the measure policy below can count on three.
            Box {
                val focusedEpisode = episode()
                if (focusedEpisode != null) {
                    TvFocusedEpisodeSummary(
                        episode = focusedEpisode,
                        unaired = episodeIsUnaired(focusedEpisode, season),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    TvSeasonSummary(
                        season = season,
                        description = description,
                        onStudioClick = onStudioClick,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            // Wraps rather than squeezes: with a resumed series the first button reads "Continue E10"
            // and the last one needs room for "Watching" beside it. The labelled buttons lead and the
            // bare icons follow as one group, so a wrap never strands an icon among the labels.
            FlowRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                val watchFocus = rememberFocusFill()
                Button(
                    onClick = onWatch,
                    enabled = canWatch,
                    shape = RoundedCornerShape(12.dp),
                    colors = watchFocus.buttonColors(),
                    contentPadding = PaddingValues(horizontal = 19.dp),
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        // Under Button's 40dp minimum, so the focus ring and its lift clear the tab
                        // chips below instead of touching them.
                        .height(TvHeaderActionHeight)
                        .focusRequester(primaryActionFocusRequester)
                        .focusProperties { right = saveActionFocusRequester }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) onPrimaryFocusAcquired()
                        }
                        .focusFill(watchFocus, RoundedCornerShape(12.dp), focusedScale = 1.04f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(21.dp))
                    Text(
                        if (isMovie) {
                            movieAwareWatchLabel(season.format, resume != null)
                        } else {
                            resume?.let { "Continue E${it.episodeLabel}" } ?: "Watch"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
                // The icons carry no plate: bare glyphs over the artwork, a ring round the one under
                // the cursor. A plate on each read as a second row of buttons competing with the
                // Watch button.
                Row(
                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The one list control: the eye opens the list menu, where Completed also fills
                    // the episode count. It stood beside a labelled "Watching" button that opened
                    // the same menu, and a viewer could not tell the two apart. Filled once the
                    // title is finished; the spoken label carries the status the button used to show.
                    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
                    val localWatched by LocalWatchedStore.entries.collectAsState()
                    // Signed out, the menu's Watched mark is the device's Completed.
                    val watched = remoteStatuses[season.id] == "COMPLETED" ||
                        localWatched.any { it.anilistId == season.id }
                    val listFocus = rememberFocusFill()
                    Button(
                        onClick = { listMenuVisible = true },
                        shape = CircleShape,
                        colors = listFocus.buttonColors(container = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .size(TvHeaderActionHeight)
                            .focusRequester(saveActionFocusRequester)
                            .focusFill(listFocus, CircleShape, focusedScale = 1.04f),
                    ) {
                        Icon(
                            if (watched) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                            contentDescription = listStatusLabel ?: if (saved) "In library" else "Add to list",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    onDownload?.let { open ->
                        val downloadFocus = rememberFocusFill()
                        Button(
                            onClick = open,
                            shape = CircleShape,
                            colors = downloadFocus.buttonColors(container = Color.Transparent),
                            contentPadding = PaddingValues(0.dp),
                            // The explicit size beats Button's 58dp minimum width.
                            modifier = Modifier
                                .size(TvHeaderActionHeight)
                                .focusFill(downloadFocus, CircleShape, focusedScale = 1.04f),
                        ) {
                            // With no label to say "Preparing…", the icon turns into a spinner instead.
                            if (downloadPreparing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = LocalContentColor.current,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    episodeOrder?.let { order ->
                        // The episode order, as a sort glyph at the end of the row rather than a chip
                        // spelling it out over the strip: descending for newest first (the numbers
                        // falling), ascending for oldest first. OK turns it round; the spoken label
                        // names the order on show.
                        val orderFocus = rememberFocusFill()
                        val newestFirst = order == EpisodeOrder.NEWEST_FIRST
                        Button(
                            onClick = onToggleOrder,
                            shape = CircleShape,
                            colors = orderFocus.buttonColors(container = Color.Transparent),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .size(TvHeaderActionHeight)
                                .focusFill(orderFocus, CircleShape, focusedScale = 1.04f),
                        ) {
                            Icon(
                                painterResource(
                                    if (newestFirst) R.drawable.ic_sort_descending else R.drawable.ic_sort_ascending,
                                ),
                                contentDescription = if (newestFirst) "Newest first" else "Oldest first",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // The whole synopsis. The header keeps it to two lines at most — fewer under a
                    // tall logo, none while an episode is focused — and that text is not focusable,
                    // so a remote had no way to read past the ellipsis. Offered whenever there is a
                    // synopsis at all, not only when it was clipped: the episode summary replaces it
                    // as soon as the cursor reaches the strip, so it is often not on screen to measure.
                    if (description.isNotBlank()) {
                        val synopsisFocus = rememberFocusFill()
                        Button(
                            onClick = { synopsisVisible = true },
                            shape = CircleShape,
                            colors = synopsisFocus.buttonColors(container = Color.Transparent),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .size(TvHeaderActionHeight)
                                .focusFill(synopsisFocus, CircleShape, focusedScale = 1.04f),
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "Read the full synopsis",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                // The phone's batch status, on its own line after the row: a batch queues its
                // episodes one at a time over minutes, and an episode no server could start never
                // reaches Downloads at all — without this, two of four simply went missing. It
                // follows the shared queue, so it stays up across a season switch and names a batch
                // from elsewhere too, with Stop always within reach. Nothing when no batch has run.
                if (onDownload != null) {
                    BulkDownloadStatus(Modifier.fillMaxWidth())
                }
            }
        },
    ) { measurables, constraints ->
        val (logoSlot, summarySlot, actionsSlot) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bounded = constraints.hasBoundedHeight
        val logoMaxPx = TvHeaderLogoMaxHeight.roundToPx()
        val actions = actionsSlot.measure(loose)
        val summary = summarySlot.measure(
            if (bounded) {
                val room = constraints.maxHeight - actions.height - TvHeaderLogoMinHeight.roundToPx()
                loose.copy(maxHeight = room.coerceAtLeast(0))
            } else {
                loose
            },
        )
        val logoRoom = if (bounded) {
            (constraints.maxHeight - actions.height - summary.height).coerceIn(0, logoMaxPx)
        } else {
            logoMaxPx
        }
        val logoPlaceable = logoSlot.measure(loose.copy(maxHeight = logoRoom))
        val height = logoPlaceable.height + summary.height + actions.height
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            logoPlaceable.placeRelative(0, 0)
            summary.placeRelative(0, logoPlaceable.height)
            actions.placeRelative(0, logoPlaceable.height + summary.height)
        }
    }
}

/** The logo's full height, and the least it is squeezed to before the synopsis gives way instead. */
private val TvHeaderLogoMaxHeight = 88.dp
private val TvHeaderLogoMinHeight = 64.dp

/** The action row's height, labelled buttons and bare icons alike. */
private val TvHeaderActionHeight = 36.dp

/**
 * What the page says about the episode under the cursor.
 *
 * Every line here is optional and every one of them is absent for something: a provider states the
 * number and nothing else, the metadata tree adds the name, the synopsis and the runtime where it
 * covers the title, and the score only where a rating source reached. Each is therefore drawn only
 * when it exists rather than reserving a slot — a header with three blank rows in it reads as a
 * page that failed to load.
 */
@Composable
private fun TvFocusedEpisodeSummary(
    episode: EpisodeItem,
    unaired: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(760.dp)) {
        Text(
            text = buildString {
                append("E")
                append(episode.displayNumber)
                episode.distinctTitle?.let {
                    append(" - ")
                    append(it)
                }
            },
            color = Color.White,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val facts = buildList {
            // The card under the cursor says how long; this says when, to the minute, in the
            // viewer's own zone — the exact time is known for the episodes that are counting down.
            val airsAt = episode.airingAt?.takeIf { unaired }
            if (airsAt != null) {
                add(tvEpisodeAirTime(airsAt))
            } else {
                tvEpisodeAirDate(episode.airDate)?.let { add(if (unaired) "Airs $it" else it) }
            }
            episode.runtime?.takeIf { it > 0 }?.let { add("$it min") }
            if (episode.filler) add("Filler")
            if (episode.dubbed) add("Dub")
        }.joinToString("  •  ")
        // The score closes the facts line rather than sitting on the title's: it is one more thing
        // said about the episode, and a badge beside the name read as part of the name.
        val rating = episode.rating
        if (facts.isNotBlank() || rating != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                val muted = Color.White.copy(alpha = 0.62f)
                if (facts.isNotBlank()) Text(text = facts, color = muted, fontSize = 13.sp)
                if (rating != null) {
                    if (facts.isNotBlank()) Text(text = "  •  ", color = muted, fontSize = 13.sp)
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = tvEpisodeRating(rating),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
        episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            FittedSynopsis(
                text = overview,
                maxLines = 2,
                topGap = 6.dp,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * What the page says about the season itself, before the cursor has reached an episode.
 *
 * The same slot the episode summary occupies, answering the question a viewer has on arrival
 * instead: how well regarded it is, whether it is still airing, when it is from, how long an
 * episode runs and what kind of show it is. Score and status are set as their own marks because
 * they are what the eye goes to first from across a room; the rest is muted and set behind a rule,
 * because it is detail rather than headline.
 */
@Composable
private fun TvSeasonSummary(
    season: Media,
    description: String,
    onStudioClick: (StudioNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The synopsis keeps to a readable measure; the line above may run on past it, so the studio at
    // its end does not cost the last genre its word.
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            season.averageScore?.takeIf { it > 0 }?.let { score ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // AniList publishes a percentage; a ten-point score is what a viewer reads.
                        text = tvEpisodeRating(score / 10.0),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
            }
            // Reach beside regard: a well-scored title few have heard of and a hugely followed one
            // that scores lower read the same from the star alone. AniList counts list members in
            // any status, not finished viewers, so it is described as members, never "watched by".
            // The people icon says that on screen; the word is left to the screen reader, since
            // it crowded a row that already carries the score, the status and the years.
            season.popularity?.takeIf { it > 0 }?.let { members ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.People,
                        contentDescription = "members",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 5.dp).size(18.dp),
                    )
                    Text(
                        text = compactMemberCount(members),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Plain words, not boxed: the box is kept for the age gate, the one mark that warns.
            season.status?.let { status ->
                Text(
                    text = tvDetailPretty(status).uppercase(Locale.US),
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
            }
            // AniList states no content rating at all, so this is the one thing it can say about
            // who a title is for. Anything finer — a broadcaster's certificate — would have to come
            // from a source this app does not read yet, and inventing one would be worse than none.
            if (season.isAdult) TvSeasonSummaryMark("18+")
            tvSeasonYears(season)?.let { years ->
                TvSeasonSummaryRule()
                Text(
                    text = years,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Runtime and genres ride on the same line as the marks rather than under them: they
            // are the least of what is said here, and a second row of them pushes the synopsis and
            // everything below it down a whole line for no gain.
            val facts = buildList {
                season.duration?.takeIf { it > 0 }?.let { add("$it min") }
                addAll(season.genres.take(3))
            }.joinToString("  •  ")
            if (facts.isNotBlank()) {
                TvSeasonSummaryRule()
                Text(
                    text = facts,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // The studio closes the line in the facts' own size, only bolder: a name the viewer may
            // follow, not a button. It still opens the studio's page under OK, so it stays focusable
            // and shows the ring like everything else; the genres give way to it, never the reverse.
            season.studios.nodes.firstOrNull { it.isAnimationStudio && !it.name.isNullOrBlank() }
                ?.let { studio ->
                    if (facts.isNotBlank()) {
                        Text("•", color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
                    } else {
                        TvSeasonSummaryRule()
                    }
                    val studioFocus = rememberFocusFill()
                    Text(
                        text = studio.name.orEmpty(),
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .focusFill(studioFocus, RoundedCornerShape(6.dp), focusedScale = 1.03f)
                            .clickable(enabled = studio.id > 0) { onStudioClick(studio) }
                            .padding(horizontal = 2.dp),
                    )
                }
        }
        if (description.isNotBlank()) {
            FittedSynopsis(
                text = description,
                maxLines = 2,
                topGap = 8.dp,
                modifier = Modifier.width(760.dp).weight(1f, fill = false),
            )
        }
    }
}

/**
 * A synopsis that keeps to whole lines of whatever height the header has left for it.
 *
 * The header measures its logo and its buttons before the summary between them, so the summary is
 * what gives way on a crowded page. A plain Text clipped by height would stop half-way through a
 * line; this drops to as many whole lines as fit — two on a roomy page, one under a tall logo, none
 * at all if it comes to that — and ends the last one in an ellipsis.
 */
@Composable
private fun FittedSynopsis(
    text: String,
    maxLines: Int,
    topGap: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val style = MaterialTheme.typography.bodyMedium
        val density = LocalDensity.current
        val lineHeightPx = with(density) {
            (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toPx()
        }
        val gapPx = with(density) { topGap.toPx() }
        val lines = if (constraints.hasBoundedHeight) {
            minOf(maxLines, ((constraints.maxHeight - gapPx) / lineHeightPx).toInt())
        } else {
            maxLines
        }
        // The gap goes with the text: an empty slot keeping its padding is height the header
        // could not spend on anything else.
        if (lines > 0) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.78f),
                style = style,
                maxLines = lines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = topGap),
            )
        }
    }
}

/** One boxed word in the season line — a status, an age gate. */
@Composable
private fun TvSeasonSummaryMark(label: String) {
    Text(
        text = label,
        color = Color.White.copy(alpha = 0.88f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** The hairline that separates the marks from the plain words beside them. */
@Composable
private fun TvSeasonSummaryRule() {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = 1.dp, height = 17.dp)
            .background(Color.White.copy(alpha = 0.26f)),
    )
}

/**
 * The years a season ran, as "2021" or "2026–".
 *
 * The trailing dash is the difference between a season that finished and one still going out, and
 * it is written only where AniList has a start and no end — a show that ended in the year it began
 * reads as one year, not as a range from itself to itself.
 */
private fun tvSeasonYears(season: Media): String? {
    val start = season.seasonYear ?: season.startDate?.year ?: return null
    // AniList fills an end year for some shows that are still going out — the date the last
    // episode is *expected*. The status is the thing that knows, so it decides.
    val airing = season.status in setOf("RELEASING", "NOT_YET_RELEASED")
    val end = season.endDate?.year.takeUnless { airing }
    return when {
        airing || end == null -> "$start–"
        end == start -> "$start"
        else -> "$start–$end"
    }
}

/**
 * An episode number as the card corner prints it: "E01", "E12", "E1043", "E13.5".
 *
 * Padded to two digits so a season's cards read as a column of the same width rather than as a
 * ragged one, and left alone past that: padding "E0100" would be wrong and padding recap numbers
 * would be nonsense.
 */
private fun tvEpisodeCardNumber(episode: EpisodeItem): String {
    val label = episode.displayNumber
    val padded = if (label.length == 1 && label.all { it.isDigit() }) "0$label" else label
    return "E$padded"
}

/** One episode score as a viewer reads it: one decimal, never a trailing zero's worth of noise. */
private fun tvEpisodeRating(rating: Double): String = String.format(Locale.US, "%.1f", rating)

/**
 * A catalogue air date as a day a viewer recognises, or null when there is nothing usable to read.
 *
 * The catalogues write two shapes for the same field — `2016-04-04` and `2024-10-02T00:00:00+00:00`
 * — so only the leading day is parsed; see `episodeAirDate`, which has the same problem.
 */
private fun tvEpisodeAirDate(raw: String?): String? {
    val day = raw?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
    val parsed = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
    return parsed.format(TV_EPISODE_DATE_FORMAT)
}

private val TV_EPISODE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** "Airs Sat, Oct 3 at 1:30 AM", or "Aired at 1:30 AM" once it has gone out, on the device's clock. */
private fun tvEpisodeAirTime(airsAt: Long): String {
    val zoned = java.time.Instant.ofEpochSecond(airsAt).atZone(java.time.ZoneId.systemDefault())
    val time = zoned.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    return if (airsAt > System.currentTimeMillis() / 1000) {
        "Airs ${zoned.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))} at $time"
    } else {
        "Aired at $time"
    }
}

/**
 * The season's next scheduled episode as a row of its own, for the strip's countdown card — or null
 * when the schedule names none, or a row for that episode already exists (then its own card
 * counts down instead of a second one beside it saying the same thing).
 *
 * The id is namespaced away from every real row's, so it can never collide with one as a list key
 * or be handed to a player.
 */
private fun tvUpcomingEpisode(season: Media, episodes: List<EpisodeItem>): EpisodeItem? {
    val next = season.nextAiringEpisode ?: return null
    val number = next.episode ?: return null
    val airingAt = next.airingAt ?: return null
    if (episodes.any { it.number == number.toDouble() }) return null
    return EpisodeItem(
        pipeId = "upcoming:${season.id}:$number",
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
        airingAt = airingAt,
    )
}

/**
 * The tab strip, and the series' seasons after it on the same row.
 *
 * The seasons had a row of their own between these tabs and the ranges. On a 540dp screen that row
 * was the header's missing height: with a tall logo there was no room left for a second line of
 * synopsis, or, before the header learned to give way, for the buttons at all. Sharing the tabs'
 * row gives that height back, and a season is still one press from the ranges under it. The row
 * scrolls, because a long franchise has more seasons than the screen is wide.
 *
 * Wired as plain chips: what each one switches is a section, or the season on show, not a route.
 */
@Composable
private fun TvDetailTabs(
    selected: TvDetailTab,
    tabs: List<TvDetailTab>,
    onSelect: (TvDetailTab) -> Unit,
    /** Held by the tab on display: where the episode strip's Up hand-off lands. */
    selectedChipTarget: TvFocusTarget? = null,
    /** The franchise's seasons, drawn after the tabs when there are two or more. */
    seasons: List<Media> = emptyList(),
    seasonLabels: Map<Int, String> = emptyMap(),
    selectedSeasonId: Int = 0,
    onSelectSeason: (Int) -> Unit = {},
    /** Held by the chip for the season on show: where Up from the ranges lands. */
    selectedSeasonTarget: TvFocusTarget? = null,
    /** The Related tab's shelves, drawn after the tabs where the seasons go on Episodes. */
    relatedFilters: List<RelatedFilter> = emptyList(),
    selectedRelatedFilter: RelatedFilter = RelatedFilter.ALL,
    onSelectRelatedFilter: (RelatedFilter) -> Unit = {},
) {
    val showTabs = tabs.size > 1
    val showSeasons = seasons.size > 1
    val showShelves = relatedFilters.isNotEmpty()
    if (!showTabs && !showSeasons && !showShelves) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            // Room above for this row's ring and the action row's, so neither touches the other.
            .padding(start = TvDetailPadding, end = TvDetailPadding, top = 11.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTabs) {
            tabs.forEach { entry ->
                TvDetailFilterChip(
                    label = entry.label,
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                    modifier = if (entry == selected) Modifier.tvFocusTarget(selectedChipTarget) else Modifier,
                )
            }
        }
        if (showSeasons) {
            if (showTabs) TvSeasonSummaryRule()
            seasons.forEachIndexed { index, season ->
                val isOnShow = season.id == selectedSeasonId
                TvDetailFilterChip(
                    label = tvSeasonChipLabel(seasonLabels[season.id] ?: "Season ${index + 1}", season),
                    selected = isOnShow,
                    onClick = { onSelectSeason(season.id) },
                    enabled = seasonChoosable(season, selectedSeasonId),
                    modifier = if (isOnShow) Modifier.tvFocusTarget(selectedSeasonTarget) else Modifier,
                )
            }
        }
        if (showShelves) {
            if (showTabs || showSeasons) TvSeasonSummaryRule()
            relatedFilters.forEach { filter ->
                TvDetailFilterChip(
                    label = filter.label,
                    selected = filter == selectedRelatedFilter,
                    onClick = { onSelectRelatedFilter(filter) },
                )
            }
        }
    }
}

/**
 * The episodes themselves: the range chips over a strip that runs the width of the screen.
 *
 * Focus is reported upwards on every move, because the header above is describing whatever the
 * cursor is on — that reporting is the whole mechanism by which this page says anything about an
 * episode at all.
 */
@Composable
private fun TvEpisodeStrip(
    episodes: List<EpisodeItem>,
    allEpisodeCount: Int,
    /**
     * The season these rows belong to. Passed rather than looked up, because the chain is empty
     * until the series walk returns and a lookup would treat every episode as released until then.
     */
    season: Media,
    blocks: List<String>,
    selectedBlockIndex: Int,
    query: String,
    searching: Boolean,
    fallbackImage: String?,
    resume: HistoryEntry?,
    loading: Boolean,
    onSelectBlock: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchingChange: (Boolean) -> Unit,
    onEpisodeFocused: (EpisodeItem) -> Unit,
    onPlay: (EpisodeItem) -> Unit,
    onToggleWatched: (EpisodeItem) -> Unit,
    /** The episode the player's Back named, to receive the cursor once its card exists. */
    arrivalFocusPipeId: String? = null,
    /**
     * The card the cursor was on when the viewer left the page, to receive it again on Back. Only
     * read when there is no [arrivalFocusPipeId]; unlike an arrival it does not wait for its range
     * to come on show, because the range it was in is restored with it.
     */
    restoreFocusPipeId: String? = null,
    /**
     * Told whether the arrival's or the restored card took the cursor, so the page can place it
     * somewhere else when it did not.
     */
    onInitialFocusResult: (Boolean) -> Unit = {},
    /** The episode the strip opens scrolled to, when it is among [episodes]. */
    openOnPipeId: String? = null,
    /** The tab strip's target, for the Up hand-off out of the range chips. */
    upFocusTarget: TvFocusTarget? = null,
    /**
     * The franchise's seasons. Drawn by the tab row, not here; the strip only needs to know whether
     * that row carries a season chip, because that is where Up from the ranges goes when it does.
     */
    seasons: List<Media> = emptyList(),
    seasonLabels: Map<Int, String> = emptyMap(),
    selectedSeasonId: Int = 0,
    onSelectSeason: (Int) -> Unit = {},
    /** Held by the chip for the season on show: where Up from the rows below it lands. */
    selectedSeasonTarget: TvFocusTarget = remember { TvFocusTarget() },
    /**
     * The next episode in the season's schedule, when no row stands for it yet: drawn as one more
     * card, counting down, in the slot the episode will take. Null when there is none, or when the
     * rows on show are not the ones it follows (another range, a search).
     */
    upcomingEpisode: EpisodeItem? = null,
    /** Whether that card leads the strip — newest first — rather than trailing it. */
    upcomingFirst: Boolean = false,
) {
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    // A leading upcoming card shifts every episode one place along in the list, and every scroll
    // below is by episode index.
    val leadingItems = if (upcomingEpisode != null && upcomingFirst) 1 else 0
    val listState = rememberLazyListState()
    // Rides the range the list is showing, so Up from a card lands on the range the viewer is in
    // rather than on the first chip — which is where Compose's own search puts it, the group
    // entered at its head.
    val selectedBlockTarget = remember { TvFocusTarget() }
    // Find swaps its chip for a text field, and Done swaps it back. Each swap removes the very
    // thing the cursor was on, and with nothing told where to go the cursor fell out of the row to
    // the top navigation — Find opened a field nobody could reach, with no keyboard. So each swap
    // hands the cursor on itself: into the field (which then opens the keyboard), and back to Find.
    val findFieldTarget = remember { TvFocusTarget() }
    val findChipTarget = remember { TvFocusTarget() }
    var returnToFind by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        val target = when {
            searching -> findFieldTarget
            returnToFind -> findChipTarget
            else -> return@LaunchedEffect
        }
        returnToFind = false
        // The field or chip is composed on the frame after the switch; wait for it, briefly.
        repeat(FIND_FOCUS_ATTEMPTS) {
            if (target.isAttached && runCatching { target.requester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            withFrameNanos {}
        }
    }
    val finishSearching = {
        returnToFind = true
        onSearchingChange(false)
    }
    val showSeasons = seasons.size > 1
    // What the rows here hand Up to: the season on show when the tab row carries seasons, the tab
    // on display when it does not.
    val aboveRangesTarget = if (showSeasons) selectedSeasonTarget else upFocusTarget
    // What Up from a card hands to: the range the list is showing when there is a range row, the
    // row above that when the season is too short for one, and nothing while the search field is up
    // so the field keeps its own keys.
    // The order is a sort button in the header's row, so a season too short for ranges has no
    // chip row at all.
    val hasRanges = allEpisodeCount >= TV_EPISODE_CHIPS_MIN_EPISODES
    val cardUpTarget = when {
        searching -> null
        hasRanges -> selectedBlockTarget
        else -> aboveRangesTarget
    }
    val scope = rememberCoroutineScope()
    // One target per card, so a held key can hand focus to a card several along instead of walking
    // to it. Keyed on the length as well as the head of the list: indexing past the end of a stale
    // list is a crash rather than a misplaced cursor.
    val focusTargets = remember(episodes.firstOrNull()?.pipeId, episodes.size) {
        List(episodes.size) { TvFocusTarget() }
    }
    // The effects below retry across frames, and the list of targets can be replaced in between.
    val currentFocusTargets by rememberUpdatedState(focusTargets)
    val currentEpisodes by rememberUpdatedState(episodes)
    // What the strip is showing, as opposed to which rows it holds: the season, the range, the
    // search and the order. A row appended by a revalidation or an airing changes the rows and none
    // of these, and must leave the scroll and the cursor where the viewer put them.
    val openKey = "${season.id}|$selectedBlockIndex|$query|$upcomingFirst"
    // Seeded with the card the strip opens on, so the first Down into it lands there rather than
    // on whichever card happens to sit under the chip above — on a long series that was a card
    // several along from the newest episode the strip had just scrolled to. Held by id rather than
    // by index, because a row appended at the head of a newest-first strip moves every index along.
    // Saveable, like the scroll position: Back onto the page re-enters the strip on this card.
    var focusedPipeId by rememberSaveable(openKey) { mutableStateOf(openOnPipeId) }
    val focusedIndex: () -> Int = {
        currentEpisodes.indexOfFirst { it.pipeId == focusedPipeId }.coerceAtLeast(0)
    }

    // Opens where the viewer left off rather than at episode one, the same as the list it replaced.
    // Once per range on show: re-run on every change to the rows, it scrolled the strip back from
    // wherever the viewer had walked to whenever a row was appended.
    // Saveable too: the list state is (rememberLazyListState), and a page returned to must not
    // scroll its restored strip back to the opening card.
    var openedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openKey, episodes.isEmpty()) {
        if (episodes.isEmpty() || openedFor == openKey) return@LaunchedEffect
        openedFor = openKey
        val target = openOnPipeId
            ?.let { id -> episodes.indexOfFirst { it.pipeId == id } }
            ?.takeIf { it >= 0 }
            ?: tvEpisodeScrollIndex(
                episodes = episodes,
                resumeEpisode = resume?.episodeNumber,
                jumpToLatest = false,
            )
        // Opening on the newest episode with the countdown leading the strip opens on the countdown
        // instead: it is the card beside that episode, and starting one along hid it off the edge.
        if (target >= 0) listState.scrollToItem(if (target == 0) 0 else target + leadingItems)
    }

    // The player's Back promises arrival on this page, and the episode it left is what the viewer
    // came back for, so the cursor goes there instead of nowhere. Scrolled first and focused a
    // frame later, the same dance a held key does, because a card that is not composed owns no
    // focus target.
    // Keyed on the episode asked for, not on the head of the list: in newest-first order an
    // appended row is the new head, and the arrival ran again and pulled the cursor back to it.
    //
    // A page returned to (rather than arrived at from the player) puts the cursor back on the card
    // it was left on the same way. The arrival wins when there is one.
    val initialFocusPipeId = arrivalFocusPipeId ?: restoreFocusPipeId
    val isArrival = arrivalFocusPipeId != null
    var initialFocusTaken by remember(initialFocusPipeId) { mutableStateOf(false) }
    val initialOnShow = initialFocusPipeId != null && episodes.any { it.pipeId == initialFocusPipeId }
    LaunchedEffect(initialFocusPipeId, initialOnShow) {
        if (initialFocusPipeId == null || initialFocusTaken) return@LaunchedEffect
        if (!initialOnShow) {
            // An arrival waits for the page to put its range on show. A restored card that is not
            // on show has nothing to wait for: its range came back with it.
            if (!isArrival) {
                initialFocusTaken = true
                onInitialFocusResult(false)
            }
            return@LaunchedEffect
        }
        val initialIndex = { currentEpisodes.indexOfFirst { it.pipeId == initialFocusPipeId } }
        if (isArrival) {
            listState.scrollToItem(initialIndex().coerceAtLeast(0) + leadingItems)
        } else {
            // The strip's scroll was saved with the page; leave it where the viewer had it unless
            // the card has gone off screen.
            withFrameNanos { }
            val shown = listState.layoutInfo.visibleItemsInfo.any { it.key == initialFocusPipeId }
            if (!shown) listState.scrollToItem(initialIndex().coerceAtLeast(0) + leadingItems)
        }
        withFrameNanos { }
        // Retried like the page's first focus: on a slow box the card can still be unattached a
        // frame after the scroll, and one attempt that missed left nothing focused.
        val attempts = retryTvFocus(TV_DETAIL_FOCUS_ATTEMPTS) {
            val card = currentFocusTargets.getOrNull(initialIndex())?.takeIf(TvFocusTarget::isAttached)
                ?: return@retryTvFocus false
            runCatching { card.requester.requestFocus() }.isSuccess && card.holdsFocus
        }
        initialFocusTaken = true
        DiagnosticsLog.event(
            "Detail ${if (isArrival) "arrival" else "restored"} episode focus requested " +
                "landed=${attempts != null} attempts=${attempts ?: TV_DETAIL_FOCUS_ATTEMPTS}",
        )
        onInitialFocusResult(attempts != null)
    }

    val stepBy: (Int) -> Boolean = step@{ delta ->
        if (episodes.isEmpty()) return@step false
        val from = focusedIndex()
        val target = (from + delta).coerceIn(0, episodes.lastIndex)
        // At either end the press is given back, so holding Right on the last episode leaves the
        // strip by ordinary focus search rather than stopping dead against its edge.
        if (target == from) return@step false
        scope.launch {
            // Scrolled first and focused a frame later: a card that is not composed holds no
            // focus target, and requesting focus on an unattached one throws inside Compose's
            // focus owner where it cannot be caught.
            listState.scrollToItem(target + leadingItems)
            withFrameNanos { }
            focusTargets.getOrNull(target)
                ?.takeIf(TvFocusTarget::isAttached)
                ?.let { runCatching { it.requester.requestFocus() } }
        }
        true
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (searching || hasRanges) Row(
            modifier = Modifier
                .fillMaxWidth()
                // Entered on the range on show rather than the chip nearest the cursor: on a long
                // series that is off-screen to the right, and focusing it scrolls it into view.
                .focusProperties {
                    enter = {
                        if (!searching && selectedBlockTarget.isAttached) {
                            selectedBlockTarget.requester
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
                .focusGroup()
                // Up leaves the chips for the row above. Only out of the ranges: while the search
                // field is up it owns its own cursor keys.
                .tvFocusHandoff(Key.DirectionUp, if (searching) null else aboveRangesTarget)
                // Scrolls, because a long series has more ranges than the screen is wide. One Piece's
                // 1,100-odd episodes make twelve chips plus Find; in a row that could not scroll,
                // everything past 801-900 was measured at zero width, could never take focus, and
                // no episode after 900 could be reached from the TV. Focus moving onto a chip
                // scrolls it into view, as it does in the season row above.
                .horizontalScroll(rememberScrollState())
                .padding(start = TvDetailPadding, end = TvDetailPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                TvNativeTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    hint = "Find episode number or title",
                    modifier = Modifier.width(360.dp),
                    imeAction = EditorInfo.IME_ACTION_SEARCH,
                    onImeAction = finishSearching,
                    tvFocusTarget = findFieldTarget,
                    // Pressing Find is already the viewer asking to type.
                    autoOpenKeyboard = true,
                    autoOpenToken = searching,
                )
                TvDetailFilterChip(
                    label = "Done",
                    selected = false,
                    onClick = {
                        onQueryChange("")
                        finishSearching()
                    },
                )
            } else {
                if (hasRanges) {
                    // The ranges are the fast way through a long season and the only way through a
                    // thousand-episode one, so they come first and search is a chip beside them.
                    blocks.forEachIndexed { index, label ->
                        TvDetailFilterChip(
                            label = label,
                            selected = index == selectedBlockIndex,
                            onClick = { onSelectBlock(index) },
                            modifier = if (index == selectedBlockIndex) {
                                Modifier.tvFocusTarget(selectedBlockTarget)
                            } else {
                                Modifier
                            },
                        )
                    }
                    TvDetailFilterChip(
                        label = "Find",
                        selected = query.isNotBlank(),
                        onClick = { onSearchingChange(true) },
                        modifier = Modifier.tvFocusTarget(findChipTarget),
                    )
                }
            }
        }

        when {
            episodes.isNotEmpty() -> LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .tvHoldToStep(stepBy)
                    // Back onto the card last left, or the one the strip opened on.
                    .focusProperties {
                        enter = { rememberedTvRailRequester(focusTargets, focusedIndex()) }
                    }
                    .focusGroup()
                    .tvFocusHandoff(Key.DirectionUp, cardUpTarget),
                contentPadding = PaddingValues(horizontal = TvDetailPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val upcomingCard: LazyListScope.() -> Unit = {
                    upcomingEpisode?.let { upcoming ->
                        item(key = upcoming.pipeId) {
                            TvEpisodeCard(
                                episode = upcoming,
                                image = episodeArtworkImage(null, fallbackImage),
                                blurred = blurEpisodeImages,
                                watchedFraction = 0f,
                                watched = false,
                                unaired = true,
                                airsAt = upcoming.airingAt,
                                onClick = {},
                                // Nothing to mark: there is no episode behind it yet.
                                onToggleWatched = {},
                                onFocused = { onEpisodeFocused(upcoming) },
                            )
                        }
                    }
                }
                if (upcomingFirst) upcomingCard()
                itemsIndexed(episodes, key = { _, episode -> episode.pipeId }) { index, episode ->
                    val unaired = episodeIsUnaired(episode, season)
                    TvEpisodeCard(
                        episode = episode,
                        image = episodeArtworkImage(episode.image, fallbackImage),
                        blurred = blurEpisodeImages,
                        watchedFraction = episodeWatchFraction(resume, episode.number),
                        watched = episodeMarkedWatched(resume, episode.number),
                        unaired = unaired,
                        // A catalogue can list the next episode before it airs, and then its own
                        // card counts down rather than a second one beside it saying the same.
                        airsAt = episode.airingAt ?: season.nextAiringEpisode
                            ?.takeIf { it.episode?.toDouble() == episode.number }
                            ?.airingAt,
                        onClick = { onPlay(episode) },
                        onToggleWatched = { onToggleWatched(episode) },
                        onFocused = {
                            focusedPipeId = episode.pipeId
                            onEpisodeFocused(episode)
                        },
                        modifier = Modifier.tvFocusTarget(focusTargets.getOrNull(index)),
                    )
                }
                if (!upcomingFirst) upcomingCard()
            }
            query.isNotBlank() -> TvEpisodeStripNotice("No episode matches “$query”.")
            loading -> TvEpisodeStripNotice("Loading episodes…")
            else -> TvEpisodeStripNotice("Episode information is not available yet.")
        }
    }
}

/** Where a strip with nothing in it says so, at the height the cards would have taken. */
@Composable
private fun TvEpisodeStripNotice(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.58f),
        modifier = Modifier.padding(horizontal = TvDetailPadding, vertical = 34.dp),
    )
}

private fun tvSeasonChipLabel(name: String, season: Media): String = buildString {
    append(name)
    (season.seasonYear ?: season.startDate?.year)?.let { append("  •  $it") }
    if (season.isUpcoming()) append("  •  Upcoming")
}


/**
 * One card of the Related rail. [kind] is what tells the entries of one franchise apart — their
 * titles all start with the same words — so it gets the pill; [details] rides beside it.
 */
private data class TvRelatedCard(
    val media: Media,
    val shelf: String,
    val kind: String?,
    val details: String,
)

/** The rest of the franchise and AniList's community picks, as one strip of wide cards. */
@Composable
private fun TvRelatedCompactRail(
    related: List<TvRelatedCard>,
    /** Names each shelf over its first card, for a rail that runs several shelves together. */
    labelShelves: Boolean,
    resetKey: Any,
    onAnimeClick: (Int) -> Unit,
    /** Told the media id of each card the cursor lands on, for the page to remember. */
    onCardFocused: (Int) -> Unit = {},
    /** The card the cursor was on when the viewer left the page, to receive it again on Back. */
    restoreMediaId: Int? = null,
    /** Told whether that card took the cursor, so the page can place it somewhere else if not. */
    onRestoreResult: (Boolean) -> Unit = {},
) {
    // Saveable, so Back from a title opened here finds the rail where it was left; a new shelf
    // still starts at its head.
    val listState = rememberSaveable(resetKey, saver = LazyListState.Saver) { LazyListState() }
    // One target per card, by media id, so a restore can hand the cursor to the card it names.
    val cardTargets = remember { mutableMapOf<Int, TvFocusTarget>() }
    LaunchedEffect(restoreMediaId) {
        val id = restoreMediaId ?: return@LaunchedEffect
        val index = related.indexOfFirst { it.media.id == id }
        if (index < 0) {
            onRestoreResult(false)
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            listState.scrollToItem(index)
            withFrameNanos { }
        }
        val attempts = retryTvFocus(TV_DETAIL_FOCUS_ATTEMPTS) {
            val card = cardTargets[id]?.takeIf(TvFocusTarget::isAttached) ?: return@retryTvFocus false
            runCatching { card.requester.requestFocus() }.isSuccess && card.holdsFocus
        }
        DiagnosticsLog.event(
            "Detail restored related focus requested landed=${attempts != null} " +
                "attempts=${attempts ?: TV_DETAIL_FOCUS_ATTEMPTS}",
        )
        onRestoreResult(attempts != null)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).focusGroup(),
        contentPadding = PaddingValues(horizontal = TvDetailPadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(related, key = { _, card -> card.media.id }) { index, card ->
            val media = card.media
            Column(Modifier.width(TvRelatedCardWidth)) {
                if (labelShelves) {
                    // Every card keeps the line, blank past a shelf's first, so the rail stays level.
                    Text(
                        text = card.shelf.takeIf { index == 0 || related[index - 1].shelf != it }.orEmpty(),
                        color = Color.White.copy(alpha = 0.58f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                TvRelatedCardBody(
                    card,
                    onClick = { onAnimeClick(media.id) },
                    onFocused = { onCardFocused(media.id) },
                    modifier = Modifier.tvFocusTarget(cardTargets.getOrPut(media.id) { TvFocusTarget() }),
                )
            }
        }
    }
}

/**
 * A Related card built like Continue watching's: the artwork is the whole card, darkened from the
 * left so the text on it stays legible, with the kind in a pill up top and the title along the foot.
 * The old card was a 16:9 picture over two captions, and a rail of them read as one title repeated.
 */
@Composable
private fun TvRelatedCardBody(
    card: TvRelatedCard,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val media = card.media
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-related-card-scale")
    Box(
        modifier = modifier
            .width(TvRelatedCardWidth)
            .height(TvRelatedCardHeight)
            // Lifted by hand rather than through focusHighlight, which clips inside itself and
            // would shave off the halo; this is how the episode cards beside it do it.
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusHalo(focused, TvCardCornerShape)
            .clip(TvCardCornerShape)
            .clickable(onClickLabel = "Open ${media.title.preferred}", onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = media.bannerImage ?: media.coverImage.best,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // High, so a portrait cover standing in for a missing banner keeps its faces.
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 28.dp, top = 14.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                card.kind?.let { kind ->
                    // The same tinted glass as the episode pill on Continue watching.
                    val pillShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .clip(pillShape)
                            .background(if (focused) accent.copy(.24f) else Color.White.copy(.10f))
                            .border(1.dp, if (focused) accent.copy(.5f) else Color.White.copy(.22f), pillShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            kind.uppercase(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                    }
                }
                if (card.details.isNotEmpty()) {
                    Text(
                        card.details,
                        color = Color.White.copy(.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                media.title.preferred,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Continue watching's proportions, so the two kinds of wide card read as one family. */
private val TvRelatedCardWidth = 300.dp
private val TvRelatedCardHeight = 118.dp

internal fun tvEpisodeScrollIndex(
    episodes: List<EpisodeItem>,
    resumeEpisode: Double?,
    jumpToLatest: Boolean,
): Int = when {
    episodes.isEmpty() -> -1
    jumpToLatest -> episodes.lastIndex
    episodes.size <= 6 -> 0
    else -> episodes.indexOfFirst { it.number == resumeEpisode }.coerceAtLeast(0)
}

@Composable
private fun TvDetailFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** False greys the chip out and takes it out of the focus order; see [seasonChoosable]. */
    enabled: Boolean = true,
) {
    // A faint pill for the one that is chosen and nothing at all for the rest, which leaves the
    // artwork behind the row showing instead of laying a line of boxes over it. The cursor is the
    // television's ring (focusFill draws it), with a trace of fill so a bare chip has a shape inside
    // it — the same rule as every other control, so "the ring is where I am" holds app-wide.
    val fill = rememberFocusFill()
    Text(
        text = label,
        color = when {
            fill.focused || selected -> Color.White
            !enabled -> Color.White.copy(alpha = 0.26f)
            else -> Color.White.copy(alpha = 0.58f)
        },
        fontWeight = if (fill.focused || selected) FontWeight.Bold else FontWeight.SemiBold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = modifier
            .focusFill(fill, CircleShape, focusedScale = 1.05f)
            .clip(CircleShape)
            .background(
                when {
                    selected -> FocusFillColor.copy(alpha = 0.2f)
                    fill.focused -> Color.White.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            // A disabled clickable is not focusable either, so D-pad passes over the chip.
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 6.dp),
    )
}

@Composable
private fun TvDetailMetadata(
    info: Media,
    maxGenres: Int = 3,
) {
    val cells = buildList {
        info.averageScore?.takeIf { it > 0 }?.let { add("score" to "$it%") }
        info.format?.let { add("text" to tvDetailPretty(it)) }
        (info.seasonYear ?: info.startDate?.year)?.let { add("text" to it.toString()) }
        info.duration?.takeIf { it > 0 }?.let { add("text" to "${it}m") }
        info.genres.take(maxGenres).forEach { add("text" to it) }
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                Text(label, color = Color.White.copy(.74f), fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvEpisodeCard(
    episode: EpisodeItem,
    image: String?,
    blurred: Boolean,
    watchedFraction: Float,
    watched: Boolean,
    /** Greyed and unopenable: an episode nobody can watch yet must not look like one they can. */
    unaired: Boolean,
    /**
     * When this episode airs, for the one card that is counting down to it — the next episode in
     * the season's schedule, or one that aired inside the last [EPISODE_WATCHABLE_AFTER_SEC] and
     * is waiting for the servers. Null on every other card.
     */
    airsAt: Long? = null,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-episode-card-scale")
    val countdown = airsAt?.takeIf { unaired }?.let { tvEpisodeCountdown(it) }
    // The eye is offered on everything that has aired. It used to be drawn only once an episode was
    // already watched, which made it a badge that happened to be pressable: the only way to *make*
    // the mark was the hold, and a hold is invisible from a sofa. An unaired card gets none, the
    // same as the phone row: there is no episode behind it yet to have seen. That is also what
    // keeps it clear of the counting-down face, which washes the whole card and would be sharing
    // the top-left corner with it.
    val showEye = !unaired
    // Where Down from the card goes. Held here rather than by the strip because nothing outside the
    // card needs to address it, and Down is the only way in: focus search from a focused card walks
    // up and out rather than down into it, so Left and Right never see this and crossing a hundred
    // episodes still costs one press each.
    val eyeTarget = remember { TvFocusTarget() }

    Column(
        modifier = modifier
            .width(TvEpisodeCardWidth)
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            // A counting card keeps its clock at full strength and dims its picture instead: the
            // clock is the thing on it worth reading, and greying it with the rest hid exactly that.
            .alpha(if (unaired && countdown == null) UNAIRED_ALPHA else 1f)
            .onFocusChanged {
                focused = it.isFocused
                // Reported on arrival only. The header above describes whatever the cursor is on,
                // and a card that reported its departure too would blank that description for the
                // frame between one card losing focus and the next one taking it.
                if (it.isFocused) onFocused()
            }
            // The indication fills the clickable's box, which here is exactly the artwork, so an
            // unshaped one showed as four square corners behind a round card. No caption hangs
            // below this one, so it needs no room under it the way Home's rails do.
            // Ahead of the clip, which would otherwise shave off a ring drawn outside the card.
            .focusHalo(focused, TvDetailCardShape)
            .clip(TvDetailCardShape)
            // combinedClickable's long press is pointer-only; the remote gets its own. The hold
            // still marks the episode, for the viewer who learned it that way.
            //
            // Off while the eye holds the cursor, because this handler runs in the **preview** pass
            // and the preview pass walks from the root down — so it sees OK before the focused eye
            // does and eats it, and the eye's one job became "play the episode". Disabled, the press
            // falls through to the eye underneath, which is what the cursor is on.
            .dpadClickable(
                onClick = { if (!unaired) onClick() },
                onLongClick = onToggleWatched,
                enabled = !eyeTarget.holdsFocus,
            )
            // Down, though, belongs to the eye — and it is handed over rather than redirected with
            // `focusProperties { down = … }`, which Compose would then consult *instead of* the
            // spatial search and would swallow the press outright if it ever failed to land.
            .tvFocusHandoff(Key.DirectionDown, eyeTarget.takeIf { showEye })
            .combinedClickable(
                onClickLabel = "Play episode ${episode.displayNumber}",
                onLongClickLabel = if (watched) "Mark unwatched" else "Mark watched",
                onClick = { if (!unaired) onClick() },
                onLongClick = onToggleWatched,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvDetailCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color.White.copy(.08f), TvDetailCardShape),
        ) {
            EpisodeArtwork(
                image = image,
                blurred = blurred,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(.02f),
                        .50f to Color.Black.copy(.08f),
                        1f to Color.Black.copy(.86f),
                    ),
                ),
            )
            // Under the number, which still names the episode being counted down to.
            countdown?.let { TvEpisodeCountdownFace(it, episode.displayNumber) }
            // The number and the filler mark are both things the catalogue says *about* the episode,
            // so they share the corner the number already had. That leaves the top-left to the eye
            // alone, which is then in the same place on every card whether or not it is filled.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (episode.filler) EpisodeFillerBadge()
                Text(
                    tvEpisodeCardNumber(episode),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(.70f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            if (showEye) {
                EpisodeWatchedToggle(
                    watched = watched,
                    onToggle = onToggleWatched,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .tvFocusTarget(eyeTarget),
                )
            }
            // Progress stays. It is the one thing the card says that the header cannot: which of
            // these the viewer has already been through and how far.
            WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * What a counting card says, and how long is left: the heading and seconds remaining, ticking.
 *
 * Two phases, because an episode airing is not an episode being watchable. Until the broadcast the
 * card counts to it. After, it stays locked for [EPISODE_WATCHABLE_AFTER_SEC] while the servers
 * post it (see [episodeIsUnaired]), and a clock left reading 00:00:00 over a card that still will
 * not open would look broken — so it counts that wait down too, under a heading that says why.
 * Null once both have run out; by then the card has unlocked.
 */
@Composable
private fun tvEpisodeCountdown(airsAt: Long): TvEpisodeCountdown? {
    val opensAt = airsAt + EPISODE_WATCHABLE_AFTER_SEC
    val now = rememberTickingEpochSeconds(until = opensAt)
    return when {
        now < airsAt -> TvEpisodeCountdown("NEXT EPISODE IN", airsAt - now, aired = false)
        now < opensAt -> TvEpisodeCountdown("ON SERVERS IN", opensAt - now, aired = true)
        else -> null
    }
}

private data class TvEpisodeCountdown(val label: String, val remaining: Long, val aired: Boolean)

/** The clock drawn over a counting card's artwork, which it dims to stay legible over any still. */
@Composable
private fun BoxScope.TvEpisodeCountdownFace(countdown: TvEpisodeCountdown, episode: String) {
    val (label, remaining, aired) = countdown
    Column(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // One announcement rather than a string of digits every second; see spokenDuration.
            .clearAndSetSemantics {
                contentDescription = if (aired) {
                    "Episode $episode has aired and reaches the servers in ${spokenDuration(remaining)}"
                } else {
                    spokenAiringCountdown(remaining, episode.toIntOrNull())
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = formatCompactCountdown(remaining),
            color = Color.White,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            // Tabular figures, so the line holds its width as the seconds turn over.
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun TvDetailSectionTitle(
    title: String,
    includePadding: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (includePadding) Modifier.padding(horizontal = TvDetailPadding) else Modifier),
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

/** Shared with the phone page so a format is not written two ways in one app. */
private fun tvDetailPretty(value: String): String = value.pretty()

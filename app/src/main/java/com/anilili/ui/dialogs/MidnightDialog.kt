package com.anilili.ui.dialogs

import com.anilili.ui.theme.appCanvas
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The single dialog frame every Midnight workflow is built in: a header that stays put, a body
 * that scrolls on its own, and a footer that keeps its space no matter how long the body is.
 *
 * Spec: design/midnight-anime/design.md §10, §27, §39. The footer reserving its space is not a
 * detail — the body used to be given a fixed maximum height and laid out first, which on a 540dp
 * television left the primary action measuring zero pixels and no way to start a download at all.
 * Header and footer are measured before the body, and the body takes only what is left over, so
 * the action is reachable on a phone in landscape, at any font scale, and on a TV.
 */
@Composable
fun MidnightDialogHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canDismiss: Boolean = true,
    header: @Composable ColumnScope.(showClose: Boolean) -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    bodyScrollState: ScrollState? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val dismiss: () -> Unit = { if (canDismiss) onDismiss() }
    if (device.isTv) {
        MidnightTvCard(onDismiss = dismiss, modifier = modifier) {
            MidnightDialogFrame(showClose = false, header = header, footer = footer, bodyScrollState = bodyScrollState, body = body)
        }
    } else {
        MidnightSheet(onDismiss = dismiss, modifier = modifier) {
            MidnightDialogFrame(showClose = true, header = header, footer = footer, bodyScrollState = bodyScrollState, body = body)
        }
    }
}

/**
 * The lazy-body variant of [MidnightDialogHost]: the same frame, but the body is a [LazyColumn], so
 * a dialog with more rows than fit composes only the ones on screen. The year picker composed all
 * 127 year rows the moment it opened (issue #49), and a season download composed up to a hundred
 * rows per range — 270 or more with a Find query (issue #59) — before anything was on screen.
 *
 * Opt-in per call site: the scrolling [MidnightDialogHost] keeps composing every row up front, and
 * every other dialog in the app is short enough that laziness buys nothing there.
 *
 * [bodyListState] is caller-owned so the caller decides where the list opens — the year picker
 * opens on the year holding the current value (#49) — and so it survives recomposition the way a
 * caller-supplied [bodyScrollState] does.
 *
 * [onBodyFocusPlaced] is called once the body's opening cursor has landed (on a television, where
 * the opening cursor exists). Rows of a lazy body compose and dispose as it scrolls, and a row
 * that claims the cursor on composition would re-claim it every time it is composed again on the
 * way back, yanking the cursor off the row the viewer is on. Callers gate the row that may claim
 * (§31) on this and stop claiming once it has fired; see [MidnightLazyDialogFrame].
 */
@Composable
fun MidnightDialogHost(
    onDismiss: () -> Unit,
    bodyListState: LazyListState,
    modifier: Modifier = Modifier,
    canDismiss: Boolean = true,
    header: @Composable ColumnScope.(showClose: Boolean) -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    onBodyFocusPlaced: (() -> Unit)? = null,
    body: LazyListScope.() -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val dismiss: () -> Unit = { if (canDismiss) onDismiss() }
    if (device.isTv) {
        MidnightTvCard(onDismiss = dismiss, modifier = modifier) {
            MidnightLazyDialogFrame(
                showClose = false,
                header = header,
                footer = footer,
                listState = bodyListState,
                onBodyFocusPlaced = onBodyFocusPlaced,
                body = body,
            )
        }
    } else {
        MidnightSheet(onDismiss = dismiss, modifier = modifier) {
            MidnightLazyDialogFrame(
                showClose = true,
                header = header,
                footer = footer,
                listState = bodyListState,
                onBodyFocusPlaced = onBodyFocusPlaced,
                body = body,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.MidnightDialogFrame(
    showClose: Boolean,
    header: @Composable ColumnScope.(Boolean) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    bodyScrollState: ScrollState?,
    body: @Composable ColumnScope.() -> Unit,
) {
    header(showClose)
    val scroll = bodyScrollState ?: rememberScrollState()
    val onTv = LocalAppDeviceProfile.current.isTv
    var viewport by remember { mutableStateOf(0) }
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cursor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // A caller-supplied state owns its opening position. Holding it at the opening cursor for six
    // frames can undo a quick D-pad move into the account charts (or a restored logout position).
    if (onTv && bodyScrollState == null) {
        LaunchedEffect(Unit) {
            // Where the body should be standing the moment the dialog is readable: far enough
            // down to show the control the cursor was put on, and not a pixel further, so what
            // sits above it — the explanation a picker opens with — keeps its first line.
            //
            // Left alone it does not stand there. On a 960x540 television the buffer-size picker
            // opened with the body animated 27px down over about 400ms, measured, with the cursor
            // on the first option and that option already wholly on screen; the scroller had no
            // reason to move and moved anyway. It is a small drift and a permanent one, because
            // the only thing that scrolls this body on a remote is the cursor, and from the first
            // option there is nothing above to move to.
            //
            // Placed once, at a priority the container's own animation cannot interrupt, and then
            // never again: after this the cursor moving is what scrolls the body, as it should.
            val target = snapshotFlow { openingScrollTarget(container, cursor, viewport, scroll) }
                .filterNotNull()
                .first()
            scroll.scroll(MutatePriority.PreventUserInput) {
                scrollBy((target - scroll.value).toFloat())
                // Held for a few frames because the container's animation is queued, not
                // instantaneous: releasing immediately just lets it run after us.
                repeat(OPENING_SCROLL_HOLD_FRAMES) { withFrameNanos { } }
            }
        }
    }
    Column(
        // fill = false so the body shrinks to its content when it is short, and stops at whatever
        // the header and footer left when it is long.
        modifier = Modifier
            .weight(1f, fill = false)
            // Only a television is measured here: the opening scroll below is the only reader, a
            // touch device never has a cursor to place, and this is the frame every dialog in the
            // app is built in.
            .then(
                if (onTv) {
                    Modifier
                        .onSizeChanged { viewport = it.height }
                        .onFocusedBoundsChanged { cursor = it }
                        .onGloballyPositioned { container = it }
                } else {
                    Modifier
                },
            )
            .verticalScroll(scroll),
    ) {
        if (bodyScrollState == null) MidnightBodyTopStop(scroll)
        body()
    }
    footer()
}

/**
 * The lazy body's frame: the same header, weighted body and footer as [MidnightDialogFrame], with
 * a [LazyColumn] instead of a scrolling Column, so only the rows on screen are composed (#49,
 * #59). The list sizes to its content when it is short, exactly as the scrolling Column does.
 *
 * The opening scroll runs here too. A lazy list has no absolute scroll value to coerce, so the
 * opening placement is a relative delta ([lazyOpeningScrollDelta]) and it lands where the
 * scrolling frame's does: forward just far enough to bring a row that hangs below the fold onto
 * the screen, and nowhere at all when the focused row already fits — the caller that opens the
 * list on a row (the year picker, #49) has already put it where it should stand.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.MidnightLazyDialogFrame(
    showClose: Boolean,
    header: @Composable ColumnScope.(Boolean) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    listState: LazyListState,
    onBodyFocusPlaced: (() -> Unit)?,
    body: LazyListScope.() -> Unit,
) {
    header(showClose)
    val onTv = LocalAppDeviceProfile.current.isTv
    var viewport by remember { mutableStateOf(0) }
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cursor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    if (onTv) {
        LaunchedEffect(listState) {
            val delta = snapshotFlow { lazyOpeningScrollDelta(container, cursor, viewport, listState) }
                .filterNotNull()
                .first()
            // The opening cursor has landed: every row that was going to claim it has. A lazy
            // body composes and disposes rows as it scrolls, so this has to happen before the
            // scroll below starts moving rows in and out — past this point a row composed again
            // on the way back must not claim the cursor, or it yanks it off the row the viewer
            // is on (#49, #59). Callers pass the flag this closes to the row that may claim.
            onBodyFocusPlaced?.invoke()
            listState.scroll(MutatePriority.PreventUserInput) {
                scrollBy(delta.toFloat())
                // Held for a few frames because the container's animation is queued, not
                // instantaneous: releasing immediately just lets it run after us.
                repeat(OPENING_SCROLL_HOLD_FRAMES) { withFrameNanos { } }
            }
        }
    }
    MidnightLazyBodyTopStop(listState)
    LazyColumn(
        state = listState,
        // fill = false so the body shrinks to its content when it is short, and stops at whatever
        // the header and footer left when it is long — the same rule the scrolling frame follows.
        modifier = Modifier
            .weight(1f, fill = false)
            .then(
                // Only a television is measured here: the opening scroll above is the only
                // reader, a touch device never has a cursor to place.
                if (onTv) {
                    Modifier
                        .onSizeChanged { viewport = it.height }
                        .onFocusedBoundsChanged { cursor = it }
                        .onGloballyPositioned { container = it }
                } else {
                    Modifier
                },
            ),
        content = body,
    )
    footer()
}

/** Frames the opening scroll is held for; long enough to outlast the container's own request. */
private const val OPENING_SCROLL_HOLD_FRAMES = 6

/**
 * The smallest scroll that shows the focused control, or null while the body is still being laid
 * out. Smallest rather than nearest: the head of a dialog body is text, not a control, so anything
 * more than the minimum scrolls reading matter out of reach of a remote.
 */
private fun openingScrollTarget(
    container: LayoutCoordinates?,
    cursor: LayoutCoordinates?,
    viewport: Int,
    scroll: ScrollState,
): Int? {
    if (container == null || cursor == null || viewport <= 0) return null
    if (!container.isAttached || !cursor.isAttached) return null
    if (scroll.maxValue == Int.MAX_VALUE) return null
    val bottom = container.localBoundingBoxOf(cursor, clipBounds = false).bottom
    return (scroll.value + bottom.toInt() - viewport).coerceIn(0, scroll.maxValue)
}

/**
 * The scroll delta that shows the focused row the way [openingScrollTarget] does for the scrolling
 * frame: as little as possible, with the row's bottom on the viewport's bottom edge. Expressed
 * relative to the current position because a lazy list has no absolute scroll value to coerce into
 * range — the scroll clamps itself at both ends. Null while the body is still being laid out or
 * nothing in it has the cursor yet.
 *
 * Never negative. A row that already fits needs no scroll at all, and the scrolling frame never
 * moves for one either — at its opening scroll it stands at zero, where a negative delta clamps to
 * nothing. But a lazy body opened on a row (#49) stands further down, where the unclamped delta
 * would scroll backward and drag the caller's opening placement up a couple of rows for no reason.
 */
private fun lazyOpeningScrollDelta(
    container: LayoutCoordinates?,
    cursor: LayoutCoordinates?,
    viewport: Int,
    state: LazyListState,
): Int? {
    if (container == null || cursor == null || viewport <= 0) return null
    if (!container.isAttached || !cursor.isAttached) return null
    if (state.layoutInfo.totalItemsCount == 0) return null
    val bottom = container.localBoundingBoxOf(cursor, clipBounds = false).bottom
    return (bottom.toInt() - viewport).coerceAtLeast(0)
}

/**
 * A focus stop above everything else in a scrolling dialog body.
 *
 * The body scrolls, and on a television the only thing that scrolls it is the cursor moving between
 * controls. The explanation a picker opens with is not a control, so once the cursor has been down
 * to the last option — which scrolls that explanation off the top, correctly — coming back up to
 * the first option scrolls nothing, and the head of the body is stranded above the viewport with no
 * key that reaches it. This stop draws nothing and does nothing when pressed; it exists so Up from
 * the first option lands somewhere, and landing here returns the body to the top.
 *
 * Only a focus stop when there is something to scroll to, so a body that fits keeps Up meaning
 * exactly what it means today.
 *
 * It is a bare [focusTarget] and it scrolls the body itself rather than being a `focusable` that
 * asks to be brought into view. A `focusable` here asks for the top as the dialog opens, at which
 * point that request sits in the scroller's queue ahead of the one from the row holding the stored
 * value and swallows it — which opened a picker whose value is near the end at the top, with the
 * cursor on a row nobody could see.
 */
@Composable
private fun MidnightBodyTopStop(scroll: ScrollState) {
    if (!LocalAppDeviceProfile.current.isTv) return
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .onFocusChanged { if (it.isFocused && scroll.value > 0) scope.launch { scroll.animateScrollTo(0) } }
            .then(if (scroll.maxValue > 0) Modifier.focusTarget() else Modifier),
    )
}

/**
 * The scrolling frame's [MidnightBodyTopStop] for a lazy body, which has no [ScrollState] to
 * return to the top of. It sits outside the list, above it, for the same reason: Up from the
 * first option has to land somewhere, and landing here returns the body to the top. Up only
 * reaches it once the list stands at item zero — anything above the visible rows is not composed
 * yet, and the list scrolls to reveal one more option per Up before the search looks outside it.
 */
@Composable
private fun MidnightLazyBodyTopStop(listState: LazyListState) {
    if (!LocalAppDeviceProfile.current.isTv) return
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .onFocusChanged {
                if (it.isFocused &&
                    (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
                ) {
                    scope.launch { listState.animateScrollToItem(0) }
                }
            }
            .then(
                if (listState.canScrollForward || listState.canScrollBackward) {
                    Modifier.focusTarget()
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * The phone host: a flat 16dp-radius sheet with its own 30×3 handle (§10).
 *
 * The body is deliberately `rgba(8,6,11,.98)` — near-opaque so content behind reads as gone, but
 * not pure black, which is reserved for the device bezel and video blackout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidnightSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberPopupBackdrop()
    val statusBarTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Frosted, the column below paints the panel — glass, then a translucent wash — and so
        // has to reach the bottom edge itself: the sheet's own inset padding would leave a strip
        // there with nothing in it but the app showing through sharp.
        containerColor = if (backdrop != null) Color.Transparent else Midnight.SheetSurface,
        contentWindowInsets = { if (backdrop != null) WindowInsets(0) else BottomSheetDefaults.windowInsets },
        contentColor = Midnight.Text,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = null,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    // Zeroing the sheet's insets for the glass drops its top inset too, so a tall
                    // sheet (a 12-episode download list) ran up under the status bar with its
                    // header behind the clock. Capped instead: never taller than the window less the
                    // status bar, which is where the sheet's own insets used to stop it.
                    if (backdrop != null) {
                        Modifier.layout { measurable, constraints ->
                            val capped = if (constraints.hasBoundedHeight) {
                                constraints.copy(
                                    maxHeight = (constraints.maxHeight - statusBarTop)
                                        .coerceAtLeast(constraints.minHeight),
                                )
                            } else {
                                constraints
                            }
                            val placeable = measurable.measure(capped)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (backdrop != null) {
                        Modifier.popupGlass(backdrop).background(Midnight.SheetSurface.copy(alpha = GLASS_PANEL_ALPHA))
                    } else {
                        Modifier
                    },
                )
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 9.dp, bottom = 12.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 30.dp, height = 3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Midnight.RadioRing),
            )
            content()
        }
    }
}

/**
 * The TV host: a card with a 2dp violet edge on the leading side, rounded at every corner, sitting
 * 48dp in from the left over the dimmed backdrop (§27). It used to round only the far side, which
 * left two square corners on the one window in the app that had any; every dialog is rounded now.
 *
 * The card is laid out inside a full-screen box so its height can never exceed the television's,
 * and the accent edge is painted rather than being a child, which keeps the card free to wrap its
 * content instead of needing an intrinsic measurement of a scrolling body.
 */
@Composable
fun MidnightTvCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // Televisions crop their edges; keep the card inside the safe area.
                .padding(horizontal = 48.dp, vertical = 27.dp),
        ) {
            val cardWidth = (maxWidth * 0.62f).coerceIn(320.dp, 620.dp)
            val shape = RoundedCornerShape(16.dp)
            val accent = LocalMidnightAccent.current
            Column(
                modifier = modifier
                    .align(Alignment.CenterStart)
                    .width(cardWidth)
                    .clip(shape)
                    .background(Midnight.TvSurface)
                    .border(1.dp, Midnight.Line2, shape)
                    .drawBehind {
                        drawRect(color = accent, size = Size(2.dp.toPx(), size.height))
                    }
                    .padding(start = 18.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
                content = content,
            )
        }
    }
}

/**
 * The centered confirmation host (§20): 12dp radius, one hairline border, no nested surfaces.
 *
 * Sized from the screen rather than the platform's default dialog width so a television gets a
 * card in proportion to the room and a phone keeps its 24dp margins.
 */
@Composable
fun MidnightModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        CoverScreenWithDialogWindow()
        val backdrop = rememberPopupBackdrop()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(
                    horizontal = if (device.isTv) 48.dp else 24.dp,
                    vertical = if (device.isTv) 27.dp else 24.dp,
                ),
        ) {
            val width = if (device.isTv) {
                (maxWidth * 0.46f).coerceIn(320.dp, 560.dp)
            } else {
                maxWidth
            }
            val shape = RoundedCornerShape(12.dp)
            Column(
                modifier = modifier
                    .align(Alignment.Center)
                    .width(width)
                    .clip(shape)
                    .popupGlass(backdrop)
                    .background(if (backdrop != null) Midnight.ModalSurface.copy(alpha = GLASS_PANEL_ALPHA) else Midnight.ModalSurface)
                    .border(1.dp, Midnight.Line2, shape)
                    .padding(18.dp),
                content = content,
            )
        }
    }
}

/**
 * A full-screen reading page over the app, for text too long for a centered modal to hold
 * comfortably (the whole of a release's notes). [content] fills the space between the system bars;
 * pin actions by giving the scrolling part `weight(1f)` and putting the actions after it.
 */
@Composable
fun MidnightPage(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        CoverScreenWithDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .appCanvas()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            content = content,
        )
    }
}

/** A frosted popup panel's wash: enough to read on, low enough for the glass to come through. */
private const val GLASS_PANEL_ALPHA = .72f

/**
 * Makes the current dialog's window cover the screen exactly, leaving the system bars to the
 * content's `WindowInsets.safeDrawing` padding.
 *
 * Under enforced edge-to-edge the window is sized to the whole screen yet was still pushed down
 * past the status bar and display cutout (by its fit-insets types, which survive
 * `decorFitsSystemWindows = false`, and the default cutout mode), so its bottom sat off screen: a
 * long update changelog put the update buttons behind the phone's navigation bar where nobody
 * could press them.
 */
@Composable
internal fun CoverScreenWithDialogWindow() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (dialogWindow != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val attrs = dialogWindow.attributes
            if (attrs.fitInsetsTypes != 0 || attrs.layoutInDisplayCutoutMode !=
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            ) {
                dialogWindow.attributes = attrs.apply {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
    }
}

/**
 * A confirmation, ready made (§20, §38): kicker, what is about to happen, what it affects, a quiet
 * way out and one filled action.
 *
 * On a television the cursor starts on the quiet action for destructive confirmations, so a
 * repeated OK press cannot delete anything (§31).
 */
@Composable
fun MidnightConfirmDialog(
    kicker: String,
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = "Cancel",
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    MidnightModal(onDismiss = onDismiss, modifier = modifier) {
        MidnightModalTitle(kicker = kicker, title = title, body = body)
        if (details != null) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                content = details,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dismissText != null) {
                MidnightQuietAction(
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier
                        .midnightInitialFocus(destructive)
                        .padding(end = 6.dp),
                )
            }
            MidnightFilledAction(
                text = confirmText,
                onClick = onConfirm,
                enabled = confirmEnabled,
                destructive = destructive,
                modifier = Modifier.midnightInitialFocus(!destructive),
            )
        }
    }
}

/** Kicker, headline and one paragraph of consequence — the top of every confirmation (§20). */
@Composable
fun MidnightModalTitle(
    kicker: String,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = kicker.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
            fontWeight = FontWeight.W800,
            color = LocalMidnightAccent.current,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W800,
            color = Midnight.Text,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Midnight.Text2,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Media header (§11): artwork carries the anime's identity, the kicker carries the action, the
 * show title outweighs the chrome, and close stays quiet.
 */
@Composable
fun MidnightMediaHeader(
    kicker: String,
    title: String,
    metadata: String?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artworkUrl != null) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 76.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.linearGradient(listOf(Midnight.Hover, Midnight.Bg2)),
                        ),
                ) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (artworkUrl != null) 12.dp else 0.dp, end = 8.dp),
            ) {
                Text(
                    text = kicker.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                    fontWeight = FontWeight.W800,
                    color = LocalMidnightAccent.current,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W800,
                    color = Midnight.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (!metadata.isNullOrBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = Midnight.Text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (onDismiss != null) {
                val extraFocus1 = rememberFocusFill()
                IconButton(
                    colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp).focusFill(extraFocus1, CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = extraFocus1.content(Midnight.Text3),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        MidnightRule()
    }
}

/** The hairline that separates a container's own parts (§8). */
@Composable
fun MidnightRule(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) Midnight.Line2 else Midnight.Line),
    )
}

/**
 * The one primary action (§17–18): purple, filled, explicit. Disabled is quiet grey — the reason
 * it is disabled lives next to the cause, not inside the button.
 */
@Composable
fun MidnightPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(8.dp)
    val fillFocus1 = rememberFocusFill()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (device.isTv) 48.dp else 44.dp)
            // A television's focus is a ring drawn outside the button, so there it goes ahead of the
            // clip; a phone keeps its order, fill inside the clip.
            .then(if (device.isTv) Modifier.focusFill(fillFocus1, shape) else Modifier)
            .clip(shape)
            .background(fillFocus1.background(if (enabled) LocalMidnightAccent.current else Midnight.DisabledFill))
            .then(if (device.isTv) Modifier else Modifier.focusFill(fillFocus1, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
            fontWeight = FontWeight.W800,
            color = fillFocus1.content(if (enabled) Midnight.OnAccent else Midnight.DisabledContent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** The compact filled action used inside confirmations (§20): red to destroy, purple to proceed. */
@Composable
fun MidnightFilledAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(7.dp)
    val fill = when {
        !enabled -> Midnight.DisabledFill
        destructive -> Midnight.Red
        else -> LocalMidnightAccent.current
    }
    val fillFocus2 = rememberFocusFill()
    Box(
        modifier = modifier
            .heightIn(min = if (device.isTv) 48.dp else 38.dp)
            // A television's focus is a ring drawn outside the button, so there it goes ahead of the
            // clip; a phone keeps its order, fill inside the clip.
            .then(if (device.isTv) Modifier.focusFill(fillFocus2, shape) else Modifier)
            .clip(shape)
            .background(fillFocus2.background(fill))
            .then(if (device.isTv) Modifier else Modifier.focusFill(fillFocus2, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
            fontWeight = FontWeight.W800,
            color = fillFocus2.content(if (enabled) Midnight.OnAccent else Midnight.DisabledContent),
            maxLines = 1,
        )
    }
}

/** A quiet text action: cancel, close, later (§19). */
@Composable
fun MidnightQuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val device = LocalAppDeviceProfile.current
    // A pill on a television, where the focus ring is drawn round the button's own shape: a 6dp
    // corner made the ring round "Close" a box rather than a button.
    val shape = if (device.isTv) RoundedCornerShape(50) else RoundedCornerShape(6.dp)
    val fillFocus3 = rememberFocusFill()
    Box(
        modifier = modifier
            // A remote lands on it by focus, not by aim, so a television needs no taller a target
            // than a finger does; at the 48dp this used to take, "Close" stood in a box nearly as
            // tall as it was wide.
            .heightIn(min = 36.dp)
            // A television's focus is a ring drawn outside the button, so there it goes ahead of the
            // clip; a phone keeps its order, fill inside the clip.
            .then(if (device.isTv) Modifier.focusFill(fillFocus3, shape) else Modifier)
            .clip(shape)
            .then(if (device.isTv) Modifier else Modifier.focusFill(fillFocus3, shape))
            .background(fillFocus3.background(Color.Transparent), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (device.isTv) 18.dp else 10.dp, vertical = if (device.isTv) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (destructive) FontWeight.W700 else FontWeight.W600,
            color = fillFocus3.content(when {
                !enabled -> Midnight.DisabledContent
                destructive -> Midnight.Red
                else -> Midnight.Text3
            }),
            maxLines = 1,
        )
    }
}

/** Identifies the destructive action inside [MidnightDialogFooter]'s custom layout. */
private const val FooterDangerId = "midnightFooterDanger"

/** Vertical gap between the two lines once the footer has to wrap. */
private val FooterRowGap = 4.dp

/**
 * Footer hierarchy (§19): destructive left in red, quiet secondary right. Never equal siblings.
 *
 * A plain `Row` put all three on one line and let the last one run off the edge — "Remove from
 * Continue Watching" beside "Details" and "Close" is wider than a phone at a large interface scale,
 * and the way that failed was that Close was clipped to "Clo", so the sheet looked like it had no
 * way out. Nothing here may shrink or ellipsize either: a half-printed verb on a destructive action
 * is worse than a second line. So the danger action keeps its own line whenever the three together
 * do not fit, and the quiet pair stays right-aligned underneath it.
 */
@Composable
fun MidnightDialogFooter(
    modifier: Modifier = Modifier,
    dangerText: String? = null,
    onDanger: (() -> Unit)? = null,
    quietText: String? = null,
    onQuiet: (() -> Unit)? = null,
    enabled: Boolean = true,
    extraQuietText: String? = null,
    onExtraQuiet: (() -> Unit)? = null,
) {
    val rowGapPx = with(LocalDensity.current) { FooterRowGap.roundToPx() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        content = {
            if (dangerText != null && onDanger != null) {
                MidnightQuietAction(
                    text = dangerText,
                    onClick = onDanger,
                    enabled = enabled,
                    destructive = true,
                    modifier = Modifier.layoutId(FooterDangerId),
                )
            }
            if (extraQuietText != null && onExtraQuiet != null) {
                MidnightQuietAction(text = extraQuietText, onClick = onExtraQuiet, enabled = enabled)
            }
            if (quietText != null && onQuiet != null) {
                MidnightQuietAction(text = quietText, onClick = onQuiet, enabled = enabled)
            }
        },
    ) { measurables, constraints ->
        val measured = measurables.map { it.layoutId to it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val danger = measured.firstOrNull { it.first == FooterDangerId }?.second
        val quiet = measured.filter { it.first != FooterDangerId }.map { it.second }
        val quietWidth = quiet.sumOf { it.width }
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            maxOf((danger?.width ?: 0) + quietWidth, constraints.minWidth)
        }
        val wraps = danger != null && quiet.isNotEmpty() && danger.width + quietWidth > width

        fun placeQuiet(scope: Placeable.PlacementScope, rowTop: Int, rowHeight: Int) {
            var x = (width - quietWidth).coerceAtLeast(0)
            quiet.forEach { placeable ->
                with(scope) { placeable.place(x, rowTop + (rowHeight - placeable.height) / 2) }
                x += placeable.width
            }
        }

        if (wraps) {
            val quietRowHeight = quiet.maxOf { it.height }
            val height = danger.height + rowGapPx + quietRowHeight
            layout(width, height) {
                danger.place(0, 0)
                placeQuiet(this, danger.height + rowGapPx, quietRowHeight)
            }
        } else {
            val height = measured.maxOfOrNull { it.second.height } ?: 0
            layout(width, height) {
                danger?.place(0, (height - danger.height) / 2)
                placeQuiet(this, 0, height)
            }
        }
    }
}

enum class MidnightNoticeTone { INFO, WARNING, ERROR, SUCCESS }

/** Inline feedback (§16): a 2dp marker, not another rounded box. */
@Composable
fun MidnightInlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: MidnightNoticeTone = MidnightNoticeTone.INFO,
) {
    val (marker, body) = when (tone) {
        MidnightNoticeTone.INFO -> LocalMidnightAccent.current to Midnight.Text2
        MidnightNoticeTone.WARNING -> Midnight.Amber to Color(0xFFE9C57F)
        MidnightNoticeTone.ERROR -> Midnight.Red to Midnight.ErrorBody
        MidnightNoticeTone.SUCCESS -> Midnight.Green to Color(0xFF9FE4C6)
    }
    Row(modifier = modifier.padding(top = 10.dp)) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = 2.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(marker),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = body,
            modifier = Modifier.padding(start = 9.dp),
        )
    }
}

/**
 * Storage as a data strip between rules, not a card (§15): size and scope on top, a 2px bar, free
 * and after below. Red only when the download genuinely cannot fit.
 */
@Composable
fun MidnightStorageStrip(
    sizeLabel: String,
    noteLabel: String,
    freeLabel: String,
    afterLabel: String,
    fillFraction: Float,
    blocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val fill = fillFraction.coerceIn(0f, 1f)
    val accent = if (blocked) Midnight.Red else LocalMidnightAccent.current
    val sizeColor = if (blocked) Midnight.Red else Midnight.Text
    val afterColor = if (blocked) Midnight.Red else Midnight.Text3
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        MidnightRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = sizeLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W800,
                color = sizeColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = noteLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Midnight.Text3,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 9.dp, bottom = 7.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Midnight.BarTrack),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = freeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Midnight.Text3,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = afterLabel,
                style = MaterialTheme.typography.labelSmall,
                color = afterColor,
            )
        }
        MidnightRule(Modifier.padding(top = 9.dp))
    }
}

/**
 * A live connection state (§24): a 5dp dot with a narrow glow, green because green is the fact
 * being reported — not because a row needed colour.
 */
@Composable
fun MidnightConnectionStatus(
    text: String,
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (connected) Midnight.Green else Midnight.Text3
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .drawBehind {
                    if (connected) {
                        drawCircle(color = color.copy(alpha = 0.35f), radius = size.minDimension)
                    }
                    drawCircle(color = color, radius = size.minDimension / 2f)
                },
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.W700,
            color = color,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** A short horizontal progress line, used while a dialog is doing something (§32). */
@Composable
fun MidnightProgressLine(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val accent = LocalMidnightAccent.current
    val track = Midnight.BarTrack
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                drawRect(color = track)
                val fraction = progress?.coerceIn(0f, 1f)
                if (fraction != null) {
                    drawRect(color = accent, size = Size(size.width * fraction, size.height))
                } else {
                    // Indeterminate work still gets a mark, so the strip never reads as "empty".
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width * 0.35f, size.height),
                    )
                }
            },
    )
}

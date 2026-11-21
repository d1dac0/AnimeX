package com.anilili.ui.profile

import com.anilili.ui.theme.appCanvasFade
import com.anilili.ui.theme.appCanvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.anilili.R
import com.anilili.data.auth.AccountService
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.WatchActivityStore
import com.anilili.data.library.mergedActivity
import com.anilili.data.model.MediaListEntry
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.components.PageFilterOption
import com.anilili.ui.components.failureAnnotated
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.home.TvContinueRail
import com.anilili.ui.home.TvRailScrollSpec
import com.anilili.ui.theme.Midnight
import java.util.Locale
import kotlinx.coroutines.android.awaitFrame

/**
 * The account's art as a television hero: the same treatment Home gives a featured title, so My
 * List and the profile read as part of the app rather than as a form laid over it.
 *
 * AniList banners are landscape and are cropped edge to edge. Every MyAnimeList account, and any
 * AniList account without a banner, borrows the cover of what was watched last — a portrait, so a
 * small copy is blurred into an ambient wash rather than stretched into a face-cropping close-up.
 * Either way the left of the picture fades into the page, where the text sits.
 */
@Composable
internal fun TvAccountBackdrop(banner: String?, fallbackCover: String?, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds().appCanvas()) {
        val wide = banner?.takeIf { it.isNotBlank() }
        if (wide != null) {
            AsyncImage(
                model = wide,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!fallbackCover.isNullOrBlank()) {
            val context = LocalContext.current
            val ambient = remember(fallbackCover) {
                ImageRequest.Builder(context).data(fallbackCover).size(320, 480).crossfade(false).build()
            }
            AsyncImage(
                model = ambient,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
                    .blur(34.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        }
        Box(
            Modifier.fillMaxSize().appCanvasFade { canvas ->
                Brush.horizontalGradient(
                    0f to canvas,
                    .36f to canvas.copy(.88f),
                    .70f to canvas.copy(.34f),
                    1f to canvas.copy(.08f),
                )
            },
        )
        Box(
            // Faded at the top as well as the bottom: the art starts under the top bar, where an
            // unfaded edge draws a hard line across the screen.
            Modifier.fillMaxSize().appCanvasFade { canvas ->
                Brush.verticalGradient(
                    0f to canvas,
                    .30f to canvas.copy(.25f),
                    .55f to Color.Transparent,
                    1f to canvas,
                )
            },
        )
    }
}

/** The accent bar and wide-tracked eyebrow Home's hero opens with ("Anilili Featured"). */
@Composable
private fun TvKicker(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier,
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
        Text(text, color = Color.White.copy(.78f), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
    }
}

/**
 * A glass pill: faint white at rest, a little brighter with the television's focus ring round it.
 * A pointer clicks it directly.
 */
@Composable
private fun TvGlassPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val content = Color.White
    Row(
        modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusHighlight(shape)
            .clip(shape)
            .background(Color.White.copy(if (focused) .18f else .10f))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(.18f), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(19.dp))
        Text(
            label,
            color = content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A [TvGlassPill] that opens a menu of [options]: the TV filter row's format, airing and sort. */
@Composable
internal fun TvGlassDropdown(
    label: String,
    options: List<PageFilterOption>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val content = Color.White
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusHighlight(shape)
                .clip(shape)
                .background(Color.White.copy(if (focused) .16f else .08f))
                .border(1.dp, if (focused) Color.Transparent else Color.White.copy(.16f), shape)
                .clickable(role = Role.Button, onClickLabel = label) { expanded = true }
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = content,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                tint = if (focused) content else Color.White.copy(.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; onSelect(option.value) },
                )
            }
        }
    }
}

/** Dot-separated facts, as under a featured title on Home. The first may carry the service's logo. */
@Composable
private fun TvMetaLine(service: AccountService?, cells: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (service != null) {
            Image(
                painter = painterResource(
                    if (service == AccountService.ANILIST) R.drawable.ic_brand_anilist else R.drawable.ic_brand_mal,
                ),
                contentDescription = null,
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        cells.forEachIndexed { index, cell ->
            if (index > 0) Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White.copy(.38f)))
            Text(
                cell,
                color = Color.White.copy(.78f),
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Where the list's titles sit, one coloured dot each, leaving out the statuses with nothing in them. */
@Composable
private fun TvStatusLegend(counts: Map<ListStatusSlice, Int>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        counts.filterValues { it > 0 }.forEach { (slice, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(slice.color))
                Text(
                    count.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Text(" ${slice.label}", color = Color.White.copy(.7f), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/**
 * The headline totals at the hero's right, large enough to read from the sofa. A mean score only
 * appears once something is scored: a dash above "No scores yet" is the one figure not worth the room.
 */
@Composable
private fun TvHeadlineStats(insights: AccountInsights, modifier: Modifier = Modifier) {
    val stats = buildList {
        add(String.format(Locale.US, "%,d", insights.titles) to "Anime")
        add(String.format(Locale.US, "%,d", insights.episodes) to "Episodes")
        add(statWatchTime(insights.minutesWatched) to "Watch time")
        insights.meanScore?.let { add(String.format(Locale.US, "%.1f", it) to "Mean score") }
    }
    Row(modifier.height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        stats.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color.White.copy(.18f)),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 28.sp, maxLines = 1)
                Text(label, color = Color.White.copy(.66f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * My List's head on a television: the account over its own art, what is on the list, and the way
 * into the profile — in place of the two bordered panels (a banner card and a stack of dropdowns)
 * that together took most of the screen before a single poster showed.
 *
 * [claimFocus] puts the cursor back on Profile when the viewer returns from the profile page, so
 * Back lands where they left rather than at the top of the screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvLibraryHero(
    state: UiState<AniListProfile>?,
    fallbackBanner: String?,
    onProfile: () -> Unit,
    onSync: () -> Unit,
    claimFocus: Boolean,
    onFocusClaimed: () -> Unit,
    chipsFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val profile = (state as? UiState.Success)?.data
    val viewer = profile?.viewer
    val insights = remember(profile) { profile?.let(::accountInsights) }
    val profileFocus = remember { FocusRequester() }
    LaunchedEffect(claimFocus) {
        if (claimFocus) {
            awaitFrame()
            runCatching { profileFocus.requestFocus() }
            onFocusClaimed()
        }
    }
    // Compact on purpose. A television's lists pull the focused control to 30% of the way down the
    // viewport, so buttons any lower than that scroll the page the moment the remote arrives and
    // clip the hero's top; up here they are already above the pivot and the page stays put.
    Box(modifier.fillMaxWidth()) {
        TvAccountBackdrop(viewer?.bannerImage, fallbackBanner, Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = device.pagePadding, end = device.pagePadding, top = 8.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(
                    viewer?.avatar?.large,
                    Modifier
                        .size(76.dp)
                        .border(2.dp, Color.White.copy(.85f), CircleShape),
                    shape = CircleShape,
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 18.dp),
                ) {
                    TvKicker("My List")
                    when (state) {
                        is UiState.Success -> {
                            Text(
                                viewer?.name.orEmpty(),
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                lineHeight = 30.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            TvMetaLine(
                                state.data.service,
                                listOfNotNull("${state.data.service.label} connected", joinedLabel(viewer?.createdAt)),
                                Modifier.padding(top = 4.dp),
                            )
                        }
                        is UiState.Error -> {
                            Text(
                                "Your lists could not be loaded",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Text(
                                failureAnnotated(state.failure, messageColor = Color.White.copy(.7f)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        else -> Text(
                            "Syncing your profile…",
                            color = Color.White.copy(.78f),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (insights != null) TvHeadlineStats(insights, Modifier.padding(start = 24.dp))
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                // One group, entered at Profile: Down from the top bar otherwise picks whichever
                // pill sits nearest the My List tab, which is Sync.
                Row(
                    Modifier
                        .focusProperties { enter = { profileFocus } }
                        .focusGroup()
                        // After the group, so it reaches the pills: the search passes over the
                        // list chips below, so Down is sent to them by hand.
                        .focusProperties { down = chipsFocus },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvGlassPill(
                        Icons.Default.BarChart,
                        "Profile & stats",
                        onProfile,
                        Modifier.focusRequester(profileFocus),
                    )
                    TvGlassPill(
                        Icons.Default.Refresh,
                        if (state is UiState.Error) "Try again" else "Sync",
                        onSync,
                    )
                }
                insights?.let { TvStatusLegend(it.statusCounts, Modifier.padding(start = 28.dp)) }
            }
        }
    }
}

/**
 * The account profile as a page of its own on a television, where the phone opens a sheet.
 *
 * The sheet was a phone-width column laid over the library: charts meant to be read at arm's length
 * squeezed into a third of a screen seen from across a room, with the library's text showing beside
 * them. Here the same pieces — Overview, Stats and Activity — spread across the width in two
 * columns, under the account's art.
 *
 * Back leaves the page. It is claimed in preview for the reason [com.anilili.ui.pairing.TvPairingScreen]
 * gives: Compose turns Back into a focus exit before a nested BackHandler sees it, and the host
 * screen's handler would otherwise take the viewer out of My List altogether.
 */
@Composable
internal fun TvProfilePage(
    state: UiState<AniListProfile>?,
    continueWatching: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val profile = (state as? UiState.Success)?.data
    val viewer = profile?.viewer
    val service = profile?.service ?: AccountService.active
    val serviceLabel = service?.label ?: "your account"
    val insights = remember(profile) { profile?.let(::accountInsights) }
    val uriHandler = LocalUriHandler.current
    var confirmingLogout by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(DashboardTab.OVERVIEW) }
    val tabFocus = remember { FocusRequester() }
    val canvas = MaterialTheme.colorScheme.background

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { tabFocus.requestFocus() }
    }
    if (confirmingLogout) {
        MidnightConfirmDialog(
            kicker = "Log out",
            title = "Log out of $serviceLabel?",
            body = "This device stops syncing with $serviceLabel, and the lists and progress it " +
                "brought here leave with it. Your watchlist, custom lists and downloads made on this " +
                "device stay. Sign in again any time to bring the rest back.",
            confirmText = "Log out",
            onConfirm = {
                confirmingLogout = false
                onLogout()
            },
            onDismiss = { confirmingLogout = false },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .appCanvas()
            .onPreviewKeyEvent { event ->
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack && event.type == KeyEventType.KeyUp) onClose()
                isBack
            },
    ) {
        // Home's scrolling, not the platform's: a television's default pulls every focused control to
        // 30% of the way down, which on opening parked the tabs there and pushed the hero's name
        // under the top bar. This scrolls only as far as it takes to show what has focus.
        TvRailScrollSpec {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 40.dp),
            ) {
                // Hero: the account over its art, with its totals at the right.
                Box(Modifier.fillMaxWidth().heightIn(min = 236.dp)) {
                    TvAccountBackdrop(viewer?.bannerImage, continueWatching.firstOrNull()?.cover, Modifier.matchParentSize())
                    Column(Modifier.fillMaxWidth().padding(start = device.pagePadding, end = device.pagePadding, top = 12.dp, bottom = 18.dp)) {
                        // For a pointer, which has no Back key to reach for.
                        TvGlassPill(Icons.AutoMirrored.Filled.ArrowBack, "My List", onClose)
                        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Bottom) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                AccountAvatar(
                                    viewer?.avatar?.large,
                                    Modifier
                                        .size(104.dp)
                                        .border(3.dp, Color.White.copy(.9f), CircleShape),
                                    shape = CircleShape,
                                )
                                Column(Modifier.padding(start = 22.dp)) {
                                    TvKicker("Profile")
                                    Text(
                                        viewer?.name ?: "Your account",
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 34.sp,
                                        lineHeight = 36.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    TvMetaLine(
                                        service,
                                        listOfNotNull(service?.let { "${it.label} connected" }, joinedLabel(viewer?.createdAt)),
                                        Modifier.padding(top = 6.dp),
                                    )
                                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TvGlassPill(Icons.Default.Refresh, "Sync now", onSync)
                                        val name = viewer?.name
                                        if (service != null && !name.isNullOrBlank()) {
                                            TvGlassPill(Icons.AutoMirrored.Filled.OpenInNew, "Open on ${service.label}", {
                                                runCatching { uriHandler.openUri(accountProfileUrl(service, name)) }
                                            })
                                        }
                                    }
                                }
                            }
                            if (insights != null) TvHeadlineStats(insights, Modifier.padding(start = 24.dp))
                        }
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    when (state) {
                        is UiState.Success -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DashboardTab.entries.forEach { entry ->
                                    TabPill(
                                        entry.label,
                                        selected = entry == tab,
                                        onClick = { tab = entry },
                                        modifier = Modifier
                                            .width(150.dp)
                                            .then(if (entry == tab) Modifier.focusRequester(tabFocus) else Modifier),
                                    )
                                }
                            }
                            TvDashboard(insights!!, state.data, tab, onTabChange = { tab = it })
                        }
                        is UiState.Error -> Column {
                            Text("Your lists could not be loaded", fontWeight = FontWeight.Bold, color = Midnight.Text)
                            Text(
                                failureAnnotated(state.failure, messageColor = Midnight.Text2),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> Text("Syncing your profile…", color = Midnight.Text2)
                    }
                }
                // Home's own Continue watching rail, edge to edge as it is there, so the cards and their
                // focus are the same ones the viewer already knows.
                if (state is UiState.Success && tab == DashboardTab.OVERVIEW && continueWatching.isNotEmpty()) {
                    Box(Modifier.padding(top = 18.dp)) {
                        TvContinueRail(
                            history = continueWatching,
                            onResume = onResume,
                            onOpenDetails = onOpenDetails,
                            blockDown = false,
                            focusKey = "profile",
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = device.pagePadding, end = device.pagePadding, top = 26.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Account", service?.let { "Signed in with ${it.label}" } ?: "Your account")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccountActionRow(
                                Icons.Default.Refresh,
                                "Sync now",
                                "Pull your lists from $serviceLabel again",
                                onSync,
                                modifier = Modifier.weight(1f),
                            )
                            AccountActionRow(
                                Icons.AutoMirrored.Filled.Logout,
                                "Log out",
                                "Sign this television out of $serviceLabel",
                                { confirmingLogout = true },
                                danger = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Overview, Stats and Activity, laid out in two columns for a television's width. */
@Composable
private fun TvDashboard(
    insights: AccountInsights,
    profile: AniListProfile,
    tab: DashboardTab,
    onTabChange: (DashboardTab) -> Unit,
) {
    val localActivity by WatchActivityStore.days.collectAsState()
    val onlyPlanning = insights.statusCounts.filterKeys { it != ListStatusSlice.PLANNING }.values.sum() == 0
    when (tab) {
        DashboardTab.OVERVIEW -> Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                YourAnimeBar(insights.statusCounts, onViewAll = { onTabChange(DashboardTab.STATS) })
                // Continue watching is Home's rail under both columns; the taste summary takes its
                // place here so the two columns stand about as tall as each other.
                if (insights.topGenres.isNotEmpty()) {
                    HorizontalBars(
                        "Top genres",
                        if (onlyPlanning) "Across the titles you plan to watch" else "Across the titles you have started",
                        insights.topGenres,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeading("Recent activity", action = "View all" to { onTabChange(DashboardTab.ACTIVITY) })
                if (insights.recent.isEmpty()) {
                    Text("Nothing on your list has changed yet.", color = Midnight.Text2)
                } else {
                    insights.recent.take(4).forEach { ActivityRow(it) }
                }
            }
        }
        DashboardTab.STATS -> {
            val activity = remember(localActivity, profile.activityHistory) {
                mergedActivity(localActivity, profile.activityHistory)
            }
            val panels = buildList<@Composable () -> Unit> {
                add { StatusBreakdown(insights.statusCounts) }
                if (insights.formats.isNotEmpty()) add { HorizontalBars("Formats", "What your list is made of", insights.formats) }
                if (insights.topGenres.isNotEmpty()) {
                    add {
                        HorizontalBars(
                            "Top genres",
                            if (onlyPlanning) "Across the titles you plan to watch" else "Across the titles you have started",
                            insights.topGenres,
                        )
                    }
                }
                if (insights.studios.isNotEmpty()) add { HorizontalBars("Top studios", "Who made what you have started", insights.studios) }
                if (insights.releaseYears.isNotEmpty()) {
                    add { ColumnChart("Release years", "When the titles on your list came out", insights.releaseYears) }
                }
                if (insights.scoreSpread.isNotEmpty()) {
                    add { ColumnChart("Your scores", "How you rate what you have scored", insights.scoreSpread) }
                } else {
                    add { SectionTitle("Your scores", "Score a few titles and your spread shows up here") }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Box(Modifier.weight(1.25f)) {
                        ActivityHeatmap(activity, fromAccount = profile.service == AccountService.ANILIST)
                    }
                    Box(Modifier.weight(1f)) { HighlightTiles(insights) }
                }
                panels.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                        pair.forEach { panel -> Box(Modifier.weight(1f)) { panel() } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        DashboardTab.ACTIVITY -> {
            if (insights.recent.isEmpty()) {
                Text("Nothing on your list has changed yet.", color = Midnight.Text2)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    insights.recent.take(20).chunked(2).forEach { pair: List<MediaListEntry> ->
                        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                            pair.forEach { entry -> Box(Modifier.weight(1f)) { ActivityRow(entry) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

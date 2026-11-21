package com.anilili.ui.profile




import com.anilili.ui.theme.canvasColor
import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.R
import com.anilili.data.DubIndex
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.library.CustomList
import com.anilili.data.library.CustomListStore
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchedEntry
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.library.continueWatchingCaption
import com.anilili.data.library.listKey
import com.anilili.data.model.MediaListEntry
import com.anilili.data.settings.PosterElement
import com.anilili.errors.AnililiError
import com.anilili.errors.asFailure
import com.anilili.errors.toFailure
import com.anilili.playback.DownloadLocationFiles
import com.anilili.playback.DownloadLocations
import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.EpisodeExportState
import com.anilili.playback.EpisodeExportStatus
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.OfflineSeries
import com.anilili.playback.episodeDownloadBadges
import com.anilili.playback.offlineEpisodeBlocks
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.offlineSeries
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.CaptionedCardShape
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.TvCardCornerShape
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.home.TV_MEDIA_CARD_ASPECT_RATIO
import com.anilili.ui.home.TvCentredScrollSpec
import com.anilili.ui.home.TvContinueRail
import com.anilili.ui.home.TvMediaCardWidth
import com.anilili.ui.home.TvRailScrollSpec
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AnimeListActionsDialog
import com.anilili.ui.components.AppChromeListBottomGap
import com.anilili.ui.components.CardCornerAction
import com.anilili.ui.components.ContinueWatchingActionsDialog
import com.anilili.ui.components.CustomListManageDialog
import com.anilili.ui.components.CustomListNameDialog
import com.anilili.ui.components.CornerBadge
import com.anilili.ui.components.DownloadCoverBadge
import com.anilili.ui.components.DubBadge
import com.anilili.ui.components.EpisodeFillerBadge
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.NewEpisodesBadge
import com.anilili.ui.components.PageFilterOption
import com.anilili.ui.components.PageFilterPill
import com.anilili.ui.components.PageFilterRow
import com.anilili.ui.components.PageMenuChoice
import com.anilili.ui.components.PageMenuSectionLabel
import com.anilili.ui.components.PageSearch
import com.anilili.ui.components.PageSortControl
import com.anilili.ui.components.PageTitleBar
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.RatingBadge
import com.anilili.ui.components.ScoreTag
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.components.WatchProgressBar
import com.anilili.ui.components.deleteAnimeListEntry
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.failureAnnotated
import com.anilili.ui.components.formatPlaybackPosition
import com.anilili.ui.components.rememberPosterElements
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.pairing.PhonePairingScreen
import com.anilili.ui.pairing.TvPairingScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A list the page can show. Several are shown at once — see [libraryViewsFrom] — so the labels come
 * in two lengths: the heading over a section, and the shorter one a chip has room for.
 */
internal enum class LibraryView(val label: String, val chipLabel: String = label) {
    WATCHLIST("My watchlist", "Watchlist"),
    WATCHING("Watching"),
    REWATCHING("Re-watching"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    DROPPED("Dropped"),
}

internal enum class LibrarySort(val label: String) {
    LAST_MODIFIED("Last modified"),
    TITLE("Title (A–Z)"),
    SCORE("Highest score"),
    PROGRESS("Most progress"),
    DEFAULT("Default"),
}


internal data class SavedAnimeCardData(
    val id: Int,
    val title: String,
    val cover: String?,
    val format: String?,
    val airingStatus: String?,
    val status: String?,
    val userScore: Double?,
    val averageScore: Int?,
    val progress: Int?,
    val totalEpisodes: Int?,
    val updatedAt: Long = 0L,
    /** AniList's next scheduled broadcast, which is what says how much of an airing series is out. */
    val nextEpisodeNumber: Int? = null,
    /** Epoch seconds; null for anything without a scheduled episode. */
    val nextEpisodeAiringAt: Long? = null,
)

private val formatOptions = listOf(
    PageFilterOption(null, "Any format"),
    PageFilterOption("TV", "TV"),
    PageFilterOption("MOVIE", "Movie"),
    PageFilterOption("ONA", "ONA"),
    PageFilterOption("OVA", "OVA"),
    PageFilterOption("SPECIAL", "Special"),
)

private val airingOptions = listOf(
    PageFilterOption(null, "Any airing status"),
    PageFilterOption("RELEASING", "Ongoing"),
    PageFilterOption("FINISHED", "Completed"),
    PageFilterOption("NOT_YET_RELEASED", "Upcoming"),
    PageFilterOption("HIATUS", "On hiatus"),
    PageFilterOption("CANCELLED", "Cancelled"),
)

private val sortOptions = LibrarySort.entries.map { PageFilterOption(it.name, "Sort: ${it.label}") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    onPlayDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val historyByAnimeId = remember(history) { history.associateBy(HistoryEntry::anilistId) }
    val watchlist by LibraryStore.watchlist.collectAsState()
    val customLists by CustomListStore.lists.collectAsState()
    val localWatched by LocalWatchedStore.entries.collectAsState()
    // The custom lists turned *off*, by [listKey]: a list the viewer just made should show up
    // without being asked for, so the default is every list on.
    var hiddenCustomListKeys by rememberSaveable { mutableStateOf("") }
    var expandedCustomListKeys by rememberSaveable { mutableStateOf("") }
    var namingList by remember { mutableStateOf(false) }
    var managingListName by remember { mutableStateOf<String?>(null) }
    val downloadIndex by EpisodeDownloads.downloads(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val exportStatuses by EpisodeExport.statuses(context).collectAsState()
    // One card per episode whether it is held as cached segments, as an MP4 in Downloads, or both.
    val episodeDownloads = remember(downloadIndex, exportedEpisodes) {
        offlineEpisodes(downloadIndex, exportedEpisodes)
    }
    // The flat list is unmanageable for long-runners; the section below groups by anime instead.
    val downloadSeries = remember(episodeDownloads) { offlineSeries(episodeDownloads) }
    val isRefreshing by vm.isRefreshing.collectAsState()
    var loginService by remember { mutableStateOf<AccountService?>(null) }
    var showPairing by remember { mutableStateOf(false) }
    var profileSheetVisible by remember { mutableStateOf(false) }
    var tvProfileFocusReturn by remember { mutableStateOf(false) }
    // The TV list chips, which the remote's own search passes over between the hero and the
    // filter row; both route to them by hand.
    val tvChipsFocus = remember { FocusRequester() }
    var selectedViewNames by rememberSaveable { mutableStateOf(defaultLibraryViewNames()) }
    var selectedFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAiring by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSortName by rememberSaveable { mutableStateOf(LibrarySort.LAST_MODIFIED.name) }
    var titleFilter by rememberSaveable { mutableStateOf("") }
    var pendingLegacyExportId by remember { mutableStateOf<String?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val downloadId = pendingLegacyExportId
        pendingLegacyExportId = null
        if (granted && downloadId != null) {
            EpisodeExport.request(context, downloadId)
        } else {
            Toast.makeText(
                context,
                "Storage permission is needed to save MP4 files in Downloads on Android 9 and earlier.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val requestEpisodeExport: (String) -> Unit = { downloadId ->
        if (DownloadLocations.needsLegacyPermission(context)) {
            pendingLegacyExportId = downloadId
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            EpisodeExport.request(context, downloadId)
        }
    }
    val loggedIn = token != null || malLoggedIn

    LaunchedEffect(loggedIn) {
        // Completed is there signed out too, holding what was marked watched on this device.
        if (!loggedIn) selectedViewNames = listOf(LibraryView.WATCHLIST, LibraryView.COMPLETED).joinToString(",") { it.name }
        vm.loadIfLoggedIn()
    }

    when (loginService) {
        AccountService.ANILIST -> {
            LoginWebView(
                authorizeUrl = remember { AuthManager.authorizeUrl() },
                isRedirect = AuthManager::isRedirect,
                extractResult = AuthManager::extractToken,
                onResult = {
                    loginService = null
                    vm.onLoggedIn(it)
                    confirmSignedIn(context, "AniList")
                },
                onCancel = { loginService = null },
            )
            return
        }
        AccountService.MAL -> {
            LoginWebView(
                authorizeUrl = remember { MalAuthManager.authorizeUrl() },
                isRedirect = MalAuthManager::isRedirect,
                extractResult = MalAuthManager::extractCode,
                onResult = { loginService = null; vm.onMalCode(it) },
                onCancel = { loginService = null },
            )
            return
        }
        null -> Unit
    }

    // Signing in from the other device. On a television this is the way in that does not involve
    // driving a login page with a remote; on a phone it is how a television gets signed in.
    if (showPairing) {
        if (device.isTv) {
            TvPairingScreen(
                onFinished = {
                    showPairing = false
                    vm.loadIfLoggedIn()
                },
                onCancel = { showPairing = false },
            )
        } else {
            PhonePairingScreen(
                onFinished = { showPairing = false },
                onCancel = { showPairing = false },
            )
        }
        return
    }

    val profile = (profileState as? UiState.Success)?.data
    val combinedWatchlist = remember(profile, watchlist, history) {
        buildCombinedWatchlist(profile, watchlist, history)
    }
    // Signed out, or before the account's lists land, the watchlist is the only list there is —
    // beside Completed, once something has been marked watched on this device.
    val availableViews = when {
        profile != null -> LibraryView.entries.toList()
        localWatched.isNotEmpty() -> listOf(LibraryView.WATCHLIST, LibraryView.COMPLETED)
        else -> listOf(LibraryView.WATCHLIST)
    }
    val selectedViews = libraryViewsFrom(selectedViewNames, availableViews)
    val selectedSort = LibrarySort.entries.firstOrNull { it.name == selectedSortName } ?: LibrarySort.LAST_MODIFIED
    // Every list, not only the shown ones: the chips carry counts, and a count that ignored the
    // filters would send a viewer into a section the search had already emptied.
    val cardsByView = remember(profile, combinedWatchlist, localWatched, selectedFormat, selectedAiring, selectedSort, titleFilter) {
        LibraryView.entries.associateWith { view ->
            libraryCards(
                source = when (view) {
                    LibraryView.WATCHLIST -> combinedWatchlist
                    LibraryView.WATCHING -> aniListCards(profile?.watching.orEmpty())
                    LibraryView.REWATCHING -> aniListCards(profile?.rewatching.orEmpty())
                    LibraryView.PAUSED -> aniListCards(profile?.paused.orEmpty())
                    LibraryView.COMPLETED -> completedCards(profile, localWatched)
                    LibraryView.DROPPED -> aniListCards(profile?.dropped.orEmpty())
                },
                format = selectedFormat,
                airing = selectedAiring,
                titleFilter = titleFilter,
                sort = selectedSort,
            )
        }
    }
    val viewCounts = remember(cardsByView) { cardsByView.mapValues { (_, cards) -> cards.size } }
    val cardsByCustomList = remember(customLists, profile, history, selectedFormat, selectedAiring, selectedSort, titleFilter) {
        customLists.associate { list ->
            listKey(list.name) to libraryCards(
                source = customListCards(list, profile, history),
                format = selectedFormat,
                airing = selectedAiring,
                titleFilter = titleFilter,
                sort = selectedSort,
            )
        }
    }
    val hiddenCustomLists = remember(hiddenCustomListKeys) { hiddenCustomListKeys.split(',').toSet() }
    val shownCustomLists = customLists.filterNot { listKey(it.name) in hiddenCustomLists }
    val shownCount = selectedViews.sumOf { viewCounts[it] ?: 0 } +
        shownCustomLists.sumOf { cardsByCustomList[listKey(it.name)]?.size ?: 0 }
    val customChips = CustomListChips(
        chips = customLists.map { list ->
            CustomListChip(
                name = list.name,
                count = cardsByCustomList[listKey(list.name)]?.size ?: 0,
                selected = listKey(list.name) !in hiddenCustomLists,
            )
        },
        onToggle = { name -> hiddenCustomListKeys = toggledKeys(hiddenCustomListKeys, listKey(name)) },
        onNew = { namingList = true },
    )
    // Which sections are grids rather than carousels, held per list so opening one out does not
    // turn every other list on the page into a wall of posters.
    var expandedViewNames by rememberSaveable { mutableStateOf("") }
    val expandedViews = remember(expandedViewNames) { expandedViewNames.split(',').toSet() }
    var isHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var isDownloadsExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedDownloadSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDownloadsRemoveAll by remember { mutableStateOf(false) }
    var downloadEpisodePendingRemovalId by remember { mutableStateOf<String?>(null) }
    val selectedDownloadSeries = downloadSeries.firstOrNull { it.anilistId == selectedDownloadSeriesId }
    val downloadEpisodePendingRemoval = episodeDownloads.firstOrNull {
        it.id == downloadEpisodePendingRemovalId
    }
    var selectedDownloadCategory by remember(selectedDownloadSeriesId) { mutableStateOf<String?>(null) }
    var selectedDownloadBlock by remember(selectedDownloadSeriesId, selectedDownloadCategory) { mutableStateOf(0) }
    // The series the viewer is browsing can vanish under them (remove-all, or the last episode
    // deleted from the watch page); drop back to the series list instead of showing a ghost.
    LaunchedEffect(selectedDownloadSeriesId, selectedDownloadSeries) {
        if (selectedDownloadSeriesId != null && selectedDownloadSeries == null) {
            selectedDownloadSeriesId = null
        }
    }
    LaunchedEffect(downloadEpisodePendingRemovalId, downloadEpisodePendingRemoval) {
        if (downloadEpisodePendingRemovalId != null && downloadEpisodePendingRemoval == null) {
            downloadEpisodePendingRemovalId = null
        }
    }
    val profileListState = rememberLazyListState()

    if (showDownloadsRemoveAll && selectedDownloadSeries != null) {
        val series = selectedDownloadSeries
        MidnightConfirmDialog(
            kicker = "Remove downloads",
            title = "Remove all episodes?",
            body = "Every saved episode of ${series.title} goes from this device, along with any " +
                "MP4 copies in the Downloads folder. Your watch progress is untouched.",
            confirmText = "Remove all",
            onConfirm = {
                series.episodes.forEach { removeOfflineEpisode(context, it) }
                showDownloadsRemoveAll = false
                selectedDownloadSeriesId = null
            },
            onDismiss = { showDownloadsRemoveAll = false },
        )
    }

    if (downloadEpisodePendingRemoval != null) {
        val episode = downloadEpisodePendingRemoval
        MidnightConfirmDialog(
            kicker = "Remove download",
            title = "Remove this episode?",
            body = "${episode.metadata.seriesTitle} · Episode ${episode.metadata.episodeNumber} " +
                "goes from this device, along with its MP4 copy in Downloads when one exists. " +
                "Your watch progress is untouched.",
            confirmText = "Remove episode",
            onConfirm = {
                removeOfflineEpisode(context, episode)
                downloadEpisodePendingRemovalId = null
            },
            onDismiss = { downloadEpisodePendingRemovalId = null },
        )
    }

    if (namingList) {
        CustomListNameDialog(
            title = "New list",
            confirmText = "Create",
            onSave = { name ->
                CustomListStore.create(name)?.also {
                    hiddenCustomListKeys = hiddenCustomListKeys.split(',').filterNot { key -> key == listKey(it) }.joinToString(",")
                    namingList = false
                }
            },
            onDismiss = { namingList = false },
        )
    }
    managingListName?.let { name ->
        val list = customLists.firstOrNull { listKey(it.name) == listKey(name) }
        if (list == null) {
            managingListName = null
        } else {
            CustomListManageDialog(list = list, onDismiss = { managingListName = null })
        }
    }

    // A television opens the profile as a page of its own rather than the phone's sheet; the
    // library's list state is held above, so Back returns to the same place on it.
    if (profileSheetVisible && device.isTv) {
        TvProfilePage(
            state = profileState,
            continueWatching = history,
            onResume = { profileSheetVisible = false; onResume(it) },
            onOpenDetails = { profileSheetVisible = false; onOpenDetails(it) },
            onSync = { vm.loadIfLoggedIn() },
            onLogout = { profileSheetVisible = false; vm.logout() },
            onClose = { profileSheetVisible = false; tvProfileFocusReturn = true },
        )
        return
    }

    if (profileSheetVisible) {
        ProfileSheet(
            state = profileState,
            continueWatching = history,
            onResume = { profileSheetVisible = false; onResume(it) },
            onPair = { profileSheetVisible = false; showPairing = true },
            onSync = { vm.loadIfLoggedIn() },
            onLogout = { profileSheetVisible = false; vm.logout() },
            onDismiss = { profileSheetVisible = false },
        )
    }

    Scaffold(
        containerColor = canvasColor(),
        modifier = modifier,
        topBar = {
            // A signed-in television names the page in its hero instead, over the account's art.
            if (!(device.isTv && loggedIn)) ScrollAwareTopBar {
                PageTitleBar(
                    title = "My List",
                    // The account pill is the phone's, and only once there is an account to show.
                    // A television is built around D-pad rows and a pill is a poor thing to land
                    // focus on from across a room; signed out there is nothing to put in one.
                    trailing = if (device.isTv || !loggedIn) {
                        null
                    } else {
                        {
                            LibraryProfilePill(
                                state = profileState,
                                onClick = { profileSheetVisible = true },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        PullRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
            // The list reserves the bar as scroll padding rather than sitting below it, so this box
            // starts behind the bar and the puck needs telling; without it a pull on My List spun
            // underneath an opaque header and looked like nothing happened.
            indicatorTopPadding = padding.calculateTopPadding(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // On TV the focused row comes to rest mid-screen, going up as well as down, as on Home.
                TvCentredScrollSpec {
                    LazyColumn(
                        state = profileListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() +
                                LocalAppChromeBottomInset.current + AppChromeListBottomGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (device.isTv && !loggedIn) {
                            // Give the two provider buttons the whole first focus row. In the old
                            // split layout, D-pad focus entered the filter panel on the right and made
                            // the AniList button appear unresponsive or unreachable on some TVs.
                            item {
                                ProfileHero(
                                    onLogin = { loginService = it },
                                    onPair = { showPairing = true },
                                    modifier = Modifier.padding(horizontal = device.pagePadding),
                                )
                            }
                            item {
                                TvLibraryToolbar(
                                    availableViews = availableViews,
                                    selectedViews = selectedViews,
                                    viewCounts = viewCounts,
                                    onToggleView = { selectedViewNames = toggledLibraryViews(selectedViewNames, it) },
                                    selectedFormat = selectedFormat,
                                    onFormatChange = { selectedFormat = it },
                                    selectedAiring = selectedAiring,
                                    onAiringChange = { selectedAiring = it },
                                    selectedSort = selectedSort,
                                    onSortChange = { selectedSortName = it.name },
                                    titleFilter = titleFilter,
                                    onTitleFilterChange = { titleFilter = it },
                                    resultCount = shownCount,
                                    customChips = customChips,
                                    chipsFocus = tvChipsFocus,
                                )
                            }
                        } else if (device.isTv) {
                            item {
                                TvLibraryHero(
                                    state = profileState,
                                    // An account with no banner of its own borrows the cover of what
                                    // was watched last, as the profile does.
                                    fallbackBanner = history.firstOrNull()?.cover,
                                    onProfile = { profileSheetVisible = true },
                                    onSync = { vm.loadIfLoggedIn() },
                                    claimFocus = tvProfileFocusReturn,
                                    onFocusClaimed = { tvProfileFocusReturn = false },
                                    chipsFocus = tvChipsFocus,
                                )
                            }
                            item {
                                TvLibraryToolbar(
                                    availableViews = availableViews,
                                    selectedViews = selectedViews,
                                    viewCounts = viewCounts,
                                    onToggleView = { selectedViewNames = toggledLibraryViews(selectedViewNames, it) },
                                    selectedFormat = selectedFormat,
                                    onFormatChange = { selectedFormat = it },
                                    selectedAiring = selectedAiring,
                                    onAiringChange = { selectedAiring = it },
                                    selectedSort = selectedSort,
                                    onSortChange = { selectedSortName = it.name },
                                    titleFilter = titleFilter,
                                    onTitleFilterChange = { titleFilter = it },
                                    resultCount = shownCount,
                                    customChips = customChips,
                                    chipsFocus = tvChipsFocus,
                                )
                            }
                        } else if (!loggedIn) {
                            // Signed out, the sign-in card *is* the page, so it keeps the whole width;
                            // there is no account to put in a pill.
                            item {
                                ProfileHero(
                                    onLogin = { loginService = it },
                                    onPair = { showPairing = true },
                                    modifier = Modifier.padding(horizontal = device.pagePadding),
                                )
                            }
                            // The same header a signed-in phone gets. This used to be the television's
                            // boxed panel — full-width dropdowns and a text field — so the page changed
                            // shape the moment someone signed in, and read heavier before they had.
                            item {
                                LibraryHeader(
                                    availableViews = availableViews,
                                    selectedViews = selectedViews,
                                    viewCounts = viewCounts,
                                    onToggleView = { selectedViewNames = toggledLibraryViews(selectedViewNames, it) },
                                    selectedFormat = selectedFormat,
                                    onFormatChange = { selectedFormat = it },
                                    selectedSort = selectedSort,
                                    onSortChange = { selectedSortName = it.name },
                                    selectedAiring = selectedAiring,
                                    onAiringChange = { selectedAiring = it },
                                    titleFilter = titleFilter,
                                    onTitleFilterChange = { titleFilter = it },
                                    resultCount = shownCount,
                                    customChips = customChips,
                                    modifier = Modifier.padding(horizontal = device.pagePadding),
                                )
                            }
                        } else {
                            item {
                                LibraryHeader(
                                    availableViews = availableViews,
                                    selectedViews = selectedViews,
                                    viewCounts = viewCounts,
                                    onToggleView = { selectedViewNames = toggledLibraryViews(selectedViewNames, it) },
                                    selectedFormat = selectedFormat,
                                    onFormatChange = { selectedFormat = it },
                                    selectedSort = selectedSort,
                                    onSortChange = { selectedSortName = it.name },
                                    selectedAiring = selectedAiring,
                                    onAiringChange = { selectedAiring = it },
                                    titleFilter = titleFilter,
                                    onTitleFilterChange = { titleFilter = it },
                                    resultCount = shownCount,
                                    customChips = customChips,
                                    modifier = Modifier.padding(horizontal = device.pagePadding),
                                )
                            }
                        }

                        if (selectedViews.isEmpty() && shownCustomLists.isEmpty()) {
                            item { EmptyPanel("Choose a list above to show it here") }
                        }
                        val serviceLabel = profile?.service?.label ?: "AniList"
                        val filtersApplied =
                            selectedFormat != null || selectedAiring != null || titleFilter.isNotBlank()
                        selectedViews.forEach { view ->
                            val cards = cardsByView[view].orEmpty()
                            libraryListSection(
                                key = view.name,
                                title = view.label,
                                cards = cards,
                                subtitle = if (view == LibraryView.WATCHLIST && !loggedIn) {
                                    "${cards.size} anime · Saved on this device"
                                } else if (view == LibraryView.WATCHLIST) {
                                    "${cards.size} anime · Saved here and in $serviceLabel Planning"
                                } else if (!loggedIn) {
                                    // Only Completed is offered signed out, filled from the device's marks.
                                    "${cards.size} anime · Marked watched on this device"
                                } else {
                                    "${cards.size} anime · Synced from $serviceLabel"
                                },
                                emptyText = if (view == LibraryView.WATCHLIST && !filtersApplied) {
                                    "Tap the heart on any anime to save it"
                                } else {
                                    "No anime match these filters"
                                },
                                isExpanded = view.name in expandedViews,
                                onToggleExpand = {
                                    expandedViewNames = toggledLibraryViews(expandedViewNames, view)
                                },
                                columns = device.posterColumns,
                                pagePadding = device.pagePadding,
                                onAnimeClick = onAnimeClick,
                                onListChanged = vm::refresh,
                            )
                        }
                        shownCustomLists.forEach { list ->
                            val key = listKey(list.name)
                            val cards = cardsByCustomList[key].orEmpty()
                            libraryListSection(
                                key = "custom_$key",
                                title = list.name,
                                cards = cards,
                                subtitle = "${cards.size} anime · " + when (profile?.service ?: AccountService.active) {
                                    AccountService.ANILIST -> "Your AniList custom list"
                                    AccountService.MAL -> "A MyAnimeList tag"
                                    null -> "On this device"
                                },
                                emptyText = if (filtersApplied) {
                                    "No anime match these filters"
                                } else if (device.isTv) {
                                    "Hold Select on any anime to add it to this list"
                                } else {
                                    "Long-press any anime to add it to this list"
                                },
                                isExpanded = key in expandedCustomListKeys.split(','),
                                onToggleExpand = {
                                    expandedCustomListKeys = toggledKeys(expandedCustomListKeys, key)
                                },
                                columns = device.posterColumns,
                                pagePadding = device.pagePadding,
                                onAnimeClick = onAnimeClick,
                                onListChanged = vm::refresh,
                                onManage = { managingListName = list.name },
                            )
                        }

                        item {
                            ProfileSectionTitle(
                                title = "Continue Watching",
                                subtitle = if (device.isTv) {
                                    "Hold Select on a title to remove it or move it on your anime list"
                                } else {
                                    "Long-press a title to remove it or move it on your anime list"
                                },
                                isExpanded = isHistoryExpanded,
                                onToggleExpand = { isHistoryExpanded = !isHistoryExpanded },
                            )
                        }
                        if (history.isEmpty()) {
                            item { EmptyPanel("Nothing watched yet") }
                        } else if (isHistoryExpanded) {
                            val columns = device.posterColumns
                            val historyRows = history.chunked(columns)
                            itemsIndexed(historyRows, key = { rowIndex, _ -> "hist_row_$rowIndex" }) { _, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    for (entry in row) {
                                        HistoryCard(
                                            entry = entry,
                                            onResume = onResume,
                                            onOpenDetails = onOpenDetails,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            item {
                                if (device.isTv) {
                                    // Home's own rail, so the card, its corner buttons and its focus
                                    // read the same on both pages.
                                    TvContinueRail(
                                        history = history,
                                        onResume = onResume,
                                        onOpenDetails = onOpenDetails,
                                        blockDown = false,
                                        focusKey = "my-list",
                                        title = null,
                                    )
                                } else {
                                    // Continue Watching reorders on the same two waves: signing in seeds
                                    // it with rows from the account's own progress. Same row, same homing.
                                    LibraryCarousel(history, itemKey = { it.anilistId }) { entry ->
                                        HistoryCard(entry, onResume, onOpenDetails)
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
    }
}

/**
 * The signed-out card: what connecting an account brings, and one row per way in. Signed in, a phone
 * shows the account in its pill and a television in [TvLibraryHero], so this is only ever the prompt.
 */
@Composable
private fun ProfileHero(
    onLogin: (AccountService) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Your anime, in one place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Connect AniList or MyAnimeList to browse every list, score, and episode progress from Anilili.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
            // One row per way in, each the full width of the card. The buttons these replace
            // sized themselves to their words, so "Login with MAL" wrapped onto two lines beside
            // a one-line AniList button and the TV link sat alone underneath — three shapes for
            // three equal choices, and nothing but the words to tell the services apart.
            Column(
                Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SignInOptionRow(
                    title = "Continue with AniList",
                    subtitle = "Sync with anilist.co",
                    onClick = { onLogin(AccountService.ANILIST) },
                    emphasized = true,
                    badge = { ServiceLogo(R.drawable.ic_brand_anilist) },
                )
                SignInOptionRow(
                    title = "Continue with MyAnimeList",
                    subtitle = "Sync with myanimelist.net",
                    onClick = { onLogin(AccountService.MAL) },
                    badge = { ServiceLogo(R.drawable.ic_brand_mal) },
                )
                // A television cannot realistically work through the login page: the sign-in
                // form now sits behind a cookie consent dialog and a Cloudflare check, both of
                // them cross-origin overlays that a D-pad cannot reliably reach. Signing in from
                // the phone sidesteps the page entirely.
                SignInOptionRow(
                    title = if (device.isTv) "Sign in from my phone" else "Link a TV",
                    subtitle = if (device.isTv) {
                        "Skip typing with the remote"
                    } else {
                        "Sign your TV in from this phone"
                    },
                    onClick = onPair,
                    badge = {
                        IconBadge(if (device.isTv) Icons.Default.PhoneAndroid else Icons.Default.Tv)
                    },
                )
            }
        }
    }
}

/**
 * One way to sign in: a badge, what it does, and a chevron. The whole row is the target, so a
 * thumb or a remote lands on it anywhere. [emphasized] fills the row for the suggested choice.
 */
@Composable
private fun SignInOptionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val focus = rememberFocusFill()
    val container = if (emphasized) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }
    Surface(
        onClick = onClick,
        shape = shape,
        color = focus.background(container),
        contentColor = focus.content(MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (emphasized) 0.9f else 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .focusFill(focus, shape),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            badge()
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = focus.content(MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = focus.content(MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = focus.content(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/** A service's own square logo, at the same size and corner as the other badges. */
@Composable
private fun ServiceLogo(@androidx.annotation.DrawableRes logo: Int) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

/**
 * The phone's My List header: the page title, the search field, the lists on show, and the two
 * filters worth keeping on screen.
 *
 * What this replaces was two bordered cards — a banner, an 88dp avatar, three stat columns and a
 * button row, then four stacked full-width selectors — which together cost most of a phone screen
 * before the first title appeared. Everything is still reachable: the account is the pill beside
 * the page title in [LibraryTopBar], and sort and airing status share the compact control at the
 * end of the filter row (see [LibrarySortControl]).
 */
@Composable
private fun LibraryHeader(
    availableViews: List<LibraryView>,
    selectedViews: List<LibraryView>,
    viewCounts: Map<LibraryView, Int>,
    onToggleView: (LibraryView) -> Unit,
    selectedFormat: String?,
    onFormatChange: (String?) -> Unit,
    selectedSort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    selectedAiring: String?,
    onAiringChange: (String?) -> Unit,
    titleFilter: String,
    onTitleFilterChange: (String) -> Unit,
    resultCount: Int,
    customChips: CustomListChips,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageSearch(
            value = titleFilter,
            onValueChange = onTitleFilterChange,
            placeholder = "Search library",
            clearLabel = "Clear search",
            resultSummary = "$resultCount anime",
        )
        LibraryViewChips(
            availableViews = availableViews,
            selectedViews = selectedViews,
            viewCounts = viewCounts,
            onToggle = onToggleView,
            customChips = customChips,
        )
        PageFilterRow {
            PageFilterPill(
                label = formatOptions.first { it.value == selectedFormat }.label,
                options = formatOptions,
                onSelect = onFormatChange,
                modifier = Modifier.weight(1f),
            )
            // Sort and airing status share this control because they are the two filters nobody
            // changes twice in a session.
            PageSortControl(contentDescription = "Sort and filter, ${selectedSort.label}") { dismiss ->
                PageMenuSectionLabel("Sort by")
                LibrarySort.entries.forEach { sort ->
                    PageMenuChoice(sort.label, sort == selectedSort) { dismiss(); onSortChange(sort) }
                }
                HorizontalDivider()
                PageMenuSectionLabel("Airing status")
                airingOptions.forEach { option ->
                    PageMenuChoice(option.label, option.value == selectedAiring) {
                        dismiss(); onAiringChange(option.value)
                    }
                }
            }
        }
        // The long-press hint used to sit under every section heading. One page can now hold six of
        // them, so it is said once, here, where the count it shares a line with is also page-wide.
        Text(
            "$resultCount anime · Long-press a title to move it on your anime list",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The lists themselves, as toggles rather than as a menu.
 *
 * The dropdown this replaces could only ever show one list, which made "what am I watching, and
 * what have I saved for later" — the question the page exists to answer — two visits and four taps.
 * Any number of them can be on screen together now, each as its own section below.
 *
 * The counts are of what the filters have left, not of the whole list, so a search that emptied a
 * section says so on the chip rather than after a viewer has opened it.
 */
@Composable
private fun LibraryViewChips(
    availableViews: List<LibraryView>,
    selectedViews: List<LibraryView>,
    viewCounts: Map<LibraryView, Int>,
    onToggle: (LibraryView) -> Unit,
    customChips: CustomListChips,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val row = @Composable {
        LazyRow(
            modifier = modifier.fillMaxWidth().focusGroup(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(availableViews, key = { it.name }) { view ->
                val selected = view in selectedViews
                val controlFocus7 = rememberFocusFill()
                FilterChip(
                    colors = controlFocus7.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = selected,
                    onClick = { onToggle(view) },
                    label = { Text("${view.chipLabel}  ${viewCounts[view] ?: 0}") },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.focusFill(controlFocus7, RoundedCornerShape(8.dp)),
                )
            }
            // The viewer's own lists, after the account's statuses and toggled the same way.
            items(customChips.chips, key = { "custom_${listKey(it.name)}" }) { chip ->
                val chipFocus = rememberFocusFill()
                FilterChip(
                    colors = chipFocus.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = chip.selected,
                    onClick = { customChips.onToggle(chip.name) },
                    label = { Text("${chip.name}  ${chip.count}") },
                    leadingIcon = if (chip.selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.focusFill(chipFocus, RoundedCornerShape(8.dp)),
                )
            }
            item(key = "custom_new") {
                val chipFocus = rememberFocusFill()
                FilterChip(
                    colors = chipFocus.chipColors(FilterChipDefaults.filterChipColors()),
                    selected = false,
                    onClick = customChips.onNew,
                    label = { Text("New list") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.focusFill(chipFocus, RoundedCornerShape(8.dp)),
                )
            }
        }
    }
    // The page around it centres its focused row on a television, and a row inside inherits that
    // sideways unless told otherwise: Home's rail scrolling instead, as the poster rows have.
    if (LocalAppDeviceProfile.current.isTv) TvRailScrollSpec(row) else row()
}

/** The custom-list half of the chip row: one chip per list, and the one that starts a new list. */
internal class CustomListChips(
    val chips: List<CustomListChip>,
    val onToggle: (String) -> Unit,
    val onNew: () -> Unit,
)

internal data class CustomListChip(val name: String, val count: Int, val selected: Boolean)

/**
 * One list of the library: its heading, and its titles as either a carousel or a grid.
 *
 * A `LazyListScope` extension rather than a composable because an expanded section's poster rows
 * have to be items of the page's own list. Wrapping them in one item would measure every poster of
 * every shown list on the first frame, and a nested vertical scroller inside a LazyColumn cannot
 * measure at all.
 */
private fun LazyListScope.libraryListSection(
    key: String,
    title: String,
    cards: List<SavedAnimeCardData>,
    subtitle: String,
    emptyText: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    columns: Int,
    pagePadding: Dp,
    onAnimeClick: (Int) -> Unit,
    onListChanged: () -> Unit,
    onManage: (() -> Unit)? = null,
) {
    item(key = "lib_title_$key") {
        ProfileSectionTitle(
            title = title,
            subtitle = subtitle,
            isExpanded = isExpanded,
            onToggleExpand = onToggleExpand,
            onManage = onManage,
        )
    }
    if (cards.isEmpty()) {
        item(key = "lib_empty_$key") { EmptyPanel(emptyText) }
        return
    }
    if (isExpanded) {
        // Keys carry the list they belong to: sections share one LazyColumn, and a repeated key
        // across two of them is a crash during measure rather than a mis-drawn row.
        val cardRows = cards.chunked(columns)
        itemsIndexed(cardRows, key = { rowIndex, _ -> "lib_${key}_row_$rowIndex" }) { _, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = pagePadding, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (entry in row) {
                    SavedAnimeCard(
                        entry = entry,
                        onAnimeClick = onAnimeClick,
                        onListChanged = onListChanged,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        item(key = "lib_carousel_$key") {
            LibraryCarousel(cards, itemKey = { it.id }) { entry ->
                SavedAnimeCard(entry, onAnimeClick, onListChanged)
            }
        }
    }
}

/**
 * A row of poster cards, kept at its first title until the viewer moves it themselves.
 *
 * The homing is not decoration. A keyed lazy row follows the key of whatever was first in it, and
 * this page's content arrives in two waves — the titles this device has saved, then a second later
 * the account's, carrying their own `updatedAt` for the sort to order by. The title the row was
 * anchored to therefore slides down the order and the row scrolls after it; asked to scroll further
 * than the content allows, it stops at the far end. Measured on a phone showing a three-poster
 * watchlist: the row sat at 158px of a 158px scroll range, so the page opened on its first poster
 * cut in half, and a flick corrected it for good.
 *
 * Two things are left alone. A row the viewer has dragged is theirs from then on, refresh or not.
 * And a row holding D-pad focus is never re-homed: scrolling a remote's own row out from under it
 * costs the focused card, and with it the focus, which is a worse bug than the one being fixed.
 */
@Composable
private fun <T> LibraryCarousel(
    entries: List<T>,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    card: @Composable (T) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    val state = rememberLazyListState()
    // Saved, so it lasts exactly as long as the scroll position it guards: both survive the row
    // scrolling out of the page and back, which disposes this composable.
    var draggedByViewer by rememberSaveable { mutableStateOf(false) }
    var holdsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) draggedByViewer = true
        }
    }
    // Only a change at the head can move the anchor; titles arriving behind it move nothing.
    val headKey = entries.firstOrNull()?.let(itemKey)
    LaunchedEffect(headKey) {
        if (!draggedByViewer && !holdsFocus) state.scrollToItem(0)
    }
    val row = @Composable {
        LazyRow(
            state = state,
            modifier = modifier.focusGroup().onFocusChanged { holdsFocus = it.hasFocus },
            // On a television, Home's vertical room too: the focus halo is drawn outside the poster
            // and the row clips to its bounds, so without it the ring lost its top and bottom.
            contentPadding = PaddingValues(horizontal = device.pagePadding, vertical = if (device.isTv) 10.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
        ) {
            items(entries, key = itemKey) { entry -> card(entry) }
        }
    }
    // Home's rail scrolling on a television: just far enough to show a card, keeping the page
    // padding at either end.
    if (device.isTv) TvRailScrollSpec(row) else row()
}


/** The account, as small as it can be and still say whose library this is. */
@Composable
private fun LibraryProfilePill(
    state: UiState<AniListProfile>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewer = (state as? UiState.Success)?.data?.viewer
    val fillFocus1 = rememberFocusFill()
    Surface(
        modifier = modifier
            .focusFill(fillFocus1, RoundedCornerShape(50))
            .clickable(onClickLabel = "Open account", onClick = onClick),
        shape = RoundedCornerShape(50),
        color = fillFocus1.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus1.content(LocalContentColor.current),
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountAvatar(viewer?.avatar?.large, Modifier.size(26.dp), shape = CircleShape)
            Text(
                // Before the profile lands there is still an account here, and "Account" is a
                // truthful placeholder where a blank pill would look like a failure.
                viewer?.name ?: "Account",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
                color = fillFocus1.content(LocalContentColor.current),
            )
        }
    }
}

/**
 * Everything the old banner card held, moved behind the pill: the cover art, the three totals, and
 * the account actions. A phone's; a television opens [TvProfilePage] instead.
 *
 * None of it is per-visit information — a watch-time total is interesting once a month, not once a
 * scroll — so it earns a tap rather than the top of every visit to the page.
 */
@Composable
private fun ProfileSheet(
    state: UiState<AniListProfile>?,
    continueWatching: List<HistoryEntry>,
    onResume: (HistoryEntry) -> Unit,
    onPair: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val success = state as? UiState.Success
    val viewer = success?.data?.viewer
    val service = success?.data?.service ?: AccountService.active
    val serviceLabel = service?.label ?: "your account"
    var confirmingLogout by remember { mutableStateOf(false) }
    var dashboardTab by rememberSaveable { mutableStateOf(DashboardTab.OVERVIEW) }
    val drawerScroll = rememberScrollState()
    // Asked first: signing out drops this device's copy of the account's lists and progress, and a
    // stray tap on the drawer's last row should not cost someone their sign-in. Shown instead of the
    // drawer rather than over it, so a television's Back key has one window to answer to.
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
        return
    }
    // Cancelling logout returns to the same tab and scroll position.
    MidnightDialogHost(
        onDismiss = onDismiss,
        bodyScrollState = drawerScroll,
    ) {
        // In the body rather than the dialog's pinned header, so the banner scrolls away with the
        // rest instead of holding a third of the drawer while the profile slides underneath it.
        AccountHeader(
            viewer = viewer,
            service = service,
            // An account with no banner of its own borrows the cover of what was watched last.
            fallbackBanner = continueWatching.firstOrNull()?.cover,
            onSync = onSync,
            onDismiss = onDismiss,
        )
        Spacer(Modifier.height(18.dp))
        when (state) {
            is UiState.Success -> {
                val insights = remember(state.data) { accountInsights(state.data) }
                AccountDashboard(
                    insights, state.data, continueWatching, onResume,
                    onSync = onSync,
                    onPair = onPair,
                    onLogout = { confirmingLogout = true },
                    tab = dashboardTab,
                    onTabChange = { dashboardTab = it },
                )
            }
            is UiState.Error -> {
                Text("Your lists could not be loaded", fontWeight = FontWeight.Bold)
                Text(
                    failureAnnotated(state.failure, messageColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                // Still reachable when the lists will not load — that is when signing out and back
                // in is most likely to be the fix.
                AccountActions(service, onSync, onPair, onLogout = { confirmingLogout = true })
            }
            else -> Text(
                "Syncing your profile…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A television's filters as one slim band under the hero: the lists as chips, then format, airing
 * status, sort and the title filter on a single row.
 *
 * This replaces a bordered panel of full-width dropdowns stacked four deep beside the account card —
 * with it, the two boxes filled the screen above the first poster. A television keeps sort and
 * airing as pills of their own rather than folding them behind one icon as a phone does, because it
 * has the width and a remote should not need a menu to see them.
 */
@Composable
private fun TvLibraryToolbar(
    availableViews: List<LibraryView>,
    selectedViews: List<LibraryView>,
    viewCounts: Map<LibraryView, Int>,
    onToggleView: (LibraryView) -> Unit,
    selectedFormat: String?,
    onFormatChange: (String?) -> Unit,
    selectedAiring: String?,
    onAiringChange: (String?) -> Unit,
    selectedSort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    titleFilter: String,
    onTitleFilterChange: (String) -> Unit,
    resultCount: Int,
    customChips: CustomListChips,
    chipsFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val pagePadding = LocalAppDeviceProfile.current.pagePadding
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Edge to edge with the page padding as content padding, as Home's rails are: the focus
        // ring is drawn a few dp outside a chip, and a row inset by the padding clipped it at the
        // row's ends.
        LibraryViewChips(
            availableViews = availableViews,
            selectedViews = selectedViews,
            viewCounts = viewCounts,
            onToggle = onToggleView,
            customChips = customChips,
            contentPadding = PaddingValues(horizontal = pagePadding),
            modifier = Modifier.focusRequester(chipsFocus),
        )
        // Up from the filters has to be told where the chips are: measured on the TV box, the
        // search went straight past them to the hero's Profile button, and Down did the same.
        PageFilterRow(
            Modifier
                .padding(horizontal = pagePadding)
                .focusProperties { up = chipsFocus },
        ) {
            TvGlassDropdown(
                label = formatOptions.first { it.value == selectedFormat }.label,
                options = formatOptions,
                onSelect = onFormatChange,
                modifier = Modifier.width(150.dp),
            )
            TvGlassDropdown(
                label = airingOptions.first { it.value == selectedAiring }.label,
                options = airingOptions,
                onSelect = onAiringChange,
                modifier = Modifier.width(196.dp),
            )
            TvGlassDropdown(
                label = sortOptions.first { it.value == selectedSort.name }.label,
                options = sortOptions,
                onSelect = { value -> LibrarySort.entries.firstOrNull { it.name == value }?.let(onSortChange) },
                modifier = Modifier.width(210.dp),
            )
            // Last in the row: the native field keeps Left and Right for its caret while it holds
            // text, so nothing past it would be reachable.
            TvNativeTextField(
                value = titleFilter,
                onValueChange = onTitleFilterChange,
                hint = "Filter by title",
                modifier = Modifier.weight(1f),
                minHeight = 40.dp,
                shape = RoundedCornerShape(50),
            )
        }
        // Said once here rather than under each of the sections the chips above can open.
        Text(
            "$resultCount anime · Hold Select on a title to move it on your anime list",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = pagePadding),
        )
    }
}

@Composable
private fun ProfileSectionTitle(
    title: String,
    subtitle: String,
    isExpanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onManage != null) {
            val manageFocus = rememberFocusFill()
            TextButton(
                colors = manageFocus.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = onManage,
                modifier = Modifier.focusFill(manageFocus, RoundedCornerShape(50)),
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
        if (onToggleExpand != null && isExpanded != null) {
            val controlFocus10 = rememberFocusFill()
            TextButton(
                colors = controlFocus10.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = onToggleExpand,
                modifier = Modifier.focusFill(controlFocus10, RoundedCornerShape(50)),
            ) {
                Icon(
                    if (isExpanded) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isExpanded) "Carousel" else "Expand")
            }
        }
    }
}

@Composable
private fun EpisodeDownloadCard(
    episode: OfflineEpisode,
    historyEntry: HistoryEntry?,
    exportStatus: EpisodeExportStatus?,
    onPlay: () -> Unit,
    onPauseToggle: () -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val download = episode.download
    val metadata = episode.metadata
    val episodeNumber = metadata.episodeNumber.trim().toDoubleOrNull()
    val watchedFraction = episodeNumber?.let { episodeWatchFraction(historyEntry, it) } ?: 0f
    val isWatched = watchedFraction >= 0.999f
    val savedPositionMs = historyEntry
        ?.takeIf { episodeNumber == it.episodeNumber }
        ?.positionMs
        ?.coerceAtLeast(0L)
        ?: 0L
    val hasResumePosition = !isWatched && savedPositionMs > 0L
    val playbackLabel = when {
        isWatched -> "Watched"
        hasResumePosition -> "Resume at ${formatPlaybackPosition(savedPositionMs)}"
        else -> null
    }
    val subtitleStatus = download?.offlineSubtitleStatusLabel
    val playActionLabel = when {
        isWatched -> "Replay"
        hasResumePosition -> "Resume"
        else -> "Play"
    }
    // The corner tick already says "saved"; the overlay is only for work still in flight.
    val coverState = remember(download, exportStatus, episode.exported) {
        episodeDownloadBadges(
            downloads = listOfNotNull(download),
            exportStatuses = listOfNotNull(exportStatus).associateBy { it.downloadId },
            exported = listOfNotNull(episode.exported),
        )[episode.id]?.takeIf { it.isBusy }
    }
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    val cardModifier = if (modifier != Modifier) {
        modifier.focusHighlight(shape)
    } else {
        Modifier.width(if (device.isTv) 320.dp else 270.dp).focusHighlight(shape)
    }
    // The explicit Play button stays for discoverability, but the card itself is the primary
    // target: tapping anywhere on a finished episode opens it, like every other card in the app.
    Surface(
        modifier = cardModifier.clickable(
            enabled = episode.isPlayable,
            onClickLabel = "$playActionLabel episode ${metadata.episodeNumber}",
            onClick = onPlay,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = metadata.artworkUrl,
                    contentDescription = metadata.seriesTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                )
                Text(
                    "EP ${metadata.episodeNumber} · ${metadata.category.uppercase()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
                if (episode.isPlayable) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
                if (isWatched) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = if (metadata.filler) 38.dp else 10.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    ) {
                        Text(
                            "WATCHED",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (metadata.filler) {
                    EpisodeFillerBadge(
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                }
                // Same cue as the watch and detail lists, so an in-flight episode looks identical
                // wherever the viewer happens to be looking at it.
                DownloadCoverBadge(
                    state = coverState,
                    modifier = Modifier.matchParentSize(),
                )
                WatchProgressBar(
                    fraction = watchedFraction,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    metadata.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metadata.episodeTitle?.takeIf(String::isNotBlank)
                        ?: "Episode ${metadata.episodeNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.isDownloading) {
                    LinearProgressIndicator(
                        progress = { (download?.percent ?: 0f) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    listOfNotNull(
                        playbackLabel,
                        // An episode that only exists as an MP4 has no Media3 download state left
                        // to report, so say where it lives instead.
                        download?.state?.displayLabel(download.percent)
                            ?: DownloadLocationFiles.describeLocation(
                                context,
                                episode.exported?.uri?.let(Uri::parse),
                            ),
                        subtitleStatus,
                        episode.sizeBytes.takeIf { it > 0 }?.let(::formatDownloadBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (
                        download?.state == EpisodeDownloadState.FAILED ||
                        download?.hasMissingOfflineSubtitles == true
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // A completed export is already spelled out by the status line above once the
                // library copy is gone; only say it twice while both copies exist.
                if (exportStatus != null && !(exportStatus.state == EpisodeExportState.COMPLETED && download == null)) {
                    if (exportStatus.state == EpisodeExportState.RUNNING) {
                        LinearProgressIndicator(
                            progress = { (exportStatus.percent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when (exportStatus.state) {
                            EpisodeExportState.PENDING -> "Queued for Downloads"
                            EpisodeExportState.RUNNING -> exportStatus.percent
                                ?.let { "Building MP4 · $it%" }
                                ?: "Building MP4…"
                            EpisodeExportState.COMPLETED -> DownloadLocationFiles.describeSaved(
                                context,
                                episode.exported?.uri?.let(Uri::parse),
                            )
                            EpisodeExportState.FAILED -> exportStatus.failure?.rowLabel()
                                ?: AnililiError.EXPORT_FAILED.asFailure("this device").rowLabel()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (exportStatus.state == EpisodeExportState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    download?.takeIf { it.canPause || it.canResume }?.let { controlledDownload ->
                        val extraFocus6 = rememberFocusFill()
                        IconButton(
                            colors = extraFocus6.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onPauseToggle, modifier = Modifier.focusFill(extraFocus6, CircleShape),) {
                            Icon(
                                if (controlledDownload.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (controlledDownload.isPaused) {
                                    "Resume download"
                                } else {
                                    "Pause download"
                                },
                            )
                        }
                    }
                    // Icons without labels: these cards sit two to a row on a phone, and three
                    // labelled buttons do not fit that width — Compose kept the row intact by
                    // wrapping "Replay" one letter per line down the card. The labels ride on the
                    // content descriptions instead, which is also what a screen reader reads out.
                    if (episode.isPlayable) {
                        // Offered per episode rather than only at download time, so one already
                        // sitting in the library can be lifted out to Downloads later.
                        if (
                            EpisodeExport.isSupported &&
                            download?.isComplete == true &&
                            !episode.isInDownloadsFolder
                        ) {
                            val extraFocus7 = rememberFocusFill()
                            IconButton(
                                colors = extraFocus7.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                                onClick = onExport,
                                enabled = exportStatus == null ||
                                    exportStatus.state == EpisodeExportState.FAILED, modifier = Modifier.focusFill(extraFocus7, CircleShape),) {
                                Icon(Icons.Default.SaveAlt, contentDescription = "Save as MP4 in Downloads")
                            }
                        }
                        val extraFocus8 = rememberFocusFill()
                        IconButton(
                            colors = extraFocus8.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onPlay, modifier = Modifier.focusFill(extraFocus8, CircleShape),) {
                            Icon(Icons.Default.PlayArrow, contentDescription = playActionLabel)
                        }
                    } else {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val extraFocus9 = rememberFocusFill()
                    IconButton(
                        colors = extraFocus9.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = onRemove, modifier = Modifier.focusFill(extraFocus9, CircleShape),) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove this episode")
                    }
                }
            }
        }
    }
}

/**
 * One anime's offline episodes as a single card. The flat per-episode list this replaced was
 * unmanageable for long-runners (180+ episodes of one show); the card opens the series view,
 * which is where individual episodes are played, paused, exported, or removed.
 */
@Composable
private fun SeriesDownloadCard(
    series: OfflineSeries,
    historyEntry: HistoryEntry?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    // The dub marker follows the same setting here as on a poster: it is the same index answering
    // the same question, and a viewer who switched it off did not mean "except on the shelf".
    val posterElements = rememberPosterElements()
    val watchedOfflineCount = series.episodes.count { episode ->
        episode.metadata.episodeNumber.trim().toDoubleOrNull()
            ?.let { episodeWatchFraction(historyEntry, it) >= 0.999f } == true
    }
    val shape = RoundedCornerShape(12.dp)
    val cardModifier = (if (modifier != Modifier) {
        modifier
    } else {
        Modifier.width(if (device.isTv) 320.dp else 270.dp)
    })
        .focusHighlight(shape)
        .clickable(onClickLabel = "Open ${series.title} downloads", onClick = onClick)
    Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = series.artworkUrl,
                    contentDescription = series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                )
                Text(
                    "${series.episodes.size} EP",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
                if (posterElements.shows(PosterElement.DUB) && DubIndex.hasDub(series.anilistId)) {
                    // Top-start here: the episode count already owns bottom-start on this card.
                    DubBadge(Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                if (series.activeCount > 0) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download in progress",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
            }
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    series.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        "${series.playableCount} of ${series.episodes.size} offline",
                        "$watchedOfflineCount watched".takeIf { watchedOfflineCount > 0 },
                        formatDownloadBytes(series.totalBytes).takeIf { series.totalBytes > 0 },
                    ).joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Removes an episode from the offline library completely, whichever copies of it exist — the
 * cached segments, the MP4 in Downloads, or both. Deleting only half of it would leave a card
 * behind that still looks downloaded.
 */
private fun removeOfflineEpisode(context: android.content.Context, episode: OfflineEpisode) {
    if (episode.download != null) EpisodeDownloads.remove(context, episode.id)
    if (episode.exported != null) EpisodeExport.deleteExported(context, episode.id)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    onResume: (HistoryEntry) -> Unit,
    onOpenDetails: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val poster = rememberPosterElements()
    var actionsVisible by remember { mutableStateOf(false) }
    if (actionsVisible) {
        ContinueWatchingActionsDialog(
            entry = entry,
            onDismiss = { actionsVisible = false },
            onOpenDetails = { onOpenDetails(entry) },
        )
    }
    if (device.isTv) {
        TvPosterCard(
            title = entry.title,
            details = entry.continueWatchingCaption(),
            onClick = { onResume(entry) },
            onLongClick = { actionsVisible = true },
            clickLabel = "Resume ${entry.title}",
            longClickLabel = "Manage Continue Watching",
            modifier = if (modifier != Modifier) modifier else Modifier.width(TvMediaCardWidth),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(entry.anilistId)) {
                DubBadge(Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 11.dp))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .4f))) {
                Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        return
    }
    // combinedClickable's long press is pointer-only; the remote gets its own.
    val pressModifier = Modifier
        .dpadClickable(
            onClick = { onResume(entry) },
            onLongClick = { actionsVisible = true },
        )
        .combinedClickable(
            onClickLabel = "Resume ${entry.title}",
            onLongClickLabel = "Manage Continue Watching",
            onClick = { onResume(entry) },
            onLongClick = { actionsVisible = true },
        )
    // CaptionedCardShape: the caption sits on the bottom edge, where a rounded corner clips it.
    val cardModifier = if (modifier != Modifier) {
        modifier.focusHighlight(CaptionedCardShape).then(pressModifier)
    } else {
        Modifier.width(device.posterWidth).focusHighlight(CaptionedCardShape).then(pressModifier)
    }
    Column(cardModifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.align(Alignment.Center))
            if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(entry.anilistId)) {
                // Bottom-start like every other card, lifted clear of the progress bar that owns
                // the last four pixels of this one.
                DubBadge(Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 9.dp))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .4f))) {
                Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        if (poster.shows(PosterElement.TITLE)) {
            Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        if (poster.shows(PosterElement.DETAILS)) {
            Text(
                entry.continueWatchingCaption(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (poster.shows(PosterElement.TITLE)) Modifier else Modifier.padding(top = 5.dp),
            )
        }
    }
}

private fun EpisodeDownloadState.displayLabel(percent: Float?): String = when (this) {
    EpisodeDownloadState.QUEUED -> "Queued"
    EpisodeDownloadState.DOWNLOADING -> percent?.let { "Downloading ${it.toInt()}%" } ?: "Downloading"
    EpisodeDownloadState.COMPLETED -> "Available offline"
    EpisodeDownloadState.FAILED -> AnililiError.DOWNLOAD_FAILED.asFailure().rowLabel()
    EpisodeDownloadState.REMOVING -> "Removing"
    EpisodeDownloadState.RESTARTING -> "Restarting"
    EpisodeDownloadState.STOPPED -> percent?.let { "Paused at ${it.toInt()}%" } ?: "Paused"
}

private fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}

/**
 * A television poster the way Home draws one ([com.anilili.ui.home.TvMediaCard]): the halo round the
 * artwork alone, a slight lift, Home's corner and caption. My List used to wear the phone's card on
 * a television — a rim round poster and caption together and a bigger jump — so the same title
 * looked and moved differently on the two pages.
 *
 * No indication on the click, as on Home: the default one paints a grey plate over the caption.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvPosterCard(
    title: String,
    details: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    clickLabel: String,
    longClickLabel: String,
    modifier: Modifier = Modifier,
    artwork: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val lowCostTvEffects = LocalAppDeviceProfile.current.useLowCostTvEffects
    val scale = if (lowCostTvEffects) {
        1f
    } else {
        val animatedScale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-poster-card-scale")
        animatedScale
    }
    val poster = rememberPosterElements()
    val shape = TvCardCornerShape
    Column(
        modifier
            .then(if (lowCostTvEffects) Modifier else Modifier.zIndex(if (focused) 1f else 0f).scale(scale))
            .onFocusChanged { focused = it.isFocused }
            // combinedClickable's long press is pointer-only; the remote gets its own.
            .dpadClickable(onClick = onClick, onLongClick = onLongClick)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = clickLabel,
                onLongClickLabel = longClickLabel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(bottom = 12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TV_MEDIA_CARD_ASPECT_RATIO)
                .focusHalo(focused, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, Color.White.copy(.08f), shape),
        ) {
            artwork()
        }
        if (poster.shows(PosterElement.TITLE)) {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
                minLines = 2,
                lineHeight = 18.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (poster.shows(PosterElement.DETAILS) && details.isNotBlank()) {
            Text(
                details,
                color = Color.White.copy(.52f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (poster.shows(PosterElement.TITLE)) 2.dp else 8.dp),
            )
        }
    }
}

/** The viewer's own score, set as AniList's is but on the ten-point scale they gave it on. */
@Composable
private fun UserScoreTag(score: Double, modifier: Modifier = Modifier) {
    val figure = String.format(Locale.US, "%.1f", score)
    ScoreTag(figure, "Your score $figure", modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedAnimeCard(
    entry: SavedAnimeCardData,
    onAnimeClick: (Int) -> Unit,
    onListChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val context = LocalContext.current
    val poster = rememberPosterElements()
    val scope = rememberCoroutineScope()
    val service = AccountService.active
    var actionsVisible by remember { mutableStateOf(false) }
    var confirmingDelete by remember(entry.id) { mutableStateOf(false) }
    var deleting by remember(entry.id) { mutableStateOf(false) }
    // Recomputed whenever the card's data changes, which is every account refresh — near enough to
    // live for a badge whose subject only moves once a week.
    val newEpisodes = remember(entry) { newEpisodeCount(entry, System.currentTimeMillis() / 1000L) }
    if (actionsVisible) {
        AnimeListActionsDialog(
            anilistId = entry.id,
            title = entry.title,
            cover = entry.cover,
            metadata = entry.progressLabel(),
            onDismiss = { actionsVisible = false },
            kicker = "My library",
            onMoved = onListChanged,
        )
    }
    if (confirmingDelete && service != null) {
        MidnightConfirmDialog(
            kicker = "Remove from ${service.label}",
            title = "Delete this from your list?",
            body = "${entry.title} comes off your ${service.label} list completely — its status, " +
                "episode count and score with it, and there is no undo on either service. It " +
                "leaves this device's watchlist and Continue Watching too. Downloaded episodes stay.",
            confirmText = "Delete",
            confirmEnabled = !deleting,
            onConfirm = {
                confirmingDelete = false
                deleting = true
                scope.launch {
                    // NonCancellable because this scope dies with the card, and the card is a row
                    // of a LazyColumn: scrolling the poster off screen while the mutation is in
                    // flight would abandon it half-done — the entry gone from AniList, the device
                    // still showing it — and the refresh that fixes the grid is in here too.
                    withContext(NonCancellable) {
                        deleteAnimeListEntry(entry.id, from = "my_library_card")
                            .onSuccess { onListChanged() }
                            .onFailure { error ->
                                // A poster has nowhere to put a sentence and the card stays exactly
                                // where it was, which on its own reads as a tap that did not
                                // register. The long-press menu shows this inline; here it has to
                                // be a toast.
                                val failure = error.toFailure(
                                    AnililiError.ACCOUNT_LIST_ENTRY_DELETE_FAILED,
                                    service.label,
                                    attributes = mapOf(
                                        "from" to "my_library_card",
                                        "service" to service.name,
                                        "anilistId" to entry.id,
                                    ),
                                )
                                Toast.makeText(context, failure.withCode(), Toast.LENGTH_LONG).show()
                            }
                    }
                    deleting = false
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
    if (device.isTv) {
        TvPosterCard(
            title = entry.title,
            details = listOfNotNull(entry.status?.toDisplayLabel(), entry.format?.replace('_', ' ')).joinToString("  •  "),
            onClick = { onAnimeClick(entry.id) },
            onLongClick = { actionsVisible = true },
            clickLabel = "Open ${entry.title}",
            longClickLabel = "Move ${entry.title} on your anime list",
            modifier = if (modifier != Modifier) modifier else Modifier.width(TvMediaCardWidth),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            entry.progress?.takeIf { poster.shows(PosterElement.PROGRESS) }?.let { progress ->
                CornerBadge(
                    text = "$progress/${entry.totalEpisodes ?: "?"}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart).padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                newEpisodes?.takeIf { poster.shows(PosterElement.NEW_EPISODES) }?.let { NewEpisodesBadge(it) }
                if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(entry.id)) DubBadge()
            }
            // Bottom-right, not the top-right Home gives the score: progress owns top-right on a
            // library card. The viewer's own score wins where there is one.
            if (poster.shows(PosterElement.SCORE)) {
                val corner = Modifier.align(Alignment.BottomEnd).padding(7.dp)
                when {
                    entry.userScore != null -> UserScoreTag(entry.userScore, corner)
                    entry.averageScore != null && entry.averageScore > 0 -> RatingBadge(entry.averageScore, corner)
                }
            }
        }
        return
    }
    // combinedClickable's long press is pointer-only; the remote gets its own.
    val pressModifier = Modifier
        .dpadClickable(
            onClick = { onAnimeClick(entry.id) },
            onLongClick = { actionsVisible = true },
        )
        .combinedClickable(
            onClickLabel = "Open ${entry.title}",
            onLongClickLabel = "Move ${entry.title} on your anime list",
            onClick = { onAnimeClick(entry.id) },
            onLongClick = { actionsVisible = true },
        )
    // CaptionedCardShape: the caption sits on the bottom edge, where a rounded corner clips it.
    val cardModifier = if (modifier != Modifier) {
        modifier.focusHighlight(CaptionedCardShape).then(pressModifier)
    } else {
        Modifier.width(device.posterWidth).focusHighlight(CaptionedCardShape).then(pressModifier)
    }
    Column(cardModifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            when {
                !poster.shows(PosterElement.SCORE) -> Unit
                // Top-left here, not the top-right the score takes on a catalogue poster: progress
                // owns top-right on a library card, and the bottom-right is the delete disc.
                entry.userScore != null -> UserScoreTag(entry.userScore, Modifier.align(Alignment.TopStart).padding(5.dp))
                entry.averageScore != null -> RatingBadge(entry.averageScore, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            entry.progress?.takeIf { poster.shows(PosterElement.PROGRESS) }?.let { progress ->
                CornerBadge(
                    text = "$progress/${entry.totalEpisodes ?: "?"}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                )
            }
            // Stacked rather than set in opposite corners, which is what the rest of the poster
            // does: a phone lays this grid out three columns wide, and "99+ NEW" facing "DUB"
            // across a hundred points of poster meets in the middle.
            Column(
                Modifier.align(Alignment.BottomStart).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                newEpisodes?.takeIf { poster.shows(PosterElement.NEW_EPISODES) }
                    ?.let { NewEpisodesBadge(it) }
                if (poster.shows(PosterElement.DUB) && DubIndex.hasDub(entry.id)) DubBadge()
            }
            // Bottom-end, not the top-right this glyph usually wears: every other corner of this
            // poster is already spoken for — score, progress, and the new/dub stack — and a disc
            // is narrow enough to sit opposite that stack without the collision two text badges
            // would have. Only for a title the account actually holds, so it cannot offer to
            // delete a purely local save, and never on television, where a D-pad has no way to
            // reach inside a card and the long-press menu is the route.
            if (service != null && entry.status != null && !device.isTv) {
                CardCornerAction(
                    icon = Icons.Default.Close,
                    contentDescription = "Delete ${entry.title} from ${service.label}",
                    onClick = { if (!deleting) confirmingDelete = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                )
            }
        }
        if (poster.shows(PosterElement.TITLE)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (poster.shows(PosterElement.DETAILS)) {
            Text(
                listOfNotNull(entry.status?.toDisplayLabel(), entry.format?.replace('_', ' ')).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (poster.shows(PosterElement.TITLE)) Modifier else Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * List states a "new episodes" badge belongs on: a series the viewer is part-way through.
 *
 * Completed and Dropped are left out because the viewer has already said what they think of the
 * title, and Planning because a show never started has no "since you last watched" to measure from —
 * every airing entry on the watchlist would wear a badge, which is the same as none of them wearing
 * one.
 */
private val NEW_EPISODE_LIST_STATUSES = setOf("CURRENT", "REPEATING", "PAUSED")

/**
 * How many broadcast episodes this title is ahead of the viewer, or null for no badge.
 *
 * "The list entry has not been touched since the last episode aired" is the obvious way to phrase
 * this and a lossy one: it cannot say *how many* episodes are waiting, and it lights up for a title
 * watched the day its episode landed but scored a week later. Counting from progress is exact, and
 * it clears itself the moment the account catches up — there is nothing to mark as seen.
 *
 * Only [SavedAnimeCardData.nextEpisodeNumber] is consulted for whether a series is still running:
 * AniList schedules an episode for exactly the titles that have one coming, so a separate check on
 * the airing status would only repeat it. An unaired first episode falls out on its own, since
 * nothing has broadcast yet.
 */
internal fun newEpisodeCount(entry: SavedAnimeCardData, nowSec: Long): Int? {
    val watched = entry.progress ?: 0
    val status = entry.status?.uppercase()
    if (status == null) {
        // Saved here but on none of the account's lists. This device's own watch history is then
        // the only thing that knows the viewer started it, and it is what fills `progress`.
        if (watched <= 0) return null
    } else if (status !in NEW_EPISODE_LIST_STATUSES) {
        return null
    }
    val aired = airedEpisodeCount(entry, nowSec) ?: return null
    return (aired - watched).takeIf { it > 0 }
}

/**
 * Episodes broadcast so far, counted back from the next scheduled one.
 *
 * The airing time is consulted as well because the media behind a MyAnimeList list is served from a
 * day-long cache: once the moment has passed, that episode is out whatever the snapshot still calls
 * "next". [AIRED_BUFFER_SEC] holds the badge back until the streaming sites have plausibly picked
 * the episode up, so it never promises one that cannot be played yet.
 */
private fun airedEpisodeCount(entry: SavedAnimeCardData, nowSec: Long): Int? {
    val next = entry.nextEpisodeNumber?.takeIf { it > 0 } ?: return null
    val airingAt = entry.nextEpisodeAiringAt?.takeIf { it > 0 }
    val aired = if (airingAt != null && nowSec >= airingAt + AIRED_BUFFER_SEC) next else next - 1
    return aired.takeIf { it > 0 }
}

/** Broadcast to watchable — the same hour and a half Home allows its recently-aired rail. */
private const val AIRED_BUFFER_SEC = 90L * 60L

/** Where this title stands, for the long-press menu's header line. */
private fun SavedAnimeCardData.progressLabel(): String? = when {
    progress != null && progress > 0 -> "Episode $progress of ${totalEpisodes ?: "?"}"
    totalEpisodes != null -> "$totalEpisodes episodes"
    else -> format?.replace('_', ' ')
}


@Composable
private fun EmptyPanel(text: String) {
    val device = LocalAppDeviceProfile.current
    Panel(Modifier.padding(horizontal = device.pagePadding)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

internal fun joinedLabel(createdAt: Long?): String? {
    if (createdAt == null || createdAt <= 0) return null
    return runCatching {
        "Joined " + Instant.ofEpochSecond(createdAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM yyyy"))
    }.getOrNull()
}

internal fun buildCombinedWatchlist(
    profile: AniListProfile?,
    local: List<WatchlistEntry>,
    history: List<HistoryEntry>,
): List<SavedAnimeCardData> {
    val aniListPlanning = profile?.planning.orEmpty().distinctBy { it.media?.id }
    val byMediaId = aniListPlanning.mapNotNull { entry -> entry.media?.id?.let { it to entry } }.toMap()
    val historyById = history.associateBy { it.anilistId }

    return buildList {
        // distinctBy, because a repeated id here is fatal rather than untidy: these become
        // LazyColumn keys, and Compose throws IllegalArgumentException("Key … was already used")
        // during measure, from a stack with no app frame in it. Reported from a phone whose saved
        // list held one anime twice. The AniList half was already deduped; the local half was not.
        local.distinctBy(WatchlistEntry::anilistId).forEach { saved ->
            val aniListEntry = byMediaId[saved.anilistId]
            val media = aniListEntry?.media
            add(
                SavedAnimeCardData(
                    id = saved.anilistId,
                    title = media?.title?.preferred ?: saved.title,
                    cover = media?.coverImage?.best ?: saved.cover,
                    format = media?.format ?: saved.format,
                    airingStatus = media?.status,
                    status = aniListEntry?.status,
                    userScore = aniListEntry?.score?.takeIf { it > 0 },
                    averageScore = media?.averageScore ?: saved.averageScore,
                    progress = aniListEntry?.progress ?: historyById[saved.anilistId]?.episodeNumber?.toInt(),
                    totalEpisodes = media?.episodes,
                    updatedAt = aniListEntry?.updatedAt?.takeIf { it > 0 } ?: (saved.addedAt / 1000L),
                    nextEpisodeNumber = media?.nextAiringEpisode?.episode,
                    nextEpisodeAiringAt = media?.nextAiringEpisode?.airingAt,
                ),
            )
        }
        aniListPlanning.forEach { aniListEntry ->
            val media = aniListEntry.media ?: return@forEach
            if (local.any { it.anilistId == media.id }) return@forEach
            add(
                SavedAnimeCardData(
                    id = media.id,
                    title = media.title.preferred,
                    cover = media.coverImage.best,
                    format = media.format,
                    airingStatus = media.status,
                    status = aniListEntry.status,
                    userScore = aniListEntry.score.takeIf { it > 0 },
                    averageScore = media.averageScore,
                    progress = aniListEntry.progress,
                    totalEpisodes = media.episodes,
                    updatedAt = aniListEntry.updatedAt,
                    nextEpisodeNumber = media.nextAiringEpisode?.episode,
                    nextEpisodeAiringAt = media.nextAiringEpisode?.airingAt,
                ),
            )
        }
    }.distinctBy(SavedAnimeCardData::id)
}

/**
 * One custom list's cards. The list holds what it needs to draw a title; where the account also
 * tracks it, the account's status, score and progress are shown the way every other section shows
 * them, so a title reads the same wherever it appears on the page.
 */
internal fun customListCards(
    list: CustomList,
    profile: AniListProfile?,
    history: List<HistoryEntry>,
): List<SavedAnimeCardData> {
    val tracked = listOfNotNull(
        profile?.watching, profile?.rewatching, profile?.planning,
        profile?.paused, profile?.completed, profile?.dropped,
    ).flatten().filter { it.media != null }.associateBy { it.media!!.id }
    val historyById = history.associateBy { it.anilistId }
    return list.entries.distinctBy { it.anilistId }.map { saved ->
        val entry = tracked[saved.anilistId]
        val media = entry?.media
        SavedAnimeCardData(
            id = saved.anilistId,
            title = media?.title?.preferred ?: saved.title,
            cover = media?.coverImage?.best ?: saved.cover,
            format = media?.format ?: saved.format,
            airingStatus = media?.status,
            status = entry?.status,
            userScore = entry?.score?.takeIf { it > 0 },
            averageScore = media?.averageScore ?: saved.averageScore,
            progress = entry?.progress ?: historyById[saved.anilistId]?.episodeNumber?.toInt(),
            totalEpisodes = media?.episodes,
            updatedAt = saved.addedAt / 1000L,
            nextEpisodeNumber = media?.nextAiringEpisode?.episode,
            nextEpisodeAiringAt = media?.nextAiringEpisode?.airingAt,
        )
    }
}

/** [key] added to the comma-joined [keys], or taken out when it is already there. */
internal fun toggledKeys(keys: String, key: String): String {
    val set = keys.split(',').filterTo(linkedSetOf()) { it.isNotBlank() }
    if (!set.add(key)) set.remove(key)
    return set.joinToString(",")
}

/**
 * The account's Completed list, then whatever was marked watched on this device that it does not
 * hold yet — every mark when signed out, and the ones still on their way to the account after a
 * sign-in.
 */
internal fun completedCards(profile: AniListProfile?, marked: List<WatchedEntry>): List<SavedAnimeCardData> {
    val account = aniListCards(profile?.completed.orEmpty())
    val held = account.mapTo(mutableSetOf(), SavedAnimeCardData::id)
    return account + marked.distinctBy(WatchedEntry::anilistId).filterNot { it.anilistId in held }.map { mark ->
        SavedAnimeCardData(
            id = mark.anilistId,
            title = mark.title,
            cover = mark.cover,
            format = mark.format,
            airingStatus = null,
            status = "COMPLETED",
            userScore = null,
            averageScore = mark.averageScore,
            progress = mark.episodes,
            totalEpisodes = mark.episodes,
            updatedAt = mark.watchedAt / 1000L,
        )
    }
}

private fun aniListCards(entries: List<MediaListEntry>): List<SavedAnimeCardData> =
    entries.mapNotNull { entry ->
        val media = entry.media ?: return@mapNotNull null
        SavedAnimeCardData(
            id = media.id,
            title = media.title.preferred,
            cover = media.coverImage.best,
            format = media.format,
            airingStatus = media.status,
            status = entry.status,
            userScore = entry.score.takeIf { it > 0 },
            averageScore = media.averageScore,
            progress = entry.progress,
            totalEpisodes = media.episodes,
            updatedAt = entry.updatedAt,
            nextEpisodeNumber = media.nextAiringEpisode?.episode,
            nextEpisodeAiringAt = media.nextAiringEpisode?.airingAt,
        )
    }.distinctBy { it.id }

/**
 * One list's cards, filtered and ordered the way its section shows them.
 *
 * Out of the composable so the page's ordering can be run in a test — [LibraryCarousel] homes a
 * carousel because this ordering moves under it between the two waves the library arrives in, and
 * that is a claim about this function.
 */
internal fun libraryCards(
    source: List<SavedAnimeCardData>,
    format: String?,
    airing: String?,
    titleFilter: String,
    sort: LibrarySort,
): List<SavedAnimeCardData> {
    val filtered = source.filter { entry ->
        (format == null || entry.format == format) &&
            (airing == null || entry.airingStatus == airing) &&
            (titleFilter.isBlank() || entry.title.contains(titleFilter, ignoreCase = true))
    }
    return when (sort) {
        LibrarySort.LAST_MODIFIED -> filtered.sortedByDescending { it.updatedAt }
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.SCORE -> filtered.sortedByDescending { it.userScore ?: 0.0 }
        LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress ?: 0 }
        LibrarySort.DEFAULT -> filtered
    }
}

/**
 * The lists a viewer sees before they have chosen any: what they are part-way through, and what
 * they have put aside for later. Those two are the page's whole question for most accounts, and
 * showing both by default is the difference this rail exists to make.
 */
internal fun defaultLibraryViewNames(): String =
    listOf(LibraryView.WATCHING, LibraryView.WATCHLIST).joinToString(",") { it.name }

/**
 * The chosen lists, read back out of the one string that holds them.
 *
 * A `Set` in `rememberSaveable` would need its own `Saver`; joined names need nothing, and the
 * parse is then the single place the selection's two rules live. Sections come out in enum order
 * however they were tapped, so toggling one never reshuffles the page — and a list this account
 * cannot offer is dropped rather than left as a heading over nothing.
 */
internal fun libraryViewsFrom(names: String, available: List<LibraryView>): List<LibraryView> {
    val chosen = names.split(',').filterTo(mutableSetOf()) { it.isNotBlank() }
    val views = available.filter { it.name in chosen }
    // Everything chosen is unavailable: the account's lists have not arrived yet, or the viewer
    // signed out with more than the watchlist on show. Falling back keeps a list on the page,
    // which is a different state from having deliberately turned them all off.
    return if (views.isEmpty() && chosen.isNotEmpty()) available.take(1) else views
}

/** Adds [view] to [names], or removes it when it is already there. Also used for expanded sections. */
internal fun toggledLibraryViews(names: String, view: LibraryView): String {
    val chosen = names.split(',').filterTo(mutableSetOf()) { it.isNotBlank() }
    if (!chosen.add(view.name)) chosen.remove(view.name)
    return LibraryView.entries.filter { it.name in chosen }.joinToString(",") { it.name }
}

private fun String.toDisplayLabel(): String = when (this) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Re-watching"
    "PLANNING" -> "Planning"
    "PAUSED" -> "Paused"
    else -> lowercase().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}


package com.anilili.ui.watch




import com.anilili.data.model.hasUsableWindow
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.AppGraph
import com.anilili.data.ProviderCatalog
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.StreamItem
import com.anilili.data.model.playsInWebView
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.EpisodeLayout
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.asFailure
import com.anilili.errors.report
import com.anilili.errors.toFailure
import com.anilili.playback.BulkDownloadOutcome
import com.anilili.playback.BulkEpisodeDownloads
import com.anilili.playback.DeviceDownloadAccess
import com.anilili.playback.DownloadLocations
import com.anilili.playback.DownloadStorage
import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadMetadata
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloadSubtitle
import com.anilili.playback.EpisodeDownloadUi
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.PlaybackService
import com.anilili.playback.ProgressiveIndexWarm
import com.anilili.playback.SleepTimer
import com.anilili.playback.downloadSeedStream
import com.anilili.playback.episodeDownloadBadges
import com.anilili.playback.externalPlaybackRequest
import com.anilili.playback.launchExternalPlayer
import com.anilili.playback.ExternalPlaybackSession
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.selectDownloadStream
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.FocusFillColor
import com.anilili.ui.adaptive.FocusFillContentColor
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.UnscaledUi
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.components.AnimeListStatusDialog
import com.anilili.ui.components.BulkDownloadStatus
import com.anilili.ui.components.compactMemberCount
import com.anilili.ui.components.DownloadCoverBadge
import com.anilili.ui.components.EPISODE_BROWSER_MIN_EPISODES
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.EpisodeBrowserBar
import com.anilili.ui.components.EpisodeFillerBadge
import com.anilili.ui.components.EpisodeNumberChip
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeArtworkFallback
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.filterEpisodes
import com.anilili.ui.detail.isMovieFormat
import com.anilili.ui.dialogs.MidnightChoiceRow
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightPrimaryButton
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightStorageStrip
import com.anilili.ui.dialogs.MidnightUnderlineSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.anilili.util.cached

@Composable
fun WatchScreen(
    animeId: Int,
    provider: String,
    category: String,
    episode: String,
    showEpisodeListInitially: Boolean = false,
    inPictureInPicture: Boolean = false,
    onPictureInPictureReadyChanged: (Boolean) -> Unit = {},
    /**
     * Leave the player. The argument is the label of the episode on screen when the viewer left,
     * which Next and auto-play move away from [episode]; null while nothing has loaded.
     */
    onBack: (playingEpisode: String?) -> Unit,
    onOpenAnime: () -> Unit,
    /**
     * True when the previous season's ending moved the player here itself; see
     * [WatchData.autoAdvanced].
     */
    autoAdvanced: Boolean = false,
    /** Open already fullscreen, as the previous season's player was when it handed over. */
    startFullscreen: Boolean = false,
    /** Replace this route with the next season's; the second argument is whether it is fullscreen. */
    onOpenNextSeason: (NextSeasonRequest, Boolean) -> Unit = { _, _ -> },
    /** Where an `anilili://watch/…?t=` link asked to start [episode]; null plays from the saved position. */
    startAtMs: Long? = null,
    vm: WatchViewModel = viewModel(),
) {
    // An Episodes route is a browser, not a playback request. It opts in only after a row is
    // selected; direct Watch/Resume routes represent the selection already.
    var externalPlaybackRequested by remember(
        animeId,
        provider,
        category,
        episode,
        showEpisodeListInitially,
    ) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(animeId, provider, category, episode, startAtMs) {
        SettingsStore.awaitLoaded()
        val useExternalPlayer =
            SettingsStore.openEpisodesInExternalPlayer.value && !showEpisodeListInitially
        externalPlaybackRequested = useExternalPlayer
        DiagnosticsLog.event("WatchScreen composed id=$animeId provider=$provider category=$category episode=$episode")
        vm.start(
            id = animeId,
            providerName = provider,
            categoryApi = category,
            episodeNumber = episode,
            externalPlayer = useExternalPlayer,
            autoAdvanced = autoAdvanced,
            startAtMs = startAtMs,
        )
    }
    val state by vm.state.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val openEpisodesInExternalPlayer by SettingsStore.openEpisodesInExternalPlayer.collectAsState()
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var webFallback by remember { mutableStateOf(false) }
    // On television this route *is* the player: it opens fullscreen, it never leaves fullscreen,
    // and Back goes to the anime page the viewer came from. The page that used to sit under the
    // video — episode grid, source pickers, season summary — is the anime page's job now, and
    // showing it again on the way out only put a second, worse copy of it between the viewer and
    // the screen they actually wanted. Everything it offered lives in the player's own panels.
    var fullscreen by remember(animeId, device.isTv) { mutableStateOf(device.isTv || startFullscreen) }
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentOnOpenNextSeason by rememberUpdatedState(onOpenNextSeason)
    LaunchedEffect(vm) {
        vm.nextSeasonRequests.collect { request ->
            // Saved before the route goes: the screen's own leave does this too, but only once the
            // next season's route has already replaced it and started reading history of its own.
            vm.commitPlaybackPosition()
            currentOnOpenNextSeason(request, currentFullscreen)
        }
    }
    val activity = remember(context) { context.findActivity() }
    // Whether the player has its own chrome up. The status bar rides along with it (see below), so
    // it starts true to match the controls, which are visible until playback auto-hides them.
    var playerControlsVisible by remember { mutableStateOf(true) }
    val currentOnBack by rememberUpdatedState(onBack)
    // Next and auto-play change episode inside this route, so the route's own [episode] is only
    // where the session started; Back hands the anime page the one actually on screen.
    val currentPlayingEpisode by rememberUpdatedState(
        (state as? UiState.Success)?.data?.let { it.episodes.getOrNull(it.currentIndex)?.displayNumber },
    )
    var embeddedPlaybackStopper by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentEmbeddedPlaybackStopper by rememberUpdatedState(embeddedPlaybackStopper)
    val pictureInPictureReady = state is UiState.Success
    DisposableEffect(pictureInPictureReady) {
        onPictureInPictureReadyChanged(pictureInPictureReady)
        onDispose {
            if (pictureInPictureReady) onPictureInPictureReadyChanged(false)
        }
    }
    // YouTube-style leave: native playback PAUSES (media stays loaded, so the notification can
    // resume it and re-entering the episode continues in place); the position is committed past
    // the periodic-save throttle so "continue watching" lands exactly where the user left off.
    // Embeds still stop — their WebView dies with this screen, so there is nothing to resume.
    val pauseAndBack = remember {
        {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
            PlaybackService.pauseActivePlayback()
            currentOnBack(currentPlayingEpisode)
        }
    }

    LaunchedEffect(webFallback) {
        DiagnosticsLog.event("WatchScreen webFallback=$webFallback")
        if (webFallback) PlaybackService.stopActivePlayback()
    }

    LaunchedEffect(state, webFallback) {
        when (val s = state) {
            is UiState.Loading -> {
                delay(10_000)
                if (state is UiState.Loading && !webFallback) {
                    DiagnosticsLog.event("WatchScreen still loading after 10000ms id=$animeId provider=$provider")
                }
            }
            // The code, not the sentence: the failure itself was already reported under it, so this
            // line exists only to mark the moment it reached the screen.
            is UiState.Error -> DiagnosticsLog.event("WatchScreen error visible code=${s.failure.code}")
            is UiState.Success -> {
                val stream = s.data.chosenStream
                if (stream?.quality.equals("Downloaded", ignoreCase = true) ||
                    stream?.url?.startsWith("file:", ignoreCase = true) == true ||
                    stream?.url?.startsWith("content:", ignoreCase = true) == true
                ) {
                    fullscreen = true
                }
                DiagnosticsLog.event(
                    "WatchScreen success visible provider=${s.data.provider} episode=${s.data.current.displayNumber} " +
                        "stream=${stream?.let { if (it.isEmbed) "embed" else if (it.isHls) "hls" else "direct" } ?: "none"} " +
                        "resolving=${s.data.isResolving}",
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (device.isTv) releaseImageMemoryForPlayback(context)
    }

    // Drive orientation from the fullscreen flag; restore it (and the bars) on leave.
    DisposableEffect(fullscreen, device.isTv) {
        if (activity != null && !device.isTv) {
            activity.requestedOrientation = if (fullscreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            val w = activity?.window
            if (activity != null && w != null) {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    PlayerSystemBarsEffect(
        activity = activity,
        immersive = fullscreen,
        controlsVisible = playerControlsVisible,
        isTv = device.isTv,
    )

    // Back exits fullscreen first, then the screen — except on television, where there is nothing
    // under fullscreen to exit to, so one press leaves for the anime page. On TV the player claims
    // Back ahead of this while it has chrome of its own up (see the remote key handlers in
    // PlayerSurface and EmbedWebView), so these fire only once nothing of the player's is showing.
    //
    // On TV a playing episode takes a second press within [TV_EXIT_CONFIRM_MS] to leave. The
    // controls hide on their own timer, so a Back meant to close them could land a moment after
    // they had gone and take the viewer out of the episode instead; the first press now only says
    // what a second one would do. Loading and error screens still leave on one press, and so does
    // every screen for a viewer who switched it off in Settings.
    val tvDoubleBackToExit by SettingsStore.tvDoubleBackToExit.collectAsState()
    var tvExitArmed by remember { mutableStateOf(false) }
    val tvExitNeedsConfirm = device.isTv && tvDoubleBackToExit && state is UiState.Success &&
        !webFallback && !inPictureInPicture
    LaunchedEffect(tvExitArmed) {
        if (tvExitArmed) {
            delay(TV_EXIT_CONFIRM_MS)
            tvExitArmed = false
        }
    }
    // Bringing the controls back is a new interaction, not the second half of a leave.
    LaunchedEffect(playerControlsVisible) { if (playerControlsVisible) tvExitArmed = false }
    val tvBack = {
        if (tvExitNeedsConfirm && !tvExitArmed) {
            DiagnosticsLog.event("WatchScreen TV back armed")
            tvExitArmed = true
        } else {
            pauseAndBack()
        }
    }
    BackHandler(enabled = fullscreen) { if (device.isTv) tvBack() else fullscreen = false }
    BackHandler(enabled = !fullscreen) { if (device.isTv) tvBack() else pauseAndBack() }

    // Pause however the screen is left (back, Anime page, notification nav) — not just the
    // Back gesture. PiP keeps this screen composed, so picture-in-picture is unaffected.
    // Pause rather than stop: the loaded session powers the notification's resume button,
    // and background media-button events are suppressed so it can't restart by itself.
    DisposableEffect(Unit) {
        onDispose {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
            PlaybackService.pauseActivePlayback()
        }
    }

    // Whether a refresh the viewer asked for is still running, so a second press says so rather
    // than tearing the first one down and starting it again — restarting Miruro's hidden browser
    // under a live decoder is the one thing this screen cannot do casually.
    val catalogRefreshRunning = (state as? UiState.Success)?.data?.isRetryingMiruro == true
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The remote's Menu key reloads the episode list and the servers behind it. This screen
            // has no navigation bar to hold OK on and no gesture either — the window belongs to the
            // player — so the one button a television remote reliably carries for "something else
            // here" is what asks for it. Nothing else on the watch screen claims Menu.
            //
            // On the release, like every other press in the app that means more than a click: a
            // held key auto-repeats, and one refresh per repeat is a pipe restart per repeat.
            .onPreviewKeyEvent { event ->
                if (!device.isTv || event.key != Key.Menu) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    if (catalogRefreshRunning) {
                        Toast.makeText(context, "Already refreshing…", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Refreshing episodes and servers…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        vm.refreshEpisodeCatalog()
                    }
                }
                true
            },
    ) {
        if (webFallback) {
            EmbedWebView(
                url = "https://www.miruro.to/info/$animeId",
                referer = "https://www.miruro.to/",
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { fullscreen = it },
                onProgress = vm::onProgress,
                onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
                onControlsVisibilityChanged = { playerControlsVisible = it },
            )
            BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            return@Box
        }
        // Do not mount the built-in player for one frame while the persisted preference is still
        // loading; that is enough for Media3 to begin fetching and briefly steal audio focus.
        if (externalPlaybackRequested == null) {
            LoadingBox()
            BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            return@Box
        }

        when (val s = state) {
            is UiState.Loading -> {
                // Only surface the "this can take a moment" note once loading is genuinely slow,
                // so it doesn't flash by on the common fast (Miruro-first) path.
                var showSlowNote by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(1_500)
                    showSlowNote = true
                }
                val loadingStatus by vm.loadingStatus.collectAsState()
                LoadingBox(
                    message = loadingStatus ?: if (showSlowNote) {
                        "Finding a source for this episode.\n" +
                            "The first playable server starts now; more options are checked after playback begins."
                    } else {
                        null
                    },
                )
                BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            }
            is UiState.Error -> Column(Modifier.fillMaxSize()) {
                ErrorBox(s.failure, onRetry = vm::retry, modifier = Modifier.weight(1f))
                val controlFocus1 = rememberFocusFill()
                TextButton(
                    colors = controlFocus1.buttonColors(ButtonDefaults.textButtonColors()),
                    onClick = { webFallback = true },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 24.dp)
                        .focusFill(controlFocus1, RoundedCornerShape(20.dp)),
                ) { Text("Open in web player") }
                BackButton(pauseAndBack, Modifier.align(Alignment.Start))
            }
            is UiState.Success -> {
                val data = s.data
                val externalStream = data.chosenStream?.takeUnless(StreamItem::isEmbed)
                if (
                    openEpisodesInExternalPlayer &&
                    externalPlaybackRequested == true &&
                    externalStream != null
                ) {
                    AutomaticExternalPlayerHandoff(
                        data = data,
                        stream = externalStream,
                        launchKey = "${data.anilistId}/${data.current.number}/${externalStream.url}",
                        session = vm.externalPlaybackSession(data),
                        onLaunched = pauseAndBack,
                        // Nothing took the episode. Keep it here for the rest of this route rather
                        // than offering it again — and to the same absent app — each time the
                        // stream underneath changes.
                        onFailure = {
                            externalPlaybackRequested = false
                            vm.dropExternalPlayerRequirement()
                        },
                    )
                    return@Box
                }

                val saved = watchlist.any { it.anilistId == data.anilistId }
                CompositionLocalProvider(LocalPlayerInPictureInPicture provides inPictureInPicture) {
                WatchContent(
                    data = data,
                    fullscreen = fullscreen || inPictureInPicture,
                    saved = saved,
                    onToggleSaved = {
                        LibraryStore.toggleWatchlist(
                            WatchlistEntry(
                                anilistId = s.data.anilistId,
                                title = s.data.seriesTitle,
                                cover = s.data.artworkUrl,
                                format = s.data.seriesFormat,
                                averageScore = s.data.averageScore,
                            ),
                        )
                    },
                    onBack = pauseAndBack,
                    onOpenAnime = onOpenAnime,
                    onPrev = vm::prev,
                    onNext = vm::next,
                    onAutoAdvance = vm::autoAdvance,
                    onSkipFiller = vm::skipFiller,
                    onChangeSource = vm::changeSource,
                    onChangeCategory = vm::changeCategory,
                    onRequestAllSources = vm::validateRemainingSources,
                    onRetryMiruroSources = vm::retryMiruroServers,
                    onSelectEpisode = { index ->
                        val useExternalPlayer = SettingsStore.openEpisodesInExternalPlayer.value
                        externalPlaybackRequested = useExternalPlayer
                        if (useExternalPlayer) PlaybackService.stopActivePlayback()
                        if (device.isTv) fullscreen = true
                        vm.playIndex(index, externalPlayer = useExternalPlayer)
                    },
                    onWebFallback = { webFallback = true },
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onFullscreenChanged = { fullscreen = it },
                    onProgress = vm::onProgress,
                    onFinalProgress = vm::onFinalProgress,
                    onPlaybackError = vm::onPlaybackError,
                    onPlaybackStalled = vm::onPlaybackStalled,
                    onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
                    playerControlsVisible = playerControlsVisible,
                    onControlsVisibilityChanged = { playerControlsVisible = it },
                    onPlayerClosed = vm::commitPlaybackPosition,
                    onReportSkipTiming = vm::reportSkipTiming,
                    externalPlaybackSession = { durationMs -> vm.externalPlaybackSession(data, durationMs) },
                    onDownloadSeries = { episodes, quality ->
                        BulkEpisodeDownloads.start(
                            context = context,
                            anilistId = s.data.anilistId,
                            seriesTitle = s.data.seriesTitle,
                            artworkUrl = s.data.artworkUrl,
                            category = s.data.category,
                            preferredProvider = s.data.provider,
                            // The subtitle shape on screen, not the category's default: queueing
                            // the rest of a series while watching bonk's soft sub used to hand
                            // back whichever variant bonk happened to list first.
                            variant = s.data.sourceVariant,
                            episodes = episodes,
                            catalog = vm.episodeCatalog(),
                            quality = quality,
                        )
                    },
                )
                }
                if (tvExitArmed) {
                    Text(
                        "Press Back again to exit",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50))
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** How long a first Back on the TV player waits for the second one that leaves the episode. */
private const val TV_EXIT_CONFIRM_MS = 2_500L

/** Resolves first, launches once, then removes the empty watch route from behind the other app. */
@Composable
private fun AutomaticExternalPlayerHandoff(
    data: WatchData,
    stream: StreamItem,
    launchKey: String,
    session: ExternalPlaybackSession,
    onLaunched: () -> Unit,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val isMovie = isMovieFormat(data.seriesFormat)
    LaunchedEffect(launchKey) {
        // A previous episode may still own the shared playback service while a new route resolves.
        // Release it before the receiving app begins fetching the same short-lived stream URL.
        PlaybackService.stopActivePlayback()
        val launched = launchExternalPlayer(
            context = context,
            request = externalPlaybackRequest(
                stream = stream,
                seriesTitle = data.seriesTitle,
                episodeTitle = if (isMovie) {
                    data.seriesTitle
                } else {
                    "Episode ${data.current.displayNumber}" +
                        (data.current.title?.let { ": $it" } ?: "")
                },
                resumePositionMs = data.startPositionMs,
                subtitles = data.sources.subtitles,
            ),
            preferredPackage = SettingsStore.externalPlayerPackage.value,
            session = session,
        )
        if (launched) onLaunched() else onFailure()
    }
    LoadingBox(message = "Opening in external video player…")
}

@Composable
private fun WatchContent(
    data: WatchData,
    fullscreen: Boolean,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onBack: () -> Unit,
    onOpenAnime: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    /** The player continuing on its own after the credits, as opposed to [onNext]'s press. */
    onAutoAdvance: () -> Unit,
    /** The FILLER tag's press: on past this filler episode to the next one that is not. */
    onSkipFiller: () -> Unit,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onWebFallback: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    /** The player's last report as it is torn down, saved past the periodic-save throttle. */
    onFinalProgress: (String, Long, Long) -> Unit,
    onPlaybackError: (AnililiFailure, String, Long) -> Unit,
    onPlaybackStalled: (String, Long) -> Unit,
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
    /** Whether the player's own controls are on screen right now; the Back arrow rides with them. */
    playerControlsVisible: Boolean = true,
    /** Reports whether the player's own controls are on screen, so the status bar can match them. */
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    onPlayerClosed: () -> Unit = {},
    /** Downvotes the AniSkip window on screen, for a viewer whose skip landed in the wrong place. */
    onReportSkipTiming: () -> Unit = {},
    /** Starts a batch download of [episodes]; the catalog it needs lives with the ViewModel. */
    onDownloadSeries: (List<EpisodeItem>, DownloadQuality) -> Unit = { _, _ -> },
    /** The episode as an external player hand-off, given the length the player has measured. */
    externalPlaybackSession: ((Long) -> ExternalPlaybackSession)? = null,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val isMovie = isMovieFormat(data.seriesFormat)
    val showLoadingOverlay by SettingsStore.showLoadingOverlay.collectAsState()
    // AniList ships its synopses with HTML in them, so the player gets the same cleaned text the
    // details pane below already shows rather than markup.
    val pauseDescription = remember(data.anilistId, data.description) {
        data.description?.cleanAniListDescription().orEmpty()
    }
    // Kept, not filtered at the call: that call sits in the lambda that reads playerControlsVisible,
    // so every reveal and auto-hide of the TV control row handed PlayerSurface a new list and it
    // could not skip.
    val nativeQualityStreams = remember(data.sources.streams) {
        data.sources.streams.filterNot(StreamItem::isEmbed)
    }
    // The series' logo for the player's title and pause billing. Keyed on the series alone, so
    // moving to the next episode does not ask again; the tree record is cached for a day anyway.
    val seriesLogo by produceState<String?>(initialValue = null, data.anilistId) {
        value = AppGraph.repository.seriesLogo(data.anilistId)
    }
    // Where moving on lands, with "Skip filler episodes" taken into account, for the up-next card
    // and the FILLER tag's label; the view model makes the same call when the move happens.
    val autoSkipFiller by SettingsStore.autoSkipFiller.collectAsState()
    val nextEpisodeIndex = data.nextIndex(skipFiller = autoSkipFiller)
    val movesIntoNextSeason = data.advancesIntoNextSeason(skipFiller = autoSkipFiller)
    val fillerSkip = if (!isMovie && data.canSkipFiller) {
        val target = if (data.advancesIntoNextSeason(skipFiller = true)) {
            data.nextSeason?.label ?: "next season"
        } else {
            data.nextIndex(skipFiller = true)
                ?.let { "Episode ${data.episodes[it].displayNumber}" } ?: "next episode"
        }
        PlayerFillerSkip(target = target, onSkip = onSkipFiller)
    } else {
        null
    }
    // The same servers the row below the player offers, in the shape the player's own panel wants.
    // Entries that do not carry this episode stay listed but unselectable, because "this server has
    // nothing for episode 12" is the answer someone opening the panel is looking for.
    val preferredSourceLanguages by SettingsStore.preferredSourceLanguages.collectAsState()
    val playerSourceOptions = remember(data, preferredSourceLanguages) {
        val preferredLanguages = preferredSourceLanguages.mapTo(linkedSetOf()) { it.label }
        val isPlaying = { option: WatchSourceOption ->
            option.provider == data.provider && option.variant == data.pickerVariant
        }
        // Playing first, as in the other pickers, so the sheet opens on what is on screen.
        data.sourceOptions.sortedByDescending(isPlaying).map { option ->
            val selected = isPlaying(option)
            PlayerSourceOption(
                id = "${option.provider}:${option.variant.route}",
                // The name and second line every other picker shows. This sheet used to show the
                // provider's internal id ("bee", "anichan") over a bare "SSUB", which said nothing
                // a viewer could choose by.
                label = serverTitle(option, data),
                detail = if (option.hasCurrentEpisode) {
                    serverDescription(option, data, preferredLanguages, selected)
                } else {
                    "No episode ${data.current.displayNumber}"
                },
                selected = selected,
                enabled = option.hasCurrentEpisode,
                onSelect = {
                    if (option.provider != data.provider || option.variant != data.sourceVariant) {
                        onChangeSource(option.provider, option.variant.route)
                    }
                },
            )
        }
    }
    // The same sub/dub the filter row below the player offers, reduced to the one press it is
    // usually worth: which audio, not which of nine servers happens to carry it.
    val playerAudioSwitch = remember(data.sourceOptions, data.provider, data.sourceVariant) {
        resolvePlayerAudioSwitch(data.sourceOptions, data.provider, data.sourceVariant)
    }
    var listStatusDialogVisible by remember(data.anilistId) { mutableStateOf(false) }
    // Raised by the player's own Servers control. A counter rather than a flag because the picker
    // owns whether it is open — this only says "the viewer asked again", which is the one thing
    // the player knows and SourceSelectors cannot see from inside the dialog it manages.
    var sourcePickerRequest by remember(data.anilistId) { mutableIntStateOf(0) }
    val downloads by EpisodeDownloads.downloads(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val preparingIds by EpisodeDownloads.preparingIds.collectAsState()
    val downloadId = EpisodeDownloads.idFor(
        data.anilistId,
        data.category.api,
        data.current.displayNumber,
    )
    val episodeDownload = downloads.firstOrNull { it.id == downloadId }
    val exportedEpisode = exportedEpisodes.firstOrNull { it.downloadId == downloadId }
    val hasDownloadEntry = episodeDownload != null || exportedEpisode != null
    val hasOfflineCopy = episodeDownload?.isComplete == true || exportedEpisode != null
    // The chosen stream may be an embed (Bonk, Kiwi) that cannot be saved; fall back to
    // any natively downloadable stream so the episode stays downloadable either way.
    val streamForDownload = data.chosenStream?.takeIf(EpisodeDownloads::canDownload)
        ?: downloadSeedStream(data.provider, data.sources)
    // A device whose Media3 cannot transmux has already proven it; offering "save to Downloads"
    // there only produces a failed export. In-app downloads still work and stay on offer.
    val canSaveToDevice = DeviceDownloadAccess.isSupported &&
        EpisodeDownloads.canSaveToDevice(streamForDownload) &&
        !EpisodeExport.transformerUnsupported(context)
    val downloadPreparing = downloadId in preparingIds
    val defaultDownloadQuality by SettingsStore.downloadQuality.collectAsState()
    val defaultDownloadDestination by SettingsStore.downloadDestination.collectAsState()
    var downloadDialogVisible by remember(
        data.anilistId,
        data.category,
        data.current.pipeId,
        data.provider,
    ) { mutableStateOf(false) }
    var removeCurrentDownloadDialogVisible by remember(downloadId) { mutableStateOf(false) }

    if (listStatusDialogVisible) {
        AnimeListStatusDialog(
            anilistId = data.anilistId,
            title = data.seriesTitle,
            episodeLabel = if (isMovie) {
                "Movie"
            } else {
                "Episode ${data.current.displayNumber}" +
                    (data.current.distinctTitle?.let { ": $it" } ?: "")
            },
            artworkUrl = data.artworkUrl,
            onDismiss = { listStatusDialogVisible = false },
        )
    }
    // On television the player is the whole screen, so the source picker has no row to live in:
    // it is mounted here with its chrome hidden, purely to own the dialog the player opens. On a
    // handheld the same picker is still drawn in full under the video.
    if (device.isTv && fullscreen) {
        val hiddenSourceFocus = remember { FocusRequester() }
        SourceSelectors(
            data = data,
            onChangeSource = onChangeSource,
            onChangeCategory = onChangeCategory,
            onRequestAllSources = onRequestAllSources,
            onRetryMiruroSources = onRetryMiruroSources,
            focusRequester = hiddenSourceFocus,
            showRow = false,
            openRequest = sourcePickerRequest,
        )
    }

    if (removeCurrentDownloadDialogVisible) {
        MidnightConfirmDialog(
            kicker = "Remove download",
            title = if (isMovie) "Remove this movie?" else "Remove this episode?",
            body = if (isMovie) {
                "${data.seriesTitle} goes from this device, along with its MP4 copy in Downloads " +
                    "when one exists. Your watch progress is untouched."
            } else {
                "${data.seriesTitle} · Episode ${data.current.displayNumber} goes from this " +
                    "device, along with its MP4 copy in Downloads when one exists. Your watch " +
                    "progress is untouched."
            },
            confirmText = if (isMovie) "Remove movie" else "Remove episode",
            onConfirm = {
                episodeDownload?.let { EpisodeDownloads.remove(context, downloadId) }
                exportedEpisode?.let { EpisodeExport.deleteExported(context, downloadId) }
                removeCurrentDownloadDialogVisible = false
            },
            onDismiss = { removeCurrentDownloadDialogVisible = false },
        )
    }
    var selectedDownloadQuality by remember { mutableStateOf(defaultDownloadQuality) }
    // 1 means just the episode on screen; anything larger is a bounded batch.
    var downloadBatchSize by remember { mutableIntStateOf(1) }
    var skipFillerDownloads by remember { mutableStateOf(false) }
    // Everything from the current episode onward that is not already saved — the batch a viewer
    // starting partway through actually wants, rather than re-fetching what they have.
    val remainingEpisodes = remember(
        data.episodes,
        data.current.number,
        data.anilistId,
        data.category,
        downloads,
        exportedEpisodes,
    ) {
        BulkEpisodeDownloads.pendingEpisodes(
            episodes = data.episodes,
            anilistId = data.anilistId,
            category = data.category.api,
            fromNumber = data.current.number,
            // Exported episodes count as held too. With the cache copy dropped after conversion,
            // going by the download index alone would offer to re-fetch a season already saved.
            alreadyHave = offlineEpisodes(downloads, exportedEpisodes)
                .filter(OfflineEpisode::isPlayable)
                .map(OfflineEpisode::id)
                .toSet(),
        )
    }
    val eligibleRemainingEpisodes = remember(remainingEpisodes, skipFillerDownloads) {
        BulkEpisodeDownloads.eligibleEpisodes(remainingEpisodes, skipFillerDownloads)
    }
    var selectedDownloadDestination by remember { mutableStateOf(defaultDownloadDestination) }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted) {
            action?.invoke()
        } else {
            if (defaultDownloadDestination.includesDevice) {
                SettingsStore.setDownloadDestination(DownloadDestination.APP_ONLY)
            }
            Toast.makeText(
                context,
                "Notification permission is needed for background downloads.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                "Storage permission is needed to save MP4 files in Downloads on Android 9 and earlier.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val queueCurrentEpisode: (DownloadQuality, DownloadDestination) -> Unit = queue@{ quality, destination ->
        streamForDownload?.let { stream ->
            val selectedStream = selectDownloadStream(
                current = stream,
                available = data.sources.streams,
                quality = quality,
            )
            val effectiveDestination = if (canSaveToDevice) {
                destination
            } else {
                DownloadDestination.APP_ONLY
            }
            val metadata = EpisodeDownloadMetadata(
                anilistId = data.anilistId,
                seriesTitle = data.seriesTitle,
                episodeNumber = data.current.displayNumber,
                episodeTitle = data.current.title,
                filler = data.current.filler,
                artworkUrl = data.artworkUrl,
                provider = data.provider,
                category = data.category.api,
                variant = data.sourceVariant.route,
                referer = selectedStream.referer,
                headers = selectedStream.headers,
                subtitles = data.sources.subtitles.map { subtitle ->
                    EpisodeDownloadSubtitle(
                        url = subtitle.url,
                        label = subtitle.label,
                        language = subtitle.language,
                        referer = subtitle.referer,
                        headers = subtitle.headers,
                        mimeType = subtitle.mimeType,
                    )
                },
                skip = data.sources.skip?.takeIf { it.hasUsableWindow() },
            )
            val appDownloadCanStart = episodeDownload == null ||
                episodeDownload.state == EpisodeDownloadState.FAILED
            // Downloads-folder copies always come from a library download that is then rewrapped,
            // so the cache download runs even when the viewer asked for Downloads alone.
            val exportsToDevice = effectiveDestination.includesDevice
            val needsAppDownload = effectiveDestination.includesApp || exportsToDevice
            val enqueue = {
                if (exportsToDevice) {
                    EpisodeExport.request(
                        context = context,
                        downloadId = downloadId,
                        // "Device Downloads" means don't leave behind a library copy *this action*
                        // created. An episode that was already in the library stays there — the
                        // dialog preselects this destination for those, and silently deleting a
                        // download the viewer already had is not what they asked for.
                        deleteAppCopyAfter = !effectiveDestination.includesApp && appDownloadCanStart,
                    )
                }
                if (needsAppDownload && appDownloadCanStart) {
                    EpisodeDownloads.enqueue(
                        context = context,
                        metadata = metadata,
                        requestedStream = selectedStream,
                        quality = quality,
                    ) { appResult ->
                        val message = when {
                            appResult.isFailure -> appResult.exceptionOrNull()
                                ?.toFailure(AnililiError.DOWNLOAD_START_FAILED)
                                ?.withCode()
                                ?: AnililiError.DOWNLOAD_START_FAILED.report().withCode()
                            exportsToDevice && effectiveDestination.includesApp ->
                                "Episode added to Anilili. The MP4 is written to Downloads once it finishes."
                            exportsToDevice ->
                                "Downloading now. The MP4 is written to Downloads once it finishes."
                            else -> "Episode added to Anilili downloads."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val message = when {
                        exportsToDevice && episodeDownload?.isComplete == true ->
                            "Saving this episode to Downloads as MP4."
                        exportsToDevice ->
                            "Already downloading. The MP4 follows once it finishes."
                        effectiveDestination.includesApp -> "This episode is already in Anilili downloads."
                        else -> AnililiError.DOWNLOAD_START_FAILED.report().withCode()
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            if (
                needsAppDownload &&
                appDownloadCanStart &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownloadAction = enqueue
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                enqueue()
            }
        }
        Unit
    }
    val showDownloadDialog: () -> Unit = show@{
        if (streamForDownload == null) {
            if (hasDownloadEntry) removeCurrentDownloadDialogVisible = true
            return@show
        }
        selectedDownloadQuality = defaultDownloadQuality
        selectedDownloadDestination = if (canSaveToDevice) {
            if (
                episodeDownload?.state == EpisodeDownloadState.COMPLETED &&
                defaultDownloadDestination == DownloadDestination.APP_ONLY
            ) {
                DownloadDestination.DEVICE_ONLY
            } else {
                defaultDownloadDestination
            }
        } else {
            DownloadDestination.APP_ONLY
        }
        downloadDialogVisible = true
    }

    if (downloadDialogVisible && streamForDownload != null) {
        EpisodeDownloadDialog(
            stream = streamForDownload,
            isMovie = isMovie,
            seriesTitle = data.seriesTitle,
            episodeLabel = if (isMovie) {
                "Movie"
            } else {
                listOfNotNull(
                    "Episode ${data.current.displayNumber}",
                    data.current.title?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            },
            artworkUrl = data.artworkUrl,
            quality = selectedDownloadQuality,
            destination = selectedDownloadDestination,
            canSaveToDevice = canSaveToDevice,
            alreadyInApp = episodeDownload?.state == EpisodeDownloadState.COMPLETED,
            alreadyInDownloads = exportedEpisode != null,
            canRemove = hasDownloadEntry,
            onQualityChange = { selectedDownloadQuality = it },
            onDestinationChange = { selectedDownloadDestination = it },
            batchSize = downloadBatchSize,
            onBatchSizeChange = { downloadBatchSize = it },
            remainingCount = eligibleRemainingEpisodes.size,
            fillerCount = remainingEpisodes.count(EpisodeItem::filler),
            skipFillers = skipFillerDownloads,
            onSkipFillersChange = { skipFillerDownloads = it },
            onDismiss = { downloadDialogVisible = false },
            onRemove = {
                downloadDialogVisible = false
                removeCurrentDownloadDialogVisible = true
            },
            onConfirm = {
                val quality = selectedDownloadQuality
                val destination = selectedDownloadDestination
                val batchRequested = downloadBatchSize > 1
                val batch = downloadBatchSize.coerceAtMost(eligibleRemainingEpisodes.size)
                downloadDialogVisible = false
                val startDownload = {
                    if (batchRequested && eligibleRemainingEpisodes.isNotEmpty()) {
                        onDownloadSeries(
                            eligibleRemainingEpisodes.take(batch.coerceAtLeast(1)),
                            quality,
                        )
                    } else {
                        queueCurrentEpisode(quality, destination)
                    }
                }
                if (
                    !batchRequested &&
                    destination.includesDevice &&
                    DownloadLocations.needsLegacyPermission(context)
                ) {
                    pendingStorageAction = startDownload
                    storagePermissionLauncher.launch(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    )
                } else {
                    startDownload()
                }
            },
        )
    }
    // A progressive MP4 keeps its index at the end of the file, so the player's first act is a
    // range-seek to the tail before it can decode anything. This runs here rather than inside the
    // player because here is the earliest the stream URL exists: the resolve has just answered and
    // the player is still seconds from being built. See [ProgressiveIndexWarm].
    val chosenStreamUrl = data.chosenStream?.url
    LaunchedEffect(chosenStreamUrl, hasOfflineCopy) {
        // Not for an episode already on disk: it keeps the stream's URL as its key, so the warm
        // would happily fetch an index over the network for a file that needs no network at all.
        if (hasOfflineCopy) return@LaunchedEffect
        data.chosenStream?.let { ProgressiveIndexWarm.warm(context, it) }
    }

    val summaryFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    val tvEpisodeListState = rememberLazyListState()
    var hasShownFullscreen by remember { mutableStateOf(fullscreen) }

    // TV: leaving fullscreen must hand focus to the episode/source area — otherwise it can
    // stay inside the player (or an embed WebView) and the D-pad never reaches the list.
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            hasShownFullscreen = true
        } else if (device.isTv) {
            // Start the remote in the episode summary, and return it there after fullscreen.
            // This avoids Android player views becoming a second, invisible D-pad focus owner.
            delay(if (hasShownFullscreen) 180 else 950)
            var focused = false
            repeat(6) {
                if (!focused) {
                    focused = runCatching { summaryFocus.requestFocus() }.isSuccess
                    if (!focused) delay(32)
                }
            }
            if (focused) {
                DiagnosticsLog.event("Watch TV selector focus requested")
            } else {
                DiagnosticsLog.event("Watch TV selector focus unavailable after retries")
            }
        }
    }

    LaunchedEffect(data.currentIndex, fullscreen, device.isTv) {
        if (device.isTv && !fullscreen) {
            // Native player focus can briefly bring a lower control into view during load.
            // Restore the summary as the visual anchor once that handoff settles.
            val returningFromFullscreen = hasShownFullscreen
            delay(if (returningFromFullscreen) 260 else 850)
            tvEpisodeListState.scrollToItem(0)
            if (!returningFromFullscreen) {
                // The initial summary focus request lands just after the first reset; undo the
                // small bring-into-view offset it can apply to the title line.
                delay(300)
                tvEpisodeListState.scrollToItem(0)
            }
        }
    }

    // The player must stay clear of the status bar so the clock/battery icons never overlap the
    // video, and its size must come from the actual constraints this screen was given (not
    // LocalConfiguration, which ignores multi-window and navigation rails) so the video always
    // fits the space it really has.
    //
    // The padding is not dropped the instant the fullscreen flag flips. Doing that let go of it
    // while the status bar was still on screen and the rotation to landscape was still a beat away
    // — so the whole screen visibly jumped upward first and rotated afterwards ("the entire thing
    // kinda shifts upwards", reported on mobile, reproduced on device and confirmed frame by frame
    // in a screen recording). It is dropped a moment later instead, once the bar has actually gone
    // and the inset has reached zero of its own accord: the content is then already sitting where
    // the padding would put it, so letting go of it moves nothing.
    //
    // And it has to be let go of, because the player asks for the status bar back every time its
    // controls come up — which includes the first frame in fullscreen. Waiting for the inset to
    // reach zero deadlocks that initial state: visible controls keep the bar visible, the visible
    // bar keeps the inset non-zero, and the inset leaves a permanent black strip above the video.
    // Landscape is the reliable handoff instead. It changes only once rotation has happened, so
    // the portrait screen cannot jump early, and from that point the transparent bar overlays the
    // picture immediately. The controls hold themselves clear of its icons with their own padding.
    val windowIsLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                if (statusBarStaysOutsidePlayer(fullscreen, windowIsLandscape)) {
                    Modifier.statusBarsPadding()
                } else {
                    Modifier
                },
            ),
    ) {
        val availableWidthDp = maxWidth
        val availableHeightDp = maxHeight
        Column(Modifier.fillMaxSize()) {
        val playerModifier = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            val naturalHeight = availableWidthDp * 9f / 16f
            val landscape = availableWidthDp > availableHeightDp
            val maxHeightFraction = when {
                device.isTv -> 0.56f
                landscape -> 0.66f
                else -> 0.62f
            }
            val playerHeight = minOf(naturalHeight, availableHeightDp * maxHeightFraction)
            // Crop (RESIZE_MODE_ZOOM) scales the picture until it fills the box, and on anything
            // narrower than the box — 4:3 shows are the common case — the scaled picture is taller
            // than the box it was measured for. Nothing above this stops it drawing outside, so it
            // spilled over the episode title below and behind the status bar above, carrying the
            // caption layer with it. Clip, so Crop cuts the picture instead of overflowing.
            Modifier.fillMaxWidth().height(playerHeight).clipToBounds()
        }
        // In the TV grid the Android player/WebView must not join the Compose focus graph: some
        // embeds retain native focus even after the ring moves, producing two remote owners.
        // Fullscreen remains available as an explicit selector below the summary.
        val playerFocusModifier = if (device.isTv && !fullscreen) {
            Modifier
                .semantics { contentDescription = "Video player" }
        } else {
            Modifier
        }
        Box(playerModifier.then(playerFocusModifier).background(Color.Black)) {
        // Provided here rather than passed down: see LocalPlayerSourcePicker for why one more
        // parameter on the embed player is one too many.
        // The same gate WatchEpisodeSummary puts on its own download button, which TV never shows.
        val canDownload = streamForDownload != null
        val appDownloadCanStart = episodeDownload == null ||
            episodeDownload.state == EpisodeDownloadState.FAILED
        val playerDownload = if (
            device.isTv &&
            (episodeDownload != null || hasOfflineCopy ||
                (canDownload && !downloadPreparing && (canSaveToDevice || appDownloadCanStart)))
        ) {
            PlayerDownloadAction(downloaded = hasOfflineCopy, onClick = showDownloadDialog)
        } else {
            null
        }
        CompositionLocalProvider(
            LocalPlayerSourcePicker provides { sourcePickerRequest++ },
            LocalPlayerDownload provides playerDownload,
        ) {
            val stream = data.chosenStream
            // Key on the KIND of player only, never per-episode/per-url: recreating PlayerSurface
            // for every Next tore down and rebuilt the PlayerView + MediaController each episode
            // (the new view was even created before the old one released). On weak TV hardware
            // (Fire TV's OMX.MS.AVC decoder, 192MB heap) that churn froze the UI for seconds and
            // could wedge the video decoder while audio played on. Both PlayerSurface and
            // EmbedWebView already handle stream-url changes internally, reusing the surface,
            // codec chain, and controller connection.
            val playerKind = when {
                device.isTv && !fullscreen -> "tv-idle"
                stream == null -> "none"
                stream.playsInWebView(data.provider) -> "embed"
                else -> "native"
            }
            // Whether whichever player is mounted has a picture yet. Both report false on their
            // first composition and again whenever a new stream is prepared, so this needs no key
            // of its own; the one gap it cannot see is the instant between one player being
            // disposed and the next reporting in, which is a frame.
            var playerHasPicture by remember { mutableStateOf(false) }
            // Resolving is only the first half of opening an episode, and it was the only half the
            // loading state covered. The second half — the player fetching a playlist and its first
            // segments — is where the time actually goes when a source is bad: measured at 10.8s,
            // 11.0s, 12.7s and 18.2s of black rectangle on four consecutive episodes, every one of
            // them with the overlay already dismissed because the resolve had succeeded. Nothing on
            // screen said the app was still doing something, so the episode read as simply not
            // starting. The picture arriving is what ends it, not the URL arriving.
            val awaitingPicture = stream != null && playerKind != "tv-idle" && !playerHasPicture
            val loadingOverlayVisible = (data.isResolving || awaitingPicture) && showLoadingOverlay
            // The video and everything the player draws over it keep the device's real density.
            // These overlays are tuned against the picture and against the panel's overscan,
            // neither of which moves when the viewer changes the interface size — and growing
            // them would push the controls into the cropped edge TV_SAFE_AREA_INSET exists to
            // stay clear of. The pane's own height is still a share of the screen, so the video
            // keeps its place in the layout at every size.
            UnscaledUi {
            CompositionLocalProvider(LocalPlayerSeriesLogo provides seriesLogo) { PlayerAccentScope {
            key(playerKind) {
                when {
                    device.isTv && !fullscreen -> {
                        // Keep native PlayerView/WebView focus out of the TV episode grid: the
                        // player only exists in fullscreen. Leaving fullscreen stops playback, so
                        // first bank the position it reached — every resume path reads it.
                        LaunchedEffect(stream?.url) {
                            PlaybackService.stopActivePlayback()
                            onPlayerClosed()
                        }
                        // A real focusable play button (not a decoration): Fire TV users expect
                        // to click play here to continue watching, which re-enters fullscreen.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val fillFocus1 = rememberFocusFill()
                            Box(
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Play"
                                        onClick(label = "Play") {
                                            onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        val keyCode = event.nativeKeyEvent.keyCode
                                        val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                        if (!activate) {
                                            false
                                        } else {
                                            if (event.type == KeyEventType.KeyUp) onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .focusFill(fillFocus1, CircleShape)
                                    .clip(CircleShape)
                                    .background(fillFocus1.background(Color.White.copy(alpha = 0.14f)))
                                    .clickable(onClick = onToggleFullscreen)
                                    .focusable()
                                    .size(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = fillFocus1.content(Color.White),
                                    modifier = Modifier.size(46.dp),
                                )
                            }
                        }
                    }
                    stream == null -> NoSource(onWebFallback)
                    stream.playsInWebView(data.provider) ->
                        Box(Modifier.fillMaxSize()) {
                            LaunchedEffect(stream.url) { PlaybackService.stopActivePlayback() }
                            EmbedEpisodeNavigationEffect(
                                hasPrevious = data.hasPrev,
                                hasNext = data.canAdvance,
                                onPrevious = onPrev,
                                onNext = onNext,
                            )
                            EmbedWebView(
                                url = stream.url,
                                referer = stream.referer,
                                modifier = Modifier.fillMaxSize(),
                                qualityStreams = data.sources.embedStreams,
                                startPositionMs = data.startPositionMs,
                                skip = data.sources.skip,
                                skipTimingStatus = data.skipTimingStatus,
                                skipReport = data.skipReport,
                                onReportSkipTiming = onReportSkipTiming,
                                autoAdvanced = data.autoAdvanced,
                                onPreviousEpisode = onPrev,
                                onNextEpisode = onNext,
                                onAutoAdvance = onAutoAdvance,
                                hasPreviousEpisode = data.hasPrev,
                                hasNextEpisode = data.canAdvance,
                                focusPlayerOnStart = fullscreen,
                                isFullscreen = fullscreen,
                                onToggleFullscreen = onToggleFullscreen,
                                onFullscreenChanged = onFullscreenChanged,
                                onProgress = onProgress,
                                onPlaybackError = onPlaybackError,
                                onPlaybackStopperChanged = onPlaybackStopperChanged,
                                onControlsVisibilityChanged = onControlsVisibilityChanged,
                                episodes = if (isMovie) emptyList() else data.episodes,
                                currentIndex = data.currentIndex,
                                artworkUrl = data.artworkUrl,
                                seriesTitle = data.seriesTitle,
                                episodeTitle = if (isMovie) {
                                    data.seriesTitle
                                } else {
                                    data.current.distinctTitle ?: "Episode ${data.current.displayNumber}"
                                },
                                onSelectEpisode = onSelectEpisode,
                                onAddToList = { listStatusDialogVisible = true },
                                audioSwitch = playerAudioSwitch,
                                onAudioSwitch = { onChangeSource(it.provider, it.variant.route) },
                                onPlaybackActiveChanged = { playerHasPicture = it },
                                category = data.sourceVariant.route,
                                fillerSkip = fillerSkip,
                            )
                            // Embed players often use CSS "web fullscreen" that never reaches the
                            // WebView fullscreen callback, so the app provides its own toggle. On
                            // touch and on TV alike it lives in the player's own control bar --
                            // TV once kept a second copy pinned to this corner, on the belief that
                            // a remote could not summon that bar while the video owned focus. It
                            // can: the WebView's dispatchKeyEvent override answers Up, Down and OK
                            // whatever the page has focused, and the row it opens carries the same
                            // toggle. The corner copy was drawn unconditionally, so unlike every
                            // other control it never timed out -- a static white glyph parked over
                            // the picture for the whole episode.
                        }
                    else -> PlayerSurface(
                        externalPlaybackSession = externalPlaybackSession,
                        stream = stream,
                        qualityStreams = nativeQualityStreams,
                        subtitles = data.sources.subtitles,
                        subtitleOffsetMs = data.sources.subtitleOffsetMs,
                        skip = data.sources.skip,
                        skipTimingStatus = data.skipTimingStatus,
                        skipReport = data.skipReport,
                        onReportSkipTiming = onReportSkipTiming,
                        seriesTitle = data.seriesTitle,
                        episodeTitle = if (isMovie) {
                            data.seriesTitle
                        } else {
                            "Episode ${data.current.displayNumber}" +
                                (data.current.title?.let { ": $it" } ?: "")
                        },
                        pauseDescription = pauseDescription,
                        sourceOptions = playerSourceOptions,
                        isLoadingSources = data.isLoadingMoreSources,
                        audioSwitch = playerAudioSwitch,
                        onAudioSwitch = { onChangeSource(it.provider, it.variant.route) },
                        artworkUrl = data.artworkUrl,
                        animeId = data.anilistId,
                        provider = data.provider,
                        category = data.sourceVariant.route,
                        burnedInSubtitles = data.sourceVariant == SourceVariant.HARD_SUB,
                        episode = data.current.displayNumber,
                        autoAdvanced = data.autoAdvanced,
                        onEnded = {
                            if (!SleepTimer.consumeEpisodeEnd() &&
                                com.anilili.data.settings.SettingsStore.autoplay.value
                            ) {
                                onAutoAdvance()
                            }
                        },
                        onNextEpisode = onNext,
                        onAutoAdvance = onAutoAdvance,
                        onError = onPlaybackError,
                        onStalled = onPlaybackStalled,
                        modifier = Modifier.fillMaxSize(),
                        onToggleFullscreen = onToggleFullscreen,
                        startPositionMs = data.startPositionMs,
                        onProgress = onProgress,
                        onFinalProgress = onFinalProgress,
                        onPreviousEpisode = onPrev,
                        hasNextEpisode = data.canAdvance,
                        nextSeasonLabel = data.nextSeason?.label?.takeIf { movesIntoNextSeason },
                        nextEpisodeIndex = nextEpisodeIndex,
                        fillerSkip = fillerSkip,
                        hasPreviousEpisode = data.hasPrev,
                        focusPlayerOnStart = fullscreen,
                        isFullscreen = fullscreen,
                        episodes = if (isMovie) emptyList() else data.episodes,
                        currentIndex = data.currentIndex,
                        onSelectEpisode = onSelectEpisode,
                        onAddToList = { listStatusDialogVisible = true },
                        onControlsVisibilityChanged = onControlsVisibilityChanged,
                        onPlaybackActiveChanged = { playerHasPicture = it },
                    )
                }
            }
            }
            // Faded out rather than cut, and kept composed until the fade ends. A cut took the
            // loader away wherever its fill happened to be — usually part way, since most opens
            // are quicker than one pour — and a one-frame dip in the condition (the handover
            // between one player reporting and the next, see playerHasPicture) remounted it from
            // empty. While the fade is still running the loader just carries on.
            val loadingOverlayAlpha by animateFloatAsState(
                targetValue = if (loadingOverlayVisible) 1f else 0f,
                animationSpec = tween(if (loadingOverlayVisible) 150 else 280),
                label = "loadingOverlayAlpha",
            )
            if (loadingOverlayVisible || loadingOverlayAlpha > 0f) {
                // Naming what is being opened, rather than spinning over a black rectangle. Which
                // episode and which server it is trying are the two things worth knowing while a
                // source resolves, and they are exactly what tells a slow server apart from a stuck
                // one when it takes a while. Both are just as worth knowing in the half after the
                // resolve, when the server has been picked and it is the stream that is slow.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = loadingOverlayAlpha }
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        com.anilili.ui.components.WaterFillLogoIndicator(size = 72.dp)
                        Text(
                            text = data.seriesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Text(
                            text = listOfNotNull(
                                if (isMovie) null else "Episode ${data.current.displayNumber}",
                                data.provider,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.68f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (
                windowedBackArrowIsVisible(
                    fullscreen = fullscreen,
                    isTv = device.isTv,
                    hasStream = data.chosenStream != null,
                    loadingOverlayShowing = loadingOverlayVisible,
                    controlsVisible = playerControlsVisible,
                )
            ) {
                BackButton(onBack, Modifier.align(Alignment.TopStart))
            }
            } }
            }
        }

        if (fullscreen) return@Column

        if (!device.isTv) {
            MobileWatchDetails(
                data = data,
                saved = saved,
                onToggleSaved = onToggleSaved,
                onOpenAnime = onOpenAnime,
                episodeDownload = episodeDownload,
                hasOfflineCopy = hasOfflineCopy,
                downloadPreparing = downloadPreparing,
                canDownload = streamForDownload != null,
                canSaveToDevice = canSaveToDevice,
                onDownload = showDownloadDialog,
                focusRequester = sourceFocus,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                onRequestAllSources = onRequestAllSources,
                onRetryMiruroSources = onRetryMiruroSources,
                onSelectEpisode = onSelectEpisode,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            return@Column
        }

        // Browsing state for long-runners. A null range means "not chosen yet", which opens on
        // the block holding the episode that is actually playing.
        var episodeQuery by remember(data.anilistId) { mutableStateOf("") }
        var chosenBlockIndex by remember(data.anilistId) { mutableStateOf<Int?>(null) }
        val blocks = remember(data.episodes) { episodeBlocks(data.episodes) }
        // Episodes are selected by their index in the full list, so filtering has to keep a way
        // back to it. pipeId is already the list key, so it is unique by contract.
        val indexByPipeId = remember(data.episodes) {
            data.episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
        }
        val tvBlockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, data.current.number))
            .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
        val tvShownEpisodes = if (isMovie) {
            emptyList()
        } else if (episodeQuery.isNotBlank()) {
            filterEpisodes(data.episodes, episodeQuery)
        } else {
            blocks.getOrNull(tvBlockIndex)?.episodes.orEmpty()
        }
        val episodeRows = remember(tvShownEpisodes, device.episodeColumns) {
            tvShownEpisodes.chunked(device.episodeColumns)
        }
        val tvBrowserVisible = !isMovie && data.episodes.size > EPISODE_BROWSER_MIN_EPISODES
        val tvBrowserFocus = rememberTvFocusTarget()
        val tvHistory by LibraryStore.history.collectAsState()
        val tvResume = tvHistory.firstOrNull { it.anilistId == data.anilistId }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = tvEpisodeListState,
        ) {
            item {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    onOpenAnime = onOpenAnime,
                    episodeDownload = episodeDownload,
                    hasOfflineCopy = hasOfflineCopy,
                    downloadPreparing = downloadPreparing,
                    canDownload = streamForDownload != null,
                    canSaveToDevice = canSaveToDevice,
                    onDownload = showDownloadDialog,
                    focusRequester = summaryFocus,
                    nextFocusRequester = sourceFocus,
                    modifier = Modifier.padding(
                        start = device.pagePadding,
                        end = device.pagePadding,
                        top = 12.dp,
                        bottom = 6.dp,
                    ),
                )
            }
            item {
                SourceSelectors(
                    data = data,
                    onChangeSource = onChangeSource,
                    onChangeCategory = onChangeCategory,
                    onRequestAllSources = onRequestAllSources,
                    onRetryMiruroSources = onRetryMiruroSources,
                    focusRequester = sourceFocus,
                    onToggleFullscreen = onToggleFullscreen,
                    downFocus = tvBrowserFocus.takeIf { tvBrowserVisible },
                )
                data.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = device.pagePadding,
                            end = device.pagePadding,
                            bottom = 6.dp,
                        ),
                    )
                }
            }
            if (tvBrowserVisible) {
                item {
                    EpisodeBrowserBar(
                        blocks = blocks,
                        selectedBlockIndex = tvBlockIndex,
                        onSelectBlock = { chosenBlockIndex = it },
                        query = episodeQuery,
                        onQueryChange = { episodeQuery = it },
                        layout = EpisodeLayout.GRID,
                        onToggleLayout = {},
                        showLayoutToggle = false,
                        tvFocusTarget = tvBrowserFocus,
                        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 6.dp),
                    )
                }
            }
            itemsIndexed(episodeRows) { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding, vertical = 5.dp)
                        // Only the top row needs redirecting; the rest reach the bar through it.
                        .then(
                            if (rowIndex == 0 && tvBrowserVisible && tvBrowserFocus.isAttached) {
                                Modifier.focusProperties { up = tvBrowserFocus.requester }
                            } else {
                                Modifier
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { episode ->
                        val index = indexByPipeId[episode.pipeId] ?: -1
                        EpisodeNumberChip(
                            episode = episode,
                            selected = index == data.currentIndex,
                            watchedFraction = episodeWatchFraction(tvResume, episode.number),
                            modifier = Modifier.weight(1f),
                            onClick = { if (index >= 0) onSelectEpisode(index) },
                        )
                    }
                    repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (episodeQuery.isNotBlank() && episodeRows.isEmpty()) {
                item {
                    Text(
                        text = "No episode matches “$episodeQuery”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 12.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

/** Keep normal pages inset, but release fullscreen as soon as its landscape rotation completes. */
internal fun statusBarStaysOutsidePlayer(fullscreen: Boolean, windowIsLandscape: Boolean): Boolean =
    !fullscreen || !windowIsLandscape

/**
 * Whether the Back arrow drawn over the windowed player is on screen.
 *
 * The arrow is part of the player's chrome and leaves when the rest of it does, rather than sitting
 * over the picture by itself a few seconds into an episode once the transport has hidden itself.
 * Fullscreen has an arrow of its own inside the controls, so this one is not drawn there at all.
 *
 * It stays up wherever the pane behind it draws no chrome for it to leave with: the TV pane holds a
 * play button and no player at all, a title with no stream shows the "no source" panel, and a
 * resolve covers the pane with the loading overlay. In each of those the arrow is the only way out
 * that is on screen, and [controlsVisible] is stale anyway — it is whatever the last player that
 * ran happened to report on its way off screen.
 */
internal fun windowedBackArrowIsVisible(
    fullscreen: Boolean,
    isTv: Boolean,
    hasStream: Boolean,
    loadingOverlayShowing: Boolean,
    controlsVisible: Boolean,
): Boolean {
    if (fullscreen) return false
    val playerDrawsChrome = !isTv && hasStream && !loadingOverlayShowing
    return !playerDrawsChrome || controlsVisible
}

@Composable
private fun WatchEpisodeSummary(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onOpenAnime: () -> Unit,
    episodeDownload: EpisodeDownload?,
    hasOfflineCopy: Boolean,
    downloadPreparing: Boolean,
    canDownload: Boolean,
    canSaveToDevice: Boolean,
    onDownload: () -> Unit,
    focusRequester: FocusRequester? = null,
    nextFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isMovie = isMovieFormat(data.seriesFormat)
    val description = remember(data.anilistId, data.description) {
        data.description?.cleanAniListDescription().orEmpty()
    }
    val providerDownloadUrl = remember(data.sources.download) {
        data.sources.download?.trim()?.takeIf { url ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()
        }
    }
    var descriptionExpanded by remember(data.anilistId, data.current.number) { mutableStateOf(false) }
    val canExpand = description.length > 180
    val localMoreFocus = remember { FocusRequester() }
    val localHeartFocus = remember { FocusRequester() }
    val moreFocus = if (canExpand) focusRequester ?: localMoreFocus else localMoreFocus
    val heartFocus = if (!canExpand) focusRequester ?: localHeartFocus else localHeartFocus
    val focusScope = rememberCoroutineScope()
    var restoreHeartFocus by remember { mutableStateOf(false) }
    val toggleSaved = {
        restoreHeartFocus = nextFocusRequester != null
        onToggleSaved()
    }

    LaunchedEffect(saved) {
        if (restoreHeartFocus) {
            // LibraryStore publishes a new list after the toggle. Restore the TV target after
            // that recomposition instead of allowing focus to fall back to the player/back key.
            delay(60)
            runCatching { heartFocus.requestFocus() }
            restoreHeartFocus = false
        }
    }

    Column(modifier = modifier.fillMaxWidth().focusGroup()) {
        Text(
            text = if (isMovie) {
                data.seriesTitle
            } else {
                data.current.title?.takeIf { it.isNotBlank() }
                    ?: "Episode ${data.current.displayNumber}"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildList {
                data.averageScore?.let { add("$it% score") }
                if (!isMovie) add("Episode ${data.current.displayNumber}")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )

        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (canExpand) {
                val fillFocus2 = rememberFocusFill()
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .focusRequester(moreFocus)
                        .onPreviewKeyEvent { event ->
                            val keyCode = event.nativeKeyEvent.keyCode
                            if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                            ) {
                                if (event.type == KeyEventType.KeyUp) {
                                    descriptionExpanded = !descriptionExpanded
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        runCatching { heartFocus.requestFocus() }
                                    }
                                }
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                        .focusFill(fillFocus2, RoundedCornerShape(16.dp))
                        .background(fillFocus2.background(Color.Transparent), RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .semantics {
                            onClick(label = if (descriptionExpanded) "Show less" else "Show more") {
                                descriptionExpanded = !descriptionExpanded
                                true
                            }
                        }
                        .pointerInput(descriptionExpanded) {
                            detectTapGestures { descriptionExpanded = !descriptionExpanded }
                        }
                        .focusable()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (descriptionExpanded) "Less" else "More",
                        style = MaterialTheme.typography.labelLarge,
                        color = fillFocus2.content(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = if (description.isBlank()) 10.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tapping the series identity leaves the episode for the anime's detail page;
            // the action buttons beside it keep their own click targets.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open ${data.seriesTitle}", onClick = onOpenAnime)
                    .focusHighlight(RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            AsyncImage(
                model = data.artworkUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = data.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                data.popularity?.let { popularity ->
                    Text(
                        text = "${compactMemberCount(popularity)} members",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
            val hasNativeDownloadAction = canDownload || hasOfflineCopy || downloadPreparing
            if (providerDownloadUrl != null) {
                val controlFocus5 = rememberFocusFill()
                IconButton(
                    colors = controlFocus5.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(providerDownloadUrl)),
                            )
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                AnililiError.DOWNLOAD_OPTIONS_UNOPENABLE.report(cause = error).withCode(),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    modifier = Modifier.focusFill(controlFocus5, CircleShape),
                ) {
                    Icon(
                        // Always the leaving-the-app icon: this opens the provider's own download
                        // page in a browser. Showing a download glyph when the app itself cannot
                        // save the episode (embeds) read as "save offline" and sent people to
                        // Chrome instead.
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open the provider's download page in a browser",
                        tint = controlFocus5.content(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (hasNativeDownloadAction) {
                val appDownloadCanStart = episodeDownload == null ||
                    episodeDownload.state == EpisodeDownloadState.FAILED
                val downloadEnabled = episodeDownload != null || hasOfflineCopy || (
                    canDownload && !downloadPreparing && (canSaveToDevice || appDownloadCanStart)
                )
                val downloadDescription = when {
                    downloadPreparing -> "Preparing episode download"
                    hasOfflineCopy && episodeDownload?.state != EpisodeDownloadState.DOWNLOADING -> "Episode downloaded"
                    episodeDownload?.state == EpisodeDownloadState.DOWNLOADING -> {
                        val progress = episodeDownload.percent?.toInt()
                        if (progress != null) "Downloading episode, $progress percent"
                        else "Downloading episode"
                    }
                    episodeDownload?.state == EpisodeDownloadState.QUEUED -> "Episode download queued"
                    episodeDownload?.state == EpisodeDownloadState.FAILED -> "Retry episode download"
                    episodeDownload?.state == EpisodeDownloadState.REMOVING -> "Removing episode download"
                    else -> "Download episode"
                }
                val controlFocus6 = rememberFocusFill()
                IconButton(
                    colors = controlFocus6.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                    onClick = onDownload,
                    enabled = downloadEnabled,
                    modifier = Modifier.focusFill(controlFocus6, CircleShape),
                ) {
                    when {
                        downloadPreparing || episodeDownload?.state == EpisodeDownloadState.DOWNLOADING -> {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .semantics { contentDescription = downloadDescription },
                                strokeWidth = 2.dp,
                            )
                        }
                        hasOfflineCopy && episodeDownload?.state != EpisodeDownloadState.DOWNLOADING -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = downloadDescription,
                                tint = controlFocus6.content(MaterialTheme.colorScheme.primary),
                            )
                        }
                        else -> Icon(
                            Icons.Default.Download,
                            contentDescription = downloadDescription,
                        )
                    }
                }
            }
            val fillFocus4 = rememberFocusFill()
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = if (saved) "Remove from list" else "Add to list"
                        onClick(label = if (saved) "Remove from list" else "Add to list") {
                            toggleSaved()
                            true
                        }
                    }
                    .focusRequester(heartFocus)
                    .onPreviewKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        ) {
                            if (event.type == KeyEventType.KeyUp) toggleSaved()
                            return@onPreviewKeyEvent true
                        }
                        when {
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP && canExpand -> {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        runCatching { moreFocus.requestFocus() }
                                    }
                                }
                                true
                            }
                            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN && nextFocusRequester != null -> {
                                if (event.type == KeyEventType.KeyUp) {
                                    focusScope.launch {
                                        delay(32)
                                        runCatching { nextFocusRequester.requestFocus() }
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .focusFill(fillFocus4, CircleShape)
                    .background(fillFocus4.background(Color.Transparent), CircleShape)
                    .clip(CircleShape)
                    .pointerInput(saved) { detectTapGestures { toggleSaved() } }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = fillFocus4.content(if (saved) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }

}

@Composable
private fun EpisodeDownloadDialog(
    stream: StreamItem,
    isMovie: Boolean,
    seriesTitle: String,
    episodeLabel: String,
    artworkUrl: String?,
    quality: DownloadQuality,
    destination: DownloadDestination,
    canSaveToDevice: Boolean,
    alreadyInApp: Boolean,
    alreadyInDownloads: Boolean,
    canRemove: Boolean,
    onQualityChange: (DownloadQuality) -> Unit,
    onDestinationChange: (DownloadDestination) -> Unit,
    batchSize: Int,
    onBatchSizeChange: (Int) -> Unit,
    remainingCount: Int,
    fillerCount: Int,
    skipFillers: Boolean,
    onSkipFillersChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    // Both volumes have to hold the episode when the save location is a card: it is downloaded into
    // the app's library cache on internal storage first and only then rewrapped onto the card. They
    // do not have to hold the same *amount*, though, which is what taking the smaller of the two
    // free figures used to assume — with "Device Downloads" the cached copy is dropped as each MP4
    // is written, so internal storage holds one episode while the card holds the batch.
    val saveLocation = remember(context) { DownloadLocations.current(context) }
    val libraryFreeBytes = remember(context) { DownloadStorage.freeBytes(context) }
    val deviceFreeBytes = remember(saveLocation) {
        DownloadLocations.freeBytes(context, saveLocation)
    }
    val deviceSharesLibraryVolume = remember(saveLocation) {
        DownloadLocations.sharesVolume(context, saveLocation, context.filesDir)
    }
    val locationLabel = remember(saveLocation) { DownloadLocations.label(context, saveLocation) }
    val volumeLabel = remember(saveLocation) { DownloadLocations.volumeLabel(context, saveLocation) }
    // Recomputed per chosen resolution: the estimate, and therefore the warning, depends on it.
    val perEpisodeBytes = remember(quality) { DownloadStorage.estimatedEpisodeBytes(quality.maxHeight) }
    val batching = batchSize > 1 && remainingCount > 0
    val batchCount = if (batching) batchSize.coerceAtMost(remainingCount) else 1
    // The estimate has to follow the choice: twenty-five episodes is a different question to one.
    val estimatedBytes = perEpisodeBytes * (if (batching) batchCount.toLong() else 1L)
    val space = downloadSpacePlan(
        episodeCount = if (batching) batchCount else 1,
        perEpisodeBytes = perEpisodeBytes,
        keepsLibraryCopy = destination.includesApp,
        savesToDevice = destination.includesDevice && canSaveToDevice,
        libraryFreeBytes = libraryFreeBytes,
        deviceFreeBytes = deviceFreeBytes,
        deviceVolumeLabel = volumeLabel,
        deviceSharesLibraryVolume = deviceSharesLibraryVolume,
        headroomBytes = DownloadStorage.HEADROOM_BYTES,
    )
    val wontFit = space.wontFit
    val tight = space.tight
    /** Null when one volume holds everything, so the gauge has no second disk to distinguish it from. */
    val gaugeVolume = space.volumeLabel
    val confirmEnabled = (
        batching || !alreadyInApp || (canSaveToDevice && destination.includesDevice)
        ) && !wontFit
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Download",
                title = seriesTitle,
                metadata = episodeLabel,
                artworkUrl = artworkUrl,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightPrimaryButton(
                text = when {
                    wontFit -> "Not enough space"
                    tight -> "Download anyway"
                    batching -> "Download $batchCount episodes"
                    isMovie -> "Download movie"
                    else -> "Download episode"
                },
                enabled = confirmEnabled,
                onClick = onConfirm,
                modifier = Modifier.padding(top = 16.dp),
            )
            MidnightDialogFooter(
                dangerText = if (canRemove) "Remove download" else null,
                onDanger = onRemove.takeIf { canRemove },
                quietText = "Cancel",
                onQuiet = onDismiss,
            )
        },
    ) {
        MidnightSectionLabel("Quality")
        MidnightUnderlineSelector(
            options = DownloadQuality.entries,
            selected = quality,
            onSelect = onQualityChange,
            tabLabel = { if (it == DownloadQuality.BEST) "Best" else it.label },
        )
        MidnightHelper(
            if (stream.isHls) {
                "Best available rendition at or below the selected resolution."
            } else {
                "Direct files use the closest resolution this source offers."
            },
        )

        MidnightSectionLabel("Save to")
        if (canSaveToDevice) {
            DownloadDestination.entries.forEach { option ->
                MidnightChoiceRow(
                    title = option.label,
                    subtitle = when (option) {
                        DownloadDestination.APP_ONLY -> "Available offline inside the app"
                        DownloadDestination.DEVICE_ONLY -> "One MP4 in $locationLabel"
                        DownloadDestination.BOTH -> "MP4 in $locationLabel plus the library copy"
                    },
                    selected = destination == option,
                    enabled = option != DownloadDestination.APP_ONLY || !alreadyInApp,
                    onSelect = { onDestinationChange(option) },
                    // The quality row already holds the cursor; only one control may claim it.
                    claimInitialFocus = false,
                )
            }
        } else {
            MidnightInlineNotice("This source can only be kept in the Anilili offline library.")
        }

        if (remainingCount > 0 || fillerCount > 0) {
            MidnightSectionLabel("Episodes")
            if (fillerCount > 0 && !isMovie) {
                MidnightSettingSwitchRow(
                    title = "Skip filler episodes",
                    description = "$fillerCount left in this run are marked as filler",
                    checked = skipFillers,
                    onCheckedChange = onSkipFillersChange,
                )
            }
            MidnightChoiceRow(
                title = if (isMovie) "This movie" else "This episode",
                selected = !batching,
                onSelect = { onBatchSizeChange(1) },
                trailing = Formatter.formatShortFileSize(context, perEpisodeBytes),
                claimInitialFocus = false,
            )
            BulkEpisodeDownloads.BATCH_SIZES
                .filter { it < remainingCount }
                .forEach { size ->
                    MidnightChoiceRow(
                        title = "Next $size",
                        selected = batchCount == size,
                        onSelect = { onBatchSizeChange(size) },
                        trailing = Formatter.formatShortFileSize(context, perEpisodeBytes * size),
                        claimInitialFocus = false,
                    )
                }
            // Everything left in the season the browser is showing, priced in the trailing
            // value before anyone commits — a long-runner's "all" is genuinely enormous.
            if (remainingCount > 1 || batching) {
                MidnightChoiceRow(
                    title = if (remainingCount == 1) "The 1 eligible episode" else "All $remainingCount left",
                    selected = batching && batchCount == remainingCount,
                    onSelect = { onBatchSizeChange(remainingCount.coerceAtLeast(2)) },
                    trailing = Formatter.formatShortFileSize(context, perEpisodeBytes * remainingCount),
                    claimInitialFocus = false,
                )
            }
            if (batching) {
                MidnightHelper("Fetched one at a time — keep watching while it runs.")
                MidnightHelper("To choose exact episode numbers, use Download on the anime page.")
            }
        }

        MidnightSectionLabel("Storage")
        MidnightStorageStrip(
            sizeLabel = "≈ ${Formatter.formatShortFileSize(context, estimatedBytes)}",
            noteLabel = when {
                batching -> "$batchCount episodes"
                isMovie -> "This movie"
                else -> "This episode"
            },
            freeLabel = when {
                space.freeBytes <= 0L -> "Free space unreadable"
                // Named whenever a second volume is in play, so a figure that does not match the
                // one the viewer has in mind is at least attributable to a disk.
                gaugeVolume != null ->
                    "${Formatter.formatShortFileSize(context, space.freeBytes)} free on $gaugeVolume"
                else -> "${Formatter.formatShortFileSize(context, space.freeBytes)} free"
            },
            afterLabel = when {
                wontFit -> "${Formatter.formatShortFileSize(
                    context,
                    space.neededBytes - space.freeBytes,
                )} short"
                space.freeBytes > 0L -> "${Formatter.formatShortFileSize(
                    context,
                    (space.freeBytes - space.neededBytes).coerceAtLeast(0L),
                )} after"
                else -> ""
            },
            fillFraction = if (space.freeBytes > 0L) {
                space.neededBytes.toFloat() / space.freeBytes
            } else {
                0f
            },
            blocked = wontFit,
        )
        // A stick has a few GB total, so the count is a genuinely useful number there; on a
        // phone with 200 GB free it would just be noise.
        if (device.isTv && space.freeBytes > 0L && !wontFit) {
            MidnightHelper(
                "Room for about ${space.freeBytes / estimatedBytes.coerceAtLeast(1L)} more episodes",
            )
        }
        when {
            wontFit -> MidnightInlineNotice(
                when (space.volume) {
                    DownloadSpaceVolume.ONLY ->
                        "Not enough room — choose fewer episodes, a lower resolution, or free " +
                            "some storage."
                    DownloadSpaceVolume.SAVE_LOCATION ->
                        "Not enough room on $volumeLabel — choose fewer episodes, a lower " +
                            "resolution, or free some storage."
                    // Otherwise a download bound for a card is refused over the phone's storage
                    // with nothing on screen to say why the phone has anything to do with it.
                    DownloadSpaceVolume.LIBRARY_CACHE ->
                        "Not enough room on $LIBRARY_CACHE_VOLUME_LABEL — episodes are fetched " +
                            "there before being saved to $locationLabel, so a little room is " +
                            "needed on both. Free some storage or choose a lower resolution."
                },
                tone = MidnightNoticeTone.ERROR,
            )
            tight -> MidnightInlineNotice(
                if (gaugeVolume != null) {
                    "${gaugeVolume.replaceFirstChar(Char::uppercase)} is nearly full — the " +
                        "download may fail part-way or crowd out other apps."
                } else {
                    "Storage is nearly full — the download may fail part-way or crowd out other " +
                        "apps."
                },
                tone = MidnightNoticeTone.WARNING,
            )
        }
        if (alreadyInApp && canSaveToDevice) {
            MidnightInlineNotice(
                "Already in your offline library — this adds a device copy only.",
            )
        }
        // Otherwise the only clue would be a second numbered MP4 appearing later.
        if (alreadyInDownloads) {
            MidnightInlineNotice(
                "An MP4 already exists in Downloads — downloading again adds a second copy.",
            )
        }
    }
}

/** How a requested download compares with the room the device actually has. */
internal enum class DownloadSpaceVerdict { FITS, TIGHT, WONT_FIT }

/**
 * Three different answers, worth keeping apart.
 *
 * [TIGHT][DownloadSpaceVerdict.TIGHT] is "this fits, but leaves the device short" — the viewer is
 * warned and may still go ahead, because only they know what else they are about to need the space
 * for. [WONT_FIT][DownloadSpaceVerdict.WONT_FIT] is "this cannot fit at all", where starting buys
 * nothing but a part-written file and a failure notice later, so the download is refused up front.
 *
 * A [freeBytes] of zero means the free space could not be read rather than that the disk is
 * genuinely full, so it never blocks a download: refusing on a failed measurement would strand
 * someone with plenty of room and no way to proceed.
 */
internal fun downloadSpaceVerdict(
    freeBytes: Long,
    estimatedBytes: Long,
    headroomBytes: Long,
): DownloadSpaceVerdict = when {
    freeBytes <= 0L -> DownloadSpaceVerdict.FITS
    freeBytes < estimatedBytes -> DownloadSpaceVerdict.WONT_FIT
    freeBytes <= estimatedBytes + headroomBytes -> DownloadSpaceVerdict.TIGHT
    else -> DownloadSpaceVerdict.FITS
}

private fun String.cleanAniListDescription(): String =
    replace(Regex.cached("<[^>]+>"), " ")
        .replace(Regex.cached("\\s+"), " ")
        .trim()

@Composable
private fun MobileWatchDetails(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onOpenAnime: () -> Unit,
    episodeDownload: EpisodeDownload?,
    hasOfflineCopy: Boolean,
    downloadPreparing: Boolean,
    canDownload: Boolean,
    canSaveToDevice: Boolean,
    onDownload: () -> Unit,
    focusRequester: FocusRequester,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val isMovie = isMovieFormat(data.seriesFormat)
    val pad = device.pagePadding
    val historyEntries by LibraryStore.history.collectAsState()
    val resume = historyEntries.firstOrNull { it.anilistId == data.anilistId }
    // Every episode row asks the same question, so resolve the three state sources once per list
    // rather than once per row.
    val badgeContext = LocalContext.current
    val downloadIndex by EpisodeDownloads.downloads(badgeContext).collectAsState()
    val exportStatuses by EpisodeExport.statuses(badgeContext).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(badgeContext).collectAsState()
    val downloadBadges = remember(downloadIndex, exportStatuses, exportedEpisodes) {
        episodeDownloadBadges(downloadIndex, exportStatuses, exportedEpisodes)
    }
    // Browsing state for long-runners; a null range opens on the block that is playing.
    val episodeLayout by SettingsStore.episodeLayout.collectAsState()
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    var episodeQuery by remember(data.anilistId) { mutableStateOf("") }
    var chosenBlockIndex by remember(data.anilistId) { mutableStateOf<Int?>(null) }
    val blocks = remember(data.episodes) { episodeBlocks(data.episodes) }
    // Episodes are selected by index into the full list, so filtering must keep a way back to it.
    val indexByPipeId = remember(data.episodes) {
        data.episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
    }
    val blockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, data.current.number))
        .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    val shownEpisodes = if (isMovie) {
        emptyList()
    } else if (episodeQuery.isNotBlank()) {
        filterEpisodes(data.episodes, episodeQuery)
    } else {
        blocks.getOrNull(blockIndex)?.episodes.orEmpty()
    }
    LazyColumn(modifier = modifier) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    onOpenAnime = onOpenAnime,
                    episodeDownload = episodeDownload,
                    hasOfflineCopy = hasOfflineCopy,
                    downloadPreparing = downloadPreparing,
                    canDownload = canDownload,
                    canSaveToDevice = canSaveToDevice,
                    onDownload = onDownload,
                    modifier = Modifier.padding(start = pad, end = pad, top = 16.dp, bottom = 4.dp),
                )
                // Previous/Next pills removed: the player transport and the episode list below
                // already cover episode navigation.
            }
            SourceSelectors(
                data = data,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                onRequestAllSources = onRequestAllSources,
                onRetryMiruroSources = onRetryMiruroSources,
                focusRequester = focusRequester,
            )
            data.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = pad, vertical = 5.dp),
                )
            }
            BulkDownloadStatus(modifier = Modifier.padding(horizontal = pad))
            if (!isMovie) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "  ${data.episodes.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Any series at all gets the layout toggle; only a long one also gets a range picker and a
        // filter field to go with it.
        if (!isMovie && data.episodes.isNotEmpty()) {
            item {
                EpisodeBrowserBar(
                    blocks = blocks,
                    selectedBlockIndex = blockIndex,
                    onSelectBlock = { chosenBlockIndex = it },
                    query = episodeQuery,
                    onQueryChange = { episodeQuery = it },
                    layout = episodeLayout,
                    onToggleLayout = { SettingsStore.setEpisodeLayout(episodeLayout.toggled()) },
                    showFilter = data.episodes.size > EPISODE_BROWSER_MIN_EPISODES,
                    modifier = Modifier.padding(horizontal = pad).padding(bottom = 6.dp),
                )
            }
        }
        when {
            shownEpisodes.isNotEmpty() && episodeLayout == EpisodeLayout.GRID -> items(
                shownEpisodes.chunked(device.episodeColumns),
                key = { row -> row.first().pipeId },
            ) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { episode ->
                        val index = indexByPipeId[episode.pipeId] ?: -1
                        EpisodeNumberChip(
                            episode = episode,
                            selected = index == data.currentIndex,
                            watchedFraction = episodeWatchFraction(resume, episode.number),
                            modifier = Modifier.weight(1f),
                            onClick = { if (index >= 0) onSelectEpisode(index) },
                        )
                    }
                    repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            shownEpisodes.isNotEmpty() -> items(shownEpisodes, key = EpisodeItem::pipeId) { episode ->
                val index = indexByPipeId[episode.pipeId] ?: -1
                MobileEpisodeRow(
                    episode = episode,
                    fallbackImage = episodeArtworkFallback(shownEpisodes, data.artworkUrl),
                    blurred = blurEpisodeImages,
                    selected = index == data.currentIndex,
                    watchedFraction = episodeWatchFraction(resume, episode.number),
                    downloadState = downloadBadges[
                        EpisodeDownloads.idFor(data.anilistId, data.category.api, episode.displayNumber)
                    ],
                    onClick = { if (index >= 0) onSelectEpisode(index) },
                )
            }
            episodeQuery.isNotBlank() -> item {
                Text(
                    text = "No episode matches “$episodeQuery”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = pad, vertical = 12.dp),
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun MobileEpisodeRow(
    episode: EpisodeItem,
    fallbackImage: String?,
    blurred: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    watchedFraction: Float = 0f,
    downloadState: EpisodeDownloadUi? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 5.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp)),
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
            com.anilili.ui.components.WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
            DownloadCoverBadge(
                state = downloadState,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)),
                compact = true,
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            val title = episode.distinctTitle
            Text(
                text = title ?: "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only worth a second line when it says something the first one didn't: providers
            // without real episode titles already have the number above.
            val subtitle = when {
                selected -> "Now playing"
                title != null -> "Episode ${episode.displayNumber}"
                else -> null
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Compact server and audio selectors. Choosing a server also makes it the global preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelectors(
    data: WatchData,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    focusRequester: FocusRequester,
    onToggleFullscreen: (() -> Unit)? = null,
    // Where the D-pad should go when leaving this row downwards. The episode browser below is
    // invisible to 2D focus search, so it has to be named explicitly.
    downFocus: TvFocusTarget? = null,
    /**
     * Whether to draw the row of pills at all.
     *
     * Cleared on a television, where the player owns the whole screen and the picker is mounted
     * only to own its dialog — the control that opens it lives in the player's own button row.
     */
    showRow: Boolean = true,
    /**
     * Bumped by a caller to open the server dialog. Compared against the value last acted on, so
     * that a recomposition cannot reopen a dialog the viewer has just closed.
     */
    openRequest: Int = 0,
) {
    val device = LocalAppDeviceProfile.current
    val preferredSourceLanguages by SettingsStore.preferredSourceLanguages.collectAsState()
    val preferredLanguageLabels = remember(preferredSourceLanguages) {
        preferredSourceLanguages.mapTo(linkedSetOf()) { it.label }
    }
    val visibleLanguages = remember(data.knownLanguages, preferredLanguageLabels) {
        visibleSourceLanguages(data.knownLanguages, preferredLanguageLabels)
    }
    var audioFilter by rememberSaveable { mutableStateOf(AudioFilter.ANY) }
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // A language that no longer appears (different episode, different servers) would otherwise
    // silently filter everything away with no visible cause.
    LaunchedEffect(visibleLanguages) {
        if (languageFilter != null && languageFilter !in visibleLanguages) languageFilter = null
    }
    val filteredOptions = remember(data.sourceOptions, data.capabilities, audioFilter, languageFilter) {
        filterSourceOptions(data.sourceOptions, data.capabilities, audioFilter, languageFilter)
    }
    val sourceChoices = filteredOptions
    val audioForServer = remember(data.sourceOptions, data.provider) {
        data.sourceOptions.filter { it.provider == data.provider }.map { it.category }.distinct()
    }
    var showServerDialog by remember { mutableStateOf(false) }
    val mobileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var lastOpenRequest by remember { mutableIntStateOf(openRequest) }
    LaunchedEffect(openRequest) {
        if (openRequest != lastOpenRequest) {
            lastOpenRequest = openRequest
            onRequestAllSources()
            showServerDialog = true
        }
    }

    if (showRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp)
            .then(
                downFocus?.takeIf { it.isAttached }?.let { target ->
                    Modifier.focusProperties { down = target.requester }
                } ?: Modifier,
            )
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Server also carries the TV focus anchor for leaving fullscreen.
        CompactClickablePill(
            label = ProviderCatalog.label(data.provider) +
                if (data.provider == data.preferredProvider) " ★" else "",
            downloadable = sourceOffersDownload(data.provider, data.sourceVariant, data.capabilities),
            enabled = sourceChoices.isNotEmpty(),
            focusRequester = focusRequester,
            onClick = {
                onRequestAllSources()
                showServerDialog = true
            }
        )
        val alternateAudio = audioForServer.firstOrNull { it != data.category }
        CompactClickablePill(
            label = data.sourceVariant.label,
            enabled = alternateAudio != null,
            active = data.category == com.anilili.data.model.Category.DUB,
            showArrow = false,
            onClick = { alternateAudio?.let { onChangeCategory(it.api) } },
        )
        if (device.isTv && onToggleFullscreen != null) {
            CompactClickablePill(
                label = "Fullscreen",
                enabled = true,
                showArrow = false,
                onClick = onToggleFullscreen,
            )
        }
        if (data.isLoadingMoreSources || data.isRetryingMiruro) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (data.isRetryingMiruro) "Reconnecting Miruro…" else "More servers…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }

    if (showServerDialog && !device.isTv) {
        ModalBottomSheet(
            onDismissRequest = { showServerDialog = false },
            sheetState = mobileSheetState,
            containerColor = PlayerPanelSurface,
            contentColor = Color.White,
        ) {
            PlayerPanelTheme {
            MobileServerPickerContent(
                data = data,
                options = sourceChoices,
                languages = visibleLanguages,
                preferredLanguages = preferredLanguageLabels,
                audio = audioFilter,
                onAudioChange = { audioFilter = it },
                language = languageFilter,
                onLanguageChange = { languageFilter = it },
                onSelect = { option ->
                    showServerDialog = false
                    if (option.provider != data.provider || option.variant != data.sourceVariant) {
                        onChangeSource(option.provider, option.variant.route)
                    }
                },
                onRetryMiruroSources = onRetryMiruroSources,
                onClose = { showServerDialog = false },
            )
            }
        }
    }

    if (showServerDialog && device.isTv) {
        val closeServerPanel = {
            showServerDialog = false
            runCatching { focusRequester.requestFocus() }
        }
        // A window of its own, because on television this picker is mounted off-screen (see the
        // hidden SourceSelectors in WatchScreen) and has to draw over the fullscreen player; inside
        // it, the same right-edge panel as the player's captions, settings and episode pickers.
        Dialog(
            onDismissRequest = { closeServerPanel() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            // The panel lays its own scrim over the picture; the window's would double it.
            (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
            PlayerPanelTheme {
                PlayerOptionsSheet(
                    title = "Servers",
                    paneTitle = "Server for this episode",
                    closeDescription = "Close servers",
                    onDismiss = { closeServerPanel() },
                    fullscreen = true,
                ) {
                    TvServerPanelSections(
                        data = data,
                        options = sourceChoices,
                        preferredLanguages = preferredLanguageLabels,
                        visibleLanguages = visibleLanguages,
                        audio = audioFilter,
                        onAudioChange = { audioFilter = it },
                        language = languageFilter,
                        onLanguageChange = { languageFilter = it },
                        onSelect = { option ->
                            closeServerPanel()
                            if (option.provider != data.provider || option.variant != data.pickerVariant) {
                                onChangeSource(option.provider, option.variant.route)
                            }
                        },
                        onRetryMiruroSources = onRetryMiruroSources,
                    )
                }
            }
        }
    }
}

/**
 * The television server picker's body, in the Settings page's dress: filters, then one row per
 * server that says what it is on its second line.
 *
 * It was a four-column grid of tiles in a card in the middle of the screen, too narrow for a
 * server's name and languages — hence the callout that floated the focused tile's full text over
 * it. A row has the width for all of it, and the panel it sits in is the one every other picker in
 * the player uses.
 */
@Composable
private fun ColumnScope.TvServerPanelSections(
    data: WatchData,
    options: List<WatchSourceOption>,
    preferredLanguages: Set<String>,
    visibleLanguages: List<String>,
    audio: AudioFilter,
    onAudioChange: (AudioFilter) -> Unit,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    onSelect: (WatchSourceOption) -> Unit,
    onRetryMiruroSources: () -> Unit,
) {
    MidnightSectionLabel("Show")
    SourceFilterRow(
        audio = audio,
        onAudioChange = onAudioChange,
        languages = visibleLanguages,
        language = language,
        onLanguageChange = onLanguageChange,
        stillChecking = !languageFilterIsComplete(data.sourceOptions, data.capabilities),
    )
    MidnightHelper(
        when {
            data.isRetryingMiruro -> "Reconnecting Miruro… ${options.size} choices available"
            data.isLoadingMoreSources -> "Finding sources… ${options.size} so far"
            data.preferredProvider == "auto" -> "Applies to this episode only"
            else -> "This episode only · Settings pins ${ProviderCatalog.label(data.preferredProvider)}"
        },
    )

    MidnightSectionLabel("Server")
    if (options.isEmpty()) {
        MidnightInlineNotice(
            if (data.isLoadingMoreSources) "Looking for servers that carry this episode…"
            else "No server matches these filters.",
        )
    }
    ServerChoiceRows(data, options, preferredLanguages, onSelect)

    if (!data.hasMiruroSources) {
        MidnightSectionLabel("More servers")
        MidnightSettingRow(
            title = if (data.isRetryingMiruro) "Retrying Miruro…" else "Retry Miruro servers",
            description = "Ask Miruro again for its servers",
            enabled = !data.isRetryingMiruro,
            busy = data.isRetryingMiruro,
            onClick = onRetryMiruroSources,
        )
    }
}

/**
 * One row per server, saying on its second line what it is: whether it is the one playing, sub or
 * dub, which subtitle languages, whether it has a signs track, whether it is fast and whether it can
 * be saved.
 *
 * Shared by the television panel and the phone sheet. The phone used to draw a two-column grid of
 * tiles with the name, two icons and a language badge, which dropped most of that line — the part
 * viewers actually choose by — and the two pickers had drifted apart describing the same servers.
 */
@Composable
private fun ServerChoiceRows(
    data: WatchData,
    options: List<WatchSourceOption>,
    preferredLanguages: Set<String>,
    onSelect: (WatchSourceOption) -> Unit,
) {
    // The server already playing leads the list, so the picker opens on what is on screen now and
    // everything below it is an alternative. A stable sort keeps the rest in their usual order.
    val isPlaying = { option: WatchSourceOption ->
        option.provider == data.provider && option.variant == data.pickerVariant
    }
    options.sortedByDescending(isPlaying).forEach { option ->
        val selected = isPlaying(option)
        MidnightChoiceRow(
            title = serverTitle(option, data),
            subtitle = serverDescription(option, data, preferredLanguages, selected),
            selected = selected,
            // The television panel puts the remote on its close button, as every player panel does.
            claimInitialFocus = false,
            // Faded, not removed: a server that just failed may well work on a second try.
            modifier = Modifier.alpha(if (option.recentlyFailed && !selected) 0.5f else 1f),
            onSelect = { onSelect(option) },
        )
    }
}

/** A server's name as every picker shows it, starred when Settings pins it. */
private fun serverTitle(option: WatchSourceOption, data: WatchData): String =
    ProviderCatalog.label(option.provider) + if (option.provider == data.preferredProvider) " ★" else ""

/**
 * The second line of a server row: what a viewer chooses a server by. One builder for every picker
 * — the television panel, the phone's sheet under the video and the player's own Sources sheet —
 * because the phone's two used to say far less than the television about the very same servers.
 */
private fun serverDescription(
    option: WatchSourceOption,
    data: WatchData,
    preferredLanguages: Set<String>,
    selected: Boolean,
): String {
    val server = option.provider
    val languages = visibleSourceLanguages(data.languagesOf(option).sorted(), preferredLanguages)
    return listOfNotNull(
        "Playing now".takeIf { selected },
        option.variant.spokenLabel(),
        languages.joinToString(", ").ifEmpty { null },
        "Has signs".takeIf { sourceOffersSigns(server, option.variant, data.capabilities) },
        "Fast".takeIf { ProviderCatalog.isFast(server) },
        "Can download".takeIf { sourceOffersDownload(server, option.variant, data.capabilities) },
        "Failed a moment ago".takeIf { option.recentlyFailed && !selected },
    ).joinToString(" · ")
}

/** A variant as a person would say it, for the second line of a server row. */
private fun SourceVariant.spokenLabel(): String = when (this) {
    SourceVariant.GENERIC_SUB -> "Subtitled"
    SourceVariant.HARD_SUB -> "Subtitles in the picture"
    SourceVariant.SOFT_SUB -> "Subtitles you can switch"
    SourceVariant.DUB -> "Dubbed"
}

@Composable
private fun MobileServerPickerContent(
    data: WatchData,
    options: List<WatchSourceOption>,
    languages: List<String>,
    preferredLanguages: Set<String>,
    audio: AudioFilter,
    onAudioChange: (AudioFilter) -> Unit,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    onSelect: (WatchSourceOption) -> Unit,
    onRetryMiruroSources: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Server for this episode",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (data.isLoadingMoreSources || data.isRetryingMiruro) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                text = if (data.isRetryingMiruro) {
                        "Reconnecting Miruro… ${options.size} choices available"
                    } else if (data.isLoadingMoreSources) {
                        "Finding sources… ${options.size} so far"
                    } else if (data.preferredProvider == "auto") {
                        "Applies to this episode only · ${options.size} choices available"
                    } else {
                        "This episode only · Settings pins ${ProviderCatalog.label(data.preferredProvider)} · " +
                            "${options.size} choices available"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SourceFilterRow(
            audio = audio,
            onAudioChange = onAudioChange,
            languages = languages,
            language = language,
            onLanguageChange = onLanguageChange,
            stillChecking = !languageFilterIsComplete(data.sourceOptions, data.capabilities),
        )

        ScrollHintColumn(
            fadeColor = PlayerPanelSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ServerChoiceRows(data, options, preferredLanguages, onSelect)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!data.hasMiruroSources) {
                val extraFocus4 = rememberFocusFill()
                TextButton(
                    colors = extraFocus4.buttonColors(ButtonDefaults.textButtonColors()),
                    onClick = onRetryMiruroSources,
                    enabled = !data.isRetryingMiruro, modifier = Modifier.focusFill(extraFocus4, RoundedCornerShape(24.dp)),) {
                    Text(
                        if (data.isRetryingMiruro) "Retrying Miruro…"
                        else "Retry Miruro servers",
                    )
                }
            }
            val extraFocus5 = rememberFocusFill()
            TextButton(
                colors = extraFocus5.buttonColors(ButtonDefaults.textButtonColors()),onClick = onClose, modifier = Modifier.focusFill(extraFocus5, RoundedCornerShape(24.dp)),) {
                Text("Close")
            }
        }
    }
}

/** The height a pill with its 24dp dropdown arrow comes out at, so pills without one match it. */
private val CompactPillMinHeight = 40.dp

/** Compact bordered pill that triggers an onClick callback. */
@Composable
private fun CompactClickablePill(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    active: Boolean = false,
    showArrow: Boolean = true,
    downloadable: Boolean = false,
    onClick: () -> Unit,
) {
    val fillFocus7 = rememberFocusFill()
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!enabled || !activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusFill(fillFocus7, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                fillFocus7.background(if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface),
            )
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            // The short end padding is there for the dropdown arrow, whose glyph carries its own
            // margin. Without one ("SUB"/"DUB") the label sat left of centre, and the pill came out
            // shorter than the server pill beside it, which the 24dp arrow is what sizes.
            .heightIn(min = CompactPillMinHeight)
            .padding(start = 12.dp, end = if (showArrow) 6.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (downloadable) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Can be saved offline",
                tint = fillFocus7.content(if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.primary
                }),
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(15.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = fillFocus7.content(if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface),
        )
        if (showArrow) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = fillFocus7.content(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

/** Compact bordered pill that opens a dropdown menu. */
@Composable
private fun CompactDropdown(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val fillFocus8 = rememberFocusFill()
        Row(
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusFill(fillFocus8, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(fillFocus8.background(MaterialTheme.colorScheme.surface))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = fillFocus8.content(LocalContentColor.current))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = fillFocus8.content(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun NoSource(onWebFallback: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                AnililiError.SOURCE_SERVER_EMPTY.asFailure().withCode(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            val controlFocus15 = rememberFocusFill()
            TextButton(
                colors = controlFocus15.buttonColors(ButtonDefaults.textButtonColors()),
                onClick = onWebFallback,
                modifier = Modifier.focusFill(controlFocus15, RoundedCornerShape(20.dp)),
            ) { Text("Open in web player") }
        }
    }
}

@Composable
private fun EmbedEpisodeNavigationEffect(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val currentHasPrevious by rememberUpdatedState(hasPrevious)
    val currentHasNext by rememberUpdatedState(hasNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)

    DisposableEffect(Unit) {
        val navigator: (Int) -> Unit = { direction ->
            DiagnosticsLog.event("Embed player episode navigator direction=$direction")
            when {
                direction > 0 && currentHasNext -> currentOnNext()
                direction < 0 && currentHasPrevious -> currentOnPrevious()
            }
        }
        PlaybackService.episodeNavigator = navigator
        DiagnosticsLog.event("Embed player episode navigator registered hasPrev=$hasPrevious hasNext=$hasNext")
        onDispose {
            if (PlaybackService.episodeNavigator === navigator) {
                PlaybackService.episodeNavigator = null
            }
            DiagnosticsLog.event("Embed player episode navigator cleared")
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tvFocusPolicy = if (LocalAppDeviceProfile.current.isTv) {
        // A TV remote already has a dedicated Back key. Keeping this overlay out of the D-pad
        // graph prevents Up/Center from unexpectedly abandoning the episode controls.
        Modifier.focusProperties { canFocus = false }
    } else {
        Modifier
    }
    val controlFocus16 = rememberFocusFill()
    IconButton(
        colors = controlFocus16.iconButtonColors(IconButtonDefaults.iconButtonColors()),
        onClick = onBack,
        // The app draws edge-to-edge, so keep the button below the clock/battery area whenever
        // the status bar is visible (the inset is zero in fullscreen, where the bars are hidden).
        modifier = modifier
            .then(tvFocusPolicy)
            .statusBarsPadding()
            .padding(4.dp)
            .focusFill(controlFocus16, RoundedCornerShape(24.dp)),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = controlFocus16.content(Color.White),
        )
    }
}

/**
 * The episode label Back from the player hands to the anime page it lands on.
 *
 * Next and auto-play move the player on inside one Watch route, so [routeEpisode] — where the
 * session started — goes stale; [playingEpisode], the one on screen when the viewer left, wins
 * whenever there is one. A page for a different anime than the player's ([detailAnimeId] is not
 * [watchAnimeId]) gets null: an episode label means nothing on another anime's strip, and handing
 * it nothing also clears whatever label that page arrived with.
 */
internal fun episodeToHandBack(
    detailAnimeId: Int,
    watchAnimeId: Int,
    routeEpisode: String?,
    playingEpisode: String?,
): String? {
    if (detailAnimeId != watchAnimeId) return null
    return playingEpisode?.takeIf { it.isNotBlank() } ?: routeEpisode?.takeIf { it.isNotBlank() }
}

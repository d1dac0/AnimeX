package com.anilili.ui.watch

import com.anilili.ui.rethrowIfCancellation
import com.anilili.diagnostics.DiagnosticsLog
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.anilili.data.model.hasUsableWindow
import com.anilili.data.model.SkipTimes
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anilili.data.AppGraph
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.resumeFor
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.settings.SettingsStore
import com.anilili.errors.AnililiFailure
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.ExternalPlaybackSession
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.PlaybackService
import com.anilili.playback.SleepTimer
import com.anilili.playback.exportedSubtitleMimeType
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.offlinePlayableEpisodesFor
import com.anilili.playback.offlineSibling
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.nav.Routes

/** Full player entry point for an episode already persisted in Media3's download cache. */
@Composable
fun DownloadedEpisodeScreen(
    downloadId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val device = LocalAppDeviceProfile.current
    val downloads by EpisodeDownloads.downloads(context).collectAsState()
    val exported by EpisodeExport.exported(context).collectAsState()
    // Next/previous switch episodes in place, so the route argument is only the initial episode.
    var currentDownloadId by remember(downloadId) { mutableStateOf(downloadId) }
    val download = downloads.firstOrNull { it.id == currentDownloadId }
    val exportedEpisode = exported.firstOrNull { it.downloadId == currentDownloadId }
    var playbackError by remember(currentDownloadId) { mutableStateOf<AnililiFailure?>(null) }
    val leave = {
        // #61: television parks — pause, then stop — so the codec and the buffer are released for
        // whatever the viewer browses next; the phone keeps the loaded pause for the notification's
        // resume. Re-entering the episode re-prepares from the committed position either way.
        PlaybackService.pauseOrParkActivePlayback(AppGraph.isTv)
        onBack()
    }
    val autoplay by SettingsStore.autoplay.collectAsState()
    val playOnOpen by SettingsStore.playOnOpen.collectAsState()

    BackHandler(onBack = leave)
    DisposableEffect(Unit) {
        onDispose { PlaybackService.pauseOrParkActivePlayback(AppGraph.isTv) }
    }

    LaunchedEffect(Unit) {
        if (device.isTv) releaseImageMemoryForPlayback(context)
    }

    // Auto rotate downloaded video to fullscreen landscape without needing to rotate device manually
    DisposableEffect(Unit, device.isTv) {
        if (activity != null && !device.isTv) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    // Either copy will do. The cached download is preferred when both exist, because that is the
    // one the player already knows how to seek through with its persisted stream keys; an exported
    // MP4 is a plain content:// file, which the playback chain resolves through DefaultDataSource.
    val playable = remember(download, exportedEpisode) {
        when {
            download?.isComplete == true -> PlayableEpisode(
                stream = StreamItem(
                    url = download.uri,
                    type = if (download.isAdaptive) "hls" else "mp4",
                    quality = "Downloaded",
                    audio = download.metadata.category,
                    referer = download.metadata.referer,
                    isActive = true,
                    width = null,
                    height = null,
                    headers = download.metadata.headers,
                ),
                subtitles = EpisodeDownloads.localSubtitles(context, download.metadata),
                metadata = download.metadata,
            )
            exportedEpisode != null -> PlayableEpisode(
                stream = StreamItem(
                    url = exportedEpisode.uri,
                    type = "mp4",
                    quality = "Downloaded",
                    audio = exportedEpisode.metadata.category,
                    referer = null,
                    isActive = true,
                    width = null,
                    height = null,
                ),
                subtitles = exportedEpisode.subtitles.map {
                    SubtitleItem(
                        url = it.uri,
                        label = it.label,
                        language = it.language,
                        mimeType = exportedSubtitleMimeType(context, it),
                    )
                },
                metadata = exportedEpisode.metadata,
            )
            else -> null
        }
    }

    // The downloaded player used to be a dead end: no next/previous and no Continue Watching
    // trace. The offline library already knows every episode on this device, so adjacency and
    // history both come from it.
    val offlineLibrary = remember(downloads, exported) { offlineEpisodes(downloads, exported) }
    val currentEpisode = offlineLibrary.firstOrNull { it.id == currentDownloadId }
    val playableSeriesEpisodes = remember(currentEpisode, offlineLibrary) {
        currentEpisode?.let { offlinePlayableEpisodesFor(it, offlineLibrary) }.orEmpty()
    }
    val nextEpisode = currentEpisode?.let { offlineSibling(it, offlineLibrary, next = true) }
    val previousEpisode = currentEpisode?.let { offlineSibling(it, offlineLibrary, next = false) }
    // True while the episode on screen is one the player took itself at the end of the last one.
    // "Play when an episode opens" has no say over those; see [WatchData.autoAdvanced].
    var autoAdvanced by remember { mutableStateOf(false) }
    val switchTo: (OfflineEpisode) -> Unit = { target ->
        autoAdvanced = false
        currentDownloadId = target.id
        playbackError = null
    }
    val autoAdvanceTo: (OfflineEpisode) -> Unit = { target ->
        autoAdvanced = true
        currentDownloadId = target.id
        playbackError = null
    }
    val drawerEpisodes = remember(playableSeriesEpisodes) {
        playableSeriesEpisodes.mapNotNull { episode ->
            val number = episode.metadata.episodeNumber.trim().toDoubleOrNull() ?: return@mapNotNull null
            episode to EpisodeItem(
                pipeId = episode.id,
                number = number,
                title = episode.metadata.episodeTitle,
                image = episode.metadata.artworkUrl,
                filler = episode.metadata.filler,
            )
        }
    }
    // Its own list, kept: a fresh map each recomposition was a new argument PlayerSurface could
    // never skip over.
    val drawerItems = remember(drawerEpisodes) { drawerEpisodes.map { it.second } }
    val currentDrawerIndex = drawerEpisodes.indexOfFirst { (episode, _) -> episode.id == currentDownloadId }
    val metadataNumber = playable?.metadata?.episodeNumber?.trim()?.toDoubleOrNull()
    // Read once per episode, not collected: this screen's own progress save rewrites history every
    // eight seconds, and collecting it recomposed the whole of PlayerSurface each time with a new
    // history row and start position it had no use for mid-episode. The store is loaded in
    // Application.onCreate, so the read is never ahead of it.
    val currentHistory = remember(currentDownloadId, playable?.metadata?.anilistId) {
        playable?.metadata?.anilistId?.let(LibraryStore::historyFor)
    }
    val resumePositionMs = if (playable != null && metadataNumber != null) {
        offlineResumePosition(
            history = currentHistory,
            anilistId = playable.metadata.anilistId,
            episodeNumber = metadataNumber,
        )
    } else {
        0L
    }

    // Mirror the watch screen's history bookkeeping so offline watches land in Continue
    // Watching with their resume position instead of leaving no trace at all.
    val seedOfflineHistory: () -> Unit = seed@{
        val metadata = playable?.metadata ?: return@seed
        val number = metadata.episodeNumber.trim().toDoubleOrNull() ?: return@seed
        val existing = LibraryStore.historyFor(metadata.anilistId)
        LibraryStore.upsertHistory(
            HistoryEntry(
                anilistId = metadata.anilistId,
                title = metadata.seriesTitle,
                cover = metadata.artworkUrl,
                episodeNumber = number,
                episodeTitle = metadata.episodeTitle,
                provider = metadata.provider,
                category = metadata.category,
                positionMs = resumePositionMs,
                durationMs = existing?.resumeFor(number)?.durationMs?.takeIf { resumePositionMs > 0L } ?: 0L,
                watchedEpisodes = existing?.watchedEpisodes.orEmpty(),
                resumePoints = existing?.resumePoints.orEmpty(),
            ),
        )
    }
    // Opening the episode is enough while the player starts on its own. With "Play when an episode
    // opens" off, the row waits for playback below, so a look at a downloaded episode leaves the
    // My List alone.
    var offlineHistorySeeded by remember(currentDownloadId) { mutableStateOf(false) }
    LaunchedEffect(currentDownloadId, playable, playOnOpen, autoAdvanced) {
        // An auto-advanced episode plays whatever the switch says, so its row is owed on the same
        // terms as one opened with the switch on.
        if (!playOnOpen && !autoAdvanced) return@LaunchedEffect
        // Recorded as seeded so the playback path below leaves the row alone; re-seeding it
        // mid-episode would write back the position playback *started* from.
        offlineHistorySeeded = true
        seedOfflineHistory()
    }

    var lastProgressSave by remember { mutableLongStateOf(0L) }
    var progressStreamUrl by remember { mutableStateOf<String?>(null) }
    var progressReportsForStream by remember { mutableIntStateOf(0) }
    val locallyWatchedEpisodes = remember { mutableSetOf<Double>() }
    // The series' length, which the download record does not carry. Without it the finale of a
    // downloaded series counted as one more episode: Continue Watching kept the title on its last
    // episode for good while the account, which looks the length up itself, already said Completed.
    // Served from the metadata cache; with nothing cached offline it stays unknown, and the
    // account's confirmation takes the row off instead (ListProgressSyncWorker).
    val seriesLength by produceState<Int?>(initialValue = null, playable?.metadata?.anilistId) {
        val id = playable?.metadata?.anilistId ?: return@produceState
        value = runCatching { AppGraph.repository.animeInfo(id)?.episodes }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()
            ?.takeIf { it > 0 }
    }
    val currentSeriesLength by rememberUpdatedState(seriesLength)
    val currentMetadata by rememberUpdatedState(playable?.metadata)
    val currentNext by rememberUpdatedState(nextEpisode)
    val currentStreamUrl by rememberUpdatedState(playable?.stream?.url)

    var chromeVisible by remember { mutableStateOf(!device.isTv) }

    // Skip windows for the episode on screen: the ones saved with the download, or — for a copy
    // saved without them, including every download made before they were kept — the same AniSkip
    // lookup the watch page runs, once the player knows the file's length. That lookup is cached, so
    // a title looked up once while online still offers Skip offline afterwards.
    val scope = rememberCoroutineScope()
    var lookedUpSkip by remember(currentDownloadId) { mutableStateOf<SkipTimes?>(null) }
    var skipLookupAsked by remember(currentDownloadId) { mutableStateOf(false) }

    // Same window policy as WatchScreen, from the same place: a downloaded episode is played on a
    // full screen too, and the two would drift apart if each kept its own copy.
    PlayerSystemBarsEffect(
        activity = activity,
        immersive = true,
        controlsVisible = chromeVisible,
        isTv = device.isTv,
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            playbackError != null -> DownloadMessage(
                title = "Could not play download",
                // Carries the code because this screen is a dead end with nothing to retry: the
                // file is on the device and it did not play, and the code is the only part of that
                // a viewer can pass on. PLAY-05 has been the answer to a real fault before — see
                // the transmux repair in EpisodeExport.
                message = playbackError?.withCode().orEmpty(),
            )
            playable == null && download == null -> DownloadMessage(
                title = "Download not found",
                message = "It may have been removed from this device.",
            )
            playable == null -> DownloadMessage(
                title = "Episode is not ready",
                message = when (download?.state) {
                    EpisodeDownloadState.FAILED -> "The download failed. Remove it and try again from the watch page."
                    EpisodeDownloadState.REMOVING -> "This episode is being removed."
                    else -> download?.percent?.let { "Downloaded ${it.toInt()}%." }
                        ?: "The download is still in progress."
                },
            )
            else -> {
                val metadata = playable.metadata
                // The series' logo for the player's title and pause billing, as on the watch page.
                // Offline it simply fails, and the player sets the title in type.
                val seriesLogo by produceState<String?>(initialValue = null, metadata.anilistId) {
                    value = AppGraph.repository.seriesLogo(metadata.anilistId)
                }
                CompositionLocalProvider(LocalPlayerSeriesLogo provides seriesLogo) { PlayerAccentScope {
                PlayerSurface(
                    stream = playable.stream,
                    subtitles = playable.subtitles,
                    // Only an episode with a whole number can be counted, the same rule as below.
                    externalPlaybackSession = metadata.episodeNumber.trim().toDoubleOrNull()?.let { number ->
                        { durationMs ->
                            val nextMeta = nextEpisode?.metadata
                            ExternalPlaybackSession(
                                anilistId = metadata.anilistId,
                                episodeNumber = number,
                                durationMsHint = durationMs,
                                totalEpisodes = currentSeriesLength,
                                nextEpisode = nextMeta?.episodeNumber?.trim()?.toDoubleOrNull(),
                                nextEpisodeTitle = nextMeta?.episodeTitle,
                            )
                        }
                    },
                    skip = metadata.skip?.takeIf { it.hasUsableWindow() } ?: lookedUpSkip,
                    seriesTitle = metadata.seriesTitle,
                    episodeTitle = metadata.episodeTitle?.takeIf(String::isNotBlank)
                        ?: "Episode ${metadata.episodeNumber}",
                    artworkUrl = metadata.artworkUrl,
                    animeId = metadata.anilistId,
                    provider = metadata.provider,
                    category = metadata.category,
                    episode = metadata.episodeNumber,
                    autoAdvanced = autoAdvanced,
                    onEnded = {
                        if (!SleepTimer.consumeEpisodeEnd() && autoplay) nextEpisode?.let(autoAdvanceTo)
                    },
                    onNextEpisode = { nextEpisode?.let(switchTo) },
                    onAutoAdvance = {
                        if (!SleepTimer.consumeEpisodeEnd()) nextEpisode?.let(autoAdvanceTo)
                    },
                    onPreviousEpisode = { previousEpisode?.let(switchTo) },
                    onError = { failure, _, _ -> playbackError = failure },
                    modifier = Modifier.fillMaxSize(),
                    hasNextEpisode = nextEpisode != null,
                    hasPreviousEpisode = previousEpisode != null,
                    focusPlayerOnStart = true,
                    isFullscreen = true,
                    // A downloaded episode has no windowed layout to shrink back into — this screen
                    // is the picture and nothing else — so the controls' Exit leaves the player
                    // instead of resizing it. Left unwired the pill was still drawn and did
                    // nothing at all when pressed. TV is given none: its row would gain a
                    // Fullscreen button beside a remote that already has Back.
                    onToggleFullscreen = if (device.isTv) null else leave,
                    startPositionMs = resumePositionMs,
                    notificationRoute = Routes.download(currentDownloadId),
                    episodes = drawerItems,
                    currentIndex = currentDrawerIndex.coerceAtLeast(0),
                    historyEntry = currentHistory,
                    onSelectEpisode = if (currentDrawerIndex >= 0) {
                        { index -> drawerEpisodes.getOrNull(index)?.first?.let(switchTo) }
                    } else {
                        null
                    },
                    onProgress = progress@{ streamUrl, positionMs, durationMs ->
                        // PlayerSurface reports the outgoing media item while switching streams.
                        // Never pair that old clock with the newly selected episode's metadata.
                        if (!isExpectedOfflineProgressStream(currentStreamUrl, streamUrl)) {
                            if (progressStreamUrl != streamUrl) {
                                progressStreamUrl = streamUrl
                                progressReportsForStream = 0
                            }
                            return@progress
                        }
                        if (progressStreamUrl != streamUrl) {
                            progressStreamUrl = streamUrl
                            progressReportsForStream = 0
                        }
                        progressReportsForStream++
                        // Playback is running, so a row held back on open has been earned.
                        if (progressReportsForStream > 1 && !offlineHistorySeeded) {
                            offlineHistorySeeded = true
                            seedOfflineHistory()
                        }
                        val meta = currentMetadata
                        val number = meta?.episodeNumber?.trim()?.toDoubleOrNull()
                        if (
                            meta != null && number != null && !skipLookupAsked &&
                            meta.skip?.hasUsableWindow() != true &&
                            number % 1.0 == 0.0 && durationMs >= MIN_SKIP_LOOKUP_DURATION_MS
                        ) {
                            skipLookupAsked = true
                            val lookupFor = currentDownloadId
                            val lengthSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1).toDouble()
                            scope.launch {
                                val found = runCatching {
                                    AppGraph.repository.skipTimes(meta.anilistId, number, lengthSeconds)
                                }.onFailure { it.rethrowIfCancellation() }.getOrNull()
                                DiagnosticsLog.event(
                                    "Offline skip lookup anilist=${meta.anilistId} episode=${meta.episodeNumber} " +
                                        "found=${found?.hasUsableWindow() == true}",
                                )
                                if (found?.hasUsableWindow() == true && lookupFor == currentDownloadId) {
                                    lookedUpSkip = found
                                }
                            }
                        }
                        if (meta != null && number != null) {
                            // A single report is not evidence of playback; the watched threshold
                            // only moves once the same stream has been observed running, matching
                            // the watch screen.
                            if (progressReportsForStream > 1 &&
                                shouldSyncAniListProgress(number, positionMs, durationMs)
                            ) {
                                if (locallyWatchedEpisodes.add(number)) {
                                    val nextMeta = currentNext?.metadata
                                    val total = currentSeriesLength
                                    LibraryStore.markEpisodeWatched(
                                        anilistId = meta.anilistId,
                                        watchedEpisode = number,
                                        nextEpisode = nextMeta?.episodeNumber?.trim()?.toDoubleOrNull(),
                                        nextEpisodeTitle = nextMeta?.episodeTitle,
                                        seriesCompleted = total != null && number.toInt() >= total,
                                        totalEpisodes = total,
                                    )
                                    syncWatchedEpisode(meta.anilistId, number, total)
                                }
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastProgressSave >= 8_000) {
                                lastProgressSave = now
                                LibraryStore.updateProgress(
                                    meta.anilistId,
                                    number,
                                    positionMs,
                                    durationMs,
                                )
                            }
                        }
                    },
                    onFinalProgress = { streamUrl, positionMs, durationMs ->
                        val meta = currentMetadata
                        val number = meta?.episodeNumber?.trim()?.toDoubleOrNull()
                        if (
                            meta != null &&
                            number != null &&
                            isExpectedOfflineProgressStream(currentStreamUrl, streamUrl)
                        ) {
                            // PlayerSurface has just sampled the controller directly. Persist this
                            // one report even if the periodic eight-second window has not elapsed.
                            LibraryStore.updateProgress(
                                meta.anilistId,
                                number,
                                positionMs,
                                durationMs,
                            )
                        }
                    },
                    onControlsVisibilityChanged = { chromeVisible = it },
                    allowStallFailover = false,
                )
                } }
            }
        }

        val subtitleStatus = download?.offlineSubtitleStatusLabel
        if (chromeVisible && playable != null && subtitleStatus != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp, start = 72.dp, end = 72.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.82f),
            ) {
                Text(
                    subtitleStatus,
                    color = if (download?.hasMissingOfflineSubtitles == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color.White
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        if (showsDownloadBackControl(hasPlayable = playable != null, hasPlaybackError = playbackError != null)) {
            val controlFocus1 = rememberFocusFill()
            IconButton(
                colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = leave,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // The TV remote has a dedicated Back key; keeping this overlay out of the
                    // D-pad graph stops it from stealing the focus the player's key handler
                    // needs to bring the controls back up.
                    .then(if (device.isTv) Modifier.focusProperties { canFocus = false } else Modifier)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .focusFill(controlFocus1, CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = controlFocus1.content(Color.White),
                )
            }
        }
    }
}

/**
 * Hand an episode finished from the download cache to the signed-in list service.
 *
 * Offline playback used to move Continue Watching and stop there: nothing on this screen ever
 * called the sync, so a season watched from downloads was invisible to AniList and MAL, and to
 * every other device. The queue this posts to is already built for exactly this case — its work is
 * network-constrained, so an episode finished on a plane is delivered when the device next has a
 * connection rather than being dropped.
 *
 * This is a report about an episode already on the device; it downloads nothing, and takes no
 * interest in what else the series has.
 */
private fun syncWatchedEpisode(anilistId: Int, episodeNumber: Double, totalEpisodes: Int?) {
    if (!LibraryStore.canSyncProgress(anilistId)) return
    // The list services count whole episodes, and shouldSyncAniListProgress has already refused
    // anything else; this is the same conversion the online player makes.
    val episode = episodeNumber.toInt()
    if (episode < 1) return
    // The download record carries no series length. With none looked up yet the worker resolves
    // one when it needs it, which is what decides whether this episode finishes the series.
    LibraryStore.enqueueProgressSync(anilistId, episode, totalEpisodes)
}

/**
 * Whether the screen draws a Back arrow of its own over the picture.
 *
 * Over playing video it does not. The player's controls already carry a way out, and a second
 * arrow floating in the corner was one exit too many. The error and not-ready screens have no
 * player surface to tap for controls, so those keep a visible Back action.
 */
internal fun showsDownloadBackControl(hasPlayable: Boolean, hasPlaybackError: Boolean): Boolean =
    hasPlaybackError || !hasPlayable

/** A progress clock is safe to record only while it belongs to the episode currently on screen. */
internal fun isExpectedOfflineProgressStream(expectedUrl: String?, reportedUrl: String): Boolean =
    expectedUrl != null && expectedUrl == reportedUrl

/**
 * Resume only the matching unfinished episode. A completed episode, corrupt overshoot, or a clock
 * already in the final ten seconds restarts cleanly instead of opening on credits or an ended item.
 */
internal fun offlineResumePosition(
    history: HistoryEntry?,
    anilistId: Int,
    episodeNumber: Double,
): Long {
    if (history == null || history.anilistId != anilistId) return 0L
    val resume = history.resumeFor(episodeNumber) ?: return 0L
    val positionMs = resume.positionMs.coerceAtLeast(0L)
    val durationMs = resume.durationMs
    if (durationMs > 0L && positionMs >= (durationMs - OFFLINE_RESTART_WINDOW_MS).coerceAtLeast(0L)) return 0L
    return positionMs
}

private const val OFFLINE_RESTART_WINDOW_MS = 10_000L

/** Whichever on-device copy of an episode the player is going to open. */
private data class PlayableEpisode(
    val stream: StreamItem,
    val subtitles: List<SubtitleItem>,
    val metadata: com.anilili.playback.EpisodeDownloadMetadata,
)

@Composable
private fun DownloadMessage(title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                message,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

package com.anilili.ui.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.anilili.R
import com.anilili.data.ProviderCatalog
import com.anilili.data.library.HistoryEntry
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.model.hasUsableWindow
import com.anilili.data.settings.CaptionEdgeStyle
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.DefaultQuality
import com.anilili.data.settings.PlayerContentScale
import com.anilili.data.settings.PreferredAudioLanguage
import com.anilili.data.settings.PreferredSubtitleLanguage
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.asFailure
import com.anilili.errors.isTlsIntercepted
import com.anilili.errors.playbackFailureEntry
import com.anilili.errors.report
import com.anilili.playback.AttachedSubtitle
import com.anilili.playback.AudioOutputMonitor
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.PlaybackService
import com.anilili.playback.SleepTimer
import com.anilili.playback.SignsPairing
import com.anilili.playback.SubtitleDelay
import com.anilili.playback.buildSignsMerge
import com.anilili.playback.externalPlaybackRequest
import com.anilili.playback.httpFailureSummary
import com.anilili.playback.importSubtitleFile
import com.anilili.playback.launchExternalPlayer
import com.anilili.playback.ExternalPlaybackSession
import com.anilili.playback.loadSubtitleCueTimes
import com.anilili.playback.nearestSubtitleCues
import com.anilili.playback.signsMergeDialogueLabel
import com.anilili.playback.signsPairing
import com.anilili.playback.subtitleDelaySeed
import com.anilili.playback.subtitleSyncDelayMs
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberScreenReaderActive
import com.anilili.ui.components.CaptionAppearanceDialog
import com.anilili.ui.components.CompiledSeparately
import com.anilili.ui.components.typeface
import com.anilili.ui.nav.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anilili.util.cached

/** Makes PlayerView's own previous/next buttons navigate episodes, not its one-item playlist. */
@OptIn(UnstableApi::class)
private class EpisodeControlPlayer(
    player: Player,
    private val hasNextEpisode: Boolean,
    private val hasPreviousEpisode: Boolean,
    private val onNextEpisode: () -> Unit,
    private val onPreviousEpisode: () -> Unit,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .removeAll(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextEpisode)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextEpisode)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousEpisode)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousEpisode)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> hasNextEpisode
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> hasPreviousEpisode
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = hasNextEpisode
    override fun hasPreviousMediaItem(): Boolean = hasPreviousEpisode

    override fun seekToNext() {
        if (hasNextEpisode) onNextEpisode()
    }

    override fun seekToNextMediaItem() {
        if (hasNextEpisode) onNextEpisode()
    }

    override fun seekToPrevious() {
        if (hasPreviousEpisode) onPreviousEpisode()
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousEpisode) onPreviousEpisode()
    }
}

internal fun shouldFailOverStalledPlayback(
    allowFailover: Boolean,
    playbackState: Int,
    playWhenReady: Boolean,
    elapsedMs: Long,
    alreadyTriggered: Boolean,
): Boolean = allowFailover &&
    playbackState == Player.STATE_BUFFERING &&
    playWhenReady &&
    elapsedMs >= STREAM_STALL_FAILOVER_MS &&
    !alreadyTriggered

private const val STREAM_STALL_FAILOVER_MS = 20_000L

/** Media3 player surface backed by [PlaybackService] for PiP and system media controls. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerSurface(
    stream: StreamItem,
    qualityStreams: List<StreamItem> = listOf(stream),
    subtitles: List<SubtitleItem>,
    skip: SkipTimes?,
    skipTimingStatus: SkipTimingStatus = if (skip?.hasUsableWindow() == true) {
        SkipTimingStatus.PROVIDER
    } else {
        SkipTimingStatus.UNAVAILABLE
    },
    skipReport: SkipReportState = SkipReportState.IDLE,
    onReportSkipTiming: (() -> Unit)? = null,
    seriesTitle: String,
    episodeTitle: String,
    /** Shown by [PlayerPauseOverlay] while the episode is paused; blank simply omits the line. */
    pauseDescription: String = "",
    artworkUrl: String?,
    animeId: Int,
    provider: String,
    category: String,
    /** True when the picture already carries the translation; see [subtitlesEnabledByDefault]. */
    burnedInSubtitles: Boolean = false,
    episode: String,
    /**
     * True when the player took this episode itself at the end of the last one, which starts it
     * even with "Play when an episode opens" off. See [WatchData.autoAdvanced].
     */
    autoAdvanced: Boolean = false,
    onEnded: () -> Unit,
    onNextEpisode: () -> Unit = onEnded,
    /** The countdown card taking the next episode on its own, as opposed to [onNextEpisode]'s press. */
    onAutoAdvance: () -> Unit = onNextEpisode,
    /**
     * A playback failure the player could not recover from, as a catalogued failure rather than the
     * player's own sentence.
     *
     * Reported only once every in-place recovery has been tried and declined — the 720p decoder
     * retry and the post-seek re-prepare both return without calling this — so reaching here is what
     * makes it worth a code and a diagnostics event.
     */
    onError: (AnililiFailure, String, Long) -> Unit,
    /**
     * A stream that was still buffering twenty seconds after being re-prepared in place. Reported
     * apart from [onError] because it is not a claim that the source is broken, and the caller must
     * not write it down as one. Unwired, the stall is reported through [onError] as before.
     */
    onStalled: ((String, Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null,
    startPositionMs: Long = 0,
    /** Reports (streamUrl, positionMs, durationMs); the url is the item the player is really on. */
    onProgress: ((String, Long, Long) -> Unit)? = null,
    /**
     * The episode as an external player hand-off, given the length this player measured, so what
     * the other player reports back can be recorded ([com.anilili.playback.ExternalPlaybackReturns]).
     * Null hands the episode over without asking for anything back.
     */
    externalPlaybackSession: ((Long) -> ExternalPlaybackSession)? = null,
    /** Reports the exact parting clock after [onProgress], bypassing caller-side periodic throttles. */
    onFinalProgress: ((String, Long, Long) -> Unit)? = null,
    /** Reports whether the player chrome (TV control row / phone overlay) is currently shown. */
    onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
    /**
     * Reports whether the player has a picture to show, so the caller can keep its loading state up
     * until there is one. False again the moment a new stream is prepared.
     */
    onPlaybackActiveChanged: ((Boolean) -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = true,
    /** What "next" is when it is the next season rather than an episode of this one ("Season 2"). */
    nextSeasonLabel: String? = null,
    hasPreviousEpisode: Boolean = true,
    focusPlayerOnStart: Boolean = true,
    isFullscreen: Boolean = false,
    subtitleOffsetMs: Long = 0L,
    notificationRoute: String? = null,
    episodes: List<EpisodeItem> = emptyList(),
    currentIndex: Int = 0,
    historyEntry: HistoryEntry? = null,
    onSelectEpisode: ((Int) -> Unit)? = null,
    onAddToList: (() -> Unit)? = null,
    /** Offline files cannot fail over to another provider, so their caller disables this guard. */
    allowStallFailover: Boolean = true,
    /** Servers this episode can be played from, for the in-player sources panel. */
    sourceOptions: List<PlayerSourceOption> = emptyList(),
    /** True while more servers are still being resolved in the background. */
    isLoadingSources: Boolean = false,
    /** Sub/dub for this episode, when it has both; null hides the switch. */
    audioSwitch: PlayerAudioSwitch? = null,
    onAudioSwitch: (PlayerAudioChoice) -> Unit = {},
    /**
     * The episode the up-next card names, which is not always the one after this: with "Skip filler
     * episodes" on, Next passes over a filler run. Null when moving on leaves the season.
     */
    nextEpisodeIndex: Int? = currentIndex + 1,
    /** The FILLER tag and its skip; null when this episode is not filler. */
    fillerSkip: PlayerFillerSkip? = null,
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val scope = rememberCoroutineScope()
    var attachedSubtitles by remember(animeId, episode) {
        mutableStateOf<List<SubtitleItem>>(emptyList())
    }
    var selectedAttachedSubtitleUrl by remember(animeId, episode) { mutableStateOf<String?>(null) }
    var openSubtitlesVisible by remember(animeId, episode) { mutableStateOf(false) }
    var sourcesPanelExpanded by remember(animeId, episode) { mutableStateOf(false) }
    val allSubtitles = remember(subtitles, attachedSubtitles) {
        (subtitles + attachedSubtitles).distinctBy(SubtitleItem::url)
    }
    // Signs merged into a dialogue track ("English CC (Dub) + signs"), since the player can show
    // only one text track; see SubtitleTrackMerge.kt. A dub opens with the two merged when it has
    // both and the viewer has not turned the merge off, and the first prepare waits a moment for
    // the file so the episode does not start on one track and re-prepare onto the other.
    val defaultSignsPairing = remember(subtitles, category) {
        signsPairing(subtitles, selectedLabel = null, preferDub = true)
            .takeIf { category.equals("dub", ignoreCase = true) }
    }
    var defaultSignsMergePending by remember(animeId, episode, defaultSignsPairing) {
        mutableStateOf(
            defaultSignsPairing != null &&
                attachedSubtitles.none { it.label == defaultSignsPairing.mergedLabel },
        )
    }
    var signsMergeInFlight by remember(animeId, episode) { mutableStateOf(false) }
    val attachSubtitle: (AttachedSubtitle) -> Unit = { attached ->
        attachedSubtitles = (attachedSubtitles + attached.subtitle).distinctBy(SubtitleItem::url)
        selectedAttachedSubtitleUrl = attached.subtitle.url
        openSubtitlesVisible = false
        val quota = attached.remainingDownloads?.let { " · $it free downloads left today" }.orEmpty()
        Toast.makeText(context, "Subtitle attached$quota", Toast.LENGTH_LONG).show()
    }
    val subtitleFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { importSubtitleFile(context, uri) } }
                .onSuccess(attachSubtitle)
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        error.message ?: "Could not import subtitle file.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }
    val playerGestures by SettingsStore.playerGestures.collectAsState()
    val playerSeekGesture by SettingsStore.playerSeekGesture.collectAsState()
    val holdToSpeedEnabled by SettingsStore.holdToSpeedEnabled.collectAsState()
    val holdPlaybackSpeed by SettingsStore.holdPlaybackSpeed.collectAsState()
    val contentScale by SettingsStore.playerContentScale.collectAsState()
    val audioOutputFormat by AudioOutputMonitor.current.collectAsState()
    CompiledSeparately {
    PlayerBrightnessOverride(enabled = playerGestures)
    }
    var persistCaptionDelayAcrossEpisodes by remember(animeId) { mutableStateOf(false) }
    // Sync-by-capture reads the selected sidecar file itself, so it is reset whenever the track or
    // the episode changes — cue times from the previous file would point at the wrong lines.
    var subtitleSyncState by remember(animeId, episode, selectedAttachedSubtitleUrl) {
        mutableStateOf(SubtitleSyncState())
    }
    // By default every episode starts from its provider-measured offset. An explicit per-anime
    // opt-in replaces that seed, including after process death and when the player changes source.
    CompiledSeparately {
    LaunchedEffect(animeId, episode, stream.url, subtitleOffsetMs) {
        SettingsStore.awaitLoaded()
        val persistentDelayMs = SettingsStore.persistentCaptionDelay(animeId)
        persistCaptionDelayAcrossEpisodes = persistentDelayMs != null
        val seed = subtitleDelaySeed(subtitleOffsetMs, persistentDelayMs)
        SubtitleDelay.set(seed.delayMs, automatic = seed.automatic)
    }
    }
    val subtitleDelayMs by SubtitleDelay.delayMs.collectAsState()
    val preferredAudioLanguage by SettingsStore.preferredAudioLanguage.collectAsState()
    val preferredSubtitleLanguage by SettingsStore.preferredSubtitleLanguage.collectAsState()
    val secondaryAudioLanguage by SettingsStore.secondaryAudioLanguage.collectAsState()
    val secondarySubtitleLanguage by SettingsStore.secondarySubtitleLanguage.collectAsState()
    val controllerFuture = remember(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaController.Builder(context, token).buildAsync()
    }
    val perSurface = remember { PlayerSurfaceState() }
    var controller by perSurface.controller
    val currentProvider by rememberUpdatedState(provider)
    val currentCategory by rememberUpdatedState(category)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnStalled by rememberUpdatedState(onStalled)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnFinalProgress by rememberUpdatedState(onFinalProgress)
    val perStream = remember(stream.url) { PlayerStreamState(stream, startPositionMs) }
    var activeStream by perStream.activeStream
    var nextStartPositionMs by perStream.nextStartPositionMs
    var playbackIsPlaying by perSurface.playbackIsPlaying
    val perController = remember(controller) { PlayerControllerState() }
    var playbackState by perController.playbackState
    var playbackWantsToPlay by perController.playbackWantsToPlay
    val perSource = remember(activeStream.url) { PlayerSourceState() }
    var stallFailoverTriggered by perSource.stallFailoverTriggered
    var stallRetried by perSource.stallRetried
    var tracksRevision by perSurface.tracksRevision
    // Followed here rather than left to PlayerView, which drops the update; see
    // [applyContentAspectRatio]. 0 means "not known", the same value PlayerView passes on.
    var contentAspectRatio by perController.contentAspectRatio
    var preparedSubtitleSignature by perController.preparedSubtitleSignature
    // The quality readout, keyed to the stream playing: what the decoder last reported, which of
    // those reports the viewer has already been shown, and the chip's own text and visibility (kept
    // apart so the text survives the fade-out instead of blanking halfway through it).
    var reportedQualityLabel by perSource.reportedQualityLabel
    var announcedQualityLabel by perSource.announcedQualityLabel
    var qualityNoticeText by perSource.qualityNoticeText
    var qualityNoticeVisible by perSource.qualityNoticeVisible
    // Followed as state rather than read off the controller where the settings sheet is composed:
    // `playbackParameters` is a plain getter, so nothing recomposed when it changed and the speed
    // slider sat at its old notch until the sheet was closed and opened again.
    var playbackSpeed by perController.playbackSpeed
    // The remembered speed, applied as each episode opens. Keyed on the incoming stream rather than
    // on `activeStream`, so a mid-episode provider failover does not throw away a speed the viewer
    // chose for the episode it is still playing. Read after awaitLoaded because on a cold start
    // the flow is still holding its in-memory default when the player is first composed.
    CompiledSeparately {
    LaunchedEffect(controller, stream.url) {
        val activeController = controller ?: return@LaunchedEffect
        SettingsStore.awaitLoaded()
        val speed = SettingsStore.defaultPlaybackSpeed.value
        if (activeController.playbackParameters.speed != speed) {
            activeController.setPlaybackSpeed(speed)
            DiagnosticsLog.event("PlayerSurface default playback speed=$speed")
        }
    }
    // Re-applied when the choice changes as well as when a player appears, so switching it takes
    // effect on what is already playing rather than only on the next episode.
    LaunchedEffect(controller, preferredAudioLanguage, secondaryAudioLanguage) {
        val activeController = controller ?: return@LaunchedEffect
        applyPreferredAudioLanguage(activeController, preferredAudioLanguage, secondaryAudioLanguage)
        DiagnosticsLog.event(
            "PlayerSurface preferred audio language=${preferredAudioLanguage.storedValue.ifBlank { "auto" }}",
        )
    }
    // Attaching a file is a deliberate choice for the episode on screen, and it is marked only as
    // the default track — which a language preference outranks in Media3's scoring. So the standing
    // preference stands down for as long as an attached subtitle is the selected one.
    LaunchedEffect(
        controller,
        preferredSubtitleLanguage,
        secondarySubtitleLanguage,
        selectedAttachedSubtitleUrl,
    ) {
        val activeController = controller ?: return@LaunchedEffect
        val attached = selectedAttachedSubtitleUrl != null
        val language = preferredSubtitleLanguage.takeIf { !attached }
            ?: PreferredSubtitleLanguage.AUTOMATIC
        // The fallback stands down with the primary: an attached file is a decision about this
        // episode, and a second-choice language would talk it back out of the selection.
        val fallback = secondarySubtitleLanguage.takeIf { !attached }
            ?: PreferredSubtitleLanguage.AUTOMATIC
        applyPreferredSubtitleLanguage(activeController, language, fallback)
        DiagnosticsLog.event(
            "PlayerSurface preferred subtitle language=${language.storedValue.ifBlank { "auto" }}",
        )
    }
    }
    // One same-stream retry at a capped resolution before giving the provider up: weak TV
    // decoders (Fire TV's OMX.MS.AVC) can die on 1080p with a codec error even though the
    // format is nominally supported, and a provider failover for a device-side decode hiccup
    // needlessly restarts the episode on another server.
    var decoderRetryDone by perStream.decoderRetryDone
    var pendingTvSeekTargetMs by perStream.pendingTvSeekTargetMs
    var tvSeekRequest by perStream.tvSeekRequest
    var lastUserSeekRealtimeMs by perStream.lastUserSeekRealtimeMs
    var lastUserSeekTargetMs by perStream.lastUserSeekTargetMs
    var seekErrorRecoveryDone by perStream.seekErrorRecoveryDone
    /** Set while the seek below is our own error recovery rather than the user's. */
    var recoverySeekInFlight by perStream.recoverySeekInFlight
    var episodeDrawerExpanded by perStream.episodeDrawerExpanded
    val nativeQualityStreams = remember(stream.url, qualityStreams) {
        (listOf(stream) + qualityStreams)
            .filterNot(StreamItem::isEmbed)
            .distinctBy(StreamItem::url)
    }

    CompiledSeparately {
    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                runCatching { controllerFuture.get() }
                    .onSuccess {
                        DiagnosticsLog.event("PlayerSurface MediaController connected")
                        controller = it
                    }
                    .onFailure { DiagnosticsLog.throwable("PlayerSurface MediaController connection failed", it) }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { MediaController.releaseFuture(controllerFuture) }
    }

    LaunchedEffect(controller) {
        if (controller == null) {
            delay(5_000)
            if (controller == null) {
                DiagnosticsLog.event("PlayerSurface controller still null after 5000ms")
            }
        }
    }

    LaunchedEffect(controller, stream.url) {
        controller?.let(::clearVideoSelection)
    }

    DisposableEffect(controller, activeStream.url) {
        val activeController = controller
        if (activeController == null) {
            onDispose { }
        } else {
            playbackIsPlaying = activeController.isPlaying
            playbackState = activeController.playbackState
            playbackWantsToPlay = activeController.playWhenReady
            contentAspectRatio = activeController.videoSize.contentAspectRatio()
            // A controller that is already playing when this surface is composed — a return from
            // picture-in-picture, say — published its size before the listener existed, so seed the
            // readout from it rather than waiting for a change that may never come.
            reportedQualityLabel = qualityLabel(activeController.videoSize)
            playbackSpeed = activeController.playbackParameters.speed
            val listener = object : Player.Listener {
                private var audioPreferenceAppliedFor: String? = null

                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                    playbackSpeed = parameters.speed
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    contentAspectRatio = videoSize.contentAspectRatio()
                    // The decoder's own size is the only honest answer to "what is playing": the
                    // ladder carries rungs this stream may never select, and an adaptive stream can
                    // move between them without anything else in the app noticing.
                    reportedQualityLabel = qualityLabel(videoSize)
                    DiagnosticsLog.event(
                        "PlayerSurface video size ${videoSize.width}x${videoSize.height} " +
                            "par=${videoSize.pixelWidthHeightRatio} ratio=$contentAspectRatio",
                    )
                }

                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                    DiagnosticsLog.event(
                        "PlayerSurface playbackState=${state.stateName()} " +
                            "mediaId=${activeController.currentMediaItem?.mediaId?.take(120) ?: "none"}",
                    )
                    // The default-quality effect waits for READY; re-trigger it when we get there.
                    if (state == Player.STATE_READY) {
                        PlaybackService.finishUncachedPlaybackRecovery()
                        tracksRevision++
                    }
                    if (state == Player.STATE_ENDED) onEnded()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackIsPlaying = isPlaying
                    DiagnosticsLog.event("PlayerSurface isPlaying=$isPlaying")
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playbackWantsToPlay = playWhenReady
                }

                /**
                 * Marks every user seek, not just the TV D-pad's.
                 *
                 * The seek marker used to be written only where remote presses are coalesced, so a
                 * scrub on the phone's progress bar, a gesture skip, or an air-mouse drag on TV set
                 * nothing at all. [shouldRecoverSeekError] then saw "no recent seek" and treated
                 * the stale-segment error that normally follows a seek as a dead server — which is
                 * why skipping ahead silently changed the server mid-episode.
                 */
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    lastUserSeekTargetMs = newPosition.positionMs.coerceAtLeast(0L)
                    lastUserSeekRealtimeMs = SystemClock.elapsedRealtime()
                    // Our own recovery seek must not hand itself a fresh retry budget, or a
                    // genuinely dead stream would retry forever instead of failing over.
                    if (recoverySeekInFlight) {
                        recoverySeekInFlight = false
                    } else {
                        seekErrorRecoveryDone = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagnosticsLog.throwable("PlayerSurface player error code=${error.errorCodeName}", error)
                    PlaybackService.finishUncachedPlaybackRecovery()
                    // "Response code: 403" is the whole of what the exception says, and it is not
                    // enough to tell a link that has expired from one minted for someone else from
                    // a host refusing us outright. What was actually asked for is.
                    var cause: Throwable? = error
                    while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) {
                        cause = cause.cause
                    }
                    val refusedCode = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    (cause as? HttpDataSource.InvalidResponseCodeException)?.let { refused ->
                        DiagnosticsLog.event(
                            "PlayerSurface request refused " +
                                httpFailureSummary(
                                    responseCode = refused.responseCode,
                                    url = refused.dataSpec.uri.toString(),
                                    headers = refused.headerFields,
                                ),
                        )
                        // A CDN that answers a refusal in JSON is telling us why in words. That is
                        // worth reading before guessing at headers: the code alone cannot separate
                        // "wrong referer" from "region blocked" from "link consumed".
                        val said = runCatching {
                            refused.responseBody.decodeToString().trim().take(200)
                        }.getOrNull()
                        if (!said.isNullOrBlank()) {
                            DiagnosticsLog.event("PlayerSurface refusal said ${said.replace('\n', ' ')}")
                        }
                    }
                    val failedMediaId = activeController.currentMediaItem?.mediaId.orEmpty()
                    if (failedMediaId.isNotBlank() && failedMediaId != activeStream.url) {
                        DiagnosticsLog.event(
                            "PlayerSurface ignored stale error " +
                                "failedHost=${runCatching { Uri.parse(failedMediaId).host }.getOrNull() ?: "unknown"} " +
                                "activeHost=${activeStream.host()}",
                        )
                        return
                    }
                    val exoError = error as? ExoPlaybackException
                    val retryAtLowerVideoResolution = shouldRetryDecoderAtLowerVideoResolution(
                        errorCode = error.errorCode,
                        rendererName = exoError?.rendererName,
                        sampleMimeType = exoError?.rendererFormat?.sampleMimeType,
                        errorMessage = error.message,
                    )
                    if (retryAtLowerVideoResolution && !decoderRetryDone) {
                        decoderRetryDone = true
                        val resumeAt = activeController.currentPosition.coerceAtLeast(0L)
                        activeController.trackSelectionParameters = activeController.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .setMaxVideoSize(1280, 720)
                            .build()
                        activeController.prepare()
                        activeController.seekTo(resumeAt)
                        activeController.play()
                        DiagnosticsLog.event(
                            "PlayerSurface decoder failed; retrying same stream capped at 720p resumeMs=$resumeAt",
                        )
                        return
                    }
                    if (
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
                        !retryAtLowerVideoResolution
                    ) {
                        DiagnosticsLog.event(
                            "PlayerSurface audio decoder failed; skipping ineffective video-quality retry",
                        )
                    }
                    val elapsedSinceSeekMs = if (lastUserSeekRealtimeMs == Long.MIN_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        SystemClock.elapsedRealtime() - lastUserSeekRealtimeMs
                    }
                    if (
                        shouldRecoverSeekError(
                            errorCode = error.errorCode,
                            elapsedSinceSeekMs = elapsedSinceSeekMs,
                            recoveryAlreadyAttempted = seekErrorRecoveryDone,
                        )
                    ) {
                        seekErrorRecoveryDone = true
                        recoverySeekInFlight = true
                        val resumeAt = lastUserSeekTargetMs.coerceAtLeast(0L)
                        activeController.prepare()
                        activeController.seekTo(resumeAt)
                        activeController.play()
                        DiagnosticsLog.event(
                            "PlayerSurface seek I/O error; retrying same stream " +
                                "code=${error.errorCodeName} resumeMs=$resumeAt",
                        )
                        return
                    }
                    // The refused status has to survive this hand-off, because the cooldown that
                    // reads it has no other way to see it: `localizedMessage` on a bad-status
                    // PlaybackException is the word "Source error", so a stream host answering 429
                    // to every episode of every title looked exactly like one broken file. AniDB
                    // spent a tester's whole session that way — `pewe` resolved every time and
                    // `hls.anidb.app` answered 429 (Cloudflare 1027, over its plan) to all of it.
                    // It now travels as the failure's platform code (`h429`) rather than inside a
                    // sentence, so nothing downstream has to parse prose to find it.
                    currentOnError(
                        playbackFailureEntry(
                            errorCode = error.errorCode,
                            isAudioFailure = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
                                !retryAtLowerVideoResolution,
                            isLocalMedia = activeStream.isLocalMedia(),
                            networkIntercepted = error.isTlsIntercepted(),
                        ).report(
                            cause = error,
                            attributes = mapOf(
                                "provider" to currentProvider,
                                "episode" to episode,
                                "streamHost" to activeStream.host(),
                                "positionMs" to activeController.currentPosition.coerceAtLeast(0L),
                                "refusedStatus" to refusedCode,
                            ),
                        ),
                        failedMediaId,
                        activeController.currentPosition.coerceAtLeast(0L),
                    )
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    DiagnosticsLog.event("PlayerSurface tracks ${tracks.diagnosticSummary()}")
                    tracksRevision++
                    val mediaId = activeController.currentMediaItem?.mediaId ?: return
                    if (currentProvider.lowercase() !in com.anilili.data.ProviderCatalog.multiAudioProviders || audioPreferenceAppliedFor == mediaId) return
                    // A viewer who named a language is not overruled, they are ranked with: the
                    // category says whether this is a dub at all, their setting says which dub.
                    // See `categoryAudioRank` for why the setting cannot be left to answer both.
                    if (applyCategoryAudioPreference(
                            activeController,
                            currentCategory,
                            currentProvider,
                            preferredAudioTrackCodes(
                                SettingsStore.preferredAudioLanguage.value,
                                SettingsStore.secondaryAudioLanguage.value,
                            ),
                        )
                    ) {
                        audioPreferenceAppliedFor = mediaId
                    }
                }
            }
            DiagnosticsLog.event("PlayerSurface listener attached")
            activeController.addListener(listener)
            onDispose {
                // Attribute the parting position to the item the controller actually holds, never
                // to whatever episode the screen has since moved on to.
                activeController.currentMediaItem?.mediaId?.let { playingUrl ->
                    val partingPositionMs = activeController.currentPosition.coerceAtLeast(0)
                    val partingDurationMs = activeController.duration.coerceAtLeast(0)
                    currentOnProgress?.invoke(
                        playingUrl,
                        partingPositionMs,
                        partingDurationMs,
                    )
                    currentOnFinalProgress?.invoke(playingUrl, partingPositionMs, partingDurationMs)
                }
                activeController.removeListener(listener)
                DiagnosticsLog.event("PlayerSurface listener removed")
            }
        }
    }

    DisposableEffect(Unit) {
        // Do not leave the service routing future episodes around the playback cache if the viewer
        // backs out while the recovery item is still buffering.
        onDispose { PlaybackService.finishUncachedPlaybackRecovery() }
    }

    LaunchedEffect(
        controller,
        activeStream.url,
        playbackState,
        playbackWantsToPlay,
        allowStallFailover,
        // Re-arms the wait after an in-place retry. Without it a retry that leaves the player in
        // STATE_BUFFERING changes none of the other keys, this effect never runs again, and the
        // second stall — the one that actually justifies leaving the server — is never seen.
        stallRetried,
    ) {
        val activeController = controller ?: return@LaunchedEffect
        if (playbackState != Player.STATE_BUFFERING || !playbackWantsToPlay ||
            !allowStallFailover || stallFailoverTriggered
        ) return@LaunchedEffect

        delay(STREAM_STALL_FAILOVER_MS)
        if (!shouldFailOverStalledPlayback(
                allowFailover = allowStallFailover,
                playbackState = activeController.playbackState,
                playWhenReady = activeController.playWhenReady,
                elapsedMs = STREAM_STALL_FAILOVER_MS,
                alreadyTriggered = stallFailoverTriggered,
            )
        ) return@LaunchedEffect
        if (activeController.currentMediaItem?.mediaId != activeStream.url) return@LaunchedEffect

        val positionMs = activeController.currentPosition.coerceAtLeast(0L)
        // Twenty seconds of buffering is a symptom, not a verdict on the server. Ask this stream
        // for the same position once more before giving up on it: a CDN hiccup, a renewed token or
        // a seek into a cold region all recover here, and every one of them used to cost the viewer
        // the server they were happily watching.
        if (!stallRetried) {
            stallRetried = true
            DiagnosticsLog.event(
                "PlayerSurface stalled; re-preparing the same stream " +
                    "afterMs=$STREAM_STALL_FAILOVER_MS host=${activeStream.host()} positionMs=$positionMs",
            )
            // Media3 1.8.1's prepare() is a no-op outside STATE_IDLE. Rebuild the held item so this
            // is a real reload, and keep the position and play intent the viewer already had.
            val retryItem = activeController.currentMediaItem ?: return@LaunchedEffect
            PlaybackService.beginUncachedPlaybackRecovery()
            activeController.stop()
            activeController.setMediaItem(retryItem, positionMs)
            activeController.prepare()
            activeController.playWhenReady = true
            return@LaunchedEffect
        }

        stallFailoverTriggered = true
        PlaybackService.finishUncachedPlaybackRecovery()
        DiagnosticsLog.event(
            "PlayerSurface stalled after retry; requesting source failover " +
                "afterMs=$STREAM_STALL_FAILOVER_MS host=${activeStream.host()} positionMs=$positionMs",
        )
        val reportStall = currentOnStalled
        if (reportStall != null) {
            reportStall(activeStream.url, positionMs)
        } else {
            currentOnError(
                AnililiError.PLAYBACK_SOURCE_UNREACHABLE.report(
                    detail = "Still buffering ${STREAM_STALL_FAILOVER_MS / 1_000} seconds after the " +
                        "stream was re-prepared in place.",
                    attributes = mapOf(
                        "provider" to currentProvider,
                        "streamHost" to activeStream.host(),
                        "positionMs" to positionMs,
                        "stalledMs" to STREAM_STALL_FAILOVER_MS,
                    ),
                ),
                activeStream.url,
                positionMs,
            )
        }
    }

    LaunchedEffect(animeId, episode, defaultSignsPairing) {
        val pairing = defaultSignsPairing ?: return@LaunchedEffect
        if (!defaultSignsMergePending) return@LaunchedEffect
        SettingsStore.awaitLoaded()
        val wanted = SettingsStore.subtitleSignsWithDialogue.value &&
            selectedAttachedSubtitleUrl == null &&
            subtitlesEnabledByDefault(
                category = category,
                subtitlesByDefault = SettingsStore.subtitlesByDefault.value,
                subtitlesWithDub = SettingsStore.subtitlesWithDub.value,
                burnedInSubtitles = burnedInSubtitles,
            )
        if (!wanted) {
            defaultSignsMergePending = false
            return@LaunchedEffect
        }
        // The wait is capped: a slow subtitle host must not hold the episode back. A file that
        // arrives after the cap is still attached, at the cost of one in-place re-prepare.
        val cap = launch {
            delay(SIGNS_MERGE_START_WAIT_MS)
            DiagnosticsLog.event("PlayerSurface signs merge over budget; preparing without it")
            defaultSignsMergePending = false
        }
        val merged = try {
            buildSignsMerge(context, pairing.fetchedLike(activeStream))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DiagnosticsLog.event(
                "PlayerSurface signs merge failed ${error.javaClass.simpleName}: ${error.message?.take(120)}",
            )
            null
        }
        cap.cancel()
        if (merged != null && selectedAttachedSubtitleUrl == null) {
            attachedSubtitles = (attachedSubtitles + merged).distinctBy(SubtitleItem::url)
            selectedAttachedSubtitleUrl = merged.url
            DiagnosticsLog.event(
                "PlayerSurface signs merged by default label=${merged.label.take(80)} mime=${merged.mimeType}",
            )
        }
        defaultSignsMergePending = false
    }

    LaunchedEffect(
        controller,
        activeStream.url,
        allSubtitles,
        selectedAttachedSubtitleUrl,
        defaultSignsMergePending,
    ) {
        val activeController = controller ?: return@LaunchedEffect
        SettingsStore.awaitLoaded()
        // A dub about to open with its signs merged in waits for the merged file (briefly; see
        // the effect above) rather than starting on one track and re-preparing onto the other. An
        // item the player already holds is never held back.
        if (defaultSignsMergePending && activeController.currentMediaItem?.mediaId != activeStream.url) {
            DiagnosticsLog.event("PlayerSurface prepare waits for the signs merge")
            return@LaunchedEffect
        }
        val subtitleSignature = buildString {
            append(activeStream.url)
            append('|')
            allSubtitles.forEach { subtitle ->
                append(subtitle.url)
                append(':')
                append(subtitle.mimeType)
                append('|')
            }
            append(selectedAttachedSubtitleUrl)
        }
        val replacingCurrentItem = activeController.currentMediaItem?.mediaId == activeStream.url
        if (replacingCurrentItem && preparedSubtitleSignature == subtitleSignature) {
            DiagnosticsLog.event(
                "PlayerSurface media item already active " +
                    "host=${activeStream.host()} type=${activeStream.typeLabel()}",
            )
            return@LaunchedEffect
        }
        val reprepareState = subtitleReprepareState(
            replacingCurrentItem = replacingCurrentItem,
            currentPositionMs = activeController.currentPosition,
            currentPlayWhenReady = activeController.playWhenReady,
            requestedStartPositionMs = nextStartPositionMs,
            // Read rather than collected: flipping the switch mid-episode must not re-prepare the
            // item on screen, only decide how the next one opens.
            playOnOpen = SettingsStore.playOnOpen.value,
            autoAdvanced = autoAdvanced,
        )
        // A fresh surface meeting an item the service still holds: the viewer has come back to an
        // episode they left, not attached subtitles to one already on screen. Only this case may
        // resume; a mid-episode subtitle re-prepare must stay seamless, and it always arrives with
        // a signature already recorded.
        val reattachingToLoadedItem = replacingCurrentItem && preparedSubtitleSignature == null
        val preparePositionMs = reprepareState.positionMs
        val resumePlayback = reprepareState.playWhenReady ||
            reattachingToLoadedItem && PlaybackService.consumeInvoluntaryPause()

        val enableDefaultSubtitles = selectedAttachedSubtitleUrl != null || subtitlesEnabledByDefault(
            category = category,
            subtitlesByDefault = SettingsStore.subtitlesByDefault.value,
            subtitlesWithDub = SettingsStore.subtitlesWithDub.value,
            burnedInSubtitles = burnedInSubtitles,
        )
        // Track-selection parameters live on the persistent service controller, not the media
        // item. Reset the text policy for every new episode so a manual choice only affects the
        // episode on screen and the Settings default reliably applies to the next one.
        activeController.trackSelectionParameters = activeController.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enableDefaultSubtitles)
            .build()

        DiagnosticsLog.event(
            "PlayerSurface prepare stream type=${activeStream.typeLabel()} host=${activeStream.host()} " +
                "height=${activeStream.declaredVideoHeight() ?: "auto"} subtitles=${allSubtitles.size} " +
                "subtitlesByDefault=$enableDefaultSubtitles startMs=$preparePositionMs",
        )
        PlaybackService.configureRequestHeaders(
            activeStream.referer,
            activeStream.playlistKey,
            activeStream.headers,
            activeStream.avoidCronet,
            activeStream.isProgressive,
        )
        val watchRoute = notificationRoute ?: Routes.watch(animeId, provider, category, episode)
        val metadata = MediaMetadata.Builder()
            .setTitle(episodeTitle)
            .setArtist(seriesTitle)
            .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .setExtras(Bundle().apply {
                putString(PlaybackService.EXTRA_WATCH_ROUTE, watchRoute)
            })
            .build()
        val itemBuilder = MediaItem.Builder()
            .setMediaId(activeStream.url)
            .setUri(activeStream.url)
            .setMediaMetadata(metadata)
            // Media3 1.8.x's Cast converter requires a MIME type for every item. Omitting it for
            // direct MP4s makes the local-to-Cast hand-off throw before the receiver can load.
            .setMimeType(videoMimeType(activeStream.type, activeStream.url))
            .setSubtitleConfigurations(
                allSubtitles.mapIndexed { index, subtitle ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                        .setMimeType(subtitle.mimeType ?: mimeFor(subtitle.url))
                        .setLanguage(subtitle.language)
                        .setLabel(subtitle.label)
                        .apply {
                            // The first soft-subtitle track is the native player's default when
                            // captions are enabled in Settings. Burned-in text is part of the
                            // picture and cannot be controlled here.
                            if (enableDefaultSubtitles && (
                                    subtitle.url == selectedAttachedSubtitleUrl ||
                                        selectedAttachedSubtitleUrl == null && index == 0
                                    )
                            ) {
                                setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            }
                        }
                        .build()
                },
            )
        val item = EpisodeDownloads.buildMediaItem(context, activeStream.url, itemBuilder)
        // Re-preparing an item the player already holds keeps its decoder configured and hands the
        // codec this screen's brand-new SurfaceView through MediaCodec.setOutputSurface. Some
        // decoders render the first frame after that handover as garbage, and a viewer returning to
        // a paused episode is shown exactly one frame — so the garbage would be the whole picture
        // until they pressed play. Stopping first releases the codec, and prepare then configures a
        // new one against the surface it will actually draw on.
        if (reattachingToLoadedItem) {
            activeController.stop()
            DiagnosticsLog.event(
                "PlayerSurface re-attached to loaded item; released decoder for a clean surface " +
                    "resumeMs=$preparePositionMs resume=$resumePlayback",
            )
        }
        activeController.setMediaItem(item, preparePositionMs)
        activeController.prepare()
        activeController.playWhenReady = resumePlayback
        preparedSubtitleSignature = subtitleSignature
        DiagnosticsLog.event(
            "PlayerSurface prepare called playWhenReady=$resumePlayback autoAdvanced=$autoAdvanced",
        )
    }
    }

    var positionMs by perSurface.positionMs
    var durationMs by perSurface.durationMs
    var bufferedPositionMs by perSurface.bufferedPositionMs
    // The stream the clock above is measuring, which is not always the stream this screen is showing:
    // through an episode change the controller keeps reporting the outgoing item until it swaps.
    var clockStreamUrl by perSurface.clockStreamUrl
    // Whichever control bar is on screen reports how far its seek bar sits above the bottom edge,
    // so the floating skip action can clear the timeline. The inline bar and the fullscreen one
    // are very different sizes.
    var controlsSeekAnchor by perSurface.controlsSeekAnchor
    // Every remote seek lands here, whether it was described as an offset or named outright, so the
    // burst coalescing below covers both: a digit pressed in the middle of a run of Left presses is
    // still one seek, not two racing each other.
    val queueTvSeekTo: (Long, String) -> Unit = { targetMs, reason ->
        pendingTvSeekTargetMs = targetMs
        positionMs = targetMs
        tvSeekRequest++
        DiagnosticsLog.event(
            "PlayerSurface TV seek queued $reason targetMs=$targetMs request=$tvSeekRequest",
        )
    }
    val queueTvSeek: (Long) -> Unit = { offsetMs ->
        controller?.let { activeController ->
            val knownDurationMs = activeController.duration
                .takeIf { it != C.TIME_UNSET && it > 0L }
                ?: durationMs
            queueTvSeekTo(
                tvSeekTargetMs(
                    currentPositionMs = pendingTvSeekTargetMs
                        ?: activeController.currentPosition.coerceAtLeast(0L),
                    durationMs = knownDurationMs,
                    offsetMs = offsetMs,
                ),
                "offsetMs=$offsetMs",
            )
        }
    }
    CompiledSeparately {
    LaunchedEffect(controller, activeStream.url, tvSeekRequest) {
        val activeController = controller ?: return@LaunchedEffect
        val targetMs = pendingTvSeekTargetMs ?: return@LaunchedEffect
        // A remote can generate presses much faster than a TV decoder can cancel and restart its
        // segment requests. Commit only after the short burst settles.
        delay(TV_SEEK_COALESCE_MS)
        if (pendingTvSeekTargetMs != targetMs) return@LaunchedEffect
        lastUserSeekTargetMs = targetMs
        lastUserSeekRealtimeMs = SystemClock.elapsedRealtime()
        seekErrorRecoveryDone = false
        pendingTvSeekTargetMs = null
        activeController.seekTo(targetMs)
        DiagnosticsLog.event("PlayerSurface TV seek committed targetMs=$targetMs")
    }
    LaunchedEffect(controller) {
        val activeController = controller ?: return@LaunchedEffect
        while (isActive) {
            if (pendingTvSeekTargetMs == null) {
                positionMs = activeController.currentPosition.coerceAtLeast(0)
            }
            durationMs = activeController.duration.coerceAtLeast(0)
            // What the seek bar's middle band reports. It is polled with the clock rather than
            // watched, because Media3 has no callback for the buffer creeping forward.
            bufferedPositionMs = activeController.bufferedPosition.coerceAtLeast(0)
            // The controller's own media id is the only reliable statement of which stream this
            // clock belongs to: while a new episode is being prepared the controller still reports
            // the outgoing item's position.
            val playingUrl = activeController.currentMediaItem?.mediaId
            // Not handed over until the new item is ready, though: the media id changes the moment
            // the item is set, and for a tick or two after it the controller can still report the
            // outgoing item's position. One Punch Man E1 left at 2:30 read as E2 at 2:30, inside
            // E2's intro, with E2's id already on it. See skipWindowsAt.
            if (playingUrl == clockStreamUrl || activeController.playbackState == Player.STATE_READY) {
                clockStreamUrl = playingUrl
            }
            if (playingUrl != null && activeController.isPlaying) {
                currentOnProgress?.invoke(playingUrl, positionMs, durationMs)
            }
            delay(500)
        }
    }
    }

    var playerView by perSurface.playerView
    var controllerVisible by perSurface.controllerVisible
    var tvControlsVisible by perSurface.tvControlsVisible
    // Whether anything inside the row currently holds the remote. There is no D-pad route into the
    // row from the picture behind it (see [tvEpisodeChangeFocus]), so a row that has lost focus is
    // a row that cannot be operated at all, and this is what tells the two apart.
    var tvControlsFocused by perSurface.tvControlsFocused
    var tvControlsInteraction by perSurface.tvControlsInteraction
    // Left/Right seeking: how far into a run of presses we are, and the readout that goes with it.
    var dpadSeekPresses by perSource.dpadSeekPresses
    var dpadSeekLastPressMs by perSource.dpadSeekLastPressMs
    var dpadSeekForwardDirection by perSource.dpadSeekForwardDirection
    var dpadSeekTick by perSource.dpadSeekTick
    var dpadSeekVisible by perSource.dpadSeekVisible
    CompiledSeparately {
    LaunchedEffect(dpadSeekTick) {
        if (dpadSeekTick == 0) return@LaunchedEffect
        dpadSeekVisible = true
        delay(TV_SEEK_INDICATOR_LINGER_MS)
        dpadSeekVisible = false
        dpadSeekPresses = 0
    }
    }
    var lastAudibleVolume by perSurface.lastAudibleVolume
    val tvPlayPauseFocus = perSurface.tvPlayPauseFocus
    val tvPlayerFocus = perSurface.tvPlayerFocus
    val focusManager = LocalFocusManager.current
    // The up-next card's "Play now", which a TV hands the remote to as the card appears; and
    // whether the ring is inside the card, which the surface's key handler and the controls'
    // auto-hide both have to leave alone.
    val nextEpisodePlayNowFocus = remember { FocusRequester() }
    var nextEpisodeCardFocused by remember { mutableStateOf(false) }
    // The bottom-right skip action, which a TV hands the remote to while it offers a timed skip
    // (see [tvSkipActionTakesRemote]); and whether the ring is on it, which OK must then reach.
    val tvSkipActionFocus = remember { FocusRequester() }
    var tvSkipActionFocused by remember { mutableStateOf(false) }
    var tvTimedSkipOffered by remember { mutableStateOf(false) }
    // Whether the ring is anywhere inside the player at all; see [tvFocusLostTarget].
    var tvPlayerHasFocus by perSurface.tvPlayerHasFocus
    // Down was the key that opened the row; read once by the effect that aims the remote at it.
    var tvControlsOpenedByDown by remember { mutableStateOf(false) }
    var tvControlsFocusRestoreRequest by perSource.tvControlsFocusRestoreRequest
    val restoreTvControlsFocus = {
        if (device.isTv && focusPlayerOnStart) {
            tvControlsVisible = true
            tvControlsInteraction++
            tvControlsFocusRestoreRequest++
        }
    }
    // Retried rather than requested once: see [TV_CONTROLS_FOCUS_ATTEMPTS] for why a single request
    // can arrive before the row it is aiming at exists, and why the throw is the only way to tell.
    val requestTvControlsFocus: suspend () -> Unit = {
        var attempt = 0
        while (
            attempt < TV_CONTROLS_FOCUS_ATTEMPTS &&
            runCatching { tvPlayPauseFocus.requestFocus() }.isFailure
        ) {
            attempt++
            delay(TV_CONTROLS_FOCUS_RETRY_MS)
        }
    }
    CompiledSeparately {
    LaunchedEffect(tvControlsVisible, focusPlayerOnStart) {
        if (!tvControlsVisible || !focusPlayerOnStart) return@LaunchedEffect
        val openedByDown = tvControlsOpenedByDown
        tvControlsOpenedByDown = false
        delay(32)
        // A row opened while a timed skip is offered opens on the skip: "Skip Intro" only shows
        // with the row, so this is the press that reveals it, and OK should answer it. A row opened
        // from the skip itself (Down, with "Skip Outro" lit) is a viewer asking for the row instead.
        if (tvTimedSkipOffered && !tvSkipActionFocused &&
            runCatching { tvSkipActionFocus.requestFocus() }.isSuccess
        ) {
            return@LaunchedEffect
        }
        // Down opens on the fixed "+Ns" instead; see [tvRowOpensOnFixedSkip]. That button is
        // composed with the row, so it is retried like the row is.
        if (tvRowOpensOnFixedSkip(
                openedByDown = openedByDown,
                fixedSkipShown = SettingsStore.showSkipButton.value &&
                    shouldShowManualSkipButton(positionMs, durationMs),
                skipActionFocused = tvSkipActionFocused,
            )
        ) {
            repeat(TV_CONTROLS_FOCUS_ATTEMPTS) {
                if (runCatching { tvSkipActionFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(TV_CONTROLS_FOCUS_RETRY_MS)
            }
        }
        requestTvControlsFocus()
    }
    LaunchedEffect(tvControlsFocusRestoreRequest, focusPlayerOnStart) {
        if (tvControlsFocusRestoreRequest == 0 || !focusPlayerOnStart) return@LaunchedEffect
        delay(64)
        requestTvControlsFocus()
        DiagnosticsLog.event(
            "PlayerSurface TV controls focus restored request=$tvControlsFocusRestoreRequest",
        )
    }
    }
    val screenReaderActive = rememberScreenReaderActive()
    val inputModeManager = LocalInputModeManager.current
    var settingsExpanded by perSurface.settingsExpanded
    var captionsExpanded by perSurface.captionsExpanded
    var captionAppearanceVisible by perSurface.captionAppearanceVisible
    // TalkBack users can't discover the hidden control row through a key press, so present it
    // as soon as the fullscreen player opens instead of waiting for the semantic reveal action.
    CompiledSeparately {
    LaunchedEffect(screenReaderActive, focusPlayerOnStart, activeStream.url) {
        if (device.isTv && screenReaderActive && focusPlayerOnStart) {
            tvControlsVisible = true
        }
    }
    LaunchedEffect(
        tvControlsVisible,
        tvControlsInteraction,
        focusPlayerOnStart,
        screenReaderActive,
        settingsExpanded,
        captionsExpanded,
        captionAppearanceVisible,
        openSubtitlesVisible,
    ) {
        if (!focusPlayerOnStart) {
            tvControlsVisible = false
            return@LaunchedEffect
        }
        if (!tvControlsVisible) return@LaunchedEffect
        // TalkBack users navigate slowly and can't reopen the controls with a key press
        // (the screen reader consumes the D-pad), so never auto-hide under a screen reader.
        if (screenReaderActive) return@LaunchedEffect
        // While the settings panel or caption dialog is up, the row must not vanish behind it:
        // hiding re-arms the "any key reopens controls" interceptor, which would then swallow
        // the panel's D-pad input and yank focus back to play/pause.
        if (settingsExpanded || captionsExpanded || captionAppearanceVisible ||
            openSubtitlesVisible || episodeDrawerExpanded
        ) {
            return@LaunchedEffect
        }
        delay(8_000)
        tvControlsVisible = false
        // The row going away is no reason to take the remote off the up-next card or a skip.
        if (!nextEpisodeCardFocused && !tvSkipActionFocused) runCatching { tvPlayerFocus.requestFocus() }
    }
    }
    val tvBackTarget = tvPlayerBackTarget(
        isTv = device.isTv,
        playerOwnsRemote = focusPlayerOnStart,
        captionAppearanceVisible = captionAppearanceVisible,
        episodeDrawerVisible = episodeDrawerExpanded,
        openSubtitlesVisible = openSubtitlesVisible,
        captionsVisible = captionsExpanded,
        settingsVisible = settingsExpanded,
        controlsVisible = tvControlsVisible,
    )
    // Retires one layer of this player's chrome, topmost first; false when it has none up and
    // Back belongs to the screen instead.
    val dismissTvChrome: () -> Boolean = dismiss@{
        when (tvBackTarget) {
            TvPlayerBackTarget.NONE -> return@dismiss false
            TvPlayerBackTarget.OPEN_SUBTITLES -> openSubtitlesVisible = false
            TvPlayerBackTarget.CAPTION_APPEARANCE -> captionAppearanceVisible = false
            TvPlayerBackTarget.EPISODE_DRAWER -> episodeDrawerExpanded = false
            TvPlayerBackTarget.CAPTIONS -> captionsExpanded = false
            TvPlayerBackTarget.SETTINGS -> settingsExpanded = false
            TvPlayerBackTarget.CONTROLS -> {
                DiagnosticsLog.event("PlayerSurface TV controls closed back")
                tvControlsVisible = false
                runCatching { tvPlayerFocus.requestFocus() }
                return@dismiss true
            }
        }
        // Dismissing a panel hands the remote back to the control row underneath it.
        restoreTvControlsFocus()
        true
    }
    // Where the remote lands once a new stream is under the player: the next episode, or the same
    // episode moved to another server. The row is deliberately not retired by either, so handing
    // the picture the remote unconditionally is what used to strand it — see [tvEpisodeChangeFocus]
    // for the whole account, including why no direction could reach the row afterwards.
    CompiledSeparately {
    LaunchedEffect(activeStream.url, playerView, device.isTv, focusPlayerOnStart, screenReaderActive) {
        if (playerView == null) return@LaunchedEffect
        delay(32)
        val watchUntilMs = SystemClock.elapsedRealtime() + TV_CONTROLS_REFOCUS_WINDOW_MS
        while (true) {
            val target = tvEpisodeChangeFocus(
                isTv = device.isTv,
                playerOwnsRemote = focusPlayerOnStart,
                screenReaderActive = screenReaderActive,
                panelVisible = settingsExpanded || captionsExpanded || captionAppearanceVisible ||
                    openSubtitlesVisible || episodeDrawerExpanded,
                controlsVisible = tvControlsVisible,
                controlsFocused = tvControlsFocused,
            )
            when (target) {
                TvEpisodeChangeFocus.UNCHANGED -> return@LaunchedEffect
                TvEpisodeChangeFocus.SURFACE -> {
                    runCatching { tvPlayerFocus.requestFocus() }
                    return@LaunchedEffect
                }
                TvEpisodeChangeFocus.CONTROLS -> {
                    DiagnosticsLog.event("PlayerSurface TV controls refocused after stream change")
                    requestTvControlsFocus()
                    return@LaunchedEffect
                }
                // See TV_CONTROLS_REFOCUS_WINDOW_MS: the row can still lose its focused control a
                // frame or two into the swap, so holding is watched rather than trusted.
                TvEpisodeChangeFocus.HOLD -> {
                    if (SystemClock.elapsedRealtime() >= watchUntilMs) return@LaunchedEffect
                    delay(TV_CONTROLS_FOCUS_RETRY_MS)
                }
            }
        }
    }
    // The net under every way of losing the remote, the ones above included: nothing inside the
    // player focused, for longer than a move between two of its controls takes, means every press
    // but Back and the media keys is landing nowhere.
    LaunchedEffect(tvPlayerHasFocus, tvControlsVisible, playerView, focusPlayerOnStart, screenReaderActive) {
        if (tvPlayerHasFocus || playerView == null) return@LaunchedEffect
        delay(TV_FOCUS_LOSS_SETTLE_MS)
        when (
            tvFocusLostTarget(
                isTv = device.isTv,
                playerOwnsRemote = focusPlayerOnStart,
                playerHasFocus = tvPlayerHasFocus,
                screenReaderActive = screenReaderActive,
                panelVisible = settingsExpanded || captionsExpanded || captionAppearanceVisible ||
                    openSubtitlesVisible || episodeDrawerExpanded,
                controlsVisible = tvControlsVisible,
            )
        ) {
            TvEpisodeChangeFocus.CONTROLS -> {
                DiagnosticsLog.event("PlayerSurface TV focus lost; refocusing controls")
                requestTvControlsFocus()
            }
            TvEpisodeChangeFocus.SURFACE -> {
                DiagnosticsLog.event("PlayerSurface TV focus lost; refocusing surface")
                runCatching { tvPlayerFocus.requestFocus() }
            }
            TvEpisodeChangeFocus.HOLD, TvEpisodeChangeFocus.UNCHANGED -> Unit
        }
    }
    }
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentHasNext by rememberUpdatedState(hasNextEpisode)
    val currentHasPrevious by rememberUpdatedState(hasPreviousEpisode)
    val canGoPrevious = hasPreviousEpisode && onPreviousEpisode != null
    val playerControls = remember(controller, hasNextEpisode, canGoPrevious) {
        controller?.let { activeController ->
            EpisodeControlPlayer(
                player = activeController,
                hasNextEpisode = hasNextEpisode,
                hasPreviousEpisode = canGoPrevious,
                onNextEpisode = { currentOnNextEpisode() },
                onPreviousEpisode = { currentOnPreviousEpisode?.invoke() },
            )
        }
    }

    // Bridges notification, remote, and hardware media-key commands into episode resolution.
    CompiledSeparately {
    DisposableEffect(Unit) {
        DiagnosticsLog.event("PlayerSurface episode navigator registered hasPrev=$hasPreviousEpisode hasNext=$hasNextEpisode")
        val navigator: (Int) -> Unit = { direction ->
            DiagnosticsLog.event("PlayerSurface episode navigator direction=$direction")
            when {
                direction > 0 && currentHasNext -> currentOnNextEpisode()
                direction < 0 && currentHasPrevious -> currentOnPreviousEpisode?.invoke()
            }
        }
        PlaybackService.episodeNavigator = navigator
        onDispose {
            if (PlaybackService.episodeNavigator === navigator) {
                PlaybackService.episodeNavigator = null
            }
            DiagnosticsLog.event("PlayerSurface episode navigator cleared")
        }
    }
    }
    // Phone draws its own Compose controls (Media3's controller is disabled below); TV keeps
    // TvPlayerControls. Visible on load, then auto-hidden a few seconds into playback.
    var phoneControlsVisible by perSurface.phoneControlsVisible
    var phoneControlsInteraction by perSurface.phoneControlsInteraction
    // Survives an episode change on purpose: someone who locked the screen to keep watching in bed
    // wants the next episode locked too, not the controls back in their face.
    var screenLocked by perSurface.screenLocked
    val trackNameProvider = remember(context) { DefaultTrackNameProvider(context.resources) }
    // Nothing drawn over the picture belongs in the mini-player; see LocalPlayerInPictureInPicture.
    // The controls are put away rather than only hidden, so they are not waiting there when the
    // window comes back to full screen.
    val inPictureInPicture = LocalPlayerInPictureInPicture.current
    LaunchedEffect(inPictureInPicture) {
        if (!inPictureInPicture) return@LaunchedEffect
        tvControlsVisible = false
        phoneControlsVisible = false
    }
    CompiledSeparately {
    LaunchedEffect(phoneControlsVisible, phoneControlsInteraction, playbackIsPlaying, device.isTv) {
        if (device.isTv || !phoneControlsVisible || !playbackIsPlaying) return@LaunchedEffect
        delay(4_000)
        phoneControlsVisible = false
    }
    }
    val currentOnControlsVisibilityChanged by rememberUpdatedState(onControlsVisibilityChanged)
    CompiledSeparately {
    LaunchedEffect(tvControlsVisible, phoneControlsVisible, device.isTv) {
        currentOnControlsVisibilityChanged?.invoke(
            if (device.isTv) tvControlsVisible else phoneControlsVisible,
        )
    }
    }
    val currentOnPlaybackActiveChanged by rememberUpdatedState(onPlaybackActiveChanged)
    // READY rather than isPlaying: a picture the viewer paused is still a picture, and a loading
    // overlay drawn over it would be a lie. Preparing the next stream drops this back to BUFFERING
    // on its own, so the caller's overlay reappears without anything having to reset it.
    //
    // ENDED is a picture too — the last frame of the episode, still on screen. Counting only READY
    // put the caller's loading overlay (scrim, spinner, series title) over every finished episode
    // that did not auto-advance, and a viewer with auto-play off read the frozen spinner as the app
    // hanging: a SHIELD report of "after full video playback no navigation buttons work".
    CompiledSeparately {
    LaunchedEffect(playbackState) {
        currentOnPlaybackActiveChanged?.invoke(playerShowsPicture(playbackState))
    }
    }
    val captionStyle by SettingsStore.captionStyle.collectAsState()
    var pinnedVideoHeight by remember(controller, stream.url) { mutableStateOf<Int?>(null) }
    fun changeVideoHeight(activeController: MediaController, height: Int?): Boolean {
        val applied = when {
            height == null -> {
                clearVideoSelection(activeController)
                if (activeStream.url != stream.url) {
                    nextStartPositionMs = activeController.currentPosition.coerceAtLeast(0)
                    activeStream = stream
                }
                DiagnosticsLog.event("PlayerSurface quality selection mode=auto")
                true
            }
            activeController.hasVideoHeight(height) -> applyVideoHeight(activeController, height)
            else -> {
                val source = nativeQualityStreams.firstOrNull { it.declaredVideoHeight() == height }
                if (source == null) {
                    DiagnosticsLog.event("PlayerSurface quality selection rejected height=$height unavailable")
                    false
                } else {
                    clearVideoSelection(activeController)
                    nextStartPositionMs = activeController.currentPosition.coerceAtLeast(0)
                    activeStream = source
                    DiagnosticsLog.event(
                        "PlayerSurface quality selection mode=manual height=$height " +
                            "source=${source.typeLabel()} host=${source.host()}",
                    )
                    true
                }
            }
        }
        if (applied) pinnedVideoHeight = height
        return applied
    }
    val defaultQuality by SettingsStore.defaultQuality.collectAsState()
    var defaultQualityApplied by perStream.defaultQualityApplied
    CompiledSeparately {
    LaunchedEffect(controller, tracksRevision, defaultQuality) {
        val activeController = controller ?: return@LaunchedEffect
        if (defaultQualityApplied || pinnedVideoHeight != null) return@LaunchedEffect
        if (defaultQuality == DefaultQuality.AUTO) return@LaunchedEffect
        // The controller is persistent across episodes, so its tracks may still describe the
        // previous media item; only apply once it is actually reporting this stream.
        if (activeController.currentMediaItem?.mediaId != activeStream.url) return@LaunchedEffect
        // Never force a track override mid-preparation: on weak TV decoders a codec reconfigure
        // during the initial BUFFERING is exactly where OMX implementations wedge. Let playback
        // reach READY on the ABR pick first; the state listener re-triggers this effect then.
        if (activeController.playbackState != Player.STATE_READY) return@LaunchedEffect
        val heights = availableVideoHeights(activeController, nativeQualityStreams)
        val target = defaultQuality.pickHeight(heights) ?: return@LaunchedEffect
        val fellBackToLowest = defaultQuality.maxHeight?.let { target > it } == true
        // A persisted default is a ceiling, not a viewer command to pin one rendition. Keeping the
        // override map empty lets ABR drop below this height when a rendition's CDN edge goes bad.
        val adaptiveCeiling = activeController.hasVideoHeight(target)
        val applied = if (adaptiveCeiling) {
            applyVideoHeightCeiling(activeController, target)
        } else {
            // Providers that publish one URL per quality have no adaptive group to constrain. Keep
            // their existing default behavior by selecting the matching source stream.
            changeVideoHeight(activeController, target)
        }
        if (!applied) return@LaunchedEffect
        defaultQualityApplied = true
        DiagnosticsLog.event(
            "PlayerSurface default quality=${defaultQuality.storedValue} target=${target}p " +
                "mode=${if (adaptiveCeiling) "adaptive-ceiling" else "separate-stream"} " +
                "fallbackToLowest=$fellBackToLowest heights=$heights",
        )
    }
    }
    // The speed to restore once a hold-to-speed gesture ends (the user's chosen playback speed).
    var preHoldSpeed by perSurface.preHoldSpeed
    var playbackGestureIsPlaying by perSurface.playbackGestureIsPlaying
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoSkipFiller by SettingsStore.autoSkipFiller.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val sleepTimer by SleepTimer.state.collectAsState()
    val pauseDetailsOverlay by SettingsStore.pauseDetailsOverlay.collectAsState()
    val playerClock by SettingsStore.playerClock.collectAsState()
    // A genuine pause, not a stall: see the overlay below for why the ready state is checked.
    val playbackPaused = !playbackIsPlaying && playbackState == Player.STATE_READY
    var pauseOverlayDue by perSurface.pauseOverlayDue
    // Timed from the pause itself rather than from the overlay's whole visibility condition. The
    // controls auto-hiding partway through must not restart the wait, and once the wait has been
    // served, dismissing the controls again should bring the panel straight back instead of
    // costing another fifteen seconds.
    CompiledSeparately {
    LaunchedEffect(playbackPaused) {
        if (!playbackPaused) {
            pauseOverlayDue = false
            return@LaunchedEffect
        }
        delay(PAUSE_OVERLAY_DELAY_MS)
        pauseOverlayDue = true
    }
    }
    val introStartMs = skip?.introStart?.times(1000)?.toLong() ?: 0L
    val introEndMs = skip?.introEnd?.times(1000)?.toLong()
    val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
    val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
    // "Previously on", which sits before the opening when a show has both. AniSkip is the only
    // source that carries it.
    val recapStartMs = skip?.recapStart?.times(1000)?.toLong() ?: 0L
    val recapEndMs = skip?.recapEnd?.times(1000)?.toLong()
    // The same windows the auto-skip and the Skip button act on, handed to the timeline so the
    // opening and the credits can be seen coming rather than only once the viewer is inside them.
    val seekMarkers = remember(skip, durationMs) { playerSeekMarkers(skip, durationMs) }
    var introAutoSkipped by remember(activeStream.url, introStartMs, introEndMs) { mutableStateOf(false) }
    var recapAutoSkipped by remember(activeStream.url, recapStartMs, recapEndMs) { mutableStateOf(false) }
    var outroAutoSkipped by remember(activeStream.url, outroStartMs, outroEndMs) { mutableStateOf(false) }
    // The auto-skip on its way, announced on the skip button first: see AUTO_SKIP_COUNTDOWN_SECONDS.
    var autoSkipCountdown by remember(activeStream.url) { mutableStateOf<AutoSkipCountdown?>(null) }
    var autoSkipSecondsLeft by remember(activeStream.url) { mutableIntStateOf(AUTO_SKIP_COUNTDOWN_SECONDS) }
    // Set once the next episode has been dealt with for this stream — played, or refused. Both count,
    // because re-offering a card the viewer just dismissed is the one thing a dismissal has to mean.
    var nextEpisodeHandled by remember(activeStream.url, outroStartMs, outroEndMs) { mutableStateOf(false) }
    var pendingSkipTargetMs by perSource.pendingSkipTargetMs
    val requestSkipSeek: (Long, String) -> Unit = { targetMs, trigger ->
        val activeController = controller
        if (activeController == null) {
            DiagnosticsLog.event(
                "PlayerSurface skip seek rejected trigger=$trigger targetMs=$targetMs controller=none",
            )
        } else {
            pendingSkipTargetMs = targetMs
            DiagnosticsLog.event(
                "PlayerSurface skip seek requested trigger=$trigger positionMs=$positionMs targetMs=$targetMs",
            )
            activeController.seekTo(targetMs)
        }
    }

    CompiledSeparately {
    LaunchedEffect(
        activeStream.url,
        skipTimingStatus,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
        durationMs,
    ) {
        DiagnosticsLog.event(
            "PlayerSurface skip metadata status=${skipTimingStatus.name.lowercase()} " +
                "introMs=$introStartMs-${introEndMs ?: "none"} " +
                "outroMs=${outroStartMs ?: "none"}-${outroEndMs ?: "none"} " +
                "recapMs=$recapStartMs-${recapEndMs ?: "none"} " +
                // The verdict, not just the numbers: a rejected outro is the difference between a
                // player that offers the next episode and one that appears to ignore its timings.
                "durationMs=$durationMs " +
                "outroIsCredits=${outroWindowIsCredits(outroStartMs, outroEndMs, durationMs)}",
        )
    }
    LaunchedEffect(positionMs, pendingSkipTargetMs) {
        val targetMs = pendingSkipTargetMs ?: return@LaunchedEffect
        if (positionMs in (targetMs - 2_500L).coerceAtLeast(0L)..(targetMs + 5_000L)) {
            DiagnosticsLog.event(
                "PlayerSurface skip seek confirmed positionMs=$positionMs targetMs=$targetMs",
            )
            pendingSkipTargetMs = null
        }
    }
    LaunchedEffect(pendingSkipTargetMs) {
        val targetMs = pendingSkipTargetMs ?: return@LaunchedEffect
        delay(5_000)
        if (pendingSkipTargetMs == targetMs) {
            DiagnosticsLog.event(
                "PlayerSurface skip seek unconfirmed positionMs=$positionMs targetMs=$targetMs",
            )
            pendingSkipTargetMs = null
        }
    }

    LaunchedEffect(playbackGestureIsPlaying) {
        if (playbackGestureIsPlaying != null) {
            delay(650)
            playbackGestureIsPlaying = null
        }
    }

    // The outro is skipped like the other two: to the end of the highlighted credits, not to the next
    // episode. It used to be left out entirely — the setting is called "skip intro and outro", and
    // the credits simply played with the up-next card on top of them. Whatever follows them now
    // plays, and credits that run to the last frame end the episode, after which auto-play decides.
    LaunchedEffect(
        autoSkipIntroOutro,
        controller,
        positionMs,
        durationMs,
        introStartMs,
        introEndMs,
        recapStartMs,
        recapEndMs,
        outroStartMs,
        outroEndMs,
        clockStreamUrl,
    ) {
        // Not until the clock is on this stream; see skipWindowsAt.
        if (clockStreamUrl != activeStream.url) return@LaunchedEffect
        // A viewer who seeks out of the window has answered the countdown themselves.
        autoSkipCountdown?.let { pending ->
            if (!isInSkipWindow(positionMs, pending.startMs, pending.endMs)) {
                DiagnosticsLog.event("PlayerSurface auto-skip ${pending.segment} called off positionMs=$positionMs")
                autoSkipCountdown = null
            }
        }
        val activeController = controller ?: return@LaunchedEffect
        if (!autoSkipIntroOutro) {
            autoSkipCountdown = null
            return@LaunchedEffect
        }
        if (!activeController.isPlaying) return@LaunchedEffect

        // Checked before the intro because it is the earlier of the two, and a recap that runs into
        // the opening would otherwise land the viewer back inside the window just skipped out of.
        if (!recapAutoSkipped && isInSkipWindow(positionMs, recapStartMs, recapEndMs)) {
            recapAutoSkipped = true
            autoSkipCountdown = AutoSkipCountdown("Recap", recapStartMs, recapEndMs ?: return@LaunchedEffect)
            return@LaunchedEffect
        }
        if (!introAutoSkipped && isInSkipWindow(positionMs, introStartMs, introEndMs)) {
            introAutoSkipped = true
            autoSkipCountdown = AutoSkipCountdown("Intro", introStartMs, introEndMs ?: return@LaunchedEffect)
            return@LaunchedEffect
        }
        // Only credits that reach the end of this file; see outroWindowIsCredits.
        if (!outroAutoSkipped && outroEndMs != null &&
            outroWindowIsCredits(outroStartMs, outroEndMs, durationMs) &&
            isInSkipWindow(positionMs, outroStartMs, outroEndMs)
        ) {
            outroAutoSkipped = true
            autoSkipCountdown = AutoSkipCountdown(
                "Outro",
                outroStartMs ?: return@LaunchedEffect,
                outroSkipTargetMs(outroEndMs, durationMs),
            )
        }
    }
    LaunchedEffect(autoSkipCountdown) {
        val pending = autoSkipCountdown ?: return@LaunchedEffect
        runAutoSkipCountdown(
            isPlaying = { playbackIsPlaying },
            onSecondsLeft = { autoSkipSecondsLeft = it },
        )
        autoSkipCountdown = null
        requestSkipSeek(pending.endMs, "auto-${pending.segment.lowercase()}")
    }
    }

    // The next episode used to arrive the instant the outro started, with no way to refuse it short
    // of turning auto-play off for good. It is offered on a countdown now instead — see
    // [nextEpisodeTrigger] for when, and [NextEpisodeCard] for why the wait is worth the delay.
    val nextEpisodeItem = nextEpisodeIndex?.let(episodes::getOrNull)
    // Derived, so the clock's twice-a-second tick only recomposes this screen when the answer
    // changes — which is twice an episode. Read straight off the position, it made every tick
    // recompose the whole player, controls hidden or not.
    val nextEpisodeTrigger by remember(outroStartMs, outroEndMs) {
        derivedStateOf {
            nextEpisodeTrigger(
                positionMs = positionMs,
                durationMs = durationMs,
                outroStartMs = outroStartMs,
                outroEndMs = outroEndMs,
            )
        }
    }
    val nextEpisodeCardVisible = autoplay &&
        // "Pause at the end of this episode" means the credits run out and nothing follows. A card
        // counting down to the next one would contradict it, and its countdown would only be
        // refused at the end anyway.
        sleepTimer != SleepTimer.State.EndOfEpisode &&
        hasNextEpisode &&
        !screenLocked &&
        !nextEpisodeHandled &&
        // The clock belongs to whichever item the controller is really on. While the next episode is
        // being prepared it is still reporting the outgoing one, near its end — and the new stream
        // url has already cleared [nextEpisodeHandled]. Reading that stale position would offer the
        // episode after this one over the top of an intro nobody has watched yet, and the countdown
        // would then take it, one episode at a time.
        clockStreamUrl == activeStream.url &&
        nextEpisodeTrigger != NextEpisodeTrigger.NONE
    // Auto-skip used to shorten this to 3s, on the reasoning that it asks for the credits to be got
    // out of the way. Three seconds is less than it takes to read the card, let alone reach "Stay",
    // so on a TV the card read as the episode changing the instant it appeared — the thing the card
    // exists to stop. The remote now lands on "Play now" as the card appears, so a viewer in a hurry
    // is one OK press away and the full wait costs them nothing.
    val nextEpisodeCountdownFrom = NEXT_EPISODE_COUNTDOWN_SECONDS
    var nextEpisodeSecondsLeft by remember(activeStream.url) {
        mutableIntStateOf(nextEpisodeCountdownFrom)
    }
    CompiledSeparately {
    // A TV hands the card the remote as it appears, so Left and Right choose between "Play now"
    // and "Stay" straight away. It had to be reached first — Up to "Play now", then Right — which
    // is a lot to ask inside a countdown. Not over a panel: that holds the remote for its own list.
    LaunchedEffect(nextEpisodeCardVisible, device.isTv, focusPlayerOnStart) {
        if (!nextEpisodeCardVisible) {
            nextEpisodeCardFocused = false
            return@LaunchedEffect
        }
        if (!device.isTv || !focusPlayerOnStart) return@LaunchedEffect
        if (settingsExpanded || captionsExpanded || captionAppearanceVisible ||
            openSubtitlesVisible || episodeDrawerExpanded
        ) {
            return@LaunchedEffect
        }
        // The card is composed this frame; the requester is attached by the next.
        delay(64)
        runCatching { nextEpisodePlayNowFocus.requestFocus() }
            .onFailure { DiagnosticsLog.throwable("PlayerSurface next-episode card unfocusable", it) }
    }
    LaunchedEffect(nextEpisodeCardVisible, activeStream.url, nextEpisodeCountdownFrom) {
        if (!nextEpisodeCardVisible) {
            nextEpisodeSecondsLeft = nextEpisodeCountdownFrom
            return@LaunchedEffect
        }
        DiagnosticsLog.event(
            "PlayerSurface next-episode card trigger=${nextEpisodeTrigger.name.lowercase()} " +
                "from=${nextEpisodeCountdownFrom}s",
        )
        while (nextEpisodeSecondsLeft > 0) {
            // A paused player is someone who has stopped watching on purpose — very often to read
            // the credits the card is sitting on. Counting on regardless would take the episode away
            // from them while they were looking at it, so the clock waits for playback.
            if (!playbackIsPlaying) {
                delay(200)
                continue
            }
            delay(1_000)
            if (playbackIsPlaying) nextEpisodeSecondsLeft -= 1
        }
        nextEpisodeHandled = true
        onAutoAdvance()
    }
    }

    val remoteModifier = if (device.isTv && focusPlayerOnStart) {
        Modifier
            .focusRequester(tvPlayerFocus)
            // Ahead of the focus target, so it reports for the picture and everything inside it.
            .onFocusChanged { tvPlayerHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                // Back has to be claimed here, ahead of everything else. Compose maps it to
                // FocusDirection.Exit before any handler runs, and the settings panel pins focus
                // with `exit = FocusRequester.Cancel` — so a press aimed at the panel was eaten by
                // the focus system and reached neither the panel's BackHandler nor the screen's.
                // Preview handlers run root-first, so this sees it before the focus system does.
                if (event.key == Key.Back) {
                    return@onPreviewKeyEvent when (event.type) {
                        // Claim the down so the focus system can't act on it, then retire one
                        // layer on the up. Declining leaves Back to the screen, which leaves
                        // fullscreen and then the episode.
                        KeyEventType.KeyDown -> tvBackTarget != TvPlayerBackTarget.NONE
                        KeyEventType.KeyUp -> dismissTvChrome()
                        else -> false
                    }
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mediaSeekOffsetMs = when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> -TV_SEEK_STEP_MS
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> TV_SEEK_STEP_MS
                    else -> null
                }
                if (mediaSeekOffsetMs != null) {
                    queueTvSeek(mediaSeekOffsetMs)
                    tvControlsVisible = true
                    tvControlsInteraction++
                    return@onPreviewKeyEvent true
                }
                // A digit names a spot outright rather than nudging from wherever the picture is:
                // 1 a tenth of the way in, 9 nine tenths, 0 back to the start. Left and Right can
                // only travel from where they find it, so "start this again" and "I was about three
                // quarters through" were a long run of presses each. See [tvNumberSeekTargetMs].
                //
                // Like Left/Right, this deliberately does not open the control row: the row takes
                // the remote with it, and the readout underneath says where the press landed.
                val numberSeekTargetMs = tvNumberSeekTargetMs(
                    keyCode = event.nativeKeyEvent.keyCode,
                    // The polled clock rather than the controller's, so the jump lands where the
                    // readout the viewer is looking at says it will.
                    durationMs = durationMs,
                )
                if (numberSeekTargetMs != null) {
                    // A panel or the episode drawer holding the remote gets its digits untouched —
                    // a number pressed at a settings list must not move the picture behind it.
                    if (settingsExpanded || captionsExpanded || captionAppearanceVisible ||
                        openSubtitlesVisible || episodeDrawerExpanded
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    // Claimed however it arrived, but only acted on once: a held key repeats every
                    // 50ms or so at the same target, and re-queueing restarts the coalescing wait,
                    // so acting on the repeats would hold the seek back until the key was released.
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        // Whatever Left/Right run this interrupts is over: the next press starts
                        // again at the smallest step instead of inheriting this jump's momentum.
                        dpadSeekPresses = 0
                        dpadSeekForwardDirection = numberSeekTargetMs >= positionMs
                        queueTvSeekTo(numberSeekTargetMs, "digit")
                        dpadSeekTick++
                        tvControlsInteraction++
                    }
                    return@onPreviewKeyEvent true
                }
                if (!opensTvPlayerControls(event.key)) return@onPreviewKeyEvent false
                // Preview handlers on this root run before the focused child sees the key, so
                // while an overlay owns the remote its input must pass through untouched. The
                // episode drawer belongs on this list as much as the panels do: without it, Left
                // or Right inside the list fell through to the timeline below, which opened the
                // control row — and the row then held the cursor behind a drawer still covering
                // the screen, with no press that could reach the list again.
                if (settingsExpanded || captionsExpanded || captionAppearanceVisible ||
                    openSubtitlesVisible || episodeDrawerExpanded
                ) {
                    return@onPreviewKeyEvent false
                }
                // The up-next card holding the remote gets Left, Right and OK: they choose between
                // its two answers and press one. Taken here, they seeked the picture and opened the
                // control row instead. Up and Down still bring the row up as they always have.
                if (nextEpisodeCardFocused &&
                    event.key != Key.DirectionUp && event.key != Key.DirectionDown
                ) {
                    return@onPreviewKeyEvent false
                }
                // Likewise OK at a skip holding the remote presses it. With the row down this used
                // to open the row instead, which is why a skip offered over bare picture could not
                // be answered without first walking back up to it.
                if (tvSkipActionFocused &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    return@onPreviewKeyEvent false
                }
                // A lit row that the ring is not on cannot be reached from here by any direction,
                // so the press puts the ring back on it. See [tvPressReclaimsControls].
                if (tvPressReclaimsControls(
                        controlsVisible = tvControlsVisible,
                        controlsFocused = tvControlsFocused,
                        overlayFocused = nextEpisodeCardFocused || tvSkipActionFocused,
                    )
                ) {
                    DiagnosticsLog.event("PlayerSurface TV controls reclaimed key=${event.key}")
                    restoreTvControlsFocus()
                    return@onPreviewKeyEvent true
                }
                // Left and Right travel the timeline and nothing else. They used to seek once and
                // open the control row, which then owned the remote: the next press walked the
                // focus ring along the buttons instead of moving the picture, so ten seconds was
                // as far as one gesture could ever get. The row is left to Up, Down and OK.
                val dpadSeekForward = when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionRight -> true
                    androidx.compose.ui.input.key.Key.DirectionLeft -> false
                    else -> null
                }
                if (dpadSeekForward != null && !tvControlsVisible) {
                    val now = SystemClock.elapsedRealtime()
                    // Claimed either way: a throttled repeat must not fall through to the focus
                    // system, which would move the ring off the player mid-gesture.
                    if (!tvDpadSeekAccepts(now - dpadSeekLastPressMs)) return@onPreviewKeyEvent true
                    dpadSeekPresses = tvDpadSeekPresses(dpadSeekPresses, now - dpadSeekLastPressMs)
                    dpadSeekLastPressMs = now
                    dpadSeekForwardDirection = dpadSeekForward
                    val step = tvScrubStepMs(dpadSeekPresses, durationMs)
                    queueTvSeek(if (dpadSeekForward) step else -step)
                    dpadSeekTick++
                    return@onPreviewKeyEvent true
                }
                tvControlsInteraction++
                // Up with the row open moves the ring itself, so a press with nowhere left to go
                // closes the row instead of leaving it to the timer. See [tvUpClosesControls].
                if (tvControlsVisible && event.key == Key.DirectionUp) {
                    val movedFocus = focusManager.moveFocus(FocusDirection.Up)
                    if (tvUpClosesControls(movedFocus, event.nativeKeyEvent.repeatCount > 0)) {
                        DiagnosticsLog.event("PlayerSurface TV controls closed up")
                        tvControlsVisible = false
                        // The row going away is no reason to take the remote off the up-next card.
                        if (!nextEpisodeCardFocused && !tvSkipActionFocused) {
                            runCatching { tvPlayerFocus.requestFocus() }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (!tvControlsVisible) {
                    DiagnosticsLog.event("PlayerSurface TV controls opened key=${event.key}")
                    tvControlsOpenedByDown = event.key == Key.DirectionDown
                    tvControlsVisible = true
                    true
                } else {
                    false
                }
            }
            // Screen readers swallow the D-pad, so the key handler above never fires under
            // TalkBack; this semantic action is the accessible way to reveal the controls.
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show player controls") {
                    tvControlsInteraction++
                    tvControlsVisible = true
                    true
                }
            }
            .focusable()
    } else {
        Modifier
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = playerTypography(),
    ) {
    Box(modifier.then(remoteModifier)) {
        AndroidView(
            factory = { ctx ->
                DiagnosticsLog.event("PlayerSurface AndroidView factory create PlayerView")
                PlayerView(ctx).apply {
                    player = playerControls
                    // Phone and TV both draw their own controls (Compose bar / TvPlayerControls),
                    // so Media3's built-in controller is off everywhere.
                    useController = false
                    isFocusable = !device.isTv
                    isFocusableInTouchMode = !device.isTv
                    setShowSubtitleButton(true)
                    // EpisodeControlPlayer maps these to app navigation; the playlist stays
                    // single-item because each episode resolves against multiple providers.
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    controllerShowTimeoutMs = if (device.isTv) 6_000 else 5_000
                    resizeMode = playerResizeMode(contentScale)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerVisible = visibility == View.VISIBLE
                            if (visibility == View.VISIBLE && device.isTv) {
                                post {
                                    findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                                        ?.requestFocus()
                                }
                            } else if (visibility != View.VISIBLE) {
                                // TV: the controller's buttons held window focus; when they go
                                // GONE focus is cleared entirely and every remote key except
                                // Back lands nowhere. Reclaim focus so D-pad/OK can resummon
                                // the controller (and reach play/pause).
                                if (device.isTv) post { requestFocus() }
                            }
                        },
                    )
                    if (onToggleFullscreen != null) {
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                    }
                    bindUnifiedSettingsButton { settingsExpanded = true }
                    playerView = this
                }
            },
            update = {
                it.player = playerControls
                it.isFocusable = !device.isTv
                it.isFocusableInTouchMode = !device.isTv
                it.resizeMode = playerResizeMode(contentScale)
                it.applyContentAspectRatio(contentAspectRatio)
                if (device.isTv) it.clearFocus()
                val controlsVisible = if (device.isTv) tvControlsVisible else phoneControlsVisible
                it.applyCaptionStyle(captionStyle, controlsVisible, device.isTv)
                it.bindUnifiedSettingsButton { settingsExpanded = true }
                DiagnosticsLog.event(
                    "PlayerSurface AndroidView update controller=${controller != null} " +
                        "size=${it.width}x${it.height} shown=${it.isShown}",
                )
            },
            onRelease = {
                it.player = null
                playerView = null
                DiagnosticsLog.event("PlayerSurface AndroidView release size=${it.width}x${it.height}")
            },
            modifier = Modifier.fillMaxSize(),
        )

        // The screen stays awake while the viewer means the episode to play, buffering included, so
        // a stall that outlasts the screen timeout does not end on a dark screen. It is released
        // on pause and at the end. This used to be a flat `keepScreenOn = true` on the view, and that
        // held the screen on for as long as the player was open, paused or finished. A viewer who
        // fell asleep woke to a flat battery, and a sleep timer that pauses could not have saved it.
        // MainActivity's window flag follows playback separately; this hold covers the buffering
        // that flag leaves out.
        val holdScreenOn = playbackWantsToPlay &&
            (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)
        LaunchedEffect(playerView, holdScreenOn) { playerView?.keepScreenOn = holdScreenOn }

        val controlsShowing = if (device.isTv) tvControlsVisible else phoneControlsVisible
        LaunchedEffect(captionStyle, controlsShowing, playerView) {
            playerView?.applyCaptionStyle(captionStyle, controlsShowing, device.isTv)
        }

        // Only a genuine pause, which is why the ready state is checked rather than just the
        // playing flag: a buffering player is also "not playing", and billing the episode while it
        // is stalled is exactly the wrong moment — it hides the spinner and reads as if playback
        // finished. Drawn here so it sits over the picture but under every control layer.
        //
        // `playbackPaused` is redundant next to `pauseOverlayDue`, which is only ever set while
        // paused, but the effect that clears it runs a frame behind the state it watches — without
        // this the panel flashes over the first frame of resumed video.
        PlayerPauseOverlay(
            visible = pauseDetailsOverlay &&
                pauseOverlayDue &&
                playbackPaused &&
                !controlsShowing &&
                !screenLocked &&
                !inPictureInPicture,
            seriesTitle = seriesTitle,
            episodeTitle = episodeTitle,
            // The server's display name, not its id: "xfdm" is what the code calls Lili.
            providerName = ProviderCatalog.label(provider),
            description = pauseDescription,
        )

        // A TV can still have a USB/Bluetooth mouse or air-mouse remote. The native PlayerView is
        // deliberately non-focusable on TV, so give pointer users the same control reveal/hide
        // behavior as a D-pad key. This layer sits below TvPlayerControls: once the bar is visible,
        // its buttons receive clicks normally while clicks on empty video dismiss it.
        if (device.isTv && focusPlayerOnStart && controller != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tvControlsVisible) {
                        detectTapGestures {
                            tvControlsInteraction++
                            if (tvControlsVisible) {
                                DiagnosticsLog.event("PlayerSurface TV controls closed pointer")
                                tvControlsVisible = false
                                runCatching { tvPlayerFocus.requestFocus() }
                            } else {
                                // Claimed before the row is shown: the reveal effect keys on
                                // `tvControlsVisible` and aims at Play/Pause 32ms later, by which
                                // point the mode has to have moved. See
                                // [claimsKeyboardModeOnPointerReveal] for what Touch mode costs.
                                val keyboardMode =
                                    if (claimsKeyboardModeOnPointerReveal(screenReaderActive)) {
                                        inputModeManager.requestInputMode(InputMode.Keyboard)
                                    } else {
                                        null
                                    }
                                // The request can be refused, and a refusal looks exactly like the
                                // bug it exists to prevent, so a bundle has to be able to tell them
                                // apart without a device in hand.
                                DiagnosticsLog.event(
                                    "PlayerSurface TV controls opened pointer " +
                                        "keyboardMode=${keyboardMode ?: "screen-reader"}",
                                )
                                tvControlsVisible = true
                            }
                        }
                    },
            )
        }

        // Keep one stable gesture surface for both visible and hidden phone controls. Previously
        // this handler was removed while the controls were showing and replaced by a single-tap
        // scrim, so double-tap pause worked or failed depending on the overlay's current state.
        // PlayerControlsScaffold is drawn above this surface, leaving its buttons clickable.
        CompiledSeparately {
        if (controller != null && !device.isTv && !screenLocked) {
            PlayerGestureControls(
                positionMs = positionMs,
                durationMs = durationMs,
                dragGestures = playerGestures,
                slideToSeek = playerSeekGesture,
                holdSpeed = holdPlaybackSpeed,
                holdSpeedEnabled = holdToSpeedEnabled,
                onTap = {
                    phoneControlsVisible = !phoneControlsVisible
                    phoneControlsInteraction++
                },
                // The sides seek by ten seconds (the gesture layer commits that through onSeek);
                // only the middle toggles playback, which is what double-tap did everywhere before.
                onDoubleTap = { zone ->
                    if (zone == PlayerDoubleTapZone.PlayPause) {
                        val active = controller ?: return@PlayerGestureControls
                        playbackGestureIsPlaying = togglePlayerPlayback(active)
                    } else {
                        phoneControlsInteraction++
                    }
                },
                onSeek = { target ->
                    controller?.seekTo(target)
                    phoneControlsInteraction++
                },
                onHoldSpeed = { active ->
                    val activeController = controller ?: return@PlayerGestureControls
                    if (active) {
                        preHoldSpeed = activeController.playbackParameters.speed
                        activeController.setPlaybackSpeed(holdPlaybackSpeed)
                    } else {
                        activeController.setPlaybackSpeed(preHoldSpeed)
                    }
                },
            )
        }
        }

        // Phone controls, shown: the shared control bar (identical to the embed player). Empty
        // video space is handled by the stable PlayerGestureControls surface underneath it.
        CompiledSeparately {
        if (controller != null && !device.isTv && phoneControlsVisible && !screenLocked) {
            PlayerControlsScaffold(
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                showBack = isFullscreen,
                fillerSkip = fillerSkip,
                isPlaying = playbackIsPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                onSeekAnchorChanged = { controlsSeekAnchor = it },
                hasPrevious = canGoPrevious,
                hasNext = hasNextEpisode,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    controller?.seekBack()
                    phoneControlsInteraction++
                },
                onPlayPause = {
                    controller?.let(::togglePlayerPlayback)
                    phoneControlsInteraction++
                },
                onForward = {
                    controller?.seekForward()
                    phoneControlsInteraction++
                },
                onNext = { currentOnNextEpisode() },
                onSeek = { target ->
                    controller?.seekTo(target)
                    phoneControlsInteraction++
                },
                onInteract = { phoneControlsInteraction++ },
                playbackSpeed = playbackSpeed,
                markers = seekMarkers,
                topRightIcons = {
                    onAddToList?.let { openList ->
                        PlayerControlIconButton(
                            "Add to My List",
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            onClick = {
                                openList()
                                phoneControlsInteraction++
                            },
                        )
                    }
                },
                bottomLeftIcons = { PlayerLockButton(onLock = { screenLocked = true }) },
            ) {
                // Labelled pills rather than bare glyphs: which of these opens subtitles and which
                // opens audio was previously something you found out by pressing one. The label is
                // dropped again on the narrow tiers, where the row really has no room for it.
                if (episodes.isNotEmpty() && onSelectEpisode != null) {
                    PlayerActionPill(
                        label = "Episodes",
                        icon = Icons.AutoMirrored.Filled.ViewList,
                        onClick = {
                            episodeDrawerExpanded = true
                            phoneControlsInteraction++
                        },
                    )
                }
                if (sourceOptions.size > 1) {
                    PlayerActionPill(
                        label = "Sources",
                        icon = Icons.Default.SwapHoriz,
                        onClick = {
                            sourcesPanelExpanded = true
                            phoneControlsInteraction++
                        },
                    )
                }
                PlayerActionPill(
                    label = "Subtitles",
                    icon = Icons.Default.ClosedCaption,
                    onClick = {
                        captionsExpanded = true
                        phoneControlsInteraction++
                    },
                )
                CastButton(Modifier.size(48.dp))
                PlayerActionPill(
                    label = if (isFullscreen) "Exit" else "Fullscreen",
                    icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    onClick = {
                        onToggleFullscreen?.invoke()
                        phoneControlsInteraction++
                    },
                )
                PlayerActionPill(
                    label = "Settings",
                    icon = Icons.Default.Settings,
                    onClick = {
                        settingsExpanded = true
                        phoneControlsInteraction++
                    },
                )
            }
        }
        }

        playbackGestureIsPlaying?.let { isPlaying ->
            PlaybackGestureIndicator(isPlaying, Modifier.align(Alignment.Center))
        }

        if (!device.isTv) {
            PlayerLockOverlay(
                locked = screenLocked,
                onUnlock = {
                    screenLocked = false
                    // Come back to visible controls, as any other player does — an unlock that left
                    // a bare picture would read as the tap not having worked.
                    phoneControlsVisible = true
                    phoneControlsInteraction++
                },
            )
        }

        if (settingsExpanded && controller != null) {
            val activeController = controller!!
            val trackHeights = availableVideoHeights(activeController, nativeQualityStreams)
            val qualityOptions = buildList {
                add(PlayerQualityOption("Auto", pinnedVideoHeight == null) { changeVideoHeight(activeController, null) })
                trackHeights.forEach { height ->
                    add(
                        PlayerQualityOption(
                            // The same wording as the quality notice, so a 2160 rung is "4K" in
                            // both places rather than "4K" over the picture and "2160p" in the menu.
                            label = qualityLabel(height) ?: "${height}p",
                            selected = pinnedVideoHeight == height,
                            onSelect = { changeVideoHeight(activeController, height) },
                            height = height,
                        ),
                    )
                }
            }
            // Keyed on the revision, not read bare: `currentTracks` is a plain getter, so a pick made
            // in this panel never recomposed it and the radio sat on the old track — see the
            // captions list below, where that read as OK doing nothing.
            val audioTracks = remember(activeController, tracksRevision) {
                trackOptions(activeController, trackNameProvider, C.TRACK_TYPE_AUDIO)
            }
            val hasAudioOverride = activeController.hasTrackOverride(C.TRACK_TYPE_AUDIO)
            val audioOptions = if (audioTracks.size > 1) {
                buildList {
                    add(PlayerQualityOption("Auto", !hasAudioOverride) { applyAudioTrack(activeController, null) })
                    audioTracks.forEach { track ->
                        add(PlayerQualityOption(track.name, hasAudioOverride && track.selected) {
                            applyAudioTrack(activeController, track)
                        })
                    }
                }
            } else {
                emptyList()
            }
            PlayerSettingsSheet(
                onDismiss = {
                    settingsExpanded = false
                    restoreTvControlsFocus()
                },
                autoplay = autoplay,
                onAutoplayChange = SettingsStore::setAutoplay,
                fullscreen = isFullscreen,
                speed = playbackSpeed,
                onSpeedChange = {
                    // Moved here as well as on the listener's word: a MediaController reports the
                    // change back over IPC, and the thumb should not wait a hop behind the finger.
                    playbackSpeed = it
                    activeController.setPlaybackSpeed(it)
                    // Remembered, so a speed chosen once carries into the next episode instead of
                    // having to be set again every time the credits roll. Only the slider writes
                    // here — hold-to-speed goes straight to the controller below, so a
                    // thumb held on the screen never becomes the standing choice.
                    SettingsStore.setDefaultPlaybackSpeed(it)
                },
                qualityOptions = qualityOptions,
                qualityMessage = dataSaverFallbackMessage(defaultQuality, trackHeights),
                audioSwitch = audioSwitch,
                onAudioSwitch = { choice ->
                    // Re-picking the side already playing must not reload it: the tab is a state
                    // readout as much as a control, and pressing it is how a viewer checks.
                    if (choice != audioSwitch?.selected) {
                        settingsExpanded = false
                        onAudioSwitch(choice)
                    }
                },
                audioOptions = audioOptions,
                audioFormat = audioOutputFormat?.label,
                contentScale = contentScale,
                onContentScaleChange = { scale ->
                    SettingsStore.setPlayerContentScale(scale)
                },
                autoSkip = autoSkipIntroOutro,
                onAutoSkipChange = SettingsStore::setAutoSkipIntroOutro,
                // Only where there is a list to move along: offline playback has no Next.
                autoSkipFiller = autoSkipFiller.takeIf { episodes.size > 1 },
                onAutoSkipFillerChange = SettingsStore::setAutoSkipFiller,
                skipTimingStatus = skipTimingStatus,
                skipReport = skipReport,
                onReportSkipTiming = onReportSkipTiming,
                onOpenInExternalPlayer = {
                    settingsExpanded = false
                    // The other player fetches the stream itself, so this one must let go of it
                    // first: two readers on the same short-lived CDN token is how a source that
                    // works alone starts refusing both.
                    val positionMs = activeController.currentPosition.coerceAtLeast(0L)
                    activeController.pause()
                    currentOnProgress?.invoke(
                        activeStream.url,
                        positionMs,
                        activeController.duration.coerceAtLeast(0L),
                    )
                    launchExternalPlayer(
                        context = context,
                        request = externalPlaybackRequest(
                            stream = activeStream,
                            seriesTitle = seriesTitle,
                            episodeTitle = episodeTitle,
                            resumePositionMs = positionMs,
                            subtitles = allSubtitles,
                        ),
                        preferredPackage = SettingsStore.externalPlayerPackage.value,
                        session = externalPlaybackSession?.invoke(activeController.duration.coerceAtLeast(0L)),
                    )
                },
            )
        }

        if (sourcesPanelExpanded) {
            PlayerSourcesSheet(
                onDismiss = {
                    sourcesPanelExpanded = false
                    restoreTvControlsFocus()
                },
                sources = sourceOptions,
                isLoading = isLoadingSources,
                fullscreen = isFullscreen,
            )
        }

        if (captionsExpanded && controller != null) {
            val activeController = controller!!
            // `currentTracks` is a plain getter, so this list has to be told when the selection moves.
            // Read bare, the panel only caught up if something else happened to recompose it: a pick
            // was applied to the player (the log said so) while the radio stayed on the old row,
            // which on a remote read as OK doing nothing.
            val subtitleTracks = remember(activeController, tracksRevision) {
                trackOptions(activeController, trackNameProvider, C.TRACK_TYPE_TEXT)
            }
            val subtitleOptions = if (subtitleTracks.isNotEmpty()) {
                buildList {
                    add(PlayerQualityOption("Off", subtitleTracks.none { it.selected }) {
                        applyTextTrack(context, activeController, null)
                        rememberCaptionChoice(category, captionsOn = false)
                    })
                    subtitleTracks.forEach { track ->
                        add(PlayerQualityOption(track.name, track.selected) {
                            applyTextTrack(context, activeController, track)
                            rememberCaptionChoice(category, captionsOn = true)
                        })
                    }
                }
            } else {
                emptyList()
            }
            val selectedTextLabel = remember(activeController, tracksRevision) {
                activeController.selectedTextLabel()
            }
            // With the merged track on screen, the pairing is read back from the dialogue track it
            // was built from, so switching it off returns to that track and not to some other one.
            val mergedSelected = attachedSubtitles.any { it.label == selectedTextLabel } &&
                signsMergeDialogueLabel(selectedTextLabel) != null
            val signsToggle = remember(subtitles, selectedTextLabel, mergedSelected, category) {
                signsPairing(
                    subtitles = subtitles,
                    selectedLabel = if (mergedSelected) {
                        signsMergeDialogueLabel(selectedTextLabel)
                    } else {
                        selectedTextLabel
                    },
                    preferDub = category.equals("dub", ignoreCase = true),
                )
            }?.let { pairing ->
                fun selectTrackLabelled(label: String): Boolean {
                    val option = subtitleTracks.firstOrNull { track ->
                        track.trackGroup.getFormat(track.trackIndex).label == label
                    } ?: return false
                    applyTextTrack(context, activeController, option)
                    rememberCaptionChoice(category, captionsOn = true)
                    return true
                }
                PlayerSignsToggle(
                    checked = mergedSelected,
                    signsLabel = pairing.signs.label,
                    dialogueLabel = pairing.dialogue.label,
                    busy = signsMergeInFlight,
                    onCheckedChange = { on ->
                        SettingsStore.setSubtitleSignsWithDialogue(on)
                        val existing = attachedSubtitles.firstOrNull { it.label == pairing.mergedLabel }
                        when {
                            !on -> selectTrackLabelled(pairing.dialogue.label)
                            existing != null && selectTrackLabelled(existing.label) -> Unit
                            else -> {
                                signsMergeInFlight = true
                                scope.launch {
                                    runCatching { buildSignsMerge(context, pairing.fetchedLike(activeStream)) }
                                        .onSuccess { merged ->
                                            // Attached and marked as the default track, the way an
                                            // imported file is: the item re-prepares in place and
                                            // comes back on the merged track.
                                            attachedSubtitles = (attachedSubtitles + merged)
                                                .distinctBy(SubtitleItem::url)
                                            selectedAttachedSubtitleUrl = merged.url
                                            rememberCaptionChoice(category, captionsOn = true)
                                            DiagnosticsLog.event(
                                                "PlayerSurface signs merged label=${merged.label.take(80)} " +
                                                    "mime=${merged.mimeType}",
                                            )
                                        }
                                        .onFailure { error ->
                                            if (error is CancellationException) throw error
                                            Toast.makeText(
                                                context,
                                                AnililiError.SUBTITLE_IMPORT_FAILED.report(
                                                    cause = error,
                                                    attributes = mapOf("stage" to "signs_merge"),
                                                ).withCode(),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    signsMergeInFlight = false
                                }
                            }
                        }
                    },
                )
            }
            PlayerCaptionsSheet(
                onDismiss = {
                    captionsExpanded = false
                    restoreTvControlsFocus()
                },
                fullscreen = isFullscreen,
                subtitleOptions = subtitleOptions,
                signsToggle = signsToggle,
                onCaptionAppearance = {
                    captionsExpanded = false
                    captionAppearanceVisible = true
                },
                syncState = subtitleSyncState.copy(available = allSubtitles.isNotEmpty()),
                onCaptureSubtitleLine = {
                    val activeController = controller
                    val target = syncableSubtitle(
                        subtitles = allSubtitles,
                        selectedAttachedUrl = selectedAttachedSubtitleUrl,
                        selectedLanguage = activeController?.selectedTextLanguage(),
                    )
                    if (activeController == null || target == null) {
                        subtitleSyncState = SubtitleSyncState(
                            available = true,
                            // Not reported: nothing failed. Someone pressed the button before
                            // choosing a track, and a diagnostics event for that would be noise.
                            errorMessage = AnililiError.SUBTITLE_NO_TRACK_SELECTED.asFailure().withCode(),
                        )
                    } else {
                        val capturedMs = activeController.currentPosition.coerceAtLeast(0L)
                        subtitleSyncState = SubtitleSyncState(available = true, isLoading = true)
                        scope.launch {
                            val loaded = runCatching {
                                withContext(Dispatchers.IO) { loadSubtitleCueTimes(context, target) }
                            }
                            subtitleSyncState = loaded.fold(
                                onSuccess = { cues ->
                                    SubtitleSyncState(
                                        available = true,
                                        capturedPositionMs = capturedMs,
                                        choices = nearestSubtitleCues(cues, capturedMs).map { cue ->
                                            SubtitleSyncChoice(
                                                startMs = cue.startMs,
                                                text = cue.text,
                                                delayMs = subtitleSyncDelayMs(capturedMs, cue),
                                            )
                                        },
                                    )
                                },
                                onFailure = { error ->
                                    SubtitleSyncState(
                                        available = true,
                                        errorMessage = AnililiError.SUBTITLE_IMPORT_FAILED.report(
                                            cause = error,
                                            attributes = mapOf("stage" to "sync_cue_load"),
                                        ).withCode(),
                                    )
                                },
                            )
                        }
                    }
                },
                onSubtitleSyncChoice = { choice ->
                    SubtitleDelay.set(choice.delayMs)
                    if (persistCaptionDelayAcrossEpisodes) {
                        SettingsStore.setPersistentCaptionDelay(animeId, SubtitleDelay.delayMs.value)
                    }
                    subtitleSyncState = SubtitleSyncState(available = true)
                    DiagnosticsLog.event("PlayerSurface subtitle synced delayMs=${choice.delayMs}")
                },
                subtitleDelayMs = subtitleDelayMs.takeIf { subtitleOptions.size > 1 },
                onSubtitleDelayChange = { requestedDelayMs ->
                    SubtitleDelay.set(requestedDelayMs)
                    if (persistCaptionDelayAcrossEpisodes) {
                        SettingsStore.setPersistentCaptionDelay(animeId, SubtitleDelay.delayMs.value)
                    }
                },
                persistDelayAcrossEpisodes = persistCaptionDelayAcrossEpisodes,
                onPersistDelayAcrossEpisodesChange = { persist ->
                    persistCaptionDelayAcrossEpisodes = persist
                    SettingsStore.setPersistentCaptionDelay(
                        animeId = animeId,
                        delayMs = SubtitleDelay.delayMs.value.takeIf { persist },
                    )
                },
                onImportSubtitle = {
                    captionsExpanded = false
                    runCatching {
                        subtitleFilePicker.launch(
                            arrayOf(
                                "text/*",
                                "application/x-subrip",
                                "application/ttml+xml",
                                "application/octet-stream",
                            ),
                        )
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            AnililiError.SUBTITLE_PICKER_MISSING.report(cause = error).withCode(),
                            Toast.LENGTH_LONG,
                        ).show()
                        restoreTvControlsFocus()
                    }
                },
                onSearchOpenSubtitles = {
                    captionsExpanded = false
                    openSubtitlesVisible = true
                },
            )
        }

        if (captionAppearanceVisible) {
            PlayerPanelTheme {
            CaptionAppearanceDialog(
                onDismiss = {
                    captionAppearanceVisible = false
                    restoreTvControlsFocus()
                },
            )
            }
        }

        if (controller == null) {
            com.anilili.ui.components.WaterFillLogoIndicator(
                modifier = Modifier.align(Alignment.Center),
                size = 72.dp,
            )
        }

        // Only while the row is down: with the row up its own bar already says the same thing.
        CompiledSeparately {
        if (device.isTv && focusPlayerOnStart && dpadSeekVisible && !tvControlsVisible) {
            TvSeekIndicator(
                positionMs = positionMs,
                durationMs = durationMs,
                forward = dpadSeekForwardDirection,
                // Top, not bottom: subtitles own the lower band, and how much of it they own is
                // the viewer's own caption-margin setting, so anything down there collides
                // eventually. Nothing else is drawn up here while the control row is down.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(TV_SAFE_AREA_INSET),
            )
        }
        }

        // The row is a fillMaxSize overlay, so anything composed before it is drawn underneath —
        // and the auto-hide effect deliberately keeps it up while a panel owns the screen. Together
        // that put the whole OpenSubtitles window behind the player: its Search button and results
        // sat under the control row, and the row's own buttons were the ones the remote reached.
        // The subtitle browser is a full-screen modal, so the row stands down while it is open;
        // tvControlsVisible itself stays true, which is what keeps the Back targets and the
        // "any key reopens the controls" interceptor behaving as they do for every other panel.
        CompiledSeparately {
        if (device.isTv && focusPlayerOnStart && tvControlsVisible && controller != null &&
            !openSubtitlesVisible
        ) {
            TvPlayerControls(
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                fillerSkip = fillerSkip,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                onSeekAnchorChanged = { controlsSeekAnchor = it },
                isPlaying = playbackIsPlaying,
                hasPrevious = canGoPrevious,
                hasNext = hasNextEpisode,
                playPauseFocusRequester = tvPlayPauseFocus,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    DiagnosticsLog.event("PlayerSurface TV control rewind")
                    queueTvSeek(-TV_SEEK_STEP_MS)
                },
                onPlayPause = {
                    DiagnosticsLog.event("PlayerSurface TV control playPause")
                    controller?.let(::togglePlayerPlayback)
                },
                onForward = {
                    DiagnosticsLog.event("PlayerSurface TV control forward")
                    queueTvSeek(TV_SEEK_STEP_MS)
                },
                onSeek = { target ->
                    DiagnosticsLog.event("PlayerSurface TV scrub seek targetMs=$target")
                    controller?.seekTo(target)
                    tvControlsInteraction++
                },
                markers = seekMarkers,
                // A pointer dragging the bar reaches none of the key handling that normally keeps
                // the row up, so it says so itself.
                onInteract = { tvControlsInteraction++ },
                onNext = currentOnNextEpisode,
                onSettings = {
                    DiagnosticsLog.event("PlayerSurface TV control settings")
                    settingsExpanded = true
                },
                onCaptions = {
                    DiagnosticsLog.event("PlayerSurface TV control captions")
                    captionsExpanded = true
                },
                onEpisodes = if (episodes.isNotEmpty() && onSelectEpisode != null) {
                    {
                        DiagnosticsLog.event("PlayerSurface TV control episodes")
                        episodeDrawerExpanded = true
                    }
                } else null,
                onSources = LocalPlayerSourcePicker.current,
                download = LocalPlayerDownload.current,
                // Reports for the whole row, not for a control: a focus event node answers for
                // every focus target under it, which is exactly the question being asked here.
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { tvControlsFocused = it.hasFocus },
            )
        }
        }

        if (episodeDrawerExpanded && episodes.isNotEmpty() && onSelectEpisode != null) {
            InPlayerEpisodeDrawer(
                episodes = episodes,
                currentIndex = currentIndex,
                artworkUrl = artworkUrl,
                historyEntry = historyEntry,
                onSelectEpisode = { index ->
                    episodeDrawerExpanded = false
                    // The drawer is closing over the item that had the remote, so the row it
                    // uncovers has to be handed focus the same way dismissing the drawer hands it
                    // back. Without this the row spends the whole resolve unreachable.
                    restoreTvControlsFocus()
                    onSelectEpisode(index)
                },
                onDismiss = {
                    episodeDrawerExpanded = false
                    restoreTvControlsFocus()
                },
            )
        }

        // Composed last so nothing in the player's own chrome is drawn over it, or reached before
        // it by the focus search.
        if (openSubtitlesVisible) {
            OpenSubtitlesPanel(
                initialQuery = seriesTitle,
                initialEpisode = episode,
                onDismiss = {
                    openSubtitlesVisible = false
                    restoreTvControlsFocus()
                },
                onAttach = attachSubtitle,
            )
        }

        val showSkipButton by SettingsStore.showSkipButton.collectAsState()
        val skipButtonSeconds by SettingsStore.skipButtonSeconds.collectAsState()
        val controlsVisible = if (device.isTv) tvControlsVisible else phoneControlsVisible
        // Every panel is composed above, so anything composed here is painted over it rather than
        // under it — the episode drawer wore a "+85s" across its rows. The control row already
        // stands down for the subtitle browser for the same reason; the skip action stands down for
        // all of them, since none of the picture it acts on is visible behind a panel anyway.
        val panelOwnsScreen = settingsExpanded || captionsExpanded || captionAppearanceVisible ||
            openSubtitlesVisible || episodeDrawerExpanded
        // The clock, the skip action and the next-episode card below all stand down for the
        // mini-player too; there is no screen in there for them to own.
        val overlaysHidden = panelOwnsScreen || inPictureInPicture

        // The clock, for the states the touch bar's own copy cannot cover: a television, whose
        // control row has no top-right stack to carry it, and a pause that has outlived the
        // controls. That second case is the one it was asked for — the question "how late will this
        // run?" is asked with the episode stopped, and on a phone the bar is one tap from gone.
        //
        // Composed here so it is painted over the picture and the controls but under nothing: every
        // panel is composed above, and a chip drawn across the episode drawer is the same fault the
        // skip action already had.
        CompiledSeparately {
        if (playerClock && !screenLocked && !overlaysHidden &&
            (controlsVisible || playbackPaused) &&
            // On a phone the bar carries its own, at this exact anchor; drawing both would double it.
            (device.isTv || !controlsVisible)
        ) {
            PlayerClockReadout(
                positionMs = positionMs,
                durationMs = durationMs,
                speed = playbackSpeed,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .playerClockAnchor(isTv = device.isTv),
            )
        }
        }

        // Composed beside the clock, under every panel, for the same reason as the clock.
        CompiledSeparately {
        if (!screenLocked && !overlaysHidden) {
            SleepTimerWarning(
                Modifier
                    .align(Alignment.TopCenter)
                    .sleepTimerWarningAnchor(isTv = device.isTv),
            )
        }
        }

        // The quality readout. It reads the decoder's own size, which is the only thing that answers
        // "what is playing": the ladder has rungs this stream may never select, and an adaptive
        // stream moves between them without anything else in the app noticing.
        //
        // Two effects rather than one, because the settle and the display answer different
        // questions. The first waits for a report to hold before accepting it, so a start-up ramp
        // announces where it landed instead of every rung it crossed. The second owns the chip's
        // time on screen and is keyed separately so that a report changing while the chip is up
        // restarts the clock, and a report that turns out to be nothing at all (a player clearing
        // its size between episodes) does not cancel the timer and leave the chip frozen on screen.
        CompiledSeparately {
        LaunchedEffect(reportedQualityLabel) {
            val label = qualityNoticeLabel(reportedQualityLabel, announcedQualityLabel)
                ?: return@LaunchedEffect
            delay(QUALITY_NOTICE_SETTLE_MS)
            // A newer report restarted this effect; only the one that settles is worth showing.
            if (reportedQualityLabel != label) return@LaunchedEffect
            announcedQualityLabel = label
            qualityNoticeText = label
            qualityNoticeVisible = true
        }
        LaunchedEffect(qualityNoticeText, qualityNoticeVisible) {
            if (!qualityNoticeVisible) return@LaunchedEffect
            delay(QUALITY_NOTICE_VISIBLE_MS)
            qualityNoticeVisible = false
        }
        if (!screenLocked && !overlaysHidden) {
            AnimatedVisibility(
                visible = qualityNoticeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .playerQualityNoticeAnchor(isTv = device.isTv),
            ) {
                qualityNoticeText?.let { PlayerQualityNotice(it) }
            }
        }
        }

        // The card and the skip button both live in the bottom-right corner, so only one of them is
        // ever offered: the card supersedes the button whenever it is up.
        NextEpisodeCard(
            // Hidden, not dismissed: its countdown still runs, so the next episode still follows.
            visible = nextEpisodeCardVisible && !inPictureInPicture,
            secondsRemaining = nextEpisodeSecondsLeft,
            nextEpisodeLabel = nextEpisodeItem?.let { next ->
                "Episode ${next.displayNumber}" + (next.distinctTitle?.let { ": $it" } ?: "")
            } ?: nextSeasonLabel?.let { "$it · Episode 1" } ?: "Next episode",
            onPlayNow = {
                nextEpisodeHandled = true
                onNextEpisode()
            },
            onCancel = {
                // Suppressed for the rest of this episode rather than re-armed a second later,
                // which is the whole point of being able to say no to it.
                nextEpisodeHandled = true
                DiagnosticsLog.event("PlayerSurface next-episode card dismissed")
            },
            playNowFocusRequester = nextEpisodePlayNowFocus.takeIf { device.isTv },
            modifier = Modifier
                .onFocusChanged { nextEpisodeCardFocused = it.hasFocus }
                .align(Alignment.BottomEnd)
                .padding(
                    end = if (device.isTv) TV_SAFE_AREA_INSET else 24.dp,
                    bottom = if (controlsVisible) {
                        controlsSeekAnchor + ControlsBarActionGap
                    } else {
                        if (device.isTv) TV_SAFE_AREA_INSET else 24.dp
                    },
                ),
        )

        // Which windows the playhead is in, derived so that the clock's tick recomposes this screen
        // only when one of the answers changes — a handful of times an episode, not twice a second.
        val skipWindows by remember(
            recapStartMs, recapEndMs, introStartMs, introEndMs, outroStartMs, outroEndMs,
        ) {
            derivedStateOf {
                skipWindowsAt(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    clockStreamUrl = clockStreamUrl,
                    streamUrl = activeStream.url,
                    recapStartMs = recapStartMs,
                    recapEndMs = recapEndMs,
                    introStartMs = introStartMs,
                    introEndMs = introEndMs,
                    outroStartMs = outroStartMs,
                    outroEndMs = outroEndMs,
                )
            }
        }
        val recapPopup = rememberTimedSkipPopup(skipWindows.recap, playbackIsPlaying, recapStartMs, recapEndMs)
        val introPopup = rememberTimedSkipPopup(skipWindows.intro, playbackIsPlaying, introStartMs, introEndMs)
        val action: Pair<String, () -> Unit>? = when {
            // See panelOwnsScreen: offered here it would be drawn over the panel, and on a TV could
            // take the remote from it. Nor in the mini-player, where nothing can press it.
            overlaysHidden -> null
            // Drawn with or without the controls, unlike the manual skips below: it is about to act
            // on its own, and it lasts seconds rather than the length of an opening. The count is
            // appended where the button is drawn, so this label stays put from one second to the
            // next and the effects keyed on it do not read each tick as a new action.
            autoSkipCountdown != null -> autoSkipCountdown!!.let { pending ->
                "Skipping ${pending.segment}" to {
                    autoSkipCountdown = null
                    requestSkipSeek(pending.endMs, "auto-${pending.segment.lowercase()}-now")
                }
            }
            // Before the intro: a show with both runs the recap first, and the two windows can abut.
            recapEndMs != null && (controlsVisible || recapPopup) &&
                skipWindows.recap ->
                "Skip Recap" to { requestSkipSeek(recapEndMs, "manual-recap") }
            // Shared with the auto-skip above rather than range-checked here: a provider reporting
            // an empty window (0-0 for "no intro") would otherwise match at that exact position and
            // offer a "Skip Intro" that seeks nowhere, hiding the fixed skip underneath it.
            //
            // Over bare picture only for its pop-up, then with the controls: a skip window is as
            // long as the opening it describes, and nothing says it starts late. ReAnime reported
            // intro 0.0-89.0 for a show that opens straight into its OP, which pinned this button
            // over the picture for the first minute and a half of the episode with no way to
            // dismiss it — worst on the inline player, where it covers much more of a small frame.
            // See TIMED_SKIP_POPUP_MS.
            introEndMs != null && (controlsVisible || introPopup) &&
                skipWindows.intro ->
                "Skip Intro" to { requestSkipSeek(introEndMs, "manual-intro") }
            nextEpisodeCardVisible -> null
            // Only an outro that reaches the end of this file: a window from the first half of it is
            // not the credits. See outroWindowIsCredits. This was "Next Episode", which threw away
            // the preview that often follows the credits and left no way to skip just the credits;
            // the next episode is the up-next card's job, at the end of the file.
            outroEndMs != null && skipWindows.creditsOutro ->
                "Skip Outro" to { requestSkipSeek(outroSkipTargetMs(outroEndMs, durationMs), "manual-outro") }
            // The fallback for shows the timings miss. It rides with the controls, so it can be
            // offered for the whole episode without ever sitting over the picture.
            showSkipButton &&
                controlsVisible &&
                skipWindows.fixedSkip ->
                "+${skipButtonSeconds}s" to {
                    val targetMs = (positionMs + skipButtonSeconds * 1_000L).coerceAtMost(durationMs)
                    requestSkipSeek(targetMs, "manual-fixed")
                }
            else -> null
        }
        val actionTakesRemote = device.isTv && focusPlayerOnStart && !screenReaderActive &&
            tvSkipActionTakesRemote(action?.first)
        SideEffect {
            tvTimedSkipOffered = actionTakesRemote
            // A removed node is not guaranteed a last focus callback.
            if (action == null) tvSkipActionFocused = false
        }
        LaunchedEffect(action?.first, playerView, device.isTv, focusPlayerOnStart, controlsVisible) {
            DiagnosticsLog.event(
                "PlayerSurface skip action=${action?.first ?: "none"} positionMs=$positionMs " +
                    "status=${skipTimingStatus.name.lowercase()}",
            )
            if (!device.isTv || !focusPlayerOnStart) return@LaunchedEffect
            when {
                // A timed skip over bare picture takes the remote, so OK alone answers it. With the
                // row up it is the row's opening that hands it over; see the effect on
                // tvControlsVisible. Taking it here as well would pull the ring off a viewer
                // already working the row — scrubbing through an opening, say.
                actionTakesRemote && !controlsVisible -> {
                    // The button is composed this frame; the requester is attached by the next.
                    delay(64)
                    runCatching { tvSkipActionFocus.requestFocus() }
                        .onFailure { DiagnosticsLog.throwable("PlayerSurface skip action unfocusable", it) }
                }
                reclaimsPlayerFocusOnSkipAction(controlsVisible) -> {
                    // Compose may focus a newly inserted skip/next action before PlayerView can
                    // reclaim focus. Return remote input to the player once this frame settles.
                    delay(32)
                    runCatching { tvPlayerFocus.requestFocus() }
                }
                // The skip the ring was on became "+Ns" once its window passed: the same node, so
                // the ring stays put, and a second OK would jump the episode on. The row has it.
                !actionTakesRemote && tvSkipActionFocused -> requestTvControlsFocus()
            }
        }
        // The other half of that: an overlay the viewer actually pressed. Both of these are
        // focusable and both vanish on the press, and Compose leaves the window with nothing
        // focused when the node holding the ring is removed — so "Next Episode" or "Play now"
        // answered from a lit control row killed every remote key until the episode was closed and
        // reopened. See [tvOverlayDismissFocus] for why it reads as one server's fault.
        val tvOverlayPresent = action != null || nextEpisodeCardVisible
        var tvOverlayWasPresent by remember { mutableStateOf(false) }
        LaunchedEffect(
            tvOverlayPresent,
            device.isTv,
            focusPlayerOnStart,
            screenReaderActive,
            panelOwnsScreen,
            controlsVisible,
            tvControlsFocused,
        ) {
            if (!device.isTv || !focusPlayerOnStart) return@LaunchedEffect
            if (tvOverlayPresent) {
                tvOverlayWasPresent = true
                return@LaunchedEffect
            }
            if (!tvOverlayWasPresent) return@LaunchedEffect
            // Cancelled and restarted if the action comes back inside the window, which is what
            // keeps a label change from being read as a dismissal.
            delay(TV_OVERLAY_DISMISS_SETTLE_MS)
            tvOverlayWasPresent = false
            when (
                tvOverlayDismissFocus(
                    isTv = device.isTv,
                    playerOwnsRemote = focusPlayerOnStart,
                    screenReaderActive = screenReaderActive,
                    panelVisible = panelOwnsScreen,
                    controlsVisible = controlsVisible,
                    controlsFocused = tvControlsFocused,
                )
            ) {
                TvEpisodeChangeFocus.CONTROLS -> {
                    DiagnosticsLog.event("PlayerSurface TV controls refocused after overlay dismissed")
                    requestTvControlsFocus()
                }
                TvEpisodeChangeFocus.SURFACE -> runCatching { tvPlayerFocus.requestFocus() }
                TvEpisodeChangeFocus.HOLD, TvEpisodeChangeFocus.UNCHANGED -> Unit
            }
        }
        action?.let { (label, onClick) -> PlayerFloatingActionFrame { compactHeight ->
            // TV sets cut off the outer ~5% of the picture, so 24.dp from the corner lands inside
            // the overscan and the button is simply never seen — reported as "skip intro doesn't
            // show on TV" while working on phones. Every other TV surface already insets 48.dp.
            val edgeInset = if (device.isTv) TV_SAFE_AREA_INSET else 24.dp
            // Bottom right either way, so the button does not move as the controls come and go —
            // it only rises far enough to clear the seek bar while they are up. It used to jump
            // from the bottom-left corner to the top of the screen, which put a fixed skip over
            // the picture and a long way from the timeline it acts on.
            //
            // It rides on the measured seek anchor rather than the bar's full height, so it sits
            // just above the timeline. Nothing can grow into that space unseen: every icon the bar
            // shows, including the ones only some sources add, hangs below the seek bar, and the
            // anchor is re-measured whenever the row changes size.
            val actionModifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = edgeInset,
                    bottom = if (controlsVisible) {
                        controlsSeekAnchor + if (compactHeight) CompactControlsBarActionGap else ControlsBarActionGap
                    } else {
                        edgeInset
                    },
                )
                // A fixed height rather than a smaller font alone: the button's own 40dp minimum is
                // most of what made it loom over the short inline frame.
                .then(if (compactHeight) Modifier.height(CompactSkipActionHeight) else Modifier)
            val controlFocus1 = rememberFocusFill()
            val countdown = autoSkipCountdown
            val countdownSeconds = countdown?.let { autoSkipSecondsLeft }
            AutoSkipButtonBounds {
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.86f)),
                colors = controlFocus1.buttonColors(
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White,
                    ),
                ),
                contentPadding = if (compactHeight) {
                    PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                } else {
                    PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                },
                // Every other TV control carries this; without it the button can hold remote focus
                // and show nothing, so on a TV the skip action looked unreachable.
                modifier = actionModifier
                    .focusRequester(tvSkipActionFocus)
                    .onFocusChanged { tvSkipActionFocused = it.hasFocus }
                    .focusFill(controlFocus1, RoundedCornerShape(10.dp))
                    .autoSkipSweep(
                        secondsLeft = countdownSeconds,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                    ),
            ) {
                Text(
                    (if (countdown != null) autoSkipCountdownLabel(countdown, autoSkipSecondsLeft) else label)
                        .uppercase(),
                    style = if (compactHeight) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            }
        } }
    }
    }
}

/**
 * Toggle the requested playback intent, not only `isPlaying`. During buffering `isPlaying` is
 * false even though playback is armed, which made a user's pause gesture call play again.
 */
private fun togglePlayerPlayback(controller: MediaController): Boolean {
    val willPlay = playerToggleWillPlay(controller.playWhenReady, controller.playbackState)
    if (willPlay) {
        // An ended player is not paused, it is finished: `playWhenReady` is still true, and
        // `play()` on its own does nothing at all. Starting again from the top is what the button
        // means there, and it is what Media3's own play/pause handling does.
        if (controller.playbackState == Player.STATE_ENDED) controller.seekToDefaultPosition()
        // A parked player (#61) is idle but still holds its item; prepare() revives it where it
        // stopped. play() alone does nothing from idle — without this, Play after Home was dead.
        if (playerTogglePreparesWhenIdle(controller.playbackState, controller.currentMediaItem != null)) {
            controller.prepare()
        }
        controller.play()
    } else {
        controller.pause()
    }
    return willPlay
}

/**
 * Whether pressing play/pause should start playback.
 *
 * Not simply `!playWhenReady`: at the end of an episode ExoPlayer leaves `playWhenReady` true, so
 * reading it alone *paused* a finished player — and the second press then called `play()` on an
 * ended one, which does nothing. The button was dead at exactly the moment a viewer with auto-play
 * off reaches for it.
 */
internal fun playerToggleWillPlay(playWhenReady: Boolean, playbackState: Int = Player.STATE_READY): Boolean =
    playbackState == Player.STATE_ENDED || !playWhenReady

/**
 * Whether pressing play on an idle player needs prepare() before play() (#61).
 *
 * A parked player — television exits pause, then stop ([com.anilili.playback.shouldParkPlaybackOnExit]) —
 * sits in STATE_IDLE still holding its item, and `play()` alone does nothing from idle. An idle
 * player with nothing held must not prepare: there is nothing to revive, and the queue it would
 * build belongs to the next setMediaItem.
 */
internal fun playerTogglePreparesWhenIdle(playbackState: Int, hasMediaItem: Boolean): Boolean =
    playbackState == Player.STATE_IDLE && hasMediaItem

/** Whether the player is drawing a frame the viewer can see — see the effect that reports it. */
internal fun playerShowsPicture(playbackState: Int): Boolean =
    playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED

/**
 * Whether a newly appeared skip action should pull remote focus back to the player surface.
 *
 * It should only when the control row is hidden, and the action is not a timed skip, which takes
 * the remote on purpose (see [tvSkipActionTakesRemote]). Anything else appearing over bare picture
 * can steal the remote, which is what this reclaim exists to undo — but
 * the fixed "+Ns" button appears *because* the row opened, so reclaiming there fights the row for
 * focus and wins by a frame. That left the controls on screen but dead to the remote, with the
 * first D-pad press landing on the row's leading button, previous episode.
 */
internal fun reclaimsPlayerFocusOnSkipAction(controlsVisible: Boolean): Boolean = !controlsVisible

/** Heights offered by the loaded tracks plus any alternate per-quality source streams. */
private fun availableVideoHeights(
    controller: MediaController,
    qualityStreams: List<StreamItem>,
): List<Int> = (
    controller.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        .flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported)
                .map { group.getTrackFormat(it).height }
        }
        .filter { it > 0 } + qualityStreams.mapNotNull(StreamItem::declaredVideoHeight)
    ).distinct().sortedDescending()

private fun applyVideoHeight(controller: MediaController, height: Int?): Boolean {
    val builder = controller.videoSelectionBuilder()
    if (height == null) {
        controller.trackSelectionParameters = builder.build()
        DiagnosticsLog.event("PlayerSurface quality selection mode=auto")
        return true
    }

    val option = controller.currentTracks.groups.asSequence()
        .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        .flatMap { group ->
            (0 until group.length).asSequence()
                .filter(group::isTrackSupported)
                .map { index -> group to index }
        }
        .firstOrNull { (group, index) -> group.getTrackFormat(index).height == height }
    if (option == null) {
        DiagnosticsLog.event("PlayerSurface quality selection rejected height=$height unavailable")
        return false
    }

    val (group, index) = option
    builder.setOverrideForType(TrackSelectionOverride(group.getMediaTrackGroup(), listOf(index)))
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        "PlayerSurface quality selection mode=manual height=$height index=$index tracks=${group.length}",
    )
    return true
}

/** Applies a maximum while leaving Media3 free to adapt among every rendition below it. */
private fun applyVideoHeightCeiling(controller: MediaController, height: Int): Boolean {
    controller.trackSelectionParameters = adaptiveVideoHeightCeiling(
        controller.trackSelectionParameters,
        height,
    )
    return true
}

internal fun adaptiveVideoHeightCeiling(
    parameters: TrackSelectionParameters,
    height: Int,
): TrackSelectionParameters = parameters.buildUpon()
    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    .clearVideoSizeConstraints()
    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
    // Only height is the user-facing constraint. A width cap inferred from 16:9 would reject
    // portrait or unusually wide video even though it satisfies the requested resolution.
    .setMaxVideoSize(Int.MAX_VALUE, height)
    .build()

private fun clearVideoSelection(controller: MediaController) {
    controller.trackSelectionParameters = controller.videoSelectionBuilder().build()
}

private fun MediaController.videoSelectionBuilder(): TrackSelectionParameters.Builder =
    trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .clearVideoSizeConstraints()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)

internal fun playerResizeMode(scale: PlayerContentScale): Int = when (scale) {
    PlayerContentScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    PlayerContentScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    PlayerContentScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

/** PlayerView's own formula, so both agree on what the picture's shape is. */
internal fun VideoSize.contentAspectRatio(): Float =
    if (width == 0 || height == 0) 0f else width * pixelWidthHeightRatio / height

/**
 * Keeps the frame the picture is measured into honest about the video's shape.
 *
 * This is PlayerView's job and PlayerView drops it: `onVideoSizeChanged` returns without doing
 * anything while the player sits in `STATE_IDLE`, and a size that arrives in that window is never
 * asked for again — the player only re-emits when the size *changes*, and it does not change on the
 * way from idle to playing. Leaving the TV player parks the episode — pause, then stop (#61) —
 * which is exactly that state, so the fresh PlayerView built on the way back in reads whatever
 * shape the controller happened to be holding mid-reset and keeps it for the whole episode.
 *
 * The captions are what shows it. `exo_subtitles` is a child of `exo_content_frame`, not a sibling,
 * so the aspect-ratio frame's height *is* the subtitle canvas's height — and media3 derives the
 * caption text size from that height ([androidx.media3.ui.SubtitleView] fractional sizing). A frame
 * measured for the wrong shape shrinks the text towards nothing: first a bare background strip where
 * the line should be, then nothing at all, because the canvas stops drawing once the size it
 * resolves reaches zero. The video keeps its own surface scaling and looks close enough to right
 * that the frame is the last thing suspected.
 *
 * Zero is passed through rather than filtered out: it is what media3 means by "shape not known", and
 * it is what releases a stale ratio the moment the item is cleared.
 */
@OptIn(UnstableApi::class)
private fun PlayerView.applyContentAspectRatio(ratio: Float) {
    findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?.setAspectRatio(ratio)
}

/**
 * Whether a subtitle track should come up selected on its own.
 *
 * [burnedInSubtitles] is the H-SUB case: the server's picture already carries the translation, so
 * switching a sidecar track on as well draws two overlapping lines of text over each other. Seen
 * on Bonk, which falls back from S-SUB to H-SUB on the same server while still returning the
 * sidecar files. The tracks stay attached and selectable — a viewer who wants a different language
 * over the burnt-in one can still pick it — they just do not turn themselves on.
 */
internal fun subtitlesEnabledByDefault(
    category: String,
    subtitlesByDefault: Boolean,
    subtitlesWithDub: Boolean,
    burnedInSubtitles: Boolean = false,
): Boolean = subtitlesByDefault &&
    !burnedInSubtitles &&
    (!category.equals("dub", ignoreCase = true) || subtitlesWithDub)

/**
 * Writes an in-player caption choice through to the settings, so the next episode opens the way the
 * viewer just asked for rather than the way it was asked before the binge started.
 */
internal fun rememberCaptionChoice(category: String, captionsOn: Boolean) {
    val next = captionDefaultsAfterChoice(
        category = category,
        captionsOn = captionsOn,
        subtitlesByDefault = SettingsStore.subtitlesByDefault.value,
        subtitlesWithDub = SettingsStore.subtitlesWithDub.value,
    )
    SettingsStore.setSubtitleDefaults(next.subtitlesByDefault, next.subtitlesWithDub)
}

/** The pair of subtitle defaults, as [captionDefaultsAfterChoice] leaves them. */
internal data class CaptionDefaults(
    val subtitlesByDefault: Boolean,
    val subtitlesWithDub: Boolean,
)

/**
 * Folds a caption switch thrown in the player back into the settings the *next* episode reads.
 *
 * Turning captions off used to last exactly one episode — the track policy is cleared and re-applied
 * for every item, by design, so the Settings default reliably wins — which meant a viewer who did
 * not want text was made to say so again at every episode of the binge. The player's own switch is
 * the control they actually reach for, so it answers for the setting behind it, the way the speed
 * slider already writes `defaultPlaybackSpeed`.
 *
 * Dubbed audio has its own switch and keeps it. Off on a dubbed episode means "not over the dub",
 * not "not ever": it clears [SettingsStore.subtitlesWithDub] and leaves subtitled episodes alone.
 * On over a dub has to set both, because the pair is an and — [subtitlesEnabledByDefault] never
 * shows text for a dub while the general default is off.
 */
internal fun captionDefaultsAfterChoice(
    category: String,
    captionsOn: Boolean,
    subtitlesByDefault: Boolean,
    subtitlesWithDub: Boolean,
): CaptionDefaults {
    val dubbed = category.equals("dub", ignoreCase = true)
    return when {
        captionsOn -> CaptionDefaults(
            subtitlesByDefault = true,
            subtitlesWithDub = subtitlesWithDub || dubbed,
        )
        dubbed -> CaptionDefaults(
            subtitlesByDefault = subtitlesByDefault,
            subtitlesWithDub = false,
        )
        else -> CaptionDefaults(
            subtitlesByDefault = false,
            subtitlesWithDub = subtitlesWithDub,
        )
    }
}

private fun MediaController.hasVideoHeight(height: Int): Boolean = currentTracks.groups.any { group ->
    group.type == C.TRACK_TYPE_VIDEO && group.isSupported &&
        (0 until group.length).any { index ->
            group.isTrackSupported(index) && group.getTrackFormat(index).height == height
        }
}

private fun MediaController.hasTrackOverride(trackType: Int): Boolean =
    trackSelectionParameters.overrides.values.any { it.type == trackType }

@OptIn(UnstableApi::class)
private fun trackOptions(
    controller: MediaController,
    trackNameProvider: DefaultTrackNameProvider,
    trackType: Int,
): List<TrackOption> = controller.currentTracks.groups
    .filter { it.type == trackType && it.isSupported }
    .flatMap { group ->
        (0 until group.length)
            .filter { group.isTrackSupported(it) }
            .map { index ->
                val format = group.getTrackFormat(index)
                TrackOption(
                    trackGroup = group.getMediaTrackGroup(),
                    trackIndex = index,
                    name = trackDisplayName(trackNameProvider.getTrackName(format), format.label),
                    selected = group.isTrackSelected(index),
                    castContentId = format.id,
                )
            }
    }
    .let { options -> collapseSameNamed(options, TrackOption::name, TrackOption::selected) }

/**
 * The name a track goes by in the picker: the stream's own label wherever it says more than the
 * language does.
 *
 * Media3's name provider leads with the language and drops the label once it has one, so ReAnime's
 * three English tracks — "Full Subtitles [9volt]", "Dubtitle(AI)" and "Signs & Songs" — all came
 * out as "English". Choosing between them was a guess, and choosing the wrong one looked like the
 * press had not landed.
 */
internal fun trackDisplayName(languageName: String, label: String?): String {
    val trimmed = label?.trim().orEmpty()
    return when {
        trimmed.isEmpty() || trimmed.equals(languageName, ignoreCase = true) -> languageName
        // "English (Full Subtitles)" already names its language; "Native" does not.
        trimmed.contains(languageName, ignoreCase = true) -> trimmed
        else -> "$languageName · $trimmed"
    }
}

/**
 * One row per name: a track the picker would list twice under the same words is listed once, as
 * the copy that is playing if either is, otherwise the first.
 *
 * Ani.pm (Settlar) hands over each subtitle twice — as a file beside the stream and again inside
 * its HLS manifest — so every language appeared twice (Saga of Tanya the Evil S2 ep 12, checked
 * 2026-09-24). Two identical rows are not a choice anyone can make; numbering them only made the
 * duplication visible. The first copy is the side-loaded file, which is also the one the delay and
 * sync-to-a-line tools read.
 */
internal fun <T> collapseSameNamed(items: List<T>, name: (T) -> String, selected: (T) -> Boolean): List<T> =
    items.groupBy(name).values.map { copies -> copies.firstOrNull(selected) ?: copies.first() }

private fun applyAudioTrack(controller: Player, option: TrackOption?) {
    val builder = controller.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    if (option != null) {
        builder.setOverrideForType(TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex)))
    }
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        if (option == null) {
            "PlayerSurface audio selection mode=auto"
        } else {
            "PlayerSurface audio selection mode=manual name=${option.name.take(80)}"
        },
    )
}

/**
 * Honours the viewer's standing choice of dub, when they have made one.
 *
 * Left to Media3 rather than matched here: it normalises two- and three-letter codes on both
 * sides before comparing, and it falls back to its own selection on a stream that does not carry
 * the language at all — which is most of them, and must stay silent rather than fail.
 *
 * This is a preference, not an override, so the audio picker in the player still wins for the
 * episode the viewer uses it on.
 */
private fun applyPreferredAudioLanguage(
    controller: Player,
    language: PreferredAudioLanguage,
    secondary: PreferredAudioLanguage = PreferredAudioLanguage.AUTOMATIC,
) {
    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
        // Ordered: media3 ranks earlier entries higher and ignores any the stream does not carry,
        // so a second choice narrows the guess without ever overriding the first.
        .setPreferredAudioLanguages(*preferredLanguageCodes(language.storedValue, secondary.storedValue))
        .build()
}

/**
 * The preference list, first choice first, with blanks and repeats removed.
 *
 * A blank is [PreferredAudioLanguage.AUTOMATIC] — "no opinion" — and an empty list is how that is
 * said to media3. Repeats are dropped because naming the same language twice is not a fallback, and
 * a secondary set to the same value as the primary is an easy thing to leave behind in the menu.
 */
internal fun preferredLanguageCodes(primary: String, secondary: String): Array<String> =
    listOf(primary, secondary)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toTypedArray()

/**
 * The subtitle counterpart: which of a stream's text tracks to take when it carries several.
 *
 * Same shape as the audio preference and for the same reasons — Media3 matches the codes and falls
 * back to its own choice on a stream that does not declare the language, which is most of them.
 * It ranks a language match above the default-track flag the prepare above sets on the first
 * subtitle, so this is what decides between provider tracks; it does not decide whether subtitles
 * are on at all, which stays with "Show subtitles by default", and the player's own subtitle
 * button still overrides it for the episode it is used on.
 */
private fun applyPreferredSubtitleLanguage(
    controller: Player,
    language: PreferredSubtitleLanguage,
    secondary: PreferredSubtitleLanguage = PreferredSubtitleLanguage.AUTOMATIC,
) {
    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
        .setPreferredTextLanguages(*preferredLanguageCodes(language.storedValue, secondary.storedValue))
        .build()
}

/**
 * The viewer's audio-language choice as the codes a track's name can be searched for, best first.
 *
 * Both forms of each language go in because the two halves of a track's name are written in
 * different vocabularies: manifests declare three-letter codes, and Media3 hands the parsed track
 * back with the two-letter one. Order still reads primary-then-secondary, which is all the ranking
 * downstream asks of this list.
 */
internal fun preferredAudioTrackCodes(
    primary: PreferredAudioLanguage,
    secondary: PreferredAudioLanguage,
): List<String> = listOf(primary, secondary)
    .filter { it != PreferredAudioLanguage.AUTOMATIC }
    .flatMap { listOf(it.shortCode, it.storedValue) }
    .filter(String::isNotBlank)
    .distinct()

internal fun applyCategoryAudioPreference(
    controller: Player,
    category: String,
    provider: String,
    preferredLanguages: List<String> = emptyList(),
): Boolean {
    val options = controller.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { index ->
                    val format = group.getTrackFormat(index)
                    TrackOption(
                        trackGroup = group.getMediaTrackGroup(),
                        trackIndex = index,
                        name = listOfNotNull(format.label, format.language).joinToString(" ").ifBlank { "Audio" },
                        selected = group.isTrackSelected(index),
                    )
                }
        }
    val decision = categoryAudioDecision(
        names = options.map(TrackOption::name),
        selectedIndex = options.indexOfFirst(TrackOption::selected).takeIf { it >= 0 },
        wantsDub = category.equals("dub", ignoreCase = true),
        preferredLanguages = preferredLanguages,
    )
    return when (decision) {
        CategoryAudioDecision.NotReady -> false
        CategoryAudioDecision.Settled -> true
        is CategoryAudioDecision.Select -> {
            val preferred = options[decision.index]
            applyAudioTrack(controller, preferred)
            DiagnosticsLog.event(
                "PlayerSurface multi-audio selected provider=$provider category=$category " +
                    "name=${preferred.name.take(80)}",
            )
            true
        }
    }
}

/** What [categoryAudioDecision] concluded about a stream's audio tracks. */
internal sealed interface CategoryAudioDecision {
    /** The player has not published the stream's tracks yet; the question has to be asked again. */
    object NotReady : CategoryAudioDecision

    /** Nothing to do: the right track is already playing, or none of them answers the category. */
    object Settled : CategoryAudioDecision

    /** Select the track at this position in the list the caller passed in. */
    data class Select(val index: Int) : CategoryAudioDecision
}

/**
 * Which of a stream's audio tracks the sub/dub category asks for, given their [names].
 *
 * The [NotReady] case is the whole reason this is a three-way answer rather than a nullable index.
 * Media3 reports an empty track list the instant a new item is prepared and the real one only once
 * the manifest has been read — measured 177ms apart on a Galaxy S25. The caller applies this
 * preference once per media item, so answering "settled" to the empty list spent that single
 * chance: switching a dual-audio server from Sub to Dub mid-episode re-resolved, re-prepared, and
 * then never looked at the track list that actually held the English track, leaving Japanese
 * playing with the viewer's Dub selection showing in the picker. Opening the same episode on Dub
 * from cold worked, because a player with no previous item never reports the empty list.
 *
 * A genuinely single-audio stream is reported [NotReady] too, and so is re-examined on every track
 * change. That costs a list scan and cannot be told apart from a list still being built; the wrong
 * guess in the other direction silently drops the dub.
 */
internal fun categoryAudioDecision(
    names: List<String>,
    selectedIndex: Int?,
    wantsDub: Boolean,
    preferredLanguages: List<String> = emptyList(),
): CategoryAudioDecision {
    if (names.size < 2) return CategoryAudioDecision.NotReady
    // Taking a track only because it is not the original audio is an answer only where the
    // original audio is what would otherwise play. A stream of nothing but dubs is already a dub,
    // whichever one it starts on, and choosing between them by position would be a coin toss
    // dressed up as a decision — so that case is left to the manifest's own default.
    val hasNativeTrack = names.any(::isNativeAudioName)
    fun rank(index: Int): Int {
        val rank = categoryAudioRank(names[index], wantsDub, preferredLanguages)
        return if (rank == ANY_DUB_RANK && !hasNativeTrack) 100 else rank
    }
    val preferred = names.indices.minByOrNull(::rank)
        ?.takeIf { rank(it) < 50 }
        ?: return CategoryAudioDecision.Settled
    return if (preferred == selectedIndex) {
        CategoryAudioDecision.Settled
    } else {
        CategoryAudioDecision.Select(preferred)
    }
}

/**
 * How well one track answers the category, lowest first; 50 or more means "does not answer it".
 *
 * [preferredLanguages] is the viewer's standing audio-language choice, primary first, in whatever
 * codes it is stored in. It ranks *within* the category rather than against it: on these servers
 * the category is a track, so "Dub" is a request for this episode and the setting is a request
 * about dubs in general. Letting the setting answer the whole question is what made a Dub play
 * Japanese — the commonest possible setting, "Preferred audio language: Japanese", silently meant
 * "never give me a dub" on ReAnime and KickAssAnime, where every other server would have handed
 * over a separate dub file that the setting could not touch.
 *
 * So a dub never lands on the original Japanese track, and it prefers, in order: the language the
 * viewer asked for, English, then any other dub the stream carries — one of which is still closer
 * to Dub than the Japanese the request was made to get away from. A sub is the mirror: the
 * original audio, which is the whole of what Sub means.
 */
internal fun categoryAudioRank(
    name: String,
    wantsDub: Boolean,
    preferredLanguages: List<String> = emptyList(),
): Int {
    val lower = name.lowercase()
    return if (wantsDub) {
        val preference = preferredLanguages.indexOfFirst { code ->
            code.isNotBlank() && (lower.contains(" $code") || lower == code)
        }
        when {
            isNativeAudioName(name) -> 100
            preference >= 0 -> preference
            lower.contains("english") || lower.contains(" eng") || lower == "en" -> 5
            lower.contains("dub") -> 6
            else -> ANY_DUB_RANK
        }
    } else {
        if (isNativeAudioName(name)) 0 else 100
    }
}

/** The rank of a track that answers a dub request only by not being the original audio. */
private const val ANY_DUB_RANK = 40

/**
 * Whether a track name is the original Japanese audio — the one a dub must never land on.
 *
 * ReAnime names it "Native" rather than "Japanese", so the language code carries as much of this
 * as the label does and both are read.
 */
internal fun isNativeAudioName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains("japanese") || lower.contains(" jpn") || lower.contains(" ja") ||
        lower == "ja" || lower.contains("native")
}


/** The label of the text track on screen — a sidecar's [SubtitleItem.label] — or null when off. */
private fun Player.selectedTextLabel(): String? = currentTracks.groups
    .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
    ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
    ?.label

private fun applyTextTrack(context: Context, controller: MediaController, option: TrackOption?) {
    if (
        controller.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE &&
        applyCastTextTrack(context, option?.castContentId)
    ) {
        DiagnosticsLog.event(
            if (option == null) {
                "PlayerSurface Cast subtitle selection mode=off"
            } else {
                "PlayerSurface Cast subtitle selection name=${option.name.take(80)}"
            },
        )
        return
    }
    val builder = controller.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
    if (option != null) {
        builder.setOverrideForType(TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex)))
    }
    controller.trackSelectionParameters = builder.build()
    DiagnosticsLog.event(
        if (option == null) {
            "PlayerSurface subtitle selection mode=off"
        } else {
            "PlayerSurface subtitle selection mode=manual name=${option.name.take(80)}"
        },
    )
}

/**
 * Overrides the style PlayerView installs in its constructor. Without this, [SubtitleView] falls
 * back to [CaptionStyleCompat.DEFAULT] — white on *fully opaque* black — for everyone who hasn't
 * turned on captions in the system's Accessibility settings, which paints a solid box over the
 * picture.
 */
@OptIn(UnstableApi::class)
internal fun PlayerView.applyCaptionStyle(
    style: CaptionStyle,
    controlsVisible: Boolean = false,
    isTv: Boolean = false,
) {
    val view = subtitleView ?: return
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setViewType(SubtitleView.VIEW_TYPE_CANVAS)
    view.setStyle(
        CaptionStyleCompat(
            style.textArgb,
            style.backgroundArgb,
            android.graphics.Color.TRANSPARENT, // window: the box behind the whole line
            style.edgeStyle.toMedia3EdgeType(),
            style.edgeArgb,
            style.font.typeface(style.boldText),
        ),
    )
    // `ignorePadding = true`, so the size is a fraction of the whole canvas rather than of what the
    // margin leaves of it (`SubtitleViewUtils.resolveTextSize`). Without it the two placement
    // settings are also a size setting — a caption dragged three quarters of the way up the picture
    // arrives a quarter of the size it left at, which reads as the text control having been reset.
    view.setFractionalTextSize(
        SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.textScalePercent / 100f,
        /* ignorePadding= */ true,
    )
    // The controls carry chrome at both edges, so the extra gap is worth the same whichever one the
    // captions are held against.
    val effectiveMarginPercent = if (controlsVisible) {
        (style.edgeMarginPercent + 10).coerceAtMost(CaptionStyle.MAX_EDGE_MARGIN_PERCENT)
    } else {
        style.edgeMarginPercent
    }
    // `setBottomPaddingFraction` is only consulted for a cue that carries no vertical position of
    // its own, and subtitle files position most of their lines — which is why the bottom-margin
    // setting looked like it did nothing on real episodes while working on a test clip. Padding is
    // taken off the canvas before any cue is placed, so it lifts positioned and unpositioned lines
    // alike; the fraction goes to zero so the two cannot both apply to the same line.
    view.setBottomPaddingFraction(0f)
    view.applyCaptionEdgeInset(
        percent = effectiveMarginPercent,
        sidePercent = if (isTv) TV_CAPTION_SIDE_SAFE_PERCENT else 0,
        position = style.position,
    )
}

/**
 * How much of the picture's width a television holds clear on each side of a caption.
 *
 * Media3 gives a caption line almost no side margin of its own: `SubtitlePainter` wraps the text at
 * `parentWidth - 2 * (0.125 * textSize)`, which is seven pixels an edge on a 1080p frame. A set that
 * crops its edges — most of them do — then eats the ends of every line that grows wide enough to
 * reach them, which is why the captions read fine at the smallest size and lose their edges at the
 * next step up rather than at some obviously huge one. Five percent is the Android TV safe area and
 * the same margin the TV dialogs already keep (§27).
 */
internal const val TV_CAPTION_SIDE_SAFE_PERCENT = 5

/** The gaps and the edge the captions are held against, kept together because one tag carries all. */
private data class CaptionEdgeInset(val percent: Int, val sidePercent: Int, val position: CaptionPosition)

/**
 * Holds the subtitle canvas [percent] of its own height clear of the edge the captions sit on, and
 * [sidePercent] of its width clear of each side.
 *
 * Padding rather than a cue offset, because it also decides the text size media3 derives from the
 * padded height — so the captions read the same size against either edge. Which edge they are
 * anchored to is [com.anilili.playback.raiseCuesToTop]'s job; this only opens the gap.
 *
 * The side gap costs the text nothing: media3 resolves a fractional size from the height alone, so
 * narrowing the canvas only wraps a long line one word earlier instead of running it off the set.
 *
 * The size is not known on the first pass — the view is styled before it is laid out — so the inset
 * is re-applied on every layout change rather than only now. One listener per view, kept behind a
 * tag, because the style is re-applied on every recomposition.
 */
@OptIn(UnstableApi::class)
private fun SubtitleView.applyCaptionEdgeInset(percent: Int, sidePercent: Int, position: CaptionPosition) {
    setTag(R.id.caption_edge_inset, CaptionEdgeInset(percent, sidePercent, position))
    fun inset() {
        val wanted = getTag(R.id.caption_edge_inset) as? CaptionEdgeInset ?: return
        val gap = (height * wanted.percent / 100f).toInt()
        val side = (width * wanted.sidePercent / 100f).toInt()
        val top = if (wanted.position == CaptionPosition.TOP) gap else 0
        val bottom = if (wanted.position == CaptionPosition.TOP) 0 else gap
        if (paddingTop != top || paddingBottom != bottom || paddingLeft != side || paddingRight != side) {
            setPadding(side, top, side, bottom)
        }
    }
    if (getTag(R.id.caption_edge_inset_listener) == null) {
        // Measured from the new size on the frame *after* the layout that changed it, never from
        // inside the layout pass itself. `setPadding` asks for a layout, and a layout requested
        // while one is already running is honoured at most once more — a window that changes size
        // for several frames running (the status bar animating in over the player, a rotation,
        // leaving picture-in-picture) can lose it. The canvas is then left with a child laid out
        // for the old height while `clipToPadding` cuts at the new padding line, which is the
        // caption sliced through the middle: the top half draws and the rest is clipped away.
        // Posting costs one frame of the previous gap, which is a caption a few pixels off its
        // mark rather than half a caption.
        val reinset = Runnable { inset() }
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.removeCallbacks(reinset)
            view.post(reinset)
        }
        setTag(R.id.caption_edge_inset_listener, listener)
        addOnLayoutChangeListener(listener)
    }
    inset()
}

@OptIn(UnstableApi::class)
private fun CaptionEdgeStyle.toMedia3EdgeType(): Int = when (this) {
    CaptionEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    CaptionEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    CaptionEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
}

@OptIn(UnstableApi::class)
private fun PlayerView.bindUnifiedSettingsButton(onClick: () -> Unit) {
    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
        showController()
        onClick()
    }
}

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}

internal fun Float.formatPlaybackSpeed(): String = if (this % 1f == 0f) {
    "${toInt()}x"
} else {
    "${this}x"
}

private data class TrackOption(
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val name: String,
    val selected: Boolean,
    val castContentId: String? = null,
)

/**
 * Speeds offered by the player, as slider notches.
 *
 * The old list spent eight of its fifteen steps between 0.9x and 1.3x, which made every notch
 * narrow and hard to land on. Two nudge steps either side of normal are enough for the drift this
 * was there to correct; the room that frees up goes to the fast end, where rewatching lives.
 *
 * Past roughly 2x, time-stretched audio gets steadily harder to follow, and some hardware decoders
 * struggle beyond 3x — these are offered, not recommended.
 */
internal val PlaybackSpeeds = listOf(
    0.25f,
    0.5f,
    0.75f,
    0.9f,
    1f,
    1.1f,
    1.25f,
    1.5f,
    1.75f,
    2f,
    2.5f,
    3f,
    3.5f,
    4f,
)

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}

private fun StreamItem.typeLabel(): String = when {
    isEmbed -> "embed"
    isHls -> "hls"
    isDash -> "dash"
    else -> "direct"
}

private fun StreamItem.host(): String =
    runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"

/**
 * Whether this is a file on the device rather than a stream from a server.
 *
 * A download plays from `file:` when the export wrote it into Downloads and from `content:` when it
 * came back through a document provider; the segment cache plays from neither. No server can be
 * blamed for either, which is what separates `PLAY-05` from the rest of the playback entries.
 */
private fun StreamItem.isLocalMedia(): Boolean =
    url.startsWith("file:", ignoreCase = true) || url.startsWith("content:", ignoreCase = true)

private fun StreamItem.declaredVideoHeight(): Int? = height ?: declaredVideoHeight(quality)

internal fun declaredVideoHeight(label: String?): Int? = label
    ?.let { Regex.cached("""(?<!\d)(\d{3,4})p\b""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
    ?.toIntOrNull()
    ?.takeIf { it in 144..4320 }

internal fun dataSaverFallbackMessage(
    quality: DefaultQuality,
    availableHeights: List<Int>,
): String? {
    if (quality != DefaultQuality.P360 || availableHeights.isEmpty() || 360 in availableHeights) return null
    val selectedHeight = quality.pickHeight(availableHeights) ?: return null
    return "360p is unavailable on this server. Data Saver is using ${selectedHeight}p."
}

private fun androidx.media3.common.Tracks.diagnosticSummary(): String = groups
    .filter { it.isSupported }
    .joinToString(separator = ";", limit = 12, truncated = "…") { group ->
        val type = when (group.type) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            else -> "type${group.type}"
        }
        val options = (0 until group.length)
            .filter(group::isTrackSupported)
            .joinToString(separator = ",", limit = 12, truncated = "…") { index ->
                val format = group.getTrackFormat(index)
                val label = when (group.type) {
                    C.TRACK_TYPE_VIDEO -> format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "unknown"
                    else -> listOfNotNull(format.label, format.language).joinToString("/").ifBlank { "unknown" }
                }
                label + if (group.isTrackSelected(index)) "*" else ""
            }
        "$type=$options"
    }

/**
 * The pairing as the player fetches it. The player sends the stream's own Referer and headers with
 * every sidecar request, so a track that names no profile of its own is fetched with those.
 */
private fun SignsPairing.fetchedLike(stream: StreamItem): SignsPairing {
    fun SubtitleItem.profiled() = copy(
        referer = referer ?: stream.referer,
        headers = headers.ifEmpty { stream.headers },
    )
    return SignsPairing(signs = signs.profiled(), dialogue = dialogue.profiled())
}

/** How long a dub's first prepare waits for its signs-plus-dialogue file before going without it. */
private const val SIGNS_MERGE_START_WAIT_MS = 2_500L

private fun mimeFor(url: String): String = when {
    url.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    url.contains(".ass", ignoreCase = true) || url.contains(".ssa", ignoreCase = true) ->
        MimeTypes.TEXT_SSA
    url.contains(".ttml", ignoreCase = true) || url.contains(".xml", ignoreCase = true) ->
        MimeTypes.APPLICATION_TTML
    else -> MimeTypes.TEXT_VTT
}

/** Container MIME supplied to both the local player and Cast receiver. */
@OptIn(UnstableApi::class)
internal fun videoMimeType(type: String, url: String): String {
    val normalizedType = type.trim().lowercase()
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalizedType == "hls" || path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        normalizedType in setOf("dash", "mpd") || path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        normalizedType in setOf("webm") || path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        normalizedType in setOf("mkv", "matroska") || path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        normalizedType in setOf("ts", "mpegts") || path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
        else -> MimeTypes.VIDEO_MP4
    }
}


/*
 * PlayerSurface's state, grouped by what resets it.
 *
 * These were individual `remember { mutableStateOf(...) }` calls, and every one of them is a group
 * of slot-table code inlined into [PlayerSurface] itself. With forty-odd of them the method sat
 * over ART's 10 000-unit JIT ceiling in debug builds and within a few hundred units of it in
 * release, where one more feature would have tipped the native player into the interpreter for the
 * life of the process — see [com.anilili.ui.components.CompiledSeparately]. As fields of one object
 * per reset key they cost one group each, and nothing that uses them changes: each local is still a
 * delegate, now to a field here instead of to a state of its own.
 *
 * A state belongs in a holder only if it was remembered under exactly that holder's key and its
 * initial value needs nothing the holder is not given. Anything else keeps its own `remember`,
 * because moving it would change when it resets.
 */

/** The 26 states that lives as long as the player surface itself: never reset. */
@Stable
private class PlayerSurfaceState() {
    val controller = mutableStateOf<MediaController?>(null)
    val playbackIsPlaying = mutableStateOf(false)
    val tracksRevision = mutableIntStateOf(0)
    val positionMs = mutableLongStateOf(0L)
    val durationMs = mutableLongStateOf(0L)
    val bufferedPositionMs = mutableLongStateOf(0L)
    val clockStreamUrl = mutableStateOf<String?>(null)
    val controlsSeekAnchor = mutableStateOf(0.dp)
    val playerView = mutableStateOf<PlayerView?>(null)
    val controllerVisible = mutableStateOf(false)
    val tvControlsVisible = mutableStateOf(false)
    val tvControlsFocused = mutableStateOf(false)
    val tvPlayerHasFocus = mutableStateOf(false)
    val tvControlsInteraction = mutableIntStateOf(0)
    val lastAudibleVolume = mutableFloatStateOf(1f)
    val tvPlayPauseFocus = FocusRequester()
    val tvPlayerFocus = FocusRequester()
    val settingsExpanded = mutableStateOf(false)
    val captionsExpanded = mutableStateOf(false)
    val captionAppearanceVisible = mutableStateOf(false)
    val phoneControlsVisible = mutableStateOf(true)
    val phoneControlsInteraction = mutableIntStateOf(0)
    val screenLocked = mutableStateOf(false)
    val preHoldSpeed = mutableFloatStateOf(1f)
    val playbackGestureIsPlaying = mutableStateOf<Boolean?>(null)
    val pauseOverlayDue = mutableStateOf(false)
}

/** The 11 states that resets when the screen hands the player a different stream. */
@Stable
private class PlayerStreamState(stream: StreamItem, startPositionMs: Long) {
    val activeStream = mutableStateOf(stream)
    val nextStartPositionMs = mutableLongStateOf(startPositionMs)
    val decoderRetryDone = mutableStateOf(false)
    val pendingTvSeekTargetMs = mutableStateOf<Long?>(null)
    val tvSeekRequest = mutableIntStateOf(0)
    val lastUserSeekRealtimeMs = mutableLongStateOf(Long.MIN_VALUE)
    val lastUserSeekTargetMs = mutableLongStateOf(startPositionMs.coerceAtLeast(0L))
    val seekErrorRecoveryDone = mutableStateOf(false)
    val recoverySeekInFlight = mutableStateOf(false)
    val episodeDrawerExpanded = mutableStateOf(false)
    val defaultQualityApplied = mutableStateOf(false)
}

/** The 5 states that resets when the MediaController is reconnected. */
@Stable
private class PlayerControllerState() {
    val playbackState = mutableIntStateOf(Player.STATE_IDLE)
    val playbackWantsToPlay = mutableStateOf(false)
    val contentAspectRatio = mutableFloatStateOf(0f)
    val preparedSubtitleSignature = mutableStateOf<String?>(null)
    val playbackSpeed = mutableFloatStateOf(1f)
}

/** The 13 states that reset whenever the stream actually playing changes, including a failover the screen never asked for. */
@Stable
private class PlayerSourceState() {
    val stallFailoverTriggered = mutableStateOf(false)
    val stallRetried = mutableStateOf(false)
    val dpadSeekPresses = mutableIntStateOf(0)
    val dpadSeekLastPressMs = mutableLongStateOf(0L)
    val dpadSeekForwardDirection = mutableStateOf(true)
    val dpadSeekTick = mutableIntStateOf(0)
    val dpadSeekVisible = mutableStateOf(false)
    val tvControlsFocusRestoreRequest = mutableIntStateOf(0)
    val pendingSkipTargetMs = mutableStateOf<Long?>(null)
    /** What the decoder reports now; null until it has a picture to describe. */
    val reportedQualityLabel = mutableStateOf<String?>(null)
    /** What the viewer has already been told, so the same height is not announced twice. */
    val announcedQualityLabel = mutableStateOf<String?>(null)
    /** The chip's text, kept after it hides so the fade-out has something to fade. */
    val qualityNoticeText = mutableStateOf<String?>(null)
    val qualityNoticeVisible = mutableStateOf(false)
}

/** Which skip windows the playhead is in; see where it is derived for why it is one value. */
internal data class SkipWindows(
    val recap: Boolean,
    val intro: Boolean,
    val creditsOutro: Boolean,
    val fixedSkip: Boolean,
) {
    companion object {
        val NONE = SkipWindows(recap = false, intro = false, creditsOutro = false, fixedSkip = false)
    }
}

/**
 * The windows the clock's [positionMs] falls in, or none while the clock is still measuring another
 * stream than the one whose windows these are ([clockStreamUrl] against [streamUrl]).
 *
 * Through an episode change the new episode's windows arrive before the controller has loaded it,
 * and until it has, the clock can still carry the outgoing episode's position — even once the new
 * media id is on it, which is why the clock waits for the new item to be ready before it names it.
 * One Punch Man went from E1 at 2:30 to E2, whose intro runs 2:03-3:33, and "Skip Intro" flashed up
 * over E2's loading screen and took the TV remote with it. With auto-skip on, the same stale reading
 * would start the intro countdown and spend the episode's one auto-skip before its opening began.
 */
internal fun skipWindowsAt(
    positionMs: Long,
    durationMs: Long,
    clockStreamUrl: String?,
    streamUrl: String,
    recapStartMs: Long?,
    recapEndMs: Long?,
    introStartMs: Long?,
    introEndMs: Long?,
    outroStartMs: Long?,
    outroEndMs: Long?,
): SkipWindows {
    if (clockStreamUrl != streamUrl) return SkipWindows.NONE
    return SkipWindows(
        recap = isInSkipWindow(positionMs, recapStartMs, recapEndMs),
        intro = isInSkipWindow(positionMs, introStartMs, introEndMs),
        creditsOutro = outroWindowIsCredits(outroStartMs, outroEndMs, durationMs) &&
            isInSkipWindow(positionMs, outroStartMs, outroEndMs),
        fixedSkip = shouldShowManualSkipButton(positionMs, durationMs),
    )
}

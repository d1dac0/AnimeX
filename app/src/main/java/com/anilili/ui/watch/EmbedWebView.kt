package com.anilili.ui.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.StreamItem
import com.anilili.data.model.hasUsableWindow
import com.anilili.data.settings.CaptionEdgeStyle
import com.anilili.data.settings.CaptionPosition
import com.anilili.data.settings.CaptionStyle
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.report
import com.anilili.playback.SleepTimer
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberScreenReaderActive
import com.anilili.ui.components.CaptionAppearanceDialog
import com.anilili.ui.components.CompiledSeparately
import com.anilili.util.webViewRefererHeaders
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.anilili.util.cached

/**
 * Renders an embed/iframe provider (or the live site) in a WebView. This is both the player for
 * `type:"embed"` sources and the durable fallback when the native path is Cloudflare-blocked.
 *
 * Main-frame navigation is pinned to the embed's own host: ad-funded embed pages fire same-frame
 * redirects that would otherwise replace the video with an ad page. Resource and iframe loads are
 * unaffected, so the players themselves keep working.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EmbedWebView(
    url: String,
    referer: String?,
    modifier: Modifier = Modifier,
    qualityStreams: List<StreamItem> = emptyList(),
    startPositionMs: Long = 0L,
    skip: SkipTimes? = null,
    skipTimingStatus: SkipTimingStatus = if (skip?.hasUsableWindow() == true) {
        SkipTimingStatus.PROVIDER
    } else {
        SkipTimingStatus.UNAVAILABLE
    },
    skipReport: SkipReportState = SkipReportState.IDLE,
    onReportSkipTiming: (() -> Unit)? = null,
    /**
     * True when the player took this episode itself at the end of the last one, which starts it
     * even with "Play when an episode opens" off. See [WatchData.autoAdvanced].
     */
    autoAdvanced: Boolean = false,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    /** The outro window taking the next episode on its own, as opposed to [onNextEpisode]'s press. */
    onAutoAdvance: (() -> Unit)? = onNextEpisode,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    focusPlayerOnStart: Boolean = true,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    /** Reports (streamUrl, positionMs, durationMs); the url is the page the ticks came from. */
    onProgress: ((streamUrl: String, positionMs: Long, durationMs: Long) -> Unit)? = null,
    onPlaybackError: ((failure: AnililiFailure, streamUrl: String, positionMs: Long) -> Unit)? = null,
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
    /** Fires whenever the player's own chrome appears or hides, whichever bar this device draws. */
    onControlsVisibilityChanged: ((Boolean) -> Unit)? = null,
    /**
     * Reports whether the embed has anything to show yet, so the caller can keep its loading state
     * up until it does. False again the moment a different page is loaded.
     */
    onPlaybackActiveChanged: ((Boolean) -> Unit)? = null,
    episodes: List<EpisodeItem> = emptyList(),
    currentIndex: Int = 0,
    artworkUrl: String? = null,
    seriesTitle: String = "",
    episodeTitle: String = "",
    onSelectEpisode: ((Int) -> Unit)? = null,
    onAddToList: (() -> Unit)? = null,
    /** Sub/dub for this episode, when it has both; null hides the switch. */
    audioSwitch: PlayerAudioSwitch? = null,
    onAudioSwitch: (PlayerAudioChoice) -> Unit = {},
    /**
     * The audio this page is playing, as the source route names it ("sub"/"dub"). Only the caption
     * defaults read it: "no subtitles over the dub" is a separate setting from "no subtitles", and
     * without the category a choice made here could not tell the sheet which one was being answered.
     */
    category: String = "",
    /** The FILLER tag and its skip; null when this episode is not filler. */
    fillerSkip: PlayerFillerSkip? = null,
) {
    DisposableEffect(Unit) { com.anilili.playback.PlaybackStatus.embedMounted(); onDispose { com.anilili.playback.PlaybackStatus.embedUnmounted() } }
    val device = LocalAppDeviceProfile.current
    val playerGestures by SettingsStore.playerGestures.collectAsState()
    val playerSeekGesture by SettingsStore.playerSeekGesture.collectAsState()
    val holdToSpeedEnabled by SettingsStore.holdToSpeedEnabled.collectAsState()
    val holdPlaybackSpeed by SettingsStore.holdPlaybackSpeed.collectAsState()
    val playerClock by SettingsStore.playerClock.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = context.findLifecycleOwner()
    CompiledSeparately {
    PlayerBrightnessOverride(enabled = playerGestures)
    }
    val embedQualityStreams = remember(url, qualityStreams) {
        qualityStreams
            .filter(StreamItem::isEmbed)
            .distinctBy { it.height ?: declaredVideoHeight(it.quality) }
            .filter { (it.height ?: declaredVideoHeight(it.quality)) != null }
            .sortedByDescending { it.height ?: declaredVideoHeight(it.quality) }
    }
    // The rest of the provider's embeds: mirrors named after their host or their server slot
    // ("AbyssPlayer", "VidMoly", "HD-2 embed") rather than a resolution. They used to be dropped
    // by the filter above and never reached the sheet at all, which is how an AnimeDekho episode
    // that resolved six players offered none of them — including when the first one is the mirror
    // that stalls. They are a source switch, not a rung on the ladder, so they get their own
    // section rather than being scored into one.
    val embedServerStreams = remember(qualityStreams, embedQualityStreams) {
        val ladder = embedQualityStreams.mapTo(mutableSetOf(), StreamItem::url)
        qualityStreams
            .filter(StreamItem::isEmbed)
            .filterNot { it.url in ladder }
            .distinctBy(StreamItem::url)
    }
    val perStream = remember(url) { EmbedStreamState(url, startPositionMs) }
    // When the page's own bridge last reported a tick; see the main-frame probe below. Atomic and
    // not state, because it is written from the bridge's binder thread and must not recompose.
    val lastBridgeTickMs = remember(url) { java.util.concurrent.atomic.AtomicLong(0L) }
    var activeUrl by perStream.activeUrl
    val activeQualityStream = embedQualityStreams.firstOrNull { it.url == activeUrl }
    val activeReferer = activeQualityStream?.referer ?: referer
    var captionAppearanceVisible by perStream.captionAppearanceVisible
    var captionsSheetVisible by perStream.captionsSheetVisible
    // Not keyed on the url: a viewer who turned the server's captions off did not mean "for this
    // episode". Every page load starts with its own captions on, so the choice is re-applied once
    // the new page has finished rather than remembered by the page.
    var embedCaptionsEnabled by remember { mutableStateOf(true) }
    var settingsSheetVisible by perStream.settingsSheetVisible
    var playbackSpeed by perStream.playbackSpeed
    // Seeded from the settings rather than in the remembers above, because on a cold start the
    // preferences have not been read off disk yet when this composes. The effects below dispatch
    // whatever lands here to the embed's own <video> once it answers.
    CompiledSeparately {
    LaunchedEffect(url) {
        SettingsStore.awaitLoaded()
        playbackSpeed = SettingsStore.defaultPlaybackSpeed.value
        // Same subtitle defaults the native player opens on, so "off" said in either player is
        // still off in the other one — and still off after the app is closed and reopened.
        embedCaptionsEnabled = subtitlesEnabledByDefault(
            category = category,
            subtitlesByDefault = SettingsStore.subtitlesByDefault.value,
            subtitlesWithDub = SettingsStore.subtitlesWithDub.value,
        )
    }
    }
    // The speed to restore once a hold-to-speed gesture ends (the user's chosen playback speed).
    var preHoldSpeed by perStream.preHoldSpeed
    var webPlaybackAvailable by remember(activeUrl) { mutableStateOf(false) }
    // The ladder the loaded page's own player reports, keyed to the page: a mirror switch loads a
    // different player whose renditions have nothing to do with the last one's.
    var pageQuality by remember(activeUrl) { mutableStateOf<EmbedPageQuality?>(null) }
    var pendingSeekMs by remember(url, startPositionMs) { mutableLongStateOf(startPositionMs) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // A main-frame error status usually means the host served an error page that will never
    // produce a video, which is worth failing over for. It is not reported the moment it
    // arrives, though: a few embeds answer non-2xx and still render a working player, so the
    // effect below waits to see whether a video shows up before calling the source dead.
    var mainFrameErrorMessage by remember(activeUrl) { mutableStateOf<String?>(null) }
    // The page player's own failure (JW's "Error Code: 232011" card), paired with the URL it was
    // reported for. Not keyed on activeUrl: the bridge that writes it is built once per WebView,
    // so the URL travels with the message and the effect below discards a stale one.
    var pagePlayerError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Bumped when the renderer dies. The platform says a WebView "can't be used" after
    // onRenderProcessGone, yet the AndroidView stayed mounted and the next embed (a fallback is
    // the usual next step) was loaded into the dead instance. Keying the view on this builds a
    // fresh one and lets the dead one go through onRelease.
    var rendererGeneration by remember { mutableIntStateOf(0) }
    // Views whose renderer is gone: their release skips the page calls and only tears them down.
    val deadRendererViews = remember {
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
    }
    var finishedUrl by remember(activeUrl) { mutableStateOf<String?>(null) }
    val currentOnPlaybackStopperChanged by rememberUpdatedState(onPlaybackStopperChanged)
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentOnAutoAdvance by rememberUpdatedState(onAutoAdvance)
    val currentHasPreviousEpisode by rememberUpdatedState(hasPreviousEpisode)
    val currentHasNextEpisode by rememberUpdatedState(hasNextEpisode)
    // The WebView is built once, so the remote handler below has to read this live rather than
    // capture the value it was created with.
    val currentPlayerOwnsRemote by rememberUpdatedState(focusPlayerOnStart)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // Compare against the URL we last requested, not WebView.url: pages rewrite their own URL
    // (history.replaceState), which would otherwise re-trigger loadUrl on every recomposition.
    // It also names which stream a progress tick belongs to — a page the app has already
    // navigated away from must not have its clock attributed to the next episode.
    val lastRequestedUrl = remember { object { var value: String? = null } }
    val currentPendingSeekMs by rememberUpdatedState(pendingSeekMs)
    var positionMs by perStream.positionMs
    var durationMs by perStream.durationMs
    var pendingTvSeekTargetMs by remember(activeUrl) { mutableStateOf<Long?>(null) }
    var tvSeekRequest by remember(activeUrl) { mutableIntStateOf(0) }
    var webIsPlaying by perStream.webIsPlaying
    var resumeDialogVisible by perStream.resumeDialogVisible
    val resumeDialogFrames = perStream.resumeDialogFrames
    // Whether this episode's uninvited start has already been answered with a pause.
    var autoplayHeldBack by perStream.autoplayHeldBack
    var webVolume by perStream.webVolume
    var lastAudibleVolume by perStream.lastAudibleVolume
    // The device media stream remains the volume/mute fallback for old WebView builds that do not
    // support document-start injection into cross-origin frames.
    val embedAudioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    var deviceVolume by remember { mutableFloatStateOf(readDeviceVolume(embedAudioManager)) }
    var tvControlsVisible by perStream.tvControlsVisible
    val focusManager = LocalFocusManager.current
    // Whether anything inside the row currently holds the remote. There is no D-pad route into the
    // row from the picture behind it, so a row that has lost focus is a row that cannot be operated
    // at all, and this is what tells the two apart. See [tvOverlayDismissFocus].
    var tvControlsFocused by remember { mutableStateOf(false) }
    // Whether the ring is anywhere inside the player at all; see [tvFocusLostTarget].
    var tvPlayerHasFocus by remember { mutableStateOf(false) }
    var tvControlsInteraction by perStream.tvControlsInteraction
    var episodeDrawerExpanded by perStream.episodeDrawerExpanded
    var touchControlsVisible by perStream.touchControlsVisible
    var touchControlsInteraction by perStream.touchControlsInteraction
    // Deliberately not keyed on `url`: a lock has to survive the reload that a server switch or the
    // next episode causes, or it would quietly let go exactly when the picture changes.
    var screenLocked by remember { mutableStateOf(false) }
    var playbackGestureIsPlaying by perStream.playbackGestureIsPlaying
    var fallbackControlsVisible by perStream.fallbackControlsVisible
    var fallbackInteraction by perStream.fallbackInteraction
    // Compatibility state for old WebViews where a cross-origin frame cannot report playback.
    var fallbackIsPlaying by perStream.fallbackIsPlaying
    val tvPlayPauseFocus = remember { FocusRequester() }
    // The bottom-right skip action, which a TV hands the remote to while it offers a timed skip
    // (see [tvSkipActionTakesRemote]); and whether the ring is on it. See PlayerSurface.
    val tvSkipActionFocus = remember { FocusRequester() }
    var tvSkipActionFocused by remember { mutableStateOf(false) }
    var tvTimedSkipOffered by remember { mutableStateOf(false) }
    // Down was the key that opened the row; read once by the effect that aims the remote at it.
    var tvControlsOpenedByDown by remember { mutableStateOf(false) }
    var tvControlsFocusRestoreRequest by perStream.tvControlsFocusRestoreRequest
    val restoreTvControlsFocus = {
        if (device.isTv && focusPlayerOnStart) {
            tvControlsVisible = true
            tvControlsInteraction++
            tvControlsFocusRestoreRequest++
        }
    }
    val currentActiveUrl by rememberUpdatedState(activeUrl)
    // Error reports want where playback stood when they fire. This used to keep a copy current
    // with rememberUpdatedState(positionMs), which reads the position during composition — so
    // every tick of the clock recomposed this whole player to refresh a value only a failure
    // ever looks at. The per-stream holder changes only with the stream, and its state is read
    // when the report is written.
    val latestStream by rememberUpdatedState(perStream)
    fun currentPositionMs(): Long = latestStream.positionMs.longValue
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoSkipFiller by SettingsStore.autoSkipFiller.collectAsState()
    val showSkipButton by SettingsStore.showSkipButton.collectAsState()
    val skipButtonSeconds by SettingsStore.skipButtonSeconds.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val playOnOpen by SettingsStore.playOnOpen.collectAsState()
    val captionStyle by SettingsStore.captionStyle.collectAsState()
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
    var introAutoSkipped by remember(activeUrl, introStartMs, introEndMs) { mutableStateOf(false) }
    var recapAutoSkipped by remember(activeUrl, recapStartMs, recapEndMs) { mutableStateOf(false) }
    var outroAutoSkipped by remember(activeUrl, outroStartMs, outroEndMs) { mutableStateOf(false) }
    // See PlayerSurface and AUTO_SKIP_COUNTDOWN_SECONDS.
    var autoSkipCountdown by remember(activeUrl) { mutableStateOf<AutoSkipCountdown?>(null) }
    var autoSkipSecondsLeft by remember(activeUrl) { mutableIntStateOf(AUTO_SKIP_COUNTDOWN_SECONDS) }
    var nextEpisodeAutoHandled by remember(activeUrl, outroStartMs, outroEndMs) { mutableStateOf(false) }
    // Whether this page has been seen playing somewhere the next episode is *not* due yet.
    //
    // Opening an episode is not the same act as watching one to the end, and the position alone
    // cannot tell them apart: someone resuming at 23:50 of a 24:00 file is inside the tail window
    // from the first tick. The native player can afford to ignore that, because its offer is a card
    // with a countdown that can be refused; this one advances outright, so it waits until it has
    // watched the clock cross into the ending rather than merely find it there.
    var nextEpisodeArmed by remember(activeUrl) { mutableStateOf(false) }
    var pendingSkipTargetMs by remember(activeUrl) { mutableStateOf<Long?>(null) }
    val requestSkipSeek: (Long, String) -> Unit = { targetMs, trigger ->
        if (webView == null) {
            DiagnosticsLog.event(
                "EmbedWebView skip seek rejected trigger=$trigger targetMs=$targetMs webView=none",
            )
        } else {
            pendingSkipTargetMs = targetMs
            DiagnosticsLog.event(
                "EmbedWebView skip seek requested trigger=$trigger positionMs=$positionMs targetMs=$targetMs",
            )
            seekWebVideo(webView, targetMs)
        }
    }
    CompiledSeparately {
    LaunchedEffect(
        activeUrl,
        skipTimingStatus,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
        durationMs,
    ) {
        DiagnosticsLog.event(
            "EmbedWebView skip metadata status=${skipTimingStatus.name.lowercase()} " +
                "introMs=$introStartMs-${introEndMs ?: "none"} " +
                "outroMs=${outroStartMs ?: "none"}-${outroEndMs ?: "none"} " +
                "recapMs=$recapStartMs-${recapEndMs ?: "none"} " +
                // The verdict, not just the numbers: a rejected outro is the difference between a
                // player that advances on the credits and one that appears to ignore its timings.
                "durationMs=$durationMs " +
                "outroIsCredits=${outroWindowIsCredits(outroStartMs, outroEndMs, durationMs)}",
        )
    }
    LaunchedEffect(positionMs, pendingSkipTargetMs) {
        val targetMs = pendingSkipTargetMs ?: return@LaunchedEffect
        if (positionMs in (targetMs - 2_500L).coerceAtLeast(0L)..(targetMs + 5_000L)) {
            DiagnosticsLog.event(
                "EmbedWebView skip seek confirmed positionMs=$positionMs targetMs=$targetMs",
            )
            pendingSkipTargetMs = null
        }
    }
    LaunchedEffect(pendingSkipTargetMs) {
        val targetMs = pendingSkipTargetMs ?: return@LaunchedEffect
        delay(5_000)
        if (pendingSkipTargetMs == targetMs) {
            DiagnosticsLog.event(
                "EmbedWebView skip seek unconfirmed positionMs=$positionMs targetMs=$targetMs",
            )
            pendingSkipTargetMs = null
        }
    }
    }
    // Every remote seek lands here, whether it was described as an offset or named outright, so the
    // burst coalescing below covers both: a digit pressed in the middle of a run of Left presses is
    // still one seek rather than two racing each other into the page's <video>.
    val queueTvSeekTo: (Long, String) -> Unit = { targetMs, reason ->
        pendingTvSeekTargetMs = targetMs
        positionMs = targetMs
        tvSeekRequest++
        DiagnosticsLog.event(
            "EmbedWebView TV seek queued $reason targetMs=$targetMs request=$tvSeekRequest",
        )
    }
    val queueTvSeek: (Long) -> Unit = { offsetMs ->
        queueTvSeekTo(
            tvSeekTargetMs(
                currentPositionMs = pendingTvSeekTargetMs ?: positionMs,
                durationMs = durationMs,
                offsetMs = offsetMs,
            ),
            "offsetMs=$offsetMs",
        )
    }
    val currentQueueTvSeek by rememberUpdatedState(queueTvSeek)

    // Left/Right seeking: how far into a run of presses we are, and the readout that goes with it.
    var dpadSeekPresses by perStream.dpadSeekPresses
    var dpadSeekLastPressMs by perStream.dpadSeekLastPressMs
    var dpadSeekForwardDirection by perStream.dpadSeekForwardDirection
    var dpadSeekTick by perStream.dpadSeekTick
    var dpadSeekVisible by perStream.dpadSeekVisible
    val dpadSeek: (Boolean) -> Unit = { forward ->
        val now = SystemClock.elapsedRealtime()
        val sinceLastPressMs = now - dpadSeekLastPressMs
        // Held keys repeat far faster than anyone taps; without this the acceleration would climb
        // most of the way through an episode in a second. See TV_DPAD_SEEK_MIN_GAP_MS.
        if (tvDpadSeekAccepts(sinceLastPressMs)) {
            dpadSeekPresses = tvDpadSeekPresses(dpadSeekPresses, sinceLastPressMs)
            dpadSeekLastPressMs = now
            dpadSeekForwardDirection = forward
            val step = tvScrubStepMs(dpadSeekPresses, durationMs)
            queueTvSeek(if (forward) step else -step)
            dpadSeekTick++
        }
    }
    val currentDpadSeek by rememberUpdatedState(dpadSeek)
    // A digit names a spot outright rather than nudging from wherever the picture is: 1 a tenth of
    // the way in, 9 nine tenths, 0 back to the start. See [tvNumberSeekTargetMs]. Like Left/Right it
    // deliberately leaves the control row alone — the row would take the remote with it — and shows
    // the same readout instead.
    val numberSeek: (Int) -> Unit = { keyCode ->
        val targetMs = tvNumberSeekTargetMs(keyCode, durationMs)
        if (targetMs != null) {
            // Whatever Left/Right run this interrupts is over: the next press starts again at the
            // smallest step instead of inheriting this jump's momentum.
            dpadSeekPresses = 0
            dpadSeekForwardDirection = targetMs >= positionMs
            queueTvSeekTo(targetMs, "digit")
            dpadSeekTick++
            tvControlsInteraction++
        }
    }
    val currentNumberSeek by rememberUpdatedState(numberSeek)
    CompiledSeparately {
    LaunchedEffect(dpadSeekTick) {
        if (dpadSeekTick == 0) return@LaunchedEffect
        dpadSeekVisible = true
        delay(TV_SEEK_INDICATOR_LINGER_MS)
        dpadSeekVisible = false
        dpadSeekPresses = 0
    }
    LaunchedEffect(activeUrl, webView, tvSeekRequest) {
        val targetMs = pendingTvSeekTargetMs ?: return@LaunchedEffect
        delay(TV_SEEK_COALESCE_MS)
        if (pendingTvSeekTargetMs != targetMs) return@LaunchedEffect
        pendingTvSeekTargetMs = null
        seekWebVideo(webView, targetMs)
        DiagnosticsLog.event("EmbedWebView TV seek committed targetMs=$targetMs")
    }

    DisposableEffect(webView) {
        val web = webView
        currentOnPlaybackStopperChanged?.invoke(web?.let { { stopWebPlayback(it) } })
        onDispose { currentOnPlaybackStopperChanged?.invoke(null) }
    }
    }

    val controlsShowing = if (device.isTv) tvControlsVisible else touchControlsVisible
    // The control bar reports how far its seek bar sits above the bottom edge, so the floating
    // skip action can clear the timeline; the inline bar and the fullscreen one are very
    // different sizes.
    var controlsSeekAnchor by remember { mutableStateOf(0.dp) }
    CompiledSeparately {
    LaunchedEffect(captionStyle, controlsShowing, webView) {
        val web = webView ?: return@LaunchedEffect
        val js = applyCaptionStyleJs(captionStyle, controlsShowing)
        web.evaluateJavascript(js, null)
    }
    }

    // Full touch controls — seek bar and all — whenever the injected JS can reach the <video>.
    val touchControlsActive = !device.isTv && webPlaybackAvailable && loadError == null && !resumeDialogVisible
    // Old WebView builds without all-frame injection get a reduced native bar. Modern WebViews
    // report even cross-origin video through the document-start bridge and use the full controls.
    val fallbackControlsActive = !device.isTv && !webPlaybackAvailable && loadError == null && !resumeDialogVisible

    // Whichever of the three bars this device draws, is one of them on screen right now?
    val chromeVisible = when {
        device.isTv -> tvControlsVisible
        touchControlsActive -> touchControlsVisible
        else -> fallbackControlsVisible
    }
    val currentOnControlsVisibilityChanged by rememberUpdatedState(onControlsVisibilityChanged)
    CompiledSeparately {
    LaunchedEffect(chromeVisible) { currentOnControlsVisibilityChanged?.invoke(chromeVisible) }
    }
    // As PlayerSurface: the mini-player shows the picture and nothing drawn over it.
    val inPictureInPicture = LocalPlayerInPictureInPicture.current
    LaunchedEffect(inPictureInPicture) {
        if (!inPictureInPicture) return@LaunchedEffect
        tvControlsVisible = false
        touchControlsVisible = false
        fallbackControlsVisible = false
    }
    val currentOnPlaybackActiveChanged by rememberUpdatedState(onPlaybackActiveChanged)
    // Either half is enough to stop calling this loading. A found video is the stronger signal but
    // it needs the all-frame bridge; a finished page is what an old WebView can offer, and it is
    // also the point at which this draws its own fallback controls. An error page counts too — the
    // message it puts on screen is not something a loading overlay should be covering.
    val embedHasSomethingToShow = webPlaybackAvailable || finishedUrl != null || loadError != null
    CompiledSeparately {
    LaunchedEffect(embedHasSomethingToShow) {
        currentOnPlaybackActiveChanged?.invoke(embedHasSomethingToShow)
    }

    LaunchedEffect(touchControlsActive, touchControlsVisible, touchControlsInteraction, webIsPlaying) {
        if (!touchControlsActive || !touchControlsVisible || !webIsPlaying) return@LaunchedEffect
        delay(4_000)
        touchControlsVisible = false
    }

    LaunchedEffect(playbackGestureIsPlaying) {
        if (playbackGestureIsPlaying != null) {
            delay(650)
            playbackGestureIsPlaying = null
        }
    }

    // Second reporting channel alongside the AniliProgress bridge: poll the main frame for the
    // video's state. kwik intermittently serves a page variant the document-start bridge never
    // fires on, which used to demote the player to the reduced fallback bar (inverted play/pause
    // guess, no double-tap seek, no skip intro). One probe per second is negligible, and both
    // channels write the same state so they can coexist.
    LaunchedEffect(webView, activeUrl, loadError) {
        val web = webView ?: return@LaunchedEffect
        if (loadError != null) return@LaunchedEffect
        var probeActivated = false
        while (true) {
            delay(1_000)
            // Only a gap-filler: while the bridge is ticking it already writes the same state, and
            // each probe is a round trip into the renderer, a callback on the main thread and a
            // write that recomposes the player — a second of work a second, doubled for nothing.
            // A page variant the bridge never reaches (kwik's) leaves the stamp at zero, so the
            // probe carries on there exactly as before.
            if (SystemClock.elapsedRealtime() - lastBridgeTickMs.get() < BRIDGE_TICK_FRESH_MS) continue
            web.evaluateJavascript(VIDEO_PROBE_JS) { raw ->
                val parts = raw?.trim('"')?.split(',') ?: return@evaluateJavascript
                if (parts.size != 5) return@evaluateJavascript
                val positionSec = parts[0].toDoubleOrNull() ?: return@evaluateJavascript
                val durationSec = parts[1].toDoubleOrNull() ?: return@evaluateJavascript
                val playing = parts[2] == "1"
                val muted = parts[3] == "1"
                val volume = parts[4].toDoubleOrNull() ?: 1.0
                if (durationSec <= 0) return@evaluateJavascript
                if (!probeActivated && !webPlaybackAvailable) {
                    probeActivated = true
                    DiagnosticsLog.event("EmbedWebView main-frame probe activated full controls")
                }
                webPlaybackAvailable = true
                positionMs = (positionSec * 1000).toLong()
                durationMs = (durationSec * 1000).toLong()
                webIsPlaying = playing
                fallbackIsPlaying = playing
                webVolume = if (muted) 0f else volume.toFloat().coerceIn(0f, 1f)
                if (playing) {
                    lastRequestedUrl.value?.let { currentOnProgress?.invoke(it, positionMs, durationMs) }
                }
            }
        }
    }

    LaunchedEffect(fallbackControlsActive, fallbackControlsVisible, fallbackInteraction) {
        if (!fallbackControlsActive || !fallbackControlsVisible) return@LaunchedEffect
        delay(4_000)
        fallbackControlsVisible = false
    }
    // Meet the video with the bar once the page is up, the way the first tap would.
    LaunchedEffect(fallbackControlsActive, finishedUrl) {
        if (fallbackControlsActive && finishedUrl != null) fallbackControlsVisible = true
    }
    LaunchedEffect(tvControlsVisible, focusPlayerOnStart) {
        if (!tvControlsVisible || !focusPlayerOnStart) return@LaunchedEffect
        val openedByDown = tvControlsOpenedByDown
        tvControlsOpenedByDown = false
        delay(32)
        // Opens on a timed skip when one is offered, unless the row was opened from the skip
        // itself; see the same effect in PlayerSurface.
        if (tvTimedSkipOffered && !tvSkipActionFocused &&
            runCatching { tvSkipActionFocus.requestFocus() }.isSuccess
        ) {
            return@LaunchedEffect
        }
        // Down opens on the fixed "+Ns" instead; see [tvRowOpensOnFixedSkip].
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
        runCatching { tvPlayPauseFocus.requestFocus() }
    }
    LaunchedEffect(tvControlsFocusRestoreRequest, focusPlayerOnStart) {
        if (tvControlsFocusRestoreRequest == 0 || !focusPlayerOnStart) return@LaunchedEffect
        delay(64)
        runCatching { tvPlayPauseFocus.requestFocus() }
        DiagnosticsLog.event(
            "EmbedWebView TV controls focus restored request=$tvControlsFocusRestoreRequest",
        )
    }
    }
    val screenReaderActive = rememberScreenReaderActive()
    val inputModeManager = LocalInputModeManager.current
    // TalkBack users can't discover the hidden control row through a key press, so present it
    // as soon as the fullscreen player opens instead of waiting for the semantic reveal action.
    CompiledSeparately {
    LaunchedEffect(screenReaderActive, focusPlayerOnStart, activeUrl) {
        if (device.isTv && screenReaderActive && focusPlayerOnStart) {
            tvControlsVisible = true
        }
    }
    LaunchedEffect(
        tvControlsVisible,
        tvControlsInteraction,
        webView,
        focusPlayerOnStart,
        screenReaderActive,
        settingsSheetVisible,
        captionsSheetVisible,
        captionAppearanceVisible,
    ) {
        if (!focusPlayerOnStart) {
            tvControlsVisible = false
            return@LaunchedEffect
        }
        if (!tvControlsVisible) return@LaunchedEffect
        // TalkBack users navigate slowly and can't reopen the controls with a key press
        // (the screen reader consumes the D-pad), so never auto-hide under a screen reader.
        if (screenReaderActive) return@LaunchedEffect
        // While the settings panel or caption dialog is up, hiding the row would hand focus
        // back to the WebView and take the remote away from the panel.
        if (settingsSheetVisible || captionsSheetVisible || captionAppearanceVisible) return@LaunchedEffect
        delay(8_000)
        tvControlsVisible = false
        // A skip still on screen keeps the remote it holds.
        if (!tvSkipActionFocused) webView?.requestFocus()
    }
    }
    val tvBackTarget = tvPlayerBackTarget(
        isTv = device.isTv,
        playerOwnsRemote = focusPlayerOnStart,
        captionAppearanceVisible = captionAppearanceVisible,
        episodeDrawerVisible = episodeDrawerExpanded,
        captionsVisible = captionsSheetVisible,
        settingsVisible = settingsSheetVisible,
        controlsVisible = tvControlsVisible,
    )
    // Retires one layer of this player's chrome, topmost first; false when it has none up and
    // Back belongs to the screen instead.
    val dismissTvChrome: () -> Boolean = dismiss@{
        when (tvBackTarget) {
            TvPlayerBackTarget.NONE -> return@dismiss false
            // Online/manual external tracks belong to the native player; an embed never opens it.
            TvPlayerBackTarget.OPEN_SUBTITLES -> return@dismiss false
            TvPlayerBackTarget.CAPTION_APPEARANCE -> captionAppearanceVisible = false
            TvPlayerBackTarget.EPISODE_DRAWER -> episodeDrawerExpanded = false
            TvPlayerBackTarget.CAPTIONS -> captionsSheetVisible = false
            TvPlayerBackTarget.SETTINGS -> settingsSheetVisible = false
            TvPlayerBackTarget.CONTROLS -> {
                DiagnosticsLog.event("EmbedWebView TV controls closed back")
                tvControlsVisible = false
                webView?.requestFocus()
                return@dismiss true
            }
        }
        // Dismissing a panel hands the remote back to the control row underneath it.
        restoreTvControlsFocus()
        true
    }

    // Where the remote lands once a new page is under the player: the next or previous episode, or
    // the same one on another server. This used to hand the WebView the remote unconditionally, which
    // is the fault PlayerSurface had and lost in "Stop an episode change from stranding the TV control
    // row": the row a viewer pressed Next on stays up, so the WebView took the remote out from under
    // it, and its key hook then answered every press aimed at the row by doing nothing. See
    // [tvEpisodeChangeFocus] and [tvPressReclaimsControls].
    CompiledSeparately {
    LaunchedEffect(activeUrl, webView, device.isTv, focusPlayerOnStart, hasPreviousEpisode, hasNextEpisode, screenReaderActive) {
        if (webView == null) return@LaunchedEffect
        delay(250)
        val watchUntilMs = SystemClock.elapsedRealtime() + TV_CONTROLS_REFOCUS_WINDOW_MS
        while (true) {
            val target = tvEpisodeChangeFocus(
                isTv = device.isTv,
                playerOwnsRemote = focusPlayerOnStart,
                // Under a screen reader the control row is auto-shown and focused; grabbing focus
                // for the WebView here would dump TalkBack back into the embed's web content.
                screenReaderActive = screenReaderActive,
                panelVisible = settingsSheetVisible || captionsSheetVisible ||
                    captionAppearanceVisible || episodeDrawerExpanded,
                controlsVisible = tvControlsVisible,
                controlsFocused = tvControlsFocused,
            )
            when (target) {
                TvEpisodeChangeFocus.UNCHANGED -> return@LaunchedEffect
                TvEpisodeChangeFocus.SURFACE -> {
                    runCatching { webView?.requestFocus() }
                    return@LaunchedEffect
                }
                TvEpisodeChangeFocus.CONTROLS -> {
                    DiagnosticsLog.event("EmbedWebView TV controls refocused after page change")
                    restoreTvControlsFocus()
                    return@LaunchedEffect
                }
                TvEpisodeChangeFocus.HOLD -> {
                    if (SystemClock.elapsedRealtime() >= watchUntilMs) return@LaunchedEffect
                    delay(TV_CONTROLS_FOCUS_RETRY_MS)
                }
            }
        }
    }
    // The same net as PlayerSurface's: nothing inside the player focused for longer than a move
    // between two of its controls takes. See [tvFocusLostTarget].
    LaunchedEffect(tvPlayerHasFocus, tvControlsVisible, webView, focusPlayerOnStart, screenReaderActive) {
        if (tvPlayerHasFocus || webView == null) return@LaunchedEffect
        delay(TV_FOCUS_LOSS_SETTLE_MS)
        when (
            tvFocusLostTarget(
                isTv = device.isTv,
                playerOwnsRemote = focusPlayerOnStart,
                playerHasFocus = tvPlayerHasFocus,
                screenReaderActive = screenReaderActive,
                panelVisible = settingsSheetVisible || captionsSheetVisible ||
                    captionAppearanceVisible || episodeDrawerExpanded || resumeDialogVisible,
                controlsVisible = tvControlsVisible,
            )
        ) {
            TvEpisodeChangeFocus.CONTROLS -> {
                DiagnosticsLog.event("EmbedWebView TV focus lost; refocusing controls")
                restoreTvControlsFocus()
            }
            TvEpisodeChangeFocus.SURFACE -> {
                DiagnosticsLog.event("EmbedWebView TV focus lost; refocusing page")
                runCatching { webView?.requestFocus() }
            }
            TvEpisodeChangeFocus.HOLD, TvEpisodeChangeFocus.UNCHANGED -> Unit
        }
    }
    }

    // Registrable-ish host the embed is allowed to navigate within (its own host + subdomains).
    val allowedHost = remember(activeUrl) {
        runCatching { Uri.parse(activeUrl).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    }
    // An embed that never answers at all — no finish, no error, no video — is the one shape of
    // dead page neither recovery path below can see, so it is timed here and reported as a
    // playback failure like any other. Time spent with the app in the background does not count:
    // the lifecycle observer calls pauseTimers() there, so the page genuinely stops loading and
    // waiting through a phone call would otherwise fail a source that was doing nothing wrong.
    CompiledSeparately {
    LaunchedEffect(activeUrl, lifecycleOwner) {
        DiagnosticsLog.event("EmbedWebView composed urlHost=${allowedHost ?: "unknown"} refererHost=${activeReferer.hostOrNone()}")
        val lifecycle = lifecycleOwner?.lifecycle ?: return@LaunchedEffect
        var loadingMs = 0L
        var reportedSlowLoad = false
        while (loadingMs < EMBED_LOAD_TIMEOUT_MS) {
            delay(EMBED_LOAD_POLL_MS)
            if (finishedUrl != null || loadError != null || webPlaybackAvailable) return@LaunchedEffect
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) continue
            loadingMs += EMBED_LOAD_POLL_MS
            if (!reportedSlowLoad && loadingMs >= EMBED_SLOW_LOAD_NOTICE_MS) {
                reportedSlowLoad = true
                DiagnosticsLog.event(
                    "EmbedWebView still loading after ${EMBED_SLOW_LOAD_NOTICE_MS}ms " +
                        "urlHost=${allowedHost ?: "unknown"}",
                )
            }
        }
        DiagnosticsLog.event(
            "EmbedWebView load timed out after ${EMBED_LOAD_TIMEOUT_MS}ms " +
                "urlHost=${allowedHost ?: "unknown"}",
        )
        currentOnPlaybackError?.invoke(
            AnililiError.PLAYBACK_EMBED_FAILED.report(
                detail = "The embed showed nothing playable within ${EMBED_LOAD_TIMEOUT_MS}ms.",
                attributes = mapOf(
                    "urlHost" to (allowedHost ?: "unknown"),
                    "timeoutMs" to EMBED_LOAD_TIMEOUT_MS,
                ),
            ),
            currentActiveUrl,
            currentPositionMs(),
        )
    }
    }
    // Once the embed page has finished loading, the main frame is locked: a real player never
    // navigates its own top frame again, so any later navigation is an ad hijack — including
    // same-host popunder gateways that the host allowlist alone would let through.
    val navigationLock = remember { object { @Volatile var locked = false } }

    // Nothing was watching the embed for a dead page before this: the viewer sat on the host's
    // 404 until they backed out of the player themselves, which is exactly what an automatic
    // fallback is for. Waiting for the grace window keeps a working-but-odd embed playing.
    CompiledSeparately {
    LaunchedEffect(mainFrameErrorMessage, webPlaybackAvailable, activeUrl) {
        val message = mainFrameErrorMessage ?: return@LaunchedEffect
        if (webPlaybackAvailable) return@LaunchedEffect
        delay(EMBED_DEAD_PAGE_GRACE_MS)
        if (webPlaybackAvailable) return@LaunchedEffect
        currentOnPlaybackError?.invoke(
            // The page's own words are worth keeping — "net::ERR_NAME_NOT_RESOLVED" and a host's own
            // 404 page are different problems — but they belong in the detail, not in the line a
            // viewer reads under a dead player.
            AnililiError.PLAYBACK_EMBED_FAILED.report(
                detail = message,
                attributes = mapOf(
                    "urlHost" to (allowedHost ?: "unknown"),
                    "graceMs" to EMBED_DEAD_PAGE_GRACE_MS,
                    "stage" to "dead_page",
                ),
            ),
            currentActiveUrl,
            currentPositionMs(),
        )
    }

    // JW's own error card ("This video file cannot be played. (Error Code: 232011)") leaves the
    // <video> element in place, so the page still reads as a working player: none of the watchdogs
    // here fire, and the viewer sat on the card while a working server was one resolve away. Seen
    // 2026-10-03 on a home network that blocked MegaPlay's video host. The page's report is
    // treated as a playback error so the watch screen fails over, unless the clock moves within
    // the grace window (the page recovered on its own).
    LaunchedEffect(pagePlayerError, activeUrl) {
        val (reportedFor, message) = pagePlayerError ?: return@LaunchedEffect
        if (reportedFor != activeUrl) return@LaunchedEffect
        val positionAtError = positionMs
        delay(EMBED_PAGE_ERROR_GRACE_MS)
        if (positionMs > positionAtError + 1_000L) {
            DiagnosticsLog.event("EmbedWebView page player recovered after its error positionMs=$positionMs")
            return@LaunchedEffect
        }
        currentOnPlaybackError?.invoke(
            AnililiError.PLAYBACK_EMBED_FAILED.report(
                detail = message,
                attributes = mapOf(
                    "urlHost" to (allowedHost ?: "unknown"),
                    "graceMs" to EMBED_PAGE_ERROR_GRACE_MS,
                    "stage" to "page_player_error",
                ),
            ),
            currentActiveUrl,
            currentPositionMs(),
        )
    }

    // The one dead end nothing watched before: see [embedSilentPageFailed] for the shape of it and
    // the report it came from. Time is credited the same way the load watchdog credits it.
    LaunchedEffect(activeUrl, lifecycleOwner) {
        val lifecycle = lifecycleOwner?.lifecycle ?: return@LaunchedEffect
        var silentMs = 0L
        while (!embedSilentPageFailed(
                pageFinished = finishedUrl != null,
                playbackAvailable = webPlaybackAvailable,
                loadError = loadError,
                silentMs = silentMs,
                timeoutMs = EMBED_SILENT_PAGE_TIMEOUT_MS,
            )
        ) {
            // Either of these means the page answered for itself; the other watchdogs own it now.
            if (webPlaybackAvailable || loadError != null) return@LaunchedEffect
            delay(EMBED_LOAD_POLL_MS)
            if (finishedUrl != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                silentMs += EMBED_LOAD_POLL_MS
            }
        }
        DiagnosticsLog.event(
            "EmbedWebView loaded but silent after ${EMBED_SILENT_PAGE_TIMEOUT_MS}ms " +
                "urlHost=${allowedHost ?: "unknown"}",
        )
        currentOnPlaybackError?.invoke(
            AnililiError.PLAYBACK_EMBED_FAILED.report(
                detail = "The embed loaded but never showed a video " +
                    "within ${EMBED_SILENT_PAGE_TIMEOUT_MS}ms.",
                attributes = mapOf(
                    "urlHost" to (allowedHost ?: "unknown"),
                    "timeoutMs" to EMBED_SILENT_PAGE_TIMEOUT_MS,
                    "stage" to "silent_page",
                ),
            ),
            currentActiveUrl,
            currentPositionMs(),
        )
    }

    LaunchedEffect(webPlaybackAvailable, playbackSpeed, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        dispatchWebPlayerCommand(web, "speed", playbackSpeed.toDouble(), SET_PLAYBACK_SPEED_JS(playbackSpeed))
    }
    }

    // An episode the player took itself is the credits running out, not the viewer opening
    // something, so "Play when an episode opens" has no say over it — that is what "Autoplay next
    // episode" means. Without this an embed binge stopped at the first credits and asked for a tap.
    val shouldStartPlayback = playOnOpen || autoAdvanced

    // Kwik (and any other Plyr embed) opens on a poster and waits for a gesture, so the episode
    // just sits at 0:00 until the viewer presses play — the app reads as frozen and every
    // position-driven affordance is blind. The native path starts on its own, so start here too.
    //
    // One press is not enough. The page reports a <video> the moment one is attached, which can be
    // a poster frame whose player has not finished wiring itself up, and play() then rejects into
    // the promise handler and is never heard from again. So ask repeatedly, while the page is still
    // telling us it is paused at the position it opened on, and stop the moment it moves.
    CompiledSeparately {
    LaunchedEffect(webPlaybackAvailable, webView, shouldStartPlayback) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable || !shouldStartPlayback) return@LaunchedEffect
        repeat(EMBED_PLAY_ATTEMPTS) { attempt ->
            dispatchWebPlayerCommand(web, "play", fallbackScript = START_PLAYBACK_JS)
            delay(EMBED_PLAY_RETRY_MS)
            // Both reporting channels write this alongside the clock, from the same reading, so it
            // is the whole of what either of them knows about whether the press landed.
            if (webIsPlaying) {
                if (attempt > 0) {
                    DiagnosticsLog.event("EmbedWebView playback started after ${attempt + 1} presses")
                }
                return@LaunchedEffect
            }
        }
        DiagnosticsLog.event(
            "EmbedWebView still paused after $EMBED_PLAY_ATTEMPTS presses positionMs=$positionMs",
        )
    }
    }

    // A call ringing is not a lifecycle change, so ON_PAUSE below does not cover it. Hand the
    // interruption the same answer the native player gives: stop, and come back if it was brief.
    var pausedByInterruption by remember(activeUrl) { mutableStateOf(false) }
    CompiledSeparately {
    EmbedAudioFocus(active = webView != null) { interruption ->
        val web = webView ?: return@EmbedAudioFocus
        when (interruption) {
            AudioInterruption.PauseTransient, AudioInterruption.PauseFinal -> {
                if (!webIsPlaying) return@EmbedAudioFocus
                // Only a brief interruption earns playback back; a permanent loss means another
                // app now owns the speaker, and starting again over it would be the same rudeness
                // this whole handler exists to stop.
                pausedByInterruption = interruption == AudioInterruption.PauseTransient
                dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
                webIsPlaying = false
                playbackGestureIsPlaying = false
            }
            AudioInterruption.Resume -> {
                if (!pausedByInterruption) return@EmbedAudioFocus
                pausedByInterruption = false
                dispatchWebPlayerCommand(web, "play", fallbackScript = START_PLAYBACK_JS)
                webIsPlaying = true
                playbackGestureIsPlaying = true
            }
        }
    }

    // The sleep timer, which the page has to carry out by itself: PlaybackService only reaches the
    // native player. The fade scales whatever level the viewer had when it began. That level goes
    // back once the timer lets go of the volume, which happens after the pause, or straight away
    // if the timer is cancelled partway through the fade.
    LaunchedEffect(webView) {
        val web = webView ?: return@LaunchedEffect
        var fadeFrom: Float? = null
        launch {
            SleepTimer.volume.collect { level ->
                if (level >= 1f) {
                    fadeFrom?.let { setWebVolume(web, it) {} }
                    fadeFrom = null
                    return@collect
                }
                // A paused page has nothing to fade, and its level is left as the viewer set it.
                if (fadeFrom == null && !webIsPlaying) return@collect
                val from = fadeFrom ?: webVolume.also { fadeFrom = it }
                setWebVolume(web, from * level) {}
            }
        }
        SleepTimer.expiries.collect {
            if (!webIsPlaying) return@collect
            DiagnosticsLog.event("EmbedWebView sleep timer pause positionMs=${currentPositionMs()}")
            dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
            webIsPlaying = false
            playbackGestureIsPlaying = false
        }
    }

    // Plenty of embeds start themselves, so declining to press play is not enough to honour the
    // setting. Hold the page back the first time it is caught running — once per episode, so the
    // viewer's own play press is never fought, and so an embed that insists on autoplay costs one
    // pause rather than an unwinnable loop.
    LaunchedEffect(webPlaybackAvailable, webView, shouldStartPlayback, webIsPlaying) {
        val web = webView ?: return@LaunchedEffect
        if (shouldStartPlayback || !webPlaybackAvailable || !webIsPlaying || autoplayHeldBack) {
            return@LaunchedEffect
        }
        autoplayHeldBack = true
        DiagnosticsLog.event("EmbedWebView held back provider autoplay positionMs=$positionMs")
        dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
        webIsPlaying = false
    }

    // Document-start injection below normally hides provider chrome before its first paint. Repeat
    // once our controls become active for older WebViews that only support main-frame evaluation.
    LaunchedEffect(webPlaybackAvailable, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        web.evaluateJavascript(HIDE_PROVIDER_CHROME_JS, null)
    }

    // Best-effort: reaches the main document and same-origin iframes only, exactly like the
    // progress poll. A cross-origin embed renders its own captions out of our reach, and some
    // providers burn subtitles into the video, where there is nothing to style at all.
    LaunchedEffect(webPlaybackAvailable, captionStyle, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        web.evaluateJavascript(CAPTION_STYLE_JS(captionStyle), null)
    }

    // Keyed on the finished page as well as the choice: a player sets its captions up again from
    // scratch on every load, so "off" has to be re-stated for the new page rather than assumed to
    // have survived it.
    LaunchedEffect(embedCaptionsEnabled, webPlaybackAvailable, finishedUrl, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        DiagnosticsLog.event("EmbedWebView captions enabled=$embedCaptionsEnabled")
        web.evaluateJavascript(setEmbedCaptionsJs(embedCaptionsEnabled), null)
    }
    // The page's own subtitle languages, so the CC sheet can offer them. Only "Off" and "On" were
    // offered before, which on MegaPlay meant living with whatever language the page opened on —
    // "I can't select subtitles" on anikoto, and on bee once cdn.kryntal.top refused its HLS and it
    // fell back to the same page. JW builds the list after the manifest, not at page-finished, so
    // it is polled for a while; reopening the sheet reads it again.
    LaunchedEffect(webPlaybackAvailable, finishedUrl, webView, perStream.captionsSheetVisible.value) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        repeat(EMBED_CAPTION_POLLS) {
            val found = web.readEmbedCaptions()
            if (found == null) {
                delay(1_000)
                return@repeat
            }
            perStream.embedCaptionTracks.value = found.labels
            perStream.embedCaptionIndex.intValue = found.current
            // A new page opens on its own default; carry the viewer's language over to it.
            val wanted = EmbedCaptionChoice.label?.let { found.labels.indexOf(it) + 1 } ?: 0
            if (embedCaptionsEnabled && wanted > 0 && wanted != found.current) {
                web.evaluateJavascript(selectEmbedCaptionJs(wanted), null)
                perStream.embedCaptionIndex.intValue = wanted
                DiagnosticsLog.event("EmbedWebView caption language restored index=$wanted")
            }
            return@LaunchedEffect
        }
    }
    }

    val chromeClient = remember {
        object : WebChromeClient() {
            // With multiple windows enabled, window.open lands here instead of navigating the
            // main frame. Returning false swallows the popunder without disturbing playback.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                DiagnosticsLog.event("EmbedWebView blocked popup isDialog=$isDialog userGesture=$isUserGesture")
                return false
            }

            // Android's custom-view fullscreen detaches the page's <video> into a separate
            // surface, and the rotation we trigger right after tears that surface down —
            // Chromium exits fullscreen and the embed player re-initializes mid-episode.
            // Embed pages are full-bleed players already, so fullscreen is implemented by
            // denying the custom view and expanding the WebView natively instead.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                DiagnosticsLog.event("EmbedWebView custom fullscreen requested")
                callback?.onCustomViewHidden()
                currentOnFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                DiagnosticsLog.event("EmbedWebView custom fullscreen hidden")
                currentOnFullscreenChanged(false)
            }
        }
    }

    // In-page ad widgets (the "watch in HD" badge that sits over the video and cannot be
    // dismissed) are not popunders: they never call window.open, so onCreateWindow never sees
    // them and the main-frame lock never fires. They arrive as ordinary subresources from a
    // third-party host — and nothing recorded which host, so there was no way to block one
    // precisely. Name each foreign origin once per page, then a blocklist can be exact instead
    // of a guess. Bounded so a page that fans out to hundreds of trackers cannot flood the log.
    // A plain ConcurrentHashMap rather than its newKeySet view: KeySetView.size is API 24, and
    // this app still runs on the API 22 Fire TV Sticks. The view compiles and then throws
    // NoSuchMethodError on exactly the devices least able to report it — the map's own size is
    // the same count and is as old as the class.
    val seenForeignOrigins = remember(activeUrl) { ConcurrentHashMap<String, Boolean>() }

    val webClient = remember(allowedHost, navigationLock, seenForeignOrigins) {
        object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val host = request?.url?.host.orEmpty().lowercase().removePrefix("www.")
                val isForeign = host.isNotEmpty() && allowedHost != null &&
                    host != allowedHost && !host.endsWith(".$allowedHost")
                val blocked = isForeign && EMBED_BLOCKED_ORIGINS.any {
                    host == it || host.endsWith(".$it")
                }
                if (isForeign &&
                    seenForeignOrigins.size < MAX_LOGGED_FOREIGN_ORIGINS &&
                    seenForeignOrigins.putIfAbsent(host, true) == null
                ) {
                    DiagnosticsLog.event(
                        "EmbedWebView third-party subresource host=$host" +
                            if (blocked) " blocked=true" else "",
                    )
                }
                // Anything not named in the blocklist is served: a player's own CDN is
                // indistinguishable from a tracker here, and refusing one takes the video with it.
                return if (blocked) emptyBlockedResponse() else null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                if (navigationLock.locked) return true // page is up; any top-frame nav is an ad
                val host = target.host.orEmpty().lowercase().removePrefix("www.")
                val allowed = allowedHost != null &&
                    (host == allowedHost || host.endsWith(".$allowedHost"))
                if (!allowed) DiagnosticsLog.event("EmbedWebView blocked main-frame nav targetHost=$host")
                return !allowed // block ad redirects that navigate away from the embed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadError = null
                finishedUrl = null
                DiagnosticsLog.event("EmbedWebView page started host=${url.hostOrNone()}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                navigationLock.locked = true
                finishedUrl = url
                DiagnosticsLog.event("EmbedWebView page finished host=${url.hostOrNone()} title=${view?.title ?: "none"}")
                // Fallback for old WebView providers without DOCUMENT_START_SCRIPT. This can only
                // reach the main document; supported providers get every frame at document start.
                view?.evaluateJavascript(HIDE_PROVIDER_CHROME_JS, null)
                view?.evaluateJavascript(PROGRESS_POLL_JS, null)
                if (currentPendingSeekMs > 0L) {
                    view?.let {
                        dispatchWebPlayerCommand(
                            it,
                            "resumeAt",
                            currentPendingSeekMs / 1000.0,
                            RESUME_WHEN_READY_JS(currentPendingSeekMs / 1000.0),
                        )
                    }
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val message = error?.description?.toString() ?: "The server did not respond"
                    loadError = message
                    currentOnPlaybackError?.invoke(
                        AnililiError.PLAYBACK_EMBED_FAILED.report(
                            detail = message,
                            attributes = mapOf(
                                "webViewErrorCode" to error?.errorCode,
                                "urlHost" to (request.url?.host ?: "unknown"),
                                "stage" to "main_frame_error",
                            ),
                        ),
                        currentActiveUrl,
                        currentPositionMs(),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    val message = "HTTP ${errorResponse?.statusCode ?: "error"} from the video server"
                    loadError = message
                    DiagnosticsLog.event(
                        "EmbedWebView main-frame HTTP error status=${errorResponse?.statusCode} " +
                            "reason=${errorResponse?.reasonPhrase} host=${request.url?.host ?: "unknown"}",
                    )
                    mainFrameErrorMessage = message
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                currentOnPlaybackError?.invoke(
                    AnililiError.PLAYBACK_EMBED_FAILED.report(
                        detail = "The embed's renderer process stopped" +
                            if (detail?.didCrash() == true) " (it crashed)." else " (the system reclaimed it).",
                        attributes = mapOf(
                            "didCrash" to detail?.didCrash(),
                            "rendererPriorityAtExit" to detail?.rendererPriorityAtExit(),
                            "stage" to "render_process_gone",
                        ),
                    ),
                    currentActiveUrl,
                    currentPositionMs(),
                )
                view?.let(deadRendererViews::add)
                if (webView === view) {
                    webView = null
                    // The fresh view has loaded nothing yet, so whichever URL is active once it
                    // is built — the fallback's, or this one again if nothing moved — is loaded.
                    lastRequestedUrl.value = null
                    rendererGeneration++
                    DiagnosticsLog.event("EmbedWebView renderer gone; rebuilding generation=$rendererGeneration")
                }
                return true
            }
        }
    }

    CompiledSeparately {
    DisposableEffect(lifecycleOwner, webView) {
        val owner = lifecycleOwner
        val web = webView
        if (owner == null || web == null) {
            onDispose {}
        } else {
            val activity = context.findActivity()
            // pauseTimers() is process-wide, so a pause this observer made must not outlive it:
            // were the player to leave while the app sits in the background, the ON_START that
            // would have answered it never reaches this observer, and every other WebView in the
            // app — resolvers, challenge dialogs, the login page — would stay frozen.
            var timersPaused = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    // Shrinking into the mini player pauses the activity too, and by the time
                    // ON_PAUSE arrives the system already reports it as in picture-in-picture.
                    // Pausing the page there froze the window it had just moved into: the video
                    // stopped and the mini player showed black. Closing the window still stops it,
                    // through ON_STOP.
                    Lifecycle.Event.ON_PAUSE -> if (!activity.inPictureInPicture()) {
                        dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
                        web.onPause()
                        web.pauseTimers()
                        timersPaused = true
                    }
                    Lifecycle.Event.ON_STOP -> {
                        dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
                        web.onPause()
                        web.pauseTimers()
                        timersPaused = true
                    }
                    Lifecycle.Event.ON_RESUME,
                    Lifecycle.Event.ON_START -> {
                        web.resumeTimers()
                        timersPaused = false
                        web.onResume()
                    }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                if (timersPaused) {
                    DiagnosticsLog.event("EmbedWebView resume timers on dispose")
                    web.resumeTimers()
                }
            }
        }
    }

    // The outro included, to the end of the credits rather than the next episode: see PlayerSurface.
    LaunchedEffect(
        autoSkipIntroOutro,
        webView,
        positionMs,
        durationMs,
        introStartMs,
        introEndMs,
        recapStartMs,
        recapEndMs,
        outroStartMs,
        outroEndMs,
    ) {
        // See PlayerSurface: seeking out of the window answers the countdown.
        autoSkipCountdown?.let { pending ->
            if (!isInSkipWindow(positionMs, pending.startMs, pending.endMs)) {
                DiagnosticsLog.event("EmbedWebView auto-skip ${pending.segment} called off positionMs=$positionMs")
                autoSkipCountdown = null
            }
        }
        if (!autoSkipIntroOutro) {
            autoSkipCountdown = null
            return@LaunchedEffect
        }
        if (positionMs <= 0L) return@LaunchedEffect

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
            isPlaying = { webIsPlaying },
            onSecondsLeft = { autoSkipSecondsLeft = it },
        )
        autoSkipCountdown = null
        requestSkipSeek(pending.endMs, "auto-${pending.segment.lowercase()}")
    }

    // Auto-play is a setting of its own, so it is deliberately not folded into the auto-skip effect
    // above. It used to be, and the two gates it inherited from that effect were the whole of what
    // this player could do: an episode advanced only when the viewer had skipping switched on *and*
    // the source had filed an outro window reaching the end of the file. A source that carries no
    // timings never advanced at all, and switching skipping off — which says the measured windows
    // are wrong, not that episodes should stop playing — turned auto-play off with it.
    //
    // [nextEpisodeTrigger] is the reading the native player uses: the tail of the file, held back
    // while highlighted credits are still rolling. Sharing it is what makes auto-play
    // mean the same thing in both players, which matters more than it sounds like — which player a
    // viewer gets is decided by whether a provider handed over a manifest, and they have no way of
    // knowing that it didn't.
    //
    // Still no card and no countdown here, unlike the native player: there is nowhere to draw one
    // that a remote can reach without fighting the page for focus. So this stays the use of an
    // outro with the least room for a wrong window, and the trigger is tested accordingly.
    LaunchedEffect(autoplay, positionMs, durationMs, outroStartMs, outroEndMs) {
        if (nextEpisodeAutoHandled) return@LaunchedEffect
        val trigger = nextEpisodeTrigger(
            positionMs = positionMs,
            durationMs = durationMs,
            outroStartMs = outroStartMs,
            outroEndMs = outroEndMs,
        )
        when (
            embedAutoAdvanceStep(
                autoplay = autoplay,
                hasNextEpisode = currentHasNextEpisode && currentOnAutoAdvance != null,
                armed = nextEpisodeArmed,
                trigger = trigger,
                durationMs = durationMs,
            )
        ) {
            EmbedAutoAdvanceStep.WAIT -> Unit
            EmbedAutoAdvanceStep.ARM -> nextEpisodeArmed = true
            EmbedAutoAdvanceStep.ADVANCE -> {
                nextEpisodeAutoHandled = true
                DiagnosticsLog.event(
                    "EmbedWebView auto-advance trigger=${trigger.name.lowercase()} " +
                        "positionMs=$positionMs durationMs=$durationMs",
                )
                currentOnAutoAdvance?.invoke()
            }
        }
    }
    }

    var tvCenterDownTime by remember { mutableLongStateOf(-1L) }
    val remoteModifier = if (device.isTv && focusPlayerOnStart && !resumeDialogVisible) {
        Modifier
            // Reports for the WebView and every control drawn over it; see [tvFocusLostTarget].
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
                // The WebView may have handed focus to Play after opening the controls.
                if (event.key == Key.DirectionCenter && event.nativeKeyEvent.downTime == tvCenterDownTime) {
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Digits are handled here as well as in the WebView's own key hook below, because
                // which of the two a press reaches depends on what is holding the remote: the row's
                // buttons while it is up, the embed itself the rest of the time. Claiming the event
                // stops it reaching the other, so the jump still happens exactly once — and a panel
                // or the episode drawer gets its digits untouched, since a number pressed at a
                // settings list must not move the picture behind it.
                if (!settingsSheetVisible && !captionsSheetVisible &&
                    !captionAppearanceVisible && !episodeDrawerExpanded &&
                    tvNumberSeekTargetMs(event.nativeKeyEvent.keyCode, durationMs) != null
                ) {
                    // Acted on once however long the key is held: see PlayerSurface for why the
                    // repeats would otherwise hold the seek back until it was released.
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        numberSeek(event.nativeKeyEvent.keyCode)
                    }
                    return@onPreviewKeyEvent true
                }
                if (!opensTvPlayerControls(event.key)) return@onPreviewKeyEvent false
                // A panel or the drawer owning the remote keeps its presses to itself: counting
                // them as interaction here would hold the control row alive underneath something
                // that covers it, which is the row the cursor then escapes into.
                if (settingsSheetVisible || captionsSheetVisible ||
                    captionAppearanceVisible || episodeDrawerExpanded
                ) {
                    return@onPreviewKeyEvent false
                }
                // A skip holding the remote over bare picture answers for the picture, which is
                // where the embed's own key hook would otherwise have heard these: Left and Right
                // still travel the timeline and Up and Down open the row. OK goes to the button.
                if (tvSkipActionFocused && !tvControlsVisible) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            currentDpadSeek(event.key == Key.DirectionRight)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            tvControlsVisible = true
                            tvControlsInteraction++
                            return@onPreviewKeyEvent true
                        }
                        else -> Unit
                    }
                }
                // A lit row the ring is not on: the WebView's hook would take this press as "open
                // the row" and do nothing with it, so it puts the ring back on the row instead.
                if (tvPressReclaimsControls(
                        controlsVisible = tvControlsVisible,
                        controlsFocused = tvControlsFocused,
                        overlayFocused = tvSkipActionFocused,
                    )
                ) {
                    DiagnosticsLog.event("EmbedWebView TV controls reclaimed key=${event.key}")
                    restoreTvControlsFocus()
                    return@onPreviewKeyEvent true
                }
                tvControlsInteraction++
                // Up with the row open moves the ring itself, so a press with nowhere left to go
                // closes the row instead of leaving it to the timer. See [tvUpClosesControls].
                if (tvControlsVisible && event.key == Key.DirectionUp) {
                    val movedFocus = focusManager.moveFocus(FocusDirection.Up)
                    if (tvUpClosesControls(movedFocus, event.nativeKeyEvent.repeatCount > 0)) {
                        DiagnosticsLog.event("EmbedWebView TV controls closed up")
                        tvControlsVisible = false
                        if (!tvSkipActionFocused) webView?.requestFocus()
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            // Screen readers swallow the D-pad, so the WebView key hook never fires under
            // TalkBack; this semantic action is the accessible way to reveal the controls.
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show player controls") {
                    tvControlsInteraction++
                    tvControlsVisible = true
                    true
                }
            }
    } else {
        Modifier
    }
    Box(modifier.then(remoteModifier)) {
        CompiledSeparately {
        if (captionAppearanceVisible) {
            PlayerPanelTheme {
            CaptionAppearanceDialog(
                onDismiss = {
                    captionAppearanceVisible = false
                    restoreTvControlsFocus()
                },
                footnote = "This server renders its own subtitles, so it may ignore some of these.",
            )
            }
        }
        androidx.compose.runtime.key(rendererGeneration) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("EmbedWebView factory create WebView")
                    DiagnosticsLog.webViewPackage("EmbedWebView factory")
                    object : WebView(ctx) {
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            // Only claim the remote while the player is the active surface. In the
                            // TV episode grid the list owns the D-pad: handling keys here ate them
                            // silently (the controls this opens are hidden in that state) and stole
                            // Center from the box that opens the player fullscreen. Report them
                            // unhandled rather than deferring to super, which would let the embed's
                            // own page act on the remote; unhandled keys let the framework move
                            // focus out of the player and back to the list.
                            if (device.isTv && !currentPlayerOwnsRemote) return false
                            if (resumeDialogVisible && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                            // Back is the app's, never the embed's. WebView answers it out of its
                            // own history — the page's iframes and ad hops make canGoBack() true
                            // more often than not — which swallowed the press: player chrome
                            // stayed up and the episode never closed. Report it unhandled so it
                            // reaches the screen's Back handling.
                            if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
                            if (device.isTv && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.downTime == tvCenterDownTime
                            ) return true
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                when {
                                    // Left and Right travel the timeline and nothing else; the
                                    // control row belongs to Up, Down and OK. See TvSeekIndicator.
                                    device.isTv &&
                                        (
                                            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                                event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                            ) &&
                                        !tvControlsVisible -> {
                                        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                        mainHandler.post { currentDpadSeek(forward) }
                                        return true
                                    }
                                    // A digit jumps to that tenth of the episode, claimed before the
                                    // page below can act on it — a number has to mean the same thing
                                    // on both players, not whatever each embed's own player does
                                    // with its keyboard shortcuts.
                                    device.isTv &&
                                        tvNumberSeekTargetMs(event.keyCode, durationMs) != null -> {
                                        val keyCode = event.keyCode
                                        mainHandler.post { currentNumberSeek(keyCode) }
                                        return true
                                    }
                                    device.isTv && opensTvPlayerControls(event.keyCode) -> {
                                        if (!tvControlsVisible && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                                            tvCenterDownTime = event.downTime
                                            if (webIsPlaying) {
                                                dispatchWebPlayerCommand(this, "pause", fallbackScript = PAUSE_VIDEO_JS)
                                                webIsPlaying = false
                                            }
                                        }
                                        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                        mainHandler.post {
                                            if (!tvControlsVisible) tvControlsOpenedByDown = down
                                            tvControlsVisible = true
                                            tvControlsInteraction++
                                        }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        dispatchWebPlayerCommand(this, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS)
                                        mainHandler.post { webIsPlaying = !webIsPlaying }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                        dispatchWebPlayerCommand(this, "play", fallbackScript = START_PLAYBACK_JS)
                                        mainHandler.post { webIsPlaying = true }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                        dispatchWebPlayerCommand(this, "pause", fallbackScript = PAUSE_VIDEO_JS)
                                        mainHandler.post { webIsPlaying = false }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                        mainHandler.post { currentQueueTvSeek(-TV_SEEK_STEP_MS) }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                        mainHandler.post { currentQueueTvSeek(TV_SEEK_STEP_MS) }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && currentHasNextEpisode -> {
                                        currentOnNextEpisode?.invoke()
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS && currentHasPreviousEpisode -> {
                                        currentOnPreviousEpisode?.invoke()
                                        return true
                                    }
                                }
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = !device.isTv || focusPlayerOnStart
                        isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
                        with(settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            // Route window.open through onCreateWindow (dropped there) instead
                            // of letting popunders replace the player in the main frame.
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = false
                            userAgentString = userAgentString.replace("; wv", "") // look less like a webview
                        }
                        // Construct the concrete bridge at registration so Android lint can
                        // verify its @JavascriptInterface methods through Kotlin's apply block.
                        addJavascriptInterface(
                            WebProgressBridge(
                                onTickCallback = { positionSec, durationSec, isPlaying, muted, volume ->
                                    if (positionSec > 0 && durationSec > 0) {
                                        // Stamped here rather than on entry: a tick that fails this
                                        // guard writes nothing, and standing the probe down for it
                                        // would leave both channels silent. That is not theoretical
                                        // — a video held at exactly 0 because autoplay was refused
                                        // ticks this way for as long as it stays paused.
                                        lastBridgeTickMs.set(SystemClock.elapsedRealtime())
                                        val nextPositionMs = (positionSec * 1000).toLong()
                                        val nextDurationMs = (durationSec * 1000).toLong()
                                        mainHandler.post {
                                            positionMs = nextPositionMs
                                            durationMs = nextDurationMs
                                            webIsPlaying = isPlaying
                                            // Written by both channels, like everything else here.
                                            // The reduced bar this drives is only ever shown while
                                            // webPlaybackAvailable is false, and a page whose bridge
                                            // ticks has already reported the video — but that is an
                                            // invariant of the injected script, not of this file, so
                                            // the tick keeps it current rather than trusting it.
                                            fallbackIsPlaying = isPlaying
                                            webVolume = if (muted) 0f else volume.toFloat().coerceIn(0f, 1f)
                                            if (isPlaying) {
                                                lastRequestedUrl.value?.let {
                                                    currentOnProgress?.invoke(it, nextPositionMs, nextDurationMs)
                                                }
                                            }
                                        }
                                    }
                                },
                                onResumeDialogCallback = { frame, visible ->
                                    mainHandler.post {
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        resumeDialogFrames.entries.removeAll { now - it.value > 2_000L }
                                        if (visible && resumeDialogFrames.size < 32) resumeDialogFrames[frame.take(80)] = now
                                        else if (!visible) resumeDialogFrames.remove(frame.take(80))
                                        val active = resumeDialogFrames.isNotEmpty()
                                        if (active != resumeDialogVisible) {
                                            DiagnosticsLog.event("EmbedWebView resume dialog visible=$active inputOwner=${if (active) "page" else "app"}")
                                            resumeDialogVisible = active
                                            if (active) {
                                                tvControlsVisible = false
                                                webView?.requestFocus()
                                            }
                                        }
                                    }
                                },
                                onVideoAvailableCallback = {
                                    mainHandler.post { webPlaybackAvailable = true }
                                },
                                onPlayerErrorCallback = { message ->
                                    mainHandler.post {
                                        val reportedFor = lastRequestedUrl.value ?: return@post
                                        // Every frame running the bootstrap may report the same
                                        // card; the first one per page is the one that counts.
                                        if (pagePlayerError?.first == reportedFor) return@post
                                        DiagnosticsLog.event(
                                            "EmbedWebView page player error ${message.take(200)} " +
                                                "positionMs=$positionMs",
                                        )
                                        pagePlayerError = reportedFor to message.take(200)
                                    }
                                },
                                onQualityLevelsCallback = { report ->
                                    val levels = parseEmbedPageQuality(report)
                                    mainHandler.post {
                                        if (levels != pageQuality) {
                                            DiagnosticsLog.event(
                                                "EmbedWebView page quality engine=${levels?.engine ?: "none"} " +
                                                    "levels=${levels?.levels?.joinToString("/") { it.label } ?: "none"} " +
                                                    "current=${levels?.current ?: -1}",
                                            )
                                        }
                                        pageQuality = levels
                                    }
                                },
                            ),
                            "AniliProgress",
                        )
                        webViewClient = webClient
                        webChromeClient = chromeClient
                        installWebPlayerBootstrap(this)
                        webView = this
                        WebViewProcessController.register(this, "embed-player")
                        DiagnosticsLog.event("EmbedWebView factory complete userAgent=${settings.userAgentString.take(100)}")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.isFocusable = !device.isTv || focusPlayerOnStart
                web.isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
                if (device.isTv && !focusPlayerOnStart) web.clearFocus()
                web.webViewClient = webClient
                val headers = webViewRefererHeaders(activeReferer)
                if (lastRequestedUrl.value != activeUrl) {
                    lastRequestedUrl.value = activeUrl
                    navigationLock.locked = false // allow the new embed's own redirect chain
                    DiagnosticsLog.event("EmbedWebView loadUrl host=${activeUrl.hostOrNone()} headers=${headers.keys.joinToString()}")
                    val needsAllAnimeShell = activeUrl.requiresAllAnimeIframeShell(activeReferer)
                    val needsAbyssShell = activeUrl.requiresAbyssIframeShell()
                    if (needsAllAnimeShell || needsAbyssShell) {
                        // OK.ru returns a blank document when opened as a top-level WebView page.
                        // The captured browser flow loads it as an iframe from AllAnime, which
                        // also supplies the required iframe navigation metadata and referer.
                        // AbyssPlayer similarly redirects top-level loads to the Abyss homepage;
                        // inside an iframe it keeps the requested video and initializes JWPlayer.
                        DiagnosticsLog.event(
                            "EmbedWebView using iframe shell host=${activeUrl.hostOrNone()} " +
                                "reason=${if (needsAbyssShell) "abyss-top-level-redirect" else "allanime-okru"}",
                        )
                        web.loadDataWithBaseURL(
                            activeReferer ?: activeUrl,
                            providerIframeShell(activeUrl),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    } else {
                        web.loadUrl(activeUrl, headers)
                    }
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                if (webView === web) webView = null
                val rendererGone = deadRendererViews.remove(web)
                DiagnosticsLog.event(
                    "EmbedWebView release url=${web.url ?: "none"} size=${web.width}x${web.height} " +
                        "rendererGone=$rendererGone",
                )
                // A dead renderer has no page to pause or blank, and a load would only ask the
                // view to start another one.
                if (!rendererGone) stopWebPlayback(web)
                web.removeJavascriptInterface("AniliProgress")
                web.clearHistory()
                web.removeAllViews()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                WebViewProcessController.release(web, "embed-player")
                web.destroy()
            },
        )
        }
        }

        CompiledSeparately {
        // TV normally reveals this bar from WebView key dispatch. Mouse/air-mouse clicks do not
        // travel through that path, and passing the first click to an embed can also trigger its
        // provider chrome or an ad. Capture empty-video clicks in Compose instead; the controls
        // are drawn later and remain directly clickable when visible.
        if (device.isTv && focusPlayerOnStart && webView != null && loadError == null && !resumeDialogVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tvControlsVisible) {
                        detectTapGestures {
                            tvControlsInteraction++
                            if (tvControlsVisible) {
                                DiagnosticsLog.event("EmbedWebView TV controls closed pointer")
                                tvControlsVisible = false
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
                                    "EmbedWebView TV controls opened pointer " +
                                        "keyboardMode=${keyboardMode ?: "screen-reader"}",
                                )
                                tvControlsVisible = true
                            }
                        }
                    },
            )
        }

        loadError?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This server's page failed to load",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "$message — try another server below.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Swallows every touch so the page never sees one: web players only raise their control
        // chrome on interaction, so starving them of taps keeps the provider UI hidden and lets
        // our overlay be the only controls. Also neuters tap-hijack ads, which need a real click.
        // Horizontal drag seeks; a vertical drag controls brightness on the left half of the
        // screen and volume on the right.
        if (touchControlsActive && !screenLocked) PlayerGestureControls(
            positionMs = positionMs,
            durationMs = durationMs,
            dragGestures = playerGestures,
            slideToSeek = playerSeekGesture,
            holdSpeed = holdPlaybackSpeed,
            holdSpeedEnabled = holdToSpeedEnabled,
            onTap = {
                touchControlsVisible = !touchControlsVisible
                touchControlsInteraction++
            },
            onDoubleTap = { zone ->
                // Sides seek ten seconds through onSeek below; the middle keeps toggling playback.
                if (zone == PlayerDoubleTapZone.PlayPause) {
                    DiagnosticsLog.event("EmbedWebView double tap playPause")
                    webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                    webIsPlaying = !webIsPlaying
                    playbackGestureIsPlaying = webIsPlaying
                } else {
                    touchControlsInteraction++
                }
            },
            onSeek = { target ->
                seekWebVideo(webView, target)
                positionMs = target
                touchControlsInteraction++
            },
            onHoldSpeed = { active ->
                if (active) {
                    preHoldSpeed = playbackSpeed
                    playbackSpeed = holdPlaybackSpeed
                } else {
                    playbackSpeed = preHoldSpeed
                }
            },
        )

        // Older WebViews without document-start frame injection cannot expose cross-origin video
        // state. They still never receive pointer input: taps only reveal/hide our fallback row, so
        // provider controls and tap-hijack ads stay inaccessible.
        if (fallbackControlsActive) Box(
            Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    detectTapGestures {
                        fallbackControlsVisible = !fallbackControlsVisible
                        fallbackInteraction++
                    }
                },
        )

        // The gesture surface is what normally starves the page of touches; while locked the lock
        // overlay is the one taking them instead, so the provider's chrome stays just as unreachable.
        if (touchControlsActive) {
            PlayerLockOverlay(
                locked = screenLocked,
                onUnlock = {
                    screenLocked = false
                    touchControlsVisible = true
                    touchControlsInteraction++
                },
            )
        }

        if (touchControlsActive && touchControlsVisible && !screenLocked) {
            EmbedTouchControls(
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                fillerSkip = fillerSkip,
                onSeekAnchorChanged = { controlsSeekAnchor = it },
                markers = seekMarkers,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    seekWebVideo(webView, (positionMs - 10_000L).coerceAtLeast(0L))
                    touchControlsInteraction++
                },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView touch control playPause")
                    webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                    webIsPlaying = !webIsPlaying
                    touchControlsInteraction++
                },
                onForward = {
                    seekWebVideo(webView, positionMs + 10_000L)
                    touchControlsInteraction++
                },
                onNext = { currentOnNextEpisode?.invoke() },
                onSeek = { targetMs ->
                    seekWebVideo(webView, targetMs)
                    positionMs = targetMs // the poll confirms next tick; without this the thumb snaps back first
                    touchControlsInteraction++
                },
                onCaptions = { captionsSheetVisible = true },
                onSettings = { settingsSheetVisible = true },
                playbackSpeed = playbackSpeed,
                onAddToList = onAddToList,
                onEpisodes = if (episodes.isNotEmpty() && onSelectEpisode != null) {
                    {
                        episodeDrawerExpanded = true
                        touchControlsInteraction++
                    }
                } else null,
                isFullscreen = isFullscreen,
                onFullscreen = onToggleFullscreen,
                onLock = { screenLocked = true },
                onInteract = { touchControlsInteraction++ },
            )
        }

        playbackGestureIsPlaying?.let { isPlaying ->
            PlaybackGestureIndicator(isPlaying, Modifier.align(Alignment.Center))
        }

        if (settingsSheetVisible) {
            PlayerSettingsSheet(
                onDismiss = {
                    settingsSheetVisible = false
                    restoreTvControlsFocus()
                },
                autoplay = autoplay,
                onAutoplayChange = SettingsStore::setAutoplay,
                fullscreen = isFullscreen,
                speed = if (webPlaybackAvailable) playbackSpeed else null,
                onSpeedChange = {
                    playbackSpeed = it
                    // Remembered, so a speed chosen once carries into the next episode instead of
                    // having to be set again every time the credits roll. Only the slider writes
                    // here — hold-to-speed assigns `playbackSpeed` directly, so a thumb
                    // held on the screen never becomes the standing choice.
                    SettingsStore.setDefaultPlaybackSpeed(it)
                },
                // The page player's own ladder leads where it has one: it switches rendition in
                // place, while the stream ladder below reloads the page and has to seek back.
                qualityOptions = pageQuality?.let { ladder ->
                    buildList {
                        if (ladder.autoSupported) {
                            add(
                                PlayerQualityOption(
                                    label = "Auto",
                                    selected = ladder.current < 0,
                                    height = null,
                                ) { dispatchPageQuality(webView, -1) },
                            )
                        }
                        ladder.levels.forEach { level ->
                            add(
                                PlayerQualityOption(
                                    label = level.label,
                                    selected = ladder.current == level.id,
                                    height = level.height,
                                ) { dispatchPageQuality(webView, level.id) },
                            )
                        }
                    }
                } ?: embedQualityStreams.mapNotNull { option ->
                    val height = option.height ?: declaredVideoHeight(option.quality) ?: return@mapNotNull null
                    PlayerQualityOption(
                        label = "${height}p",
                        selected = option.url == activeUrl,
                        onSelect = {
                            pendingSeekMs = positionMs
                            activeUrl = option.url
                        },
                        height = height,
                    )
                },
                qualityMessage = embedQualityMessage(
                    hasLadder = pageQuality != null || embedQualityStreams.isNotEmpty(),
                    hasOtherServers = embedServerStreams.size > 1,
                ),
                serverOptions = embedServerLabels(embedServerStreams).map { (option, label) ->
                    PlayerQualityOption(
                        label = label,
                        selected = option.url == activeUrl,
                        onSelect = {
                            pendingSeekMs = positionMs
                            activeUrl = option.url
                        },
                    )
                },
                serverMessage = "Different players for the same episode. Switching keeps your place.",
                audioSwitch = audioSwitch,
                onAudioSwitch = { choice ->
                    // Re-picking the side already playing must not reload it: the tab is a state
                    // readout as much as a control, and pressing it is how a viewer checks.
                    if (choice != audioSwitch?.selected) {
                        settingsSheetVisible = false
                        onAudioSwitch(choice)
                    }
                },
                autoSkip = autoSkipIntroOutro,
                onAutoSkipChange = SettingsStore::setAutoSkipIntroOutro,
                // Only where there is a list to move along: offline playback has no Next.
                autoSkipFiller = autoSkipFiller.takeIf { episodes.size > 1 },
                onAutoSkipFillerChange = SettingsStore::setAutoSkipFiller,
                skipTimingStatus = skipTimingStatus,
                skipReport = skipReport,
                onReportSkipTiming = onReportSkipTiming,
            )
        }
        }

        CompiledSeparately {
        if (captionsSheetVisible) {
            PlayerCaptionsSheet(
                onDismiss = {
                    captionsSheetVisible = false
                    restoreTvControlsFocus()
                },
                fullscreen = isFullscreen,
                // Which subtitle track is still the server's business, but whether to show one at
                // all is not: an embed that burns nothing in can be watched without captions, and
                // before this the sheet only explained why there was nothing to press.
                subtitleOptions = buildList {
                    add(
                        PlayerQualityOption(
                            label = "Off",
                            selected = !embedCaptionsEnabled,
                            onSelect = {
                                embedCaptionsEnabled = false
                                rememberCaptionChoice(category, captionsOn = false)
                            },
                        ),
                    )
                    val tracks = perStream.embedCaptionTracks.value
                    if (tracks.isEmpty()) {
                        // A player that publishes no list: whether to show its captions is still ours.
                        add(
                            PlayerQualityOption(
                                label = "On (this server's subtitles)",
                                selected = embedCaptionsEnabled,
                                onSelect = {
                                    embedCaptionsEnabled = true
                                    rememberCaptionChoice(category, captionsOn = true)
                                },
                            ),
                        )
                    }
                    tracks.forEachIndexed { position, label ->
                        val index = position + 1
                        add(
                            PlayerQualityOption(
                                label = label,
                                selected = embedCaptionsEnabled &&
                                    perStream.embedCaptionIndex.intValue == index,
                                onSelect = {
                                    webView?.evaluateJavascript(selectEmbedCaptionJs(index), null)
                                    perStream.embedCaptionIndex.intValue = index
                                    EmbedCaptionChoice.label = label
                                    embedCaptionsEnabled = true
                                    rememberCaptionChoice(category, captionsOn = true)
                                    DiagnosticsLog.event("EmbedWebView caption language index=$index")
                                },
                            ),
                        )
                    }
                },
                onCaptionAppearance = {
                    captionsSheetVisible = false
                    captionAppearanceVisible = true
                },
            )
        }

        // Reduced native bar for old WebView builds that cannot report cross-origin position or
        // duration. Provider controls remain suppressed, so seeking is unavailable in this rare
        // compatibility mode instead of exposing the page UI.
        if (fallbackControlsActive && fallbackControlsVisible) Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onAddToList?.let { openList ->
                    PlayerControlIconButton(
                        "Add to My List",
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        onClick = {
                            openList()
                            fallbackInteraction++
                        },
                    )
                }
                PlayerControlIconButton(
                    "Previous episode",
                    Icons.Default.SkipPrevious,
                    enabled = hasPreviousEpisode && currentOnPreviousEpisode != null,
                    onClick = { currentOnPreviousEpisode?.invoke() },
                )
                PlayerControlIconButton(
                    if (fallbackIsPlaying) "Pause" else "Play",
                    if (fallbackIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    onClick = {
                        DiagnosticsLog.event("EmbedWebView fallback playPause")
                        webView?.let {
                            dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS)
                        }
                        fallbackIsPlaying = !fallbackIsPlaying
                        fallbackInteraction++
                    },
                )
                PlayerControlIconButton(
                    "Next episode",
                    Icons.Default.SkipNext,
                    enabled = hasNextEpisode && currentOnNextEpisode != null,
                    onClick = { currentOnNextEpisode?.invoke() },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerControlIconButton(
                    "Settings",
                    Icons.Default.Settings,
                    onClick = {
                        settingsSheetVisible = true
                        fallbackInteraction++
                    },
                )
                onToggleFullscreen?.let { toggle ->
                    PlayerControlIconButton(
                        if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        onClick = {
                            toggle()
                            fallbackInteraction++
                        },
                    )
                }
            }
        }

        // Only while the row is down: with the row up its own bar already says the same thing.
        if (device.isTv && focusPlayerOnStart && dpadSeekVisible && !tvControlsVisible && !resumeDialogVisible) {
            TvSeekIndicator(
                positionMs = positionMs,
                durationMs = durationMs,
                forward = dpadSeekForwardDirection,
                // Top, not bottom: subtitles own the lower band — and an embed renders its own
                // wherever it likes — so anything down there collides eventually.
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(TV_SAFE_AREA_INSET),
            )
        }

        if (device.isTv && focusPlayerOnStart && tvControlsVisible && !resumeDialogVisible) {
            TvPlayerControls(
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                fillerSkip = fillerSkip,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                playPauseFocusRequester = tvPlayPauseFocus,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = { queueTvSeek(-TV_SEEK_STEP_MS) },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView TV control playPause")
                    webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                    webIsPlaying = !webIsPlaying
                },
                onForward = { queueTvSeek(TV_SEEK_STEP_MS) },
                onSeek = { target ->
                    DiagnosticsLog.event("EmbedWebView TV scrub seek targetMs=$target")
                    seekWebVideo(webView, target)
                    positionMs = target
                    tvControlsInteraction++
                },
                markers = seekMarkers,
                // A pointer dragging the bar reaches none of the key handling that normally keeps
                // the row up, so it says so itself.
                onInteract = { tvControlsInteraction++ },
                onNext = { currentOnNextEpisode?.invoke() },
                onSettings = {
                    // The full playback settings panel (quality, speed, autoplay, auto-skip,
                    // volume) with the TV side-panel presentation — the old quality-or-speed
                    // dialog left most settings unreachable from a remote.
                    DiagnosticsLog.event("EmbedWebView TV control settings")
                    settingsSheetVisible = true
                },
                onCaptions = if (webPlaybackAvailable) {
                    {
                        DiagnosticsLog.event("EmbedWebView TV control captions")
                        captionsSheetVisible = true
                    }
                } else {
                    null
                },
                onEpisodes = if (episodes.isNotEmpty() && onSelectEpisode != null) {
                    {
                        DiagnosticsLog.event("EmbedWebView TV control episodes")
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

        CompiledSeparately {
        if (episodeDrawerExpanded && episodes.isNotEmpty() && onSelectEpisode != null) {
            InPlayerEpisodeDrawer(
                episodes = episodes,
                currentIndex = currentIndex,
                artworkUrl = artworkUrl,
                onSelectEpisode = { index ->
                    episodeDrawerExpanded = false
                    onSelectEpisode(index)
                },
                onDismiss = {
                    episodeDrawerExpanded = false
                    restoreTvControlsFocus()
                },
            )
        }

        // Only offer a skip once the page has actually started playing. Embeds that open on a
        // poster (Kwik) sit at position 0 indefinitely, and an intro window starting at 0 would
        // otherwise pin "Skip Intro" on screen from load until the viewer presses play. A non-zero
        // duration is the poll's proof it reached the <video> and saw it running.
        val skipAvailable = durationMs > 0L
        // Every panel is composed above, so anything composed here is painted over it rather than
        // under it — the episode drawer wore a "+Ns" across its rows. The skip action stands down
        // for all of them, since none of the picture it acts on is visible behind a panel anyway.
        val panelOwnsScreen = settingsSheetVisible || captionsSheetVisible ||
            captionAppearanceVisible || episodeDrawerExpanded
        val overlaysHidden = panelOwnsScreen || inPictureInPicture

        // See PlayerSurface for the whole account. Here the touch bar is the only one of the three
        // that carries its own copy, so a TV row, an old WebView's fallback row and a pause that has
        // outlived any of them all get theirs from here.
        val barCarriesClock = touchControlsActive && touchControlsVisible
        if (playerClock && !screenLocked && !overlaysHidden && !resumeDialogVisible &&
            !barCarriesClock && (chromeVisible || (webPlaybackAvailable && !webIsPlaying))
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

        if (!screenLocked && !overlaysHidden && !resumeDialogVisible) {
            SleepTimerWarning(
                Modifier
                    .align(Alignment.TopCenter)
                    .sleepTimerWarningAnchor(isTv = device.isTv),
            )
        }

        val inRecap = isInSkipWindow(positionMs, recapStartMs, recapEndMs)
        val inIntro = isInSkipWindow(positionMs, introStartMs, introEndMs)
        val recapPopup = rememberTimedSkipPopup(inRecap, webIsPlaying, recapStartMs, recapEndMs)
        val introPopup = rememberTimedSkipPopup(inIntro, webIsPlaying, introStartMs, introEndMs)
        val action: Pair<String, () -> Unit>? = when {
            overlaysHidden -> null
            !skipAvailable -> null
            // With or without the controls, and the count appended at the button: see PlayerSurface.
            autoSkipCountdown != null -> autoSkipCountdown!!.let { pending ->
                "Skipping ${pending.segment}" to {
                    autoSkipCountdown = null
                    requestSkipSeek(pending.endMs, "auto-${pending.segment.lowercase()}-now")
                }
            }
            // Before the intro: a show with both runs the recap first, and the two windows can abut.
            // Both timed skips pop up on their own and then ride the controls, for the reason
            // spelled out in PlayerSurface: an intro window that starts at 0 would otherwise pin
            // the button over the picture for the whole opening. See TIMED_SKIP_POPUP_MS.
            recapEndMs != null && (controlsShowing || recapPopup) && inRecap ->
                "Skip Recap" to { requestSkipSeek(recapEndMs, "manual-recap") }
            introEndMs != null && (controlsShowing || introPopup) && inIntro ->
                "Skip Intro" to { requestSkipSeek(introEndMs, "manual-intro") }
            // Only an outro that reaches the end of this file; see outroWindowIsCredits. Skips the
            // credits, not the episode: see PlayerSurface.
            outroEndMs != null &&
                outroWindowIsCredits(outroStartMs, outroEndMs, durationMs) &&
                isInSkipWindow(positionMs, outroStartMs, outroEndMs) ->
                "Skip Outro" to { requestSkipSeek(outroSkipTargetMs(outroEndMs, durationMs), "manual-outro") }
            // The fallback for shows the timings miss. It rides with the controls, so it can be
            // offered for the whole episode without ever sitting over the picture.
            showSkipButton &&
                controlsShowing &&
                shouldShowManualSkipButton(positionMs, durationMs) ->
                "+${skipButtonSeconds}s" to {
                    val targetMs = (positionMs + skipButtonSeconds * 1_000L).coerceAtMost(durationMs)
                    requestSkipSeek(targetMs, "manual-fixed")
                }
            else -> null
        }
        LaunchedEffect(action?.first, activeUrl) {
            DiagnosticsLog.event(
                "EmbedWebView skip action=${action?.first ?: "none"} positionMs=$positionMs " +
                    "durationMs=$durationMs status=${skipTimingStatus.name.lowercase()}",
            )
        }
        val actionTakesRemote = device.isTv && focusPlayerOnStart && !screenReaderActive &&
            tvSkipActionTakesRemote(action?.first)
        SideEffect {
            tvTimedSkipOffered = actionTakesRemote
            // A removed node is not guaranteed a last focus callback.
            if (action == null) tvSkipActionFocused = false
        }
        // The skip over bare picture takes the remote so OK alone answers it, and gives it to the
        // row once its window passes and it becomes "+Ns". See the same effect in PlayerSurface.
        LaunchedEffect(action?.first, tvControlsVisible, focusPlayerOnStart) {
            if (!device.isTv || !focusPlayerOnStart) return@LaunchedEffect
            if (actionTakesRemote && !tvControlsVisible) {
                // The button is composed this frame; the requester is attached by the next.
                delay(64)
                runCatching { tvSkipActionFocus.requestFocus() }
                    .onFailure { DiagnosticsLog.throwable("EmbedWebView skip action unfocusable", it) }
            } else if (!actionTakesRemote && tvSkipActionFocused && tvControlsVisible) {
                restoreTvControlsFocus()
            }
        }
        // The action is focusable and gone the instant it is pressed, and Compose leaves the window
        // with nothing focused when the node holding the ring is removed — so answering "Next
        // Episode" from a lit control row killed every remote key until the episode was closed and
        // reopened. See [tvOverlayDismissFocus]; the native player carries the same repair.
        var tvOverlayWasPresent by remember { mutableStateOf(false) }
        LaunchedEffect(
            action != null,
            device.isTv,
            focusPlayerOnStart,
            screenReaderActive,
            panelOwnsScreen,
            tvControlsVisible,
            tvControlsFocused,
        ) {
            if (!device.isTv || !focusPlayerOnStart) return@LaunchedEffect
            if (action != null) {
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
                    controlsVisible = tvControlsVisible,
                    controlsFocused = tvControlsFocused,
                )
            ) {
                TvEpisodeChangeFocus.CONTROLS -> {
                    DiagnosticsLog.event("EmbedWebView TV controls refocused after overlay dismissed")
                    restoreTvControlsFocus()
                }
                TvEpisodeChangeFocus.SURFACE -> runCatching { webView?.requestFocus() }
                TvEpisodeChangeFocus.HOLD, TvEpisodeChangeFocus.UNCHANGED -> Unit
            }
        }
        action?.let { (label, onClick) -> PlayerFloatingActionFrame { compactHeight ->
            val controlsShowing = chromeVisible
            // See PlayerSurface: on a TV the outer ~5% is overscan, so the usual 24.dp corner
            // inset puts this button off the edge of the picture entirely.
            val edgeInset = if (device.isTv) TV_SAFE_AREA_INSET else 24.dp
            val countdown = autoSkipCountdown
            WebSkipButton(
                label = if (countdown != null) autoSkipCountdownLabel(countdown, autoSkipSecondsLeft) else label,
                countdownSeconds = countdown?.let { autoSkipSecondsLeft },
                onClick = onClick,
                compact = compactHeight,
                // Bottom right either way — see PlayerSurface. It only rises far enough to clear
                // the seek bar while the controls are up, and rides on the measured anchor so the
                // icons below the timeline cannot appear into it.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = edgeInset,
                        bottom = if (controlsShowing) {
                            controlsSeekAnchor +
                                if (compactHeight) CompactControlsBarActionGap else ControlsBarActionGap
                        } else {
                            edgeInset
                        },
                    )
                    .focusRequester(tvSkipActionFocus)
                    .onFocusChanged { tvSkipActionFocused = it.hasFocus },
            )
        } }
        }
    }
}

private fun String.requiresAllAnimeIframeShell(referer: String?): Boolean {
    val host = runCatching { Uri.parse(this).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    val refererHost = runCatching { Uri.parse(referer).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    return host == "ok.ru" && refererHost == "allanime.day"
}

internal fun String.requiresAbyssIframeShell(): Boolean {
    // java.net.URI keeps this policy testable on the local JVM; android.net.Uri is a stub there.
    val host = runCatching { java.net.URI(this).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    return host == "abyssplayer.com" || host?.endsWith(".abyssplayer.com") == true
}

internal fun providerIframeShell(url: String): String {
    val escaped = url
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:#000;overflow:hidden"><iframe src="$escaped" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="no-referrer-when-downgrade" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe></body></html>"""
}

@Composable
private fun WebSkipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countdownSeconds: Int? = null,
    /** The short inline player; see [CompactSkipActionHeight]. */
    compact: Boolean = false,
) {
    val extraFocus1 = rememberFocusFill()
    AutoSkipButtonBounds {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
        colors = extraFocus1.buttonColors(
            ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
            ),
        ),
        contentPadding = if (compact) {
            PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        } else {
            PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        },
        modifier = modifier
            .then(if (compact) Modifier.height(CompactSkipActionHeight) else Modifier)
            .focusFill(extraFocus1, RoundedCornerShape(3.dp))
            .autoSkipSweep(
                secondsLeft = countdownSeconds,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                shape = RoundedCornerShape(3.dp),
            ),
    ) {
        Text(
            label.uppercase(),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
    }
}

/**
 * Legacy main-frame fallback for WebViews without document-start injection. Current WebViews use
 * the all-frame poll installed by [HIDE_PROVIDER_CHROME_JS].
 */
private val PROGRESS_POLL_JS = """
    (function() {
      if (window.__aniliProgressHooked) return;
      window.__aniliProgressHooked = true;
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      setInterval(function() {
        try {
          var v = findVideo();
          if (v && !window.__aniliVideoReported) {
            window.__aniliVideoReported = true;
            AniliProgress.onVideoAvailable();
          }
          if (v && isFinite(v.duration) && v.duration > 0 && v.currentTime >= 0) {
            AniliProgress.onTick(v.currentTime, v.duration, !v.paused, v.muted, v.volume);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

private fun SET_PLAYBACK_SPEED_JS(speed: Float): String = """
    (function() {
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      try {
        var v = findVideo();
        if (!v) return false;
        v.defaultPlaybackRate = $speed;
        v.playbackRate = $speed;
        return Math.abs(v.playbackRate - $speed) < 0.01;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

/**
 * How long a main-frame error status is given to turn into a playing video before the source is
 * declared dead. Long enough for a slow host on a weak connection to still come up, short enough
 * that a genuine 404 fails over well before a viewer would give up and leave the player.
 */
private const val EMBED_DEAD_PAGE_GRACE_MS = 6_000L

/**
 * How long the page player's own error card is given to recover before the server is failed over.
 * Some embeds answer a JW error by loading another source themselves; if the clock moves in this
 * window, that worked and the app leaves it alone.
 */
private const val EMBED_PAGE_ERROR_GRACE_MS = 3_000L

/**
 * How long an embed gets to either finish loading or produce a video before it is called dead.
 *
 * A page that answers an error status is handled by [EMBED_DEAD_PAGE_GRACE_MS] above; this covers
 * the page that answers *nothing* — no `onPageFinished`, no `onReceivedError`, no video. Neither of
 * the existing recovery paths can fire for it, so it used to sit there forever: the native player
 * has already been torn down by the time an embed is composed, and an embed with no loaded page
 * draws no controls of its own either, so the whole screen is a black rectangle the viewer can only
 * escape by leaving. Reported as "I clicked the next episode and nothing happened, and the player
 * controls disappeared" — bonk's own embed, reached after its first stream 403s, taking over
 * twenty-five seconds without a single callback.
 *
 * Twenty seconds because the load is judged from the *composition*, which is several seconds before
 * the page starts fetching its third-party player, and a slow host that comes up at fifteen still
 * plays. Past that, another server is a better bet than more waiting.
 */
private const val EMBED_LOAD_TIMEOUT_MS = 20_000L

/**
 * How long a *finished* embed page gets to produce a video before another server is tried.
 *
 * [EMBED_LOAD_TIMEOUT_MS] covers the page that never answers; this covers the opposite, and worse,
 * case — the page that answers correctly and is simply empty. It stops being the load watchdog's
 * problem the instant `onPageFinished` fires, which is why it needs its own clock.
 *
 * Counted from the finish rather than the composition, so it is additional to the load budget by
 * design: the page has already proved it can serve, and what is being waited on now is its player
 * script. Twenty seconds covers a Fire TV Stick instantiating JW Player through an ad stack — the
 * measured case took fifteen seconds just to finish loading — and the embed that provoked this had
 * produced nothing after thirty-four.
 */
private const val EMBED_SILENT_PAGE_TIMEOUT_MS = 20_000L

/** How often the load watchdog checks in, and the granularity of the time it credits. */
private const val EMBED_LOAD_POLL_MS = 500L

/** The point at which a still-loading embed is worth a diagnostics line but not yet a failure. */
private const val EMBED_SLOW_LOAD_NOTICE_MS = 10_000L

/**
 * How many times, and how far apart, the app presses play on an embed that should have started.
 *
 * The gap clears one tick of the once-a-second progress probe, so each attempt is judged against a
 * fresh reading rather than the same stale one. Five of them covers a player that attaches its
 * video well before it can act on a press, without pressing on so long that it could collide with a
 * viewer who has given up waiting and started the episode themselves.
 */
private const val EMBED_PLAY_ATTEMPTS = 5
private const val EMBED_PLAY_RETRY_MS = 1_200L

/** Enough to name every ad network on a page without letting one flood a diagnostics bundle. */
private const val MAX_LOGGED_FOREIGN_ORIGINS = 40

/** How recent a bridge tick must be for the main-frame probe to stand down. */
private const val BRIDGE_TICK_FRESH_MS = 2_500L

/**
 * Origins an embed is refused, matched on the registrable domain and any subdomain of it.
 *
 * This is the blocklist the third-party subresource log was collecting evidence for. The evidence:
 * an AnimeDekho episode on a VU 4K box (armeabi-v7a, 192 MB memory class) spent 40-70% CPU and
 * dropped frames up to 780 ms while its AbyssPlayer page pulled in tag manager, two analytics
 * beacons and a syndicated ad — and the ad's own audio bid cost the video its audio focus, which
 * this app answers by pausing. Playback then needed up to five synthetic presses to start, and
 * sometimes never did. A phone absorbs that page; a TV box does not.
 *
 * Every entry is an advertising, analytics or beacon origin that no player needs. Script CDNs the
 * players genuinely load from — `cdnjs.cloudflare.com`, `iamcdn.net` — are deliberately absent:
 * blocking one of those takes the video with it, which is the failure this list must not cause.
 * `sssrr.org` is listed by its domain because the popunder rotates a fresh random subdomain per
 * page load, so naming the host seen in any one bundle would block nothing.
 */
private val EMBED_BLOCKED_ORIGINS = setOf(
    "googlesyndication.com",
    "googletagmanager.com",
    "google-analytics.com",
    "doubleclick.net",
    "cloudflareinsights.com",
    "morphify.net",
    "sssrr.org",
    // Analytics beacons, named by the embed pages' own third-party host logs across four tester
    // bundles (2026-09-12 to 09-22): each loaded on nearly every embed episode and none serves a
    // byte of video. The other hosts in those logs were CDNs, libraries the players need, or
    // unidentified, and stay served — see the note on [shouldInterceptRequest] for why guessing costs
    // the video.
    "statlytic.net",
    "plausible.io",
    "jwpltx.com",
)

/**
 * An empty 200 for a refused subresource, built fresh each time because the stream is consumed.
 *
 * Answering rather than failing is deliberate: a page whose beacon errors sometimes retries it on a
 * timer, which is the CPU this is trying to give back.
 */
private fun emptyBlockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

private const val CAPTION_STYLE_ELEMENT_ID = "anili-caption-style"

/**
 * Restyles captions in the main document and any same-origin iframe. Idempotent: re-running with a
 * new style rewrites the same `<style>` element instead of stacking duplicates.
 */
private fun CAPTION_STYLE_JS(style: CaptionStyle): String = """
    (function() {
      var css = ${captionCss(style).toJsStringLiteral()};
      function apply(doc) {
        if (!doc) return;
        var el = doc.getElementById('$CAPTION_STYLE_ELEMENT_ID');
        if (!el) {
          el = doc.createElement('style');
          el.id = '$CAPTION_STYLE_ELEMENT_ID';
          (doc.head || doc.documentElement).appendChild(el);
        }
        el.textContent = css;
      }
      try {
        apply(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { apply(frames[i].contentDocument); } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

/**
 * How the captions on an embed page *look*: `::cue` for Chromium's own WebVTT rendering, and the
 * class selectors for the embed players that draw captions as ordinary DOM instead. Ours is
 * appended last, so at equal specificity it wins.
 *
 * Where they sit is deliberately not here. Two stylesheets are installed in the same document —
 * this one and [applyCaptionStyleJs]'s — and while both named a caption element, both moved it:
 * this one aimed at the line (`.jw-text-track-cue`, `.vjs-text-track-cue > div`) and the other at
 * the layer around it, so one requested margin was applied twice over. [applyCaptionStyleJs] owns
 * placement now, on its own, for every edge and every player.
 */
internal fun captionCss(style: CaptionStyle): String {
    val background = style.backgroundCssRgba()
    // `background` first so the shorthand can't reset the colour we set right after it.
    val declarations = "background: $background !important; " +
        "background-color: $background !important; " +
        "color: ${style.textCssRgba()} !important; " +
        "text-shadow: ${style.edgeStyle.toCssTextShadow()} !important; " +
        "font-size: ${style.textScalePercent}% !important; " +
        "font-weight: ${if (style.boldText) 700 else 400} !important;" +
        style.font.cssFamily?.let { " font-family: $it !important;" }.orEmpty()
    return "::cue { $declarations }\n$DOM_CAPTION_SELECTORS { $declarations }"
}

private const val DOM_CAPTION_SELECTORS =
    ".plyr__caption, .vjs-text-track-cue > div, .jw-text-track-cue, .art-subtitle"

private fun CaptionEdgeStyle.toCssTextShadow(): String = when (this) {
    CaptionEdgeStyle.NONE -> "none"
    CaptionEdgeStyle.OUTLINE ->
        "-1px -1px 1px #000, 1px -1px 1px #000, -1px 1px 1px #000, 1px 1px 1px #000"
    CaptionEdgeStyle.DROP_SHADOW -> "2px 2px 3px rgba(0, 0, 0, 0.9)"
}

private fun String.toJsStringLiteral(): String =
    "'" + replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

/**
 * Starts a paused embed. Only ever calls play() on a video that is actually paused, so a provider
 * that already autoplays is left alone rather than being toggled off. `play()` returns a promise
 * that rejects when the page still insists on a gesture; that is a no-op, not a failure.
 *
 * Deliberately not latched to one run per page. A rejected promise leaves the video exactly as it
 * was found, so the caller's retry needs this to be repeatable — the old `__aniliAutoStarted` guard
 * was set on finding a video rather than on it playing, and spent the whole page's budget on the
 * one attempt least likely to work.
 */
internal val START_PLAYBACK_JS = """
    (function() {
      function start(root) {
        var video = root.querySelector('video');
        if (!video || !video.paused) return !!video;
        var play = video.play();
        if (play && play.catch) play.catch(function() {});
        return true;
      }
      try {
        if (start(document)) return;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            if (frames[i].contentDocument && start(frames[i].contentDocument)) return;
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* best effort */ }
    })();
""".trimIndent()

/**
 * Removes embed-player chrome while leaving video and captions intact. The script is installed at
 * document start in every frame, so it reaches cross-origin Kiwi/Kwik iframes that a later
 * [WebView.evaluateJavascript] call cannot access. The observer makes the suppression survive SPA
 * rerenders and players that recreate their controls after playback begins.
 */
internal val HIDE_PROVIDER_CHROME_JS = """
    (function() {
      if (window.__aniliChromeSuppressionInstalled) return;
      window.__aniliChromeSuppressionInstalled = true;

      var styleId = 'anili-hide-provider-chrome';
      var css = [
        '.plyr__controls',
        '.plyr__control--overlaid',
        '.plyr__menu__container',
        '.vjs-control-bar',
        '.vjs-big-play-button',
        // Video.js 8 puts the title strip up for the whole of a pause, apart from the control bar.
        '.vjs-title-bar',
        '.jw-controlbar',
        '.jw-display-controls',
        '.jw-title',
        '.jw-logo',
        // JW's darkening gradient is a sibling of the control bar rather than part of it, so with
        // only the bar hidden a paused MegaPlay/Filemoon/StreamWish frame kept a dark band along
        // its bottom edge. Its "Up Next" card rides the same corner as the app's own.
        '.jw-controls-backdrop',
        '.jw-nextup-container',
        '.art-controls',
        // ArtPlayer (ReAnime) hangs its timeline beside the button row, not inside it, and keeps
        // that whole bottom strip up for as long as the video is paused — so with only the buttons
        // hidden, its seek bar sat under ours and stayed on screen through every pause. The mini
        // bar is the thin copy it draws once the full one hides.
        '.art-bottom',
        '.art-progress',
        '.art-mini-progress-bar',
        '.art-center',
        '.art-mask',
        '.art-contextmenus',
        '.media-control',
        '.play-wrapper',
        '.fluid_controls_container',
        '.fluid_initial_play_button',
        '.mejs__controls',
        '.mejs__overlay-play',
        '.shaka-controls-container',
        '.dplayer-controller',
        // DPlayer draws its gradient as a sibling of the controller and flashes a play/pause glyph
        // in the centre on every toggle — over the app's own.
        '.dplayer-controller-mask',
        '.dplayer-bezel',
        '.dplayer-mobile-play',
        '.dplayer-menu',
        '.dplayer-logo',
        '.xgplayer-controls',
        '.xgplayer-start',
        '.fp-controls',
        '.fp-header',
        '.fp-play',
        '.playkit-control-bar',
        '.playkit-pre-playback-play-button',
        '.playkit-overlay-button',
        '.op-controls',
        '.op-player__play',
        '[data-media-controls]',
        // Vidstack's default layout names its whole control layer this way, timeline included.
        '.vds-controls',
        '[data-media-button]',
        'media-controls',
        'media-play-button',
        '.back-button',
        '.back-btn',
        '.backButton',
        '.player-back',
        '.player__back',
        '.plyr__back',
        '.vjs-back-button',
        '.jw-back-button',
        '.art-back',
        'button[aria-label="Back" i]',
        'button[title="Back" i]',
        '[data-testid="back-button"]',
        '[data-testid="player-back"]',
        '[class~="player-controls"]',
        '[class~="player-control-bar"]',
        '[class~="controls-container"]',
        '[class~="big-play-button"]',
        '[class~="center-play-button"]',
        // MegaPlay (anikoto) paints its own Skip Intro/Outro next to ours, in its own overlay
        // layer above the JW player rather than inside the control bar. Hide the layers, not just
        // the two anchors: '.botright' is absolutely positioned into the same bottom-right corner
        // the app's own skip button takes.
        '#overlay-control-handle',
        '#overlay-control-megaplay',
      ].join(',') +
        '{display:none!important;opacity:0!important;visibility:hidden!important;' +
        'pointer-events:none!important;}';
      css += 'video::-webkit-media-controls{display:none!important;}';
      css += 'video::-webkit-media-controls-enclosure{display:none!important;}';
      css += 'video::-webkit-media-controls-panel{display:none!important;}';
      // Several providers ship a white page body. The WebView underneath is black, but the page
      // paints over it, so any strip the video does not cover — below a 16:9 player on a taller
      // screen, or the letterbox around a differently-shaped one — came out as a white band.
      css += 'html,body{background:#000!important;}';

      function installStyle() {
        var parent = document.head || document.documentElement;
        if (!parent) return;
        var style = document.getElementById(styleId);
        if (!style) {
          style = document.createElement('style');
          style.id = styleId;
          parent.appendChild(style);
        }
        if (style.textContent !== css) style.textContent = css;
      }

      function suppressNativeControls(root) {
        if (!root) return;
        var videos = [];
        if (root.matches && root.matches('video')) videos.push(root);
        if (root.querySelectorAll) {
          var descendants = root.querySelectorAll('video');
          for (var i = 0; i < descendants.length; i++) videos.push(descendants[i]);
        }
        for (var j = 0; j < videos.length; j++) {
          var video = videos[j];
          video.controls = false;
          video.removeAttribute('controls');
          video.setAttribute('controlsList', 'nodownload nofullscreen noremoteplayback');
          try { video.disablePictureInPicture = true; } catch (e) { /* optional */ }
        }
        if (videos.length && document.documentElement) {
          document.documentElement.classList.add('anili-video-document');
        }
      }

      // AbyssPlayer keeps #overlay mounted after Anilili starts the underlying JW video directly.
      // Hide the entire exact overlay shape, not just its white triangle: an invisible #overlay
      // would otherwise keep intercepting the full player surface. The IDs are too generic to use
      // independently, so require both the direct parent/child relationship and a real <video>.
      function hideKnownSourceOverlays() {
        if (!findVideo()) return;
        var playback = document.querySelector('#overlay > #playback');
        var overlay = playback && playback.parentElement;
        if (!overlay || overlay.id !== 'overlay') return;
        overlay.style.setProperty('display', 'none', 'important');
        overlay.style.setProperty('pointer-events', 'none', 'important');
      }

      // Do not infer success from click(): the page may not have installed its handler yet.
      // Retrying is bounded, and an unhandled prompt keeps its own buttons and native input.
      var resumeFrame = Math.random().toString(36).slice(2);
      function resumePromptVisible(node) {
        if (!node) return false;
        var style = window.getComputedStyle(node);
        return style.display !== 'none' && style.visibility !== 'hidden' && node.getClientRects().length > 0;
      }
      function dismissResumePrompts() {
        var prompt = document.getElementById('checkresume_div');
        if (!resumePromptVisible(prompt)) return;
        var decline = prompt.querySelector('#no_thanks');
        if (!decline || prompt.__aniliResumeAttempts >= 5) return;
        var now = Date.now();
        if (now - (prompt.__aniliResumeLastTry || 0) < 1000) return;
        prompt.__aniliResumeLastTry = now;
        prompt.__aniliResumeAttempts = (prompt.__aniliResumeAttempts || 0) + 1;
        try { decline.click(); } catch (e) { /* leave the dialog usable */ }
      }
      var lastResumeVisible = null;
      function reportResumeDialog() {
        var nodes = document.querySelectorAll('#checkresume_div, [role="dialog"], dialog, [id*="resume" i], [class*="resume" i]');
        var visible = false;
        for (var i = 0; i < Math.min(nodes.length, 100); i++) {
          var node = nodes[i];
          if (!resumePromptVisible(node) || node.querySelector('video')) continue;
          if (node.id === 'checkresume_div' || /resume\s+(playing|playback)|continue\s+watching/i.test((node.textContent || '').slice(0, 1000))) {
            visible = true;
            break;
          }
        }
        // A visible prompt is re-reported on every sweep because the app treats that as a
        // heartbeat and expires a frame's claim after two seconds of silence (a frame can vanish
        // without ever saying goodbye). "Not visible" is the steady state of every episode, and
        // was being sent across the bridge onto the main thread several times a second for no
        // change at all, so it goes once, when it stops being visible.
        if (visible || lastResumeVisible !== false) {
          try { AniliProgress.onResumeDialog(resumeFrame, visible); } catch (e) { /* detached */ }
        }
        lastResumeVisible = visible;
      }

      // Ad widgets that float over the video ("watch in HD now" and friends). They are not
      // popunders — they never call window.open, so the main-frame lock and onCreateWindow never
      // see them — and they are often served by the embed host itself, so no request blocklist can
      // reach them either. They also appear minutes into playback, which is why this runs on the
      // same repeating pass rather than once at load.
      //
      // Deliberately narrow, because the alternative is hiding the player: a candidate must float
      // above the video, overlap it, be small relative to it, and either be clickable (the whole
      // point of an ad) or read like a promo. Anything containing the <video> is never touched.
      var promoText = /(watch|stream|play|view)\s+(in\s+)?(hd|4k|full|now)|download\s+(the\s+)?app|install\s+now|click\s+here|jetzt|regarder/i;

      function overlapsAndFitsInside(candidate, host) {
        var overlaps = candidate.left < host.right && candidate.right > host.left &&
          candidate.top < host.bottom && candidate.bottom > host.top;
        if (!overlaps) return false;
        var hostArea = Math.max(1, host.width * host.height);
        return (candidate.width * candidate.height) / hostArea < 0.4;
      }

      function hideOverlayAds() {
        var video = findVideo();
        if (!video) return;
        var videoRect = video.getBoundingClientRect();
        if (videoRect.width < 40 || videoRect.height < 40) return;
        var candidates = document.querySelectorAll('body a[href], body iframe, body > div, body > span, body > ins');
        var limit = Math.min(candidates.length, 400);
        for (var i = 0; i < limit; i++) {
          var node = candidates[i];
          if (!node || node.__aniliOverlayHidden) continue;
          // Never hide the player, or any wrapper the video lives inside.
          if (node === video || (node.querySelector && node.querySelector('video'))) continue;
          var style = null;
          try { style = window.getComputedStyle(node); } catch (e) { continue; }
          if (!style) continue;
          if (style.position !== 'fixed' && style.position !== 'absolute' && style.position !== 'sticky') continue;
          if (style.display === 'none' || style.visibility === 'hidden') continue;
          var rect = node.getBoundingClientRect();
          if (rect.width < 8 || rect.height < 8) continue;
          if (!overlapsAndFitsInside(rect, videoRect)) continue;
          var clickable = node.tagName === 'A' || node.tagName === 'IFRAME' ||
            (node.querySelector && node.querySelector('a[href]'));
          var text = (node.innerText || node.textContent || '').slice(0, 200);
          if (!clickable && !promoText.test(text)) continue;
          node.__aniliOverlayHidden = true;
          node.style.setProperty('display', 'none', 'important');
          node.style.setProperty('pointer-events', 'none', 'important');
        }
      }

      function suppress(root) {
        installStyle();
        suppressNativeControls(root || document);
        try { hideKnownSourceOverlays(); } catch (e) { /* best effort */ }
        try { dismissResumePrompts(); reportResumeDialog(); } catch (e) { /* best effort */ }
        try { hideOverlayAds(); } catch (e) { /* best effort */ }
      }

      function findVideo() {
        var videos = document.querySelectorAll('video');
        var best = null;
        var bestScore = -1;
        for (var i = 0; i < videos.length; i++) {
          var video = videos[i];
          var rect = video.getBoundingClientRect();
          var score = Math.max(0, rect.width) * Math.max(0, rect.height);
          if (!video.paused) score += 1000000000;
          if (isFinite(video.duration) && video.duration > 60) score += 100000000;
          if (score > bestScore) { best = video; bestScore = score; }
        }
        return best;
      }

      function play(video) {
        if (!video) return false;
        var result = video.play();
        if (result && result.catch) result.catch(function() {});
        return true;
      }

      // ---- the page player's own resolution ladder -------------------------------------------
      //
      // An embed's renditions live inside its player, not in the URL the provider handed us: one
      // page plays every quality and the control bar picks between them. This script hides that
      // control bar, so without reading the ladder back out the choice is not moved into the app's
      // sheet, it is destroyed — which is exactly how servers like AnimeDekho and ReAnime came to
      // have no quality control at all.
      //
      // Each engine builder answers a uniform shape: levels in the order they will be shown, the
      // position of the active one (-1 while the player is choosing for itself), and a setter that
      // takes that same position. Positions rather than the engine's own indices, because those
      // are not indices everywhere — Plyr selects by height — and the app should not have to know.
      //
      // Only ladders are handled here. Players whose "quality" menu swaps the source URL
      // (Artplayer, DPlayer) are deliberately left out: reloading a source is what the Video
      // server section already does, and doing it from here would lose the position silently.
      function isHlsJs(value) {
        return !!value && Object.prototype.toString.call(value.levels) === '[object Array]' &&
          typeof value.currentLevel === 'number';
      }

      function hlsJsInstance() {
        var names = ['hls', 'hlsPlayer', 'hlsjs', '_hls', '__hls'];
        for (var i = 0; i < names.length; i++) {
          try { if (isHlsJs(window[names[i]])) return window[names[i]]; } catch (e) { /* guarded */ }
        }
        var video = findVideo();
        if (video) {
          var keys = ['hls', '_hls', '__hls', 'hlsPlayer'];
          for (var j = 0; j < keys.length; j++) {
            try { if (isHlsJs(video[keys[j]])) return video[keys[j]]; } catch (e) { /* guarded */ }
          }
        }
        return null;
      }

      function heightLabel(height, position) {
        return height > 0 ? height + 'p' : 'Level ' + (position + 1);
      }

      // JW Player wraps its own hls.js, so its API leads: writing hls.currentLevel underneath it
      // is undone the next time JW reasserts the quality it believes is selected.
      function jwEngine() {
        if (typeof window.jwplayer !== 'function') return null;
        var player;
        try { player = window.jwplayer(); } catch (e) { return null; }
        if (!player || typeof player.getQualityLevels !== 'function') return null;
        var raw;
        try { raw = player.getQualityLevels() || []; } catch (e) { return null; }
        var levels = [];
        var indices = [];
        var autoIndex = -1;
        for (var i = 0; i < raw.length; i++) {
          var label = String((raw[i] && raw[i].label) || '');
          // JW carries its auto entry in the same list; it is offered separately below.
          if (/^auto$/i.test(label)) { autoIndex = i; continue; }
          var height = Number(raw[i] && raw[i].height) || 0;
          levels.push({ label: label || heightLabel(height, levels.length), height: height });
          indices.push(i);
        }
        if (!levels.length) return null;
        var currentIndex = -1;
        try { currentIndex = player.getCurrentQuality(); } catch (e) { currentIndex = -1; }
        var current = -1;
        for (var k = 0; k < indices.length; k++) if (indices[k] === currentIndex) current = k;
        return {
          name: 'jwplayer',
          levels: levels,
          current: current,
          auto: autoIndex >= 0,
          set: function(position) {
            player.setCurrentQuality(position < 0 ? autoIndex : indices[position]);
            return true;
          }
        };
      }

      function videoJsEngine() {
        if (typeof window.videojs !== 'function' || !window.videojs.getAllPlayers) return null;
        var players;
        try { players = window.videojs.getAllPlayers() || []; } catch (e) { return null; }
        for (var i = 0; i < players.length; i++) {
          var player = players[i];
          if (!player || typeof player.qualityLevels !== 'function') continue;
          var list;
          try { list = player.qualityLevels(); } catch (e) { continue; }
          if (!list || !list.length) continue;
          var levels = [];
          for (var j = 0; j < list.length; j++) {
            var height = Number(list[j] && list[j].height) || 0;
            levels.push({ label: heightLabel(height, j), height: height });
          }
          // The plugin selects by leaving exactly one level enabled, so "everything enabled" is
          // what auto looks like and a single enabled level is what a manual pick looks like.
          var enabled = -1;
          var enabledCount = 0;
          for (var m = 0; m < list.length; m++) {
            if (list[m] && list[m].enabled) { enabledCount++; enabled = m; }
          }
          return {
            name: 'videojs',
            levels: levels,
            current: enabledCount === 1 ? enabled : -1,
            auto: true,
            set: (function(levelList) {
              return function(position) {
                for (var n = 0; n < levelList.length; n++) {
                  levelList[n].enabled = position < 0 || n === position;
                }
                return true;
              };
            })(list)
          };
        }
        return null;
      }

      function plyrEngine() {
        var player = window.player || window.plyr;
        if (!player || !player.elements || typeof player.quality === 'undefined') return null;
        var options = (player.options && player.options.quality) || [];
        if (!options.length) return null;
        var levels = [];
        for (var i = 0; i < options.length; i++) {
          var height = Number(options[i]) || 0;
          levels.push({ label: heightLabel(height, i), height: height });
        }
        var current = -1;
        for (var j = 0; j < options.length; j++) {
          if (Number(options[j]) === Number(player.quality)) current = j;
        }
        return {
          name: 'plyr',
          levels: levels,
          current: current,
          // Plyr selects by height and has no auto value of its own.
          auto: false,
          set: function(position) {
            if (position < 0) return false;
            player.quality = options[position];
            return true;
          }
        };
      }

      function hlsJsEngine() {
        var hls = hlsJsInstance();
        if (!hls || !hls.levels.length) return null;
        var levels = [];
        for (var i = 0; i < hls.levels.length; i++) {
          var height = Number(hls.levels[i] && hls.levels[i].height) || 0;
          levels.push({ label: heightLabel(height, i), height: height });
        }
        return {
          name: 'hlsjs',
          levels: levels,
          current: hls.currentLevel >= 0 && hls.currentLevel < levels.length ? hls.currentLevel : -1,
          auto: true,
          set: function(position) { hls.currentLevel = position < 0 ? -1 : position; return true; }
        };
      }

      function qualityEngine() {
        var builders = [jwEngine, videoJsEngine, plyrEngine, hlsJsEngine];
        for (var i = 0; i < builders.length; i++) {
          var engine = null;
          try { engine = builders[i](); } catch (e) { engine = null; }
          // One rendition is not a choice, and offering it as one implies the others were lost.
          if (engine && engine.levels.length > 1) return engine;
        }
        return null;
      }

      function qualityReport() {
        // Frames without a video are the outer shell and the ad slots. Neither owns a ladder, and
        // letting them answer would have them overwrite the report from the frame that does.
        if (!findVideo()) return '';
        var engine = qualityEngine();
        if (!engine) return '';
        var levels = [];
        for (var i = 0; i < engine.levels.length; i++) {
          levels.push({ id: i, label: engine.levels[i].label, height: engine.levels[i].height });
        }
        return JSON.stringify({
          engine: engine.name,
          auto: !!engine.auto,
          current: engine.current,
          levels: levels
        });
      }

      function setQuality(position) {
        var engine = qualityEngine();
        if (!engine) return false;
        if (position < 0) return engine.auto ? engine.set(-1) : false;
        if (position >= engine.levels.length) return false;
        return engine.set(position);
      }

      function applyCommand(data) {
        if (!data || data.__aniliPlayerCommand !== 'v1') return false;
        var video = findVideo();
        if (!video) return false;
        var value = Number(data.value);
        try {
          switch (data.command) {
            case 'play': return play(video);
            case 'pause': video.pause(); return true;
            case 'toggle': if (video.paused) play(video); else video.pause(); return true;
            case 'seek':
              if (!isFinite(value)) return false;
              video.currentTime = isFinite(video.duration) && video.duration > 0
                ? Math.min(Math.max(0, value), video.duration)
                : Math.max(0, value);
              return true;
            case 'resumeAt':
              if (!isFinite(value)) return false;
              video.currentTime = isFinite(video.duration) && video.duration > 0
                ? Math.min(Math.max(0, value), video.duration)
                : Math.max(0, value);
              return play(video);
            case 'speed':
              if (!isFinite(value) || value <= 0) return false;
              video.defaultPlaybackRate = value;
              video.playbackRate = value;
              return true;
            case 'volume':
              if (!isFinite(value)) return false;
              video.volume = Math.min(1, Math.max(0, value));
              video.muted = video.volume <= 0;
              return true;
            // A position in the ladder last reported by this frame; -1 asks for auto.
            case 'quality':
              if (!isFinite(value)) return false;
              return setQuality(Math.round(value));
          }
        } catch (e) { /* best effort */ }
        return false;
      }

      function forwardCommand(data) {
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { frames[i].contentWindow.postMessage(data, '*'); } catch (e) { /* detached */ }
        }
      }

      // Reports whether this frame itself carried the command out. It used to answer true come what
      // may, which made every fallbackScript in dispatchWebPlayerCommand unreachable — including
      // the iframe-walking one written for the poster pages that need it most. Frames reached by
      // postMessage cannot answer for themselves from here, so a video living in one still reads as
      // unhandled; the fallback then finds nothing it could reach either, and costs one no-op.
      window.__aniliDispatchPlayerCommand = function(command, value) {
        var data = { __aniliPlayerCommand: 'v1', command: command, value: value };
        var handled = applyCommand(data);
        forwardCommand(data);
        return handled;
      };
      window.addEventListener('message', function(event) {
        var data = event.data;
        if (!data || data.__aniliPlayerCommand !== 'v1') return;
        applyCommand(data);
        forwardCommand(data);
      }, true);

      function observe() {
        if (!document.documentElement) {
          setTimeout(observe, 0);
          return;
        }
        suppress(document);
        // The page's own player rewrites its time label several times a second, and every one of
        // those is a mutation. Suppressing native controls stays per-mutation — it is scoped to the
        // added nodes, and a flash of the page's own controls is the thing it exists to prevent —
        // but the page-wide sweeps (a full-document query for resume prompts, then layout reads on
        // each candidate, then a call across the bridge onto the app's main thread) ran on every
        // one of those rewrites, in every frame the script reached. On an old television whose
        // renderer shares the app's process, that was the app's CPU. They now run once per burst.
        var sweepPending = false;
        function scheduleSweep() {
          if (sweepPending) return;
          sweepPending = true;
          setTimeout(function() {
            sweepPending = false;
            try { dismissResumePrompts(); reportResumeDialog(); } catch (e) { /* best effort */ }
            try { hideKnownSourceOverlays(); } catch (e) { /* best effort */ }
          }, 300);
        }
        new MutationObserver(function(mutations) {
          installStyle();
          for (var i = 0; i < mutations.length; i++) {
            var mutation = mutations[i];
            if (mutation.type === 'attributes') suppressNativeControls(mutation.target);
            for (var j = 0; j < mutation.addedNodes.length; j++) {
              suppressNativeControls(mutation.addedNodes[j]);
            }
          }
          scheduleSweep();
        }).observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['controls']
        });
      }

      try { observe(); } catch (e) { /* best effort */ }

      // JW Player's own error card ("This video file cannot be played. (Error Code: 232011)")
      // keeps the <video> element, so nothing else here can tell the player has given up. Its
      // error and setupError events are both terminal (recoverable trouble is a "warning"). The
      // events are hooked on first sight, and the state and the card's class cover an error that
      // fired before the hook. Returns the code and message, or null.
      function jwFailure() {
        if (typeof window.jwplayer === 'function') {
          var player = null;
          try { player = window.jwplayer(); } catch (e) { player = null; }
          if (player && typeof player.on === 'function' && window.__aniliJwHooked !== player) {
            window.__aniliJwHooked = player;
            var remember = function(event) {
              window.__aniliJwError = 'jwplayer ' + String((event && event.code) || '') + ' ' +
                String((event && event.message) || '').slice(0, 160);
            };
            try { player.on('error', remember); player.on('setupError', remember); } catch (e) { /* old JW */ }
          }
          if (window.__aniliJwError) return window.__aniliJwError;
          try {
            if (player && typeof player.getState === 'function' && player.getState() === 'error') {
              return 'jwplayer state=error';
            }
          } catch (e) { /* not set up */ }
        }
        var card = document.querySelector('.jw-state-error');
        if (!card) return null;
        var text = card.querySelector('.jw-error-text, .jw-error-msg');
        return 'jwplayer ' + String((text && text.textContent) || 'state=error')
          .replace(/\s+/g, ' ').trim().slice(0, 160);
      }

      // This bootstrap runs independently in every origin-matched frame. The Android JavaScript
      // bridge is exposed to those frames too, so a deeply nested cross-origin video can activate
      // the same native controls and progress UI as a top-level player.
      if (!window.__aniliProgressHooked) {
        window.__aniliProgressHooked = true;
        window.__aniliQualityReport = '';
        setInterval(function() {
          try {
            var video = findVideo();
            if (video && !window.__aniliVideoReported) {
              window.__aniliVideoReported = true;
              AniliProgress.onVideoAvailable();
            }
            if (video && isFinite(video.duration) && video.duration > 0 && video.currentTime >= 0) {
              AniliProgress.onTick(video.currentTime, video.duration, !video.paused, video.muted, video.volume);
            }
          } catch (e) { /* bridge detached or not available on an old WebView */ }
          // Polled rather than pushed: the ladder appears when the manifest is parsed, which is
          // after the video element exists, and hls.js quality events are not exposed uniformly
          // across the engines above. Only sent on a change, so a steady episode is silent.
          try {
            var report = qualityReport();
            if (report !== window.__aniliQualityReport) {
              window.__aniliQualityReport = report;
              AniliProgress.onQualityLevels(report);
            }
          } catch (e) { /* no player API in this frame */ }
          try {
            if (!window.__aniliPlayerErrorReported) {
              var failure = jwFailure();
              if (failure) {
                window.__aniliPlayerErrorReported = true;
                AniliProgress.onPlayerError(failure);
              }
            }
          } catch (e) { /* bridge detached */ }
          // Floating ads are injected minutes in, long after load and often without a DOM
          // mutation we observe, so the sweep has to keep running for the whole episode. The ad
          // pass reads computed style and layout on up to 400 elements, so it runs every third
          // tick: an ad that sits over the picture for two seconds costs less than the pass did.
          try { dismissResumePrompts(); reportResumeDialog(); } catch (e) { /* best effort */ }
          try { hideKnownSourceOverlays(); } catch (e) { /* best effort */ }
          window.__aniliSweepTick = (window.__aniliSweepTick || 0) + 1;
          if (window.__aniliSweepTick % 3 === 0) {
            try { hideOverlayAds(); } catch (e) { /* best effort */ }
          }
        }, 1000);
      }
    })();
""".trimIndent()

internal val PROVIDER_CHROME_ORIGIN_RULES = setOf("*")

/**
 * Main-frame probe for providers whose page variant defeats the document-start bridge (kwik
 * serves such a variant intermittently: the video never reports through AniliProgress, which
 * used to demote the player to the reduced fallback bar — no double-tap seek, no skip intro,
 * and a play/pause button that only guessed its state). Returns "pos,dur,playing,muted,volume"
 * or "none"; same-origin iframes are walked, cross-origin ones stay the bridge's job.
 */
private val VIDEO_PROBE_JS = """
    (function() {
      function find(root) {
        var video = root.querySelector('video');
        if (video) return video;
        var frames = root.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            if (frames[i].contentDocument) {
              var nested = find(frames[i].contentDocument);
              if (nested) return nested;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      try {
        var v = find(document);
        if (!v) return 'none';
        return [
          v.currentTime || 0,
          isFinite(v.duration) ? v.duration : 0,
          v.paused ? 0 : 1,
          v.muted ? 1 : 0,
          v.volume
        ].join(',');
      } catch (e) { return 'none'; }
    })();
""".trimIndent()

private fun installWebPlayerBootstrap(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        DiagnosticsLog.event("EmbedWebView document-start injection unavailable; using main-frame fallback")
        return
    }
    runCatching {
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            HIDE_PROVIDER_CHROME_JS,
            PROVIDER_CHROME_ORIGIN_RULES,
        )
    }.onSuccess {
        DiagnosticsLog.event("EmbedWebView native-control bootstrap installed for all frames")
    }.onFailure {
        DiagnosticsLog.throwable("EmbedWebView provider chrome suppression install failed", it)
    }
}

private val REMOTE_TOGGLE_PLAYBACK_JS = """
    (function() {
      function toggle(root) {
        var selectors = [
          '[data-plyr="play"]', '.plyr__control--overlaid', '.vjs-big-play-button',
          '.jw-icon-playback', 'button[aria-label*="Play" i]',
          'button[aria-label*="Pause" i]', '.play-button'
        ];
        for (var i = 0; i < selectors.length; i++) {
          var button = root.querySelector(selectors[i]);
          if (button) { button.click(); return true; }
        }
        var video = root.querySelector('video');
        if (video) {
          if (video.paused) video.play(); else video.pause();
          return true;
        }
        return false;
      }
      try {
        if (toggle(document)) return true;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            if (frames[i].contentDocument && toggle(frames[i].contentDocument)) return true;
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
      return false;
    })();
""".trimIndent()

private fun RESUME_WHEN_READY_JS(targetSec: Double): String = """
    (function() {
      var attempts = 0;
      var timer = setInterval(function() {
        attempts++;
        try {
          var video = document.querySelector('video');
          if (video && video.readyState >= 1) {
            var target = $targetSec;
            video.currentTime = isFinite(video.duration) && video.duration > 0
              ? Math.min(target, video.duration)
              : target;
            video.play();
            clearInterval(timer);
          } else if (attempts >= 30) {
            clearInterval(timer);
          }
        } catch (e) {
          if (attempts >= 30) clearInterval(timer);
        }
      }, 250);
    })();
""".trimIndent()

private fun seekWebVideo(webView: WebView?, targetMs: Long?) {
    val targetSec = targetMs?.div(1000.0) ?: return
    webView?.let { dispatchWebPlayerCommand(it, "seek", targetSec, SEEK_VIDEO_JS(targetSec)) }
}

private fun setWebVolume(webView: WebView?, volume: Float, onChanged: (Float) -> Unit) {
    val target = volume.coerceIn(0f, 1f)
    webView?.let {
        dispatchWebPlayerCommand(it, "volume", target.toDouble(), WEB_VOLUME_JS(delta = null, absolute = target))
    }
    onChanged(target)
}

internal fun webPlayerCommandJs(command: String, value: Double? = null): String {
    require(command.matches(Regex.cached("[A-Za-z]+"))) { "Invalid web player command" }
    val jsValue = value?.takeIf(Double::isFinite)?.toString() ?: "null"
    return "(function(){try{return window.__aniliDispatchPlayerCommand" +
        "?window.__aniliDispatchPlayerCommand('$command',$jsValue):false;}catch(e){return false;}})();"
}

private fun dispatchWebPlayerCommand(
    webView: WebView,
    command: String,
    value: Double? = null,
    fallbackScript: String? = null,
) {
    runCatching {
        webView.evaluateJavascript(webPlayerCommandJs(command, value)) { handled ->
            if (handled != "true" && fallbackScript != null) {
                runCatching { webView.evaluateJavascript(fallbackScript, null) }
            }
        }
    }
}

private fun WEB_VOLUME_JS(delta: Float?, absolute: Float?): String {
    val targetExpression = absolute?.coerceIn(0f, 1f)?.toString()
        ?: "Math.max(0, Math.min(1, v.volume + ${delta ?: 0f}))"
    return """
        (function() {
          function findVideo() {
            var v = document.querySelector('video');
            if (v) return v;
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try {
                var d = frames[i].contentDocument;
                if (d) {
                  var fv = d.querySelector('video');
                  if (fv) return fv;
                }
              } catch (e) { /* cross-origin */ }
            }
            return null;
          }
          try {
            var v = findVideo();
            if (!v) return -1;
            v.volume = $targetExpression;
            v.muted = v.volume <= 0;
            return v.muted ? 0 : v.volume;
          } catch (e) {
            return -1;
          }
        })();
    """.trimIndent()
}

private fun SEEK_VIDEO_JS(targetSec: Double): String = """
    (function() {
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      try {
        var v = findVideo();
        if (!v) return false;
        var target = $targetSec;
        v.currentTime = isFinite(v.duration) && v.duration > 0 ? Math.min(target, v.duration) : target;
        if (v.paused) v.play();
        return true;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private fun readDeviceVolume(audioManager: android.media.AudioManager?): Float {
    audioManager ?: return 1f
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return 1f
    return audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyDeviceVolume(audioManager: android.media.AudioManager?, value: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    audioManager.setStreamVolume(
        android.media.AudioManager.STREAM_MUSIC,
        (value * max).toInt().coerceIn(0, max),
        0,
    )
}

private fun stopWebPlayback(webView: WebView) {
    DiagnosticsLog.event("EmbedWebView stop playback url=${webView.url ?: "none"}")
    dispatchWebPlayerCommand(webView, "pause", fallbackScript = PAUSE_VIDEO_JS)
    webView.onPause()
    // No pauseTimers() here. It is process-wide: it stopped JavaScript timers in every WebView the
    // app owns — the Pahe, Flixcloud and Hanime resolvers, both challenge dialogs and the login
    // page — and the only resumeTimers() lived in a mounted embed's lifecycle observer, so after
    // backing out of an embed episode those stalled until the next embed opened (Pahe's clearance
    // timed out into its cooldown, Flixcloud hit its TV timeout). onPause(), about:blank and the
    // destroy() that follows already stop this page; the global pair belongs to the lifecycle
    // observer alone, where the app going to the background is the reason for it.
    webView.stopLoading()
    webView.loadUrl("about:blank")
}

private val PAUSE_VIDEO_JS = """
    (function() {
      function pauseVideos(root) {
        var videos = root.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
          videos[i].pause();
        }
      }
      try {
        pauseVideos(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) pauseVideos(d);
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

private fun android.app.Activity?.inPictureInPicture(): Boolean =
    this != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}

private fun String?.hostOrNone(): String =
    this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"

private class WebProgressBridge(
    private val onTickCallback: (Double, Double, Boolean, Boolean, Double) -> Unit,
    private val onVideoAvailableCallback: () -> Unit,
    private val onQualityLevelsCallback: (String) -> Unit,
    private val onResumeDialogCallback: (String, Boolean) -> Unit,
    private val onPlayerErrorCallback: (String) -> Unit,
) {
    /** The page's own player gave up (JW's error card); [message] is its code and words. */
    @JavascriptInterface
    fun onPlayerError(message: String) {
        onPlayerErrorCallback(message)
    }

    @JavascriptInterface
    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean, muted: Boolean, volume: Double) {
        onTickCallback(positionSec, durationSec, isPlaying, muted, volume)
    }

    @JavascriptInterface
    fun onResumeDialog(frame: String, visible: Boolean) {
        onResumeDialogCallback(frame, visible)
    }

    @JavascriptInterface
    fun onVideoAvailable() {
        onVideoAvailableCallback()
    }

    /** The page player's resolution ladder as JSON, or an empty string once it has none. */
    @JavascriptInterface
    fun onQualityLevels(report: String) {
        onQualityLevelsCallback(report)
    }
}

/** One rendition offered by the embed page's own player. */
internal data class EmbedPageQualityLevel(
    /** Position in [EmbedPageQuality.levels]; the value the `quality` command takes. */
    val id: Int,
    val label: String,
    val height: Int?,
)

/** A resolution ladder read out of the embed page's own player. */
internal data class EmbedPageQuality(
    val engine: String,
    /** Whether the player accepts being handed the choice back. */
    val autoSupported: Boolean,
    /** Position of the active rendition, or -1 while the player is choosing for itself. */
    val current: Int,
    val levels: List<EmbedPageQualityLevel>,
)

@Serializable
private class EmbedQualityLevelPayload(
    val id: Int = -1,
    val label: String = "",
    val height: Int = 0,
)

@Serializable
private class EmbedQualityPayload(
    val engine: String = "",
    val auto: Boolean = false,
    val current: Int = -1,
    val levels: List<EmbedQualityLevelPayload> = emptyList(),
)

private val embedQualityJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Reads the ladder a frame reported.
 *
 * Everything here arrives over a JavaScript bridge that [android.webkit.WebView] exposes to every
 * frame, ad slots included, so nothing in the payload is trusted: the list is capped, labels are
 * trimmed to a length a row can show, and an id that is not its own position is dropped rather
 * than sent back to a player that would read it as some other rendition. A ladder of one is no
 * choice and reads as none at all.
 */
internal fun parseEmbedPageQuality(report: String?): EmbedPageQuality? {
    val payload = report?.takeIf { it.isNotBlank() }
        ?.let { runCatching { embedQualityJson.decodeFromString<EmbedQualityPayload>(it) }.getOrNull() }
        ?: return null
    val levels = payload.levels.take(EMBED_QUALITY_LEVEL_LIMIT).mapIndexedNotNull { position, level ->
        if (level.id != position) return@mapIndexedNotNull null
        val label = level.label.trim().take(EMBED_QUALITY_LABEL_LIMIT)
            .ifBlank { level.height.takeIf { it > 0 }?.let { "${it}p" } }
            ?: return@mapIndexedNotNull null
        EmbedPageQualityLevel(position, label, level.height.takeIf { it > 0 })
    }
    if (levels.size < 2) return null
    return EmbedPageQuality(
        engine = payload.engine.trim().take(EMBED_QUALITY_LABEL_LIMIT),
        autoSupported = payload.auto,
        current = payload.current.takeIf { current -> levels.any { it.id == current } } ?: -1,
        levels = levels,
    )
}

private const val EMBED_QUALITY_LEVEL_LIMIT = 12
private const val EMBED_QUALITY_LABEL_LIMIT = 24

/**
 * Names each mirror in the Video server section.
 *
 * [StreamItem.label] is not enough on its own. Providers that name their mirrors give something
 * readable ("AbyssPlayer", "HD-2 embed"), but plenty leave `quality` null, and the label then falls
 * back to "auto" — which produced a list of four rows all reading "auto", telling a viewer nothing
 * about what they were choosing between. The host is what actually distinguishes them, so it stands
 * in, and a repeat is numbered rather than shown twice under one name.
 */
internal fun embedServerLabels(streams: List<StreamItem>): List<Pair<StreamItem, String>> {
    val used = mutableMapOf<String, Int>()
    return streams.mapIndexed { position, stream ->
        val base = stream.quality?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", true) }
            ?: stream.url.embedHostLabel()
            ?: "Server ${position + 1}"
        val seen = used.merge(base, 1, Int::plus) ?: 1
        stream to if (seen == 1) base else "$base $seen"
    }
}

/**
 * "https://vidtube.site/e/abc" → "Vidtube". Null when the URL carries no usable host.
 *
 * [java.net.URI] rather than [Uri] so the labelling is testable off-device; the URLs reaching here
 * are absolute provider links, not the relative forms Android's parser is more forgiving about.
 */
private fun String.embedHostLabel(): String? = runCatching { URI(this).host }
    .getOrNull()
    ?.removePrefix("www.")
    ?.substringBefore('.')
    // Load-balanced mirrors arrive as vault-99, cdn2 and the like; the digits are not a name.
    ?.trimEnd('-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    ?.takeIf { it.isNotBlank() }
    ?.replaceFirstChar { it.uppercase() }

/** Asks the page player for a rung of its own ladder; -1 hands the choice back to it. */
private fun dispatchPageQuality(webView: WebView?, level: Int) {
    val web = webView ?: return
    DiagnosticsLog.event("EmbedWebView page quality request level=$level")
    dispatchWebPlayerCommand(web, "quality", level.toDouble())
}

/**
 * What the Quality section says when it has nothing to offer.
 *
 * Null while there is a ladder, because the rows are then the answer. Otherwise it is deliberately
 * not silent: the section used to disappear whole, and a viewer reading a sheet that jumps from
 * Playback speed to Audio has no way to tell a server with one encode apart from a bug.
 */
internal fun embedQualityMessage(hasLadder: Boolean, hasOtherServers: Boolean): String? = when {
    hasLadder -> null
    hasOtherServers -> "This server chooses the video quality itself. Another one below may let you pick."
    else -> "This server chooses the video quality itself."
}

/**
 * One caption line, for the top position's offset to discount.
 *
 * `em` on the cue container, not a percentage of the picture: Blink sizes that container's font
 * from the video's own height, so the allowance tracks the caption it is standing in for at any
 * player size. Measured at 31px against a 630px-tall video, which is the 1.5 line-height a caption
 * line is laid out with.
 */
private const val NATIVE_CUE_LINE_ALLOWANCE = "1.5em"

/**
 * Stylesheet the embed players get for their captions, wrapped in the script that installs it.
 *
 * The CSS goes through [asJsStringLiteral] rather than straight into the template: its attribute
 * selectors contain double quotes, which silently turned the whole script into a syntax error —
 * and `evaluateJavascript` swallows that, so the captions just quietly kept their own styling.
 *
 * Each surface gets exactly one offset mechanism, and it has to be one that surface honours.
 * `::cue` styles a cue but cannot place it: the WebVTT allow-list has no `transform`, so the
 * `translateY` this used to lean on was dropped on the floor by Blink and the bottom margin did
 * nothing at all wherever an embed left the captions to the browser. Measured over CDP against
 * Chrome 140: with the rule and without it the cue box came back at the same `top=417.94
 * bottom=445.94`, while a `font-size` in the same rule grew it from 28px to 82px — so the rule
 * matched and only the transform was discarded.
 *
 * The box the browser lays cues out in is a real element and does move — but it is
 * `-webkit-media-text-track-container`, not `-webkit-media-text-track-display`. Blink builds a
 * *display box per cue* and places each one from the WebVTT layout, and that layout is the only
 * thing keeping two cues that overlap in time apart: Blink has no other collision pass. Naming
 * the display box put the same `!important` edge on every cue alike, so simultaneous lines were
 * painted through one another — reported from a MegaPlay episode as subtitles "placed one on top
 * of the other", and that is the shape to recognise it by, two readable lines sharing one row
 * rather than one garbled one. Measured with two cues live at once over CDP against Chrome 154,
 * a 630px-tall video and an 8% margin: unstyled the two boxes sit at `top=584` and `top=627`,
 * with the old rule both come back at `top=580`, and with the container they are back to
 * `top=534`/`top=577` — still 43px apart, and the pair moved by the 50px the margin asked for.
 *
 * This is why the margin is the same property for both edges. The container's cues stay anchored
 * to its bottom, so a `top` on it slides the whole block *down* and off the picture (measured:
 * `top: 20%` on the same clip puts the lines at `top=710`/`753`, below a video ending at 670).
 * The top position is therefore the same offset taken from the other edge, less a line so the
 * first line lands on the margin instead of one line above it: at 20%, `bottom: 80%` alone
 * measured 13.2% down the picture, and less [NATIVE_CUE_LINE_ALLOWANCE] it measures 20.6%
 * against the 20% the old per-cue rule placed it at.
 *
 * The offset across the picture moved with it, and had to change shape on the way: a margin on
 * the container is laid out around it and moves nothing inside, so that one is a translation.
 * Only the browser's own captions moved seam. The player-drawn layers below are containers whose
 * lines are ordinary children that stack on their own, so they keep the margin pair they had.
 *
 * The player-drawn containers take `bottom`, but only the box the line is painted in — never the
 * full-size layer the player hangs it from. JW Player, which is what MegaPlay (AniKoto) runs, has
 * both: `.jw-captions` covers the whole picture, and `.jw-text-track-display` inside it is what
 * carries the line, already sitting 55px up. Moving the layer moved the line *as well as* the
 * offset JW had already given it, and `.jw-text-track-cue` — relatively positioned, so `bottom`
 * shifts rather than places it — added a third. Measured against MegaPlay's own JW 8.33.2 bundle
 * with a local file and a one-line VTT, at 1280x720: asking for 8% put the line 19.4% up, and 0%
 * still left it at 10.8%. Placing the display box instead gives 10.7% for 8% and 3.1% for 0% —
 * the residual is the line box's own padding — so the setting now replaces the player's offset
 * instead of stacking on top of it.
 *
 * The catch-all attribute selectors are held off anything that contains the `<video>` itself. A
 * class list is not a statement about what an element is for: ArtPlayer — which is what ReAnime's
 * embed runs — writes its subtitle *state* onto the player root, `art-video-player
 * art-subtitle-show …`, and that root is `position: relative` and holds the picture. So
 * `[class*="subtitle"]` matched it, `bottom` on a relatively positioned box moves it rather than
 * placing it, and the whole episode slid up the screen by the caption margin — by a further ten
 * percent of the height whenever the controls came up, which is how it was reported: the picture
 * jumps upward and loses its top edge on every tap. A caption layer never contains the video, so
 * that is where the line goes. `:has()` is Chrome 105 and later, and the guarded selectors are
 * kept in a rule of their own so a WebView too old to parse it drops only the guess and still
 * places the captions of every player we can name.
 *
 * The guesses are held off a box that holds a caption box too, for the same reason and by the same
 * shape of guard. `.jw-captions` is JW's full-picture layer and contains the display box named
 * above, and it also contains the word "caption" — and `[class*="caption"]:not(:has(video))` is a
 * more specific selector than `.jw-text-track-display`, so both moved and the margin was applied
 * twice again: 20% measured 42.1% with only the video guard, and 22.1% with this one.
 */
internal fun applyCaptionStyleJs(style: CaptionStyle, controlsVisible: Boolean = false): String {
    val margin = if (controlsVisible) {
        (style.edgeMarginPercent + 10).coerceAtMost(CaptionStyle.MAX_EDGE_MARGIN_PERCENT)
    } else {
        style.edgeMarginPercent
    }
    val bg = style.backgroundCssRgba()
    val textColor = style.textCssRgba()
    val weight = if (style.boldText) "bold" else "normal"
    val family = style.font.cssFamily?.let { "font-family: $it !important;" }.orEmpty()
    val size = style.textScalePercent
    val top = style.position == CaptionPosition.TOP
    val vertical = if (top) {
        "top: ${margin}% !important; bottom: auto !important;"
    } else {
        "bottom: ${margin}% !important; top: auto !important;"
    }
    // A margin pair rather than `left` or a `transform`: these boxes are laid out full width with
    // the line centred inside, and an offsetting pair slides the whole box across the picture
    // without changing how wide it is — so the line stays centred in it and wraps exactly where it
    // did. Setting `left` instead would either over-constrain the box (with the player's own
    // `right` still in force) or shrink it to fit, either of which rewraps every caption. Emitted
    // only when the captions have actually been moved, so a centred setup keeps whatever placement
    // the embed's own player already chose.
    val across = style.horizontalAnchorPercent - CaptionStyle.DEFAULT_HORIZONTAL_ANCHOR_PERCENT
    val sideways = if (style.horizontallyCentred) {
        ""
    } else {
        " margin-left: $across% !important; margin-right: ${-across}% !important;"
    }
    val placement = vertical + sideways
    // Blink builds one `-webkit-media-text-track-display` *per cue* and places each from the
    // WebVTT layout, which is the step that keeps two cues that are live at the same moment off
    // each other. Forcing one edge onto that box overrules the layout for every cue alike, so
    // simultaneous lines land on the same pixel and are painted through one another. The
    // container holds them all and takes the offset without touching their order, so placement
    // goes there and the cues keep whatever spacing the layout gave them.
    val nativeVertical = if (top) {
        // The container's cues stay anchored to its bottom edge, so the top position is the same
        // offset measured from the other side; the line allowance puts the first line where the
        // margin asks for it rather than one line higher.
        "bottom: calc(${100 - margin}% - ${NATIVE_CUE_LINE_ALLOWANCE}) !important; top: auto !important;"
    } else {
        "bottom: ${margin}% !important; top: auto !important;"
    }
    // A transform rather than the margin pair the player-drawn layers get: a margin on the
    // container is laid out around it and moves nothing inside, and on the per-cue box it was
    // enough to flatten the cues together on its own — a 25% anchor with no vertical rule at all
    // still put both boxes at `top=671`. A translation moves the painted result and leaves the
    // layout, so the box keeps its width and the lines wrap and stack exactly as they did.
    val nativeSideways = if (style.horizontallyCentred) {
        ""
    } else {
        " transform: translateX($across%) !important;"
    }
    val css = """
        video::cue, ::cue {
            background-color: $bg !important;
            color: $textColor !important;
            font-size: $size% !important;
            font-weight: $weight !important;
            $family
        }
        video::-webkit-media-text-track-container {
            ${nativeVertical + nativeSideways}
        }
        .jw-text-track-display, .vjs-text-track-display, .art-subtitle, .dplayer-subtitle, .shaka-text-container {
            $placement
        }
        [class*="subtitle"]:not(:has(video)):not(:has([class*="track-display"])),
        [class*="caption"]:not(:has(video)):not(:has([class*="track-display"])) {
            $placement
        }
    """.trimIndent().replace("\n", " ")
    return """
        (function() {
          var id = 'anili-caption-styles';
          var parent = document.head || document.documentElement;
          if (!parent) return;
          var style = document.getElementById(id);
          if (!style) {
            style = document.createElement('style');
            style.id = id;
            parent.appendChild(style);
          }
          style.textContent = ${css.asJsStringLiteral()};
        })();
    """.trimIndent()
}

/**
 * Turns an embed's own captions off, and back on.
 *
 * Three things at once, because no one of them is enough on its own. The player's own switch is
 * the only one that stops a player *redrawing* the captions from its own state — JW Player, which
 * is what MegaPlay (AniKoto) runs, offers exactly `Off` and the track as `getCaptionsList()`, and
 * index 0 is Off. The `<video>`'s own text tracks cover the players that hand the browser a
 * `<track>` and let it paint. The stylesheet covers whatever neither reached: a player we cannot
 * name, drawing into a layer we can only guess at.
 *
 * Reversible on purpose: the track that was showing is remembered on the track itself and on the
 * page, so turning captions back on restores the one the server had chosen rather than picking a
 * language for the viewer.
 *
 * "On" never overrides a choice the page has already made, which is what the remembered index and
 * the `showing` check below are for. This runs on every finished page load and not only after a
 * toggle, so a fresh page has nothing remembered — and forcing the *first* track there picks a
 * language for the viewer after all. MegaPlay (AniKoto) publishes eight tracks on a back-catalogue
 * episode, ordered Arabic, English, French, German, Italian, Portuguese, Spanish, Spanish, with
 * English carrying `default: true` (Tsuki ga Kirei episode 6, verified 2026-09-20). JW honours that
 * flag and opens on English; the old unconditional `setCurrentCaptions(1)` then moved it to Arabic.
 * A current-season episode publishes one track, so the same override was invisible there — which is
 * why this read as "old episodes are in the wrong language".
 *
 * Choosing only happens when the page is showing nothing at all — captions the server left off, and
 * a viewer asking for them — where the first track is still the best available answer.
 */
internal fun setEmbedCaptionsJs(enabled: Boolean): String {
    // `display` is not in the WebVTT allow-list for `::cue`, but `visibility` is.
    val hideCss = """
        ::cue { visibility: hidden !important; }
        video::-webkit-media-text-track-display { display: none !important; }
        .jw-captions, .jw-text-track-display, .vjs-text-track-display, .art-subtitle,
        .dplayer-subtitle, .shaka-text-container, .plyr__captions, .plyr__caption {
            display: none !important;
        }
        [class*="subtitle"]:not(:has(video)), [class*="caption"]:not(:has(video)) {
            display: none !important;
        }
    """.trimIndent().replace("\n", " ")
    return """
        (function() {
          var on = $enabled;
          var id = '$CAPTIONS_OFF_ELEMENT_ID';
          function apply(doc, win) {
            if (!doc) return;
            var el = doc.getElementById(id);
            if (on) {
              if (el && el.parentNode) el.parentNode.removeChild(el);
            } else {
              if (!el) {
                el = doc.createElement('style');
                el.id = id;
                (doc.head || doc.documentElement).appendChild(el);
              }
              el.textContent = ${hideCss.asJsStringLiteral()};
            }
            try {
              var jw = win && win.jwplayer ? win.jwplayer() : null;
              if (jw && jw.getCurrentCaptions) {
                if (on) {
                  if (win.__aniliCaptionIndex) {
                    jw.setCurrentCaptions(win.__aniliCaptionIndex);
                  } else if (jw.getCurrentCaptions() === 0) {
                    jw.setCurrentCaptions(1);
                  }
                } else {
                  var current = jw.getCurrentCaptions();
                  if (current > 0) win.__aniliCaptionIndex = current;
                  jw.setCurrentCaptions(0);
                }
              }
            } catch (e) { /* not a JW page, or it has not set up yet */ }
            var videos = doc.getElementsByTagName('video');
            for (var i = 0; i < videos.length; i++) {
              var tracks = videos[i].textTracks;
              var restored = false;
              var showing = false;
              var first = null;
              for (var j = 0; j < tracks.length; j++) {
                var t = tracks[j];
                if (t.kind !== 'captions' && t.kind !== 'subtitles') continue;
                if (first === null) first = t;
                if (on) {
                  if (t.mode === 'showing') showing = true;
                  if (t.__aniliWasShowing) { t.mode = 'showing'; restored = true; }
                } else {
                  t.__aniliWasShowing = t.mode === 'showing';
                  if (t.mode !== 'disabled') t.mode = 'disabled';
                }
              }
              if (on && !restored && !showing && first) first.mode = 'showing';
            }
          }
          try {
            apply(document, window);
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try { apply(frames[i].contentDocument, frames[i].contentWindow); } catch (e) { /* cross-origin */ }
            }
          } catch (e) { /* ignored */ }
        })();
    """.trimIndent()
}

private const val CAPTIONS_OFF_ELEMENT_ID = "anili-captions-off"

/** Quotes [this] as a JavaScript string literal, escaping everything that could end it early. */
internal fun String.asJsStringLiteral(): String = buildString(length + 2) {
    append('"')
    this@asJsStringLiteral.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            // Keeps the payload inert if it is ever injected into an inline <script> block.
            '<' -> append("\\u003C")
            else -> append(ch)
        }
    }
    append('"')
}


/**
 * Everything the embed player resets when it is handed a different stream, remembered once.
 *
 * These were twenty-nine separate `remember(url) { mutableStateOf(...) }` calls, and each one is a
 * group of slot-table code inlined into [EmbedWebView] itself — they were most of what kept that
 * method over ART's 10 000-unit JIT ceiling after its layout had been split out (see
 * [com.anilili.ui.components.CompiledSeparately]). As fields of one object they cost one group
 * between them, and nothing that uses them changes: each local is still a delegate, now to a field
 * here instead of to a state of its own, so `positionMs = target` still writes the same state.
 *
 * Only states keyed on `url` alone, and whose initial value needs nothing but the stream and where
 * it starts, belong here. Anything keyed on more than that keeps its own `remember`, because moving
 * it would change when it resets.
 */
@Stable
private class EmbedStreamState(url: String, startPositionMs: Long) {
    val activeUrl = mutableStateOf(url)
    val captionAppearanceVisible = mutableStateOf(false)
    val captionsSheetVisible = mutableStateOf(false)
    val settingsSheetVisible = mutableStateOf(false)
    val playbackSpeed = mutableFloatStateOf(1f)
    val preHoldSpeed = mutableFloatStateOf(1f)
    val positionMs = mutableLongStateOf(startPositionMs)
    val durationMs = mutableLongStateOf(0L)
    val webIsPlaying = mutableStateOf(false)
    val resumeDialogVisible = mutableStateOf(false)
    val resumeDialogFrames = mutableMapOf<String, Long>()
    val autoplayHeldBack = mutableStateOf(false)
    val webVolume = mutableFloatStateOf(1f)
    val lastAudibleVolume = mutableFloatStateOf(1f)
    val tvControlsVisible = mutableStateOf(false)
    val tvControlsInteraction = mutableIntStateOf(0)
    val episodeDrawerExpanded = mutableStateOf(false)
    val touchControlsVisible = mutableStateOf(true)
    val touchControlsInteraction = mutableIntStateOf(0)
    val playbackGestureIsPlaying = mutableStateOf<Boolean?>(null)
    val fallbackControlsVisible = mutableStateOf(true)
    val fallbackInteraction = mutableIntStateOf(0)
    val fallbackIsPlaying = mutableStateOf(true)
    val tvControlsFocusRestoreRequest = mutableIntStateOf(0)
    val dpadSeekPresses = mutableIntStateOf(0)
    val dpadSeekLastPressMs = mutableLongStateOf(0L)
    val dpadSeekForwardDirection = mutableStateOf(true)
    val dpadSeekTick = mutableIntStateOf(0)
    val dpadSeekVisible = mutableStateOf(false)
    /** The page player's own subtitle tracks, by label; JW index = position + 1 (0 is its Off). */
    val embedCaptionTracks = mutableStateOf<List<String>>(emptyList())
    val embedCaptionIndex = mutableIntStateOf(-1)
}

/**
 * The embed subtitle language the viewer picked, for the rest of the session.
 *
 * Not per stream: the next episode is a new page with a new list, and a viewer who chose English
 * over the server's Arabic default did not mean "for this episode". Matched by label, since that is
 * all two pages' lists have in common.
 */
private object EmbedCaptionChoice {
    @Volatile var label: String? = null
}

/** How many one-second tries a page gets to publish its caption list before the sheet gives up. */
private const val EMBED_CAPTION_POLLS = 15

/** What [READ_EMBED_CAPTIONS_JS] found: JW's current index and its tracks after Off. */
internal data class EmbedCaptionList(val current: Int, val labels: List<String>)

/**
 * JW Player's caption list — what MegaPlay (AniKoto, and Bee once its own host refuses) runs.
 * Top frame only: that is where MegaPlay mounts it, and a cross-origin frame could not answer.
 */
private const val READ_EMBED_CAPTIONS_JS = """
(function() {
  try {
    var jw = window.jwplayer ? window.jwplayer() : null;
    if (!jw || !jw.getCaptionsList) return null;
    var list = jw.getCaptionsList() || [];
    if (list.length < 2) return null;
    return JSON.stringify({ current: jw.getCurrentCaptions(), labels: list.slice(1).map(function(c) { return String(c.label || c.language || ''); }) });
  } catch (e) { return null; }
})();
"""

/** Parses [READ_EMBED_CAPTIONS_JS]'s answer, which `evaluateJavascript` hands back JSON-quoted. */
internal fun parseEmbedCaptionList(raw: String?): EmbedCaptionList? = runCatching {
    if (raw == null || raw == "null") return null
    val json = org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
    val labels = json.getJSONArray("labels")
    EmbedCaptionList(
        current = json.getInt("current"),
        labels = List(labels.length()) { labels.getString(it) },
    )
}.getOrNull()

/** Selects JW track [index], and remembers it where [setEmbedCaptionsJs] restores from. */
private fun selectEmbedCaptionJs(index: Int): String = """
    (function() {
      try {
        window.__aniliCaptionIndex = $index;
        var jw = window.jwplayer ? window.jwplayer() : null;
        if (jw && jw.setCurrentCaptions) jw.setCurrentCaptions($index);
      } catch (e) { /* the page went away */ }
    })();
""".trimIndent()

private suspend fun WebView.readEmbedCaptions(): EmbedCaptionList? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        evaluateJavascript(READ_EMBED_CAPTIONS_JS) { raw ->
            if (cont.isActive) cont.resumeWith(Result.success(parseEmbedCaptionList(raw)))
        }
    }
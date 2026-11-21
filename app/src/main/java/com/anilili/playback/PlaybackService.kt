package com.anilili.playback

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.MainActivity
import com.anilili.data.device.isTelevision
import com.anilili.data.remote.AppDns
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.nav.Routes
import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Process-wide playback state used by the activity to decide when PiP is appropriate. */
object PlaybackStatus {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    internal fun update(playing: Boolean) {
        _isPlaying.value = playing
    }
}

/**
 * Whether a stall recovery should read from the network instead of the bounded playback cache.
 *
 * The download cache still sits in front of this decision, so a saved episode remains playable
 * offline. This gate only stops a same-stream retry from receiving the same cached HLS playlists
 * and partial segment bytes that led it back to the dead CDN edge the first time.
 */
internal class PlaybackCacheBypassGate {
    private val active = AtomicBoolean(false)

    fun begin(): Boolean = active.compareAndSet(false, true)

    fun end(): Boolean = active.getAndSet(false)

    fun isActive(): Boolean = active.get()
}

/**
 * Owns the app playback player so playback survives activity backgrounding and exposes Android
 * notification, lock-screen, headset, Bluetooth, and Cast controls through a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var castRoute: CastRoute? = null
    private lateinit var session: MediaSession
    private val transportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sleepTimerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transportLock = Any()
    private var transportDestroyed = false
    private var shutdownCronet: (() -> Unit)? = null
    private lateinit var httpFactory: SwappableHttpDataSourceFactory

    /**
     * The queue as the local player held it, kept for the length of a Cast session.
     *
     * Coming back from a receiver reads the queue off the *cast* player, which is holding the
     * proxied addresses it was handed. Restoring the snapshot is exact; [CastStreamRouting] can
     * only undo the stream URL, so that stays the fallback for a session this service did not see
     * begin.
     */
    private var localRouteItems: List<MediaItem>? = null

    /**
     * Where the local player was when the queue left for a receiver.
     *
     * `CastPlayer` reports position 0 until the receiver acknowledges a load, so a cast that is
     * refused has no position of its own to come back with. Without this, recovering from one
     * returned the viewer to the start of an episode they were twenty minutes into.
     */
    private var localRoutePositionMs: Long = 0L

    /** Whether the current receiver ever got as far as playable; see [localRoutePositionMs]. */
    private var remoteRouteEverReady = false

    /**
     * Whether [StreamCorsProxy] answered for the address this handover was about to publish.
     *
     * Resolved once per handover by [handOverToReceiver] and read by [switchPlaybackPlayer], rather
     * than probed inside the switch: setting a player's queue is main-thread work and asking a
     * third-party host anything is not.
     */
    private var castProxyServing = true

    /**
     * Whether a receiver is connected, as the availability callbacks report it.
     *
     * [handOverToReceiver] leaves the main thread to probe an address, and a viewer can disconnect
     * inside that gap — this is how the switch knows the session it was preparing for is still
     * there. `CastPlayer` would accept the queue either way and simply hold it.
     */
    private var castSessionAvailable = false

    /**
     * Asks the edge proxy for headers only, sharing the transport client's DNS and trust anchors.
     *
     * A separate client because the call timeout has to be short: this sits between a viewer
     * choosing a television and the episode appearing on it. Built on the main thread and read from
     * [transportScope], hence volatile.
     */
    @Volatile
    private var castProbeClient: OkHttpClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var castLoadWatchdogArmed = false

    /**
     * The receiver has been handed the queue and has gone quiet: end the session so playback comes
     * back here, rather than leaving the player screen under a loading overlay for good.
     *
     * Re-checks rather than trusts the state that armed it, the same way the player screen's stall
     * failover does — anything that recovered in the meantime must not be torn down for it.
     */
    private val castLoadWatchdog = Runnable {
        castLoadWatchdogArmed = false
        val route = castRoute ?: return@Runnable
        if (!isCastLoadStuck(route)) return@Runnable
        DiagnosticsLog.event(
            "PlaybackService cast load gave up afterMs=$CAST_LOAD_TIMEOUT_MS " +
                "receiver=${route.receiverDescription ?: "none"} " +
                "positionMs=$localRoutePositionMs; returning playback to this device",
        )
        CastRouteStatus.reportPlayingHere(
            "The TV didn't start this episode. Playing it here instead.",
        )
        route.endSession()
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.event("PlaybackService.onCreate")
        // Native Media3 playback must not initialize Chromium just to obtain an HTTP header.
        val playerUserAgent = NATIVE_PLAYBACK_USER_AGENT
        activeUserAgent = playerUserAgent
        val defaultHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(playerUserAgent)
            .setAllowCrossProtocolRedirects(true)
        // Some protected media CDNs reject Android's URLConnection/Cronet fingerprints even when
        // the browser headers are correct. Media3's OkHttp data source gives those hosts a
        // transport they accept, per stream, without a localhost proxy.
        val okHttpClient = OkHttpClient.Builder()
            .withLegacyTrustAnchors()
            .dns(AppDns)
            .addInterceptor(AppDns.InterceptionRetry)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(playerUserAgent)
        castProbeClient = okHttpClient.newBuilder()
            .callTimeout(CAST_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        // Play Services can spend tens of seconds installing its Cronet provider. A foreground
        // service only gets a few seconds to finish starting, so begin with the platform stack and
        // upgrade future data sources after Cronet is ready off the main thread.
        httpFactory = SwappableHttpDataSourceFactory(defaultHttpFactory)
        DiagnosticsLog.event("PlaybackService HTTP transport=DefaultHttpDataSource; Cronet pending")
        activeHttpFactory = httpFactory
        nonCronetHttpFactory = okHttpFactory
        transportScope.launch {
            runCatching { CronetUtil.buildCronetEngine(applicationContext, null, true) }
                .onSuccess { engine ->
                    if (engine == null) {
                        DiagnosticsLog.event("PlaybackService Cronet unavailable; keeping default transport")
                    } else {
                        synchronized(transportLock) {
                            if (!isActive || transportDestroyed) {
                                runCatching { engine.shutdown() }
                            } else {
                                httpFactory.swap(
                                    CronetDataSource.Factory(engine, Runnable::run)
                                        .setUserAgent(playerUserAgent),
                                )
                                shutdownCronet = { engine.shutdown() }
                                DiagnosticsLog.event(
                                    "PlaybackService HTTP transport=Cronet provider=${engine.versionString}",
                                )
                            }
                        }
                    }
                }
                .onFailure { DiagnosticsLog.throwable("PlaybackService Cronet initialization failed", it) }
        }
        // Cronet everywhere except sources known to trip it, which use OkHttp. This mirrors the
        // working extractor transport while keeping Media3 in control of manifests and segments.
        // Both factories are kept configured so the choice can change between episodes.
        //
        // A chosen DNS resolver joins that list of reasons. Cronet and DefaultHttpDataSource each
        // carry their own resolver and offer nowhere to hand one in, so a viewer who turned DoH on
        // to reach a blocked source would watch the episode list load and then get nothing from the
        // player — the manifest and every segment would be looked up by the resolver being bypassed.
        // OkHttp is the only transport here that reads [AppDns], so it is the one that runs.
        val transportDataSource = DataSource.Factory {
            val factory = if (activeAvoidCronet || AppDns.active) okHttpFactory else httpFactory
            factory.createDataSource()
        }
        // Above either HTTP stack and below the caches: the same rule reaches manifests,
        // segments and subtitles, while completed offline downloads remain completely local.
        val upstreamDataSource = androidx.media3.datasource.ResolvingDataSource.Factory(
            transportDataSource,
            androidx.media3.datasource.ResolvingDataSource.Resolver { dataSpec ->
                val referer = dataSpec.httpRequestHeaders.entries
                    .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: activeReferer
                val original = dataSpec.uri.toString()
                val rewritten = com.anilili.data.remote.MegaPlayCdnHealth.rewrite(original, referer)
                if (original == rewritten) dataSpec else dataSpec.withUri(android.net.Uri.parse(rewritten))
            },
        )
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(upstreamDataSource)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Kept below the download cache: recovery must refresh an ordinary stream's playlists and
        // partial segments, while a completed offline episode must still read its saved bytes.
        // The viewer's "Video cache: Off" goes the same way, read per source so it lands mid-episode.
        val cachedOrRecoveryDataSource = DataSource.Factory {
            if (playbackCacheBypass.isActive() || !MediaCache.enabled) {
                upstreamDataSource.createDataSource()
            } else {
                cacheDataSource.createDataSource()
            }
        }
        val downloadedOrStreamingDataSource = EpisodeDownloads.readOnlyPlaybackFactory(
            this,
            cachedOrRecoveryDataSource,
        )
        val localAwareDataSource = DefaultDataSource.Factory(this, downloadedOrStreamingDataSource)
        // Dropped with the AnimePahe provider: its HLS shim sat in front of *every* stream and
        // buffered each segment whole (up to 64 MB) to undo Kwik's non-standard AES-128 IVs and
        // image-typed segments. Nothing else needs that, and Media3 handles standard AES-128 HLS
        // natively, so the chain goes straight to the Flixcloud playlist wrapper again.
        // UniqueStreamKeyDataSource sits outermost: it only intercepts key.bin fetches (serving
        // the locally derived AES key) and passes everything else through unbuffered.
        // KwikPlaylistDataSource sits outermost so it sees a finished playlist whatever produced
        // it, and it only rewrites one that is unmistakably Kwik's — see its own notes for why an
        // implicit IV is wrong there and how quietly it fails.
        val playbackDataSource = DataSource.Factory {
            KwikPlaylistDataSource(
                UniqueStreamKeyDataSource(
                    FlixcloudPlaylistDataSource(localAwareDataSource.createDataSource()) {
                        activePlaylistKey
                    },
                ) { activePlaylistKey },
            )
        }

        // Constrained phones can hit the same playback heap ceiling as Fire TV, so the automatic
        // policy holds both to a shorter buffer. It is read through a lambda rather than once here
        // because a viewer whose episodes keep stalling can lengthen it in settings, and this
        // service outlives the watch screen — see [AdjustableLoadControl] for when it takes effect.
        val isTvDevice = isTelevision(this)
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val loadControl = AdjustableLoadControl {
            playbackBufferPolicy(
                size = SettingsStore.playbackBufferSize.value,
                isTv = isTvDevice,
                isLowRamDevice = activityManager.isLowRamDevice,
                memoryClassMb = activityManager.memoryClass,
                // Read through the lambda like the setting beside it, and for the same reason: this
                // service outlives the watch screen, so the stream it is loading changes underneath
                // a value captured here. [AdjustableLoadControl] re-reads on every prepare, which is
                // exactly when a new stream arrives.
                isProgressive = activeProgressive,
            )
        }
        // The *device's* policy, not necessarily the one in force: a progressive stream widens it,
        // and `activeProgressive` still holds the last stream's answer when a service is rebuilt
        // mid-session. Every change from here is its own `AdjustableLoadControl buffer resized`
        // line, so a reader following a stall has both the baseline and what it became.
        val devicePolicy = playbackBufferPolicy(
            size = SettingsStore.playbackBufferSize.value,
            isTv = isTvDevice,
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
        )
        DiagnosticsLog.event(
            "PlaybackService buffer policy size=${SettingsStore.playbackBufferSize.value.storedValue} " +
                "tv=$isTvDevice lowRam=${activityManager.isLowRamDevice} " +
                "memoryClassMb=${activityManager.memoryClass} maxMs=${devicePolicy.maximumMs} " +
                "bytes=${devicePolicy.targetBufferBytes ?: -1}",
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(playbackDataSource)
                    // A refused segment is a decision, not a hiccup; retrying it only delays the
                    // switch to the provider's other stream. See StreamRefusalLoadErrorPolicy.
                    .setLoadErrorHandlingPolicy(StreamRefusalLoadErrorPolicy()),
            )
            .setRenderersFactory(SubtitleDelayRenderersFactory(this))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        // Null on TV builds, and on mobile devices whose Play Services cannot serve the cast
        // module — playback just runs local.
        castRoute = CastRoutes.create(this)
        activePlayer = player
        PlaybackDiagnostics.attach(player)
        AudioOutputMonitor.attach(player)
        player.addListener(playbackListener(player))
        castRoute?.let { it.player.addListener(playbackListener(it.player)) }
        bindSleepTimer()

        val localEpisodeAwarePlayer = episodeAwarePlayer(player)

        // Brand the media notification: Media3's provider handles layout, actions, and artwork
        // (loading MediaMetadata.artworkUri itself); only the small icon needs overriding.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(com.anilili.R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, localEpisodeAwarePlayer)
            // Artwork reaches the platform session as a raw Bitmap it later rescales on the main
            // thread; see [ArtworkBitmapLoader] for why it must be a copy we alone own.
            .setBitmapLoader(ArtworkBitmapLoader.default(this))
            .setSessionActivity(sessionActivity(null))
            .setCallback(object : MediaSession.Callback {
                // A paused-but-loaded session normally resumes on any KEYCODE_MEDIA_PLAY —
                // including ones a Bluetooth headset or car fires on reconnect, which made
                // paused audio restart in the background "by itself". While the app is
                // backgrounded after a lifecycle pause, ignore media-button play; the
                // notification's own play action arrives as a controller command (not a key
                // event), so deliberate resumes still work.
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    val isResumeKey = key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        key?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    if (isResumeKey && suppressMediaButtonResume && activePlayer?.isPlaying != true) {
                        DiagnosticsLog.event(
                            "PlaybackService ignored background media-button resume key=${key?.keyCode}",
                        )
                        return true
                    }
                    // Media3 1.8.1 caches whether its notification controller is connected, then
                    // force-unwraps a second nullable lookup. Controller removal/session teardown
                    // between those reads throws from inside applyMediaButtonKeyEvent. Handle the
                    // event locally when the controller is absent, and catch that exact race if it
                    // disappears between this check and the library call.
                    if (session.mediaNotificationControllerInfo == null) {
                        return handleMediaButtonLocally(session.player, key)
                    }
                    return try {
                        super.onMediaButtonEvent(session, controllerInfo, intent)
                    } catch (error: NullPointerException) {
                        DiagnosticsLog.throwable("PlaybackService Media3 controller race", error)
                        handleMediaButtonLocally(session.player, key)
                    }
                }
            })
            .build()
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(CommandButton.ICON_REWIND)
                    .setDisplayName("Rewind 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                    .setDisplayName("Forward 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
            ),
        )

        // Media3 1.9 introduced CastPlayer.Builder.setLocalPlayer(), but that release also raised
        // minSdk to 23. Keep the same local/cast hand-off explicitly so Fire OS 5 (API 22) can use
        // the player without sacrificing casting on newer devices.
        castRoute?.let { route ->
            val cast = route.player
            val castEpisodeAwarePlayer = episodeAwarePlayer(cast)
            route.onSessionChanged(
                onAvailable = {
                    castSessionAvailable = true
                    handOverToReceiver(player, cast, castEpisodeAwarePlayer)
                },
                onUnavailable = {
                    castSessionAvailable = false
                    switchPlaybackPlayer(cast, player, localEpisodeAwarePlayer)
                },
            )
        }
    }

    /**
     * Hands [SleepTimer] the native player. That covers picture-in-picture as well, where no
     * player surface is composed to hear the timer.
     *
     * The fade is applied to the local ExoPlayer's own volume and never to a Cast receiver: a
     * receiver's volume is the television's volume, and nothing should leave it at zero. The pause
     * applies to whichever player is active, because a TV that keeps playing all night is exactly
     * what the timer was set to prevent.
     */
    private fun bindSleepTimer() {
        sleepTimerScope.launch {
            SleepTimer.volume.collect { level -> player.volume = level }
        }
        sleepTimerScope.launch {
            SleepTimer.expiries.collect {
                val active = activePlayer ?: return@collect
                DiagnosticsLog.event("PlaybackService sleep timer pause playing=${active.isPlaying}")
                active.pause()
            }
        }
    }

    private fun playbackListener(source: Player): Player.Listener =
        object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event("PlaybackService player isPlaying=$isPlaying")
                        PlaybackStatus.update(isPlaying)
                        // Any playback that actually starts (notification play, in-app play)
                        // re-arms the media buttons; the suppression only covers the window
                        // between the lifecycle pause and the next deliberate start.
                        if (isPlaying) allowMediaButtonResume()
                    }

                    private var lastPlaybackState = Player.STATE_IDLE

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        DiagnosticsLog.event("PlaybackService player state=${playbackState.stateName()}")
                        val preparedFromIdle =
                            lastPlaybackState == Player.STATE_IDLE && playbackState != Player.STATE_IDLE
                        lastPlaybackState = playbackState
                        if (activePlayer !== source) return
                        // Preparing an item here means the viewer is watching in this app again, so
                        // a proxy kept up for an earlier external hand-off has nothing left to serve.
                        if (preparedFromIdle) LocalStreamProxy.stop("native playback prepared")
                        if (playbackState == Player.STATE_READY && source === castRoute?.player) {
                            remoteRouteEverReady = true
                        }
                        reviewCastLoadWatchdog()
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (activePlayer !== source) return
                        reviewCastLoadWatchdog()
                    }

                    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                        if (activePlayer !== source) return
                        val route = if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            "remote"
                        } else {
                            "local"
                        }
                        DiagnosticsLog.event(
                            "PlaybackService route=$route " +
                                "routingController=${deviceInfo.routingControllerId ?: "none"}",
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.throwable("PlaybackService player error code=${error.errorCodeName}", error)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event(
                            "PlaybackService media transition reason=$reason " +
                                "mediaId=${mediaItem?.mediaId?.take(120) ?: "none"}",
                        )
                        if (!::session.isInitialized) return
                        val route = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_WATCH_ROUTE)
                        session.setSessionActivity(sessionActivity(route))
                    }
                }

    private fun episodeAwarePlayer(delegate: Player): ForwardingPlayer =
        // The playlist holds one media item at a time (episodes resolve lazily, per provider),
        // but the session still advertises next/previous by forwarding them to the app's
        // episode navigator. That lights up Media3's built-in prev/next buttons and makes the
        // media notification and hardware/Bluetooth media keys switch episodes.
        object : ForwardingPlayer(delegate) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = episodeNavigator != null
            override fun hasPreviousMediaItem(): Boolean = episodeNavigator != null

            override fun seekToNext() {
                DiagnosticsLog.event("PlaybackService seekToNext navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToNextMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                DiagnosticsLog.event("PlaybackService seekToPrevious navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToPreviousMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPreviousMediaItem()
            }
        }

    /**
     * Settles what a receiver can be given before it is given anything, then switches.
     *
     * Two questions, and both used to be skipped. Whether this source can be cast *at all* —
     * [CastStreamRouting.canCastQueue] — because a keyed playlist or an embed has nothing a receiver
     * could read, and a viewer waiting out fifteen seconds of load watchdog to be told so is fifteen
     * seconds spent on an answer that was available immediately. And whether the edge proxy is
     * serving the address about to be published, because it refuses some of them and a receiver
     * handed a 500 reports only `STATE_IDLE`; see [CastStreamRouting].
     *
     * The probe costs one small ranged GET, ~150ms against a handover that already waits four to
     * five seconds for the session to connect, and it runs off the main thread with local playback
     * still going. A probe that *fails* rather than being refused leaves the proxy in place: this
     * device being unable to reach a host says nothing about what the receiver's network can do,
     * while a refusal is the proxy itself and it will refuse the receiver identically.
     *
     * When the proxy refuses a gated stream, the direct address is asked the same way before it is
     * handed over. It carries no referer from a receiver, so it is usually refused too, and saying
     * so now beats the load watchdog saying it fifteen seconds later.
     */
    private fun handOverToReceiver(from: Player, to: Player, sessionPlayer: Player) {
        val outgoing = List(from.mediaItemCount) { from.getMediaItemAt(it) }
        if (!CastStreamRouting.canCastQueue(outgoing, activePlaylistKey)) {
            declineCast("items=${outgoing.size} keyedPlaylist=${activePlaylistKey != null}")
            return
        }
        val probeUrl = CastStreamRouting.proxyProbeUrl(outgoing, activeReferer)
        if (probeUrl == null) {
            castProxyServing = true
            switchPlaybackPlayer(from, to, sessionPlayer)
            return
        }
        val directUrl = outgoing.first().localConfiguration?.uri?.toString()
        val gated = activeReferer != null
        val progressive = activeProgressive
        transportScope.launch {
            val serving = receiverCanFetch(probeUrl, needsCors = true) ?: true
            if (!serving) {
                DiagnosticsLog.event(
                    "PlaybackService cast address refused by the proxy; handing the receiver the direct address",
                )
            }
            // A network error here says nothing about the receiver's reach, so only a refusal declines.
            val directRefused = !serving && gated && directUrl != null &&
                receiverCanFetch(directUrl, needsCors = !progressive) == false
            mainHandler.post {
                if (!castSessionAvailable || activePlayer === to) return@post
                if (directRefused) {
                    declineCast("gated=true proxyServing=false directRefused=true")
                    return@post
                }
                castProxyServing = serving
                switchPlaybackPlayer(from, to, sessionPlayer)
            }
        }
    }

    private fun declineCast(reason: String) {
        DiagnosticsLog.event(
            "PlaybackService cast declined $reason receiver=${castRoute?.receiverDescription ?: "none"}",
        )
        CastRouteStatus.reportPlayingHere("This server can't play on a TV. Try a different one.")
        castRoute?.endSession()
    }

    /**
     * Whether a receiver could fetch [url] — true, false, or null when this device could not ask.
     *
     * Asked the way a receiver asks, which is the whole point. A receiver is a web page, so every
     * request it makes carries that page's `Origin`, and the edge proxy now answers by allowlist:
     * measured 2026-10-03, a gated KAA playlist through it is 200 with no Origin or a miruro one and
     * 403 for `www.gstatic.com`, `example.com`, `null` or anything else. The probe used to send no
     * Origin, saw 200, and handed the receiver an address that refused it — every referer-gated
     * server failed to cast that way, silently, after the full load watchdog. A web page also needs
     * CORS on what it fetches as data ([needsCors]: playlists and subtitles), though not on a file
     * handed to its video element.
     */
    private fun receiverCanFetch(url: String, needsCors: Boolean): Boolean? {
        val client = castProbeClient ?: return null
        return runCatching {
            // Building the request can throw on a malformed address too, which is the same answer.
            val request = Request.Builder().url(url)
                .header("Origin", RECEIVER_ORIGIN)
                .header("Range", "bytes=0-1023")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val cors = response.header("Access-Control-Allow-Origin")
                    ?.let { it == "*" || it == RECEIVER_ORIGIN } == true
                val reachable = response.isSuccessful && (cors || !needsCors)
                if (!reachable) {
                    DiagnosticsLog.event(
                        "PlaybackService cast address refused for a receiver status=${response.code} " +
                            "cors=$cors host=${request.url.host}",
                    )
                }
                reachable
            }
        }.getOrElse { error ->
            DiagnosticsLog.throwable("PlaybackService cast address probe failed", error)
            null
        }
    }

    private fun switchPlaybackPlayer(from: Player, to: Player, sessionPlayer: Player) {
        if (activePlayer === to) return
        val outgoing = List(from.mediaItemCount) { from.getMediaItemAt(it) }
        val toRemote = to === castRoute?.player
        // A receiver fetches these URLs itself and carries none of our headers, so the queue it is
        // handed is not the queue this device plays; see [CastStreamRouting].
        val mediaItems = if (toRemote) {
            localRouteItems = outgoing
            localRoutePositionMs = from.currentPosition.coerceAtLeast(0L)
            remoteRouteEverReady = false
            CastStreamRouting.castItems(outgoing, activeReferer, castProxyServing)
        } else {
            val snapshot = localRouteItems
            localRouteItems = null
            snapshot
                ?.takeIf { it.size == outgoing.size }
                ?.takeIf { restored -> restored.indices.all { restored[it].mediaId == outgoing[it].mediaId } }
                ?: CastStreamRouting.localItems(outgoing)
        }
        val currentIndex = from.currentMediaItemIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        // A receiver that never reached READY has no position to give back — see
        // [localRoutePositionMs] — so the one the queue left with is the honest answer.
        val currentPosition = if (!toRemote && !remoteRouteEverReady) {
            localRoutePositionMs
        } else {
            from.currentPosition.coerceAtLeast(0L)
        }
        val shouldPlay = from.playWhenReady

        val transfer = runCatching {
            if (mediaItems.isNotEmpty()) {
                to.setMediaItems(mediaItems, currentIndex, currentPosition)
                to.prepare()
                to.playWhenReady = shouldPlay
            }
        }
        if (transfer.isFailure) {
            runCatching {
                to.stop()
                to.clearMediaItems()
            }
            DiagnosticsLog.throwable(
                "PlaybackService route transfer failed target=${if (to === castRoute?.player) "remote" else "local"}",
                checkNotNull(transfer.exceptionOrNull()),
            )
            return
        }

        activePlayer = to
        session.player = sessionPlayer
        from.stop()
        from.clearMediaItems()
        DiagnosticsLog.event(
            "PlaybackService switched route=${if (toRemote) "remote" else "local"} " +
                "items=${mediaItems.size} positionMs=$currentPosition play=$shouldPlay " +
                "proxied=${mediaItems.count { it.localConfiguration?.uri?.toString()?.let(StreamCorsProxy::isProxied) == true }}",
        )
        if (toRemote) {
            // What the receiver was handed, because it is the half of a failed cast that the
            // receiver's own status cannot report: how many sideloaded tracks it has to fetch
            // before it can start, whether the source is gated, and whether the addresses went
            // through the edge proxy or straight to the CDN. `gated=true proxyServing=false` is the
            // one combination that says the proxy refused this pair and the receiver is being asked
            // to fetch a gated CDN itself, carrying no referer, which it will probably be refused.
            DiagnosticsLog.event(
                "PlaybackService cast handover receiver=${castRoute?.receiverDescription ?: "none"} " +
                    "gated=${activeReferer != null} proxyServing=$castProxyServing " +
                    "subtitleTracks=${mediaItems.sumOf { it.localConfiguration?.subtitleConfigurations?.size ?: 0 }} " +
                    "proxiedSubtitles=${
                        mediaItems.sumOf { item ->
                            item.localConfiguration?.subtitleConfigurations.orEmpty()
                                .count { StreamCorsProxy.isProxied(it.uri.toString()) }
                        }
                    }",
            )
        }
        reviewCastLoadWatchdog()
    }

    /**
     * Arms or stands down the wait for a receiver that has stopped saying anything.
     *
     * Deliberately only [Player.STATE_IDLE]: a receiver that is genuinely still fetching reports
     * BUFFERING, which the player screen's own twenty-second failover already covers. IDLE with
     * the queue still wanting to play is the state `CastPlayer` leaves behind when a load is
     * refused, and the one nothing else in the app was watching for.
     */
    private fun reviewCastLoadWatchdog() {
        val route = castRoute ?: return
        if (!isCastLoadStuck(route)) {
            if (castLoadWatchdogArmed) {
                castLoadWatchdogArmed = false
                mainHandler.removeCallbacks(castLoadWatchdog)
            }
            return
        }
        if (castLoadWatchdogArmed) return
        castLoadWatchdogArmed = true
        mainHandler.postDelayed(castLoadWatchdog, CAST_LOAD_TIMEOUT_MS)
    }

    private fun isCastLoadStuck(route: CastRoute): Boolean {
        val cast = route.player
        return shouldGiveUpOnCastLoad(
            isRemoteRoute = activePlayer === cast,
            playbackState = cast.playbackState,
            playWhenReady = cast.playWhenReady,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /**
     * Stands the service down when it was foreground-started with nothing to play.
     *
     * Android wakes the whole process just to hand this service a `startForegroundService`, and
     * then requires a `startForeground` within a few seconds or it kills the app with
     * [android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException]. Media3 only
     * posts its notification once a player is really playing, and a stray media button — a headset
     * or car stereo reconnecting is the usual source — is one [MediaSession.Callback.onMediaButtonEvent]
     * deliberately ignores while the app is in the background. So nothing plays, nothing posts a
     * notification, and the process is killed: three crash reports on 0.1.54 all show a cold start
     * reaching `MiruroApp.onCreate` with no activity and no playback, then the fatal.
     *
     * Ending the service cancels that timer, which is the honest response — there is nothing here
     * to be in the foreground for. Playback started from the app binds the service instead of
     * starting it, so it never takes this path; a notification button does, and by then the player
     * holds the episode it belongs to.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if ((activePlayer?.mediaItemCount ?: 0) == 0) {
            DiagnosticsLog.event(
                "PlaybackService foreground start with nothing queued; standing down " +
                    "action=${intent?.action ?: "none"}",
            )
            standDownFromForegroundStart()
        }
        return result
    }

    /**
     * Answers the foreground start the only way the system accepts, then leaves.
     *
     * Stopping the service is not an answer: 0.1.57 shipped exactly that and a vivo on Android 16
     * still died, its event log reading `onCreate` → stand-down → `onDestroy` → fatal inside one
     * second. The timer is armed by `startForegroundService` and only [startForeground] disarms it,
     * so post a notification, take it straight back down, and stop. The channel is muted and the
     * notification is removed in the same breath, so nothing reaches the shade.
     */
    private fun standDownFromForegroundStart() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                if (manager?.getNotificationChannel(STAND_DOWN_CHANNEL_ID) == null) {
                    manager?.createNotificationChannel(
                        NotificationChannel(
                            STAND_DOWN_CHANNEL_ID,
                            "Playback handover",
                            NotificationManager.IMPORTANCE_MIN,
                        ),
                    )
                }
            }
            val placeholder = NotificationCompat.Builder(this, STAND_DOWN_CHANNEL_ID)
                .setSmallIcon(com.anilili.R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build()
            startForeground(STAND_DOWN_NOTIFICATION_ID, placeholder)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure {
            DiagnosticsLog.event("PlaybackService stand-down notification refused: ${it.javaClass.simpleName}")
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = activePlayer
        DiagnosticsLog.event("PlaybackService.onTaskRemoved playing=${active?.playWhenReady == true}")
        if (active?.playWhenReady != true || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        DiagnosticsLog.event("PlaybackService.onDestroy")
        val closeCronet = synchronized(transportLock) {
            transportDestroyed = true
            shutdownCronet.also { shutdownCronet = null }
        }
        transportScope.cancel()
        sleepTimerScope.cancel()
        runCatching { closeCronet?.invoke() }
        playbackCacheBypass.end()
        mainHandler.removeCallbacks(castLoadWatchdog)
        castLoadWatchdogArmed = false
        activeHttpFactory = null
        activePlayer = null
        episodeNavigator = null
        PlaybackStatus.update(false)
        if (::session.isInitialized) session.release()
        castRoute?.release()
        if (::player.isInitialized) {
            AudioOutputMonitor.detach(player)
            player.release()
        }
        super.onDestroy()
    }

    private fun sessionActivity(route: String?): PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(Routes.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            this,
            route?.hashCode() ?: 0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_WATCH_ROUTE = "watch_route"

        /**
         * Channel and id for the placeholder that answers a foreground start with nothing to play.
         * Deliberately not Media3's notification id, so taking this one down can never cancel a
         * real playback notification that arrived in between.
         */
        private const val STAND_DOWN_CHANNEL_ID = "playback_handover"
        private const val STAND_DOWN_NOTIFICATION_ID = 4711

        /**
         * How long a receiver may hold the queue in IDLE before this gives up on it.
         *
         * Generous on purpose, because ending a session the viewer chose is not a small thing to
         * do on their behalf. It costs nothing to wait: a receiver that is working answers in
         * seconds — the one in the report that produced this went IDLE 896ms after being handed
         * the episode — and one that is still fetching says BUFFERING rather than IDLE.
         */
        private const val CAST_LOAD_TIMEOUT_MS = 15_000L

        /**
         * How long a cast address has to answer the pre-handover probe before it is abandoned.
         *
         * Measured at ~150ms, so this is not a budget but a ceiling: it sits between a viewer
         * picking a television and the episode appearing, and running out of it changes nothing
         * (see `receiverCanFetch`), so waiting longer buys nothing.
         */
        private const val CAST_PROBE_TIMEOUT_MS = 4_000L

        /**
         * The `Origin` the pre-handover probe sends, standing in for the receiver page's own. Its
         * exact value matters less than that it is not a miruro site: the edge proxy allowlists
         * those and refuses every other origin alike.
         */
        private const val RECEIVER_ORIGIN = "https://www.gstatic.com"
        private val playbackCacheBypass = PlaybackCacheBypassGate()
        @Volatile
        private var activeHttpFactory: HttpDataSource.Factory? = null

        /** The plain stack, kept alongside Cronet for sources Cronet cannot read. */
        @Volatile
        private var nonCronetHttpFactory: HttpDataSource.Factory? = null

        @Volatile
        private var activeAvoidCronet: Boolean = false

        /**
         * Whether the current source is one long file rather than a list of segments.
         *
         * Only the buffer policy asks: a progressive read is cancelled and re-opened every time the
         * buffer fills, and on a television those reopens are what a viewer sees as stalling. See
         * [playbackBufferPolicy].
         */
        @Volatile
        private var activeProgressive: Boolean = false
        @Volatile
        private var activeUserAgent: String = NATIVE_PLAYBACK_USER_AGENT
        @Volatile
        private var activePlaylistKey: String? = null

        /**
         * The referer the current source asked for, or null when it named none.
         *
         * Kept apart from the header map because it is also the signal that a source is gated at
         * all, which is what decides whether a Cast receiver can be handed the URL directly.
         */
        @Volatile
        private var activeReferer: String? = null
        @Volatile
        private var activePlayer: Player? = null

        /**
         * Set by the watch screen while it is visible: receives +1/-1 and resolves the
         * next/previous episode through the normal source-resolution flow. Invoked on the
         * main thread (session commands arrive on the application looper).
         */
        @Volatile
        var episodeNavigator: ((direction: Int) -> Unit)? = null

        /** Used when switching explicitly from native playback to a provider WebView. */
        fun stopActivePlayback() {
            DiagnosticsLog.event("PlaybackService.stopActivePlayback")
            finishUncachedPlaybackRecovery()
            // Nothing is left to resume, so a pending pause record must not outlive the item.
            involuntaryPause.set(false)
            activePlayer?.run {
                stop()
                clearMediaItems()
            }
        }

        @Volatile
        private var suppressMediaButtonResume = false

        /**
         * Set when [pauseActivePlayback] stopped playback that was actually running, and cleared by
         * the next player surface that re-attaches to the still-loaded item.
         *
         * The player screen and the loaded media have separate lifetimes: leaving the screen pauses
         * but keeps the item, so re-entering resumes in place. Without this flag that re-entry could
         * not tell "we paused this because the screen went away" from "the viewer pressed pause",
         * and carried the pause forward — the episode came back frozen on a single held frame.
         * A viewer-initiated pause never reaches here with [Player.getPlayWhenReady] still true, so
         * their choice is preserved.
         */
        private val involuntaryPause = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Whether the pause still in effect was ours, clearing the record in the same breath. */
        fun consumeInvoluntaryPause(): Boolean = involuntaryPause.getAndSet(false)

        /** Pause playback when the app is backgrounded while keeping the current media loaded. */
        fun pauseActivePlayback() {
            DiagnosticsLog.event("PlaybackService.pauseActivePlayback")
            val active = activePlayer ?: return
            if (active.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                DiagnosticsLog.event("PlaybackService.pauseActivePlayback skipped remote route")
                return
            }
            if (active.playWhenReady) involuntaryPause.set(true)
            active.pause()
            // The media stays loaded so the notification can resume it, but stray media-button
            // events (Bluetooth reconnects) must not restart it while the app is away.
            suppressMediaButtonResume = true
        }

        /** The app is visible again (or playback deliberately restarted): buttons act normally. */
        fun allowMediaButtonResume() {
            suppressMediaButtonResume = false
        }

        /** Starts a same-stream retry whose network reads must not reuse the failed cached route. */
        fun beginUncachedPlaybackRecovery() {
            if (playbackCacheBypass.begin()) {
                DiagnosticsLog.event("PlaybackService playback cache bypass enabled for stall recovery")
            }
        }

        /** Returns ordinary streaming reads to the bounded playback cache. */
        fun finishUncachedPlaybackRecovery() {
            if (playbackCacheBypass.end()) {
                DiagnosticsLog.event("PlaybackService playback cache bypass cleared")
            }
        }

        /** Applies per-provider headers before Media3 creates manifest and segment data sources. */
        fun configureRequestHeaders(
            referer: String?,
            playlistKey: String?,
            extraHeaders: Map<String, String> = emptyMap(),
            avoidCronet: Boolean = false,
            progressive: Boolean = false,
        ) {
            activePlaylistKey = playlistKey
            activeAvoidCronet = avoidCronet
            activeProgressive = progressive
            activeReferer = referer?.takeIf(String::isNotBlank)
            // A null referer is the app's signal that a stream is *not gated* — AniChan,
            // AnimeHeaven and XFDM all set it deliberately, each on a measurement that their CDN
            // refuses nothing. Standing in `https://www.miruro.to/` for them did not make those
            // streams safer; it broke the one request that leaves the stream's own origin.
            // Media3 configures one set of default request properties for the whole MediaItem, so
            // a fabricated Referer also rode along on sideloaded subtitles, and AniChan's
            // subtitle CDN answers 403 to it: measured against s1.toroplay.click, the same URL is
            // 200 with no Referer and 403 with miruro's. The track appeared in the menu, English
            // was selected, and no line was ever drawn.
            val headers = mediaRequestHeaders(
                referer = activeReferer,
                userAgent = activeUserAgent,
                extraHeaders = extraHeaders,
            )
            // Both stacks stay configured; the upstream factory picks between them per stream.
            activeHttpFactory?.setDefaultRequestProperties(headers)
            nonCronetHttpFactory?.setDefaultRequestProperties(headers)
            DiagnosticsLog.event(
                "PlaybackService.configureRequestHeaders " +
                    "refererHost=${activeReferer?.let { android.net.Uri.parse(it).host } ?: "none"} " +
                    "playlistKey=${playlistKey != null} extraHeaders=${extraHeaders.keys.sorted()} " +
                    "avoidCronet=$avoidCronet progressive=$progressive",
            )
        }
    }
}

/** A stable factory reference whose delegate can be upgraded without rebuilding the player. */
@OptIn(UnstableApi::class)
internal class SwappableHttpDataSourceFactory(
    initial: HttpDataSource.Factory,
) : HttpDataSource.Factory {
    @Volatile
    private var delegate: HttpDataSource.Factory = initial

    @Volatile
    private var requestProperties: Map<String, String> = emptyMap()

    override fun createDataSource(): HttpDataSource = delegate.createDataSource()

    override fun setDefaultRequestProperties(
        defaultRequestProperties: Map<String, String>,
    ): HttpDataSource.Factory = apply {
        requestProperties = defaultRequestProperties.toMap()
        delegate.setDefaultRequestProperties(requestProperties)
    }

    fun swap(next: HttpDataSource.Factory) {
        next.setDefaultRequestProperties(requestProperties)
        delegate = next
    }
}

private fun handleMediaButtonLocally(player: Player, key: KeyEvent?): Boolean {
    if (key == null) return true
    if (key.action != KeyEvent.ACTION_DOWN) return true
    when (key.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
        KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> if (player.isPlaying) player.pause() else player.play()
        KeyEvent.KEYCODE_MEDIA_NEXT -> player.seekToNext()
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.seekToPrevious()
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekForward()
        KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBack()
        KeyEvent.KEYCODE_MEDIA_STOP -> player.stop()
        else -> return false
    }
    return true
}

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}

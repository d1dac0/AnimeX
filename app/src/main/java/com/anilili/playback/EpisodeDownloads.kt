package com.anilili.playback

import com.anilili.data.ProviderCatalog
import com.anilili.data.model.SkipTimes
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.remote.AppDns
import com.anilili.data.settings.DownloadQuality
import com.anilili.MainActivity
import com.anilili.R
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import com.anilili.diagnostics.DiagnosticsHttpEventListener
import com.anilili.ui.nav.Routes
import com.anilili.ui.watch.CategoryAudioDecision
import com.anilili.ui.watch.categoryAudioDecision
import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import com.anilili.util.cached

@Serializable
data class EpisodeDownloadSubtitle(
    val url: String,
    val label: String,
    val language: String,
    val fileName: String? = null,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * The format the provider stated, for a URL whose path carries no extension. Without it the
     * saved file was named `.vtt` whatever it held, and an SRT or ASS track then failed to parse
     * offline.
     */
    val mimeType: String? = null,
)

@Serializable
data class EpisodeDownloadMetadata(
    val anilistId: Int,
    val seriesTitle: String,
    val episodeNumber: String,
    val episodeTitle: String? = null,
    val filler: Boolean = false,
    val artworkUrl: String? = null,
    val provider: String,
    val category: String,
    /**
     * The subtitle shape this copy came from — `sub`, `hsub`, `ssub` or `dub` — or null for the
     * downloads queued before the field existed.
     *
     * [category] cannot stand in for it: bonk and twin each publish a hard sub and a soft sub of
     * the same episode under the same category, and burned-in subtitles versus a track the player
     * can switch off is the whole of the difference between the two files. A re-queue on another
     * server reads this to ask for the same thing again.
     */
    val variant: String? = null,
    /**
     * The download was asked for with "Hard subs only", so a re-queue on another server keeps to
     * servers that burn the subtitles in rather than settling for a soft-sub copy. See
     * [HardSubDownloads].
     */
    val hardSubsOnly: Boolean = false,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /**
     * The obfuscation key the source's playlists need, carried so the downloader can read them.
     *
     * Playback holds this on the service as `activePlaylistKey`; a download has no player, and the
     * value has to survive a process restart, so it rides in the request's own metadata. Defaulted
     * for the downloads queued before this field existed — those are all from sources that never
     * had a key. See [EpisodeDownloads.upstreamFactory] for what consumes it.
     */
    val playlistKey: String? = null,
    val subtitles: List<EpisodeDownloadSubtitle> = emptyList(),
    val quality: String? = null,
    val streamType: String? = null,
    /**
     * The quality that was *asked* for, which [quality] stops being the moment an HLS manifest is
     * prepared — that field is rewritten to the rendition actually selected so the library and the
     * exported filename tell the truth. Re-queueing on another source needs the original request
     * back, so it is kept separately rather than parsed out of a resolution label.
     */
    val requestedQuality: String? = null,
    /**
     * Every provider this episode has already been handed to, oldest first.
     *
     * Carried in the download request rather than held in memory so a source that failed stays
     * excluded across a process restart, and so the number of sources already burned is knowable
     * from the download alone.
     */
    val attemptedProviders: List<String> = emptyList(),
    /**
     * How many times this episode was resolved again on [provider] itself after a transfer failed,
     * for a server whose stream URLs expire ([ProviderCatalog.hasShortLivedStreamUrls]). Bounds that
     * retry to once, so a server that keeps failing still hands the episode on.
     */
    val freshResolves: Int = 0,
    /**
     * The intro, recap and outro windows this copy was downloaded with — the server's own, or
     * AniSkip's once the watch page had looked them up — so the Skip button works on the saved
     * copy offline. They fit this encode, which is the one being saved. Null for downloads made
     * before the field existed; the downloaded player looks those up itself.
     */
    val skip: SkipTimes? = null,
)

enum class EpisodeDownloadState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    REMOVING,
    RESTARTING,
    STOPPED,
}

data class EpisodeDownload(
    val id: String,
    val uri: String,
    val metadata: EpisodeDownloadMetadata,
    val state: EpisodeDownloadState,
    val percent: Float?,
    val bytesDownloaded: Long,
    val contentLength: Long?,
    val updatedAtMs: Long,
    val subtitleTotal: Int = 0,
    val subtitleDownloaded: Int = 0,
    val subtitlesPending: Boolean = false,
    /** Media3's raw stop reason, so a pause the app performed reads differently from the user's. */
    val stopReason: Int = 0,
) {
    val isComplete: Boolean get() = state == EpisodeDownloadState.COMPLETED
    val isPaused: Boolean get() = state == EpisodeDownloadState.STOPPED
    val canPause: Boolean get() = state in setOf(
        EpisodeDownloadState.QUEUED,
        EpisodeDownloadState.DOWNLOADING,
        EpisodeDownloadState.RESTARTING,
    )
    val canResume: Boolean get() = isPaused

    /** Paused by the stall watchdog or the foreground-service budget, not by the viewer. */
    val stalled: Boolean get() = isPaused && stopReason in setOf(
        EpisodeDownloads.SYSTEM_TIMEOUT_STOP_REASON,
        EpisodeDownloads.STALLED_DOWNLOAD_STOP_REASON,
    )

    /**
     * A download that has stopped making progress and will not restart itself.
     *
     * Media3 gives up after its own retries and parks the download in FAILED, and the watchdog
     * parks a stalled one in STOPPED; neither ever comes back on its own. Both were dead ends in
     * the UI — the only offered action was Delete, which throws away the bytes already fetched.
     */
    val canRetry: Boolean get() = state == EpisodeDownloadState.FAILED || stalled
    val offlineSubtitleStatusLabel: String?
        get() = offlineSubtitleStatusLabel(subtitleTotal, subtitleDownloaded, subtitlesPending)
    val hasMissingOfflineSubtitles: Boolean
        get() = subtitleTotal > subtitleDownloaded && !subtitlesPending

    /** Adaptive downloads are a manifest plus segments; direct ones are already a single file. */
    val isAdaptive: Boolean get() = metadata.streamType?.equals("hls", true)
        ?: uri.contains(".m3u8", ignoreCase = true)

    val isActive: Boolean get() = state in setOf(
        EpisodeDownloadState.QUEUED,
        EpisodeDownloadState.DOWNLOADING,
        EpisodeDownloadState.RESTARTING,
        EpisodeDownloadState.STOPPED,
    )
}

internal fun offlineSubtitleStatusLabel(total: Int, downloaded: Int, pending: Boolean): String? = when {
    total <= 0 || downloaded >= total -> null
    pending -> "Saving subtitles…"
    downloaded <= 0 -> "Subtitles unavailable offline"
    else -> "$downloaded of $total subtitles available offline"
}

/** Try the provider profile first, then one minimal retry for hotlink servers rejecting it. */
internal fun subtitleRequestHeaderProfiles(
    metadata: EpisodeDownloadMetadata,
    subtitle: EpisodeDownloadSubtitle,
    userAgent: String,
): List<Map<String, String>> {
    val specificHeaders = metadata.headers + subtitle.headers
    val preferred = mediaRequestHeaders(
        referer = subtitle.referer ?: metadata.referer,
        userAgent = userAgent,
        extraHeaders = specificHeaders,
    ) + ("User-Agent" to userAgent)
    val minimal = buildMap {
        put("User-Agent", userAgent)
        putAll(subtitle.headers)
    }
    return listOf(preferred, minimal).distinct()
}

/**
 * Owns Media3's persistent download index and non-evicting download cache.
 *
 * Download request metadata contains the HTTP profile needed by the provider. A resolving data
 * source applies that profile to the manifest and every child playlist/segment request, including
 * child URLs hosted on a different CDN. Downloads are deliberately serialized so the active
 * profile is unambiguous — [activePlaylistKey] leans on that same guarantee.
 *
 * Above the resolver sit the Flixcloud and UniqueStream decoders, so a source whose playlists are
 * obfuscated can be saved rather than merely played. See [upstreamFactory] and [canDownload].
 */
@OptIn(UnstableApi::class)
object EpisodeDownloads {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloads = MutableStateFlow<List<EpisodeDownload>>(emptyList())
    private val _preparingIds = MutableStateFlow<Set<String>>(emptySet())
    val preparingIds: StateFlow<Set<String>> = _preparingIds.asStateFlow()

    private val metadataById = ConcurrentHashMap<String, EpisodeDownloadMetadata>()
    private val manifestIdByUri = ConcurrentHashMap<String, String>()
    private val manifestIdByHost = ConcurrentHashMap<String, String>()
    private val requestByUri = ConcurrentHashMap<String, DownloadRequest>()
    private val providerFallbacksStarted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val batchNotices = DownloadBatchNotices()
    private val hostCooldowns = HostCooldowns()

    /**
     * Fetches the parts of one download in parallel.
     *
     * This used to be `Runnable::run`, which Media3 documents as "each download task will download
     * data on its own thread" — one thread for the *whole* episode. A progressive MP4 does not care;
     * an HLS episode is hundreds of small segments fetched strictly one after another, so every
     * segment pays a full round trip before the next one starts and the throughput ceiling is
     * latency, not bandwidth. That is the difference behind "part 2 finished twelve episodes in
     * fifteen minutes, part 1 is still on episode 1": a distant or slow-to-first-byte CDN costs
     * nothing on one big file and everything on a segmented one.
     *
     * Kept deliberately small. [maxParallelDownloads] stays at 1, so this widens a single episode
     * rather than running several at once, and the threads are daemons so a queue left running
     * cannot hold the process up.
     */
    private val segmentExecutor: Executor by lazy {
        Executors.newFixedThreadPool(SEGMENT_DOWNLOAD_THREADS) { runnable ->
            Thread(runnable, "episode-download").apply { isDaemon = true }
        }
    }
    private val subtitleJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var initialized = false
    @Volatile private var activeDownloadId: String? = null
    @Volatile private var preparingDownloadId: String? = null
    private var progressPoller: Job? = null

    private lateinit var appContext: Context
    private lateinit var databaseProvider: DatabaseProvider
    private lateinit var downloadCache: SimpleCache
    private lateinit var upstreamFactory: DataSource.Factory
    private lateinit var manager: DownloadManager
    private lateinit var userAgent: String

    /**
     * Segment transport for the downloader.
     *
     * The timeouts are stated rather than inherited. Left unset these were OkHttp's defaults — ten
     * seconds to read — which made this the only client in the app reading on a tenth of the
     * patience [PlaybackService] and [LocalStreamProxy] give the very same CDNs. Segments here run
     * to four megabytes and a busy host routinely takes fifteen to twenty seconds over one, so the
     * short read timeout turned a slow server into a hard error and handed it to a retry loop.
     *
     * Declared before [subtitleClient], which derives from it (#73). Internal so
     * `MirrorConnectionPoolTest` can prove that sharing.
     */
    internal val downloadHttpClient = OkHttpClient.Builder()
        .addInterceptor(com.anilili.data.remote.MegaPlayCdnHealthInterceptor())
        .withLegacyTrustAnchors()
        .dns(AppDns)
        .addInterceptor(AppDns.InterceptionRetry)
        .eventListenerFactory(DiagnosticsHttpEventListener.Factory)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Transport for sidecar subtitle files, derived from [downloadHttpClient] with `newBuilder()`
     * (#73). Subtitles and the segments they ride beside come from the same CDNs, and a second
     * independent client meant a second ConnectionPool and Dispatcher for them — duplicate TLS
     * handshakes, idle sockets held twice over. Deriving shares both, and the configuration it
     * inherits was already this client's own down to the letter: the same interceptors, DNS,
     * redirects, 20s/30s deadlines, and `retryOnConnectionFailure`, which the subtitle client left
     * at OkHttp's default of true rather than stating. Nothing about these requests changes except
     * that they reuse the downloader's connections.
     */
    internal val subtitleClient = downloadHttpClient.newBuilder().build()

    fun downloads(context: Context): StateFlow<List<EpisodeDownload>> {
        initialize(context)
        return _downloads.asStateFlow()
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            databaseProvider = ExoDatabase.provider(appContext)
            downloadCache = SimpleCache(
                File(appContext.filesDir, DOWNLOAD_DIRECTORY),
                NoOpCacheEvictor(),
                databaseProvider,
            )
            // WebSettings.getDefaultUserAgent() initializes the complete Chromium renderer on
            // current Android WebView builds. Downloads are native HTTP work, so use the same
            // browser-compatible UA we already used when that lookup failed instead of paying
            // for a hidden browser process on Home and beside native playback.
            userAgent = NATIVE_PLAYBACK_USER_AGENT
            // Keep manifest preparation and segment downloads on the same browser-compatible
            // transport used by provider extraction. This avoids the URLConnection fingerprint
            // differences that cause protected CDNs to return HTML instead of an HLS manifest.
            val okHttpFactory = OkHttpDataSource.Factory(downloadHttpClient)
                .setUserAgent(userAgent)
            // Innermost, so a 429 is waited out at the request that got it — before the playlist
            // decoders or Media3's own five-quick-retries policy can turn it into a failed episode.
            val httpFactory = DataSource.Factory {
                RateLimitBackoffDataSource(okHttpFactory.createDataSource(), hostCooldowns)
            }
            val headerFactory = ResolvingDataSource.Factory(
                httpFactory,
                ResolvingDataSource.Resolver { dataSpec ->
                    val headers = requestHeadersFor(dataSpec.uri)
                    if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
                },
            )
            // The same two decoders [PlaybackService] runs, in the same order, because a source
            // whose playlists are obfuscated is unreadable to the *downloader* for exactly the
            // reason it is unreadable to the player. Without them ReAnime and UniqueStream could
            // never be saved at all, and the picker had to badge them undownloadable.
            //
            // These sit inside the factory the DownloadManager caches from, so what lands in the
            // cache is the decoded playlist and the segment bytes as the CDN served them. That is
            // what makes offline playback work afterwards: the cache is read back through
            // PlaybackService's own copy of this chain, and both decoders are no-ops on input they
            // have already handled — Flixcloud returns early on a playlist that already begins
            // `#EXTM3U`, and neither recognises a segment it has nothing to do to.
            //
            // Kwik's is deliberately absent. Its playlist rewrite is only needed to play bytes,
            // and [enqueue] takes Kwik's progressive MP4 instead of its playlist anyway.
            upstreamFactory = DataSource.Factory {
                UniqueStreamKeyDataSource(
                    FlixcloudPlaylistDataSource(headerFactory.createDataSource()) {
                        activePlaylistKey()
                    },
                ) { activePlaylistKey() }
            }
            manager = DownloadManager(
                appContext,
                databaseProvider,
                downloadCache,
                upstreamFactory,
                segmentExecutor,
            ).apply {
                maxParallelDownloads = 1
                addListener(downloadListener)
            }
            initialized = true
            refreshDownloads()
            DiagnosticsLog.event("EpisodeDownloads initialized")
            // Safe to call from inside the lock: this object is already flagged initialized, so
            // the export observer's own call back into here returns before reaching it.
            EpisodeExport.initialize(appContext)
        }
    }

    fun getDownloadManager(context: Context): DownloadManager {
        initialize(context)
        return manager
    }

    /** Stops all active work without a service command, used from the platform FGS timeout. */
    internal fun pauseAllForSystemTimeout(context: Context) {
        initialize(context)
        manager.currentDownloads
            .filter {
                it.state == Download.STATE_QUEUED ||
                    it.state == Download.STATE_DOWNLOADING ||
                    it.state == Download.STATE_RESTARTING
            }
            .forEach { manager.setStopReason(it.request.id, SYSTEM_TIMEOUT_STOP_REASON) }
        DiagnosticsLog.event("Episode downloads paused after foreground-service timeout")
    }

    /** Removes cached media in-process; safe for workers that cannot start a background service. */
    internal fun removeDirectly(context: Context, id: String) {
        initialize(context)
        manager.removeDownload(id)
        DiagnosticsLog.event("Episode download direct remove requested id=$id")
    }

    fun downloadCache(context: Context): Cache {
        initialize(context)
        return downloadCache
    }

    /**
     * Adds the download cache in front of the normal streaming source without writing new playback
     * traffic into it. Media3 will therefore play a completed download offline while ordinary
     * streams continue to use the app's bounded playback cache.
     */
    fun readOnlyPlaybackFactory(
        context: Context,
        upstream: DataSource.Factory,
    ): DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache(context))
        .setUpstreamDataSourceFactory(upstream)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * The downloader's transport for one stream that is not a download yet, for reading its
     * playlist ahead of time — see [DownloadRenditionProbe].
     *
     * [upstreamFactory] cannot serve this: it finds its headers and playlist key through whichever
     * download is in flight, and a probe asks about a stream nothing has queued. So the same client
     * and the same two decoders, with this stream's own headers and key fixed in.
     */
    internal fun probeDataSourceFactory(context: Context, stream: StreamItem): DataSource.Factory {
        initialize(context)
        val http = OkHttpDataSource.Factory(downloadHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(
                mediaRequestHeaders(
                    referer = stream.referer,
                    userAgent = userAgent,
                    extraHeaders = stream.headers,
                ),
            )
        val key = stream.playlistKey
        return DataSource.Factory {
            UniqueStreamKeyDataSource(
                FlixcloudPlaylistDataSource(http.createDataSource()) { key },
            ) { key }
        }
    }

    fun idFor(anilistId: Int, category: String, episodeNumber: String): String =
        "episode:$anilistId:${category.trim().lowercase()}:${episodeNumber.trim()}"

    /**
     * Whether the offline library can fetch this stream.
     *
     * A [StreamItem.playlistKey] used to disqualify a stream outright, because the downloader's
     * transport was a bare header-adding [ResolvingDataSource] while playback ran the same bytes
     * through Flixcloud and UniqueStream decoders — so a ReAnime or UniqueStream download would
     * have cached a playlist nothing could read. [upstreamFactory] now carries those same two
     * decoders, so a key is no longer a reason to refuse.
     *
     * What was measured before wiring it (2026-09-10, ReAnime/flixcloud, off-device): the master
     * and media playlists arrive XOR'd under the key and decode to ordinary HLS; the segments are
     * plain AES-128 whose `key.bin` serves 200 over a normal GET; the decrypted bytes are raw
     * MPEG-TS, with the `.webp`/`.png` extension and the image content type being camouflage in
     * the URL rather than a wrapper. So decoding the playlists is the whole of what the downloader
     * was missing — Media3 already does the rest.
     */
    fun canDownload(stream: StreamItem?): Boolean =
        stream != null &&
            !stream.isEmbed &&
            (stream.isHls || stream.isDirectFile())

    /**
     * Whether the Downloads folder can receive this episode — the same set as [canDownload],
     * because everything now reaches it the same way: into the library cache first, then rewrapped
     * into a single MP4 by [EpisodeExport].
     *
     * Handing direct files to the system downloader instead would be one pass rather than two, but
     * it produces a file the app has no record of (and leaves .mkv/.webm sources unconverted), so
     * the episode would vanish from the offline library. One route keeps the library honest.
     *
     * Callers must still use [DeviceDownloadAccess]: Android 6–9 need runtime storage permission,
     * while Android 10+ can publish through MediaStore without broad storage access.
     */
    fun canSaveToDevice(stream: StreamItem?): Boolean = canDownload(stream)

    /**
     * Prepares the adaptive manifest first so Media3 records the selected HLS stream keys instead
     * of blindly downloading every rendition in a multivariant playlist.
     */
    fun enqueue(
        context: Context,
        metadata: EpisodeDownloadMetadata,
        requestedStream: StreamItem,
        quality: DownloadQuality = DownloadQuality.BEST,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        initialize(context)
        if (!canDownload(requestedStream)) {
            onResult(Result.failure(IllegalArgumentException("This stream cannot be downloaded")))
            return
        }
        val id = idFor(metadata.anilistId, metadata.category, metadata.episodeNumber)
        // Kwik hands out the same rendition as one MP4 as well as a playlist, and for a download
        // that is one ranged request instead of a key plus ~140 encrypted segments — already in
        // the container the export step would otherwise rewrap it into. Its renditions are
        // separate URLs rather than one adaptive ladder, so nothing is given up by not asking the
        // player to choose a track.
        val progressive = kwikProgressiveMp4(
            requestedStream,
            fileName = "${safeFilePart(metadata.seriesTitle)} ${metadata.episodeNumber}.mp4",
        )
        if (progressive != null) {
            DiagnosticsLog.event("Kwik download taking the progressive MP4 id=$id")
        }
        val stream = progressive ?: requestedStream
        val preparedMetadata = metadata.copy(
            // Read off the stream rather than asked of the caller, so no queue can forget it: both
            // paths here already hand over the StreamItem, and it is the only thing that knows.
            playlistKey = stream.playlistKey,
            // Providers routinely list the same track twice — BLACK TORCH offers two identical
            // "English" tracks. Downloading both wastes a request and, once exported, drops two
            // sidecars next to the MP4 that the viewer has no way to tell apart.
            subtitles = metadata.subtitles
                .distinctBy { it.url }
                .distinctBy { it.language.trim().lowercase() to it.label.trim().lowercase() }
                .mapIndexed { index, subtitle ->
                    subtitle.copy(fileName = subtitle.fileName ?: subtitleFileName(id, index, subtitle.url, subtitle.mimeType))
                },
            quality = if (stream.isHls) quality.label else stream.label,
            streamType = if (stream.isHls) "hls" else "direct",
            requestedQuality = quality.storedValue,
        )
        metadataById[id] = preparedMetadata
        manifestIdByUri[stream.url] = id
        Uri.parse(stream.url).host?.let { manifestIdByHost[it] = id }
        preparingDownloadId = id
        _preparingIds.value += id

        if (!stream.isHls) {
            val result = runCatching {
                val data = json.encodeToString(preparedMetadata).encodeToByteArray()
                val request = DownloadRequest.Builder(id, Uri.parse(stream.url))
                    .setMimeType(directFileFormat(stream).mimeType)
                    .setData(data)
                    .build()
                requestByUri[request.uri.toString()] = request
                DownloadService.sendAddDownload(
                    appContext,
                    EpisodeDownloadService::class.java,
                    request,
                    false,
                )
                DiagnosticsLog.event(
                    "Direct episode download queued id=$id provider=${preparedMetadata.provider} " +
                        "host=${request.uri.host ?: "unknown"} quality=${preparedMetadata.quality}",
                )
                downloadSubtitleFiles(id, preparedMetadata)
            }
            finishPreparing(id)
            onResult(result)
            return
        }

        prepareHlsDownload(id, preparedMetadata, stream, quality, onResult)
    }

    private fun prepareHlsDownload(
        id: String,
        preparedMetadata: EpisodeDownloadMetadata,
        stream: StreamItem,
        quality: DownloadQuality,
        onResult: (Result<Unit>) -> Unit,
        attempt: Int = 1,
    ) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(stream.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(upstreamFactory)
            .setRenderersFactory(DefaultRenderersFactory(appContext))
            .create(mediaItem)
        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                val result = runCatching {
                    val trackParameters = TrackSelectionParameters.Builder()
                        .setForceHighestSupportedBitrate(true)
                        .apply {
                            quality.maxHeight?.let { height ->
                                setMaxVideoSize(Int.MAX_VALUE, height)
                            }
                        }
                        .build()
                    for (periodIndex in 0 until helper.periodCount) {
                        helper.replaceTrackSelections(
                            periodIndex,
                            categoryAudioParameters(
                                parameters = trackParameters,
                                tracks = helper.getTracks(periodIndex),
                                category = preparedMetadata.category,
                            ),
                        )
                    }
                    // The chosen resolution is a ceiling, not a promise: a source offering only a
                    // single 1080p rendition still yields 1080p. Record what was actually selected,
                    // otherwise the library and the exported filename both claim a resolution the
                    // file does not have.
                    val resolvedMetadata = selectedVideoHeight(helper)
                        ?.let { preparedMetadata.copy(quality = "${it}p") }
                        ?: preparedMetadata
                    // After the height is read, before the request is built. The text selections
                    // disable video to keep its keys out of the request, and asking what video was
                    // selected while that is the most recent word on the subject invites an answer
                    // about the wrong pass.
                    val textTracks = addAllTextTrackSelections(helper, trackParameters)
                    metadataById[id] = resolvedMetadata
                    val data = json.encodeToString(resolvedMetadata).encodeToByteArray()
                    val request = helper.getDownloadRequest(id, data)
                    requestByUri[request.uri.toString()] = request
                    DownloadService.sendAddDownload(
                        appContext,
                        EpisodeDownloadService::class.java,
                        request,
                        false,
                    )
                    DiagnosticsLog.event(
                        "Episode download queued id=$id provider=${preparedMetadata.provider} " +
                            "host=${request.uri.host ?: "unknown"} tracks=${request.streamKeys.size} " +
                            "subtitles=${preparedMetadata.subtitles.size} textTracks=$textTracks " +
                            "quality=${quality.label}",
                    )
                    downloadSubtitleFiles(id, preparedMetadata)
                }
                helper.release()
                finishPreparing(id)
                onResult(result)
            }

            override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                helper.release()
                if (attempt < MAX_PREPARE_ATTEMPTS) {
                    DiagnosticsLog.throwable(
                        "Episode download prepare retry id=$id attempt=$attempt",
                        e,
                    )
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        prepareHlsDownload(
                            id,
                            preparedMetadata,
                            stream,
                            quality,
                            onResult,
                            attempt + 1,
                        )
                    }
                } else {
                    finishPreparing(id)
                    DiagnosticsLog.throwable("Episode download prepare failed id=$id", e)
                    onResult(
                        Result.failure(
                            IOException(
                                "The source did not return a valid HLS playlist. Try another provider.",
                                e,
                            ),
                        ),
                    )
                }
            }
        })
    }

    fun remove(context: Context, id: String) {
        initialize(context)
        DownloadService.sendRemoveDownload(
            appContext,
            EpisodeDownloadService::class.java,
            id,
            false,
        )
        DiagnosticsLog.event("Episode download remove requested id=$id")
    }

    /** Stops one persistent Media3 download without discarding its cached bytes. */
    fun pause(context: Context, id: String) {
        initialize(context)
        DownloadService.sendSetStopReason(
            appContext,
            EpisodeDownloadService::class.java,
            id,
            USER_PAUSED_STOP_REASON,
            false,
        )
        DiagnosticsLog.event("Episode download paused id=$id")
    }

    /** Clears the user stop reason so Media3 continues the same request from its cached bytes. */
    fun resume(context: Context, id: String) {
        initialize(context)
        DownloadService.sendSetStopReason(
            appContext,
            EpisodeDownloadService::class.java,
            id,
            Download.STOP_REASON_NONE,
            false,
        )
        DiagnosticsLog.event("Episode download resumed id=$id")
    }

    /**
     * Puts a failed or stalled download back in the queue, keeping every byte already fetched.
     *
     * Re-sending the original request rather than only clearing the stop reason is what revives a
     * FAILED one: Media3 exhausts its retry budget and leaves the download parked, and only adding
     * it again returns it to QUEUED. The request is the one the download already carries, so the
     * partially filled cache is reused and this resumes rather than starting over.
     */
    fun retry(context: Context, id: String) {
        initialize(context)
        val request = manager.currentDownloads.firstOrNull { it.request.id == id }?.request
            ?: manager.downloadIndex.getDownload(id)?.request
        if (request == null) {
            DiagnosticsLog.event("Episode download retry found no request id=$id")
            return
        }
        DownloadService.sendAddDownload(
            appContext,
            EpisodeDownloadService::class.java,
            request,
            false,
        )
        DownloadService.sendSetStopReason(
            appContext,
            EpisodeDownloadService::class.java,
            id,
            Download.STOP_REASON_NONE,
            false,
        )
        DiagnosticsLog.event("Episode download retry requested id=$id")
    }

    fun localSubtitles(
        context: Context,
        metadata: EpisodeDownloadMetadata,
    ): List<SubtitleItem> {
        initialize(context)
        val directory = subtitleDirectory()
        return metadata.subtitles.mapNotNull { subtitle ->
            val fileName = subtitle.fileName ?: return@mapNotNull null
            val file = File(directory, fileName).takeIf(File::isFile) ?: return@mapNotNull null
            SubtitleItem(
                url = Uri.fromFile(file).toString(),
                label = subtitle.label,
                language = subtitle.language,
                mimeType = subtitle.mimeType,
                referer = subtitle.referer,
                headers = subtitle.headers,
            )
        }
    }

    /** The on-disk subtitle files for a download, paired with their entry, for sidecar export. */
    internal fun subtitleFiles(
        context: Context,
        metadata: EpisodeDownloadMetadata,
    ): List<Pair<EpisodeDownloadSubtitle, File>> {
        initialize(context)
        val directory = subtitleDirectory()
        return metadata.subtitles.mapNotNull { subtitle ->
            val fileName = subtitle.fileName ?: return@mapNotNull null
            val file = File(directory, fileName).takeIf(File::isFile) ?: return@mapNotNull null
            subtitle to file
        }
    }

    /** Filename-safe tag for a subtitle sidecar, e.g. `Episode 3 [1080p].en.vtt`. */
    internal fun subtitleTag(subtitle: EpisodeDownloadSubtitle): String {
        val raw = subtitle.language.takeIf(String::isNotBlank)
            ?: subtitle.label.takeIf(String::isNotBlank)
            ?: "sub"
        return safeFilePart(raw).replace(' ', '-')
    }

    /**
     * Reads the download cache first and only falls back to the network for anything missing, with
     * the provider's header profile still applied. That is what lets an export run entirely
     * offline once the download itself is complete.
     */
    internal fun exportDataSourceFactory(context: Context): DataSource.Factory {
        initialize(context)
        return readOnlyPlaybackFactory(context, upstreamFactory)
    }

    /**
     * Reuses the persisted adaptive stream keys when the normal player opens a downloaded URL.
     * This prevents the player from requesting an undownloaded rendition while offline.
     */
    fun buildMediaItem(
        context: Context,
        uri: String,
        builder: MediaItem.Builder,
    ): MediaItem {
        initialize(context)
        val request = requestByUri[uri]
        return request
            ?.toMediaItem(builder)
            ?.buildUpon()
            ?.setMediaId(uri)
            ?.build()
            ?: builder.build()
    }

    /** Height of the video track Media3 actually selected, once track selections are applied. */
    private fun selectedVideoHeight(helper: DownloadHelper): Int? =
        (0 until helper.periodCount)
            .asSequence()
            .flatMap { helper.getTracks(it).groups.asSequence() }
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length)
                    .asSequence()
                    .filter(group::isTrackSelected)
                    .map { group.getTrackFormat(it).height }
            }
            .filter { it != Format.NO_VALUE }
            .maxOrNull()

    private fun finishPreparing(id: String) {
        _preparingIds.value -= id
        if (preparingDownloadId == id) preparingDownloadId = null
    }

    private fun requestHeadersFor(uri: Uri): Map<String, String> {
        val exactId = manifestIdByUri[uri.toString()]
        val sameHostId = uri.host?.let(manifestIdByHost::get)
        val metadata = metadataById[exactId ?: activeDownloadId ?: preparingDownloadId ?: sameHostId]
            ?: return emptyMap()
        return requestHeaders(metadata)
    }

    /**
     * The playlist key of the download currently being fetched, or null when none needs one.
     *
     * Answered from the download in flight rather than per URI, because the decoders that read it
     * are handed a `() -> String?` and never see the request. That is sound here for the reason
     * stated on this object: [maxParallelDownloads] is 1 and downloads are deliberately serialized,
     * so there is one unambiguous answer — the same assumption [requestHeadersFor] already leans on
     * for the referer, where being wrong costs a 403 rather than an unreadable playlist.
     *
     * Null during an export, which is correct and not a gap: a completed download has its playlists
     * in the cache already decoded, so the export never reaches upstream for one.
     */
    private fun activePlaylistKey(): String? {
        val id = activeDownloadId ?: preparingDownloadId ?: return null
        return metadataById[id]?.playlistKey?.takeIf(String::isNotBlank)
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            register(download)
            if (download.state == Download.STATE_DOWNLOADING) {
                activeDownloadId = download.request.id
            } else if (activeDownloadId == download.request.id) {
                activeDownloadId = null
            }
            finalException?.let {
                DiagnosticsLog.throwable("Episode download failed id=${download.request.id}", it)
            }
            // Asked first: a failure being answered by another source is not news the viewer needs
            // a notification about, and it would be contradicted a few seconds later anyway.
            if (!considerAnotherProvider(download)) notifyTerminalState(download)
            refreshDownloads()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            providerFallbacksStarted.remove(download.request.id)
            subtitleJobs.remove(download.request.id)?.cancel()
            val metadata = metadataById[download.request.id] ?: decodeMetadata(download.request)
            metadata?.let(::deleteSubtitleFiles)
            metadataById.remove(download.request.id)
            manifestIdByUri.entries.removeAll { it.value == download.request.id }
            manifestIdByHost.entries.removeAll { it.value == download.request.id }
            requestByUri.remove(download.request.uri.toString())
            if (activeDownloadId == download.request.id) activeDownloadId = null
            refreshDownloads()
        }
    }

    /**
     * Hands an episode to a different source when the one it is on cannot finish it.
     *
     * A dead end used to be the end of it: Media3 parks a download in FAILED, the watchdog parks a
     * stalled one in STOPPED, and both simply sat there until somebody noticed and pressed Retry —
     * which puts the *same* request back on the *same* source. That is the wrong move when the
     * source is the problem, and it usually is. The CDN behind one Re:Zero episode held a segment
     * it would answer but never finish sending; two independent attempts both died on it after
     * exactly 140,671,940 bytes, and pressing Retry could only ever die there a third time.
     *
     * The cached bytes go with the old source. They are keyed to its segment URLs and no other
     * provider can read them, so there is nothing to salvage and keeping them only means the next
     * attempt inherits a cache it must ignore. [MAX_PROVIDER_ATTEMPTS] bounds how much of the
     * viewer's data this is allowed to spend before the download is left alone for them to judge.
     *
     * Returns whether a replacement is being sought, so the caller can hold back the notification
     * that says this episode failed.
     */
    private fun considerAnotherProvider(download: Download): Boolean {
        val parkedByWatchdog = download.state == Download.STATE_STOPPED &&
            download.stopReason == STALLED_DOWNLOAD_STOP_REASON
        if (download.state != Download.STATE_FAILED && !parkedByWatchdog) return false
        val id = download.request.id
        val metadata = metadataById[id] ?: decodeMetadata(download.request) ?: return false
        // Downloads queued before this field existed carry no list; the source named on the
        // download is the one that just failed either way.
        val attempted = metadata.attemptedProviders.ifEmpty { listOf(metadata.provider) }
        if (shouldResolveAgainOnSameProvider(metadata)) {
            if (!providerFallbacksStarted.add(id)) return true
            DiagnosticsLog.event("Episode download resolving again id=$id provider=${metadata.provider}")
            BulkEpisodeDownloads.retryOnAnotherProvider(
                appContext,
                metadata,
                excludedProviders = attempted.toSet() - metadata.provider,
                preferredProvider = metadata.provider,
                freshResolves = metadata.freshResolves + 1,
            )
            return true
        }
        if (attempted.size >= MAX_PROVIDER_ATTEMPTS) {
            DiagnosticsLog.event("Episode download out of sources id=$id tried=$attempted")
            return false
        }
        // onDownloadChanged repeats for a download already in its terminal state, and the search
        // for a replacement takes a provider round trip; the guard holds for the length of it.
        if (!providerFallbacksStarted.add(id)) return true
        DiagnosticsLog.event("Episode download switching source id=$id tried=$attempted")
        BulkEpisodeDownloads.retryOnAnotherProvider(appContext, metadata, attempted.toSet())
        return true
    }

    /**
     * Whether a dead transfer should first be resolved again on the server it was already on.
     *
     * For a server whose stream URLs expire, a transfer that dies after a pause or a long queue
     * most likely died of the URL, not of the server: Media3 re-reads the stored playlist URL on
     * every start, and Kartoons' proxy answers 404 once it lapses. Switching servers there would
     * replace the Hindi audio the viewer chose with whatever the next server speaks, so the same
     * server is asked for a fresh URL first — once, so a server that is genuinely failing still
     * hands the episode on.
     */
    internal fun shouldResolveAgainOnSameProvider(metadata: EpisodeDownloadMetadata): Boolean =
        ProviderCatalog.hasShortLivedStreamUrls(metadata.provider) && metadata.freshResolves < MAX_FRESH_RESOLVES

    /** Lets the queue hand back a switch it could not carry out, so the download can be judged again. */
    internal fun releaseProviderFallback(id: String) {
        providerFallbacksStarted.remove(id)
    }

    /**
     * Posts a notification when a download reaches a terminal state.
     *
     * The service's foreground notification disappears with the service, so without this a
     * download that finishes while the app is in the background leaves nothing behind and the
     * viewer has to open the downloads page to find out whether it worked.
     *
     * One notification covers the whole batch ([DownloadBatchNotices]): a single episode reads as
     * before, several become "N episodes downloaded" with a line each.
     */
    private fun notifyTerminalState(download: Download) {
        val failed = when (download.state) {
            Download.STATE_COMPLETED -> false
            Download.STATE_FAILED -> true
            else -> return
        }
        val label = decodeMetadata(download.request)
            ?.let { "${it.seriesTitle} · Episode ${it.episodeNumber}" }
        val id = download.request.id
        val queueIdle = manager.currentDownloads.none {
            it.request.id != id && (
                it.state == Download.STATE_QUEUED ||
                    it.state == Download.STATE_DOWNLOADING ||
                    it.state == Download.STATE_RESTARTING
                )
        }
        val batch = batchNotices.record(
            id,
            DownloadBatchNotice(label ?: id, failed),
            queueIdle,
        )
        // Posted once the queue is done, not per episode. While anything is still downloading the
        // progress notification is already in the shade, and a second notification from this app
        // is what makes Samsung's System UI bundle them into an auto-group that it rebuilds — every
        // row, blur and all — on each progress tick: 0.7–1.5s frames with the shade open.
        if (!queueIdle) return
        val helper = DownloadNotificationHelper(appContext, DOWNLOAD_CHANNEL_ID)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Routes.EXTRA_ROUTE, Routes.DOWNLOADS)
        }
        val pending = PendingIntent.getActivity(
            appContext,
            BATCH_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = if (batch.size == 1) {
            if (failed) {
                helper.buildDownloadFailedNotification(appContext, R.drawable.ic_notification, pending, label)
            } else {
                helper.buildDownloadCompletedNotification(appContext, R.drawable.ic_notification, pending, label)
            }
        } else {
            batchNotification(batch, pending)
        }
        runCatching {
            NotificationUtil.setNotification(appContext, BATCH_NOTIFICATION_ID, notification)
        }.onFailure { DiagnosticsLog.throwable("Episode download notification failed", it) }
    }

    private fun batchNotification(
        batch: List<DownloadBatchNotice>,
        pending: PendingIntent,
    ): android.app.Notification {
        val resources = appContext.resources
        val done = batch.count { !it.failed }
        val failedCount = batch.size - done
        val title = when {
            failedCount == 0 ->
                resources.getQuantityString(R.plurals.download_batch_completed, done, done)
            done == 0 ->
                resources.getQuantityString(R.plurals.download_batch_failed, failedCount, failedCount)
            else -> appContext.getString(R.string.download_batch_mixed, done, failedCount)
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        batch.forEach { notice ->
            style.addLine(
                if (notice.failed) {
                    appContext.getString(R.string.download_batch_line_failed, notice.label)
                } else {
                    notice.label
                },
            )
        }
        return NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(batch.first().label)
            .setStyle(style)
            .setNumber(batch.size)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
    }

    private fun refreshDownloads() {
        if (!initialized) return
        scope.launch {
            // The index row is not the live figure. DownloadManager writes to the index when a
            // download changes state and keeps progress on its own objects in between, so polling
            // the index alone re-read the same early value for the whole transfer — a viewer
            // watched the bar sit at 4% on both phone and television while the file downloaded
            // normally and only jumped once it completed. currentDownloads carries the running
            // percentage, and it must be read on the thread the manager was built on.
            val live = withContext(Dispatchers.Main) {
                runCatching { manager.currentDownloads.associateBy { it.request.id } }
                    .getOrDefault(emptyMap())
            }
            val loaded = runCatching {
                buildList {
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            val download = live[cursor.download.request.id] ?: cursor.download
                            register(download)
                            download.toEpisodeDownload()?.let(::add)
                        }
                    }
                }.sortedByDescending(EpisodeDownload::updatedAtMs)
            }.onFailure {
                DiagnosticsLog.throwable("Episode download index query failed", it)
            }.getOrDefault(_downloads.value)
            _downloads.value = loaded
            if (loaded.any { it.state == EpisodeDownloadState.DOWNLOADING }) {
                startProgressPoller()
            } else {
                stopProgressPoller()
            }
        }
    }

    private fun register(download: Download) {
        val metadata = decodeMetadata(download.request) ?: return
        metadataById[download.request.id] = metadata
        manifestIdByUri[download.request.uri.toString()] = download.request.id
        download.request.uri.host?.let { manifestIdByHost[it] = download.request.id }
        requestByUri[download.request.uri.toString()] = download.request
    }

    private fun Download.toEpisodeDownload(): EpisodeDownload? {
        val metadata = decodeMetadata(request) ?: return null
        val rawPercent = percentDownloaded
        val subtitleDownloaded = metadata.subtitles.count { subtitle ->
            subtitle.fileName?.let { File(subtitleDirectory(), it).isFile } == true
        }
        return EpisodeDownload(
            id = request.id,
            uri = request.uri.toString(),
            metadata = metadata,
            state = state.toEpisodeDownloadState(),
            percent = when {
                state == Download.STATE_COMPLETED -> 100f
                rawPercent.isFinite() && rawPercent >= 0f -> rawPercent.coerceIn(0f, 100f)
                else -> null
            },
            bytesDownloaded = bytesDownloaded,
            contentLength = contentLength.takeIf { it > 0 },
            updatedAtMs = updateTimeMs,
            subtitleTotal = metadata.subtitles.size,
            subtitleDownloaded = subtitleDownloaded,
            subtitlesPending = subtitleJobs.containsKey(request.id),
            stopReason = stopReason,
        )
    }

    private fun decodeMetadata(request: DownloadRequest): EpisodeDownloadMetadata? =
        runCatching {
            json.decodeFromString<EpisodeDownloadMetadata>(request.data.decodeToString())
        }.onFailure {
            DiagnosticsLog.throwable("Episode download metadata invalid id=${request.id}", it)
        }.getOrNull()

    private fun downloadSubtitleFiles(id: String, metadata: EpisodeDownloadMetadata) {
        if (metadata.subtitles.isEmpty()) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val directory = subtitleDirectory().apply { mkdirs() }
                metadata.subtitles.forEach { subtitle ->
                    val fileName = subtitle.fileName ?: return@forEach
                    val target = File(directory, fileName)
                    val temporary = File(directory, "$fileName.part")
                    runCatching {
                        val profiles = subtitleRequestHeaderProfiles(metadata, subtitle, userAgent)
                        var completed = false
                        profiles.forEachIndexed { attempt, headers ->
                            if (completed) return@forEachIndexed
                            val request = Request.Builder()
                                .url(subtitle.url)
                                .apply { headers.forEach { (name, value) -> header(name, value) } }
                                .build()
                            subtitleClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    val canRetry = response.code in setOf(401, 403) && attempt < profiles.lastIndex
                                    if (canRetry) {
                                        DiagnosticsLog.event(
                                            "Episode subtitle retrying minimal headers id=$id " +
                                                "language=${subtitle.language} http=${response.code}",
                                        )
                                        return@use
                                    }
                                    throw IOException("Subtitle HTTP ${response.code}")
                                }
                                val body = response.body ?: throw IOException("Subtitle response was empty")
                                temporary.outputStream().use { output ->
                                    body.byteStream().use { input -> input.copyTo(output) }
                                }
                                completed = true
                            }
                        }
                        if (!completed) throw IOException("Subtitle request was rejected")
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (target.exists() && !target.delete()) {
                            throw IOException("Could not replace subtitle file")
                        }
                        if (!temporary.renameTo(target)) {
                            temporary.copyTo(target, overwrite = true)
                            temporary.delete()
                        }
                        DiagnosticsLog.event(
                            "Episode subtitle downloaded id=$id language=${subtitle.language} " +
                                "label=${subtitle.label.take(48)}",
                        )
                    }.onFailure { error ->
                        temporary.delete()
                        if (error is CancellationException) throw error
                        // DL-05 has no action on purpose: the episode itself is downloading fine and
                        // the viewer can still pick a track in the player. It is logged under a code
                        // so "my downloads have no subtitles" can be counted rather than guessed at.
                        AnililiError.DOWNLOAD_SUBTITLE_WRITE_FAILED.report(
                            cause = error,
                            attributes = mapOf(
                                "downloadId" to id,
                                "language" to subtitle.language,
                                "label" to subtitle.label.take(48),
                            ),
                        )
                    }
                }
            } finally {
                kotlin.coroutines.coroutineContext[Job]?.let { job -> subtitleJobs.remove(id, job) }
                refreshDownloads()
            }
        }
        subtitleJobs.put(id, job)?.cancel()
        refreshDownloads()
        job.start()
    }

    private fun requestHeaders(metadata: EpisodeDownloadMetadata): Map<String, String> {
        return mediaRequestHeaders(
            referer = metadata.referer,
            userAgent = userAgent,
            extraHeaders = metadata.headers,
        )
    }

    private fun StreamItem.isDirectFile(): Boolean {
        if (isHls || isEmbed) return false
        val normalizedType = type.trim().lowercase()
        if (normalizedType in setOf("dash", "mpd", "webrtc")) return false
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
        return !path.endsWith(".mpd")
    }

    private data class DirectFileFormat(val extension: String, val mimeType: String)

    private fun directFileFormat(stream: StreamItem): DirectFileFormat {
        val path = runCatching { Uri.parse(stream.url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.endsWith(".webm") || stream.type.equals("webm", true) ->
                DirectFileFormat("webm", "video/webm")
            path.endsWith(".mkv") || stream.type.equals("mkv", true) ->
                DirectFileFormat("mkv", "video/x-matroska")
            else -> DirectFileFormat("mp4", MimeTypes.VIDEO_MP4)
        }
    }

    private fun safeFilePart(value: String): String =
        value.trim()
            .replace(Regex.cached("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .replace(Regex.cached("""\s+"""), " ")
            .trim('.', ' ')
            .ifBlank { "Episode" }

    /**
     * Each series gets its own folder under Downloads/Anilili. A long-runner otherwise buries
     * everything else in the same flat list, and the file manager has no way to group them.
     */
    internal fun deviceSeriesFolder(metadata: EpisodeDownloadMetadata): String =
        safeFilePart(metadata.seriesTitle).take(80).trim()

    internal fun deviceRelativePath(seriesFolder: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DOWNLOAD_SUBDIRECTORY/$seriesFolder/"

    internal fun deviceFileStem(metadata: EpisodeDownloadMetadata): String = buildString {
        val qualityLabel = metadata.quality
        append("Episode ")
        append(safeFilePart(metadata.episodeNumber))
        metadata.episodeTitle
            ?.takeIf(String::isNotBlank)
            ?.let { append(" - ").append(safeFilePart(it)) }
        qualityLabel?.takeIf { it.isNotBlank() && !it.equals("auto", true) }
            ?.let { append(" [").append(safeFilePart(it)).append(']') }
    }.take(180).trim()

    private fun deleteSubtitleFiles(metadata: EpisodeDownloadMetadata) {
        val directory = subtitleDirectory()
        metadata.subtitles.forEach { subtitle ->
            subtitle.fileName?.let { fileName ->
                File(directory, fileName).delete()
                File(directory, "$fileName.part").delete()
            }
        }
    }

    private fun subtitleDirectory(): File =
        File(appContext.filesDir, SUBTITLE_DIRECTORY)

    private fun subtitleFileName(
        id: String,
        index: Int,
        url: String,
        mimeType: String?,
    ): String {
        val extension = Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_SUBTITLE_EXTENSIONS }
            ?: subtitleExtensionFor(mimeType)
            ?: "vtt"
        return "${id.hashCode().toUInt().toString(16)}-$index.$extension"
    }

    private fun startProgressPoller() {
        if (progressPoller?.isActive == true) return
        progressPoller = scope.launch {
            while (isActive) {
                delay(PROGRESS_REFRESH_MS)
                refreshDownloads()
                if (_downloads.value.none { it.state == EpisodeDownloadState.DOWNLOADING }) break
            }
        }
    }

    private fun stopProgressPoller() {
        progressPoller?.cancel()
        progressPoller = null
    }

    private fun Int.toEpisodeDownloadState(): EpisodeDownloadState = when (this) {
        Download.STATE_DOWNLOADING -> EpisodeDownloadState.DOWNLOADING
        Download.STATE_COMPLETED -> EpisodeDownloadState.COMPLETED
        Download.STATE_FAILED -> EpisodeDownloadState.FAILED
        Download.STATE_REMOVING -> EpisodeDownloadState.REMOVING
        Download.STATE_RESTARTING -> EpisodeDownloadState.RESTARTING
        Download.STATE_STOPPED -> EpisodeDownloadState.STOPPED
        else -> EpisodeDownloadState.QUEUED
    }

    private const val DOWNLOAD_CHANNEL_ID = "episode_downloads"
    private const val BATCH_NOTIFICATION_ID = 7_400
    private const val DOWNLOAD_DIRECTORY = "episode-downloads"
    private const val SUBTITLE_DIRECTORY = "episode-download-subtitles"
    internal const val PUBLIC_DOWNLOAD_SUBDIRECTORY = "Anilili"
    private const val PROGRESS_REFRESH_MS = 1_000L
    private const val USER_PAUSED_STOP_REASON = 1
    internal const val SYSTEM_TIMEOUT_STOP_REASON = 2
    internal const val STALLED_DOWNLOAD_STOP_REASON = 3
    private const val SEGMENT_DOWNLOAD_THREADS = 4
    private const val MAX_PREPARE_ATTEMPTS = 2

    /**
     * How many sources one episode may be handed to before it is left alone.
     *
     * Each switch throws away whatever the previous source managed to cache, so this is a budget in
     * the viewer's data as much as in their patience. Three is the original plus two alternatives.
     */
    internal const val MAX_PROVIDER_ATTEMPTS = 3

    /** See [shouldResolveAgainOnSameProvider]. */
    internal const val MAX_FRESH_RESOLVES = 1
    private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("vtt", "srt", "ass", "ssa", "ttml", "xml")
}

/**
 * [parameters] with the audio track the sub/dub category asks for pinned, when [tracks] offers a
 * choice at all.
 *
 * A download keeps one audio rendition and nothing here ever said which one, so Media3 fell back to
 * whichever the manifest flags DEFAULT — the Japanese track on every multi-audio server (see
 * `ProviderCatalog.multiAudioProviders`, where sub and dub are one file rather than a stream each).
 * Playback has answered this question since those servers were added:
 * `PlayerSurface.applyCategoryAudioPreference` selects the English track once the stream publishes
 * its tracks. A download is prepared with no player, so it never did, and a dub came back Japanese.
 *
 * Offline that is worse than the same fault on a live stream: the English track was not merely left
 * unselected, it was never written to disk, so the audio menu has nothing to switch to and the
 * viewer cannot recover the dub they asked for without downloading the episode again.
 *
 * Shares [categoryAudioDecision] with the player rather than asking for a language code, because
 * these providers label the tracks ("Native", "English") far more reliably than they tag them, and
 * two rules that drifted apart would write one language into the file and have the player go
 * looking for the other.
 *
 * The viewer's standing audio-language choice is the one input the player passes and this does
 * not: it only ever ranks one dub above another, and a download has no settings to read out here.
 * So a dub is written in English where a stream carries several, which is the same track the
 * player would have chosen for anyone who never named a language.
 */
@OptIn(UnstableApi::class)
internal fun categoryAudioParameters(
    parameters: TrackSelectionParameters,
    tracks: Tracks,
    category: String,
): TrackSelectionParameters {
    val options = tracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { index ->
                    val format = group.getTrackFormat(index)
                    DownloadAudioOption(
                        trackGroup = group.mediaTrackGroup,
                        trackIndex = index,
                        name = listOfNotNull(format.label, format.language)
                            .joinToString(" ")
                            .ifBlank { "Audio" },
                    )
                }
        }
    // A single-audio stream answers NotReady here, exactly as it does in the player. There is
    // nothing to choose between, so the parameters are handed back untouched and Media3 keeps the
    // only track on offer.
    val decision = categoryAudioDecision(
        names = options.map(DownloadAudioOption::name),
        selectedIndex = null,
        wantsDub = category.equals("dub", ignoreCase = true),
    )
    val chosen = (decision as? CategoryAudioDecision.Select)?.let { options[it.index] }
        ?: return parameters
    DiagnosticsLog.event(
        "Episode download audio selected category=$category name=${chosen.name.take(80)}",
    )
    return parameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(chosen.trackGroup, chosen.trackIndex))
        .build()
}

/**
 * Selects every text rendition in the manifest for download, and answers how many.
 *
 * Nothing here is a preference. Media3 scores text tracks the way a *player* does — a rendition
 * that matches no preferred language and carries no DEFAULT/FORCED flag scores zero and is not
 * selected — so a download prepared with only video and audio parameters emits no subtitle stream
 * keys at all, and the segments are never written to the cache. That is why soft-sub servers whose
 * subtitles ride inside the HLS manifest, rather than arriving as sidecar VTT URLs in
 * `sources.subtitles`, downloaded as raw unsubtitled video: the sidecar path had nothing to fetch
 * and the track path never asked. The audio selection right above this has carried a comment about
 * exactly this hazard ("if it is unselected, it was never written to disk") since it was written;
 * text simply never got the same treatment.
 *
 * A download is one-shot and text renditions are tiny next to video, so every track is taken rather
 * than the best-scoring one. Narrowing here would make the offline subtitle menu smaller than the
 * live one for no saving worth having.
 *
 * Each track goes in as its own additive selection because [DownloadHelper.replaceTrackSelections]
 * resolves one selection per renderer, and there is a single text renderer: asking for all of them
 * in one parameters object would still yield one track.
 *
 * Capability is deliberately not required ([Tracks.Group.isTrackSupported] is asked with
 * `allowExceedsCapabilities`). A device that cannot render a track still exports it — the flattened
 * VTT sidecar beside the MP4 is read by whatever plays it next, not by this device's decoders.
 */
@OptIn(UnstableApi::class)
internal fun addAllTextTrackSelections(
    helper: DownloadHelper,
    parameters: TrackSelectionParameters,
): Int {
    var selected = 0
    for (periodIndex in 0 until helper.periodCount) {
        textTrackSelections(helper.getTracks(periodIndex), parameters).forEach { selection ->
            helper.addTrackSelection(periodIndex, selection)
            selected++
        }
    }
    return selected
}

/** One parameters object per text track in [tracks] — see [addAllTextTrackSelections]. */
@OptIn(UnstableApi::class)
internal fun textTrackSelections(
    tracks: Tracks,
    parameters: TrackSelectionParameters,
): List<TrackSelectionParameters> =
    tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it, /* allowExceedsCapabilities= */ true) }
                .map { index ->
                    parameters.buildUpon()
                        // The video and audio tracks were chosen by the pass above. Leaving them
                        // enabled here would re-select them once per subtitle track and repeat
                        // their stream keys in the request.
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                }
        }

/** One selectable audio track on a manifest being prepared for download. */
private class DownloadAudioOption(
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val name: String,
)

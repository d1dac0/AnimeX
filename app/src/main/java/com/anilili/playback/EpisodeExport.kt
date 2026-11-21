package com.anilili.playback

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiException
import com.anilili.errors.AnililiFailure
import com.anilili.errors.anililiFailure
import com.anilili.errors.report
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class EpisodeExportState { PENDING, RUNNING, COMPLETED, FAILED }

data class EpisodeExportStatus(
    val downloadId: String,
    val state: EpisodeExportState,
    /** Null until the transmuxer can estimate; the manifest has to be read first. */
    val percent: Int? = null,
    val fileName: String? = null,
    /**
     * The catalogued failure, not a sentence.
     *
     * This used to be a `String` written at the throw site and printed into the downloads list
     * verbatim, which is how a series with eleven failed exports became eleven copies of a
     * fifteen-word paragraph. A [AnililiFailure] lets the row show one short line and a code while
     * the explanation waits behind the details sheet.
     */
    val failure: AnililiFailure? = null,
)

/**
 * [mimeType] is kept because [uri] is a MediaStore/SAF `content://` URI with no extension, and a
 * player guessing from the URL falls back to WebVTT — an SRT or ASS sidecar (ReAnime's) then fails
 * to parse and the exported episode plays with no subtitles at all. Null on records written before
 * it existed; [exportedSubtitleMimeType] recovers those from the file's display name.
 */
@Serializable
data class ExportedSubtitle(
    val uri: String,
    val label: String,
    val language: String,
    val mimeType: String? = null,
)

/**
 * An episode that now lives as an MP4 in the device's Downloads folder.
 *
 * This is the app's own record, not Media3's. Once an episode is exported the segment cache is
 * usually dropped, so the Media3 download index no longer knows about it — this record is what
 * keeps it in the offline library, and its [uri] is what the player opens.
 */
@Serializable
data class ExportedEpisode(
    val downloadId: String,
    val uri: String,
    val fileName: String,
    val metadata: EpisodeDownloadMetadata,
    val sizeBytes: Long = 0,
    val exportedAtMs: Long = 0,
    val subtitles: List<ExportedSubtitle> = emptyList(),
)

/** An export stopped because a playback session opened; it is retried, never reported. */
internal class ExportYieldedToPlayback : Exception("Export stopped for playback")

/**
 * Turns a completed HLS download into a single MP4 in the device's public Downloads folder.
 *
 * An HLS download is a manifest plus a few hundred segments in Media3's cache — useful to this app
 * and nothing else. Exporting rewraps those segments into one MP4 container, which is what a file
 * manager, a USB stick, or any other player can actually open.
 *
 * Two properties make this cheap. The segments are already on disk, so the export never touches the
 * network and works offline; and with no effects and no MIME override, Transformer *transmuxes* —
 * it copies the encoded samples across untouched rather than decoding and re-encoding them. The
 * cost is close to a large file copy rather than a real conversion.
 *
 * Reading back through the download cache (rather than the origin) also inherits everything the
 * download already solved: the right rendition via the persisted stream keys, the provider's
 * Referer/Origin profile, and AES-128 segments that the HLS source has already decrypted.
 */
@OptIn(UnstableApi::class)
object EpisodeExport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _statuses = MutableStateFlow<Map<String, EpisodeExportStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, EpisodeExportStatus>> = _statuses.asStateFlow()
    private val _exported = MutableStateFlow<List<ExportedEpisode>>(emptyList())
    val exported: StateFlow<List<ExportedEpisode>> = _exported.asStateFlow()

    @Volatile private var initialized = false
    private var observer: Job? = null
    private var playbackWatcher: Job? = null

    /** Android 10+ uses MediaStore; Android 9 and earlier use their legacy permission path. */
    val isSupported: Boolean get() = DeviceDownloadAccess.isSupported

    fun statuses(context: Context): StateFlow<Map<String, EpisodeExportStatus>> {
        initialize(context)
        return statuses
    }

    fun exported(context: Context): StateFlow<List<ExportedEpisode>> {
        initialize(context)
        return exported
    }

    /** Removes the MP4 and its sidecars from Downloads, and forgets the episode. */
    fun deleteExported(context: Context, downloadId: String) {
        val app = context.applicationContext
        scope.launch {
            val record = _exported.value.firstOrNull { it.downloadId == downloadId }
                ?: return@launch
            val deletion = runCatching {
                (listOf(record.uri) + record.subtitles.map { it.uri }).forEach { uri ->
                    deleteUri(app, uri)
                }
            }.onFailure {
                DiagnosticsLog.throwable("Exported episode delete failed id=$downloadId", it)
            }
            if (deletion.isFailure) return@launch
            saveExported(app, _exported.value.filterNot { it.downloadId == downloadId })
            _statuses.value -= downloadId
            DiagnosticsLog.event("Exported episode deleted id=$downloadId")
        }
    }

    fun initialize(context: Context) {
        if (initialized || !isSupported) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            // One observer covers both cases: an export asked for while the download was still
            // running, and one left half-finished by a process death. Both surface here as a
            // completed download that is still marked pending.
            observer = scope.launch {
                restoreExported(app)
                EpisodeDownloads.downloads(app).collect { downloads ->
                    val pending = pendingIds(app)
                    downloads.asSequence()
                        .filter { it.isComplete && it.id in pending }
                        .filter { _statuses.value[it.id]?.state != EpisodeExportState.RUNNING }
                        .forEach { EpisodeExportWorker.enqueue(app, it.id) }
                }
            }
            // An export deferred because the viewer was watching, or because downloads were still
            // running, keeps its place in WorkManager's backoff, which grows with every attempt —
            // so by the time the deferral ended, the next try could be an hour away. Re-arming
            // them the moment both are over replaces that wait with a fresh attempt.
            //
            // "Over" for playback is the session closing and staying closed for a quiet window,
            // not isPlaying turning false: that happens on every pause, rebuffer and episode start,
            // and each one used to launch a transmux that then ran on through resumed playback.
            // collectLatest drops the wait the moment a session reopens.
            playbackWatcher = scope.launch {
                combine(
                    PlaybackStatus.sessionActive,
                    EpisodeDownloads.downloads(app).map(::queueActive).distinctUntilChanged(),
                ) { session, downloading -> session || downloading }
                    .distinctUntilChanged()
                    .collectLatest { busy ->
                        if (busy) return@collectLatest
                        val quietWaitMs = PlaybackStatus.quietRemainingMs() ?: return@collectLatest
                        delay(quietWaitMs)
                        val pending = pendingIds(app)
                        if (pending.isEmpty()) return@collectLatest
                        EpisodeDownloads.downloads(app).value
                            .asSequence()
                            .filter { it.isComplete && it.id in pending }
                            .filter { _statuses.value[it.id]?.state != EpisodeExportState.RUNNING }
                            .forEach { EpisodeExportWorker.enqueue(app, it.id, restart = true) }
                    }
            }
        }
    }

    /** Whether the download queue still has work, as the export deferral sees it. */
    internal fun downloadQueueActive(context: Context): Boolean =
        queueActive(EpisodeDownloads.downloads(context.applicationContext).value)

    private fun queueActive(downloads: List<EpisodeDownload>): Boolean = downloads.any {
        it.state == EpisodeDownloadState.QUEUED ||
            it.state == EpisodeDownloadState.DOWNLOADING ||
            it.state == EpisodeDownloadState.RESTARTING
    }

    /**
     * Admits one transmux at a time, process-wide.
     *
     * The observer above enqueues a separate uniquely-named work request per pending episode, so
     * finishing a batch download queued fifteen of them at once — and WorkManager happily runs
     * several workers in parallel. Each one builds its own Media3 [androidx.media3.transformer
     * .Transformer], whose `EncodedSampleExporter` buffers encoded samples in an *unbounded*
     * queue while the muxer catches up. Four device reports died exactly there, all at the same
     * 256 MB ceiling, and every one of them came from a heavy download+export session; bundles
     * with no export traffic never hit it. Exports are background work with no deadline, so they
     * take turns instead of racing each other for one heap.
     */
    private val exportSlot = java.util.concurrent.Semaphore(1)

    internal fun tryAcquireExportSlot(): Boolean = exportSlot.tryAcquire()

    internal fun releaseExportSlot() {
        exportSlot.release()
    }

    /**
     * Marks [downloadId] for export. The work starts as soon as the download itself is complete,
     * so this is safe to call while it is still downloading.
     */
    fun request(context: Context, downloadId: String, deleteAppCopyAfter: Boolean = false) {
        if (!isSupported) return
        val app = context.applicationContext
        initialize(app)
        if (transformerUnsupported(app)) {
            setStatus(
                EpisodeExportStatus(
                    downloadId = downloadId,
                    state = EpisodeExportState.FAILED,
                    failure = AnililiError.EXPORT_DEVICE_UNSUPPORTED.report(
                        attributes = mapOf("downloadId" to downloadId, "stage" to "request"),
                    ),
                ),
            )
            return
        }
        val location = DownloadLocations.current(app)
        DownloadLocations.unavailableReason(app, location)?.let { reason ->
            setStatus(
                EpisodeExportStatus(
                    downloadId = downloadId,
                    state = EpisodeExportState.FAILED,
                    failure = reason.report(
                        attributes = mapOf(
                            "downloadId" to downloadId,
                            "stage" to "request",
                            "location" to DownloadLocations.label(app, location),
                        ),
                    ),
                ),
            )
            return
        }
        val prefs = prefs(app)
        val deleting = prefs.getStringSet(KEY_DELETE_AFTER, emptySet()).orEmpty()
        prefs.edit()
            .putStringSet(KEY_PENDING, pendingIds(app) + downloadId)
            .putStringSet(
                KEY_DELETE_AFTER,
                if (deleteAppCopyAfter) deleting + downloadId else deleting - downloadId,
            )
            // Pinned to the request rather than read when the work runs. A queue can take hours,
            // and an episode asked for while the card was chosen belongs on the card even if the
            // setting moved on since — and the worker can start on a cold process where the live
            // setting has not been read off disk yet.
            .putString("$KEY_LOCATION_PREFIX$downloadId", location.storedValue)
            .apply()
        setStatus(EpisodeExportStatus(downloadId, EpisodeExportState.PENDING))
        DiagnosticsLog.event(
            "Episode export requested id=$downloadId deleteAppCopy=$deleteAppCopyAfter",
        )
        // Only start work for a download that is already sitting complete. Anything still running
        // is left to the observer, which fires when it finishes — starting now would just produce
        // a worker that finds nothing to rewrap.
        val ready = EpisodeDownloads.downloads(app).value
            .firstOrNull { it.id == downloadId }?.isComplete == true
        if (ready) EpisodeExportWorker.enqueue(app, downloadId)
    }

    /**
     * Runs one export end to end. Called by [EpisodeExportWorker] so the work keeps its foreground
     * notification and survives the app being backgrounded.
     */
    internal suspend fun performExport(
        context: Context,
        downloadId: String,
        onProgress: suspend (Int?) -> Unit,
    ): Result<String> {
        val app = context.applicationContext
        initialize(app)
        // A record already present means the MP4 landed and only the bookkeeping was interrupted —
        // by process death between writing the file and clearing the request, say. Finishing that
        // bookkeeping is right; rebuilding the file and reporting a failure over it is not.
        _exported.value.firstOrNull { it.downloadId == downloadId }?.let { record ->
            // The MP4 is already published, so it is the published copy that has to earn the right
            // to replace the cached one — same question as below, asked of the published file.
            val published = readAvcConfig(app, Uri.parse(record.uri))
            finishExportCleanup(app, downloadId, published?.let(::avcConfigHasParameterSets) == true)
            return Result.success(record.fileName)
        }
        val location = requestedLocation(app, downloadId)
        DownloadLocations.unavailableReason(app, location)?.let { reason ->
            return fail(
                app,
                downloadId,
                reason.report(
                    attributes = mapOf(
                        "downloadId" to downloadId,
                        "stage" to "export",
                        "location" to DownloadLocations.label(app, location),
                    ),
                ),
            )
        }
        // The index is loaded asynchronously, so on a cold start after process death the download
        // list is briefly empty. Waiting beats reporting an episode as missing that is right there.
        val download = withTimeoutOrNull(DOWNLOAD_LOOKUP_TIMEOUT_MS) {
            EpisodeDownloads.downloads(app)
                .mapNotNull { downloads -> downloads.firstOrNull { it.id == downloadId } }
                .first()
        } ?: return fail(
            app,
            downloadId,
            AnililiError.EXPORT_DOWNLOAD_MISSING.report(attributes = mapOf("downloadId" to downloadId)),
        )
        if (!download.isComplete) {
            // Enqueued ahead of the download finishing. Stay pending rather than failing: the
            // observer re-runs this the moment the download completes.
            setStatus(EpisodeExportStatus(downloadId, EpisodeExportState.PENDING))
            return Result.failure(IllegalStateException("The download has not finished yet."))
        }

        val metadata = download.metadata
        val seriesFolder = EpisodeDownloads.deviceSeriesFolder(metadata)
        val fileName = DownloadLocationFiles.uniqueFileName(
            context = app,
            location = location,
            seriesFolder = seriesFolder,
            stem = EpisodeDownloads.deviceFileStem(metadata),
            extension = "mp4",
        )
        setStatus(EpisodeExportStatus(downloadId, EpisodeExportState.RUNNING, null, fileName))

        // Transformer writes to a path, so the MP4 is built as a file and then streamed into the
        // destination. That means the episode transiently needs room for a second copy of itself —
        // on the destination's own volume where possible, so a full internal disk cannot stop a
        // download that was headed for a memory card.
        val expected = download.contentLength ?: download.bytesDownloaded
        val workingDirectory = DownloadLocations.scratchDirectory(app, location)
        // When the scratch copy and the finished file share a volume — the normal case, and the
        // point of putting scratch on the destination's volume — that volume briefly holds both.
        val shared = DownloadLocations.sharesVolume(app, location, workingDirectory)
        val scratchNeeds = (if (shared) 2 * expected else expected) + DownloadStorage.HEADROOM_BYTES
        if (DownloadStorage.freeBytes(workingDirectory) < scratchNeeds) {
            return fail(
                app,
                downloadId,
                AnililiError.EXPORT_OUT_OF_STORAGE_CACHE.report(
                    attributes = mapOf(
                        "downloadId" to downloadId,
                        "neededBytes" to scratchNeeds,
                        "freeBytes" to DownloadStorage.freeBytes(workingDirectory),
                    ),
                ),
            )
        }
        val destinationFree = DownloadLocations.freeBytes(app, location).takeIf { !shared }
        if (destinationFree != null && destinationFree < expected + DownloadStorage.HEADROOM_BYTES) {
            return fail(
                app,
                downloadId,
                AnililiError.EXPORT_OUT_OF_STORAGE_TARGET.report(
                    DownloadLocations.label(app, location),
                    attributes = mapOf(
                        "downloadId" to downloadId,
                        "neededBytes" to expected + DownloadStorage.HEADROOM_BYTES,
                        "freeBytes" to destinationFree,
                    ),
                ),
            )
        }

        val temporary = File(workingDirectory, "${downloadId.hashCode().toUInt().toString(16)}.mp4")
        temporary.delete()

        return runCatching {
            transmux(app, download, temporary.absolutePath) { percent ->
                setStatus(
                    EpisodeExportStatus(downloadId, EpisodeExportState.RUNNING, percent, fileName),
                )
                onProgress(percent)
            }
            // The rewrap is currently capable of producing an MP4 whose video track carries no
            // codec configuration at all — the samples are copied faithfully, but nothing tells a
            // decoder how to read them, so the picture is flat green while audio and subtitles play
            // normally. Whatever else happens, an export like that must not be treated as good
            // enough to discard the cached copy with: the cache is then the only playable copy left
            // on the device. This check answers "is the MP4 configured?", not "is it perfect", and
            // it costs a box walk over the header.
            val videoCodec = readMp4VideoCodec(temporary)
            val isAvc = videoCodec in setOf("avc1", "avc3")
            var exportIsConfigured = if (isAvc) {
                readAvcConfig(temporary)?.let(::avcConfigHasParameterSets) == true
            } else {
                videoCodec != null && hasExtractableVideoConfig(temporary)
            }
            if (isAvc && !exportIsConfigured) {
                exportIsConfigured = repairVideoConfig(app, download, temporary, downloadId)
            }
            if (!exportIsConfigured) {
                throw AnililiException(
                    AnililiError.EXPORT_NO_VIDEO_CONFIG.report(
                        detail = "The rewrapped video track carries no decoder configuration " +
                            "(codec=${videoCodec ?: "unreadable"}), so it would play as flat green. " +
                            "Publication refused; the library copy is kept.",
                        attributes = mapOf(
                            "downloadId" to downloadId,
                            "videoCodec" to videoCodec,
                            "repairAttempted" to (videoCodec in setOf("avc1", "avc3")),
                        ),
                    ),
                )
            }
            // The destination has the last word on the name — MediaStore and the document
            // providers both rename around collisions of their own — so the record follows the
            // file rather than the request.
            val published = publish(app, temporary, location, seriesFolder, fileName)
            val sidecars = writeSubtitlesBeside(
                app,
                download,
                metadata,
                location,
                seriesFolder,
                published.fileName,
            )
            // Recorded before the cache copy is dropped: this record is what keeps the episode in
            // the offline library once Media3's download index no longer knows about it.
            rememberExported(
                app,
                ExportedEpisode(
                    downloadId = downloadId,
                    uri = published.uri.toString(),
                    fileName = published.fileName,
                    metadata = metadata,
                    sizeBytes = temporary.length(),
                    exportedAtMs = System.currentTimeMillis(),
                    subtitles = sidecars,
                ),
            )
            setStatus(
                EpisodeExportStatus(
                    downloadId,
                    EpisodeExportState.COMPLETED,
                    100,
                    published.fileName,
                ),
            )
            DiagnosticsLog.event(
                "Episode export finished id=$downloadId file=${published.fileName} " +
                    "uri=${published.uri} subtitles=${sidecars.size}",
            )
            finishExportCleanup(app, downloadId, exportIsConfigured)
            published.fileName
        }.onFailure { error ->
            temporary.delete()
            if (error is CancellationException) {
                _statuses.value -= downloadId
                throw error
            }
            // Stood aside for playback: the partial file is gone and the request stays pending, so
            // the playback watcher re-arms it once the session has been closed for a while. Not an
            // attempt, and not a failure to show in the library.
            if (error is ExportYieldedToPlayback) {
                setStatus(EpisodeExportStatus(downloadId, EpisodeExportState.PENDING))
                return Result.failure(error)
            }
            // The observer re-enqueues every complete download still marked pending, so a failure
            // that leaves the mark in place is not one failure — it is one every time the download
            // index emits, for as long as the app lives. A device report caught 834 of them in
            // 7h40m, ~33s apart, until the process died allocating a cursor window. So a failure
            // that will not come out differently retires the request.
            val attempts = recordExportAttempt(app, downloadId)
            val unsupported = isUnsupportedDeviceError(error)
            if (unsupported) markTransformerUnsupported(app)
            if (!shouldRetryExport(error, attempts)) clearPending(app, downloadId)
            setStatus(
                EpisodeExportStatus(
                    downloadId = downloadId,
                    state = EpisodeExportState.FAILED,
                    fileName = fileName,
                    // An error that already identified itself keeps its own code. Relabelling it
                    // here would throw away the specific reason — EXP-04 becoming a generic
                    // "couldn't save" is exactly the loss this catalogue exists to prevent.
                    failure = error.anililiFailure() ?: when {
                        unsupported -> AnililiError.EXPORT_DEVICE_UNSUPPORTED
                        else -> AnililiError.EXPORT_FAILED
                    }.report(
                        DownloadLocations.label(app, location),
                        cause = error,
                        attributes = mapOf("downloadId" to downloadId, "attempts" to attempts),
                    ),
                ),
            )
        }.also { temporary.delete() }
    }

    /**
     * Puts a usable codec configuration back into an export that was written without one.
     *
     * Media3 1.8.1's rewrap truncates every picture parameter set by a byte and then describes the
     * track with an `avcC` holding no parameter sets at all, which decodes as flat green everywhere
     * but this app — the app only escapes it because it prefers the cached copy. The source still
     * has both parameter sets intact, and the extractor hands them over as the track's codec-specific
     * data, so they are read back from the download and written into the file the rewrap produced.
     *
     * Returns whether the file now describes its own video. Media3 1.9 fixes the rewrap outright but
     * requires minSdk 23, which would drop Fire OS 5, so the repair lives here instead.
     */
    private suspend fun repairVideoConfig(
        context: Context,
        download: EpisodeDownload,
        file: File,
        downloadId: String,
    ): Boolean {
        val parameterSets = runCatching { sourceParameterSets(context, download) }
            .onFailure { DiagnosticsLog.throwable("Episode export could not read source csd id=$downloadId", it) }
            .getOrNull()
        if (parameterSets == null) {
            DiagnosticsLog.event("Episode export repair skipped id=$downloadId reason=no_source_parameter_sets")
            return false
        }
        val (sps, pps) = parameterSets
        val neutered = repairTruncatedPictureParameterSets(file, sps, pps)
        val wrote = writeAvcConfig(file, buildAvcConfig(sps, pps))
        val configured = wrote && readAvcConfig(file)?.let(::avcConfigHasParameterSets) == true
        DiagnosticsLog.event(
            "Episode export repaired video config id=$downloadId spsBytes=${sps.size} " +
                "ppsBytes=${pps.size} neutered=${neutered ?: -1} wroteConfig=$wrote configured=$configured",
        )
        return configured
    }

    /** The SPS and PPS the source declares, with their start codes stripped. */
    private suspend fun sourceParameterSets(
        context: Context,
        download: EpisodeDownload,
    ): Pair<ByteArray, ByteArray>? = withContext(Dispatchers.Main) {
        val builder = MediaItem.Builder().setUri(download.uri)
        if (download.isAdaptive) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val item = EpisodeDownloads.buildMediaItem(context, download.uri, builder)
        val groups = MetadataRetriever
            .retrieveMetadata(parameterSetSourceFactory(context, download), item)
            .awaitResult()
        val parameterSets = avcParameterSets(groups)
        if (parameterSets == null) {
            // Says what the source described instead, because "no parameter sets" on its own does
            // not distinguish a stream this genuinely cannot repair from a preparation that handed
            // back the manifest's idea of the track rather than the stream's.
            DiagnosticsLog.event(
                "Episode export source csd unusable id=${download.id} " +
                    "adaptive=${download.isAdaptive} ${videoTrackSummary(groups)}",
            )
        }
        parameterSets
    }

    /**
     * The source factory used to read a finished download's codec-specific data.
     *
     * Chunkless preparation is off for a reason. It is on by default in Media3's HLS factory, and
     * it builds the track groups out of the multivariant playlist alone: the resulting `Format`
     * carries the playlist's `CODECS` string and an **empty** `initializationData`, because nothing
     * has read a segment yet. That is the same emptiness the rewrap turns into an `avcC` with no
     * parameter sets, so the repair was reading its fix out of the one place guaranteed not to have
     * it — nineteen of twenty-four exports across the bundles on file skipped the repair with
     * `no_source_parameter_sets`, and since publication is refused those are now failed exports
     * rather than green ones. Turning it off costs one segment read, served from the download cache
     * like everything else here, and yields the format the extractor built from the stream itself.
     *
     * Deliberately not applied to the transmux in [transmux]: the repair is proven to produce a
     * decodable file once it has the parameter sets, so this is the smaller change, and altering
     * what the muxer is fed would change the bytes of every export rather than only the ones that
     * currently fail.
     */
    private fun parameterSetSourceFactory(
        context: Context,
        download: EpisodeDownload,
    ): MediaSource.Factory {
        val dataSourceFactory = EpisodeDownloads.exportDataSourceFactory(context)
        return if (download.isAdaptive) {
            HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(false)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }
    }

    /** Awaits a Media3 future without pulling in a coroutines-guava dependency for one call. */
    private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            },
            Runnable::run,
        )
        cont.invokeOnCancellation { cancel(false) }
    }

    /**
     * Rewraps the downloaded stream into [outputPath].
     *
     * Transformer is single-threaded and callback driven, so it is driven from the main looper and
     * awaited; the actual work happens on its own internal threads. No effects and no MIME override
     * are set anywhere here — that is what keeps this a transmux instead of a re-encode.
     */
    private suspend fun transmux(
        context: Context,
        download: EpisodeDownload,
        outputPath: String,
        onProgress: suspend (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val completion = CompletableDeferred<Unit>()
        val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
            context,
            DefaultDecoderFactory.Builder(context).build(),
            Clock.DEFAULT,
            DefaultMediaSourceFactory(EpisodeDownloads.exportDataSourceFactory(context)),
        )
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(assetLoaderFactory)
            .setMuxerFactory(
                MonotonicAudioMuxer.Factory { dropped ->
                    if (dropped > 0) {
                        DiagnosticsLog.event(
                            "Episode export dropped backward audio id=${download.id} samples=$dropped",
                        )
                    }
                },
            )
            .setLooper(Looper.getMainLooper())
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completion.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    completion.completeExceptionally(exportException)
                }

                // Worth logging loudly: a fallback means some track could not be copied as-is and
                // is being re-encoded, which turns a file copy into real CPU work.
                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest,
                ) {
                    DiagnosticsLog.event(
                        "Episode export re-encoding id=${download.id} " +
                            "audio=${fallbackTransformationRequest.audioMimeType} " +
                            "video=${fallbackTransformationRequest.videoMimeType}",
                    )
                }
            })
            .build()

        val builder = MediaItem.Builder().setUri(download.uri)
        if (download.isAdaptive) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val mediaItem = EpisodeDownloads.buildMediaItem(context, download.uri, builder)
        transformer.start(EditedMediaItem.Builder(mediaItem).build(), outputPath)

        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
            }
        }
        // The worker only checks for playback before it starts. A session that opens mid-export
        // (the viewer pressed play) stops the transformer here instead of sharing the heap — and,
        // after a fallback, the hardware decoder — with the player for the rest of the episode.
        val yieldWatcher = launch {
            PlaybackStatus.sessionActive.first { it }
            DiagnosticsLog.event("Episode export yielding to playback id=${download.id}")
            completion.completeExceptionally(ExportYieldedToPlayback())
        }
        try {
            completion.await()
        } catch (error: Throwable) {
            transformer.cancel()
            throw error
        } finally {
            poller.cancel()
            yieldWatcher.cancel()
        }
    }

    /** Streams the finished MP4 into the chosen save location and returns where it landed. */
    private suspend fun publish(
        context: Context,
        source: File,
        location: DownloadLocation,
        seriesFolder: String,
        fileName: String,
    ): PublishedFile = publishFile(
        context = context,
        source = source,
        location = location,
        seriesFolder = seriesFolder,
        fileName = fileName,
        mimeType = MimeTypes.VIDEO_MP4,
    )

    private suspend fun publishFile(
        context: Context,
        source: File,
        location: DownloadLocation,
        seriesFolder: String,
        fileName: String,
        mimeType: String,
    ): PublishedFile = withContext(Dispatchers.IO) {
        DownloadLocationFiles.publish(
            context = context,
            source = source,
            location = location,
            seriesFolder = seriesFolder,
            fileName = fileName,
            mimeType = mimeType,
        )
    }

    /**
     * Drops every subtitle track next to the MP4 under a matching name. Media3's MP4 muxer will not
     * take a text track, and a sidecar is what external players look for anyway.
     *
     * Two sources feed this, because providers deliver soft subs two different ways and only one of
     * them leaves a file on disk by itself. Sidecar VTT URLs were downloaded alongside the episode
     * and are copied straight across. Renditions carried inside the HLS manifest have no URL of
     * their own, so they are read back out of the download cache and flattened here — without that
     * step those servers exported as raw unsubtitled video even though the download itself had the
     * subtitles in it.
     *
     * A language already covered by a sidecar is not flattened again: the two paths routinely
     * describe the same track, and two indistinguishable files beside the MP4 is worse than one.
     */
    private suspend fun writeSubtitlesBeside(
        context: Context,
        download: EpisodeDownload,
        metadata: EpisodeDownloadMetadata,
        location: DownloadLocation,
        seriesFolder: String,
        fileName: String,
    ): List<ExportedSubtitle> = withContext(Dispatchers.IO) {
        val stem = fileName.substringBeforeLast('.')
        val used = mutableSetOf<String>()
        // Reserves the sidecar's name and answers where it landed, so both sources number their
        // collisions against one set rather than overwriting each other.
        suspend fun place(
            source: File,
            tag: String,
            extension: String,
            label: String,
            language: String,
        ): ExportedSubtitle {
            val name = generateSequence(0) { it + 1 }
                .map { index -> if (index == 0) "$stem.$tag" else "$stem.$tag.$index" }
                .first { it !in used }
            used += name
            val target = publishFile(
                context = context,
                source = source,
                location = location,
                seriesFolder = seriesFolder,
                fileName = "$name.$extension",
                mimeType = subtitleMimeType(extension),
            )
            return ExportedSubtitle(
                uri = target.uri.toString(),
                label = label,
                language = language,
                mimeType = subtitleMimeType(extension),
            )
        }

        val downloaded = EpisodeDownloads.subtitleFiles(context, metadata).mapNotNull { (subtitle, file) ->
            runCatching {
                place(
                    source = file,
                    tag = EpisodeDownloads.subtitleTag(subtitle),
                    extension = file.extension,
                    label = subtitle.label,
                    language = subtitle.language,
                )
            }.onFailure {
                DiagnosticsLog.throwable(
                    "Episode export subtitle copy failed language=${subtitle.language}",
                    it,
                )
            }.getOrNull()
        }

        val covered = downloaded.map { it.language.trim().lowercase() }.filter(String::isNotBlank).toSet()
        val flattened = extractHlsSubtitles(context, download)
            .filter { it.language.trim().lowercase() !in covered }
            .mapNotNull { extracted ->
                val scratch = File(context.cacheDir, "export-subtitle-${System.nanoTime()}.vtt")
                runCatching {
                    scratch.writeText(extracted.webvtt)
                    place(
                        source = scratch,
                        tag = subtitleTag(extracted.language, extracted.label),
                        extension = "vtt",
                        label = extracted.label,
                        language = extracted.language,
                    )
                }.onFailure {
                    DiagnosticsLog.throwable(
                        "Episode export subtitle flatten failed language=${extracted.language}",
                        it,
                    )
                }.also { scratch.delete() }.getOrNull()
            }

        downloaded + flattened
    }

    /** Matches [EpisodeDownloads.subtitleTag], which takes an entry this path does not have. */
    private fun subtitleTag(language: String, label: String): String =
        EpisodeDownloads.subtitleTag(
            EpisodeDownloadSubtitle(url = "", label = label, language = language),
        )

    private fun subtitleMimeType(extension: String): String = when (extension.lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }

    /** [failure] has already been reported by the caller's `report()`; this only records it. */
    private fun fail(context: Context, downloadId: String, failure: AnililiFailure): Result<String> {
        clearPending(context, downloadId)
        setStatus(
            EpisodeExportStatus(
                downloadId = downloadId,
                state = EpisodeExportState.FAILED,
                failure = failure,
            ),
        )
        return Result.failure(AnililiException(failure))
    }

    private fun setStatus(status: EpisodeExportStatus) {
        _statuses.value += status.downloadId to status
    }

    /**
     * Rebuilds the session's view of exports from disk.
     *
     * Records are pruned against MediaStore on the way in: the viewer is free to delete an MP4 in
     * any file manager, and an episode the library offers to play that is no longer there is worse
     * than one that quietly disappears from the list.
     */
    private fun restoreExported(context: Context) {
        val stored = runCatching {
            prefs(context).getString(KEY_RECORDS, null)
                ?.let { json.decodeFromString<List<ExportedEpisode>>(it) }
                .orEmpty()
        }.onFailure {
            DiagnosticsLog.throwable("Exported episode records unreadable", it)
        }.getOrDefault(emptyList())

        // Only records the device positively denies survive being pruned. A revoked Android 6–9
        // storage permission, an unplugged memory card, a folder grant not yet re-taken — all
        // three make files that are still on disk unreadable, and forgetting a library because a
        // card was out is not something the viewer can undo.
        val alive = stored.filter { record ->
            DownloadLocationFiles.exists(context, Uri.parse(record.uri)) != false
        }
        if (alive.size != stored.size) {
            DiagnosticsLog.event(
                "Exported episodes pruned removed=${stored.size - alive.size} kept=${alive.size}",
            )
            saveExported(context, alive)
        } else {
            _exported.value = alive.sortedByDescending(ExportedEpisode::exportedAtMs)
        }
        _statuses.value = alive.associate {
            it.downloadId to EpisodeExportStatus(
                downloadId = it.downloadId,
                state = EpisodeExportState.COMPLETED,
                percent = 100,
                fileName = it.fileName,
            )
        } + pendingIds(context).associateWith {
            EpisodeExportStatus(it, EpisodeExportState.PENDING)
        }
    }

    private fun deleteUri(context: Context, uri: String) {
        DownloadLocationFiles.delete(context, Uri.parse(uri))
    }

    private fun rememberExported(context: Context, record: ExportedEpisode) {
        saveExported(
            context,
            _exported.value.filterNot { it.downloadId == record.downloadId } + record,
        )
    }

    private fun saveExported(context: Context, records: List<ExportedEpisode>) {
        val ordered = records.sortedByDescending(ExportedEpisode::exportedAtMs)
        _exported.value = ordered
        prefs(context).edit()
            .putString(KEY_RECORDS, runCatching { json.encodeToString(ordered) }.getOrNull())
            .apply()
    }

    private fun pendingIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PENDING, emptySet()).orEmpty()

    private fun shouldDeleteAppCopy(context: Context, downloadId: String): Boolean =
        prefs(context).getStringSet(KEY_DELETE_AFTER, emptySet()).orEmpty().contains(downloadId)

    /**
     * Retires the export request, dropping the cached copy only when there is a playable MP4 to
     * drop it for. [exportIsConfigured] false means the rewrap produced a file no decoder can read,
     * so the library copy stays whatever the viewer chose as their destination — a second copy on
     * disk is a far smaller problem than an episode that only plays as green.
     */
    private suspend fun finishExportCleanup(
        context: Context,
        downloadId: String,
        exportIsConfigured: Boolean,
    ) {
        val cleanup = runCatching {
            if (shouldDeleteAppCopy(context, downloadId) && exportIsConfigured) {
                EpisodeDownloads.removeDirectly(context, downloadId)
                check(
                    withTimeoutOrNull(DOWNLOAD_CLEANUP_TIMEOUT_MS) {
                        EpisodeDownloads.downloads(context)
                            .map { downloads -> downloads.none { it.id == downloadId } }
                            .first { removed -> removed }
                    } == true,
                ) { "Timed out removing the app download copy" }
            }
        }
        cleanup.onSuccess {
            clearPending(context, downloadId)
        }.onFailure {
            // Keep the request pending. The restored exported record makes the next worker retry
            // cleanup without rebuilding or republishing the MP4.
            DiagnosticsLog.throwable("Episode export cleanup deferred id=$downloadId", it)
        }
    }

    private fun clearPending(context: Context, downloadId: String) {
        val prefs = prefs(context)
        prefs.edit()
            .putStringSet(KEY_PENDING, pendingIds(context) - downloadId)
            .putStringSet(
                KEY_DELETE_AFTER,
                prefs.getStringSet(KEY_DELETE_AFTER, emptySet()).orEmpty() - downloadId,
            )
            .remove("$KEY_ATTEMPT_PREFIX$downloadId")
            .remove("$KEY_LOCATION_PREFIX$downloadId")
            .apply()
    }

    /**
     * Where this export was told to go when it was asked for. Requests made before save locations
     * existed carry no note, and belong where they always went: the current setting, which for
     * those installs is still the public Downloads folder.
     */
    private fun requestedLocation(context: Context, downloadId: String): DownloadLocation {
        val stored = prefs(context).getString("$KEY_LOCATION_PREFIX$downloadId", null)
        return if (stored == null) {
            DownloadLocations.current(context)
        } else {
            parseDownloadLocation(stored)
        }
    }

    /** Counts one finished-and-failed attempt at [downloadId], returning the new total. */
    private fun recordExportAttempt(context: Context, downloadId: String): Int {
        val prefs = prefs(context)
        val key = "$KEY_ATTEMPT_PREFIX$downloadId"
        val attempts = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, attempts).apply()
        return attempts
    }

    /**
     * Remembers that this device cannot run a transmux at all, so the rest of the library stops
     * offering something that can only fail. Recorded from the failure itself rather than gated on
     * an SDK number: the ceiling belongs to whichever Media3 is linked in, not to this code.
     */
    private fun markTransformerUnsupported(context: Context) {
        if (transformerUnsupported(context)) return
        prefs(context).edit().putBoolean(KEY_TRANSFORMER_UNSUPPORTED, true).apply()
        DiagnosticsLog.event("Episode export disabled: this device cannot run the transmuxer")
    }

    /** Whether a transmux has already proven impossible on this device. */
    fun transformerUnsupported(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRANSFORMER_UNSUPPORTED, false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "episode_exports"
    private const val KEY_PENDING = "pending"
    private const val KEY_ATTEMPT_PREFIX = "attempts:"
    private const val KEY_LOCATION_PREFIX = "location:"
    private const val KEY_TRANSFORMER_UNSUPPORTED = "transformer_unsupported"
    private const val KEY_DELETE_AFTER = "delete_after"
    private const val KEY_RECORDS = "records"
    private const val PROGRESS_INTERVAL_MS = 700L
    private const val DOWNLOAD_LOOKUP_TIMEOUT_MS = 10_000L
    private const val DOWNLOAD_CLEANUP_TIMEOUT_MS = 10_000L
}

/** How many times one episode may fail to transmux before the request is retired. */
internal const val MAX_EXPORT_ATTEMPTS = 3

/** The AVC parameter sets [groups] carries, or null when its video track describes none. */
@OptIn(UnstableApi::class)
internal fun avcParameterSets(groups: TrackGroupArray): Pair<ByteArray, ByteArray>? {
    val csd = videoFormat(groups)?.initializationData?.map(::stripStartCode).orEmpty()
    val sps = csd.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 7 }
    val pps = csd.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 8 }
    return if (sps == null || pps == null) null else sps to pps
}

/** What the source said about its video track, for a report that has to explain an empty csd. */
@OptIn(UnstableApi::class)
internal fun videoTrackSummary(groups: TrackGroupArray): String {
    val video = videoFormat(groups) ?: return "video=none groups=${groups.length}"
    val types = video.initializationData.map(::stripStartCode)
        .joinToString("/") { if (it.isEmpty()) "empty" else (it[0].toInt() and 0x1f).toString() }
    return "video=${video.sampleMimeType} codecs=${video.codecs ?: "none"} " +
        "csd=${video.initializationData.size} nalTypes=${types.ifEmpty { "none" }}"
}

@OptIn(UnstableApi::class)
private fun videoFormat(groups: TrackGroupArray) = (0 until groups.length)
    .map(groups::get)
    .flatMap { group -> (0 until group.length).map(group::getFormat) }
    .firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }

/** Codec-specific data arrives as Annex-B units; the configuration record wants them bare. */
private fun stripStartCode(nal: ByteArray): ByteArray = when {
    nal.size > 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
        nal[2] == 0.toByte() && nal[3] == 1.toByte() -> nal.copyOfRange(4, nal.size)
    nal.size > 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte() ->
        nal.copyOfRange(3, nal.size)
    else -> nal
}

/** Non-AVC exports need their own decoder configuration, not an AVC parameter-set check. */
private fun hasExtractableVideoConfig(file: File): Boolean = runCatching {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        (0 until extractor.trackCount).map(extractor::getTrackFormat).any { format ->
            format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                format.getInteger(MediaFormat.KEY_WIDTH) > 0 &&
                format.getInteger(MediaFormat.KEY_HEIGHT) > 0 &&
                format.getByteBuffer("csd-0")?.hasRemaining() == true
        }
    } finally {
        extractor.release()
    }
}.getOrDefault(false)

/**
 * Whether the device itself is what failed, rather than this episode.
 *
 * Media3's Transformer resolves `android.media.metrics.LogSessionId` — an API 31 class — while
 * building its asset loader, so on anything older every export dies with `NoClassDefFoundError`
 * before a single byte is written. That is a [LinkageError], and no amount of retrying changes it:
 * a device report shows the same episode failing this way 834 times in a row.
 */
internal fun isUnsupportedDeviceError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is LinkageError }

/**
 * Whether a failed export deserves to stay queued. Transient causes — a busy muxer, storage that
 * filled up, a process killed mid-write — are worth [MAX_EXPORT_ATTEMPTS] tries; a device that
 * cannot transmux at all is worth none.
 */
internal fun shouldRetryExport(error: Throwable, attempts: Int): Boolean =
    !isUnsupportedDeviceError(error) &&
        error.anililiFailure()?.error != AnililiError.EXPORT_NO_VIDEO_CONFIG &&
        attempts < MAX_EXPORT_ATTEMPTS

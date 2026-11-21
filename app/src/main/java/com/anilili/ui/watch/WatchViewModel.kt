package com.anilili.ui.watch

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.MiruroRepository
import com.anilili.data.preferredLanguageFor
import com.anilili.data.ProviderCatalog
import com.anilili.data.ProviderSourceIdentity
import com.anilili.data.ProviderSpeed
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.resumeFor
import com.anilili.data.discord.DiscordPresence
import com.anilili.data.discord.DiscordWatching
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.anilili.data.settings.WATCHED_THRESHOLD_MAX_PERCENT
import com.anilili.data.settings.WATCHED_THRESHOLD_MIN_PERCENT
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.aniSkipSegmentIds
import com.anilili.data.model.hasUsableWindow
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.Media
import com.anilili.data.remote.PipeBridge
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.rendererCrashIsFatal
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.raise
import com.anilili.errors.report
import com.anilili.errors.toFailure
import com.anilili.playback.CastRouteStatus
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.ExternalPlaybackSession
import com.anilili.playback.SleepTimer
import com.anilili.playback.isSignsSubtitle
import com.anilili.ui.UiState
import com.anilili.ui.detail.fillEpisodeArtFromCatalog
import com.anilili.ui.detail.fillEpisodeFillerFromCatalog
import com.anilili.ui.detail.mergeEpisodeMetadata
import com.anilili.ui.detail.seasonChain
import com.anilili.ui.detail.seasonChainLabels
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Ignore short ad clips accidentally reported by an embed as its main video. */
internal const val MIN_SKIP_LOOKUP_DURATION_MS = 60_000L

/**
 * How long the initial load waits on the Miruro pipe before proceeding with the fast Anivexa
 * catalog alone. A healthy warm pipe answers well under this; a Cloudflare-dead one takes 15 s+,
 * which used to be dead time on the loading screen. Miruro still merges in if it answers later.
 */
private const val MIRURO_CATALOG_WAIT_MS = 8_000L

/**
 * How long an unpinned start, having found the episode on a fast native server first, still waits
 * for Miruro before resolving. Miruro's servers resolve in ~100ms, so one that is a moment behind
 * is worth including in the race; one that is further behind merges in after playback starts.
 */
private const val AUTO_MIRURO_GRACE_MS = 400L

data class WatchData(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int,
    val provider: String,
    val category: Category,
    val sourceVariant: SourceVariant = SourceVariant.defaultFor(category),
    val sourceOptions: List<WatchSourceOption>,
    val anilistId: Int,
    val sources: SourcesResult,
    val chosenStream: StreamItem?,
    val skipTimingStatus: SkipTimingStatus = SkipTimingStatus.WAITING_FOR_DURATION,
    val skipReport: SkipReportState = SkipReportState.IDLE,
    val seriesTitle: String,
    val artworkUrl: String?,
    val seriesFormat: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val description: String? = null,
    val startPositionMs: Long = 0,
    /**
     * True when the player moved to this episode itself — the credits ran out and "Autoplay next
     * episode" took the next one — rather than the viewer opening it.
     *
     * It is what lets a binge run with "Play when an episode opens" off, which is a switch about
     * opening episodes and was never meant to strand one. Carried on the data rather than read off
     * the outgoing player, because an embed episode arrives on a page that has already been torn
     * down and has no outgoing player left to ask.
     */
    val autoAdvanced: Boolean = false,
    /** The season after this one, once the season chain has been read; see [continuesIntoNextSeason]. */
    val nextSeason: NextSeason? = null,
    val playbackGeneration: Int = 0,
    val preferredProvider: String = DEFAULT_PREFERRED_PROVIDER,
    val isResolving: Boolean = false,
    /** The fast Miruro sources are shown; the slower Anivexa servers are still loading in. */
    val isLoadingMoreSources: Boolean = false,
    /** A bounded Miruro catalog recovery is queued or running after a failed cold start. */
    val isRetryingMiruro: Boolean = false,
    /** Track languages discovered per server/audio pair, filled in as sources are validated. */
    val capabilities: Map<Pair<String, SourceVariant>, SourceCapabilities> = emptyMap(),
    val notice: String? = null,
) {
    val current: EpisodeItem get() = episodes[currentIndex]

    fun capabilitiesOf(option: WatchSourceOption): SourceCapabilities =
        capabilities[option.provider to option.variant] ?: SourceCapabilities()

    /** What [option] is in: its server's declared language, else what its resolve reported. */
    fun languagesOf(option: WatchSourceOption): Set<String> =
        sourceLanguages(option, capabilitiesOf(option))

    /**
     * Every language seen so far across all options, for the picker's filter row, plus the
     * declared language of every server listed — so a Spanish server is findable under Spanish
     * before anything has resolved it.
     */
    val knownLanguages: List<String>
        get() = (
            capabilities.entries.flatMap { (key, capability) -> capability.languagesFor(key.second.category) } +
                sourceOptions.mapNotNull { ProviderCatalog.declaredLanguage(it.provider) }
            )
            .distinct()
            .sorted()
    val hasMiruroSources: Boolean
        get() = hasMiruroSourceOptions(sourceOptions)

    /**
     * The variant the picker should draw as selected, which is not always the one playing.
     *
     * The resolver may fall back to a subtitle shape the catalogue does not publish for that server
     * — see `ProviderCatalog.unpublishedSubtitleVariant` — and there is no cell for it, so a strict
     * `provider && variant` match highlights nothing and the server the viewer just chose looks
     * unchosen. Falling back to the server's own cell keeps the grid honest about where the stream
     * came from; the chips above it still read the real variant.
     */
    val pickerVariant: SourceVariant
        get() = if (sourceOptions.any { it.provider == provider && it.variant == sourceVariant }) {
            sourceVariant
        } else {
            sourceOptions.firstOrNull { it.provider == provider }?.variant ?: sourceVariant
        }

    val hasNext: Boolean get() = currentIndex < episodes.lastIndex
    val hasPrev: Boolean get() = currentIndex > 0

    /**
     * Whether moving on from here means the next season's first episode: this is the last episode
     * listed, of a season that has finished airing, and another season follows it.
     *
     * The season's own episode count is checked as well as the list, because the list is only what
     * the servers carry. A catalogue missing the finale ends the row an episode early, and moving
     * on from there would skip the finale to start the next season.
     */
    val continuesIntoNextSeason: Boolean
        get() = !hasNext && nextSeason != null &&
            (nextSeason.afterEpisode == null || current.number >= nextSeason.afterEpisode)

    /** Whether the player has anything to move on to: an episode of this season, or the next one. */
    val canAdvance: Boolean get() = hasNext || continuesIntoNextSeason

    /**
     * Where moving on lands: the next episode listed, or with [skipFiller] the next one that is not
     * filler. Null when the move leaves this season — see [advancesIntoNextSeason] — or there is
     * nowhere to go.
     *
     * A season whose remaining episodes are all filler, with no season after it, still moves on to
     * the next one: skipping filler is about getting to the story, and stranding the viewer on the
     * last canon episode with a dead Next button is not that.
     */
    fun nextIndex(skipFiller: Boolean): Int? {
        if (!hasNext) return null
        if (!skipFiller) return currentIndex + 1
        return ((currentIndex + 1)..episodes.lastIndex).firstOrNull { !episodes[it].filler }
            ?: (currentIndex + 1).takeUnless { advancesIntoNextSeason(skipFiller = true) }
    }

    /** Whether moving on, with or without [skipFiller], means the next season's first episode. */
    fun advancesIntoNextSeason(skipFiller: Boolean): Boolean {
        if (!skipFiller || !hasNext) return continuesIntoNextSeason
        if (((currentIndex + 1)..episodes.lastIndex).any { !episodes[it].filler }) return false
        // Only filler left in the list: the same finished-season test as [continuesIntoNextSeason],
        // made against the last episode listed rather than this one.
        val season = nextSeason ?: return false
        return season.afterEpisode == null || episodes.last().number >= season.afterEpisode
    }

    /**
     * Whether the player should offer to skip this episode as filler: it is one, and skipping it
     * lands on something that is not — the season's next canon episode, or the next season.
     */
    val canSkipFiller: Boolean
        get() = current.filler && (
            advancesIntoNextSeason(skipFiller = true) ||
                nextIndex(skipFiller = true)?.let { !episodes[it].filler } == true
            )
}

/**
 * The season that follows the one being watched, as the anime page's season chips order them.
 *
 * [afterEpisode] is the current season's episode count, which is where it has to be finished before
 * the player moves on; null when AniList does not say.
 */
data class NextSeason(
    val anilistId: Int,
    /** What the season chips call it ("Season 2"), or its title when they have no number for it. */
    val label: String,
    val afterEpisode: Int?,
)

/** The player leaving the last episode of a season for the first one of [anilistId]. */
data class NextSeasonRequest(
    val anilistId: Int,
    val provider: String,
    val category: String,
    /** True when the credits ran out and auto-play took it, as for [WatchData.autoAdvanced]. */
    val autoAdvanced: Boolean,
)

/**
 * The season to move on to after [info] ends, from its [seasons] chain.
 *
 * Only a finished season moves on — one still airing has its next episode still to come — and only
 * to a season that has started airing, since one that has not has no episode one to open. A film
 * is left alone: the fallback chain lists it among the seasons it aired between, and the TV season
 * that aired after it is not what "next" means for someone who has just watched a film.
 */
internal fun nextSeasonAfter(info: Media, seasons: List<Media>): Media? {
    if (info.status != "FINISHED" || info.format == "MOVIE") return null
    val index = seasons.indexOfFirst { it.id == info.id }
    if (index < 0) return null
    return seasons.getOrNull(index + 1)?.takeIf { it.status in AIRED_STATUSES }
}

private val AIRED_STATUSES = setOf("FINISHED", "RELEASING", "HIATUS")

data class WatchSourceOption(
    val provider: String,
    val category: Category,
    val hasCurrentEpisode: Boolean,
    val episodeCount: Int,
    val variant: SourceVariant = SourceVariant.defaultFor(category),
    /**
     * This pair has already been tried for this episode and did not answer, so it sits at the end
     * of the picker and is drawn faded. Still selectable: see [visibleSourceOptions].
     */
    val recentlyFailed: Boolean = false,
)

/**
 * What a server/audio pair actually turned out to offer, learned when its sources were resolved.
 *
 * The catalog only knows sub vs dub. Which spoken language a dub is in, and which subtitle tracks
 * ride along, are inside the resolved [com.anilili.data.model.SourcesResult] — so they are
 * only knowable after a resolve. The source-validation pass already performs exactly that resolve
 * for every candidate and used to keep nothing but a boolean; this is that discarded detail.
 *
 * [known] separates "we checked and it has no subtitle tracks" (common — plenty of servers ship
 * hardsubbed video) from "we have not checked yet", so the picker never claims a language is
 * missing while it is still looking.
 */
data class SourceCapabilities(
    val audioLanguages: Set<String> = emptySet(),
    val subtitleLanguages: Set<String> = emptySet(),
    /**
     * Whether the resolve produced a stream the offline library can actually fetch.
     *
     * [com.anilili.data.ProviderCatalog.supportsOfflineDownload] answers the same question from the
     * provider's name alone, which is a forecast rather than an observation. When MegaPlay rotated
     * its source route on 2026-09-09 and AniKoto fell back to its embed, the forecast kept saying
     * "downloadable" while every resolve came back embed-only — so the picker offered a download
     * glyph and the screen underneath it then showed no download button. Only a resolve can settle
     * this, and this *is* a resolve, so the answer is kept here beside the track languages.
     *
     * Like the languages, this is sampled per provider and not per episode (see
     * [WatchViewModel.sourceCapabilities]). That holds for the usual reason a provider is
     * embed-only — it has no native extractor — but not universally: 2Dhive serves some episodes
     * natively and others through an embed alone. The current server is re-resolved on every
     * episode, so its badge is always first-hand; the rest stay a forecast, just a measured one.
     */
    val downloadable: Boolean = false,
    /**
     * Whether the resolve handed over a signs-only subtitle track ("English (Signs & Songs)").
     *
     * Which server has one used to be found by opening servers one after another and reading each
     * track list, because nothing before the player looked. A dub without it shows no on-screen
     * text at all, since its dialogue track leaves the signs out. Sampled per provider like
     * [downloadable], and for the same reason that is close enough: a provider that splits its
     * signs out does it for the whole release.
     */
    val signsTrack: Boolean = false,
    val known: Boolean = false,
) {
    /**
     * The languages that describe a row of [category]: what a dub is spoken in, what a sub is
     * written in.
     *
     * These used to be handed out merged, and on a dub row that made the subtitles answer a
     * question about the audio. Almost nothing reports an audio language — only AnimeDekho,
     * ToonHub, RareAnimes and the Miruro pipe do; every stream built by [
     * com.anilili.data.remote.AnivexaClient] passes `audio = null`, and AllAnime and KickAssAnime
     * pass the category word, which [normalizeLanguage] discards as the placeholder it is. So the
     * merged set was, for most servers, the subtitle languages wearing the row's clothes:
     * AniKoto's dub carries one `eng-2.vtt` and was therefore badged EN, on a stream measured
     * 2026-09-05 to hold a single muxed audio track with no English in it.
     */
    fun languagesFor(category: Category): Set<String> =
        if (category == Category.DUB) audioLanguages else subtitleLanguages
}

private data class EpisodeSourceKey(
    val episode: Double,
    val provider: String,
    val variant: SourceVariant,
)

/** What became of an attempt to move within the streams one provider published for an episode. */
private enum class InternalFallback {
    /** Another of the provider's streams is now playing, or this failure was already spent. */
    MOVED,

    /** The provider has nothing untried left; the caller picks a different server. */
    EXHAUSTED,

    /** It has an embed left, but a confirmed server outranks it; see `deferredEmbedSources`. */
    EMBED_DEFERRED,
}

private data class SkipLookupKey(
    val episode: Double,
    val provider: String,
    val variant: SourceVariant,
    val durationSeconds: Int,
)

class WatchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<WatchData>>(UiState.Loading)
    val state = _state.asStateFlow()

    /** Live status line for the initial loading screen ("main source is down, trying others…"). */
    private val _loadingStatus = MutableStateFlow<String?>(null)
    val loadingStatus = _loadingStatus.asStateFlow()

    /**
     * A cast that is not happening, said out loud: one a receiver dropped and the service brought
     * back, or one it would not hand over at all.
     *
     * Held apart from [onPlaybackError] for the same reason [onPlaybackStalled] is, and more
     * sharply: a playback error is the source saying it has nothing to give, and acting on one
     * records the stream as refused, demotes its host for the rest of the session and resolves
     * another server. None of that is true of a cast that failed. The stream is playing on this
     * device at the moment the notice appears — it was the receiver that could not fetch it, from
     * its own network, with none of this app's headers. Charging the server for that would cost a
     * healthy one every time somebody casts to a television that cannot reach it.
     */
    init {
        viewModelScope.launch {
            CastRouteStatus.playingHere.collect { message ->
                val data = (_state.value as? UiState.Success)?.data ?: return@collect
                // Which of the two it was belongs to the service, which is the half that knows;
                // this line only records that the viewer was told.
                DiagnosticsLog.event(
                    "Watch cast notice shown provider=${data.provider} " +
                        "episode=${data.current.displayNumber}",
                )
                _state.value = UiState.Success(data.copy(notice = message))
            }
        }
    }

    private var anilistId = 0
    private var category = Category.SUB
    private var sourceVariant = SourceVariant.GENERIC_SUB
    /** Whether the current episode needs a URL another installed video app can consume. */
    private var requireExternalPlayer = false
    private var globalPreferredProvider = DEFAULT_PREFERRED_PROVIDER
    private var preferred = ""
    private var spine: List<EpisodeItem> = emptyList()
    private var startedKey: String? = null
    private var seriesTitle = "Anime"
    private var artworkUrl: String? = null
    private var seriesFormat: String? = null
    private var averageScore: Int? = null
    private var popularity: Int? = null
    private var description: String? = null
    private var totalEpisodes: Int? = null
    private var seriesStatus: String? = null
    private val locallyWatchedEpisodes = mutableSetOf<Int>()
    private val scheduledRemoteProgressEpisodes = mutableSetOf<Int>()

    /** Episodes already speculatively resolved, so a preheat runs at most once each. */
    private val preheatedEpisodes = mutableSetOf<Double>()
    private var lastRequestedNumber = 1.0

    /**
     * Streams that failed to play this episode, remembered for as long as it is the episode open.
     *
     * [failedStreamUrls] is cleared by every resolve, which is right for the ladder it drives but
     * meant the app had no memory at all: bonk lists a dead stream first — segments on a ByteDance
     * domain that answers `{"code":1004,"error":"domain forbidden"}` — and every re-resolve handed
     * it straight back. Changing server and changing back, or pressing play again, each restarted
     * fifteen seconds of doomed retries on a URL the app had already watched fail. Reported as
     * twenty presses of play and four server changes for one episode.
     *
     * Held per episode because that is the scope of the claim: the next episode's URLs are new, and
     * leaving the screen drops the whole view model anyway. Consulted only when picking which of a
     * provider's streams to start on; when *every* one of them is in here the ordinary choice is
     * made and the existing fallback ladder runs, so this can never leave an episode with nothing.
     */
    private val refusedStreamUrls = mutableSetOf<String>()
    private var refusedStreamEpisode: Double? = null

    /**
     * Hosts that have refused to play *any* episode of this title, for as long as this screen lives.
     *
     * [refusedStreamUrls] above is deliberately per-episode, and that is right for a URL: the next
     * episode's URL is a new claim. A host is not. Bonk's dead first stream is dead on every
     * episode, so the per-episode memory re-learned it from scratch each time and spent another ten
     * to eighteen seconds of black screen doing it — measured at 10.8s, 11.0s, 12.7s and 18.2s on
     * four consecutive episodes of three different titles, every one of them ending in the same
     * `{"code":1004,"error":"domain forbidden"}` from the same ByteDance segment host.
     *
     * Only ever a demotion, never an exclusion (see [firstUnrefusedStream]): one refusal is enough
     * to stop leading with a host, and nowhere near enough to throw away the only stream a provider
     * has. Nothing is written down for later sessions — a dead edge is usually dead for hours, not
     * for good, and a stale note would outlive the outage.
     */
    private val refusedStreamHosts = mutableSetOf<String>()

    /**
     * Set by [autoAdvance] and read once by the resolve it started, which spends it on
     * [WatchData.autoAdvanced]. A resolve is the only thing that can consume it, so a viewer who
     * opens an episode by hand always gets a false.
     */
    private var pendingAutoAdvance = false

    /**
     * The episode number and position an `anilili://watch/…?t=` link asked to start from. It beats
     * the saved position for that episode until this stream has been seen playing, after which the
     * saved position is newer than the link and takes over again (a source switch resumes from
     * where the viewer is, not from where the link said).
     */
    private var linkedStart: Pair<Double, Long>? = null

    /** The season after this one, once [launchNextSeasonLookup] has found it. */
    private var nextSeason: NextSeason? = null
    private var nextSeasonJob: Job? = null

    /** Set once the move to [nextSeason] has been asked for, so the card and the ending cannot both. */
    private var nextSeasonRequested = false
    private val _nextSeasonRequests = MutableSharedFlow<NextSeasonRequest>(extraBufferCapacity = 1)

    /** Collected by the screen, which owns navigation and replaces this route with the next season's. */
    val nextSeasonRequests = _nextSeasonRequests.asSharedFlow()
    private var failedSources = mutableSetOf<ProviderSourceIdentity>()

    /**
     * Sources passed over because they kept buffering, as opposed to failing.
     *
     * Deliberately separate from [failedSources] and [unavailableSources]: a stall says the network
     * or the CDN was slow for twenty seconds, not that the server has nothing playable. These are
     * skipped when picking the next source automatically — otherwise the resolve walks straight back
     * into the stream that was stalling — but they stay listed and hand-pickable in the sources
     * panel, and nothing about them is written down for later sessions.
     */
    private val stalledSources = mutableSetOf<ProviderSourceIdentity>()

    /**
     * Sources whose remaining offer for this episode is an embed, put behind a server that has
     * already been proven to answer.
     *
     * Stepping from a provider's own direct stream down to its own embed is normally the right move
     * — it keeps the viewer on the server they chose. It stops being right when the embed is the
     * *worse* answer and a better one is already known: an embed costs the native controls, the
     * subtitle offset, PiP and Cast, and the one that prompted this sat on a page that never
     * finished loading at all, leaving a black rectangle with no controls on it for as long as the
     * viewer was willing to wait. The background sweep had confirmed two other servers for that
     * exact episode seconds earlier and nothing consulted them.
     *
     * Deferred rather than failed, and restored by [resolveAndPlay] if no other server works out,
     * because "there is something better" is not "this is broken" — the embed is still the last
     * thing standing between the viewer and no episode. Cleared with the other per-episode
     * exclusions, so the next episode makes the judgement again from scratch.
     */
    private val deferredEmbedSources = mutableSetOf<ProviderSourceIdentity>()
    private val unavailableSources = mutableSetOf<EpisodeSourceKey>()
    private val confirmedSources = mutableSetOf<EpisodeSourceKey>()

    /**
     * Sources already resolved again after a playback error because their URLs expire; see
     * [onPlaybackError]. One fresh resolve each, so a server that is genuinely failing still fails.
     */
    private val freshlyResolvedSources = mutableSetOf<EpisodeSourceKey>()

    /**
     * What each server/audio pair offers, keyed by provider+category and *not* by episode.
     *
     * Sampling one episode is enough: a provider's track lineup for a series does not change
     * between its episodes (checked live — AniBD returned exactly `[en]` for Dandadan episodes 1,
     * 2 and 5). Keying by episode would multiply the resolves by the episode count for data that
     * comes back identical every time.
     */
    private val sourceCapabilities = mutableMapOf<Pair<String, SourceVariant>, SourceCapabilities>()
    private val failedStreamUrls = mutableSetOf<String>()
    /** Konoha's episode titles and stills, kept so every spine rebuild carries them. */
    private var episodeMeta: List<EpisodeMetadata> = emptyList()
    private var resolveJob: Job? = null
    private var episodeMetaJob: Job? = null
    private var anivexaMergeJob: Job? = null
    private var fastCatalogMergeJob: Job? = null
    private var miruroLateMergeJob: Job? = null
    private var miruroRecoveryJob: Job? = null
    private var miruroAutoRetryAttempted = false
    private var miruroRecoveryPending = false
    private var sourceValidationJob: Job? = null
    private var sourceValidationIsExhaustive = false
    private var skipLookupJob: Job? = null
    private var skipLookupKey: SkipLookupKey? = null
    private var subtitleReachabilityJob: Job? = null
    private var borrowedSubtitlesJob: Job? = null
    private var mergedIncludesAnivexa = false
    private var deferSupplementalCatalog = false
    private var mergedEpisodes = EpisodesResult(emptyList())

    fun start(
        id: Int,
        providerName: String,
        categoryApi: String,
        episodeNumber: String,
        externalPlayer: Boolean = false,
        autoAdvanced: Boolean = false,
        startAtMs: Long? = null,
    ) {
        val key = "$id/$providerName/$categoryApi/$episodeNumber/external=$externalPlayer/startMs=$startAtMs"
        if (key == startedKey && _state.value is UiState.Success) {
            DiagnosticsLog.event("Watch start ignored duplicate key=$key")
            return
        }
        DiagnosticsLog.event("Watch start key=$key")
        startedKey = key
        anilistId = id
        category = Category.from(categoryApi)
        sourceVariant = SourceVariant.fromRoute(categoryApi)
        requireExternalPlayer = externalPlayer
        // A season the previous one's ending moved on to is as auto-advanced as any next episode:
        // "Play when an episode opens" being off must not stop a binge at the season boundary.
        pendingAutoAdvance = autoAdvanced
        linkedStart = startAtMs?.takeIf { it > 0 }?.let { ms -> episodeNumber.toDoubleOrNull()?.let { it to ms } }
        preferred = providerName
        nextSeasonJob?.cancel()
        nextSeason = null
        nextSeasonRequested = false
        totalEpisodes = null
        seriesTitle = "Anime"
        artworkUrl = null
        seriesFormat = null
        averageScore = null
        popularity = null
        description = null
        locallyWatchedEpisodes.clear()
        scheduledRemoteProgressEpisodes.clear()
        failedSources.clear()
        stalledSources.clear()
        deferredEmbedSources.clear()
        unavailableSources.clear()
        confirmedSources.clear()
        refusedStreamHosts.clear()
        mergedIncludesAnivexa = false
        deferSupplementalCatalog = false
        episodeMeta = emptyList()

        resolveJob?.cancel()
        episodeMetaJob?.cancel()
        anivexaMergeJob?.cancel()
        fastCatalogMergeJob?.cancel()
        miruroLateMergeJob?.cancel()
        miruroRecoveryJob?.cancel()
        miruroAutoRetryAttempted = false
        miruroRecoveryPending = false
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = false
        // Runs beside source resolution: providers hand back bare numbered lists, so without this
        // the episode list here reads "Episode 5" where the Anime page shows the real title.
        episodeMetaJob = viewModelScope.launch { loadEpisodeMetadata(id) }
        resolveJob = viewModelScope.launch {
            _state.value = UiState.Loading
            _loadingStatus.value = null
            var pipeWarmSession: AutoCloseable? = null
            try {
                SettingsStore.awaitLoaded()
                // v0.1.49 was reliable because this browser was already resident. Hold one
                // bounded session instead: it starts when Watch really needs Miruro and survives
                // the catalog -> first-source hand-off, then disappears as playback begins.
                pipeWarmSession = PipeBridge.acquireWarmSession("initial-watch-$id")
                val storedPreferred = SettingsStore.preferredProvider.value
                globalPreferredProvider = storedPreferred
                preferred = preferredProviderForWatch(storedPreferred, providerName).let { chosen ->
                    // Nobody chose a server, but the viewer asked for a language one server is in:
                    // start there, as if picked. The fast catalogue is built without it, so the
                    // automatic race would otherwise begin before it has even been looked up.
                    // A title it does not carry demotes back to auto below, like any pick.
                    val byLanguage = ProviderCatalog.providerForLanguage(preferredLanguageFor(category))
                    if (chosen == DEFAULT_PREFERRED_PROVIDER && byLanguage != null) byLanguage else chosen
                }
                DiagnosticsLog.event(
                    "Watch preferred server route=$providerName stored=$storedPreferred selected=$preferred",
                )
                DiagnosticsLog.event("Watch episodes load start id=$id")
                // Fast path: both catalogs race from the start. The fast Anivexa subset (plus the
                // preferred server) is already in flight if the Miruro pipe turns out to be slow
                // or down, and the Miruro wait is capped so a dead pipe (Cloudflare timeout)
                // can't hold the loading screen hostage — a late Miruro answer merges in behind.
                // The remaining slow Anivexa scrapers fold in via launchAnivexaMerge.
                // Each fast server's row as it answers, so the quickest is usable without waiting
                // for the slowest member of the group.
                val fastSoFar = MutableStateFlow(EpisodesResult(emptyList()))
                val fastCatalog = async {
                    runCatching {
                        repo.fastAnivexaEpisodes(id, setOf(preferred), onPartial = { fastSoFar.value = it })
                    }
                        .onFailure {
                            it.rethrowIfCancellation()
                            DiagnosticsLog.throwable("Watch fast anivexa episodes failed id=$id", it)
                        }
                        .getOrDefault(EpisodesResult(emptyList()))
                }
                val miruroDeferred = async {
                    runCatching { repo.miruroEpisodes(id) }.onFailure { it.rethrowIfCancellation() }
                }
                // Nobody chose a server, so nothing needs Miruro in particular: start on whichever
                // catalogue can play the episode first, and let the resolver race what it holds.
                val autoRace = preferred == DEFAULT_PREFERRED_PROVIDER
                val initial = if (autoRace) {
                    awaitInitialAutoCatalogs(
                        miruroDeferred, fastSoFar, fastCatalog, category,
                        episodeNumber.toDoubleOrNull(), SettingsStore.preferDub.value,
                        MIRURO_CATALOG_WAIT_MS, AUTO_MIRURO_GRACE_MS,
                    )
                } else {
                    awaitInitialWatchCatalogs(
                        miruroDeferred, fastCatalog, preferred, category,
                        episodeNumber.toDoubleOrNull(), SettingsStore.preferDub.value, MIRURO_CATALOG_WAIT_MS,
                    )
                }
                val miruroResult = initial.miruro
                miruroResult?.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro episodes failed id=$id", it)
                }
                if (initial.nativeStartedFirst) {
                    DiagnosticsLog.event("Watch native catalog ready before Miruro id=$id provider=$preferred")
                } else if (miruroResult == null) {
                    DiagnosticsLog.event(
                        "Watch miruro episodes still pending after ${MIRURO_CATALOG_WAIT_MS}ms id=$id",
                    )
                }
                val miruro = miruroResult?.getOrNull() ?: EpisodesResult(emptyList())
                if (miruro.isEmpty && !initial.nativeStartedFirst) {
                    _loadingStatus.value =
                        "The main source looks down right now. Trying other sources…"
                }
                val preferredIsAnivexa =
                    ProviderCatalog.sourceOf(preferred) == ProviderCatalog.Source.ANIVEXA
                val autoStart = if (autoRace) {
                    repo.mergeProviders(miruro, initial.native ?: EpisodesResult(emptyList()))
                        .takeUnless { it.isEmpty }
                } else {
                    null
                }
                if (autoStart != null) {
                    DiagnosticsLog.event(
                        "Watch auto start id=$id nativeFirst=${initial.nativeStartedFirst} " +
                            "providers=" + autoStart.providerNames.joinToString(),
                    )
                }
                var merged = if (autoStart != null) {
                    autoStart
                } else if (!miruro.isEmpty && !preferredIsAnivexa) {
                    // Keep the small native lookup alive. Cancelling it left Anikoto behind the
                    // full scraper fan-out (or absent on TVs that defer that fan-out).
                    miruro
                } else {
                    val fast = initial.native ?: fastCatalog.await()
                    if (fast.isEmpty) {
                        repo.episodes(id).also { mergedIncludesAnivexa = true }
                    } else {
                        DiagnosticsLog.event(
                            "Watch fast catalog id=$id providers=" + fast.providerNames.joinToString(),
                        )
                        repo.mergeProviders(miruro, fast)
                    }
                }
                merged = catalogReachingEpisode(id, merged, episodeNumber.toDoubleOrNull())
                DiagnosticsLog.event(
                    "Watch episodes load success id=$id providers=" +
                        merged.providers.joinToString { provider ->
                            "${provider.name}:sub=${provider.sub.size},dub=${provider.dub.size}"
                        },
                )
                mergedEpisodes = merged
                // Prefer dub: launches carrying category=sub (typically history saved before the
                // setting was enabled) upgrade to dub when the catalog actually has the start
                // episode dubbed. In-player category switches are unaffected — this only runs on
                // screen entry — and when no dub exists the launch stays sub instead of erroring.
                if (category == Category.SUB && SettingsStore.preferDub.value) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubAvailable = merged.providers.any { provider ->
                        provider.dub.isNotEmpty() &&
                            (startNumber == null || provider.dub.any { it.number == startNumber })
                    }
                    if (dubAvailable) {
                        category = Category.DUB
                        sourceVariant = SourceVariant.DUB
                        DiagnosticsLog.event("Watch category upgraded to dub (prefer dub) id=$id")
                    }
                }
                // The mirror case: launches carrying category=dub (prefer-dub defaults from
                // Watch Now, seeded Continue Watching, or stale history) where the dub cannot
                // actually serve this episode. Without this the spine comes up empty and the
                // screen dies with "No episodes for this title" even though subs exist.
                //
                // Judged per episode, not per title. Dubs routinely lag the sub release, so a
                // long-running show has dubbed early episodes and undubbed recent ones: checking
                // only "does any dub exist" passed, then every dub server was tried in turn for an
                // episode none of them had, and the viewer waited out the whole fallback chain to
                // be told "no server found" for something available in sub all along.
                if (category == Category.DUB) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubHasEpisode = merged.providers.any { provider ->
                        // A multi-audio server counts as dub-capable through its sub list: that
                        // one file carries the English track. Without this the app decides no dub
                        // exists, drops to sub, and the player then picks Japanese — the exact
                        // complaint, with an English option sitting unused in the audio menu.
                        val list = provider.episodes(
                            ProviderCatalog.dubCapableCategory(provider.name, Category.DUB),
                        )
                        list.isNotEmpty() &&
                            (startNumber == null || list.any { it.number == startNumber })
                    }
                    val subHasEpisode = merged.providers.any { provider ->
                        provider.sub.isNotEmpty() &&
                            (startNumber == null || provider.sub.any { it.number == startNumber })
                    }
                    if (!dubHasEpisode && subHasEpisode) {
                        category = Category.SUB
                        sourceVariant = SourceVariant.GENERIC_SUB
                        DiagnosticsLog.event(
                            "Watch category fell back to sub (no dub for episode) id=$id " +
                                "episode=$episodeNumber",
                        )
                    }
                }
                repo.animeInfo(id)?.let { info ->
                    seriesTitle = info.title.preferred
                    artworkUrl = info.coverImage.best
                    seriesFormat = info.format
                    averageScore = info.averageScore
                    popularity = info.popularity
                    description = info.description
                    totalEpisodes = info.episodes
                    seriesStatus = info.status
                    DiagnosticsLog.event("Watch animeInfo success id=$id title=${seriesTitle.take(80)}")
                    launchNextSeasonLookup(info)
                }
                // Now that the category has settled, drop a stored server that has nothing to
                // offer in it — otherwise the spine is picked around a server that is about to
                // fail, and the viewer is told it is unavailable instead of just being given one
                // that works.
                preferredProviderForCategory(
                    preferred,
                    category,
                    merged,
                    episodeNumber.toDoubleOrNull(),
                ).let { usable ->
                    if (usable != preferred) {
                        DiagnosticsLog.event(
                            "Watch preferred server $preferred has no ${category.api} for " +
                                "episode=$episodeNumber id=$id; falling back to $usable",
                        )
                        preferred = usable
                    }
                }
                // The resolve that sets lastRequestedNumber has not run yet, so it would still
                // name the previous episode here.
                spine = pickSpine(merged, playing = episodeNumber.toDoubleOrNull())
                DiagnosticsLog.event(
                    "Watch spine picked size=${spine.size} preferred=$preferred category=${category.api} " +
                        "variant=${sourceVariant.route} " +
                        "first=${spine.firstOrNull()?.displayNumber ?: "none"} last=${spine.lastOrNull()?.displayNumber ?: "none"}",
                )
                if (spine.isEmpty()) {
                    AnililiError.SOURCE_NO_EPISODES.raise(attributes = mapOf("anilistId" to id, "stage" to "spine"))
                }
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                deferSupplementalCatalog = deferWatchSupplementalCatalog(AppGraph.isTv, spine.size)
                // Launch only after the initial spine/catalogue are installed; a fast late result
                // arriving during animeInfo must not be overwritten by the initial assignment.
                if (miruroResult == null) {
                    launchMiruroLateMerge(id, miruroDeferred)
                } else if (miruro.isEmpty) {
                    scheduleMiruroRecovery(id, "initial catalog failed")
                }
                if (autoStart != null || (!preferredIsAnivexa && !miruro.isEmpty)) {
                    fastCatalogMergeJob = viewModelScope.launch {
                        // Only rows not yet on screen are merged. Handing an already-listed server
                        // back through applyRefreshedCatalog would clear a failure the resolver has
                        // just recorded against it, and a server that refused would reappear as fine.
                        val applied = mergedEpisodes.providerNames.toMutableSet()
                        fun applyNew(catalog: EpisodesResult, trigger: String) {
                            val fresh = catalog.providers.filter { it.name !in applied }
                            if (fresh.isEmpty()) return
                            applied += fresh.map { it.name }
                            applyRefreshedCatalog(id, EpisodesResult(fresh), trigger, clearMiruroRecovery = false)
                        }
                        val partials = launch { fastSoFar.collect { applyNew(it, "fast-native-partial") } }
                        val done = fastCatalog.await()
                        partials.cancel()
                        applyNew(done, "fast-native")
                    }
                }
                // Long TV series keep the initial episode index, but fetch other providers only
                // on demand. Joining the lazy job on a resolve miss still loads all fallbacks.
                if (!mergedIncludesAnivexa) launchAnivexaMerge(id)
                resolveAndPlay(startNumber)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _loadingStatus.value = null
                // A failure that named itself deeper down keeps its own code: `raise` above already
                // means SRC-01, and relabelling it SRC-05 here would lose the one useful fact.
                _state.value = UiState.Error(
                    e.toFailure(
                        AnililiError.SOURCE_RESOLVE_FAILED,
                        attributes = mapOf("key" to key, "stage" to "start"),
                    ),
                )
            } finally {
                pipeWarmSession?.close()
            }
        }
    }

    /**
     * [merged], or a refetched catalogue when [number] is past everything [merged] lists.
     *
     * A catalogue that predates the episode being asked for is a stale read, not an answer. The
     * episode lists are served stale-while-revalidate — expired copies come back instantly for a
     * week while a refresh runs behind them — so the first visit to a title after a new episode
     * airs resolves against the list from before that episode existed. No provider carries the
     * number, so the walk builds zero candidates and contacts nothing: the viewer is told "no
     * playable source on any server" in about a millisecond, while the refresh that would have
     * answered lands half a second later and repairs it only for the *next* visit. That is the
     * whole of "it said there were no servers, I pressed play again and it just worked" (reported
     * 2026-09-14 against a same-day episode; the catalogue on disk was from two days earlier).
     *
     * So spend the round trip here, where it is the difference between playing and not, rather
     * than on every entry. Both halves are asked again because either can be the stale one, and
     * the answers are merged *over* what we already had: a refresh that comes back short must not
     * cost the viewer providers the stale copy still lists. A failure keeps the old catalogue —
     * this is an attempt to do better than a stale answer, never a reason to have none.
     */
    private suspend fun catalogReachingEpisode(
        id: Int,
        merged: EpisodesResult,
        number: Double?,
    ): EpisodesResult {
        if (number == null || merged.isEmpty || merged.carriesEpisode(number)) return merged
        DiagnosticsLog.event(
            "Watch catalog behind requested episode id=$id episode=${fmt(number)} " +
                "last=${merged.lastEpisodeNumber?.let { fmt(it) } ?: "none"}",
        )
        // Held here rather than left to the caller because the retry path has no session of its
        // own; the lease is counted, so the one `start` already holds is undisturbed.
        val warmSession = PipeBridge.acquireWarmSession("catalog-refresh-$id")
        // Under the same cap the initial load puts on the pipe, and for the same reason: a
        // Cloudflare-dead Miruro takes 15s+, and the stale catalogue is still worth resolving
        // against — it may carry the episode under a provider that publishes late. Better a walk
        // that probably fails than a loading screen that certainly does nothing.
        val refreshed = try {
            withTimeoutOrNull(MIRURO_CATALOG_WAIT_MS) {
                coroutineScope {
                    val miruro = async {
                        runCatching { repo.miruroEpisodes(id, force = true) }
                            .onFailure {
                                DiagnosticsLog.throwable("Watch catalog refresh miruro failed id=$id", it)
                            }
                            .getOrDefault(EpisodesResult(emptyList()))
                    }
                    val fast = async {
                        runCatching { repo.fastAnivexaEpisodes(id, setOf(preferred), force = true) }
                            .onFailure {
                                DiagnosticsLog.throwable("Watch catalog refresh anivexa failed id=$id", it)
                            }
                            .getOrDefault(EpisodesResult(emptyList()))
                    }
                    repo.mergeProviders(miruro.await(), fast.await())
                }
            } ?: EpisodesResult(emptyList()).also {
                DiagnosticsLog.event(
                    "Watch catalog refresh timed out id=$id afterMs=$MIRURO_CATALOG_WAIT_MS",
                )
            }
        } finally {
            warmSession.close()
        }
        val combined = repo.mergeProviders(merged, refreshed)
        DiagnosticsLog.event(
            "Watch catalog refreshed id=$id episode=${fmt(number)} " +
                "reached=${combined.carriesEpisode(number)} " +
                "last=${combined.lastEpisodeNumber?.let { fmt(it) } ?: "none"} " +
                "refetched=${refreshed.providerNames.joinToString()}",
        )
        return combined
    }

    /**
     * When the Miruro pipe outlived its initial wait, keep listening: if it eventually answers,
     * fold its providers into the catalog so they become selectable, without disturbing playback
     * that already started on a fast source.
     */
    private fun launchMiruroLateMerge(id: Int, deferred: kotlinx.coroutines.Deferred<Result<EpisodesResult>>) {
        miruroLateMergeJob?.cancel()
        miruroLateMergeJob = viewModelScope.launch {
            val result = try {
                deferred.await()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Result.failure(e)
            }
            val late = result.getOrNull()
            if (late == null || late.isEmpty) {
                result.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro late catalog failed id=$id", it)
                }
                scheduleMiruroRecovery(id, "late catalog failed")
                return@launch
            }
            applyRefreshedCatalog(id, late, "late")
        }
    }

    /** Queue at most one automatic retry so a transient cold-start miss repairs the live picker. */
    private fun scheduleMiruroRecovery(id: Int, reason: String) {
        if (id != anilistId || miruroAutoRetryAttempted || miruroRecoveryJob?.isActive == true) return
        // The retry needs Miruro's hidden browser, and on a TV that browser is deliberately down
        // for as long as the player is up. Mounting one here would build it against a live decoder
        // on exactly the devices that cannot survive a renderer crash — a whole process lost, in
        // the background, to repair a picker the user may never open. It costs nothing to wait:
        // leaving the player disposes this ViewModel, and the next visit refreshes the catalog on
        // its normal cold path. The picker's own retry button still works, because that is the
        // user asking for it while looking at the failure.
        if (AppGraph.isTv && rendererCrashIsFatal()) {
            DiagnosticsLog.event(
                "Watch miruro recovery deferred id=$id reason=$reason cause=renderer-crash-fatal",
            )
            return
        }
        miruroAutoRetryAttempted = true
        setMiruroRecoveryPending(true)
        DiagnosticsLog.event(
            "Watch miruro recovery scheduled id=$id delayMs=$MIRURO_RECOVERY_DELAY_MS reason=$reason",
        )
        miruroRecoveryJob = viewModelScope.launch {
            delay(MIRURO_RECOVERY_DELAY_MS)
            refreshMiruroCatalog(id, "automatic")
        }
    }

    /**
     * Everything behind the episode on screen, asked for again on the viewer's word: the episode
     * list and the servers that can play from it.
     *
     * Both halves of the catalogue, unlike [retryMiruroServers], which repairs a Miruro pipe that
     * failed and said so in the picker. This one is raised by someone with no failure in front of
     * them: the episode that aired an hour ago is simply not in a list cached before it did, and
     * which half of the catalogue is the stale one is not something they can know. The answers are
     * merged *over* what is already held rather than replacing it, so a refresh that comes back
     * short cannot cost them providers the stale copy still lists.
     *
     * It mounts Miruro's hidden browser while a stream is playing, which [scheduleMiruroRecovery]
     * deliberately refuses to do by itself on a television. The difference is the one the picker's
     * retry button already relies on: this is the viewer asking, now, and a refusal they did not
     * ask for is indistinguishable from a dead key.
     */
    fun refreshEpisodeCatalog() {
        val id = anilistId
        if (id <= 0) return
        DiagnosticsLog.event("Watch catalog refresh requested id=$id")
        // The same slot the retry uses, so the two cannot run at once against one pipe.
        miruroRecoveryJob?.cancel()
        miruroAutoRetryAttempted = true
        setMiruroRecoveryPending(true)
        miruroRecoveryJob = viewModelScope.launch {
            PipeBridge.prepareForRetry()
            val warmSession = PipeBridge.acquireWarmSession("manual-catalog-refresh-$id")
            try {
                // Under the cap the initial load puts on the pipe, for the reason recorded there:
                // a Cloudflare-dead Miruro takes 15s+, and the fast half is worth having on its own.
                val refreshed = withTimeoutOrNull(MIRURO_CATALOG_WAIT_MS) {
                    coroutineScope {
                        val miruro = async {
                            runCatching { repo.miruroEpisodes(id, force = true) }
                                .onFailure {
                                    DiagnosticsLog.throwable("Watch manual refresh miruro failed id=$id", it)
                                }
                                .getOrDefault(EpisodesResult(emptyList()))
                        }
                        val fast = async {
                            runCatching { repo.fastAnivexaEpisodes(id, setOf(preferred), force = true) }
                                .onFailure {
                                    DiagnosticsLog.throwable("Watch manual refresh anivexa failed id=$id", it)
                                }
                                .getOrDefault(EpisodesResult(emptyList()))
                        }
                        repo.mergeProviders(miruro.await(), fast.await())
                    }
                }
                if (refreshed == null) {
                    DiagnosticsLog.event(
                        "Watch manual refresh timed out id=$id afterMs=$MIRURO_CATALOG_WAIT_MS",
                    )
                } else if (refreshed.isEmpty) {
                    DiagnosticsLog.event("Watch manual refresh returned no providers id=$id")
                } else {
                    applyRefreshedCatalog(id, refreshed, "manual-refresh")
                }
            } finally {
                warmSession.close()
                if (id == anilistId) setMiruroRecoveryPending(false)
            }
        }
    }

    /** User-visible retry from the source picker; unlike the automatic path it starts now. */
    fun retryMiruroServers() {
        val id = anilistId
        if (id <= 0) return
        DiagnosticsLog.event("Watch miruro retry requested id=$id")
        miruroRecoveryJob?.cancel()
        miruroAutoRetryAttempted = true
        setMiruroRecoveryPending(true)
        miruroRecoveryJob = viewModelScope.launch {
            refreshMiruroCatalog(id, "manual")
        }
    }

    private suspend fun refreshMiruroCatalog(id: Int, trigger: String) {
        PipeBridge.prepareForRetry()
        val warmSession = PipeBridge.acquireWarmSession("$trigger-miruro-retry-$id")
        try {
            val refreshed = try {
                repo.miruroEpisodes(id, force = true)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch miruro $trigger retry failed id=$id", e)
                null
            }
            if (refreshed != null && !refreshed.isEmpty) {
                applyRefreshedCatalog(id, refreshed, trigger)
            } else {
                DiagnosticsLog.event("Watch miruro $trigger retry returned no providers id=$id")
            }
        } finally {
            warmSession.close()
            if (id == anilistId) setMiruroRecoveryPending(false)
        }
    }

    /**
     * Merge a freshly fetched catalogue without replacing the stream that is already playing.
     *
     * Shared by the late arrival, the retry and the viewer's own refresh, because all three are
     * the same event as far as the screen is concerned: a better list than the one it is holding.
     */
    private fun applyRefreshedCatalog(
        id: Int,
        catalog: EpisodesResult,
        trigger: String,
        clearMiruroRecovery: Boolean = true,
    ) {
        if (id != anilistId || catalog.isEmpty) return
        val recoveredProviders = catalog.providerNames.toSet()
        unavailableSources.removeAll { it.provider in recoveredProviders }
        mergedEpisodes = repo.mergeProviders(catalog, mergedEpisodes)
        spine = retainNonEmptyNavigationSpine(spine, pickSpine(mergedEpisodes))
        DiagnosticsLog.event(
            "Watch catalog $trigger merge applied id=$id providers=" +
                recoveredProviders.sorted().joinToString(),
        )
        val data = (_state.value as? UiState.Success)?.data ?: return
        val number = data.current.number
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        _state.value = UiState.Success(
            data.copy(
                episodes = spine,
                currentIndex = index,
                sourceOptions = sourceOptions(number),
                capabilities = sourceCapabilities.toMap(),
                isRetryingMiruro = if (clearMiruroRecovery) false else data.isRetryingMiruro,
            ),
        )
        launchSourceValidation(number)
    }

    private fun setMiruroRecoveryPending(pending: Boolean) {
        miruroRecoveryPending = pending
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isRetryingMiruro != pending) {
            _state.value = UiState.Success(data.copy(isRetryingMiruro = pending))
        }
    }

    /**
     * Fold the slower Anivexa providers in once they arrive, without disturbing playback that
     * already started on a Miruro source. Refreshes the navigation spine and the source list so
     * the extra servers become selectable; the active stream and resolving flag are left as-is.
     */
    private fun launchAnivexaMerge(id: Int) {
        anivexaMergeJob?.cancel()
        if (deferSupplementalCatalog) {
            DiagnosticsLog.event("Watch supplemental catalog deferred id=$id episodes=${spine.size}")
        }
        anivexaMergeJob = viewModelScope.launch(
            start = if (deferSupplementalCatalog) CoroutineStart.LAZY else CoroutineStart.DEFAULT,
        ) {
            DiagnosticsLog.event("Watch supplemental catalog started id=$id")
            val anivexa = runCatching { repo.anivexaEpisodes(id) }
                .onFailure { DiagnosticsLog.throwable("Watch anivexa merge failed id=$id", it) }
                .getOrNull()
            if (anivexa != null && !anivexa.isEmpty) {
                mergedEpisodes = repo.mergeProviders(mergedEpisodes, anivexa)
                spine = retainNonEmptyNavigationSpine(spine, pickSpine(mergedEpisodes))
                DiagnosticsLog.event(
                    "Watch anivexa merge applied id=$id providers=" + mergedEpisodes.providerNames.joinToString(),
                )
            }
            mergedIncludesAnivexa = true
            // Reflect completion whether or not Anivexa added anything: the extra servers become
            // selectable and the "loading more servers" hint clears.
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            val number = data.current.number
            val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
            _state.value = UiState.Success(
                data.copy(
                    episodes = spine,
                    currentIndex = index,
                    sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                    isLoadingMoreSources = false,
                ),
            )
            launchSourceValidation(number)
        }
    }

    /**
     * Navigation spine: the longest provider episode list that has [playing], so Next never
     * dead-ends just because the launched provider's list lags behind the others. Ties keep the
     * chosen provider; source resolution still tries the preferred provider first and falls back
     * per episode.
     */
    private fun pickSpine(
        merged: EpisodesResult,
        playing: Double? = lastRequestedNumber,
    ): List<EpisodeItem> =
        withEpisodeMetadata(
            clampSpineToStatedTotal(
                spine = pickNavigationSpine(merged, preferred, category, playing),
                statedTotal = totalEpisodes.takeIf { seriesStatus == "FINISHED" },
                keep = playing,
            ),
        )

    /**
     * [spine] wearing the catalogue's episode titles and stills, over any artwork its siblings in
     * the catalog can lend the rows that came with none.
     *
     * Every list that reaches the screen goes through here, because every one of them starts as a
     * provider's bare numbered rows and these two passes are the only things that put a name and a
     * picture on them. Missing them once is not a cosmetic slip confined to the list: the header
     * above the player reads the playing episode out of the same rows, so it drops back to
     * "Episode 5" too, and the thumbnails go blank rather than falling back to the poster — the
     * list deliberately shows nothing rather than the same frame on every row.
     *
     * The catalogue runs last so its still still outranks a provider's, which is the order the
     * overlay has always used: a provider's picture belongs to whichever server is selected.
     */
    private fun withEpisodeMetadata(spine: List<EpisodeItem>): List<EpisodeItem> {
        if (spine.isEmpty()) return spine
        val withArt = fillEpisodeFillerFromCatalog(fillEpisodeArtFromCatalog(spine, mergedEpisodes), mergedEpisodes)
        if (episodeMeta.isEmpty()) return withArt
        return mergeEpisodeMetadata(withArt, episodeMeta, anilistId)
    }

    /**
     * Konoha's episode titles and stills — the same overlay the Anime page applies. Cosmetic and
     * rate-limit-free, so any failure just leaves the provider's numbered list as it was.
     */
    private suspend fun loadEpisodeMetadata(id: Int) {
        val meta = runCatching { repo.episodeMetadata(id) }
            .onFailure { DiagnosticsLog.throwable("Watch episode metadata failed id=$id", it) }
            .getOrDefault(emptyList())
        if (meta.isEmpty() || id != anilistId) return
        episodeMeta = meta
        // The spine and not just the screen: it was picked before this answer arrived, and it is
        // what the next resolve republishes, so leaving it bare put the titles back on the next
        // episode the viewer opened.
        spine = withEpisodeMetadata(spine)
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.episodes.isEmpty()) return
        _state.value = UiState.Success(
            data.copy(episodes = mergeEpisodeMetadata(data.episodes, meta, id)),
        )
    }

    /**
     * Resolves as usual, except that an embed page is not an answer when another app is meant to
     * play the episode. Embed-only server/category pairs are skipped for this selection without
     * recording a failure or putting a working web source on cooldown.
     */
    private suspend fun resolveSourcesForPlayback(
        number: Double,
        preferredProvider: String,
        requestedVariant: SourceVariant,
        episodes: EpisodesResult,
        maxAttempts: Int = 5,
    ): MiruroRepository.SourceResolution {
        val incompatibleSources = linkedSetOf<ProviderSourceIdentity>()
        val unavailableSources = linkedSetOf<ProviderSourceIdentity>()
        // On a low-memory TV an automatic choice looks past an embed for a direct stream; see
        // [prefersDirectStreams]. What it sets aside is still the answer if nothing better turns up.
        val preferDirect = !requireExternalPlayer && prefersDirectStreams(
            isTv = AppGraph.isTv,
            isLowRamDevice = AppGraph.isLowRamDevice,
            memoryClassMb = AppGraph.memoryClassMb,
        )
        val automaticChoice = preferredProvider.equals(DEFAULT_PREFERRED_PROVIDER, ignoreCase = true)
        val heldEmbeds = linkedSetOf<ProviderSourceIdentity>()
        var firstHeldEmbed: MiruroRepository.SourceResolution? = null
        while (true) {
            val resolution = repo.resolveSources(
                anilistId = anilistId,
                number = number,
                preferred = preferredProvider,
                category = category,
                sourceVariant = requestedVariant,
                episodes = episodes,
                excludedSources = failedSources + stalledSources + deferredEmbedSources +
                    incompatibleSources + heldEmbeds,
                maxAttempts = maxAttempts,
                // The picker keeps a failed server visible, so tapping it has to reach it. What
                // stops this looping on a server that just failed is `failedSources` above, which
                // carries the pair for exactly as long as this resolve walk does.
                honorCooldownForPreferred = false,
            )
            // Each pass answers only for the servers it reached, so what was checked has to be
            // carried across the walk or the caller sees just the last pass's findings.
            unavailableSources += resolution.unavailableSources
            val resolved = resolution.resolved
            val held = firstHeldEmbed
            if (held != null && resolved == null) {
                DiagnosticsLog.event(
                    "Watch no direct stream found; playing held embed provider=${held.resolved?.provider} " +
                        "episode=${fmt(number)}",
                )
                return held.copy(unavailableSources = unavailableSources)
            }
            if (resolved != null) {
                val wouldPlayEmbed = firstUnrefusedStream(
                    provider = resolved.provider,
                    sources = resolved.sources,
                    refusedUrls = refusedStreamUrls,
                    refusedHosts = refusedStreamHosts,
                )?.isEmbed == true
                val identity = ProviderSourceIdentity(resolved.provider, resolved.variant)
                if (shouldHoldEmbedForDirectStream(preferDirect, automaticChoice, wouldPlayEmbed, heldEmbeds.size) &&
                    heldEmbeds.add(identity)
                ) {
                    if (firstHeldEmbed == null) firstHeldEmbed = resolution
                    DiagnosticsLog.event(
                        "Watch holding embed for a direct stream provider=${resolved.provider} " +
                            "variant=${resolved.variant.route} episode=${fmt(number)} held=${heldEmbeds.size}",
                    )
                    continue
                }
                // Out of passes: the first embed set aside outranked this one in the walk.
                if (held != null && wouldPlayEmbed && preferDirect && automaticChoice) {
                    DiagnosticsLog.event(
                        "Watch direct stream search exhausted; playing held embed " +
                            "provider=${held.resolved?.provider} episode=${fmt(number)}",
                    )
                    return held.copy(unavailableSources = unavailableSources)
                }
            }
            if (resolved == null || !requireExternalPlayer ||
                hasExternalPlayerStream(resolved.provider, resolved.sources)
            ) {
                return resolution.copy(unavailableSources = unavailableSources)
            }
            DiagnosticsLog.event(
                "Watch external player skipped embed provider=${resolved.provider} " +
                    "variant=${resolved.variant.route} episode=${fmt(number)}",
            )
            // Excluding the pair is what makes the next pass answer differently. A pair that comes
            // back after being excluded would loop forever, so that ends the walk instead.
            val incompatible = ProviderSourceIdentity(resolved.provider, resolved.variant)
            if (!incompatibleSources.add(incompatible)) {
                return MiruroRepository.SourceResolution(
                    resolved = null,
                    unavailableSources = unavailableSources,
                )
            }
        }
    }

    private suspend fun resolveAndPlay(number: Double) {
        failedStreamUrls.clear()
        // Survives the resolve, unlike the set above: a stream that just refused this episode is
        // still refusing it a second after the viewer changed server and changed back.
        if (number != refusedStreamEpisode) {
            refusedStreamUrls.clear()
            refusedStreamEpisode = number
        }
        skipLookupJob?.cancel()
        skipLookupJob = null
        skipLookupKey = null
        val requestedProvider = preferred
        val requestedVariant = sourceVariant
        DiagnosticsLog.event(
            "Watch resolve start id=$anilistId episode=${fmt(number)} preferred=$requestedProvider " +
                "category=${category.api} variant=${requestedVariant.route} " +
                "excluded=${failedSources.joinToString { "${it.provider}:${it.variant.route}" }}",
        )
        lastRequestedNumber = number
        val previous = (_state.value as? UiState.Success)?.data
        // Spent here, and only here, so the flag cannot outlive the advance that set it. A resolve
        // of the episode already on screen is a server failover mid-binge, not a new episode, so it
        // inherits rather than clears: on an embed the torn-down page cannot vouch for itself.
        val previousNumber = previous?.episodes?.getOrNull(previous.currentIndex)?.number
        val autoAdvanced = pendingAutoAdvance ||
            previous?.autoAdvanced == true && previousNumber == number
        pendingAutoAdvance = false
        _state.value = previous?.let { UiState.Success(it.copy(isResolving = true, notice = null)) }
            ?: UiState.Loading
        // Keep completion state paired with the exact catalog snapshot passed to the first
        // resolution. The background merge can finish while that resolution is suspended; using
        // the live flag afterward would falsely imply that the first attempt saw those providers.
        val initialCatalogIncludedFullAnivexa = mergedIncludesAnivexa
        val initialEpisodes = mergedEpisodes
        var resolution = resolveSourcesForPlayback(
            number = number,
            preferredProvider = requestedProvider,
            requestedVariant = requestedVariant,
            episodes = initialEpisodes,
        )
        val unavailableThisResolve = resolution.unavailableSources.toMutableSet()
        var resolved = resolution.resolved
        val shouldRetryMergedCatalog = shouldRetryWithMergedCatalog(
            hasResolvedSource = resolved != null,
            initialCatalogIncludedFullAnivexa = initialCatalogIncludedFullAnivexa,
        )
        if (shouldRetryMergedCatalog) {
            // The quick partial catalog missed; the slower servers may still carry this episode,
            // so wait for the full merge before declaring no source.
            DiagnosticsLog.event(
                "Watch resolve retry pending full catalog id=$anilistId episode=${fmt(number)}",
            )
            _loadingStatus.value = "Still looking — checking the remaining servers…"
            anivexaMergeJob?.join()
            // Last chance before "no source": the attempt cap only exists to bound mid-playback
            // fallback latency, so here every remaining server gets a try.
            resolution = resolveSourcesForPlayback(
                number = number,
                preferredProvider = requestedProvider,
                requestedVariant = requestedVariant,
                episodes = mergedEpisodes,
                maxAttempts = Int.MAX_VALUE,
            )
            unavailableThisResolve += resolution.unavailableSources
            resolved = resolution.resolved
        }
        unavailableThisResolve.forEach { source ->
            unavailableSources += EpisodeSourceKey(number, source.provider, source.variant)
        }
        // An embed put behind a better server was only ever second in the queue, not out of it. If
        // the servers it was put behind have come to nothing, it is now the best thing left, so the
        // deferral is dropped and the walk runs once more rather than telling a viewer there is no
        // source while a playable page sits unused.
        if (resolved == null && deferredEmbedSources.isNotEmpty()) {
            DiagnosticsLog.event(
                "Watch restoring deferred embeds episode=${fmt(number)} " +
                    "sources=${deferredEmbedSources.joinToString { "${it.provider}:${it.variant.route}" }}",
            )
            deferredEmbedSources.clear()
            resolution = resolveSourcesForPlayback(
                number = number,
                preferredProvider = requestedProvider,
                requestedVariant = requestedVariant,
                episodes = mergedEpisodes,
                maxAttempts = Int.MAX_VALUE,
            )
            resolution.unavailableSources.forEach { source ->
                unavailableSources += EpisodeSourceKey(number, source.provider, source.variant)
            }
            resolved = resolution.resolved
        }
        if (resolved == null) {
            _loadingStatus.value = null
            // The episode number rides in the attributes rather than the sentence: the viewer just
            // asked for this episode, and the catalogue keeps one wording for the banner over a
            // still-playing screen and for the dead end, so a report quoting SRC-03 matches both.
            val failure = (
                if (requireExternalPlayer) AnililiError.SOURCE_NO_EXTERNAL_STREAM
                else AnililiError.SOURCE_NONE_PLAYABLE
                ).report(
                attributes = mapOf(
                    "anilistId" to anilistId,
                    "episode" to fmt(number),
                    "requestedProvider" to requestedProvider,
                ),
            )
            _state.value = previous?.takeUnless { requireExternalPlayer }?.let {
                UiState.Success(
                    it.copy(
                        sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                        isResolving = false,
                        notice = failure.withCode(),
                    ),
                )
            } ?: UiState.Error(failure)
            return
        }
        // A later successful retry makes this option valid again for the current session.
        unavailableSources -= EpisodeSourceKey(number, resolved.provider, resolved.variant)
        confirmedSources += EpisodeSourceKey(number, resolved.provider, resolved.variant)
        val fallbackNotice = when {
            resolved.provider != requestedProvider && requestedProvider != DEFAULT_PREFERRED_PROVIDER ->
                "${ProviderCatalog.label(requestedProvider)} is unavailable for this episode. " +
                    "Playing ${ProviderCatalog.label(resolved.provider)} ${resolved.variant.label} instead."
            resolved.provider == requestedProvider && requestedVariant.isExactSubtitleVariant &&
                resolved.variant != requestedVariant ->
                "${ProviderCatalog.label(requestedProvider)} ${requestedVariant.label} is unavailable. " +
                    "Playing ${resolved.variant.label} from the same server instead."
            else -> null
        }
        if (fallbackNotice != null) {
            DiagnosticsLog.event(
                "Watch source fallback requested=$requestedProvider:${requestedVariant.route} " +
                    "actual=${resolved.provider}:${resolved.variant.route} episode=${fmt(number)}",
            )
        }
        // An empty provider `skipTimes: {}` is not capability. Normalise it to null so playback
        // can ask AniSkip after the player reports the exact duration of this encode.
        val sources = resolved.sources.let { providerSources ->
            if (providerSources.skip?.hasUsableWindow() == true) providerSources
            else providerSources.copy(skip = null)
        }
        val skipTimingStatus = when {
            sources.skip != null -> SkipTimingStatus.PROVIDER
            number % 1.0 != 0.0 -> SkipTimingStatus.UNAVAILABLE
            else -> SkipTimingStatus.WAITING_FOR_DURATION
        }
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        val resume = linkedStart?.takeIf { it.first == number }?.second
            ?: LibraryStore.historyFor(anilistId)?.resumeFor(number)?.positionMs ?: 0L
        val chosen = firstUnrefusedStream(
            provider = resolved.provider,
            sources = sources,
            refusedUrls = refusedStreamUrls,
            refusedHosts = refusedStreamHosts,
        )
        category = resolved.variant.category
        sourceVariant = resolved.variant
        // The playing server's own resolve is the most authoritative reading there is of what it
        // offers — better than the validation sweep's, which may have sampled another episode — so
        // file it before the state below hands `sourceCapabilities` to the picker.
        recordCapabilities(resolved.provider, resolved.variant, sources)
        DiagnosticsLog.event(
            "Watch resolve success provider=${resolved.provider} variant=${resolved.variant.route} " +
                "episode=${fmt(number)} index=$index " +
                "hls=${resolved.sources.hlsStreams.size} total=${resolved.sources.streams.size} " +
                "embed=${resolved.sources.embedStreams.size} subtitles=${resolved.sources.subtitles.size} " +
                "chosen=${chosen?.diagnosticLabel() ?: "none"} resumeMs=$resume " +
                "skip=${skipTimingStatus.name.lowercase()} ${sources.skip.diagnosticSummary()}",
        )
        DiagnosticsLog.event(
            "Watch source inventory provider=${resolved.provider} " +
                resolved.sources.streams.joinToString(separator = ",", limit = 16, truncated = "...") {
                    "${it.diagnosticLabel()}${if (it.isActive) "*" else ""}"
                },
        )
        _loadingStatus.value = null
        // A success carrying no episodes is not a success: WatchData.current reads
        // episodes[currentIndex], so the screen crashes the moment it draws. Three reports of
        // "Empty list doesn't contain element at index 0" arrived from exactly this, and an
        // upstream that answers with an empty catalogue is far from rare.
        if (spine.isEmpty()) {
            _state.value = UiState.Error(
                AnililiError.SOURCE_NO_EPISODES.report(
                    attributes = mapOf(
                        "anilistId" to anilistId,
                        "episode" to fmt(number),
                        "provider" to resolved.provider,
                        "stage" to "resolved",
                    ),
                ),
            )
            return
        }
        _state.value = UiState.Success(
            WatchData(
                episodes = spine,
                currentIndex = index,
                provider = resolved.provider,
                category = category,
                sourceVariant = sourceVariant,
                sourceOptions = sourceOptions(number),
                capabilities = sourceCapabilities.toMap(),
                anilistId = anilistId,
                sources = sources,
                chosenStream = chosen,
                skipTimingStatus = skipTimingStatus,
                seriesTitle = seriesTitle,
                artworkUrl = artworkUrl,
                seriesFormat = seriesFormat,
                averageScore = averageScore,
                popularity = popularity,
                description = description,
                startPositionMs = resume,
                autoAdvanced = autoAdvanced,
                nextSeason = nextSeason,
                preferredProvider = globalPreferredProvider,
                isResolving = false,
                isLoadingMoreSources = !mergedIncludesAnivexa && anivexaMergeJob?.isActive == true,
                isRetryingMiruro = miruroRecoveryPending,
                notice = fallbackNotice,
            ),
        )
        // An auto-advanced episode is about to play whatever the switch says, so its row is owed
        // straight away exactly as if the switch were on; holding it back would leave a binge
        // missing from Continue Watching until the second progress report.
        if (SettingsStore.playOnOpen.value || requireExternalPlayer || autoAdvanced) {
            recordHistory(number, resolved.provider)
        } else {
            // Opening an episode is not watching it. With "Play when an episode opens" off the
            // viewer may only be reading titles or queueing a download, so hold the Continue
            // Watching row back until playback actually runs rather than posting one for them to
            // delete afterwards.
            deferredHistory = number to resolved.provider
        }
        launchSourceValidation(number)
        launchBorrowedSubtitles(number, resolved.provider, resolved.variant, sources, chosen)
        launchSubtitleReachability(number, resolved.provider, resolved.variant, sources, chosen)
    }

    /**
     * Fetches the subtitles a provider has to borrow, once its picture is already moving.
     *
     * XFDM burns Chinese subtitles into the frame and publishes no track of its own, so English is
     * borrowed from AniChan — two requests to a different site, keyed on the same AniList id. With
     * XFDM's own streams precomputed those two were the entire remaining wait: 589ms of a 589ms
     * resolve, spent on text that will not be drawn for another half minute. The first line of
     * dialogue in the episode this was measured against arrives at 29.5s.
     *
     * So the resolve returns the stream alone and the tracks are merged in behind it, through the
     * same invisible re-prepare [launchSubtitleReachability] uses to take tracks away. The guard is
     * the same too: by the time this answers the viewer may have changed episode, server or audio
     * track, and a list fetched for one of those belongs to none of the others.
     */
    private fun launchBorrowedSubtitles(
        number: Double,
        provider: String,
        variant: SourceVariant,
        sources: SourcesResult,
        chosen: StreamItem?,
    ) {
        borrowedSubtitlesJob?.cancel()
        // Only a provider that publishes none needs any, and an embed draws its own inside the
        // page where this list can never reach it.
        if (chosen == null || chosen.isEmbed) return
        if (provider != "xfdm" || sources.subtitles.isNotEmpty()) return
        borrowedSubtitlesJob = viewModelScope.launch {
            val borrowed = repo.borrowedSubtitles(anilistId, number.toInt())
            if (borrowed.isEmpty()) return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            if (
                current.current.number != number ||
                current.provider != provider ||
                current.sourceVariant != variant
            ) return@launch
            if (current.sources.subtitles.isNotEmpty()) return@launch
            DiagnosticsLog.event(
                "Watch borrowed subtitles provider=$provider episode=${fmt(number)} " +
                    "tracks=${borrowed.size}",
            )
            _state.value = UiState.Success(
                current.copy(sources = current.sources.copy(subtitles = borrowed)),
            )
        }
    }

    /**
     * Drops the subtitle tracks this provider advertises but its host does not have.
     *
     * Runs behind playback rather than in front of it: the media item is prepared with everything
     * the provider offered, and only a track the host answers 404 or 410 for is taken back out. The
     * common case — every track real — changes nothing at all, so the episode pays a few HEAD
     * requests and no re-prepare; when something is missing, the replacement list goes through the
     * same invisible swap an attached OpenSubtitles track uses, which keeps position and playback
     * state. See [SubtitleReachability] for why the rule is that narrow.
     */
    private fun launchSubtitleReachability(
        number: Double,
        provider: String,
        variant: SourceVariant,
        sources: SourcesResult,
        chosen: StreamItem?,
    ) {
        subtitleReachabilityJob?.cancel()
        // An embed carries its own player and its own subtitle menu inside the page; this list
        // never reaches it, so probing the tracks would only spend requests on nothing.
        if (chosen == null || chosen.isEmbed) return
        if (SubtitleReachability.probable(sources.subtitles).isEmpty()) return
        subtitleReachabilityJob = viewModelScope.launch {
            val missing = SubtitleReachability.missingTracks(
                client = AppGraph.httpClient,
                tracks = sources.subtitles,
                streamReferer = chosen.referer,
            )
            if (missing.isEmpty()) return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            // The answer is about one episode on one server; anything else on screen by now has its
            // own track list, and pruning that one with these URLs would be a guess.
            if (
                current.current.number != number ||
                current.provider != provider ||
                current.sourceVariant != variant
            ) return@launch
            val kept = current.sources.subtitles.filterNot { it.url in missing }
            if (kept.size == current.sources.subtitles.size) return@launch
            DiagnosticsLog.event(
                "Watch subtitle tracks dropped provider=$provider episode=${fmt(number)} " +
                    "missing=${missing.size} kept=${kept.size}",
            )
            // A signs file that turned out to be dead takes the picker's "Has signs" with it.
            sourceCapabilities[provider to variant]?.let { capability ->
                sourceCapabilities[provider to variant] =
                    capability.copy(signsTrack = kept.any { isSignsSubtitle(it.label) })
            }
            _state.value = UiState.Success(
                current.copy(
                    sources = current.sources.copy(subtitles = kept),
                    capabilities = sourceCapabilities.toMap(),
                ),
            )
        }
    }

    /**
     * Switches the server for the episode on screen only.
     *
     * Picking a server here used to also pin it as the global preference, so reaching for a
     * working source on one awkward episode silently re-pointed every future title at it. The
     * lasting choice now lives in Settings ▸ Servers, where it can be ranked and seen.
     */
    /** The merged episode catalog, for callers that resolve episodes outside the current one. */
    fun episodeCatalog(): EpisodesResult = mergedEpisodes

    fun changeSource(providerName: String, variantRoute: String) {
        DiagnosticsLog.event("Watch changeSource requested provider=$providerName variant=$variantRoute")
        switchSource(providerName, variantRoute, rememberProvider = false)
    }

    fun changeCategory(categoryApi: String) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val providerName = data.provider
        val nextCategory = Category.from(categoryApi)
        val nextOption = availableSourceOptions(
            mergedEpisodes,
            data.current.number,
            SettingsStore.serverPriority.value,
        )
            .firstOrNull { it.provider == providerName && it.category == nextCategory }
            ?: return
        DiagnosticsLog.event("Watch changeCategory requested provider=$providerName category=$categoryApi")
        switchSource(providerName, nextOption.variant.route, rememberProvider = false)
    }

    private fun switchSource(providerName: String, variantRoute: String, rememberProvider: Boolean) {
        val requestedVariant = SourceVariant.fromRoute(variantRoute)
        val nextCategory = requestedVariant.category
        val nextVariant = if (requestedVariant == SourceVariant.GENERIC_SUB) {
            ProviderCatalog.sourceVariants(providerName, nextCategory).firstOrNull() ?: requestedVariant
        } else {
            requestedVariant
        }
        val provider = mergedEpisodes.provider(providerName) ?: return
        if (nextVariant !in ProviderCatalog.sourceVariants(providerName, nextCategory)) return
        val lookup = ProviderCatalog.dubCapableCategory(providerName, nextCategory)
        val nextSpine = provider.episodes(lookup).takeIf { it.isNotEmpty() } ?: return
        val current = (_state.value as? UiState.Success)?.data
        val currentNumber = current?.current?.number ?: lastRequestedNumber
        // Source controls are episode-scoped. Never let a stale selection jump the viewer to the
        // first episode merely because that server/audio pair lacks the episode being watched.
        if (nextSpine.none { it.number == currentNumber }) return

        // Steer this resolve at the server the viewer just picked. Keeping the pick out of
        // `preferred` was what stopped it pinning the global preference, but `preferred` is also
        // the only thing `resolveAndPlay` passes to the resolver — so the pick was dropped
        // entirely and resolution fell back to whichever server answered first. Choosing a dub
        // server that way kept landing on a sub-only server's stream, i.e. Japanese audio.
        preferred = providerName
        if (rememberProvider) {
            globalPreferredProvider = providerName
            SettingsStore.setPreferredProvider(providerName)
        }
        if (current?.provider == providerName && current.sourceVariant == nextVariant) {
            if (rememberProvider) {
                _state.value = UiState.Success(
                    current.copy(
                        preferredProvider = providerName,
                        notice = "${ProviderCatalog.label(providerName)} is now your preferred server.",
                    ),
                )
            }
            return
        }
        category = nextCategory
        sourceVariant = nextVariant
        // The picked server's own rows, so they need the overlay the same as any other spine —
        // without it, choosing a server emptied the episode list of every title and thumbnail and
        // renamed the episode playing above it back to its number. And the same trim, so picking
        // the server that lists two cours does not add the second one to a finished series.
        spine = withEpisodeMetadata(
            clampSpineToStatedTotal(
                spine = nextSpine,
                statedTotal = totalEpisodes.takeIf { seriesStatus == "FINISHED" },
                keep = currentNumber,
            ),
        )
        failedSources.clear()
        stalledSources.clear()
        deferredEmbedSources.clear()
        if (current != null) {
            _state.value = UiState.Success(
                current.copy(
                    preferredProvider = globalPreferredProvider,
                    notice = if (rememberProvider) {
                        "${ProviderCatalog.label(providerName)} is now your preferred server."
                    } else {
                        current.notice
                    },
                ),
            )
        }
        launchResolve(currentNumber)
    }

    /** Episode and server whose Continue Watching row is waiting on playback to actually start. */
    private var deferredHistory: Pair<Double, String>? = null

    /** Posts a held-back row once the episode it belongs to is genuinely running. */
    private fun commitDeferredHistory(number: Double) {
        val (pendingNumber, provider) = deferredHistory ?: return
        if (pendingNumber != number) return
        deferredHistory = null
        recordHistory(pendingNumber, provider)
    }

    private fun recordHistory(number: Double, provider: String) {
        val ep = spine.firstOrNull { it.number == number }
        val existing = LibraryStore.historyFor(anilistId)
        val resume = existing?.resumeFor(number)
        LibraryStore.upsertHistory(
            HistoryEntry(
                anilistId = anilistId,
                title = seriesTitle,
                cover = artworkUrl,
                episodeNumber = number,
                episodeTitle = ep?.title,
                provider = provider,
                category = sourceVariant.route,
                positionMs = resume?.positionMs ?: 0L,
                durationMs = resume?.durationMs ?: 0L,
                // This rebuilds the row from scratch on every resolve, so anything the row
                // accumulates has to be carried across explicitly or it is silently dropped.
                watchedEpisodes = existing?.watchedEpisodes.orEmpty(),
                resumePoints = existing?.resumePoints.orEmpty(),
            ),
        )
    }

    private var lastProgressSave = 0L
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private var lastKnownNumber: Double? = null

    /**
     * The stream a progress report was accepted for. Playback surfaces keep polling their player
     * while a new episode is being resolved, so the first tick after the episode changes can still
     * carry the *previous* stream's clock. Attributing it to the new episode marked that episode
     * watched before a single frame of it had played: picking episode 2 jumped Continue Watching
     * straight to 3, and re-opening episode 2 then resumed from 0:00 because the stored row no
     * longer pointed at it. Progress is only counted once the same stream has reported twice.
     */
    private var progressStreamUrl: String? = null
    private var progressReportsForStream = 0

    fun onProgress(streamUrl: String, positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val expected = data.chosenStream?.url
        if (expected != null && streamUrl != expected) {
            if (progressStreamUrl != streamUrl) {
                DiagnosticsLog.event(
                    "Watch progress ignored from stale stream episode=${fmt(data.current.number)} " +
                        "reportedHost=${runCatching { Uri.parse(streamUrl).host }.getOrNull() ?: "unknown"}",
                )
                progressStreamUrl = streamUrl
                progressReportsForStream = 0
            }
            return
        }
        if (progressStreamUrl != streamUrl) {
            progressStreamUrl = streamUrl
            progressReportsForStream = 0
        }
        progressReportsForStream++
        lastKnownPositionMs = positionMs
        lastKnownDurationMs = durationMs
        lastKnownNumber = data.current.number
        maybeLoadSkipTiming(data, durationMs)
        // A single report is not evidence of playback: it can be the tail of a torn-down player.
        // Watched/synced state only moves once this stream has been observed running.
        if (progressReportsForStream > 1) {
            linkedStart = null
            commitDeferredHistory(data.current.number)
            maybeAdvanceContinueWatching(data, positionMs, durationMs)
            maybeSyncRemoteProgress(data.current.number, positionMs, durationMs)
            maybePreheatNextEpisode(data, positionMs)
            publishDiscordPresence(data, positionMs, durationMs)
        }
        val now = System.currentTimeMillis()
        if (now - lastProgressSave < 8_000) return
        lastProgressSave = now
        // The row follows the episode actually playing: progress is only ever written for a stream
        // the screen chose, so past the two-reports bar above a clock for a different episode means
        // playback has outgrown wherever the row was left, and it moves rather than waiting for a
        // resolve that may never come to re-anchor it.
        LibraryStore.updateProgress(
            anilistId,
            data.current.number,
            positionMs,
            durationMs,
            followsPlayback = progressReportsForStream > 1,
        )
    }

    /**
     * The player's parting report as it is torn down, saved past the periodic throttle: the throttle
     * runs eight seconds behind, so the position the viewer closed the app on would otherwise be
     * whatever the last tick managed to keep. The report carries the stream the controller actually
     * holds, so it is only kept when that is still the stream the screen chose — the parting clock
     * of an episode the screen has since left belongs to that episode, and cannot be attributed
     * here. It counts as playback having run, so a row still pointing elsewhere follows it.
     */
    fun onFinalProgress(streamUrl: String, positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.chosenStream?.url != streamUrl || positionMs <= 0) return
        lastKnownPositionMs = positionMs
        lastKnownDurationMs = durationMs
        lastKnownNumber = data.current.number
        lastProgressSave = System.currentTimeMillis()
        LibraryStore.updateProgress(
            anilistId,
            data.current.number,
            positionMs,
            durationMs,
            followsPlayback = true,
        )
    }

    /**
     * Hands the live episode to Discord, if the viewer has connected an account and asked for it.
     *
     * Reported on the same tick as everything else rather than on an episode change, because a
     * status carries a position: [DiscordPresence] republishes only when the card genuinely
     * changes, and needs the clock to notice a seek. `paused` is false here for the same reason
     * this sits inside the guard above — a report only exists while something is playing, and
     * silence is what the presence controller reads as a pause.
     */
    private fun publishDiscordPresence(data: WatchData, positionMs: Long, durationMs: Long) {
        DiscordPresence.watching(
            DiscordWatching(
                seriesTitle = data.seriesTitle,
                episodeNumber = data.current.displayNumber,
                episodeTitle = data.current.distinctTitle,
                totalEpisodes = totalEpisodes,
                artworkUrl = data.artworkUrl,
                anilistId = data.anilistId,
                positionMs = positionMs,
                durationMs = durationMs,
                paused = false,
            ),
        )
    }

    /**
     * Leaving the player takes the Discord status down with it. Picture-in-picture and the
     * background service both keep this view model alive, so an episode that follows the viewer
     * out of the app keeps its status — which is the honest answer, since it is still playing.
     */
    override fun onCleared() {
        super.onCleared()
        DiscordPresence.stop()
    }

    /**
     * Providers frequently omit skip metadata, and different encodes can have different cuts.
     * Wait until the active player knows its duration, then let AniSkip select the matching set.
     * The lookup is background-only: it never holds up playback and can populate the button live.
     */
    private fun maybeLoadSkipTiming(data: WatchData, durationMs: Long) {
        if (data.sources.skip?.hasUsableWindow() == true) return
        if (data.current.number % 1.0 != 0.0 || durationMs < MIN_SKIP_LOOKUP_DURATION_MS) return
        val durationSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
        val key = SkipLookupKey(data.current.number, data.provider, data.sourceVariant, durationSeconds)
        if (skipLookupKey == key) return

        skipLookupJob?.cancel()
        skipLookupKey = key
        DiagnosticsLog.event(
            "Watch skip lookup start provider=${data.provider} episode=${data.current.displayNumber} " +
                "durationSec=$durationSeconds",
        )
        _state.value = UiState.Success(
            data.copy(
                skipTimingStatus = SkipTimingStatus.CHECKING,
                // A report is about the window on screen, so a fresh lookup retires it.
                skipReport = SkipReportState.IDLE,
            ),
        )
        skipLookupJob = viewModelScope.launch {
            val result = runCatching {
                repo.skipTimes(anilistId, data.current.number, durationSeconds.toDouble())
            }
            if (skipLookupKey != key) return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            if (
                current.current.number != key.episode ||
                current.provider != key.provider ||
                current.sourceVariant != key.variant
            ) return@launch

            result.onSuccess { skip ->
                if (skip?.hasUsableWindow() == true) {
                    DiagnosticsLog.event(
                        "Watch skip lookup success provider=${key.provider} " +
                            "episode=${current.current.displayNumber} durationSec=$durationSeconds " +
                            skip.diagnosticSummary(),
                    )
                    _state.value = UiState.Success(
                        current.copy(
                            sources = current.sources.copy(skip = skip),
                            skipTimingStatus = SkipTimingStatus.ANISKIP,
                        ),
                    )
                } else {
                    DiagnosticsLog.event(
                        "Watch skip lookup unavailable provider=${key.provider} " +
                            "episode=${current.current.displayNumber} durationSec=$durationSeconds",
                    )
                    _state.value = UiState.Success(
                        current.copy(skipTimingStatus = SkipTimingStatus.UNAVAILABLE),
                    )
                }
            }.onFailure { error ->
                // Reported under SUB-09 rather than logged as prose: skip windows going missing is a
                // whole-service fault when it happens, and counting it needs one code. The row keeps
                // its own quiet wording — nothing here is worth a code in front of a viewer who only
                // wanted to skip an opening.
                AnililiError.SKIP_TIMINGS_UNAVAILABLE.report(
                    cause = error,
                    attributes = mapOf(
                        "provider" to key.provider,
                        "episode" to current.current.displayNumber,
                        "durationSec" to durationSeconds,
                        "stage" to "lookup",
                    ),
                )
                _state.value = UiState.Success(
                    current.copy(skipTimingStatus = SkipTimingStatus.SERVICE_ERROR),
                )
            }
        }
    }

    /**
     * Downvotes every AniSkip segment behind the window currently on screen.
     *
     * Only AniSkip's own timings can be reported: a provider's are baked into its payload and it has
     * no endpoint that would hear about them. Each segment carries its own id, so an episode with a
     * bad opening and a good ending is voted on as the two separate submissions AniSkip stores.
     */
    fun reportSkipTiming() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.skipReport == SkipReportState.SENDING) return
        val segments = data.sources.skip?.aniSkipSegmentIds().orEmpty()
        if (segments.isEmpty()) return

        val episode = data.current.number
        _state.value = UiState.Success(data.copy(skipReport = SkipReportState.SENDING))
        viewModelScope.launch {
            val result = runCatching {
                segments.forEach { (skipId, label) ->
                    repo.voteSkipTime(skipId, upvote = false)
                    DiagnosticsLog.event(
                        "Watch skip timing reported segment=${label.lowercase()} episode=${fmt(episode)}",
                    )
                }
            }
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            // The viewer may have moved on while the votes were in flight; the votes still counted,
            // but the row they would report into belongs to a different episode now.
            if (current.current.number != episode) return@launch
            result.onFailure { error ->
                AnililiError.SKIP_TIMINGS_UNAVAILABLE.report(
                    cause = error,
                    attributes = mapOf("episode" to fmt(episode), "stage" to "vote"),
                )
            }
            _state.value = UiState.Success(
                current.copy(
                    skipReport = if (result.isSuccess) SkipReportState.SENT else SkipReportState.FAILED,
                ),
            )
        }
    }

    /**
     * TV tears the inline player down when leaving fullscreen. Persist the position it reached —
     * bypassing the periodic-save throttle — and fold it into [WatchData.startPositionMs], so the
     * next play (fullscreen pill, the inline play button, or re-picking the same episode chip)
     * resumes there instead of restarting from the stale resolve-time position.
     */
    fun commitPlaybackPosition() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val number = lastKnownNumber ?: return
        if (number != data.current.number || lastKnownPositionMs <= 0) return
        lastProgressSave = System.currentTimeMillis()
        LibraryStore.updateProgress(anilistId, number, lastKnownPositionMs, lastKnownDurationMs)
        DiagnosticsLog.event(
            "Watch commit position episode=${fmt(number)} positionMs=$lastKnownPositionMs",
        )
        _state.value = UiState.Success(data.copy(startPositionMs = lastKnownPositionMs))
    }

    /**
     * Hand the finished episode to ListProgressSyncWorker rather than posting it from here.
     *
     * This used to fork on the service: MAL was queued, AniList went out on viewModelScope. The
     * viewer leaving the player is what tears that scope down, and the watched threshold sits
     * roughly where a viewer skips the credits — so the mutation was regularly cancelled in flight. A
     * timeout or a 429 dropped it just as quietly, and nothing retried, because the account's
     * count only moves again on the *next* episode.
     */
    private fun maybeSyncRemoteProgress(episodeNumber: Double, positionMs: Long, durationMs: Long) {
        if (!LibraryStore.canSyncProgress(anilistId)) return
        if (!shouldSyncAniListProgress(episodeNumber, positionMs, durationMs)) return
        val episode = episodeNumber.toInt()
        if (!scheduledRemoteProgressEpisodes.add(episode)) return
        runCatching {
            LibraryStore.enqueueProgressSync(anilistId, episode, totalEpisodes)
        }.onFailure { error ->
            scheduledRemoteProgressEpisodes.remove(episode)
            DiagnosticsLog.throwable(
                "Watch progress enqueue failed id=$anilistId episode=${fmt(episodeNumber)}",
                error,
            )
        }
    }

    /**
     * The episode on screen as an external player hand-off, so what that player reports back moves
     * the row and the account exactly as [maybeAdvanceContinueWatching] and [maybeSyncRemoteProgress]
     * would have.
     */
    internal fun externalPlaybackSession(data: WatchData, durationMsHint: Long = 0L): ExternalPlaybackSession {
        val next = data.nextIndex(SettingsStore.autoSkipFiller.value)?.let(data.episodes::getOrNull)
        return ExternalPlaybackSession(
            anilistId = anilistId,
            episodeNumber = data.current.number,
            durationMsHint = durationMsHint,
            totalEpisodes = totalEpisodes?.takeIf { it > 0 },
            nextEpisode = next?.number,
            nextEpisodeTitle = next?.distinctTitle,
        )
    }

    /**
     * Keep the local row aligned with what the viewer actually finished, regardless of whether
     * AniList/MAL syncing is enabled or currently reachable.
     */
    private fun maybeAdvanceContinueWatching(data: WatchData, positionMs: Long, durationMs: Long) {
        val watchedNumber = data.current.number
        if (!shouldSyncAniListProgress(watchedNumber, positionMs, durationMs)) return
        val episode = watchedNumber.toInt()
        if (!locallyWatchedEpisodes.add(episode)) return
        // Continue Watching offers what Next would open, so with filler skipped it skips it too.
        val next = data.nextIndex(SettingsStore.autoSkipFiller.value)?.let(data.episodes::getOrNull)
        val seriesCompleted = totalEpisodes?.takeIf { it > 0 }?.let { episode >= it } == true
        LibraryStore.markEpisodeWatched(
            anilistId = anilistId,
            watchedEpisode = watchedNumber,
            nextEpisode = next?.number,
            nextEpisodeTitle = next?.distinctTitle,
            seriesCompleted = seriesCompleted,
            totalEpisodes = totalEpisodes,
        )
    }

    /**
     * Resolves the next episode's stream in the background while this one plays.
     *
     * Deliberately not on TV, and that is the whole design. A TV box has a 96–192 MB heap, the
     * resolver is a hidden WebView, and a hidden WebView that touches media has already been
     * observed taking the hardware decoder away from the playing video (OMX 0x80001013,
     * "preempted"). TV device reports also run at ~15% janky frames against ~1% on phones, so
     * there is no headroom to spend on speculative work; the episode catalog it would otherwise
     * want is already served from the repository cache.
     *
     * The wait until [PREHEAT_AFTER_MS] is what keeps this from competing with the *current*
     * episode's own startup, which is the moment the viewer actually feels.
     */
    private fun maybePreheatNextEpisode(data: WatchData, positionMs: Long) {
        if (AppGraph.isTv) return
        if (positionMs < PREHEAT_AFTER_MS) return
        // The episode moving on will actually open, so a filler run being skipped is not preheated.
        val next = data.nextIndex(SettingsStore.autoSkipFiller.value)?.let(data.episodes::getOrNull) ?: return
        if (!preheatedEpisodes.add(next.number)) return
        viewModelScope.launch {
            runCatching {
                repo.preheatSources(
                    anilistId = anilistId,
                    number = next.number,
                    preferred = data.provider,
                    category = data.category,
                    sourceVariant = data.sourceVariant,
                    episodes = mergedEpisodes,
                )
            }.onFailure {
                it.rethrowIfCancellation()
                // Speculative work; the real resolve will surface anything that matters.
                preheatedEpisodes.remove(next.number)
            }
        }
    }

    fun playIndex(
        index: Int,
        autoAdvance: Boolean = false,
        externalPlayer: Boolean = requireExternalPlayer,
    ) {
        DiagnosticsLog.event(
            "Watch playIndex requested index=$index spineSize=${spine.size} autoAdvance=$autoAdvance",
        )
        if (index !in spine.indices) {
            DiagnosticsLog.event("Watch playIndex ignored out of bounds index=$index")
            return
        }
        failedSources.clear()
        stalledSources.clear()
        deferredEmbedSources.clear()
        requireExternalPlayer = externalPlayer
        pendingAutoAdvance = autoAdvance
        launchResolve(spine[index].number)
    }

    /**
     * Gives up on handing this episode over, because no installed app accepted it.
     *
     * The built-in player takes it from here, and it is owed the whole server list: leaving the
     * requirement set would keep every embed-only server out of the next resolve, so a server the
     * viewer then picks by name would be quietly answered with a different one.
     */
    fun dropExternalPlayerRequirement() {
        if (!requireExternalPlayer) return
        DiagnosticsLog.event("Watch external player unavailable; built-in player keeps the episode")
        requireExternalPlayer = false
    }

    fun next() {
        DiagnosticsLog.event("Watch next requested")
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (!data.hasNext && data.continuesIntoNextSeason) {
            requestNextSeason(data, autoAdvance = false)
            return
        }
        moveOn(data, autoAdvance = false, skipFiller = SettingsStore.autoSkipFiller.value)
    }

    /**
     * The player's "Filler · Skip" tag: on to the next episode that is not filler, whether or not
     * "Skip filler episodes" is on — the press is the viewer asking for exactly that.
     */
    fun skipFiller() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        DiagnosticsLog.event("Watch skip filler requested episode=${fmt(data.current.number)}")
        if (!data.canSkipFiller) return
        moveOn(data, autoAdvance = false, skipFiller = true)
    }

    /** Next or auto-play, with the filler after this episode passed over when [skipFiller] says so. */
    private fun moveOn(data: WatchData, autoAdvance: Boolean, skipFiller: Boolean) {
        if (data.advancesIntoNextSeason(skipFiller)) {
            requestNextSeason(data, autoAdvance = autoAdvance)
            return
        }
        val index = data.nextIndex(skipFiller) ?: return
        if (index > data.currentIndex + 1) {
            DiagnosticsLog.event(
                "Watch skipping filler from=${fmt(data.current.number)} " +
                    "to=${fmt(data.episodes[index].number)} skipped=${index - data.currentIndex - 1}",
            )
        }
        playIndex(index, autoAdvance = autoAdvance)
    }

    /**
     * The next episode taken by the player itself, at the end of this one.
     *
     * Separate from [next] so that what reaches the player is the fact of an auto-advance rather
     * than an inference drawn from it; see [WatchData.autoAdvanced].
     */
    fun autoAdvance() {
        DiagnosticsLog.event("Watch auto-advance requested")
        // The one funnel every player's auto-play reaches, the embed's included. That makes it the
        // place where "pause at the end of this episode" holds the next one back.
        if (SleepTimer.consumeEpisodeEnd()) return
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (!data.hasNext && data.continuesIntoNextSeason) {
            requestNextSeason(data, autoAdvance = true)
            return
        }
        moveOn(data, autoAdvance = true, skipFiller = SettingsStore.autoSkipFiller.value)
    }

    /**
     * Hands the screen the next season's first episode, in the audio this season was watched in.
     *
     * A new route rather than a new spine in this view model: every episode list, server, skip
     * lookup and history entry here is keyed to this season's AniList id, and a fresh route is the
     * one path that already resets all of them. The preference is passed on as asked for rather
     * than as the server that happened to answer, so a server that does not carry the next season
     * does not stand in the way of the ones that do.
     */
    private fun requestNextSeason(data: WatchData, autoAdvance: Boolean) {
        val season = data.nextSeason ?: return
        if (nextSeasonRequested) return
        nextSeasonRequested = true
        DiagnosticsLog.event(
            "Watch next season requested from=${data.anilistId} to=${season.anilistId} " +
                "label=${season.label} autoAdvance=$autoAdvance",
        )
        _nextSeasonRequests.tryEmit(
            NextSeasonRequest(
                anilistId = season.anilistId,
                provider = preferred.ifBlank { DEFAULT_PREFERRED_PROVIDER },
                category = data.sourceVariant.route,
                autoAdvanced = autoAdvance,
            ),
        )
    }

    /**
     * Finds the season after [info], for [WatchData.nextSeason].
     *
     * Beside the episode load rather than in it: the season chain is a walk of AniList relations
     * (cached per member, but a walk on a first visit), and nothing needs it until the season's
     * last episode ends. A failure only means the binge stops at the end of the season, as it
     * always did.
     */
    private fun launchNextSeasonLookup(info: Media) {
        nextSeasonJob?.cancel()
        if (info.status != "FINISHED" || info.format == "MOVIE") return
        nextSeasonJob = viewModelScope.launch {
            val series = runCatching { repo.animeSeries(info) }
                .onFailure {
                    it.rethrowIfCancellation()
                    DiagnosticsLog.throwable("Watch next season lookup failed id=${info.id}", it)
                }
                .getOrNull() ?: return@launch
            val seasons = seasonChain(info, series)
            val next = nextSeasonAfter(info, seasons) ?: return@launch
            val found = NextSeason(
                anilistId = next.id,
                label = seasonChainLabels(seasons)[next.id] ?: next.title.preferred,
                afterEpisode = info.episodes?.takeIf { it > 0 },
            )
            nextSeason = found
            DiagnosticsLog.event(
                "Watch next season found id=${info.id} next=${found.anilistId} label=${found.label} " +
                    "after=${found.afterEpisode}",
            )
            val current = _state.value as? UiState.Success ?: return@launch
            _state.value = UiState.Success(current.data.copy(nextSeason = found))
        }
    }

    fun prev() {
        DiagnosticsLog.event("Watch prev requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur - 1)
    }

    fun retry() {
        DiagnosticsLog.event("Watch retry requested episode=${fmt(lastRequestedNumber)}")
        failedSources.clear()
        stalledSources.clear()
        deferredEmbedSources.clear()
        unavailableSources.removeAll {
            it.episode == lastRequestedNumber && it.variant.category == category
        }
        launchResolve(lastRequestedNumber) {
            // The viewer is looking at a failure and asking again, so the one thing this must not
            // do is repeat the last answer. It used to re-walk the catalogue already in memory,
            // which is fine when the servers were what failed and useless when the catalogue was:
            // against one that predates the episode there are no candidates to walk, so every
            // press logged zero attempts in zero milliseconds and reprinted the same error. Only
            // refetches when the episode really is missing, so an ordinary retry still starts now.
            val reached = catalogReachingEpisode(anilistId, mergedEpisodes, lastRequestedNumber)
            if (reached !== mergedEpisodes) {
                mergedEpisodes = reached
                // The spine carries the row the player draws as "now playing"; leaving it short
                // would resolve the right episode and then present it as episode one, because the
                // current index falls back to zero when the number is not in the list.
                spine = retainNonEmptyNavigationSpine(spine, pickSpine(mergedEpisodes))
            }
        }
    }

    /** All episode resolution goes through here so a failure becomes an error state, not a crash. */
    private fun launchResolve(number: Double, before: (suspend () -> Unit)? = null) {
        DiagnosticsLog.event("Watch launchResolve episode=${fmt(number)}")
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = false
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                before?.invoke()
                resolveAndPlay(number)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _loadingStatus.value = null
                _state.value = UiState.Error(
                    e.toFailure(
                        AnililiError.SOURCE_RESOLVE_FAILED,
                        attributes = mapOf(
                            "anilistId" to anilistId,
                            "episode" to fmt(number),
                            "stage" to "resolve",
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Moves to another of the same provider's streams for this episode, for the providers that
     * publish more than one.
     *
     * [InternalFallback.MOVED] means the move was made, or that this stream had already been given
     * up on and there is nothing further to do; either way the caller stops. [EXHAUSTED] means the
     * provider is out of streams and the caller should go on to pick a different server.
     * [EMBED_DEFERRED] means it has one left but it is an embed and a proven server exists — see
     * [deferredEmbedSources].
     *
     * [allowEmbed] is false only where the alternative has actually been checked. A stall passes
     * true: it is not a claim that anything is broken, and dropping a server over one slow twenty
     * seconds is the thing [onPlaybackStalled] exists to avoid.
     */
    private fun tryInternalFallback(
        data: WatchData,
        streamUrl: String,
        positionMs: Long,
        reason: String,
        allowEmbed: Boolean = true,
    ): InternalFallback {
        val providerLabel = ProviderCatalog.label(data.provider)
        if (!failedStreamUrls.add(streamUrl)) {
            DiagnosticsLog.event(
                "Watch ignored duplicate $providerLabel stream $reason host=${Uri.parse(streamUrl).host}",
            )
            return InternalFallback.MOVED
        }
        val next = nextProviderStream(
            provider = data.provider,
            sources = data.sources,
            currentUrl = streamUrl,
            failedUrls = failedStreamUrls,
        ) ?: run {
            DiagnosticsLog.event(
                "Watch $providerLabel streams exhausted attempted=${failedStreamUrls.size}; " +
                    "trying another provider",
            )
            return InternalFallback.EXHAUSTED
        }
        if (!allowEmbed && next.isEmbed) {
            DiagnosticsLog.event(
                "Watch $providerLabel embed held back next=${next.diagnosticLabel()} " +
                    "attempted=${failedStreamUrls.size}; a confirmed server goes first",
            )
            return InternalFallback.EMBED_DEFERRED
        }
        val previous = data.sources.streams.firstOrNull { it.url == streamUrl }
        val resume = maxOf(data.startPositionMs, positionMs.coerceAtLeast(0L))
        DiagnosticsLog.event(
            "Watch $providerLabel internal fallback $reason=${previous?.diagnosticLabel() ?: "unknown"} " +
                "next=${next.diagnosticLabel()} attempted=${failedStreamUrls.size} resumeMs=$resume",
        )
        _state.value = UiState.Success(
            data.copy(
                chosenStream = next,
                startPositionMs = resume,
                playbackGeneration = data.playbackGeneration + 1,
                notice = "${previous?.label ?: "$providerLabel source"} $reason. Trying ${next.label}…",
            ),
        )
        return InternalFallback.MOVED
    }

    /**
     * Whether a server other than the one playing has been proven to answer for this episode.
     *
     * "Proven" is what [launchSourceValidation] wrote down, and it is a modest claim — a resolve
     * returned a URL, not that the URL plays. It is enough to outrank a web page that has to be
     * loaded before anyone finds out whether it has a video on it, and not enough to justify giving
     * up on the current server, which is why the embed is deferred rather than dropped.
     */
    private fun hasConfirmedFallbackProvider(data: WatchData): Boolean =
        confirmedSources.any { key ->
            key.episode == data.current.number &&
                key.provider != data.provider &&
                key !in unavailableSources
        }

    /**
     * A stream that kept buffering even after the player re-prepared it in place.
     *
     * Held apart from [onPlaybackError] because the two are not the same claim. A playback error is
     * the source saying it has nothing to give; a stall is the network or the CDN being slow, and
     * the reports that led here were all of the same shape — a server that plays fine gets dropped
     * mid-episode after one bad twenty seconds, usually onto a worse one. So a stall records no
     * failure, marks nothing unavailable, and leaves the server in the picker: it only steps past it
     * for the automatic choice being made right now.
     */
    fun onPlaybackStalled(streamUrl: String, positionMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        val streamHost = runCatching { Uri.parse(streamUrl).host }.getOrNull()
        DiagnosticsLog.event(
            "Watch playback stalled provider=${data.provider} episode=${data.current.displayNumber} " +
                "streamHost=${streamHost ?: "unknown"} positionMs=$positionMs",
        )
        // A provider that published another stream gets to use it before the server is given up on;
        // that is cheaper than a resolve and keeps the viewer put.
        if (hasProviderStreamFallback(data.sources, streamUrl) &&
            tryInternalFallback(data, streamUrl, positionMs, reason = "stalled") ==
            InternalFallback.MOVED
        ) {
            return
        }
        if (!stalledSources.add(ProviderSourceIdentity(data.provider, data.sourceVariant))) {
            DiagnosticsLog.event(
                "Watch ignored repeat stall provider=${data.provider} " +
                    "variant=${data.sourceVariant.route}",
            )
            return
        }
        sourceValidationJob?.cancel()
        launchResolve(data.current.number) {
            _state.value = UiState.Success(
                data.copy(
                    isResolving = true,
                    notice = "${ProviderCatalog.label(data.provider)} kept buffering. " +
                        "Trying another server…",
                ),
            )
        }
    }

    fun onPlaybackError(failure: AnililiFailure, streamUrl: String, positionMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        val streamHost = runCatching { Uri.parse(streamUrl).host }.getOrNull()
        DiagnosticsLog.event(
            "Watch playback error code=${failure.code} provider=${data.provider} " +
                "episode=${data.current.displayNumber} " +
                "streamHost=${streamHost ?: "unknown"} positionMs=$positionMs",
        )
        // A server whose links expire is asked for a fresh one before anything is held against it.
        // Kartoons' proxy URLs were dead within 32 minutes of being minted (2026-10-03), so a viewer
        // who pauses for half an hour comes back to a playlist that 404s — and the steps below would
        // try its other (equally expired) link, cool the server for this episode and move them to
        // another one, swapping the Hindi audio they chose for whatever that server speaks. Nor is
        // the host demoted: an expired link says nothing about the host.
        val freshKey = EpisodeSourceKey(data.current.number, data.provider, data.sourceVariant)
        if (ProviderCatalog.hasShortLivedStreamUrls(data.provider) && freshlyResolvedSources.add(freshKey)) {
            DiagnosticsLog.event(
                "Watch resolving again for a fresh link provider=${data.provider} " +
                    "episode=${data.current.displayNumber} positionMs=$positionMs",
            )
            // The resolve resumes from the library's position, which is saved on a throttle.
            if (positionMs > 0) {
                LibraryStore.updateProgress(anilistId, data.current.number, positionMs, lastKnownDurationMs)
                lastProgressSave = System.currentTimeMillis()
            }
            launchResolve(data.current.number) {
                _state.value = UiState.Success(
                    data.copy(
                        isResolving = true,
                        notice = "Refreshing the ${ProviderCatalog.label(data.provider)} link…",
                    ),
                )
            }
            return
        }
        // Recorded before anything else acts on it, and only for a real playback error — a stall
        // is the network being slow rather than the stream being wrong, and holding it against the
        // stream would drop a server that plays fine after one bad twenty seconds.
        // An embed whose page loaded but whose player never played is a failure the resolve could not
        // see — it "succeeded" in a few hundred milliseconds — so the ranking hears of it here, once
        // per stream. Without this a server that only offers a broken web player keeps its place.
        val failedEmbed = data.sources.streams.firstOrNull { it.url == streamUrl }?.isEmbed == true ||
            failure.error == AnililiError.PLAYBACK_EMBED_FAILED
        if (failedEmbed && streamUrl !in refusedStreamUrls) {
            ProviderSpeed.record(data.provider, succeeded = false, durationMs = 0L)
            DiagnosticsLog.event("Watch embed failure recorded for ranking provider=${data.provider}")
        }
        if (streamUrl.isNotBlank()) {
            refusedStreamEpisode = data.current.number
            refusedStreamUrls += streamUrl
            // The host outlives the episode; see [refusedStreamHosts] for why that is a demotion
            // rather than a ban, and why one refusal is enough to earn it.
            streamHostOf(streamUrl)?.let(refusedStreamHosts::add)
        }

        val internalFallback = if (hasProviderStreamFallback(data.sources, streamUrl)) {
            tryInternalFallback(
                data = data,
                streamUrl = streamUrl,
                positionMs = positionMs,
                reason = "failed",
                allowEmbed = !hasConfirmedFallbackProvider(data),
            )
        } else {
            InternalFallback.EXHAUSTED
        }
        if (internalFallback == InternalFallback.MOVED) return
        if (internalFallback == InternalFallback.EMBED_DEFERRED) {
            deferredEmbedSources += ProviderSourceIdentity(data.provider, data.sourceVariant)
            sourceValidationJob?.cancel()
            launchResolve(data.current.number) {
                _state.value = UiState.Success(
                    data.copy(
                        isResolving = true,
                        notice = failoverNotice(data.provider, failure),
                    ),
                )
            }
            return
        }

        val failedSource = ProviderSourceIdentity(data.provider, data.sourceVariant)
        if (!failedSources.add(failedSource)) {
            DiagnosticsLog.event(
                "Watch ignored duplicate source failure provider=${data.provider} " +
                    "variant=${data.sourceVariant.route}",
            )
            return
        }
        // A validation resolve proves only that a URL exists. Stop it before recording the player
        // failure so a concurrent successful lookup cannot make the broken stream selectable again.
        sourceValidationJob?.cancel()
        repo.recordPlaybackFailure(
            anilistId = data.anilistId,
            number = data.current.number,
            provider = data.provider,
            variant = data.sourceVariant,
            streamHost = streamHost,
            // The cooldown wants the technical account, not the viewer's line: a 5xx from an embed's
            // own page and a refused status are both in the detail, and the code the player reported
            // is the structured half of the same fact.
            message = failure.detail ?: failure.message,
            platformCode = failure.platformCode,
        )
        unavailableSources += EpisodeSourceKey(data.current.number, data.provider, data.sourceVariant)
        launchResolve(data.current.number) {
            _state.value = UiState.Success(
                data.copy(
                    isResolving = true,
                    notice = failoverNotice(data.provider, failure),
                ),
            )
        }
    }

    /**
     * What the viewer reads while the app moves to another server.
     *
     * The failure's own sentence is deliberately not repeated here. It said "Lost the connection to
     * this server", and this banner is already saying which server and that another is being tried —
     * so what is left to add is the code, for the case where every server fails and they report it.
     */
    private fun failoverNotice(provider: String, failure: AnililiFailure): String =
        "${ProviderCatalog.label(provider)} failed (${failure.code}). Trying another source…"

    /**
     * Files away the track languages a resolve turned up.
     *
     * Audio labels are provider-authored free text ("Hindi", "English Dub", "sub"), so they are
     * normalised through [normalizeLanguage], and the placeholder labels that just restate the
     * category are dropped — "sub" is not a language.
     *
     * Whether anything here can be saved offline is read off the same resolve, using the predicate
     * the download button itself obeys, so the picker's glyph and the button can no longer disagree.
     */
    private fun recordCapabilities(
        provider: String,
        variant: SourceVariant,
        sources: SourcesResult,
    ) {
        val audio = sources.streams.mapNotNull { it.audio }
            .mapNotNull(::normalizeLanguage)
            .toSet()
        val subtitles = sources.subtitles
            .mapNotNull { normalizeLanguage(it.language.ifBlank { it.label }) }
            .toSet()
        sourceCapabilities[provider to variant] = SourceCapabilities(
            audioLanguages = audio,
            subtitleLanguages = subtitles,
            downloadable = sources.streams.any(EpisodeDownloads::canDownload),
            signsTrack = sources.subtitles.any { isSignsSubtitle(it.label) },
            known = true,
        )
    }

    fun capabilitiesFor(provider: String, variant: SourceVariant): SourceCapabilities =
        sourceCapabilities[provider to variant] ?: SourceCapabilities()

    /** Finish the slower provider sweep only when the user asks to see every server. */
    fun validateRemainingSources() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        launchSourceValidation(data.current.number, exhaustive = true)
    }

    private fun sourceOptions(number: Double): List<WatchSourceOption> =
        visibleSourceOptions(
            candidates = availableSourceOptions(mergedEpisodes, number, SettingsStore.serverPriority.value),
            isConfirmed = { EpisodeSourceKey(number, it.provider, it.variant) in confirmedSources },
            isUnavailable = { EpisodeSourceKey(number, it.provider, it.variant) in unavailableSources },
        )

    /**
     * Episode catalogs can contain stale rows whose source endpoint returns no stream. Validate
     * those rows in small parallel batches and publish only confirmed server/audio pairs; this
     * keeps the picker truthful without delaying the first playable provider.
     */
    private fun launchSourceValidation(number: Double, exhaustive: Boolean = false) {
        // Probing a server can fetch another full thousand-episode catalog. Keep this work out
        // of long-series TV playback unless the viewer explicitly opens the source picker.
        if (deferSupplementalCatalog && !exhaustive) return
        if (sourceValidationIsExhaustive && sourceValidationJob?.isActive == true) {
            return
        }
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = exhaustive
        sourceValidationJob = viewModelScope.launch {
            if (exhaustive && !mergedIncludesAnivexa) {
                val data = (_state.value as? UiState.Success)?.data ?: return@launch
                _state.value = UiState.Success(data.copy(isLoadingMoreSources = true))
                // join starts a deferred merge and also waits for an already running one.
                anivexaMergeJob?.join()
                val current = (_state.value as? UiState.Success)?.data ?: return@launch
                if (current.current.number != number || current.isResolving) return@launch
            }
            // Validation is useful for a truthful picker but competes with video startup. Every
            // device gives playback a head start; phones use two probes at once and low-RAM TVs one.
            // The automatic pass stops after two fallbacks (or six attempts). Opening the source
            // picker explicitly requests the remaining options.
            if (!exhaustive) kotlinx.coroutines.delay(SOURCE_VALIDATION_START_DELAY_MS)
            val validationConcurrency = sourceValidationConcurrency(AppGraph.isTv)
            val allOptions = availableSourceOptions(mergedEpisodes, number, SettingsStore.serverPriority.value)
            val activeProvider = (_state.value as? UiState.Success)?.data?.provider
            val confirmedFallbackProviders = allOptions
                .filter { option ->
                    option.provider != activeProvider &&
                        EpisodeSourceKey(number, option.provider, option.variant) in confirmedSources
                }
                .mapTo(mutableSetOf(), WatchSourceOption::provider)
            val fallbackTarget = if (exhaustive) {
                Int.MAX_VALUE
            } else {
                (SOURCE_VALIDATION_FALLBACK_TARGET - confirmedFallbackProviders.size).coerceAtLeast(0)
            }
            val candidates = allOptions.filter { option ->
                val key = EpisodeSourceKey(number, option.provider, option.variant)
                if (key in confirmedSources || key in unavailableSources) return@filter false
                validatesDuringPlayback(option.provider) &&
                    (exhaustive || option.provider != activeProvider)
            }.take(sourceValidationCandidateLimit(exhaustive))
            if (candidates.isEmpty() || fallbackTarget == 0) {
                val data = (_state.value as? UiState.Success)?.data
                if (data != null && data.current.number == number && mergedIncludesAnivexa) {
                    _state.value = UiState.Success(data.copy(isLoadingMoreSources = false))
                }
                return@launch
            }

            val initial = (_state.value as? UiState.Success)?.data
            if (initial != null && initial.current.number == number) {
                _state.value = UiState.Success(initial.copy(isLoadingMoreSources = true))
            }
            val providerNames = mergedEpisodes.providerNames.toSet()
            val newlyConfirmedFallbackProviders = mutableSetOf<String>()
            candidates.chunked(validationConcurrency).forEachIndexed { batchIndex, batch ->
                val results = batch.map { option ->
                    async {
                        val resolution = runCatching {
                            repo.resolveSources(
                                anilistId = anilistId,
                                number = number,
                                preferred = option.provider,
                                category = option.category,
                                sourceVariant = option.variant,
                                episodes = mergedEpisodes,
                                excludedProviders = providerNames - option.provider,
                                maxAttempts = 1,
                                allowInteractiveChallenges = false,
                            )
                        }.onFailure {
                            // This pass is stopped on purpose — a playback failure cancels it so a
                            // probe cannot make a broken stream selectable again — and the viewer
                            // leaving stops it too. Neither is a provider that failed, and a probe
                            // interrupted mid-read reports an ordinary IOException rather than a
                            // cancellation, so the log has to ask the job before it names a server.
                            it.rethrowIfCancellation()
                            currentCoroutineContext().ensureActive()
                            DiagnosticsLog.throwable(
                                "Watch source validation failed provider=${option.provider} " +
                                    "category=${option.category.api} variant=${option.variant.route} " +
                                    "episode=${fmt(number)}",
                                it,
                            )
                        }.getOrNull()
                        val resolved = resolution?.resolved?.takeIf { it.provider == option.provider }
                        // Keep what the resolve told us about the tracks, not just whether it
                        // worked. This costs nothing extra — the request already happened.
                        resolved?.let { recordCapabilities(option.provider, option.variant, it.sources) }
                        option to (resolved != null)
                    }
                }.awaitAll()

                if (lastRequestedNumber != number) return@launch
                results.forEach { (option, available) ->
                    val key = EpisodeSourceKey(number, option.provider, option.variant)
                    if (available) {
                        confirmedSources += key
                        unavailableSources -= key
                    } else {
                        unavailableSources += key
                    }
                }
                results.filter { (_, available) -> available }
                    .mapTo(newlyConfirmedFallbackProviders) { (option, _) -> option.provider }
                val data = (_state.value as? UiState.Success)?.data ?: return@launch
                if (data.current.number != number) return@launch
                val reachedTarget = !exhaustive &&
                    newlyConfirmedFallbackProviders.size >= fallbackTarget
                val hasMore = !reachedTarget &&
                    (batchIndex + 1) * validationConcurrency < candidates.size
                _state.value = UiState.Success(
                    data.copy(
                        sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                        isLoadingMoreSources = !mergedIncludesAnivexa || hasMore,
                    ),
                )
                if (reachedTarget) {
                    DiagnosticsLog.event(
                        "Watch source validation paused episode=${fmt(number)} " +
                            "confirmedFallbacks=" +
                            "${confirmedFallbackProviders.size + newlyConfirmedFallbackProviders.size}",
                    )
                    return@launch
                }
            }
        }
    }

    private fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun StreamItem.diagnosticLabel(): String {
        val type = when {
            isEmbed -> "embed"
            isHls -> "hls"
            isDash -> "dash"
            else -> "direct"
        }
        return "$type label=${label.take(48)} audio=${audio ?: "unknown"} " +
            "height=${height ?: "auto"} host=${runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"}"
    }
}

/**
 * Which of the episode's server/audio pairs are shown in the picker. Fast providers (the Miruro
 * pipe's native HLS set and the API-backed Anivexa lookups) appear the moment the catalog has
 * them — quick and reliable, no waiting on a per-server validation round-trip. Slower scrapers
 * must be confirmed playable first so the list never advertises a dead endpoint. Anything proven
 * unavailable for this episode is always hidden, even a fast one that failed validation.
 *
 * Except that "confirmed first" and [validatesDuringPlayback] together were a deadlock. A provider
 * the sweep deliberately never runs can never be confirmed, so requiring confirmation hid it for
 * good: AllAnime and ReAnime were in the catalogue, carried the episode, and could not be picked
 * on any device — and a third source looked like a resolver failure for a whole session when it had
 * simply never been offered. Those are shown unconfirmed instead. The honesty this trades away is
 * small and bounded: they sit last, a viewer reaches one deliberately,
 * and a dead one fails over to the next server, whereas a server nobody can select is not honest
 * either.
 *
 * And a server that *did* answer once and then failed is not removed either — it is moved to the
 * end and marked [WatchSourceOption.recentlyFailed]. Dropping it is what the "servers disappear"
 * reports were: every failure puts the pair on a 1-10 minute cooldown, the cooldown is reported
 * back as unavailable on the next resolve, and the row then stayed gone for the rest of the screen
 * because this set has no expiry while the cooldown does. Leaving the screen cleared it and
 * returning re-applied whichever timers were still running, so the list a viewer came back to
 * depended on the clock. Measured across five diagnostics bundles from 2026-09-11: eighteen
 * distinct server/variant pairs hidden this way, re-applied over 218 resolves — kiwi, ally, nun,
 * kaa, animegg and animekai among them, and `ally:hsub` on its own for 36 of them. A faded row
 * that can be picked again says what happened; a missing one says the app lost the server.
 */
internal fun visibleSourceOptions(
    candidates: List<WatchSourceOption>,
    isConfirmed: (WatchSourceOption) -> Boolean,
    isUnavailable: (WatchSourceOption) -> Boolean,
): List<WatchSourceOption> {
    // Whether the pair has earned a place at all is decided first, and unchanged: a slow scraper
    // the sweep has never confirmed is still not advertised, whether or not it is also cooling.
    // Only rows that were offered and then failed are demoted rather than dropped.
    val offered = candidates.filter { option ->
        isConfirmed(option) ||
            ProviderCatalog.isFast(option.provider) ||
            !validatesDuringPlayback(option.provider) ||
            // A server in another language is listed as soon as the catalogue has it. The sweep
            // reaches it last, if at all, and a viewer looking under Spanish should not find the
            // one Spanish server missing until then.
            ProviderCatalog.declaredLanguage(option.provider) != null
    }
    val (failed, working) = offered.partition(isUnavailable)
    return working + failed.map { it.copy(recentlyFailed = true) }
}

/**
 * Only server/audio pairs carrying the episode currently on screen are valid controls.
 *
 * [priority] is the viewer's own ranking from Settings. It leads, because a list someone arranged
 * by hand is a stronger signal than any measurement: the resolver already tries their order, and
 * the picker showing a different one made the two disagree about what "first" means. Everything
 * they did not rank follows [ProviderCatalog.defaultPriority], then the catalogue's speed-first order.
 */
internal fun availableSourceOptions(
    episodes: EpisodesResult,
    number: Double,
    priority: List<String> = emptyList(),
    speedScore: (String) -> Double = com.anilili.data.ProviderSpeed::score,
): List<WatchSourceOption> = episodes.providers
    .flatMap { provider ->
        provider.categories.flatMap { category ->
            val providerEpisodes = provider.episodes(category)
            val hasCurrentEpisode = providerEpisodes.any { it.number == number }
            if (!hasCurrentEpisode) return@flatMap emptyList()
            ProviderCatalog.sourceVariants(provider.name, category).map { variant ->
                WatchSourceOption(
                    provider = provider.name,
                    category = category,
                    hasCurrentEpisode = true,
                    episodeCount = providerEpisodes.size,
                    variant = variant,
                )
            }
        }
    }
    .sortedWith(
        compareBy<WatchSourceOption> { option ->
            priority.indexOfFirst { it.equals(option.provider, ignoreCase = true) }
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
            // The catalogue's default priority, then what this device has measured, then the
            // catalogue's own order to break ties — so a fresh install, with nothing measured,
            // still lists fast servers first.
            .thenBy { ProviderCatalog.defaultPriorityRank(it.provider) }
            .thenBy { speedScore(it.provider) }
            .thenBy { ProviderCatalog.sortKey(it.provider) }
            .thenBy { it.category.ordinal }
            .thenBy { option ->
                ProviderCatalog.sourceVariants(option.provider, option.category).indexOf(option.variant)
            },
    )

/**
 * Whether this position counts the episode as watched — for Continue Watching and for the list
 * services alike.
 *
 * The share of the episode is the viewer's own setting; the caller normally leaves
 * [thresholdPercent] alone so every code path reads the same preference.
 */
internal fun shouldSyncAniListProgress(
    episodeNumber: Double,
    positionMs: Long,
    durationMs: Long,
    thresholdPercent: Int = SettingsStore.watchedThresholdPercent.value,
): Boolean {
    if (episodeNumber < 1 || episodeNumber % 1.0 != 0.0) return false
    if (durationMs < MIN_ANILIST_SYNC_DURATION_MS || positionMs <= 0) return false
    val fraction = thresholdPercent
        .coerceIn(WATCHED_THRESHOLD_MIN_PERCENT, WATCHED_THRESHOLD_MAX_PERCENT) / 100.0
    return positionMs.toDouble() / durationMs >= fraction
}

private const val MIN_ANILIST_SYNC_DURATION_MS = 60_000L

/**
 * How far into an episode the next one is resolved ahead of time.
 *
 * Late enough that the current episode's own startup is finished and the viewer has clearly
 * settled on this title, early enough to be ready long before the credits.
 */
private const val PREHEAT_AFTER_MS = 90_000L
/** Let fallback playback settle before one bounded background resolver recovery. */
private const val MIRURO_RECOVERY_DELAY_MS = 15_000L
private const val SOURCE_VALIDATION_START_DELAY_MS = 10_000L
private const val SOURCE_VALIDATION_FALLBACK_TARGET = 2
private const val SOURCE_VALIDATION_MAX_AUTOMATIC_ATTEMPTS = 6

internal fun sourceValidationConcurrency(isTv: Boolean): Int = if (isTv) 1 else 2

internal fun sourceValidationCandidateLimit(exhaustive: Boolean): Int =
    if (exhaustive) Int.MAX_VALUE else SOURCE_VALIDATION_MAX_AUTOMATIC_ATTEMPTS

private fun SkipTimes?.diagnosticSummary(): String = this?.let {
    "intro=${it.introStart ?: "none"}-${it.introEnd ?: "none"} " +
        "outro=${it.outroStart ?: "none"}-${it.outroEnd ?: "none"}"
} ?: "intro=none outro=none"

/** Provider-specific first-player policy, applied only after that provider has resolved sources. */
internal fun pickProviderStream(provider: String, sources: SourcesResult): StreamItem? {
    val direct = sources.streams.filterNot(StreamItem::isEmbed)
    val embeds = sources.embedStreams

    return when (provider) {
        // Kiwi led with its embed for a long time because its direct renditions all failed. Two
        // separate reasons were blamed and both are now settled:
        //
        // The 403 from vault-*.owocdn.top, recorded here for months, is gone. Instrumented on a
        // Galaxy S25 on 2026-08-10, the CDN answers 200 to every request the player makes —
        // playlist, key and every segment.
        //
        // What actually broke them was Kwik's AES-128 IV, which is the number in each segment's
        // filename rather than the media sequence the standard prescribes. One wrong IV corrupts
        // one byte, that byte is the PMT PID inside the PAT, and TsExtractor then emits no tracks
        // while every chunk still "succeeds" — see KwikPlaylistDataSource, which now writes the IV
        // out explicitly.
        //
        // Verified live on 2026-08-11: 1080p, tracks built, seeking and Skip Intro working. So the
        // native player leads and the embed sits behind it, which is the right way round — native
        // gets the app's own controls, subtitle delay, PiP and Cast, none of which reach inside a
        // WebView.
        "kiwi" -> bestHls(direct)
            ?: direct.firstOrNull()
            ?: embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()

        // Ally mixes AllAnime progressive files with an unreliable HLS mirror. Its direct files
        // are independently playable and retain the selected SUB/DUB category.
        "ally" -> direct.firstOrNull { !it.isHls }
            ?: bestHls(direct)
            ?: embeds.firstOrNull()

        else -> bestHls(direct)
            ?: direct.firstOrNull()
            ?: embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: sources.streams.firstOrNull()
    }
}

/** Whether the provider resolved a media URL rather than a web player page. */
internal fun hasExternalPlayerStream(provider: String, sources: SourcesResult): Boolean =
    pickProviderStream(provider, sources)?.isEmbed == false

/** Every distinct stream in the same order the provider's first-player policy would choose it. */
internal fun providerStreamOrder(
    provider: String,
    sources: SourcesResult,
): List<StreamItem> {
    val remaining = sources.streams.distinctBy(StreamItem::url).toMutableList()
    return buildList {
        while (remaining.isNotEmpty()) {
            val next = pickProviderStream(provider, sources.copy(streams = remaining)) ?: break
            add(next)
            remaining.removeAll { it.url == next.url }
        }
    }
}

/** True when this resolution contains another distinct stream worth trying before a new resolve. */
internal fun hasProviderStreamFallback(sources: SourcesResult, currentUrl: String): Boolean =
    currentUrl.isNotBlank() && sources.streams.any { it.url != currentUrl }

/**
 * The host part of a stream URL, lowercased and without `www.`, for comparing one against another.
 *
 * Parsed rather than handed to `Uri`, so the policy above it stays a pure function that a unit test
 * can exercise — `Uri.parse` answers null under the JVM test runtime and would quietly make every
 * host compare equal.
 */
internal fun streamHostOf(url: String): String? {
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isEmpty()) return null
    return withoutScheme.substringBefore('/')
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
        .removePrefix("www.")
        .takeIf { it.isNotEmpty() }
}

/**
 * The provider's own first choice, skipping any stream already known to have refused this episode
 * and preferring one whose host has not refused an earlier episode either.
 *
 * The two lists are held to different standards on purpose. A URL that refused is skipped outright,
 * because there is a specific thing wrong with it. A *host* that refused is only demoted: the claim
 * is weaker, it was learned from a different episode, and a provider whose every stream sits on one
 * bad host still has to be given the stream it has. Same reasoning at the end — when everything has
 * refused, the ordinary first choice is made and the view model's fallback ladder runs from there,
 * so this can never leave an episode with nothing.
 */
internal fun firstUnrefusedStream(
    provider: String,
    sources: SourcesResult,
    refusedUrls: Set<String>,
    refusedHosts: Set<String> = emptySet(),
): StreamItem? {
    val untried = providerStreamOrder(provider, sources).filter { it.url !in refusedUrls }
    return untried.firstOrNull { streamHostOf(it.url) !in refusedHosts }
        ?: untried.firstOrNull()
        ?: pickProviderStream(provider, sources)
}

/** Finds the next untried stream after the one that failed, wrapping once before giving up. */
internal fun nextProviderStream(
    provider: String,
    sources: SourcesResult,
    currentUrl: String,
    failedUrls: Set<String>,
): StreamItem? {
    val ordered = providerStreamOrder(provider, sources)
    if (ordered.isEmpty()) return null
    val currentIndex = ordered.indexOfFirst { it.url == currentUrl }
    val candidates = if (currentIndex >= 0) {
        ordered.drop(currentIndex + 1) + ordered.take(currentIndex)
    } else {
        ordered
    }
    return candidates.firstOrNull { it.url !in failedUrls }
}

/** A saved global choice wins; before the first explicit choice, keep the launch route behavior. */
internal fun preferredProviderForWatch(storedPreferred: String?, routeProvider: String): String {
    val stored = storedPreferred?.trim()?.lowercase().orEmpty()
    if (stored.isNotBlank() && stored != DEFAULT_PREFERRED_PROVIDER) return stored
    return routeProvider.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
}

/**
 * Gives up a stored server that the catalog says cannot serve this episode in this category.
 *
 * A preference only means anything among servers that could answer the request. RareAnimes is
 * dub-only by construction, so a sub launch with it stored resolved it, failed, and told the
 * viewer "RareAnimes is unavailable for this episode" on a title six other servers had — and did
 * that on every sub title until they went and changed the setting. Demoting to auto here lets the
 * normal ordering choose, and the viewer's choice survives for the dubs it was made for.
 *
 * Judged per episode, for the same reason the category fallbacks above are: a server that stops
 * at episode 12 is no help to someone opening episode 13. A server the catalog has not answered
 * for is left alone — absent here means "not looked up yet", not "has nothing".
 */
internal fun preferredProviderForCategory(
    preferred: String,
    category: Category,
    episodes: EpisodesResult,
    episodeNumber: Double?,
): String {
    if (preferred == DEFAULT_PREFERRED_PROVIDER) return preferred
    val provider = episodes.provider(preferred) ?: return preferred
    val listed = provider.episodes(ProviderCatalog.dubCapableCategory(preferred, category))
    val serves = listed.isNotEmpty() &&
        (episodeNumber == null || listed.any { it.number == episodeNumber })
    return if (serves) preferred else DEFAULT_PREFERRED_PROVIDER
}

/** Whether the picker already contains at least one provider from the Miruro pipe catalog. */
internal fun hasMiruroSourceOptions(options: List<WatchSourceOption>): Boolean = options.any {
    ProviderCatalog.sourceOf(it.provider) == ProviderCatalog.Source.MIRURO
}

/**
 * A merge that completes while the first resolution is suspended still requires a retry: the
 * deciding state is whether the catalog snapshot used by that resolution already contained it.
 */
internal fun shouldRetryWithMergedCatalog(
    hasResolvedSource: Boolean,
    initialCatalogIncludedFullAnivexa: Boolean,
): Boolean = !hasResolvedSource && !initialCatalogIncludedFullAnivexa

/**
 * Whether the background sweep may confirm this provider while an episode is on screen.
 * Providers that resolve through a hidden WebView each need the one engine they share. ReAnime
 * runs a real player to resolve, and even larger devices can have a single hardware decoder, so a
 * background resolve may preempt playback or leak its intro audio; AnimePahe only reads documents,
 * but a speculative resolve still queues behind whatever else is holding that engine and spends
 * Cloudflare's patience to do it. Those providers stay unvalidated on every device; selecting one
 * by hand still resolves it normally.
 */
internal fun validatesDuringPlayback(provider: String): Boolean =
    provider != "allanime" && !ProviderCatalog.requiresResolverWebView(provider)

private fun bestHls(streams: List<StreamItem>): StreamItem? = streams
    .filter(StreamItem::isHls)
    .maxByOrNull { (it.height ?: 0) + if (it.isActive) 100_000 else 0 }

/**
 * The list the player navigates by, and reads the playing episode out of.
 *
 * A list that contains [playing] beats any list that does not, however long; length only decides
 * between lists on the same side of that line. Length alone was the rule once, and on One Piece
 * Miruro's `hop` lists 1,197 rows — eighteen of them fractional recaps — yet stops at 1179, so it
 * outranked every list that had the new 1180. Every caller then looked 1180 up in the spine, found
 * nothing and fell back to index 0: the header, "Now playing", saved progress and the Discord card
 * all said Episode 1 while 1180 played.
 */
internal fun pickNavigationSpine(
    episodes: EpisodesResult,
    preferred: String,
    category: Category,
    playing: Double? = null,
): List<EpisodeItem> {
    // Multi-audio servers list one set of episodes carrying both audio tracks, so a dub request
    // has to read their sub list or the spine comes back empty and navigation dies.
    fun raw(provider: String): List<EpisodeItem> = episodes.provider(provider)
        ?.episodes(ProviderCatalog.dubCapableCategory(provider, category))
        .orEmpty()

    fun normalized(provider: String): List<EpisodeItem> = raw(provider)
        .distinctBy(EpisodeItem::number)
        .sortedBy(EpisodeItem::number)

    // Every provider used to be de-duplicated and sorted just to find out which list was longest,
    // and this runs again on every merge — the initial catalog, the supplemental one, and each
    // Miruro late/retry answer. On One Piece that is thirteen providers of about 2,300 episodes
    // sorted four times over for a single number.
    //
    // De-duplication only ever shrinks a list, so a provider whose *raw* list is no longer than the
    // best normalized one so far cannot win and never has to be normalized at all. Walking the
    // providers in descending raw size makes that bound bite on the first or second one. Ties still
    // go to the preferred provider, which is why it seeds the comparison and the rest only replace
    // it on a strict win.
    //
    // A best list that is missing the playing episode switches the bound off until one that has it
    // turns up, since any list containing it wins regardless of size.
    fun hasPlaying(list: List<EpisodeItem>) = playing == null || list.any { it.number == playing }

    var best = normalized(preferred)
    var bestHasPlaying = hasPlaying(best)
    val challengers = episodes.providerNames
        .filterNot { it == preferred }
        .map { provider -> provider to raw(provider).size }
        .sortedByDescending { (_, rawSize) -> rawSize }
    for ((provider, rawSize) in challengers) {
        if (bestHasPlaying && rawSize <= best.size) break
        val candidateHasPlaying = hasPlaying(raw(provider))
        if (bestHasPlaying && !candidateHasPlaying) continue
        val candidate = normalized(provider)
        if ((candidateHasPlaying && !bestHasPlaying) || candidate.size > best.size) {
            best = candidate
            bestHasPlaying = candidateHasPlaying
        }
    }
    return best
}

/**
 * Drops the rows a finished series cannot have, when one server lists somebody else's episodes too.
 *
 * The spine is the longest list any server offers, so one server that files two cours under the
 * first cour's id stretches it for everyone. Mushoku Tensei II is thirteen episodes on AniList;
 * AniBD lists twenty-four and RareAnimes twenty-four, so fifteen seconds after the episode opened —
 * when the slower catalog merged — eleven rows appeared with no title and the series poster in
 * place of a still, and changing server made them vanish again, since that path takes the picked
 * server's own list.
 *
 * Only for a series AniList calls FINISHED and states a count for: a releasing show's count is a
 * plan, and a server ahead of AniList on a Sunday is right where the plan is not. Never trims away
 * the episode being watched, and never returns nothing — the spine is what Next reads, so a wrong
 * trim is worse than a long list.
 */
internal fun clampSpineToStatedTotal(
    spine: List<EpisodeItem>,
    statedTotal: Int?,
    keep: Double?,
): List<EpisodeItem> {
    if (statedTotal == null || statedTotal <= 0) return spine
    val clamped = spine.filter { it.number <= statedTotal }
    if (clamped.size == spine.size) return spine
    if (clamped.isEmpty()) return spine
    if (keep != null && spine.any { it.number == keep } && clamped.none { it.number == keep }) {
        return spine
    }
    return clamped
}

/**
 * Supplemental catalogs must not invalidate playback that already resolved successfully.
 * In particular, a late catalog can replace a provider row that had dubbed episodes with a
 * sub-only row. Publishing that empty candidate with currentIndex=0 makes WatchData.current
 * index into EmptyList and crashes the app.
 */
internal fun retainNonEmptyNavigationSpine(
    current: List<EpisodeItem>,
    candidate: List<EpisodeItem>,
): List<EpisodeItem> = candidate.ifEmpty { current }

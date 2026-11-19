package com.anilili.data.remote

import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.model.GqlMediaListResponse
import com.anilili.data.model.GqlDiscoverOptionsResponse
import com.anilili.data.model.GqlHomeCollectionsResponse
import com.anilili.data.model.GqlMediaResponse
import com.anilili.data.model.GqlPageResponse
import com.anilili.data.model.GqlRecommendationsResponse
import com.anilili.data.model.GqlStudioPageResponse
import com.anilili.data.model.GqlViewerResponse
import com.anilili.data.model.GqlViewerFavouritesResponse
import com.anilili.data.model.GqlViewerOptionsResponse
import com.anilili.data.model.GraphQLRequest
import com.anilili.data.model.Media
import com.anilili.data.model.HomeCollections
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.DiscoverOptions
import com.anilili.data.model.MediaListCollection
import com.anilili.data.model.MediaPage
import com.anilili.data.model.StudioNode
import com.anilili.data.model.Viewer
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.raise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * AniList GraphQL metadata client. Ports the query set from MiruroAPI's anilist.js.
 * These endpoints are public and work from any IP (unlike the streaming pipe).
 */
class AniListClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: String = ANILIST_URL,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Client-side pacing so bursts (the home screen's parallel loads, list sync, the related-
    // seasons walk) don't hit AniList's degraded ~30/min limit and earn a 1-minute 429 timeout.
    // We read X-RateLimit-Remaining/Reset from every response and glide toward the window reset as
    // the budget runs low, instead of only reacting to a 429 after the fact.
    private val rateGate = Mutex()
    // Only a small number of callers may reserve against the same stale quota snapshot. The
    // permit is held until response headers update the shared budget, keeping background sync
    // from starving an interactive home/detail request.
    private val requestGate = Semaphore(2)
    private var nextSlotMs = 0L // guarded by rateGate
    @Volatile private var rateRemaining = UNKNOWN_RATE_REMAINING
    @Volatile private var rateResetMs = 0L
    @Volatile private var rateLimit = DEFAULT_RATE_LIMIT
    private val studioMediaIdCache = ConcurrentHashMap<Int, List<Int>>()

    /** Reserve the next request slot, spacing bursts and backing off as the budget nears zero. */
    private suspend fun awaitRateSlot() {
        val slot = rateGate.withLock {
            val (start, next) = nextRateSlot(
                now = System.currentTimeMillis(),
                remaining = rateRemaining,
                reset = rateResetMs,
                nextSlot = nextSlotMs,
                limit = rateLimit,
            )
            nextSlotMs = next
            start
        }
        val wait = slot - System.currentTimeMillis()
        if (wait > 0) {
            if (wait >= SLOW_WAIT_LOG_MS) {
                DiagnosticsLog.event("AniList throttle waiting ${wait}ms (remaining=$rateRemaining)")
            }
            delay(wait)
        }
    }

    private fun recordRateHeaders(limitHeader: String?, remainingHeader: String?, resetHeader: String?) {
        limitHeader?.toIntOrNull()?.takeIf { it > 0 }?.let { rateLimit = it }
        remainingHeader?.toIntOrNull()?.let { rateRemaining = it }
        resetHeader?.toLongOrNull()?.let { rateResetMs = it * 1_000 }
    }

    private val mediaListFields = """
        id
        idMal
        title { romaji english native userPreferred }
        coverImage { large extraLarge color }
        bannerImage
        trailer { thumbnail }
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        popularity
        isAdult
        countryOfOrigin
        genres
        studios(isMain: true) { nodes { id name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
    """.trimIndent()

    // Home's cinematic hero needs a synopsis, while poster rails and relation nodes stay on the
    // compact field set to keep their payloads small.
    private val mediaSpotlightFields = """
        $mediaListFields
        description(asHtml: false)
    """.trimIndent()

    private val mediaFullFields = """
        id
        idMal
        title { romaji english native userPreferred }
        description(asHtml: false)
        coverImage { large extraLarge color }
        bannerImage
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        meanScore
        popularity
        favourites
        isAdult
        countryOfOrigin
        genres
        tags { name rank isMediaSpoiler isGeneralSpoiler }
        studios { nodes { id name isAnimationStudio } }
        trailer { id site thumbnail }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
        endDate { year month day }
        streamingEpisodes { title thumbnail url site }
        relations {
          edges {
            relationType
            node { $mediaListFields }
          }
        }
    """.trimIndent()

    private suspend fun queryPage(query: String, variables: JsonObject, page: Int): MediaPage =
        withContext(Dispatchers.IO) {
            val text = post(query, variables)
            val parsed = json.decodeFromString(GqlPageResponse.serializer(), text).data?.page
            MediaPage(
                items = parsed?.media ?: emptyList(),
                hasNextPage = parsed?.pageInfo?.hasNextPage ?: false,
                page = page,
                // Only the Browse query asks for `total`; everywhere else pageInfo carries the
                // default 0, which is exactly what "cannot say" means to the caller.
                total = parsed?.pageInfo?.total ?: 0,
            )
        }

    /**
     * The six Home collections in one GraphQL operation, avoiding a burst on every cold start.
     *
     * "Popular" is the rail titled "Popular this season", so it is scoped to [season]: sorted by
     * popularity alone it was all-time popularity, and the same decade-old titles filled it every
     * season.
     */
    suspend fun homeCollections(
        season: AiringSeason = AiringSeason.current(),
        hideAdult: Boolean = false,
    ): HomeCollections = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}isAdult: Boolean, ${'$'}airedBefore: Int, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
              spotlight: Page(page: 1, perPage: 30) {
                media(type: ANIME, sort: [TRENDING_DESC], isAdult: ${'$'}isAdult) { $mediaSpotlightFields }
              }
              newest: Page(page: 1, perPage: 50) {
                airingSchedules(airingAt_lesser: ${'$'}airedBefore, sort: TIME_DESC) {
                  media { $mediaListFields }
                }
              }
              popular: Page(page: 1, perPage: 30) {
                media(type: ANIME, season: ${'$'}season, seasonYear: ${'$'}seasonYear, sort: [POPULARITY_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              movies: Page(page: 1, perPage: 30) {
                media(type: ANIME, format: MOVIE, sort: [POPULARITY_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              topRated: Page(page: 1, perPage: 30) {
                media(type: ANIME, sort: [SCORE_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
              upcoming: Page(page: 1, perPage: 30) {
                media(type: ANIME, status: NOT_YET_RELEASED, sort: [POPULARITY_DESC], isAdult: ${'$'}isAdult) { $mediaListFields }
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            if (hideAdult) put("isAdult", false)
            put("season", season.season)
            put("seasonYear", season.year)
            // airingAt is the broadcast start; allow ~25 min runtime plus an hour for
            // streaming sites to pick the episode up, so every entry is actually watchable.
            put("airedBefore", System.currentTimeMillis() / 1000 - NEWEST_AIRED_BUFFER_SEC)
        }
        val data = json.decodeFromString(GqlHomeCollectionsResponse.serializer(), post(gql, variables)).data
        HomeCollections(
            spotlight = data?.spotlight?.media.orEmpty(),
            // Recently aired episodes, newest first; a show airing several times in the
            // window appears once. isAdult can't be filtered inside airingSchedules.
            newest = data?.newest?.airingSchedules.orEmpty()
                .mapNotNull { it.media }
                .distinctBy { it.id }
                .filterNot { hideAdult && it.isAdult },
            popular = data?.popular?.media.orEmpty(),
            movies = data?.movies?.media.orEmpty(),
            topRated = data?.topRated?.media.orEmpty(),
            upcoming = data?.upcoming?.media.orEmpty(),
        )
    }

    suspend fun search(query: String, page: Int = 1, perPage: Int = 20, hideAdult: Boolean = false): MediaPage {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int, ${'$'}isAdult: Boolean) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, isAdult: ${'$'}isAdult) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("search", query)
            put("page", page)
            put("perPage", perPage)
            if (hideAdult) put("isAdult", false)
        }
        return queryPage(gql, vars, page)
    }

    /**
     * Server-side catalog search used by Browse: every selected filter is applied by AniList.
     *
     * [dubbedIds] is the device's dub index, supplied by the caller when
     * [DiscoverFilters.dubbedOnly] is set. AniList has no audio-language field, so the filter is
     * expressed as `id_in` — the same argument the studio filter uses, which is why the two are
     * intersected here rather than each writing the variable on its own.
     */
    suspend fun discover(
        filters: DiscoverFilters,
        page: Int = 1,
        perPage: Int = 30,
        hideAdult: Boolean = false,
        dubbedIds: Set<Int> = emptySet(),
        /** Titles to leave out — [DiscoverFilters.hideWatched]'s answer, sent as `id_not_in`. */
        excludedIds: Set<Int> = emptySet(),
    ): MediaPage {
        val gql = """
            query (
              ${'$'}search: String,
              ${'$'}page: Int,
              ${'$'}perPage: Int,
              ${'$'}genres: [String],
              ${'$'}tags: [String],
              ${'$'}excludedGenres: [String],
              ${'$'}excludedTags: [String],
              ${'$'}mediaIds: [Int],
              ${'$'}excludedIds: [Int],
              ${'$'}year: Int,
              ${'$'}season: MediaSeason,
              ${'$'}startedAfter: FuzzyDateInt,
              ${'$'}startedBefore: FuzzyDateInt,
              ${'$'}status: MediaStatus,
              ${'$'}format: MediaFormat,
              ${'$'}country: CountryCode,
              ${'$'}source: MediaSource,
              ${'$'}episodesMin: Int,
              ${'$'}episodesMax: Int,
              ${'$'}minimumScore: Int,
              ${'$'}sort: [MediaSort],
              ${'$'}isAdult: Boolean
            ) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage total }
                media(
                  search: ${'$'}search,
                  type: ANIME,
                  genre_in: ${'$'}genres,
                  tag_in: ${'$'}tags,
                  genre_not_in: ${'$'}excludedGenres,
                  tag_not_in: ${'$'}excludedTags,
                  id_in: ${'$'}mediaIds,
                  id_not_in: ${'$'}excludedIds,
                  seasonYear: ${'$'}year,
                  season: ${'$'}season,
                  startDate_greater: ${'$'}startedAfter,
                  startDate_lesser: ${'$'}startedBefore,
                  status: ${'$'}status,
                  format: ${'$'}format,
                  countryOfOrigin: ${'$'}country,
                  source: ${'$'}source,
                  episodes_greater: ${'$'}episodesMin,
                  episodes_lesser: ${'$'}episodesMax,
                  averageScore_greater: ${'$'}minimumScore,
                  sort: ${'$'}sort,
                  isAdult: ${'$'}isAdult
                ) { $mediaListFields }
              }
            }
        """.trimIndent()
        val studioMediaIds = filters.studioId?.takeIf { it > 0 }?.let { mediaIdsForStudio(it) }
        if (filters.studioId != null && studioMediaIds.isNullOrEmpty()) {
            return MediaPage(items = emptyList(), hasNextPage = false, page = page)
        }
        val mediaIds = discoverMediaIds(filters, studioMediaIds, dubbedIds)
        // An id filter that resolved to nothing means no title can satisfy it, which is not the
        // same as sending no ids at all — that would quietly widen the search to the whole catalog.
        if (mediaIds != null && mediaIds.isEmpty()) {
            return MediaPage(items = emptyList(), hasNextPage = false, page = page)
        }
        val vars = discoverVariables(filters, page, perPage, hideAdult, mediaIds, excludedIds)
        return queryPage(gql, vars, page)
    }

    /**
     * One page of the pool the random pick draws from: released series, films, ONAs and OVAs with
     * at least [RANDOM_POOL_MIN_POPULARITY] AniList members, most popular first.
     *
     * The floor is what keeps a roll worth taking. Unfiltered, most of the catalogue is music
     * videos, shorts and titles nobody streams; at 10,000 members the pool was ~3,700 titles (75
     * pages of 50, measured 2026-10-03), every one of them something a source is likely to carry.
     * The spotlight field set comes back so the pick can show its synopsis without a second request.
     */
    suspend fun randomPool(page: Int, perPage: Int = RANDOM_POOL_PAGE_SIZE, hideAdult: Boolean = false): MediaPage {
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}minPopularity: Int, ${'$'}isAdult: Boolean) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(
                  type: ANIME,
                  sort: [POPULARITY_DESC],
                  popularity_greater: ${'$'}minPopularity,
                  format_in: [TV, MOVIE, ONA, OVA],
                  status_not: NOT_YET_RELEASED,
                  isAdult: ${'$'}isAdult
                ) { $mediaSpotlightFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
            put("minPopularity", RANDOM_POOL_MIN_POPULARITY)
            if (hideAdult) put("isAdult", false)
        }
        return queryPage(gql, vars, page)
    }

    /**
     * AniList has no studio argument on Page.media. Resolve a studio's media IDs through its
     * connection once, then use Page.media's id_in so every other Browse filter remains server-side.
     * Twenty 25-item aliases match AniList's public 500-result pagination ceiling in one request.
     */
    private suspend fun mediaIdsForStudio(studioId: Int): List<Int> {
        studioMediaIdCache[studioId]?.let { return it }
        val pageFields = (1..MAX_STUDIO_MEDIA_PAGES).joinToString("\n") { page ->
            "p$page: media(page: $page, perPage: $STUDIO_MEDIA_PAGE_SIZE) { nodes { id } }"
        }
        val gql = """
            query (${'$'}studioId: Int) {
              Studio(id: ${'$'}studioId) {
                $pageFields
              }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("studioId", studioId) }
        val root = json.parseToJsonElement(post(gql, vars)).jsonObject
        val data = root["data"] as? JsonObject
        val studio = data?.get("Studio") as? JsonObject
        val ids = (1..MAX_STUDIO_MEDIA_PAGES)
            .flatMap { page ->
                val connection = studio?.get("p$page") as? JsonObject
                val nodes = connection?.get("nodes") as? JsonArray
                nodes.orEmpty().mapNotNull { node ->
                    (node as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
                }
            }
            .distinct()
        studioMediaIdCache.putIfAbsent(studioId, ids)
        return studioMediaIdCache[studioId].orEmpty()
    }

    /** Studio suggestions for Browse. AniList media filtering requires the selected studio ID. */
    suspend fun searchStudios(query: String, perPage: Int = 12): List<StudioNode> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val gql = """
            query (${'$'}search: String, ${'$'}perPage: Int) {
              Page(page: 1, perPage: ${'$'}perPage) {
                studios(search: ${'$'}search, sort: [SEARCH_MATCH]) {
                  id
                  name
                  isAnimationStudio
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("search", term)
            put("perPage", perPage.coerceIn(1, 25))
        }
        json.decodeFromString(GqlStudioPageResponse.serializer(), post(gql, vars))
            .data?.page?.studios.orEmpty()
            .filter { it.id > 0 && it.isAnimationStudio && !it.name.isNullOrBlank() }
    }

    /** AniList-owned genre and tag catalog, filtered using the user's adult-content setting. */
    suspend fun discoverOptions(hideAdult: Boolean): DiscoverOptions = withContext(Dispatchers.IO) {
        val gql = """
            query {
              GenreCollection
              MediaTagCollection { name description category isAdult }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { })
        val data = json.decodeFromString(GqlDiscoverOptionsResponse.serializer(), text).data
        DiscoverOptions(
            genres = data?.genres.orEmpty().sorted(),
            tags = data?.tags.orEmpty()
                .let { tags -> if (hideAdult) tags.filterNot { it.isAdult } else tags }
                .sortedBy { it.name },
        )
    }

    /** Generic collection fetch (trending, popular, etc.). [status] is an optional MediaStatus enum. */
    suspend fun collection(
        sort: String,
        status: String? = null,
        format: String? = null,
        season: AiringSeason? = null,
        page: Int = 1,
        perPage: Int = 20,
        hideAdult: Boolean = false,
    ): MediaPage {
        val statusFilter = if (status != null) ", status: $status" else ""
        val seasonFilter = if (season != null) ", season: ${season.season}, seasonYear: ${season.year}" else ""
        val formatFilter = if (format != null) ", format: $format" else ""
        val adultFilter = if (hideAdult) ", isAdult: false" else ""
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage currentPage }
                media(type: ANIME, sort: [$sort]$statusFilter$formatFilter$seasonFilter$adultFilter) { $mediaListFields }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("page", page)
            put("perPage", perPage)
        }
        return queryPage(gql, vars, page)
    }

    /** All formats, ordered by weighted score; list membership is only a safe prefilter. */
    suspend fun topRatedByYear(year: Int, page: Int = 1, hideAdult: Boolean = false): MediaPage {
        val gql = """
            query (${'$'}year: Int, ${'$'}page: Int, ${'$'}isAdult: Boolean) {
              Page(page: ${'$'}page, perPage: 50) {
                pageInfo { hasNextPage currentPage }
                media(type: ANIME, seasonYear: ${'$'}year, sort: [SCORE_DESC, ID],
                      popularity_greater: 4999, averageScore_greater: 0, isAdult: ${'$'}isAdult) {
                  $mediaListFields
                  stats { scoreDistribution { score amount } }
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("year", year)
            put("page", page)
            if (hideAdult) put("isAdult", false)
        }
        val result = queryPage(gql, vars, page)
        return result.copy(items = result.items.filter { media ->
            (media.averageScore ?: 0) > 0 &&
                (media.stats?.scoreDistribution?.sumOf { it.amount.toLong() } ?: 0L) >= 5_000
        })
    }

    /** Episodes airing in the half-open range [startSec, endExclusiveSec), sorted by time. */
    suspend fun airingSchedule(startSec: Long, endExclusiveSec: Long): List<com.anilili.data.model.AiringSchedule> =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}start: Int, ${'$'}end: Int, ${'$'}page: Int) {
                  Page(page: ${'$'}page, perPage: 50) {
                    pageInfo { hasNextPage currentPage }
                    airingSchedules(airingAt_greater: ${'$'}start, airingAt_lesser: ${'$'}end, sort: TIME) {
                      episode
                      airingAt
                      media { $mediaListFields }
                    }
                  }
                }
            """.trimIndent()
            val all = mutableListOf<com.anilili.data.model.AiringSchedule>()
            var page = 1
            var hasNext = true
            while (hasNext && page <= MAX_SCHEDULE_PAGES) {
                val vars = buildJsonObject {
                    // AniList uses strict comparisons, so move both bounds out by one second.
                    put("start", startSec - 1)
                    put("end", endExclusiveSec)
                    put("page", page)
                }
                val parsed = json.decodeFromString(
                    com.anilili.data.model.GqlScheduleResponse.serializer(),
                    post(gql, vars),
                ).data?.page ?: break
                all += parsed.airingSchedules
                hasNext = parsed.pageInfo.hasNextPage
                page++
            }
            all.distinctBy { it.airingAt to it.media?.id }
        }

    /**
     * How many episodes air in each of [days], given as half-open [start, end) epoch-second ranges.
     * `null` in the result means AniList answered nothing for that day, which is not the same as a
     * day with nothing on it.
     *
     * Asked for as one query per [MAX_COUNT_ALIASES] days rather than one query per day, by giving
     * every day its own aliased `Page` field. A calendar month is otherwise a genuinely expensive
     * screen: paging the whole range costs about twenty requests against a degraded budget of
     * thirty a minute, and one request per day costs thirty — either would leave the pacer in
     * [awaitRateSlot] spacing everything else in the app out behind it for the rest of the window.
     * Aliased, a six-week grid is a single request and a single rate-limit unit (measured
     * 2026-09-12: 42 days, ~800ms, ~40KB).
     */
    suspend fun airingCounts(days: List<LongRange>): List<com.anilili.data.model.AiringDayCount?> =
        withContext(Dispatchers.IO) {
            days.chunked(MAX_COUNT_ALIASES).flatMap { chunk -> airingCountChunk(chunk) }
        }

    private suspend fun airingCountChunk(days: List<LongRange>): List<com.anilili.data.model.AiringDayCount?> {
        val gql = buildString {
            append("query {\n")
            days.forEachIndexed { index, window ->
                // AniList uses strict comparisons, so move both bounds out by one second. The
                // windows are app-computed epoch seconds, so they are inlined rather than passed as
                // variables — one query needs a pair per day, and naming 84 of them reads worse
                // than the numbers do.
                append("  d$index: Page(page: 1, perPage: $COUNT_PAGE_SIZE) { pageInfo { hasNextPage } ")
                append("airingSchedules(airingAt_greater: ${window.first - 1}, ")
                append("airingAt_lesser: ${window.last + 1}, sort: TIME) ")
                append("{ airingAt media { id isAdult } } }\n")
            }
            append("}")
        }
        val pages = json.decodeFromString(
            com.anilili.data.model.GqlAiringCountsResponse.serializer(),
            post(gql, buildJsonObject { }),
        ).data.orEmpty()
        return days.indices.map { index ->
            val page = pages["d$index"] ?: return@map null
            // Deduplicated the same way the day lists are, so a cell's number matches the list it
            // opens: the same title twice at the same minute is one row there and one episode here.
            val entries = page.airingSchedules.distinctBy { it.airingAt to it.media?.id }
            com.anilili.data.model.AiringDayCount(
                total = entries.size,
                nonAdult = entries.count { it.media?.isAdult != true },
                truncated = page.pageInfo.hasNextPage,
            )
        }
    }

    /** AniList media for the given MAL ids (one page per 50); unknown ids are simply absent. */
    suspend fun mediaByMalIds(malIds: List<Int>): List<Media> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(type: ANIME, idMal_in: ${'$'}ids) { $mediaListFields }
              }
            }
        """.trimIndent()
        malIds.distinct().chunked(50).flatMap { chunk ->
            val vars = buildJsonObject {
                put("ids", kotlinx.serialization.json.JsonArray(chunk.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            json.decodeFromString(GqlPageResponse.serializer(), post(gql, vars)).data?.page?.media.orEmpty()
        }
    }

    /** AniList media for the given AniList ids (one page per 50); unknown ids are simply absent. */
    suspend fun mediaByIds(ids: List<Int>): List<Media> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}ids: [Int]) {
              Page(page: 1, perPage: 50) {
                media(type: ANIME, id_in: ${'$'}ids) { $mediaListFields }
              }
            }
        """.trimIndent()
        ids.distinct().chunked(50).flatMap { chunk ->
            val vars = buildJsonObject {
                put("ids", kotlinx.serialization.json.JsonArray(chunk.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            json.decodeFromString(GqlPageResponse.serializer(), post(gql, vars)).data?.page?.media.orEmpty()
        }
    }

    /**
     * AniList's community recommendations for one title ("users who liked this also liked"),
     * best-rated first.
     *
     * The connection carries no `isAdult` argument and can recommend manga as readily as anime, so
     * both filters are applied here on the decoded nodes rather than server-side.
     */
    suspend fun recommendations(
        id: Int,
        perPage: Int = 24,
        hideAdult: Boolean = false,
    ): List<Media> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}id: Int, ${'$'}perPage: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                recommendations(sort: [RATING_DESC], perPage: ${'$'}perPage) {
                  nodes {
                    rating
                    mediaRecommendation { $mediaListFields }
                  }
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("id", id)
            put("perPage", perPage)
        }
        val text = post(gql, vars)
        json.decodeFromString(GqlRecommendationsResponse.serializer(), text)
            .data?.media?.recommendations?.nodes.orEmpty()
            .mapNotNull { it.mediaRecommendation }
            .filterNot { it.format in READING_FORMATS }
            .filterNot { hideAdult && it.isAdult }
            .distinctBy(Media::id)
    }

    suspend fun animeInfo(id: Int): Media? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $mediaFullFields }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("id", id) }
        val text = post(gql, vars)
        json.decodeFromString(GqlMediaResponse.serializer(), text).data?.media
    }

    /**
     * The most-favourited characters on AniList, as (name, portrait URL) pairs — the picture set
     * a local profile chooses its avatar from. Nothing is bundled: the portraits are served by
     * AniList exactly as the app's posters are.
     */
    suspend fun popularCharacters(perPage: Int = 36): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}perPage: Int) {
              Page(perPage: ${'$'}perPage) {
                characters(sort: FAVOURITES_DESC) { name { full } image { large } }
              }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { put("perPage", perPage) })
        val characters = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("Page")?.jsonObject?.get("characters") as? JsonArray ?: return@withContext emptyList()
        characters.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val name = (node["name"] as? JsonObject)?.get("full")?.jsonPrimitive?.contentOrNull
            val image = (node["image"] as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull
            if (name.isNullOrBlank() || image.isNullOrBlank() || image.contains("/default.")) null
            else name to image
        }
    }

    /** Authenticated: the logged-in user's profile + stats. */
    suspend fun viewer(): Viewer? = withContext(Dispatchers.IO) {
        val gql = """
            query {
              Viewer {
                id
                name
                avatar { large }
                bannerImage
                createdAt
                statistics { anime { count episodesWatched minutesWatched meanScore } }
              }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { }, authenticated = true)
        json.decodeFromString(GqlViewerResponse.serializer(), text).data?.viewer
    }

    /**
     * Authenticated: whether AniList's own 18+ switch is on for the signed-in account.
     *
     * AniList applies that account option server-side to every request that carries a token,
     * whatever the query asks for. A viewer who has allowed adult content here and is signed in to
     * an account that has not gets an empty answer for `genre_in: ["Hentai"]` with no error and no
     * `isAdult` argument of ours in the request — measured 3 Sep 2026, the same query answering
     * with 30 titles the moment the Authorization header is dropped. Null when nobody is signed in.
     */
    suspend fun viewerDisplaysAdultContent(): Boolean? = withContext(Dispatchers.IO) {
        if (AuthManager.current() == null) return@withContext null
        val gql = """
            query {
              Viewer { options { displayAdultContent } }
            }
        """.trimIndent()
        val text = post(gql, buildJsonObject { }, authenticated = true)
        json.decodeFromString(GqlViewerOptionsResponse.serializer(), text)
            .data?.viewer?.options?.displayAdultContent
    }

    /** Authenticated: every anime the current user has marked as a favourite. */
    suspend fun favouriteAnime(): List<Media> = withContext(Dispatchers.IO) {
        val all = mutableListOf<Media>()
        var page = 1
        var hasNext = true
        while (hasNext && page <= MAX_FAVOURITE_PAGES) {
            val gql = """
                query (${'$'}page: Int) {
                  Viewer {
                    favourites {
                      anime(page: ${'$'}page, perPage: 25) {
                        pageInfo { hasNextPage currentPage }
                        nodes { $mediaListFields }
                      }
                    }
                  }
                }
            """.trimIndent()
            val text = post(gql, buildJsonObject { put("page", page) }, authenticated = true)
            val connection = json.decodeFromString(GqlViewerFavouritesResponse.serializer(), text)
                .data?.viewer?.favourites?.anime ?: break
            all += connection.nodes
            hasNext = connection.pageInfo.hasNextPage
            page++
        }
        all.distinctBy { it.id }
    }

    /**
     * Authenticated: the viewer's notification feed, newest first. AniList has no per-item
     * read flag — the first [unreadCount] entries are the unread ones. Passing
     * [markAllRead] resets the server-side unread counter.
     */
    suspend fun notifications(markAllRead: Boolean = false): Pair<List<com.anilili.data.model.AppNotification>, Int> =
        withContext(Dispatchers.IO) {
            val mediaFields = "media { id title { romaji english userPreferred } coverImage { large } bannerImage }"
            val userFields = "user { id name avatar { large } }"
            val gql = """
                query {
                  Viewer { unreadNotificationCount }
                  Page(page: 1, perPage: 50) {
                    notifications(resetNotificationCount: false) {
                      __typename
                      ... on AiringNotification { id createdAt episode $mediaFields }
                      ... on RelatedMediaAdditionNotification { id createdAt context $mediaFields }
                      ... on MediaDataChangeNotification { id createdAt context $mediaFields }
                      ... on MediaMergeNotification { id createdAt reason $mediaFields }
                      ... on FollowingNotification { id createdAt context $userFields }
                      ... on ActivityMessageNotification { id createdAt context $userFields }
                      ... on ActivityMentionNotification { id createdAt context $userFields }
                      ... on ActivityReplyNotification { id createdAt context $userFields }
                      ... on ActivityLikeNotification { id createdAt context $userFields }
                      ... on ActivityReplyLikeNotification { id createdAt context $userFields }
                    }
                  }
                }
            """.trimIndent()
            val text = post(gql, buildJsonObject { }, authenticated = true)
            val root = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            val unreadCount = root?.get("Viewer")?.jsonObject
                ?.get("unreadNotificationCount")?.jsonPrimitive?.intOrNull ?: 0
            val items = (root?.get("Page")?.jsonObject?.get("notifications") as? kotlinx.serialization.json.JsonArray)
                .orEmpty()
                .mapIndexedNotNull { index, element ->
                    parseNotification(element as? JsonObject ?: return@mapIndexedNotNull null, unread = index < unreadCount)
                }
            // Keep the original count long enough to mark the correct rows in this response, then
            // reset it in a separate operation; GraphQL sibling root fields have no ordering.
            if (markAllRead && unreadCount > 0) markNotificationsRead()
            items to unreadCount
        }

    private suspend fun markNotificationsRead() {
        val gql = """
            query {
              Page(page: 1, perPage: 1) {
                notifications(resetNotificationCount: true) { __typename }
              }
            }
        """.trimIndent()
        post(gql, buildJsonObject { }, authenticated = true)
    }

    private fun parseNotification(obj: JsonObject, unread: Boolean): com.anilili.data.model.AppNotification? {
        val typename = obj["__typename"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val media = obj["media"] as? JsonObject
        val user = obj["user"] as? JsonObject
        val mediaTitle = (media?.get("title") as? JsonObject)?.let {
            it["english"]?.jsonPrimitive?.contentOrNull
                ?: it["userPreferred"]?.jsonPrimitive?.contentOrNull
                ?: it["romaji"]?.jsonPrimitive?.contentOrNull
        }
        val userName = user?.get("name")?.jsonPrimitive?.contentOrNull
        val context = obj["context"]?.jsonPrimitive?.contentOrNull
            ?: obj["reason"]?.jsonPrimitive?.contentOrNull
        val kind = when (typename) {
            "AiringNotification" -> com.anilili.data.model.AppNotification.Kind.AIRING
            "RelatedMediaAdditionNotification",
            "MediaDataChangeNotification",
            "MediaMergeNotification",
            -> com.anilili.data.model.AppNotification.Kind.MEDIA
            else -> com.anilili.data.model.AppNotification.Kind.SOCIAL
        }
        val episode = obj["episode"]?.jsonPrimitive?.intOrNull
        val badge = when (typename) {
            "AiringNotification" -> episode?.let { "EP $it" }
            "RelatedMediaAdditionNotification" -> "NEW RELATED"
            "MediaDataChangeNotification", "MediaMergeNotification" -> "MEDIA UPDATE"
            else -> null
        }
        return com.anilili.data.model.AppNotification(
            id = id,
            kind = kind,
            createdAt = createdAt,
            title = mediaTitle ?: userName ?: "AniList",
            badge = badge,
            detail = if (kind == com.anilili.data.model.AppNotification.Kind.SOCIAL) {
                listOfNotNull(userName, context?.trim()).joinToString(" ").ifBlank { null }
            } else {
                null
            },
            episode = if (typename == "AiringNotification") episode else null,
            mediaId = media?.get("id")?.jsonPrimitive?.intOrNull,
            image = (media?.get("coverImage") as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull
                ?: (user?.get("avatar") as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull,
            banner = media?.get("bannerImage")?.jsonPrimitive?.contentOrNull,
            unread = unread,
        )
    }

    /**
     * Authenticated: the user's anime lists with per-entry progress, plus which custom lists each
     * title is in and the names of every custom list — the empty ones included, which no entry can
     * reveal.
     */
    suspend fun userAnimeList(userId: Int): MediaListCollection? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}userId: Int) {
              MediaListCollection(userId: ${'$'}userId, type: ANIME, status_in: [CURRENT, REPEATING, PLANNING, PAUSED, COMPLETED, DROPPED]) {
                user { mediaListOptions { animeList { customLists } } }
                lists {
                  name
                  status
                  isCustomList
                  entries {
                    id updatedAt progress score(format: POINT_10_DECIMAL) status
                    hiddenFromStatusLists customLists(asArray: true)
                    media { $mediaListFields }
                  }
                }
              }
            }
        """.trimIndent()
        val vars = buildJsonObject { put("userId", userId) }
        val text = post(gql, vars, authenticated = true)
        json.decodeFromString(GqlMediaListResponse.serializer(), text).data?.collection
    }

    /**
     * The account's activity per day, as AniList's own profile heatmap draws it: about six months of
     * days, each with how many list activities it held. Keyed by epoch day; days with none are absent.
     *
     * `User.stats` is deprecated in favour of `statistics`, which has no daily history, so this is
     * the only source there is — and why a failure here is reported as empty rather than thrown.
     */
    suspend fun activityHistory(userId: Int): Map<Long, Int> = withContext(Dispatchers.IO) {
        val query = """
            query (${'$'}id: Int) { User(id: ${'$'}id) { stats { activityHistory { date amount } } } }
        """.trimIndent()
        val text = post(query, buildJsonObject { put("id", userId) }, authenticated = true)
        val history = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("User")?.jsonObject
            ?.get("stats")?.jsonObject
            ?.get("activityHistory") as? JsonArray ?: return@withContext emptyMap()
        history.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val date = obj["date"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            val amount = obj["amount"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            java.time.Instant.ofEpochSecond(date).atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay() to amount
        }.filter { it.second > 0 }.toMap()
    }

    /** Update watched progress without regressing progress or overwriting user-owned list states. */
    suspend fun syncMediaListProgress(
        mediaId: Int,
        progress: Int,
        totalEpisodes: Int?,
    ): MediaListProgressUpdate? = withContext(Dispatchers.IO) {
        val current = mediaListEntry(mediaId)
        val update = planMediaListProgressUpdate(current, progress, totalEpisodes)
            ?: return@withContext null
        // Only a finished rewatch carries a count, and only it brings the dates along.
        val (rewatchDeclarations, rewatchArgs, rewatchValues) =
            rewatchArguments(current.takeIf { update.repeat != null }, update.repeat)
        val statusDeclaration = if (update.status != null) ", ${'$'}status: MediaListStatus" else ""
        // A status change also brings a title out of "custom lists only": starting one that was
        // filed away in a custom list is the viewer putting it on Watching, and AniList would
        // otherwise keep it off that list while it counted the episodes.
        val statusArgument = if (update.status != null) {
            ", status: ${'$'}status, hiddenFromStatusLists: false"
        } else {
            ""
        }
        val gql = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int$statusDeclaration$rewatchDeclarations) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress$statusArgument$rewatchArgs) {
                id progress status repeat
              }
            }
        """.trimIndent()
        val vars = buildJsonObject {
            put("mediaId", mediaId)
            put("progress", update.progress)
            update.status?.let { put("status", it) }
            rewatchValues.forEach { (name, value) -> put(name, value) }
        }
        val text = post(gql, vars, authenticated = true)
        update.repeat?.let { requested -> checkConfirmedRepeat(text, requested) }
        update
    }

    /**
     * Fails a rewatch write AniList did not count. Without it a refused `repeat` would look like a
     * finished rewatch here while the account still said REPEATING, and nothing would retry it.
     */
    private fun checkConfirmedRepeat(text: String, requested: Int) {
        val confirmed = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("SaveMediaListEntry")?.jsonObject
            ?.get("repeat")?.jsonPrimitive?.intOrNull
        check(confirmed == requested) {
            "AniList confirmed repeat ${confirmed ?: "missing"} after requesting $requested"
        }
    }

    /**
     * Mark every episode watched in one write: full progress plus COMPLETED.
     *
     * This deliberately bypasses [planMediaListProgressUpdate]: that planner refuses to touch an
     * entry already COMPLETED, which is exactly the state a title is in when the viewer marks it
     * watched a second time (toggle off, then on again). A null [totalEpisodes] — airing or
     * announced titles that don't know their count yet — writes the status alone.
     *
     * Marking a rewatch watched finishes it, so it is counted ([planRewatchMove]) with the dates
     * written back as they were.
     */
    suspend fun setWatched(mediaId: Int, totalEpisodes: Int?) = withContext(Dispatchers.IO) {
        val current = mediaListEntry(mediaId)
        val rewatch = planRewatchMove(current?.status, current?.repeat ?: 0, "COMPLETED")
        val (rewatchDeclarations, rewatchArgs, rewatchValues) =
            rewatchArguments(current.takeIf { rewatch.touchesRewatch }, rewatch.repeat)
        val progressDeclaration = if (totalEpisodes != null) ", ${'$'}progress: Int" else ""
        val progressArgument = if (totalEpisodes != null) ", progress: ${'$'}progress" else ""
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus$progressDeclaration$rewatchDeclarations) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, hiddenFromStatusLists: false$progressArgument$rewatchArgs) {
                id status progress repeat
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            put("status", "COMPLETED")
            totalEpisodes?.let { put("progress", it) }
            rewatchValues.forEach { (name, value) -> put(name, value) }
        }
        val text = post(mutation, variables, authenticated = true)
        rewatch.repeat?.let { checkConfirmedRepeat(text, it) }
    }

    /**
     * Move an anime to another standard AniList list while preserving its progress and score.
     *
     * Choosing a status is choosing to have it on that list, so a title kept in custom lists only
     * comes out onto it; its custom lists are left as they were.
     *
     * Rewatching a finished title starts its count again from 0, and moving a rewatch to Completed
     * counts it ([planRewatchMove]); both write the entry's dates back as they were. Only those two
     * moves read the entry first.
     */
    suspend fun updateMediaListStatus(mediaId: Int, status: String) = withContext(Dispatchers.IO) {
        require(status in MEDIA_LIST_STATUSES) { "Unsupported AniList status: $status" }
        val current = if (status == "REPEATING" || status == "COMPLETED") mediaListEntry(mediaId) else null
        val rewatch = planRewatchMove(current?.status, current?.repeat ?: 0, status)
        val (rewatchDeclarations, rewatchArgs, rewatchValues) =
            rewatchArguments(current.takeIf { rewatch.touchesRewatch }, rewatch.repeat)
        val progressDeclaration = if (rewatch.resetProgress) ", ${'$'}progress: Int" else ""
        val progressArgument = if (rewatch.resetProgress) ", progress: ${'$'}progress" else ""
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus$progressDeclaration$rewatchDeclarations) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, hiddenFromStatusLists: false$progressArgument$rewatchArgs) {
                id status progress repeat
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            put("status", status)
            if (rewatch.resetProgress) put("progress", 0)
            rewatchValues.forEach { (name, value) -> put(name, value) }
        }
        val text = post(mutation, variables, authenticated = true)
        rewatch.repeat?.let { checkConfirmedRepeat(text, it) }
    }

    /**
     * Give an entry the viewer's own score out of ten; 0 clears it.
     *
     * Written as `scoreRaw`, AniList's 0–100 figure, rather than `score`: `score` is read in the
     * account's chosen format, so a 7 would mean seven stars out of five on one account and 7/100
     * on another. `scoreRaw` is the same number whatever the viewer picked, and AniList shows it
     * back to them in their own format.
     *
     * Only for a title already on the account. AniList would otherwise create an entry to hold the
     * score, on a list the viewer never chose.
     */
    suspend fun updateMediaListScore(mediaId: Int, score: Int) = withContext(Dispatchers.IO) {
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}scoreRaw: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, scoreRaw: ${'$'}scoreRaw) {
                id score(format: POINT_10_DECIMAL)
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            put("scoreRaw", aniListScoreRaw(score))
        }
        val text = post(mutation, variables, authenticated = true)
        val confirmed = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("SaveMediaListEntry")?.jsonObject
            ?.get("score")?.jsonPrimitive?.doubleOrNull
        check(confirmed != null && kotlin.math.abs(confirmed - score) < 0.05) {
            "AniList confirmed score ${confirmed ?: "missing"} after requesting $score"
        }
    }

    /** The viewer's episode count for one title, or null when it is not on their list. */
    suspend fun mediaListProgress(mediaId: Int): Int? = withContext(Dispatchers.IO) {
        mediaListEntry(mediaId)?.progress
    }

    /**
     * Set an entry's episode count to exactly [progress], lower as well as higher; the status is
     * left as it is.
     *
     * Deliberately not [syncMediaListProgress]: that one is playback's, and refuses to go backwards
     * so a rewatch can never undo a count. This is the viewer correcting the number by hand, which
     * is the one case where lowering it is what was asked for.
     */
    suspend fun setMediaListProgress(mediaId: Int, progress: Int) = withContext(Dispatchers.IO) {
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}progress: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress) {
                id progress
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            put("progress", progress)
        }
        val text = post(mutation, variables, authenticated = true)
        val confirmed = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("SaveMediaListEntry")?.jsonObject
            ?.get("progress")?.jsonPrimitive?.intOrNull
        check(confirmed == progress) {
            "AniList confirmed ${confirmed ?: "missing"} watched episodes after requesting $progress"
        }
    }

    /**
     * Take this title off the list entirely, whatever state its entry is in.
     *
     * Deliberately not [syncSavedAnime]: that one deletes only a PLANNING entry, because it mirrors
     * the device's Save button and must never take a watched row's progress and score down with it.
     * This is the viewer asking for the entry itself to go, so those go too — AniList offers no undo,
     * which is why the caller confirms first.
     *
     * `DeleteMediaListEntry` is keyed by the *entry* id rather than the media id, so the read is not
     * optional. It doubles as the only way to tell a removal from a title that was never on the list:
     * false means there was nothing to delete, and the caller must not claim otherwise.
     */
    suspend fun deleteMediaListEntry(mediaId: Int): Boolean = withContext(Dispatchers.IO) {
        val current = mediaListEntry(mediaId) ?: return@withContext false
        val mutation = """
            mutation (${'$'}id: Int) {
              DeleteMediaListEntry(id: ${'$'}id) { deleted }
            }
        """.trimIndent()
        val text = post(mutation, buildJsonObject { put("id", current.id) }, authenticated = true)
        json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("DeleteMediaListEntry")?.jsonObject
            ?.get("deleted")?.jsonPrimitive?.booleanOrNull
            ?: false
    }

    /**
     * Mirrors the device Save button without damaging an existing AniList status/progress.
     * New saves become PLANNING; unsaving only removes entries that are still PLANNING.
     *
     * Custom lists change what "removes" may mean. An entry that is also in a custom list is not
     * deleted on unsave — that would take it out of the lists as well — but tucked away into the
     * lists only, and saving an entry that was tucked away brings it back out onto Planning.
     */
    suspend fun syncSavedAnime(mediaId: Int, saved: Boolean) = withContext(Dispatchers.IO) {
        val current = customListEntry(mediaId)
        when {
            saved && current == null -> {
                val mutation = """
                    mutation (${'$'}mediaId: Int) {
                      SaveMediaListEntry(mediaId: ${'$'}mediaId, status: PLANNING) { id status }
                    }
                """.trimIndent()
                post(mutation, buildJsonObject { put("mediaId", mediaId) }, authenticated = true)
            }
            saved && current?.status == "PLANNING" && current.hidden -> setHiddenFromStatusLists(mediaId, false)
            !saved && current?.status == "PLANNING" && !current.hidden && current.lists.isNotEmpty() ->
                setHiddenFromStatusLists(mediaId, true)
            !saved && current?.status == "PLANNING" && current.lists.isEmpty() -> {
                val mutation = """
                    mutation (${'$'}id: Int) {
                      DeleteMediaListEntry(id: ${'$'}id) { deleted }
                    }
                """.trimIndent()
                post(mutation, buildJsonObject { put("id", current.id) }, authenticated = true)
            }
        }
    }

    private suspend fun setHiddenFromStatusLists(mediaId: Int, hidden: Boolean) {
        val mutation = """
            mutation (${'$'}mediaId: Int, ${'$'}hidden: Boolean) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, hiddenFromStatusLists: ${'$'}hidden) { id }
            }
        """.trimIndent()
        post(
            mutation,
            buildJsonObject {
                put("mediaId", mediaId)
                put("hidden", hidden)
            },
            authenticated = true,
        )
    }

    /** Reconcile all device saves in one list read plus small mutation batches. */
    suspend fun syncSavedAnime(mediaIds: Collection<Int>, viewerId: Int) = withContext(Dispatchers.IO) {
        val requested = mediaIds.filter { it > 0 }.distinct()
        if (requested.isEmpty()) return@withContext
        val existing = userAnimeList(viewerId)?.lists.orEmpty()
            .flatMap { it.entries }
            .filter { it.media != null }
            .associateBy { it.media!!.id }
        requested.filterNot(existing::containsKey).chunked(SAVED_SYNC_BATCH_SIZE).forEach { batch ->
            val declarations = batch.indices.joinToString { index -> "${'$'}id$index: Int" }
            val fields = batch.indices.joinToString("\n") { index ->
                "save$index: SaveMediaListEntry(mediaId: ${'$'}id$index, status: PLANNING) { id status }"
            }
            val mutation = "mutation ($declarations) {\n$fields\n}"
            val variables = buildJsonObject { batch.forEachIndexed { index, id -> put("id$index", id) } }
            post(mutation, variables, authenticated = true)
        }
        // A save of a title that is filed in custom lists only is a request to have it on Planning.
        requested.filter { id -> existing[id]?.let { it.hiddenFromStatusLists && it.status == "PLANNING" } == true }
            .chunked(SAVED_SYNC_BATCH_SIZE)
            .forEach { batch ->
                val declarations = batch.indices.joinToString { index -> "${'$'}id$index: Int" }
                val fields = batch.indices.joinToString("\n") { index ->
                    "show$index: SaveMediaListEntry(mediaId: ${'$'}id$index, hiddenFromStatusLists: false) { id }"
                }
                val variables = buildJsonObject { batch.forEachIndexed { index, id -> put("id$index", id) } }
                post("mutation ($declarations) {\n$fields\n}", variables, authenticated = true)
            }
    }

    /** One title's entry as custom lists see it, or null when the account has no entry for it. */
    internal data class CustomListEntry(
        val id: Int,
        val status: String,
        val hidden: Boolean,
        val lists: List<String>,
    )

    private suspend fun customListEntry(mediaId: Int): CustomListEntry? {
        val query = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: ANIME) {
                mediaListEntry { id status hiddenFromStatusLists customLists(asArray: true) }
              }
            }
        """.trimIndent()
        val text = post(query, buildJsonObject { put("mediaId", mediaId) }, authenticated = true)
        val entry = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject
            ?.get("mediaListEntry") as? JsonObject ?: return null
        val id = entry["id"]?.jsonPrimitive?.intOrNull ?: return null
        val status = entry["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val lists = entry["customLists"]
            ?.let { json.decodeFromJsonElement(com.anilili.data.model.CustomListNamesSerializer, it) }
            .orEmpty()
        return CustomListEntry(
            id = id,
            status = status,
            hidden = entry["hiddenFromStatusLists"]?.jsonPrimitive?.booleanOrNull == true,
            lists = lists,
        )
    }

    /**
     * Make the account's custom anime lists exactly [names], in that order.
     *
     * AniList has no create or rename call — the list of names is one field on the account's list
     * settings, written whole. A name dropped from it takes the list away from every entry that was
     * in it. Returns the names AniList confirmed.
     */
    suspend fun setCustomListNames(names: List<String>): List<String> = withContext(Dispatchers.IO) {
        val mutation = """
            mutation (${'$'}names: [String]) {
              UpdateUser(animeListOptions: { customLists: ${'$'}names }) {
                mediaListOptions { animeList { customLists } }
              }
            }
        """.trimIndent()
        val text = post(
            mutation,
            buildJsonObject { put("names", buildJsonArray { names.forEach { add(it) } }) },
            authenticated = true,
        )
        val confirmed = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("UpdateUser")?.jsonObject
            ?.get("mediaListOptions")?.jsonObject
            ?.get("animeList")?.jsonObject
            ?.get("customLists") as? JsonArray
        confirmed?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: names
    }

    /**
     * Write which custom lists each title is in, several titles to a request.
     *
     * A title the account has no entry for is created as Planning *hidden from the status lists*,
     * so it appears in its custom list and nowhere else — the viewer filed it, they did not plan it.
     */
    suspend fun saveCustomListMemberships(writes: List<CustomListWrite>) = withContext(Dispatchers.IO) {
        writes.chunked(CUSTOM_LIST_BATCH_SIZE).forEach { batch ->
            val declarations = batch.indices.joinToString { index ->
                "${'$'}id$index: Int, ${'$'}lists$index: [String]"
            }
            val fields = batch.withIndex().joinToString("\n") { (index, write) ->
                val create = if (write.create) ", status: PLANNING, hiddenFromStatusLists: true" else ""
                "set$index: SaveMediaListEntry(mediaId: ${'$'}id$index, customLists: ${'$'}lists$index$create) { id }"
            }
            val variables = buildJsonObject {
                batch.forEachIndexed { index, write ->
                    put("id$index", write.mediaId)
                    put("lists$index", buildJsonArray { write.lists.forEach { add(it) } })
                }
            }
            post("mutation ($declarations) {\n$fields\n}", variables, authenticated = true)
        }
    }

    /** Delete list entries by *entry* id, several to a request. */
    suspend fun deleteMediaListEntries(entryIds: List<Int>) = withContext(Dispatchers.IO) {
        entryIds.distinct().chunked(CUSTOM_LIST_BATCH_SIZE).forEach { batch ->
            val declarations = batch.indices.joinToString { index -> "${'$'}id$index: Int" }
            val fields = batch.indices.joinToString("\n") { index ->
                "delete$index: DeleteMediaListEntry(id: ${'$'}id$index) { deleted }"
            }
            val variables = buildJsonObject { batch.forEachIndexed { index, id -> put("id$index", id) } }
            post("mutation ($declarations) {\n$fields\n}", variables, authenticated = true)
        }
    }

    private suspend fun mediaListEntry(mediaId: Int): MediaListProgressSnapshot? {
        val query = """
            query (${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: ANIME) {
                mediaListEntry {
                  id status progress repeat
                  startedAt { year month day } completedAt { year month day }
                }
              }
            }
        """.trimIndent()
        val text = post(query, buildJsonObject { put("mediaId", mediaId) }, authenticated = true)
        val entry = json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject
            ?.get("mediaListEntry") as? JsonObject ?: return null
        val id = entry["id"]?.jsonPrimitive?.intOrNull ?: return null
        val status = entry["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val progress = entry["progress"]?.jsonPrimitive?.intOrNull ?: 0
        return MediaListProgressSnapshot(
            id = id,
            status = status,
            progress = progress,
            repeat = entry["repeat"]?.jsonPrimitive?.intOrNull ?: 0,
            startedAt = (entry["startedAt"] as? JsonObject).toListDate(),
            completedAt = (entry["completedAt"] as? JsonObject).toListDate(),
        )
    }

    /**
     * AniList rate-limits the whole API per user/IP (degraded to ~30 req/min). On 429 we honor
     * the Retry-After header and retry instead of surfacing the error, so bursts (list sync,
     * fast browsing) and interactive saves recover on their own.
     */
    private suspend fun post(query: String, variables: JsonObject, authenticated: Boolean = false): String {
        val payload = json.encodeToString(GraphQLRequest.serializer(), GraphQLRequest(query, variables))
        var attempt = 0
        while (true) {
            val builder = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                // AniList refuses a request that carries no Referer at all, with the same
                // "temporarily disabled due to severe stability issues" 403 the 2 Aug outage used.
                // The value is not inspected — measured 2026-08-22, `Referer: banana` answers 200
                // while no Referer answers 403 on repeat, and no other header makes any difference
                // — so this is a filter on header-less clients rather than a rule about who may
                // call the API. This says what the request actually is, an Android app, and claims
                // to be nothing else; sending anilist.co here would be a lie told to no purpose,
                // because the filter cannot tell the difference.
                .header("Referer", REFERER)
            val token = AuthManager.current()
            if (authenticated && token == null) {
                AnililiError.ACCOUNT_SESSION_EXPIRED.raise(
                    AccountService.ANILIST.label,
                    detail = "A request that needs the account was made with no token held.",
                )
            }
            // Sent on every request once we hold a token, not only where the query needs one.
            // AniList's 2 Aug 2026 shutdown answered anonymous requests with the maintenance 403
            // and let authenticated ones through untouched — measured on a device mid-outage as an
            // interleaved 10x403 / 4x200, the 200s being exactly the list queries that carried the
            // header. A signed-in viewer had no reason to see a blank home. It also buys the
            // higher authenticated rate limit for browsing, which is the quota we pace against.
            if (token != null) builder.header("Authorization", "Bearer $token")
            var retryAfterMs = -1L
            val response = try {
                requestGate.withPermit {
                    awaitRateSlot()
                    client.newCall(builder.build()).awaitResponse().also { resp ->
                        recordRateHeaders(
                            resp.header("X-RateLimit-Limit"),
                            resp.header("X-RateLimit-Remaining"),
                            resp.header("X-RateLimit-Reset"),
                        )
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                currentCoroutineContext().ensureActive()
                // ISP-level blocks of anilist.co (common in some regions) surface as timeouts
                // while the rest of the app's hosts work fine. Name the fix for the user.
                throw IOException(
                    "AniList is unreachable (timeout). Your network may be blocking anilist.co — " +
                        "try private DNS (dns.google) or a VPN.",
                    e,
                )
            } catch (e: java.net.UnknownHostException) {
                currentCoroutineContext().ensureActive()
                throw IOException(
                    "AniList is unreachable (DNS). Your network may be blocking anilist.co — " +
                        "try private DNS (dns.google) or a VPN.",
                    e,
                )
            }
            response.use { resp ->
                if (resp.code == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    val seconds = (resp.header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_SECONDS)
                        .coerceAtLeast(1)
                    retryAfterMs = seconds.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
                    rateRemaining = 0
                    rateResetMs = maxOf(rateResetMs, System.currentTimeMillis() + retryAfterMs)
                } else {
                    val body = resp.body?.string().orEmpty()
                    val graphQlErrors = graphQlErrorMessages(body)
                    if (resp.code == 401) {
                        AnililiError.ACCOUNT_SESSION_EXPIRED.raise(
                            AccountService.ANILIST.label,
                            detail = "AniList answered 401: the sign-in expired or was revoked.",
                        )
                    }
                    // A 429 that survived every retry above is the one-minute lockout, not a blip,
                    // and it is the whole app's rather than this request's — so it gets its own code
                    // and its own advice rather than arriving as "AniList HTTP 429".
                    if (resp.code == 429) {
                        AnililiError.ACCOUNT_RATE_LIMITED.raise(
                            AccountService.ANILIST.label,
                            detail = "AniList refused $MAX_RATE_LIMIT_RETRIES attempts with 429; " +
                                "the limit is per account and IP, and degrades to about 30 a minute.",
                            attributes = mapOf("retryAfterMs" to retryAfterMs),
                        )
                    }
                    if (!resp.isSuccessful) {
                        val detail = graphQlErrors.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { ": $it" }.orEmpty()
                        throw IOException(
                            "AniList HTTP ${resp.code}$detail." +
                                anilistFailureGuidance(resp.code, graphQlErrors),
                        )
                    }
                    if (graphQlErrors.isNotEmpty()) {
                        throw IOException("AniList GraphQL error: ${graphQlErrors.joinToString("; ")}")
                    }
                    return body
                }
            }
            attempt++
            delay(retryAfterMs)
        }
    }

    companion object {
        const val ANILIST_URL = "https://graphql.anilist.co"
        private val MEDIA_LIST_STATUSES =
            setOf("CURRENT", "REPEATING", "PLANNING", "PAUSED", "COMPLETED", "DROPPED")
        /** AniList recommends across media types; these formats mark the ones we cannot play. */
        private val READING_FORMATS = setOf("MANGA", "NOVEL", "ONE_SHOT")
        private const val MAX_STUDIO_MEDIA_PAGES = 20
        private const val STUDIO_MEDIA_PAGE_SIZE = 25
        private const val MAX_RATE_LIMIT_RETRIES = 2
        private const val DEFAULT_RETRY_AFTER_SECONDS = 10L
        private const val MAX_FAVOURITE_PAGES = 40
        private const val MAX_SCHEDULE_PAGES = 10

        /**
         * Days per aliased count query. A six-week calendar grid is 42 cells, so the usual month
         * fits in one request; anything longer is chunked rather than refused.
         */
        internal const val MAX_COUNT_ALIASES = 42

        /**
         * AniList caps `perPage` at 50 whatever is asked for, and a day has never been observed
         * near that (31 was the busiest measured). A day that did overflow is reported as a floor
         * rather than silently undercounted — see `AiringDayCount.truncated`.
         */
        private const val COUNT_PAGE_SIZE = 50

        /** Episode start must be at least this old (~25 min runtime + 1 h for links to appear). */
        private const val NEWEST_AIRED_BUFFER_SEC = 90L * 60
        private const val SAVED_SYNC_BATCH_SIZE = 10
        private const val CUSTOM_LIST_BATCH_SIZE = 10
        private const val USER_AGENT = "Anilili/0.1.14 Android (AniList client 45552)"

        /**
         * What this client is, in the scheme Android itself uses for app-originated requests.
         *
         * Hard-coded rather than built from BuildConfig.APPLICATION_ID because the debug build
         * carries a `.debug` suffix, and the identity AniList sees should be the app, not which
         * copy of it happens to be running.
         */
        private const val REFERER = "android-app://com.miruronative"
    }
}

/** Member count a title needs to be in the random pick's pool; see [AniListClient.randomPool]. */
private const val RANDOM_POOL_MIN_POPULARITY = 10_000
internal const val RANDOM_POOL_PAGE_SIZE = 50
/**
 * Pages the random pick chooses among. The pool ran to 75 when measured; a few short of that keeps
 * every page full as titles drift below the floor, and an empty page shrinks the range on its own.
 */
internal const val RANDOM_POOL_PAGES = 70

private const val DEFAULT_RATE_LIMIT = 30
private const val RATE_WINDOW_MS = 60_000L
private const val RATE_SAFETY_MS = 100L
private const val MIN_SPACING_MS = 100L
// Cap on the per-request gap while spreading a low budget across the rest of the window.
private const val MAX_SPACING_MS = 3_000L
// Cap on how long to hold when the budget is fully spent and we're awaiting a reset.
private const val MAX_BACKOFF_MS = 60_000L
// Start spreading requests once this few remain in the window. Above it a burst runs at full
// speed; the reserve is what a 429 (a one-minute lockout for the whole app) is bought with.
internal const val RATE_LOW_WATERMARK = 8
// Sentinel for "no X-RateLimit-Remaining seen yet"; matches AniListClient's initial value.
internal const val UNKNOWN_RATE_REMAINING = Int.MAX_VALUE
internal const val SLOW_WAIT_LOG_MS = 750L

/**
 * Pure slot scheduler for AniList pacing. Given [now], the last-seen budget ([remaining] and its
 * window [reset], both ms epoch) and the previously reserved [nextSlot], returns
 * (thisRequestStartMs, newNextSlotMs).
 *
 * The budget is a bucket to spend, not a rate to hold. Spacing every call by the quota average
 * meant a cold start — where nothing has been observed yet — paced its first requests 2.1s apart,
 * so the home screen's query queued behind the notification, reminder and library calls that fire
 * at startup and blew its 15s budget: users saw "Home request timed out" on a healthy connection.
 * Bursts now run at [MIN_SPACING_MS] while the budget is healthy or unknown, and only glide toward
 * the window [reset] once the remaining budget is genuinely low ([RATE_LOW_WATERMARK]) — where the
 * spread is what keeps a 429 (a full one-minute lockout) away.
 */
/** An AniList broadcast season: `WINTER`, `SPRING`, `SUMMER` or `FALL`, and its year. */
data class AiringSeason(val season: String, val year: Int) {
    companion object {
        /** The season [date] falls in, by AniList's calendar quarters. */
        fun of(date: java.time.LocalDate): AiringSeason = AiringSeason(
            season = when (date.monthValue) {
                in 1..3 -> "WINTER"
                in 4..6 -> "SPRING"
                in 7..9 -> "SUMMER"
                else -> "FALL"
            },
            year = date.year,
        )

        fun current(): AiringSeason = of(java.time.LocalDate.now())
    }
}

internal fun nextRateSlot(
    now: Long,
    remaining: Int,
    reset: Long,
    nextSlot: Long,
    limit: Int = DEFAULT_RATE_LIMIT,
): Pair<Long, Long> {
    val earliest = if (remaining <= 0 && now < reset) minOf(reset, now + MAX_BACKOFF_MS) else now
    val quotaSpacing = (RATE_WINDOW_MS / limit.coerceAtLeast(1) + RATE_SAFETY_MS).coerceAtLeast(MIN_SPACING_MS)
    val windowOpen = now < reset
    val interval = when {
        // Nothing observed yet, or the window has already rolled over: the budget is whole.
        remaining == UNKNOWN_RATE_REMAINING || !windowOpen -> MIN_SPACING_MS
        // Comfortable budget: let the burst through and let the response headers tighten pacing.
        remaining > RATE_LOW_WATERMARK -> MIN_SPACING_MS
        remaining > 0 -> maxOf(quotaSpacing, ((reset - now) / remaining).coerceAtMost(MAX_SPACING_MS))
        else -> quotaSpacing
    }
    // A reserved slot is a wall-clock time, so a clock that steps backwards — a box booting ahead
    // and then corrected by network time — leaves it that far in the future. Uncapped, that held
    // every AniList request, a page reload included, for over an hour behind a spinner. No slot
    // this function hands out is ever further off than a full backoff plus one spacing, so one
    // that is can only be the clock's doing, and is dropped rather than waited out.
    val reserved = if (nextSlot > now + MAX_BACKOFF_MS + MAX_SPACING_MS) now else nextSlot
    val start = maxOf(earliest, reserved)
    return start to (start + interval)
}

internal data class MediaListProgressSnapshot(
    val id: Int,
    val status: String,
    val progress: Int,
    /** Times the title has been watched through again: AniList's `repeat`, MAL's `num_times_rewatched`. */
    val repeat: Int = 0,
    val startedAt: ListDate? = null,
    val completedAt: ListDate? = null,
)

/** A list entry's start or finish date, as precise as the viewer set it. */
internal data class ListDate(val year: Int, val month: Int? = null, val day: Int? = null)

/**
 * One title's complete set of custom lists, as it should be on the account. [create] asks for a
 * new, list-only entry, for a title the account does not have yet.
 */
data class CustomListWrite(val mediaId: Int, val lists: List<String>, val create: Boolean = false)
data class MediaListProgressUpdate(
    val progress: Int,
    val status: String?,
    /** The rewatch count to write, set only when this update finishes a rewatch. */
    val repeat: Int? = null,
)

/**
 * The `id_in` list a set of filters comes down to, or null when the catalog is not being narrowed
 * to a fixed set of titles at all.
 *
 * Two filters share this one argument — studio and dubbed-only — so with both on, only the titles
 * in both lists answer the question that was actually asked. The index arrives as a set because
 * that intersection asks it thousands of membership questions, one per title the studio made.
 */
internal fun discoverMediaIds(
    filters: DiscoverFilters,
    studioMediaIds: List<Int>?,
    dubbedIds: Set<Int>,
): List<Int>? = when {
    !filters.dubbedOnly -> studioMediaIds
    studioMediaIds == null -> dubbedIds.toList()
    else -> studioMediaIds.filter { it in dubbedIds }
}

internal fun discoverVariables(
    filters: DiscoverFilters,
    page: Int,
    perPage: Int,
    hideAdult: Boolean,
    mediaIds: List<Int>? = null,
    excludedIds: Collection<Int> = emptyList(),
): JsonObject = buildJsonObject {
    filters.query.trim().takeIf { it.isNotEmpty() }?.let { put("search", it) }
    put("page", page)
    put("perPage", perPage)
    if (hideAdult) put("isAdult", false)
    if (filters.genres.isNotEmpty()) put("genres", buildJsonArray { filters.genres.forEach(::add) })
    if (filters.tags.isNotEmpty()) put("tags", buildJsonArray { filters.tags.forEach(::add) })
    if (filters.excludedGenres.isNotEmpty()) {
        put("excludedGenres", buildJsonArray { filters.excludedGenres.forEach(::add) })
    }
    if (filters.excludedTags.isNotEmpty()) {
        put("excludedTags", buildJsonArray { filters.excludedTags.forEach(::add) })
    }
    mediaIds?.takeIf { it.isNotEmpty() }?.let { ids ->
        put("mediaIds", buildJsonArray { ids.forEach(::add) })
    }
    // Sorted so the same set always serialises the same way, whatever order the library held it in.
    if (excludedIds.isNotEmpty()) put("excludedIds", buildJsonArray { excludedIds.sorted().forEach(::add) })
    filters.year?.let { put("year", it) }
    filters.season?.let { put("season", it) }
    // A FuzzyDateInt is yyyymmdd, and startDate_greater/_lesser are exclusive like every other
    // AniList range argument. Aiming at the zero day either side of the span — 20100000 and
    // 20200000 for "2010–2019" — lands strictly outside every real date in it, so both ends of the
    // span the viewer picked are included without arithmetic on months nobody supplied.
    filters.yearFrom?.let { put("startedAfter", it * 10_000) }
    filters.yearTo?.let { put("startedBefore", (it + 1) * 10_000) }
    filters.status?.let { put("status", it) }
    filters.format?.let { put("format", it) }
    filters.country?.let { put("country", it) }
    filters.source?.let { put("source", it) }
    // Same exclusive-bound correction as the score below: the buckets are written as inclusive
    // ranges ("13–26"), so each end is pushed one past what the viewer chose.
    filters.episodesMin?.let { put("episodesMin", (it - 1).coerceAtLeast(0)) }
    filters.episodesMax?.let { put("episodesMax", it + 1) }
    // AniList's *_greater filter is exclusive; the UI labels this value as inclusive.
    filters.minimumScore?.let { put("minimumScore", (it - 1).coerceAtLeast(0)) }
    put("sort", buildJsonArray { add(filters.sort) })
}

/** A score out of ten as AniList's format-independent 0–100 `scoreRaw`; 0 clears it. */
internal fun aniListScoreRaw(score: Int): Int {
    require(score in 0..10) { "A list score is 0 to 10, not $score" }
    return score * 10
}

/**
 * Pure policy used by playback sync; null means the user's AniList entry should not be changed.
 *
 * A REPEATING entry is a rewatch, and its last episode finishes it: back to COMPLETED with the
 * rewatch count one higher. That holds even when the count never moved during the rewatch — an
 * entry put on Rewatching elsewhere usually keeps its full count, and every episode before the
 * last then reads as "already watched" — because the finale is the one thing that ends a rewatch.
 */
internal fun planMediaListProgressUpdate(
    current: MediaListProgressSnapshot?,
    watchedProgress: Int,
    totalEpisodes: Int?,
): MediaListProgressUpdate? {
    if (watchedProgress < 1) return null
    val completed = totalEpisodes?.takeIf { it > 0 }?.let { watchedProgress >= it } == true
    if (current?.status == "REPEATING" && completed) {
        return MediaListProgressUpdate(progress = watchedProgress, status = "COMPLETED", repeat = current.repeat + 1)
    }
    if (current != null && watchedProgress <= current.progress) return null
    if (current?.status == "COMPLETED") return null
    val status = when {
        current == null -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "PLANNING" -> if (completed) "COMPLETED" else "CURRENT"
        current.status == "CURRENT" && completed -> "COMPLETED"
        else -> null // Preserve REPEATING, PAUSED, DROPPED, and any future AniList states.
    }
    return MediaListProgressUpdate(progress = watchedProgress, status = status)
}

/** What a status move has to write besides the status, so a rewatch is recorded rather than lost. */
internal data class RewatchMove(
    /** Start the episode count again from 0: a rewatch counts its own episodes. */
    val resetProgress: Boolean = false,
    /** The rewatch count to write; null leaves it. */
    val repeat: Int? = null,
) {
    val touchesRewatch: Boolean get() = resetProgress || repeat != null
}

/**
 * The rewatch bookkeeping for moving an entry from [currentStatus] to [target], in AniList
 * vocabulary (a MAL entry flagged `is_rewatching` is REPEATING here).
 *
 * Starting a rewatch of a finished title counts from 0, as AniList's and MAL's own editors do;
 * leaving the count at the end would make every episode of the rewatch read as already watched.
 * Finishing one — moving REPEATING to COMPLETED by hand, or marking it watched — counts it, the
 * same as playback reaching the last episode does ([planMediaListProgressUpdate]).
 */
internal fun planRewatchMove(currentStatus: String?, currentRepeat: Int, target: String): RewatchMove = when {
    target == "REPEATING" && currentStatus == "COMPLETED" -> RewatchMove(resetProgress = true)
    target == "COMPLETED" && currentStatus == "REPEATING" -> RewatchMove(repeat = currentRepeat + 1)
    else -> RewatchMove()
}

/**
 * The entry's own dates, to send back unchanged with a write that ends or starts a rewatch.
 *
 * A rewatch belongs to the original watch period, and neither service keeps a second one. Writing
 * the dates back costs nothing and keeps them whatever the server does on the status change; a
 * date the entry does not have is left out rather than sent empty, which would clear it.
 */
internal fun preservedListDates(entry: MediaListProgressSnapshot?): List<Pair<String, ListDate>> = listOfNotNull(
    entry?.startedAt?.let { "startedAt" to it },
    entry?.completedAt?.let { "completedAt" to it },
)

private fun ListDate.toFuzzyDateInput(): JsonObject = buildJsonObject {
    put("year", year)
    month?.let { put("month", it) }
    day?.let { put("day", it) }
}

private fun JsonObject?.toListDate(): ListDate? {
    val year = this?.get("year")?.jsonPrimitive?.intOrNull ?: return null
    return ListDate(year, this["month"]?.jsonPrimitive?.intOrNull, this["day"]?.jsonPrimitive?.intOrNull)
}

/**
 * The `SaveMediaListEntry` pieces for a rewatch write: variable declarations, arguments and their
 * values for [repeat] and [entry]'s preserved dates. A write that is not about a rewatch passes a
 * null [entry] and [repeat] and gets three empties.
 */
internal fun rewatchArguments(
    entry: MediaListProgressSnapshot?,
    repeat: Int?,
): Triple<String, String, Map<String, JsonElement>> {
    val values = buildMap<String, Pair<String, JsonElement>> {
        repeat?.let { put("repeat", "Int" to JsonPrimitive(it)) }
        preservedListDates(entry).forEach { (name, date) -> put(name, "FuzzyDateInput" to date.toFuzzyDateInput()) }
    }
    val declarations = values.entries.joinToString("") { (name, typed) -> ", ${'$'}$name: ${typed.first}" }
    val arguments = values.keys.joinToString("") { name -> ", $name: ${'$'}$name" }
    return Triple(declarations, arguments, values.mapValues { it.value.second })
}

/**
 * What to add after an AniList HTTP failure, if anything.
 *
 * A bare 403 is usually something between the app and AniList — a network or DNS filter — so the
 * advice to change networks is worth giving. But AniList answers 403 with an explanation of its own
 * when it takes the API down on purpose, as it did on 2 Aug 2026 ("temporarily disabled due to
 * severe stability issues"). Appending "try again or switch networks" to that sends people to hunt
 * a connection fault that does not exist, on an outage no network change can get around. When
 * AniList has said why, let it speak for itself.
 */
internal fun anilistFailureGuidance(
    code: Int,
    graphQlErrors: List<String>,
): String = when {
    code != 403 -> ""
    // This used to read "signing in should restore this — it is refusing anonymous requests", on
    // the evidence of the 2 Aug 2026 shutdown, where a tokenless request was refused fourteen times
    // out of fourteen and the same query with a token went through. That reading was wrong, and a
    // user on the 22 Aug refusal proved it by following the advice and still being refused.
    //
    // What is actually refused is a request carrying neither a Referer nor an Authorization header;
    // the token only ever helped because it is a header. Measured 22 Aug: no headers 403, any
    // Referer at all 200, and even an invalid bearer token reaches token validation. The app now
    // always sends a Referer, so an explained 403 arriving here is no longer something the person
    // reading it can do anything about.
    //
    // It was also the wrong thing to say to the people most likely to see it. Someone signed in
    // with MyAnimeList holds no AniList token, so `signedIn` is false for them — and AniList is
    // still the metadata backbone their library is built from. They were told to sign in to the
    // service they had deliberately not chosen, to fix something signing in would not have fixed.
    graphQlErrors.isNotEmpty() -> ""
    // A bare 403 with no explanation is more often something sitting between the app and AniList.
    else -> " AniList may be unavailable or this network may be blocked; try again or switch networks."
}

internal fun graphQlErrorMessages(body: String): List<String> = runCatching {
    val root = Json.parseToJsonElement(body).jsonObject
    (root["errors"] as? JsonArray).orEmpty().mapNotNull { element ->
        val error = element as? JsonObject ?: return@mapNotNull null
        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val status = (error["extensions"] as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
        if (status == null) message else "$message ($status)"
    }
}.getOrDefault(emptyList())

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    var deliveredResponse: Response? = null
    continuation.invokeOnCancellation {
        cancel()
        deliveredResponse?.close()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            deliveredResponse = response
            if (continuation.isActive) {
                continuation.resumeWith(Result.success(response))
            } else {
                response.close()
            }
        }
    })
}

package com.anilili.data.remote

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.anilili.data.cache.AppCache
import com.anilili.data.MetadataOverrides
import com.anilili.data.parseDubEpisodes
import com.anilili.data.parseDubIndex
import com.anilili.data.model.AiringSchedule
import com.anilili.data.model.CoverImage
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class CachedMapping(
    val anilistId: Int?,
)

@Serializable
private data class KonohaMappingResponse(
    val anilist_id: Int,
)

/**
 * One title from Konoha's per-anime `index.json` — the detail record behind a title page.
 *
 * Only the fields the detail screen actually draws are modelled; Konoha also carries match
 * confidence, cache bookkeeping and streaming links that mean nothing here.
 */
@Serializable
data class KonohaDetail(
    val ids: KonohaIds = KonohaIds(),
    val titles: KonohaTitles = KonohaTitles(),
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val season_year: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val score_anilist: Int? = null,
    val popularity: Int? = null,
    val images: KonohaImages = KonohaImages(),
    val bangumi: KonohaBangumi? = null,
    /**
     * The AniList record in the app's own shape, written by the generator from its daily catalogue
     * walk — relations, tags, studios, trailer and dates included. Null in trees generated before
     * it existed. See [com.anilili.data.MiruroRepository.animeInfo] for when it is believed.
     */
    val anilist: Media? = null,
    /** The season chain, walked by the generator exactly as the app walks it; the title included. */
    val series: List<Int> = emptyList(),
    /** AniList's community recommendations, best rated first, as list-card records. */
    val recommendations: List<Media> = emptyList(),
)

/**
 * The Bangumi subject this title was matched to, and the names to search Chinese sources with.
 *
 * Absent on roughly a third of the catalogue — almost all of it music videos and specials; TV
 * matched at 96.5% — so every reader has to cope with null rather than treat it as a given.
 *
 * [search_names] is the field that matters and it is deliberately not just [name_cn]. Chinese
 * sites title a page with whatever name their uploader used, which is frequently one of Bangumi's
 * aliases: 嘀嗒影视 lists 躲在超市后门抽烟的两人 for the subject whose `name_cn` is 在超市后门吸烟的二人,
 * so searching the canonical name alone never reaches it. The list is ordered most-canonical
 * first — `name_cn`, then the Japanese name, then aliases — so a caller that can only afford one
 * request still makes the best one.
 */
@Serializable
data class KonohaBangumi(
    val id: Int? = null,
    val name: String? = null,
    val name_cn: String? = null,
    val search_names: List<String> = emptyList(),
    /** `STRONG`, `SEED`, `WEAK`, `REVIEW` or `OVERRIDE`; only STRONG and OVERRIDE are corroborated. */
    val confidence: String? = null,
)

/**
 * Stream URLs for one title on XFDM, resolved by the metadata build instead of on the device.
 *
 * The provider's own lookup is two requests to find the title and a third to resolve the episode —
 * about 1,750ms on a phone. Reading this file instead was measured at 111-167ms cold and nothing
 * at all once the app has it. That is only sound because these URLs do not expire: re-resolved
 * eight hours apart, seven of seven came back byte-identical, with no token, signature or `exp`.
 *
 * Present only for the titles the build has crawled, so every caller has to cope with null and fall
 * back to resolving live.
 *
 * @param base the directory every episode shares, stored once; a full URL is [base] + the entry.
 *   One Piece's 1,113 episodes are 19KB this way against 86KB written out in full, and that size is
 *   latency a viewer waits through to play a single episode.
 * @param layout `archive` for a finished title, whose path has been stable for years, or `temp` for
 *   one still airing. The site stages the current year under `/temp/{YYMM}/` and files it away at
 *   the rollover, so a `temp` entry is expected to break once and be healed rather than trusted.
 */
@Serializable
data class KonohaLiliSources(
    val provider: String? = null,
    val bangumi: Int? = null,
    val subject: String? = null,
    val layout: String? = null,
    val crawled: String? = null,
    val base: String = "",
    val episodes: Map<String, String> = emptyMap(),
) {
    /** The full URL for [episode], or null when this file does not carry it. */
    fun urlFor(episode: Int): String? =
        episodes[episode.toString()]?.takeIf { it.isNotBlank() }?.let { base + it }

    /** Whether the site is still staging this title and its paths are due to move. */
    val isProvisional: Boolean get() = layout.equals("temp", ignoreCase = true)
}

@Serializable
data class KonohaIds(val anilist: Int? = null, val mal: Int? = null)

@Serializable
data class KonohaTitles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

/**
 * Konoha's detail record as the app's own [Media].
 *
 * Konoha aggregates AniList, so the shapes line up field for field. What it does not carry —
 * relations, studios in the app's own shape, trailers, next-airing — stays at its default rather
 * than being invented, so a title page built from this is thinner than a live one but never wrong.
 */
fun KonohaDetail.toMedia(anilistId: Int): Media = Media(
    id = ids.anilist ?: anilistId,
    idMal = ids.mal,
    title = MediaTitle(
        romaji = titles.romaji,
        english = titles.english,
        native = titles.native,
        userPreferred = titles.english ?: titles.romaji,
    ),
    description = description,
    coverImage = CoverImage(large = images.poster, color = images.poster_color),
    bannerImage = images.banner,
    format = format,
    season = season,
    seasonYear = season_year,
    episodes = episodes,
    duration = duration,
    status = status,
    averageScore = score_anilist,
    popularity = popularity,
    genres = genres,
)

@Serializable
data class KonohaImages(
    val poster: String? = null,
    val poster_color: String? = null,
    val banner: String? = null,
    /**
     * The series' own typography as a transparent PNG — its logo, not a cover with the name burnt
     * into the artwork — or null where no artwork source has one.
     *
     * Written by the tree's AniZip pass, which reads it from TVDB. Two shapes come back and the
     * reader has to tolerate both: modern records are cropped to the wordmark, older ones are the
     * same wordmark centred in a transparent square. Either is fine inside a box that fits rather
     * than crops, which is why the field carries the URL as published rather than a normalised one.
     */
    val logo: String? = null,
)

/**
 * One airing from the metadata tree's `schedule/<YYYY-MM>.json`.
 *
 * The three title forms and the cover are carried separately rather than pre-picked, because which
 * one to show is the app's decision — [com.anilili.data.model.MediaTitle.preferred] resolves it —
 * and baking one choice into the dataset would freeze that order for every reader.
 *
 * Absent fields are dropped by the generator rather than written as null, so every field here needs
 * a default; a month is mostly absences.
 */
@Serializable
data class KonohaAiring(
    val id: Int = 0,
    val episode: Int = 0,
    val airingAt: Long = 0,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
    val cover: String? = null,
    val format: String? = null,
    val year: Int? = null,
    /** Written only when true, so its absence is the answer for almost every row. */
    val adult: Boolean = false,
)

/**
 * One published airing as the rest of the app already understands it.
 *
 * The schedule row carries exactly the fields a schedule card renders, so this fills in those and
 * leaves the rest of [Media] at its defaults rather than pretending to a completeness the file does
 * not have: tapping the card opens the detail screen, which fetches the title properly.
 */
fun KonohaAiring.toAiringSchedule(): AiringSchedule = AiringSchedule(
    episode = episode,
    airingAt = airingAt,
    media = Media(
        id = id,
        title = MediaTitle(english = english, romaji = romaji, native = native),
        // The generator already picked extraLarge over large, and `CoverImage.best` reads either.
        coverImage = CoverImage(large = cover),
        format = format,
        seasonYear = year,
        isAdult = adult,
    ),
)

/** One episode from Konoha's per-anime `episodes.json` (TMDB-backed titles and stills). */
@Serializable
data class KonohaEpisode(
    val number: Double? = null,
    val title: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    /**
     * What viewers scored this episode, from the tree's AniZip pass, or null where none reached it.
     *
     * TMDB is the tree's source for everything else on the row, but the generator takes the score
     * from AniZip's TVDB-backed mapping instead: it answers for nearly every episode of a title
     * anyone is likely to open, which is the only place a score is worth the pixel it costs.
     */
    val rating: Double? = null,
)

class KonohaClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: kotlinx.serialization.json.Json,
    private val cache: AppCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The bundled id-map's tables. Every lookup answers null from here until the parse lands
     * (#84): a fresh instance is empty, so "not loaded yet" and "never mapped" are one answer,
     * and no lookup ever waits for the load. The whole instance is published by one volatile
     * write after the walk has finished, so a reader that sees it sees all of it.
     */
    @Volatile
    private var idMap = IdMapTables()

    /**
     * The first-use parse of id-map.json, kicked off by [requestIdMapLoad] (#84).
     *
     * LAZY plus start() is exactly-once by construction: start() moves the coroutine from New to
     * Active with a single CAS, so however many threads reach a lookup first, the walk runs once.
     * It used to be launched from `init` — paying the parse on every process start, including
     * WorkManager-only ones, and leaving `tmdbRefFor`/`tvdbRefFor` to block Main on its lock while
     * a Detail composed — so the parse now runs here, on the client's IO scope, when something
     * first asks. A failed walk leaves the empty instance published, as the old load left its
     * empty maps; nothing retries it.
     */
    private val idMapLoad = scope.launch(start = CoroutineStart.LAZY) {
        val startedAt = System.currentTimeMillis()
        val parsed = try {
            MetadataOverrides.read(context, ID_MAP_ASSET) { stream -> parseIdMap(stream) }
        } catch (e: Exception) {
            com.anilili.diagnostics.DiagnosticsLog.throwable("Failed to load id-map.json", e)
            null
        }
        val tables = parsed ?: IdMapTables()
        idMap = tables
        if (parsed != null) {
            com.anilili.diagnostics.DiagnosticsLog.event(
                "id-map loaded entries=${tables.entries} in ${System.currentTimeMillis() - startedAt}ms",
            )
        }
    }

    /** Kicks the first-use load without waiting for it; every start after the first is a no-op. */
    private fun requestIdMapLoad() {
        idMapLoad.start()
    }

    /**
     * The bundled map's guess at which TMDB record an AniList id is, or null when it has none.
     *
     * A guess is all it is: the map ships a `confidence` of its own and it cannot be believed. It
     * rates AniList 171018 (Dandadan) as a HIGH-confidence match for TMDB 274861, which is a 2024
     * Chinese drama. Whatever this returns has to be checked against the AniList record before its
     * episode stills are put on screen — see [TmdbClient].
     */
    fun tmdbRefFor(anilistId: Int): TmdbRef? {
        // First use starts the load and answers at once. Null here means "not loaded yet" as much
        // as "unmapped", so a Detail opened mid-parse falls back rather than blocking Main (#84).
        requestIdMapLoad()
        return idMap.tmdbRef(anilistId)
    }

    /**
     * Which TheTVDB series an AniList id belongs to, and which season of it that entry is.
     *
     * AniList has no concept of a season: "season two" is a separate entry joined to the first by a
     * PREQUEL/SEQUEL relation, and that chain also carries spin-offs, recaps and films with no way
     * to tell them apart beyond `format`. TheTVDB groups a series the way viewers talk about it —
     * one series id, one numbered season per entry — so a chain built on it holds exactly the
     * entries that belong and orders them correctly.
     *
     * Null when the cross-reference has never placed the title, which is about a fifth of the
     * catalogue; callers fall back to the relation chain.
     */
    fun tvdbRefFor(anilistId: Int): TvdbRef? {
        // Same shape as [tmdbRefFor]: kick the load, read what is published, never wait (#84).
        requestIdMapLoad()
        return idMap.tvdbRef(anilistId)
    }

    /**
     * Resolves an AniList ID from a MyAnimeList ID.
     * Checks the bundled map first (starting its first-use load if it has not run), then falls
     * back to AppCache/CDN.
     */
    suspend fun resolveAnilistIdFromMal(malId: Int): Int? {
        // The one lookup allowed to wait for the map, and only by suspending — this runs on
        // background sync paths, never in composition (#84). join() also starts the LAZY job, so
        // a MAL resolution alone triggers the load; a load that failed published empty tables and
        // the empty lookup falls through to the CDN mapping below exactly as an unloaded one did.
        requestIdMapLoad()
        idMapLoad.join()
        idMap.anilistForMal(malId)?.let { return it }

        return withContext(Dispatchers.IO) {
            val cacheKey = "konohamal:v1:$malId"
            
            val cached = cache.getOrFetch(
                key = cacheKey,
                serializer = CachedMapping.serializer(),
                ttlMs = TTL_HIT_MS,
            ) {
                val shard = malId / 1000
                val url = "$DATA_BASE/mappings/mal/$shard/$malId.json"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                    
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.code == 404) {
                            CachedMapping(null)
                        } else if (!response.isSuccessful) {
                            error("Konoha HTTP ${response.code}")
                        } else {
                            val body = response.body?.string().orEmpty()
                            val parsed = json.decodeFromString(KonohaMappingResponse.serializer(), body)
                            CachedMapping(parsed.anilist_id)
                        }
                    }
                }.getOrElse {
                    throw it
                }
            }
            
            cached.anilistId
        }
    }

    /**
     * The detail record for one AniList id from the Konoha CDN.
     *
     * Konoha aggregates AniList itself, so this is the same metadata a title page normally shows —
     * only served from a static CDN that stays up when AniList does not. Null when Konoha has never
     * seen the title, or on any failure; the caller keeps whatever it already had.
     */
    suspend fun details(anilistId: Int): KonohaDetail? = withContext(Dispatchers.IO) {
        runCatching {
            cache.getOrFetch(
                // v2 since `bangumi` was added. The field defaults to null, so a v1 row still
                // decodes rather than being discarded as corrupt — which is exactly the problem:
                // it would decode to "this title has no Chinese names" and hold that for the
                // thirty-day TTL, hiding the Chinese sources from every existing install.
                // v3 since `anilist`, `series` and `recommendations` were added: a v2 row decodes
                // with all three empty and would keep the title on AniList for its whole TTL.
                key = "konohadetail:v3:$anilistId",
                serializer = KonohaDetail.serializer().nullable,
                ttlMs = EPISODES_TTL_MS,
            ) {
                val shard = anilistId / 1000
                val url = "$DATA_BASE/anime/$shard/$anilistId/index.json"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful -> error("Konoha HTTP ${response.code}")
                        else -> json.decodeFromString(
                            KonohaDetail.serializer().nullable,
                            response.body?.string().orEmpty(),
                        )
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Episode metadata (titles, thumbnails, synopses) for one AniList id from the Konoha CDN.
     * Zero rate limits and ~CDN latency, so the detail page can show a full episode list
     * instantly. Empty when Konoha doesn't know the title or on any failure.
     */
    /**
     * XFDM's stream URLs for one title, or null when the build has not crawled it.
     *
     * A 404 is the ordinary answer — only part of the catalogue is crawled — and is cached as null
     * so a title without a file is not re-asked on every episode. Failures are swallowed for the
     * same reason: this is an optimisation, and a provider that can still resolve live should not
     * be taken off a title because a CDN read failed.
     */
    suspend fun liliSources(anilistId: Int): KonohaLiliSources? = withContext(Dispatchers.IO) {
        runCatching {
            cache.getOrFetch(
                key = "konohalili:v1:$anilistId",
                serializer = KonohaLiliSources.serializer().nullable,
                ttlMs = LILI_TTL_MS,
            ) {
                val shard = anilistId / 1000
                val url = "$DATA_BASE/anime/$shard/$anilistId/lili.json"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful -> error("Konoha HTTP ${response.code}")
                        else -> json.decodeFromString(
                            KonohaLiliSources.serializer().nullable,
                            response.body?.string().orEmpty(),
                        )
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun episodes(anilistId: Int): List<KonohaEpisode> = withContext(Dispatchers.IO) {
        cache.getOrFetch(
            key = "konohaeps:v1:$anilistId",
            serializer = ListSerializer(KonohaEpisode.serializer()),
            ttlMs = EPISODES_TTL_MS,
        ) {
            val shard = anilistId / 1000
            val url = "$DATA_BASE/anime/$shard/$anilistId/episodes.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList()
                    !response.isSuccessful -> error("Konoha HTTP ${response.code}")
                    else -> json.decodeFromString(
                        ListSerializer(KonohaEpisode.serializer()),
                        response.body?.string().orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Every airing in one month, from the metadata tree. Empty when that month has never been
     * published, which is how a caller knows to ask AniList itself instead.
     *
     * A month's file covers the month plus a week either side, so one of these serves a whole
     * calendar grid — including the neighbouring days that finish its first and last weeks — in any
     * device timezone. That is the point of reading it at all: the same screen costs AniList one
     * aliased request per month and a 15-minute cache, against a CDN file with no rate limit
     * attached to it at all.
     *
     * [month] is `YYYY-MM`. Rebuilt daily by `scripts/konoha_build.py schedule`, so a day's rows can
     * be up to a build old — times move rarely and episode numbers not at all, which is the trade
     * a calendar is happy with.
     */
    suspend fun schedule(month: String, force: Boolean = false): List<KonohaAiring> = withContext(Dispatchers.IO) {
        cache.getOrFetch(
            key = "konohasched:v1:$month",
            serializer = ListSerializer(KonohaAiring.serializer()),
            ttlMs = SCHEDULE_TTL_MS,
            // A pull-to-refresh re-reads the month rather than reaching past it to AniList: the
            // file is what the screen is showing, and re-downloading 22KB is the cheaper answer to
            // "is there anything newer" than a request against a 30-a-minute budget.
            forceRefresh = force,
        ) {
            val url = "$DATA_BASE/schedule/$month.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList()
                    !response.isSuccessful -> error("Konoha HTTP ${response.code}")
                    else -> json.decodeFromString(
                        ListSerializer(KonohaAiring.serializer()),
                        response.body?.string().orEmpty(),
                    )
                }
            }
        }
    }

    /**
     * Every AniList id the published crawl has found a dub for.
     *
     * Deliberately not cached through [AppCache] like everything else here. [DubIndex] needs this
     * list on disk in a form it can read *synchronously* on the startup path, before the first
     * grid is drawn, so it keeps its own file and asks this only when that file is a day old. A
     * second copy in the cache would just be a second thing to expire.
     *
     * Throws rather than returning empty on any failure, because empty and "could not ask" mean
     * opposite things to the caller: one would overwrite a good overlay with nothing.
     *
     * The size guard is a whole megabyte against a file that is currently 13 KB — it is there to
     * stop a mis-served body (a CDN error page, another repo's file) being read into memory, not
     * to police growth.
     */
    suspend fun dubIndex(): Set<Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$DATA_BASE/dub-index.json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Dub index HTTP ${response.code}" }
            val source = checkNotNull(response.body).source()
            require(!source.request(MAX_DUB_INDEX_BYTES + 1)) { "Dub index too large" }
            parseDubIndex(source.readUtf8())
        }
    }

    /**
     * Filler episode numbers by AniList id, from `data/filler-index.json` — built by
     * `scripts/filler_index_build.py`. A title mapped to an empty set was checked and has no
     * filler; a title that is absent was never answered, and the caller falls back to MAL.
     *
     * The raw file goes through [AppCache] so it survives restarts, and the parsed map is kept
     * here for [FILLER_INDEX_MEMO_MS]: every episode list asks, and parsing a hundred-odd KB of
     * JSON per ask is the kind of cost a television pays thirty times over.
     */
    suspend fun fillerIndex(): Map<Int, Set<Int>> {
        fillerIndexMemo?.takeIf { System.currentTimeMillis() - it.first < FILLER_INDEX_MEMO_MS }
            ?.let { return it.second }
        val raw = withContext(Dispatchers.IO) {
            cache.getOrFetch(
                key = "fillerindex:v1",
                serializer = FillerIndex.serializer(),
                ttlMs = FILLER_INDEX_TTL_MS,
                cacheIf = { it.titles.isNotEmpty() },
            ) {
                val request = Request.Builder().url("$DATA_BASE/filler-index.json").build()
                client.newCall(request).execute().use { response ->
                    when {
                        // Not published yet: an empty index, which leaves every title to MAL.
                        response.code == 404 -> FillerIndex()
                        !response.isSuccessful -> error("Filler index HTTP ${response.code}")
                        else -> {
                            val source = checkNotNull(response.body).source()
                            require(!source.request(MAX_FILLER_INDEX_BYTES + 1)) { "Filler index too large" }
                            json.decodeFromString(FillerIndex.serializer(), source.readUtf8())
                        }
                    }
                }
            }
        }
        val parsed = raw.titles.mapNotNull { (id, numbers) -> id.toIntOrNull()?.let { it to numbers.toSet() } }.toMap()
        fillerIndexMemo = System.currentTimeMillis() to parsed
        return parsed
    }

    /**
     * AniSkip's candidates for every episode of a title, mirrored into `data/skip/` by
     * `scripts/skip_index_build.py`; null when the title was never mirrored. See
     * [com.anilili.data.MiruroRepository.skipTimes] for how an episode missing from it is handled.
     */
    suspend fun skipTimes(anilistId: Int): KonohaSkipFile? = withContext(Dispatchers.IO) {
        runCatching {
            cache.getOrFetch(
                key = "konohaskip:v1:$anilistId",
                serializer = KonohaSkipFile.serializer().nullable,
                ttlMs = SKIP_TTL_MS,
            ) {
                val request = Request.Builder()
                    .url("$DATA_BASE/skip/${anilistId / 1000}/$anilistId.json")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        !response.isSuccessful -> error("Konoha skip HTTP ${response.code}")
                        else -> json.decodeFromString(KonohaSkipFile.serializer(), response.body?.string().orEmpty())
                    }
                }
            }
        }.getOrNull()
    }

    @Volatile
    private var fillerIndexMemo: Pair<Long, Map<Int, Set<Int>>>? = null

    /**
     * Landscape pictures for the TV hero by AniList id, from `data/backdrops.json`, written by
     * `scripts/konoha_build.py emit` for titles AniList has no banner for. Absent means the dataset
     * has none, and [com.anilili.data.MiruroRepository.heroBackdrop] then asks AniZip and TMDB itself.
     *
     * The answer is kept in memory as well: Home asks once per featured title, and the cache would
     * otherwise be read from disk and parsed again for every one.
     */
    suspend fun heroBackdrops(): Map<Int, String> {
        heroBackdropsMemo?.takeIf { System.currentTimeMillis() - it.first < HERO_BACKDROPS_TTL_MS }
            ?.let { return it.second }
        val raw = withContext(Dispatchers.IO) {
            cache.getOrFetch(
                key = "herobackdrops:v1",
                serializer = HeroBackdropIndex.serializer(),
                ttlMs = HERO_BACKDROPS_TTL_MS,
                cacheIf = { it.titles.isNotEmpty() },
            ) {
                val request = Request.Builder().url("$DATA_BASE/backdrops.json").build()
                client.newCall(request).execute().use { response ->
                    when {
                        // Not published yet: every title goes to the live lookup.
                        response.code == 404 -> HeroBackdropIndex()
                        !response.isSuccessful -> error("Hero backdrops HTTP ${response.code}")
                        else -> {
                            val source = checkNotNull(response.body).source()
                            require(!source.request(MAX_HERO_BACKDROPS_BYTES + 1)) { "Hero backdrops too large" }
                            json.decodeFromString(HeroBackdropIndex.serializer(), source.readUtf8())
                        }
                    }
                }
            }
        }
        val parsed = raw.titles.mapNotNull { (id, url) -> id.toIntOrNull()?.let { it to url } }.toMap()
        heroBackdropsMemo = System.currentTimeMillis() to parsed
        return parsed
    }

    @Volatile
    private var heroBackdropsMemo: Pair<Long, Map<Int, String>>? = null

    /**
     * Latest dubbed episode per AniList id, from `data/dub-episodes.json` (built by
     * `scripts/filler_index_build.py dub`). Like [dubIndex] it throws rather than answering empty,
     * because [com.anilili.data.DubIndex] persists whatever this returns.
     */
    suspend fun dubEpisodes(): Map<Int, Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$DATA_BASE/dub-episodes.json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Dub episodes HTTP ${response.code}" }
            val source = checkNotNull(response.body).source()
            require(!source.request(MAX_FILLER_INDEX_BYTES + 1)) { "Dub episodes too large" }
            parseDubEpisodes(source.readUtf8())
        }
    }

    /** A file in the metadata tree, for readers that keep their own copy on disk (the neighbour graph). */
    fun dataUrl(path: String): String = "$DATA_BASE/$path"

    /**
     * Streams id-map.json through [JsonReader] straight into [IdMapTables] (#84).
     *
     * The file is ~4.5 MB, of which only five fields per row are used. The old path read it whole
     * into a String and then a full JsonObject tree — tens of MB of transient heap on a ~192 MB
     * heap to keep a few ints per row — because the tree was the price of one pass over it; a
     * stream makes the same single pass without the tree. The walk is strict and fails fast: an
     * unreadable row throws into [MetadataOverrides.read], so a downloaded copy that does not
     * parse falls back to the bundled file, exactly as the JSON-tree parse inside the read did.
     */
    private fun parseIdMap(stream: InputStream): IdMapTables {
        val tables = IdMapTables()
        JsonReader(stream.bufferedReader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                // Top-level keys are AniList ids; anything else is a row this build cannot key,
                // skipped whole, as the old walk skipped it.
                val anilistId = reader.nextName().toIntOrNull()
                if (anilistId == null) {
                    reader.skipValue()
                    continue
                }
                var tmdbId: Int? = null
                var tmdbType: String? = null
                var tvdbId: Int? = null
                var tvdbSeason: Int? = null
                var malId: Int? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "tmdb" -> tmdbId = reader.intOrNullValue()
                        "tmdb_type" -> tmdbType = reader.stringOrNullValue()
                        "tvdb" -> tvdbId = reader.intOrNullValue()
                        "tvdb_season" -> tvdbSeason = reader.intOrNullValue()
                        "mal" -> malId = reader.intOrNullValue()
                        // kitsu, anidb, simkl, confidence, updated_at, bangumi: not asked of a row.
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                tables.addRow(anilistId, tmdbId, tmdbType, tvdbId, tvdbSeason, malId)
            }
            reader.endObject()
        }
        return tables
    }

    /**
     * The int the next value carries, or null for `null` and for anything that is not an int,
     * number or quoted-number form alike — the answer `JsonPrimitive.intOrNull` gave the old tree
     * walk. A shape this cannot read (an object, an array) throws, by design: see [parseIdMap].
     */
    private fun JsonReader.intOrNullValue(): Int? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> nextString().toIntOrNull()
    }

    /** The string the next value carries, or null for `null`; numbers come back in string form. */
    private fun JsonReader.stringOrNullValue(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        else -> nextString()
    }

    private companion object {
        /**
         * Root of the static metadata tree, served from a GitHub repo through jsDelivr.
         *
         * One constant because the host has already moved once and will again: this pointed at
         * AlokRepo/Konoha, a third-party mirror that stopped syncing — its `sync-status.json` still
         * reads `lastFinishedYear: 2010` and `stats.json` was last written in June 2026. The tree
         * is now generated by `scripts/konoha_build.py` into the same layout, so moving it is this
         * line and nothing else.
         *
         * The MAL mapping used to be fetched from raw.githubusercontent while the other two came
         * from the CDN, which meant one of the three lookups paid an uncached origin request and
         * was the first to fail when GitHub rate-limited. They all go through jsDelivr now.
         *
         * The repo was renamed from `anilili-metadata` to `media-index` on 2026-09-21. It is the
         * same repo, not a second one: GitHub redirects the old name and jsDelivr follows it, so
         * every build older than 0.1.80 — which has the old name compiled into this line and
         * cannot be changed — keeps reading the same tree. Verified against three repos GitHub had
         * already renamed, where the dead name still serves content published years afterwards.
         *
         * Two things follow, and both are easy to get wrong. **Never create `anilili-metadata`
         * under `DATA_OWNER` again**: GitHub drops the redirect the moment that name is taken,
         * and every one of those installs loses episode titles, stills and the MAL mapping at
         * once. And a purge names a URL, not a repo, so the old spelling caches separately and has
         * to be purged alongside this one — see the publish step in `konoha-dataset/refresh.yml`.
         */
        const val DATA_BASE =
            "https://cdn.jsdelivr.net/gh/DATA_OWNER/media-index@main/data"

        /** The bundled cross-reference, streamed on first use; see [idMapLoad] (#84). */
        const val ID_MAP_ASSET = "id-map.json"

        const val TTL_HIT_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        /** Airing shows gain an episode weekly; six hours keeps lists fresh without re-fetching. */
        const val EPISODES_TTL_MS = 6L * 60 * 60 * 1000

        /**
         * Long, because an archived path has not moved in years and a wrong one costs a single
         * failed play before the provider heals it. Not longer, because an airing title is
         * restaged at the year rollover and a whole season would otherwise stay broken until the
         * viewer cleared the cache.
         */
        const val LILI_TTL_MS = 24L * 60 * 60 * 1000

        /**
         * Matched to how often the file behind it changes: the schedule stage runs once a day, so a
         * shorter window would re-download a month to be told the same thing. A month already past
         * never changes at all, and is only re-read because expiring it costs less than tracking
         * which months are settled.
         */
        const val SCHEDULE_TTL_MS = 6L * 60 * 60 * 1000

        /** See [dubIndex]: a sanity bound on a body, not a budget for the list. */
        const val MAX_DUB_INDEX_BYTES = 1_000_000L
        const val MAX_FILLER_INDEX_BYTES = 2_000_000L
        /** The mirror is rebuilt by hand; a day keeps a corrected timing from lingering. */
        const val SKIP_TTL_MS = 24L * 60 * 60 * 1000
        /** Classifications only change as a long-runner airs, and the index is rebuilt by hand. */
        const val FILLER_INDEX_TTL_MS = 24L * 60 * 60 * 1000
        const val FILLER_INDEX_MEMO_MS = 60L * 60 * 1000
        /** Rewritten by the daily refresh; half a day lets a new season's picture arrive the same day. */
        const val HERO_BACKDROPS_TTL_MS = 12L * 60 * 60 * 1000
        const val MAX_HERO_BACKDROPS_BYTES = 2_000_000L
    }
}

/**
 * The three tables the bundled `id-map.json` feeds, built row by row by
 * [KonohaClient.parseIdMap] (#84).
 *
 * Deliberately pure, so the JVM unit tests can hold it: `android.util.JsonReader` is a
 * null-returning stub there and the streaming walk cannot be exercised, but everything that
 * decides what the walk produces lives in [addRow]. A fresh instance answers null to every
 * lookup — the answer callers get while the load is still running — and nothing mutates an
 * instance after [KonohaClient] publishes it, so its reads need no locking of their own.
 */
internal class IdMapTables {
    private val malToAnilist = HashMap<Int, Int>()
    private val tmdbById = HashMap<Int, TmdbRef>()
    private val tvdbById = HashMap<Int, TvdbRef>()

    /** AniList rows the walk consumed, mapped or not; the count the "id-map loaded" log reports. */
    var entries: Int = 0
        private set

    /** The bundled map's guess at the TMDB record behind [anilistId], or null when it has none. */
    fun tmdbRef(anilistId: Int): TmdbRef? = tmdbById[anilistId]

    /** The bundled map's placement of [anilistId] in a TheTVDB series, or null when unmapped. */
    fun tvdbRef(anilistId: Int): TvdbRef? = tvdbById[anilistId]

    /** The AniList id [malId] maps to, or null when the map does not know it. */
    fun anilistForMal(malId: Int): Int? = malToAnilist[malId]

    /**
     * Folds one parsed row in.
     *
     * The writes follow the order the old tree walk made them: the TMDB and TVDB placements are
     * recorded whether or not the row carries a MAL id — only the MAL write is skipped when
     * [malId] is null. Later rows overwrite earlier ones on every key: an AniList id that appears
     * twice keeps its last TMDB/TVDB answer, and two titles sharing a MAL id leave the later one
     * answering the MAL lookup. That is what plain map writes gave and nothing asks for more.
     */
    fun addRow(
        anilistId: Int,
        tmdbId: Int?,
        tmdbType: String?,
        tvdbId: Int?,
        tvdbSeason: Int?,
        malId: Int?,
    ) {
        entries++
        tmdbId?.let { tmdbById[anilistId] = TmdbRef(id = it, type = tmdbType) }
        tvdbId?.let { tvdbById[anilistId] = TvdbRef(id = it, season = tvdbSeason) }
        malId?.let { malToAnilist[it] = anilistId }
    }
}

/**
 * One title's mirrored AniSkip timings: episode number → candidates, each
 * `[type, start, end, episodeLength, skipId]` exactly as `scripts/skip_index_build.py` writes them.
 */
@Serializable
data class KonohaSkipFile(
    val v: Int = 1,
    val mal: Int? = null,
    val checked: Int = 0,
    val episodes: Map<String, List<kotlinx.serialization.json.JsonArray>> = emptyMap(),
)

/** The published filler index; see [KonohaClient.fillerIndex]. */
@Serializable
internal data class FillerIndex(
    val v: Int = 1,
    val titles: Map<String, List<Int>> = emptyMap(),
)

/** The published hero backdrops; see [KonohaClient.heroBackdrops]. */
@Serializable
internal data class HeroBackdropIndex(
    val v: Int = 1,
    val titles: Map<String, String> = emptyMap(),
)

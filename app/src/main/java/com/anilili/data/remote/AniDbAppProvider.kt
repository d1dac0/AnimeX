package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.util.cached
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native AniDB App resolver.
 *
 * Two hosts, because AniDB runs two. `anidb.app` is the website: its search and title pages link
 * each title to AniList and MAL, which is what makes an exact match possible. `play.anidb.app` is
 * the player API its own apps (Anilab, KYOTO Player) stream through, and it is all this needs for
 * playback: episodes, the servers per episode, and a header-free HLS master per server.
 *
 * The split is not academic. On 2026-09-27 the website went into maintenance — every path a 503,
 * the old `/api/frontend` episode and embed routes with it — while `play.anidb.app` kept serving
 * every episode. The player API has no search and no external ids, though; it is keyed by the
 * Anilab post id, which is the website's numeric id plus [POST_ID_BASE] (`death-note-1199` is post
 * 1000001199). So the website still decides the match whenever it answers, and the match is
 * remembered per title (see [cachedPostId]) so an outage later costs nothing for titles already
 * seen. Only when the website cannot be reached at all does the Anilab catalogue stand in, matched
 * on year, format and title — weaker than an id, so it is never remembered.
 */
internal class AniDbAppProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = BASE,
    private val playUrl: String = PLAY_API,
    private val catalogueUrl: String = CATALOGUE_API,
) {
    internal data class Episode(
        val id: Int,
        val number: Int,
        val filler: Boolean,
    )

    /** One of an episode's servers: [path] is `<episodeId>/<jpn|eng>`, as the API names it. */
    internal data class Server(
        val audio: String,
        val path: String,
    )

    internal data class CataloguePost(
        val id: Int,
        val title: String,
        val type: String?,
        val year: Int?,
    )

    /** Post ids the website placed by AniList/MAL id; the only ones worth remembering. */
    private val exactPostIds = ConcurrentHashMap<Int, Int>()

    /** Post ids the catalogue fallback placed by title, for this session only. */
    private val guessedPostIds = ConcurrentHashMap<Int, Int>()

    /**
     * Episode rows per matched post id, and server lists per episode id probed.
     *
     * One row set can reach a couple of thousand entries and one dub inference probes a server
     * list a dozen times over (first, last, and a binary search between them), and before #72
     * every set a session ever caused was held for the life of the process. Both are bounded now,
     * least-recently-used out: [EPISODE_LISTS] titles, and [SERVER_LISTS] episode probes — enough
     * that one title's dub search never evicts itself mid-walk.
     */
    private val episodeCache = BoundedLruMap<Int, List<Episode>>(capacity = EPISODE_LISTS)
    private val serverCache = BoundedLruMap<Int, List<Server>>(capacity = SERVER_LISTS)

    fun cachedPostId(mediaId: Int): String? = exactPostIds[mediaId]?.toString()

    fun seedPostId(mediaId: Int, postId: String) {
        postId.toIntOrNull()?.let { exactPostIds[mediaId] = it }
    }

    fun forgetPostId(mediaId: Int) {
        exactPostIds.remove(mediaId)?.let { postId -> episodeCache.remove(postId) }
    }

    fun episodeAvailability(media: Media, expectedCount: Int): EpisodeAvailability {
        val postId = resolvePostId(media)
        val episodes = episodes(postId)
        if (episodes.isEmpty()) error("AniDB App returned no episode catalog")
        val offset = AniDbAppParser.inferOffset(episodes.map(Episode::number), expectedCount)
        val normalized = episodes.mapNotNull { episode ->
            (episode.number - offset).takeIf { it >= 1 && (expectedCount <= 0 || it <= expectedCount) }
                ?.let { number -> episode to number }
        }
        if (normalized.isEmpty()) error("AniDB App episode numbering did not match this series")

        val firstServers = servers(normalized.first().first)
        val sub = if (firstServers.isEmpty() || AniDbAppParser.serverForAudio(firstServers, "sub") != null) {
            normalized.mapTo(linkedSetOf()) { it.second }
        } else {
            emptySet()
        }

        val dub = when {
            AniDbAppParser.serverForAudio(firstServers, "dub") == null -> emptySet()
            normalized.size == 1 -> setOf(normalized.first().second)
            AniDbAppParser.serverForAudio(servers(normalized.last().first), "dub") != null ->
                normalized.mapTo(linkedSetOf()) { it.second }
            else -> {
                // Dubs normally release as a contiguous prefix. Binary search the actual provider
                // episode rows so long-running shows do not trigger hundreds of server calls.
                var low = 0
                var high = normalized.lastIndex
                var lastDubIndex = 0
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val hasDub = AniDbAppParser.serverForAudio(servers(normalized[mid].first), "dub") != null
                    if (hasDub) {
                        lastDubIndex = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                normalized.take(lastDubIndex + 1).mapTo(linkedSetOf()) { it.second }
            }
        }
        return EpisodeAvailability(sub, dub)
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val postId = resolvePostId(media)
        val episodes = episodes(postId)
        val offset = AniDbAppParser.inferOffset(episodes.map(Episode::number), expectedCount(media))
        val providerEpisode = episodes.firstOrNull { it.number == episode + offset }
            ?: error("AniDB App episode $episode was not found")
        val server = AniDbAppParser.serverForAudio(servers(providerEpisode), audio)
            ?: error("AniDB App episode $episode has no ${audio.uppercase()} source")
        val hls = AniDbAppParser.link(json.parseToJsonElement(playGet("episode/${server.path}/iframe")))
            ?: error("AniDB App episode $episode returned no direct HLS stream")
        val stream = StreamItem(
            url = hls,
            type = "hls",
            quality = if (server.audio == "dub") "AniDB App English" else "AniDB App Japanese",
            audio = audio,
            // The CDN serves the master, variants and segments to a bare request.
            referer = null,
            isActive = true,
            width = null,
            height = null,
        )
        return SourcesResult(listOf(stream), emptyList(), null, null)
    }

    private fun resolvePostId(media: Media): Int {
        exactPostIds[media.id]?.let { return it }
        guessedPostIds[media.id]?.let { return it }
        val exact = runCatching { exactPostId(media) }
        exact.getOrNull()?.let { postId ->
            exactPostIds[media.id] = postId
            return postId
        }
        // The website answered and named no title with this identity: that is authoritative, and
        // a title-only guess from the catalogue would only be a worse answer to the same question.
        if (exact.isSuccess) error("AniDB App match not found for AniList ${media.id}")
        val guessed = runCatching { cataloguePostId(media) }
            .getOrElse { failure ->
                throw IllegalStateException("AniDB App identity lookup failed", exact.exceptionOrNull())
                    .apply { addSuppressed(failure) }
            }
            ?: error("AniDB App match not found for AniList ${media.id}")
        guessedPostIds[media.id] = guessed
        return guessed
    }

    /**
     * The website's exact AniList/MAL placement, or null when it answered and had none. Throws
     * when it could not be asked, which is the one case the catalogue fallback is for.
     */
    private fun exactPostId(media: Media): Int? {
        val candidates = linkedMapOf<String, AniDbAppParser.Candidate>()
        titles(media).forEach { title ->
            AniDbAppParser.searchResults(
                get(
                    "$baseUrl/search/suggestions?q=${encode(title)}",
                    referer = "$baseUrl/home",
                    accept = "text/html, */*",
                    xhr = true,
                ),
            ).forEach { candidate -> candidates.putIfAbsent(candidate.slug, candidate) }
        }

        val ordered = candidates.values.sortedByDescending { candidate ->
            titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.title) } ?: 0.0
        }
        var successfulIdentityPages = 0
        var lastIdentityFailure: Throwable? = null
        ordered.take(MAX_IDENTITY_CANDIDATES).forEach { candidate ->
            val page = runCatching {
                get("$baseUrl/anime/${candidate.slug}", referer = "$baseUrl/home", accept = "text/html, */*")
            }.onFailure { lastIdentityFailure = it }.getOrNull() ?: return@forEach
            successfulIdentityPages++
            val ids = AniDbAppParser.externalIds(page)
            val exactAniList = ids.anilistId == media.id
            val exactMalFallback = ids.anilistId == null && media.idMal != null && ids.malId == media.idMal
            if (exactAniList || exactMalFallback) return POST_ID_BASE + candidate.siteId
        }
        if (successfulIdentityPages == 0) lastIdentityFailure?.let { throw it }
        return null
    }

    /**
     * The Anilab catalogue's best title match, or null when it answered and nothing fit.
     *
     * Its records carry an English title, a type and a premiere season, and nothing that names
     * AniList, so a candidate must premiere within a year and share the format family before its
     * title is compared at all — the year is what separates a series from its own sequels, and the
     * format what separates it from the shorts released alongside (Frieren's mini anime is the same
     * year, an ONA). The only survivor, in the exact year, is taken whatever its title score,
     * because a romaji-only AniList record compares badly against an English one.
     */
    private fun cataloguePostId(media: Media): Int? {
        var unanswered: Throwable? = null
        val ids = titles(media).firstNotNullOfOrNull { title ->
            runCatching {
                AniDbAppParser.catalogueSearch(
                    json.parseToJsonElement(catalogueGet("search?query=${encode(title)}&page=1")),
                )
            }.onFailure { unanswered = it }.getOrNull()?.takeIf(List<Int>::isNotEmpty)
        }
        if (ids == null) {
            unanswered?.let { throw it }
            return null
        }
        val year = media.seasonYear ?: media.startDate?.year
        var answered = 0
        val fitting = ids.take(MAX_CATALOGUE_CANDIDATES).mapNotNull { id ->
            val post = runCatching {
                AniDbAppParser.cataloguePost(json.parseToJsonElement(catalogueGet("post?id=$id")))
            }.onFailure { unanswered = it }.getOrNull() ?: return@mapNotNull null
            answered++
            post.takeIf {
                AniDbAppParser.yearDistance(year, it.year) <= 1 &&
                    AniDbAppParser.sameFormatFamily(media.format, it.type)
            }
        }
        if (answered == 0) unanswered?.let { throw it }
        val scored = fitting.map { post ->
            post to (titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, post.title) } ?: 0.0)
        }
        // A year apart is allowed because the two catalogues date a late-December premiere
        // differently — AniList files Jujutsu Kaisen 0 under 2021, Anilab under "Winter 2022" — but
        // the exact year still ranks first, so a sequel a year on cannot beat the series itself.
        scored.singleOrNull()?.takeIf { (post, _) -> AniDbAppParser.yearDistance(year, post.year) == 0 }
            ?.let { (post, _) -> return post.id }
        return scored
            .filter { (_, score) -> score >= MIN_TITLE_SCORE }
            .sortedWith(
                compareBy<Pair<CataloguePost, Double>> { (post, _) -> AniDbAppParser.yearDistance(year, post.year) }
                    .thenByDescending { (_, score) -> score },
            )
            .firstOrNull()?.first?.id
    }

    private fun episodes(postId: Int): List<Episode> {
        episodeCache[postId]?.let { return it }
        val parsed = AniDbAppParser.episodes(json.parseToJsonElement(playGet("anime/$postId/episodes")))
        if (parsed.isNotEmpty()) episodeCache.put(postId, parsed)
        return parsed
    }

    private fun servers(episode: Episode): List<Server> {
        serverCache[episode.id]?.let { return it }
        val parsed = AniDbAppParser.servers(json.parseToJsonElement(playGet("episode/${episode.id}/servers")))
        // Empty results can be a temporary upstream state, so only retain positive responses.
        if (parsed.isNotEmpty()) serverCache.put(episode.id, parsed)
        return parsed
    }

    // The player API answers without these; they are what AniDB's own player sends, kept so these
    // requests look like the traffic the API exists for.
    private fun playGet(path: String): String = get(
        "$playUrl/$path",
        referer = PLAY_REFERER,
        accept = "application/json, */*",
        requestedWith = PLAY_REQUESTED_WITH,
    )

    private fun catalogueGet(path: String): String =
        get("$catalogueUrl/$path", referer = null, accept = "application/json, */*")

    private fun get(
        url: String,
        referer: String?,
        accept: String,
        xhr: Boolean = false,
        requestedWith: String? = if (xhr) "XMLHttpRequest" else null,
    ): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
        referer?.let { builder.header("Referer", it) }
        requestedWith?.let { builder.header("X-Requested-With", it) }
        val request = builder.get().build()
        client.newCall(request).execute().use { response ->
            // A host that now redirects elsewhere was not asked at all. Since 2026-10-22 every
            // anidb.app path is a 302 to anilab.so, an app landing page: followed, it answered 200
            // with no search results, which read as "the website has no such title" and skipped
            // the catalogue fallback for every title not already matched.
            val asked = request.url.host
            val answered = response.request.url.host
            if (answered != asked) error("AniDB App host $asked now redirects to $answered")
            val body = response.body?.string().orEmpty()
            if (AniDbAppParser.isCloudflareChallenge(body)) {
                error("AniDB App blocked this resolver with a Cloudflare challenge")
            }
            if (!response.isSuccessful) {
                error("AniDB App HTTP ${response.code}")
            }
            return body
        }
    }

    private fun expectedCount(media: Media): Int = when {
        media.status == "RELEASING" && (media.nextAiringEpisode?.episode ?: 0) > 1 ->
            media.nextAiringEpisode!!.episode!! - 1
        (media.episodes ?: 0) > 0 -> media.episodes!!
        media.format == "MOVIE" -> 1
        else -> 0
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.userPreferred,
        media.title.native,
    ).map(String::trim).filter { it.length >= 2 }.distinct()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val BASE = "https://anidb.app"
        private const val PLAY_API = "https://play.anidb.app/api"
        private const val CATALOGUE_API = "https://anilab2.amdapi.click/api"
        private const val PLAY_REFERER = "https://play.app/"
        private const val PLAY_REQUESTED_WITH = "PLAY"

        /** Anilab post id = website id + this; the player API is keyed by the post id. */
        internal const val POST_ID_BASE = 1_000_000_000
        private const val MAX_IDENTITY_CANDIDATES = 8
        private const val MAX_CATALOGUE_CANDIDATES = 6
        private const val MIN_TITLE_SCORE = 0.28

        /**
         * How many titles' episode rows and how many episodes' server lists are kept (#72). Sixteen
         * titles matches `PipeClient`'s episode-list bound, for the same "titles in one session"
         * quantity. Sixty-four episode probes: one dub inference on a long-running title is a
         * binary search — first, last and ~log2(rows) probes between them, thirteen for One Piece —
         * so sixty-four keeps several such searches warm without one evicting itself mid-walk.
         */
        private const val EPISODE_LISTS = 16
        private const val SERVER_LISTS = 64
        private const val USER_AGENT = "Miruro Android/1.0"
    }
}

internal object AniDbAppParser {
    data class Candidate(
        val slug: String,
        val siteId: Int,
        val title: String,
    )

    data class ExternalIds(
        val anilistId: Int?,
        val malId: Int?,
    )

    fun searchResults(html: String): List<Candidate> =
        Regex.cached(
            """<a\b[^>]*\bdata-search-item\b[^>]*>[\s\S]*?</a>""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).mapNotNull { match ->
            val openTag = Regex.cached("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)
                .find(match.value)?.value ?: return@mapNotNull null
            val href = NativeProviderParsers.attr(openTag, "href")
            val slug = Regex.cached("""/anime/([^/?#]+)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val siteId = Regex.cached("""-(\d+)$""").find(slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val title = Regex.cached(
                """<p\b[^>]*class=["'][^"']*\btext-sm\b[^"']*["'][^>]*>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE,
            ).find(match.value)?.groupValues?.get(1)?.let(NativeProviderParsers::stripTags)
                .orEmpty().ifBlank { slug.replace('-', ' ') }
            Candidate(slug, siteId, title)
        }.distinctBy(Candidate::slug).toList()

    fun externalIds(html: String): ExternalIds = ExternalIds(
        anilistId = Regex.cached("""https://anilist\.co/anime/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull(),
        malId = Regex.cached("""https://myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull(),
    )

    /** `{"list":[{"id":"3062","number":"1","filler":false}]}` — ids and numbers come as strings. */
    fun episodes(root: JsonElement): List<AniDbAppProvider.Episode> =
        list(root).mapNotNull { episode ->
            val id = int(episode, "id") ?: return@mapNotNull null
            val number = int(episode, "number") ?: return@mapNotNull null
            if (id <= 0 || number <= 0) return@mapNotNull null
            AniDbAppProvider.Episode(
                id = id,
                number = number,
                filler = (episode["filler"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.distinctBy(AniDbAppProvider.Episode::number).sortedBy(AniDbAppProvider.Episode::number)

    /**
     * `{"list":[{"id":"3062/jpn","lang":"sub"},{"id":"3062/eng","lang":"dub"}]}`. Each audio is
     * listed twice, as "Server #1" and "#2", under the same id; they resolve to the same link.
     */
    fun servers(root: JsonElement): List<AniDbAppProvider.Server> =
        list(root).mapNotNull { server ->
            AniDbAppProvider.Server(
                audio = string(server, "lang")?.lowercase() ?: return@mapNotNull null,
                path = string(server, "id") ?: return@mapNotNull null,
            )
        }.distinctBy(AniDbAppProvider.Server::path)

    fun serverForAudio(servers: List<AniDbAppProvider.Server>, audio: String): AniDbAppProvider.Server? =
        servers.firstOrNull { it.audio == audio }
            ?: servers.firstOrNull { it.path.endsWith(if (audio == "dub") "/eng" else "/jpn") }

    /** `{"link":"https://…/master.m3u8"}`. */
    fun link(root: JsonElement): String? =
        (root as? JsonObject)?.let { string(it, "link") }?.takeIf { it.startsWith("http") }

    /** `{"posts":[{"id":1000001199,…}]}` — ids only; everything else needs [cataloguePost]. */
    fun catalogueSearch(root: JsonElement): List<Int> =
        ((root as? JsonObject)?.get("posts") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { post -> int(post, "id") } }
            .distinct()

    fun cataloguePost(root: JsonElement): AniDbAppProvider.CataloguePost? {
        val post = root as? JsonObject ?: return null
        return AniDbAppProvider.CataloguePost(
            id = int(post, "id") ?: return null,
            title = string(post, "title") ?: return null,
            type = string(post, "type"),
            // "Fall 2006"
            year = string(post, "premiered")?.let { Regex.cached("""\b(\d{4})\b""").find(it) }
                ?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    /** Years between two premieres; 0 when either is unknown, which is no evidence either way. */
    fun yearDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 0 else kotlin.math.abs(a - b)

    /**
     * Whether an AniList format and an Anilab type describe the same kind of release. Unknown on
     * either side passes; TV and TV_SHORT are one family, and so are the short-run formats, which
     * the two catalogues file inconsistently.
     */
    fun sameFormatFamily(anilistFormat: String?, anilabType: String?): Boolean {
        if (anilistFormat.isNullOrBlank() || anilabType.isNullOrBlank()) return true
        fun family(value: String) = when (value.uppercase().replace(' ', '_')) {
            "TV", "TV_SHORT" -> "TV"
            "MOVIE" -> "MOVIE"
            "OVA", "ONA", "SPECIAL" -> "SHORT"
            else -> value.uppercase()
        }
        return family(anilistFormat) == family(anilabType)
    }

    fun inferOffset(numbers: List<Int>, expectedCount: Int): Int {
        val valid = numbers.filter { it > 0 }
        if (valid.isEmpty() || expectedCount <= 0) return 0
        val min = valid.min()
        val max = valid.max()
        return if (min > expectedCount || (min > 1 && max - min + 1 >= expectedCount)) min - 1 else 0
    }

    fun isCloudflareChallenge(body: String): Boolean =
        body.contains("cf_chl", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true)

    private fun list(root: JsonElement): List<JsonObject> =
        ((root as? JsonObject)?.get("list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun string(obj: JsonObject, name: String): String? =
        (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun int(obj: JsonObject, name: String): Int? = (obj[name] as? JsonPrimitive)?.intOrNull
}

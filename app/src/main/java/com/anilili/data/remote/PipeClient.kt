package com.anilili.data.remote

import android.os.SystemClock
import com.anilili.data.ProviderCatalog
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SourcesResult
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Miruro's servers — bee, kiwi, hop and the rest — read through the site's catalog API.
 *
 * Named for the pipe it used to speak, which Miruro retired around 2026-10-03 for the REST catalog
 * in [MiruroCatalogApi]; the shapes and the rules for turning them into a catalogue are in
 * [MiruroCatalog]. The public surface is unchanged, so nothing above this class knew.
 */
class PipeClient(
    private val json: Json,
    /**
     * Reads an iframe embed's page over plain HTTP so its stream can play in the app's own player.
     *
     * Optional so the client still constructs without an HTTP client, and so a test can exercise
     * it without a network.
     */
    private val embedResolver: MiruroEmbedResolver? = null,
) {
    @Volatile private var config: Pair<Long, List<MiruroCatalog.ProviderConfig>>? = null
    private val titles = ConcurrentHashMap<Int, MiruroCatalog.Title>()

    /**
     * Episode rows per Miruro title. Up to [MAX_EPISODES] rows each — twenty thousand of them —
     * and one was kept for every title the app ever looked at, for the life of the process. #72
     * bounds it at [EPISODE_LISTS] titles, least-recently-used out: more than a session touches.
     */
    private val episodeLists = BoundedLruMap<String, List<MiruroCatalog.Episode>>(capacity = EPISODE_LISTS)

    /**
     * Recent `play` responses. One answers every server for an episode, while the resolver asks per
     * server and variant — so without this, walking five servers of one episode was five identical
     * requests. Short-lived because the stream URLs inside are signed, so the TTL is the map's, and
     * #72 bounds it at [PLAY_RESPONSES] responses: each is a full parsed play body, and every one
     * a viewer ever caused used to be held until the process died.
     */
    private val plays = BoundedTtlMap<String, JsonObject>(
        capacity = PLAY_RESPONSES,
        ttlMs = PLAY_TTL_MS,
        nowMs = SystemClock::elapsedRealtime,
    )

    // ---- episodes ----

    suspend fun getEpisodes(anilistId: Int): EpisodesResult {
        val providerConfig = providerConfig()
        val title = title(anilistId) ?: return EpisodesResult(emptyList())
        val episodes = episodeList(title.id)
        if (episodes.isEmpty()) return EpisodesResult(emptyList())
        val sample = runCatching { play(title.id, MiruroCatalog.numberText(episodes.first().number)) }
            .onFailure { DiagnosticsLog.throwable("Miruro catalog sample play failed id=$anilistId", it) }
            .getOrNull()
        val providers = MiruroCatalog.providers(providerConfig, title, episodes, sample)
            .sortedBy { ProviderCatalog.sortKey(it.name) }
        DiagnosticsLog.event(
            "Miruro catalog episodes id=$anilistId episodes=${episodes.size} " +
                "providers=${providers.joinToString(",") { it.name }} sampled=${sample != null}",
        )
        return EpisodesResult(providers)
    }

    // ---- sources ----

    suspend fun getSources(
        pipeId: String,
        provider: String,
        variant: SourceVariant,
        anilistId: Int,
    ): SourcesResult {
        val (titleId, episode) = MiruroCatalog.parseEpisodeId(pipeId)
            ?: throw IOException("Miruro episode id from the retired pipe; refresh the episode list")
        val providerConfig = providerConfig()
        val skip = runCatching {
            episodeList(titleId).firstOrNull { it.number == episode.toDoubleOrNull() }?.skip
        }.getOrNull()
        val parsed = MiruroCatalog.sources(providerConfig, provider, variant, play(titleId, episode), skip)
        if (parsed.streams.isEmpty()) {
            throw IOException("Miruro has no ${variant.label} source on $provider for episode $episode")
        }
        // An iframe provider is only *offered* as an iframe. Where its page turns out to declare a
        // plain stream, playing that natively wins the app's own controls, subtitle delay, PiP,
        // Cast, external players and offline downloads — none of which reach inside a WebView. The
        // embed is kept behind it, so a page this cannot read is exactly as playable as before.
        if (!ProviderCatalog.isEmbed(provider)) return parsed
        return embedResolver?.resolved(parsed) ?: parsed
    }

    // ---- catalog requests ----

    private suspend fun providerConfig(): List<MiruroCatalog.ProviderConfig> {
        config?.takeIf { SystemClock.elapsedRealtime() - it.first < CONFIG_TTL_MS }?.let { return it.second }
        val parsed = MiruroCatalog.parseConfig(json, MiruroCatalogApi.get("config"))
        check(parsed.isNotEmpty()) { "Miruro streaming config listed no providers" }
        config = SystemClock.elapsedRealtime() to parsed
        return parsed
    }

    private suspend fun title(anilistId: Int): MiruroCatalog.Title? {
        titles[anilistId]?.let { return it }
        // `limit=100` is the site's own lookup page size, and the API refuses any other.
        val text = MiruroCatalogApi.get("v1/anime", mapOf("anilist_id_in" to anilistId.toString(), "limit" to "100"))
        return MiruroCatalog.parseTitle(json, text)?.also { titles[anilistId] = it }
    }

    private suspend fun episodeList(titleId: String): List<MiruroCatalog.Episode> {
        episodeLists[titleId]?.let { return it }
        val all = mutableListOf<MiruroCatalog.Episode>()
        var cursor: String? = null
        do {
            // No `kind=regular` any more: since 2026-10-12 the server answers it with
            // `400 Unsupported catalog request`, and the parser drops the specials instead.
            val query = buildMap {
                put("limit", "10000")
                cursor?.let { put("cursor", it) }
            }
            val (page, next) = MiruroCatalog.parseEpisodes(json, MiruroCatalogApi.get("v1/anime/$titleId/episodes", query))
            all += page
            cursor = next
        } while (cursor != null && all.size < MAX_EPISODES)
        if (all.isNotEmpty()) episodeLists.put(titleId, all)
        return all
    }

    private suspend fun play(titleId: String, episode: String): JsonObject {
        val key = "$titleId:$episode"
        // The TTL is the map's own: an entry past it answers null and is dropped right there.
        plays[key]?.let { return it }
        val text = MiruroCatalogApi.get("v1/anime/$titleId/episodes/$episode/play", budgetMs = PIPE_SOURCE_RESPONSE_TIMEOUT_MS)
        val parsed = json.parseToJsonElement(text) as? JsonObject
            ?: throw IOException("Miruro play response was not an object")
        plays.put(key, parsed)
        return parsed
    }

    private companion object {
        const val CONFIG_TTL_MS = 30 * 60_000L
        const val PLAY_TTL_MS = 2 * 60_000L
        const val MAX_EPISODES = 20_000

        /**
         * How many titles' episode lists and how many play responses are kept (#72). One play
         * response answers every server of an episode, and the resolver walks five of them —
         * so eight holds a viewer's recent walks with room to spare; sixteen titles of episode
         * rows is far more than one session browses.
         */
        const val PLAY_RESPONSES = 8
        const val EPISODE_LISTS = 16
    }
}

/**
 * Miruro's web client rewrites legacy AnimePahe download pages through this public compatibility
 * worker. Keeping the same rewrite prevents users from being sent to the retired pahe.win host.
 */
internal fun normalizePipeDownloadUrl(url: String): String =
    if (url.startsWith(PAHE_DOWNLOAD_ORIGIN)) {
        PAHE_DOWNLOAD_WORKER + url.removePrefix(PAHE_DOWNLOAD_ORIGIN)
    } else {
        url
    }

private const val PAHE_DOWNLOAD_ORIGIN = "https://pahe.win/"
private const val PAHE_DOWNLOAD_WORKER = "https://orange-leaf-cefa.asd-968.workers.dev/"

/**
 * Whether an episode row is filler, as a viewer means the word.
 *
 * The flag follows animefillerlist's four kinds and is true for two of them: `filler` and
 * `mixed_canon/filler`. The second is a canon episode with some anime-original padding, and it
 * covers Naruto Shippuden's whole opening arc (1-19) — so every provider badged the story's first
 * nineteen episodes FILLER. Where the row names its kind, only `filler` counts.
 */
internal fun isFillerEpisode(flag: Boolean?, kind: String?): Boolean =
    if (kind.isNullOrBlank()) flag ?: false else kind.trim().equals("filler", ignoreCase = true)

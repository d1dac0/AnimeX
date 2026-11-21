package com.anilili.data.remote

import android.content.Context
import com.anilili.data.model.Media
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.model.hasUsableWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AnimeRift (`com.riftapps.animerift`), the Arabic catalogue behind the Anime Rift Android app.
 *
 * Not a website: it is a private mobile API reached through Firebase, and it is the only Arabic
 * server in this app. Measured 2026-10-06 — current-season titles carry Arabic subtitles at 1080p
 * from episode 1 (11 of 14 sources on three of them), several also carry an Arabic dub, and its
 * episode rows publish intro/outro windows on 1164 of One Piece's 1180 episodes.
 *
 * ```
 * POST firebaseinstallations.googleapis.com/v1/projects/anime-rift-4142e/installations   -> fid
 * POST firebaseremoteconfig.googleapis.com/v1/projects/536921039715/namespaces/firebase:fetch
 *                                                                         -> the gateway base URL
 * POST {gateway}/auth/register/device                                     -> a bound device
 * POST {gateway}/library/search?page=0&sort_by=release_year&…            -> title candidates
 * GET  {gateway}/library/episodes/{id}?sort_by_latest=1&with_arcs=true&with_favorites=true
 * POST {gateway}/library/episode/sources          {animeId, episodeId, episode_number}
 * POST {gateway}/library/episode/source/can_play  {episodeId, hostId, …}     -> sessionId
 * POST {gateway}/library/episode/source/direct_link {id, quality, with_internal_player, sessionId}
 * ```
 *
 * Four things differ from the CloudStream extension this was ported from, each because it was
 * measured against the live gateway rather than read off the decompile:
 *
 * 1. **The gateway URL is resolved at runtime.** The extension hardcodes
 *    `gateway.anime-rift.com`; remote config now answers `gateway.embrassme.top/api/v4`, so the
 *    hardcoded host no longer serves the paths this client calls.
 * 2. **No `integrity` JWT.** The extension signs an HS256 token per endpoint with a 64-hex secret
 *    baked into the build. The gateway does not check it: a garbage token, a token signed for the
 *    wrong scope, and no `integrity` header at all were each answered `200`, including on
 *    `/auth/register/device`. Omitting it keeps a leaked credential out of this app for nothing.
 * 3. **No `/ads_manager/claim`.** The extension calls it with `reward_result:"not_filled"` and
 *    `is_reward:true` — asserting a reward ad was watched when it was not. A device that had never
 *    touched the ads endpoint was answered `claimed:false` by `can_play` and still received a
 *    working stream with the claim skipped, so the call buys nothing.
 * 4. **Hosts are ordered by the priority the service publishes**, not by the list in the client.
 *
 * The `fid` and the registered device are kept in preferences rather than minted per run: Firebase
 * hands out a new installation for every call with an empty `fid`, and every registration leaves a
 * device behind on their side, so re-bootstrapping on each launch would be both wasteful and
 * conspicuous. [rebuild] covers the case where the device is refused anyway.
 */
internal class RiftProvider(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(RiftParser.PREFS, Context.MODE_PRIVATE)

    /**
     * The Rift id behind an AniList id, for this session.
     *
     * A map of its own rather than a read of [prefs]: the caller warms this before a lookup from
     * the persistent catalogue store and harvests it afterwards, exactly as
     * `withRememberedIdentity` does for AniDB App and XFDM. The store's copy is the durable one;
     * this is only the hand-off.
     */
    private val titleIds = ConcurrentHashMap<Int, String>()

    /**
     * Episode rows by Rift id, since the catalogue lookup and the resolve both need them, with when
     * they were read. Kept for [EPISODES_TTL_MS] only: an airing title gains a row a week, and a list
     * held for the life of the process (a TV box's can be days) never learned of it. #72 also bounds
     * it at [EPISODE_ROW_TITLES] titles, so the map cannot grow with every title browsed either;
     * rows serve the title being watched or matched, and the TTL already bounds their age.
     */
    private val episodeRows = BoundedTtlMap<String, List<RiftParser.RiftEpisode>>(
        capacity = EPISODE_ROW_TITLES,
        ttlMs = EPISODES_TTL_MS,
        // The wall clock is what the old read check used; the map only carries it over.
        nowMs = System::currentTimeMillis,
    )

    /** Whether a title carries any Arabic dub at all, probed once from its first episode. */
    private val dubbed = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var session: RiftParser.Session? = null
    private val sessionLock = Any()

    /**
     * The title was found and the episode is listed, but there is nothing to play for this audio:
     * an answer about the request, not about the match, so the remembered Rift id is kept. A title
     * whose first episode carries a dub is offered dubbed throughout (see [episodeAvailability]),
     * and One Piece's dub stops long before episode 1100 — so this is an everyday answer here.
     */
    class NothingForThisRequest(message: String) : IllegalStateException(message)

    fun cachedTitleId(mediaId: Int): String? = titleIds[mediaId]

    fun seedTitleId(mediaId: Int, titleId: String) {
        if (titleId.isNotBlank()) titleIds[mediaId] = titleId
    }

    fun forgetTitleId(mediaId: Int) {
        titleIds.remove(mediaId)?.let { titleId ->
            episodeRows.remove(titleId)
            dubbed.remove(titleId)
        }
    }

    /**
     * What Rift carries for this title.
     *
     * Arabic subtitles are taken as covering every episode the service lists — the same trust
     * [AniChanProvider] places in a published count, and the alternative is a sources request per
     * episode during a catalogue walk that gives seventeen providers fifteen seconds between them.
     * The dub is the one thing worth a request of its own, because it genuinely varies title by
     * title: it is read off the first episode's host list and applied across the run, and a title
     * whose first episode publishes no `ar_dub` is offered as subtitled only.
     */
    fun episodeAvailability(media: Media): EpisodeAvailability {
        val titleId = resolveTitleId(media)
        val episodes = episodes(titleId)
        if (episodes.isEmpty()) error("Rift returned no episode list for this title")
        val numbers = episodes.map { it.number }.toSortedSet()
        // Only an answer is remembered. A probe that failed or was cut off says nothing about the
        // dub, and remembering it as "none" hid the dub row for the rest of the process.
        val hasDub = dubbed[titleId]
            ?: orNull { RiftParser.hosts(hostRows(titleId, episodes.first()), DUB).isNotEmpty() }
                ?.also { dubbed[titleId] = it }
            ?: false
        return EpisodeAvailability(sub = numbers, dub = if (hasDub) numbers else emptySet())
    }

    fun sources(media: Media, audio: String, episode: Int): SourcesResult {
        val titleId = resolveTitleId(media)
        val row = episodeRow(titleId, episode) ?: error("Rift has no episode $episode for this title")

        val wanted = if (audio.equals("dub", ignoreCase = true)) DUB else SUB
        val hosts = RiftParser.hosts(hostRows(titleId, row), wanted)
        if (hosts.isEmpty()) throw NothingForThisRequest("Rift carries no Arabic $audio for episode $episode")

        val streams = ArrayList<StreamItem>()
        val subtitles = LinkedHashMap<String, SubtitleItem>()
        var resolved = 0
        var servedBy: String? = null
        for (host in hosts) {
            if (streams.size >= MAX_STREAMS || resolved >= MAX_RESOLVES) break
            // One server's streams are enough. A server publishes one row per quality (cr2 had three
            // for One Piece 1100), so this waits for the server to change rather than the row. Going
            // on to the next server is what lost a resolve that already held three good cr2 streams:
            // it reached streamtape, whose ticket wait and Cloudflare refusal ran the 8s budget out,
            // and the timed-out resolve took the cr2 streams down with it (2026-10-06, both devices).
            if (servedBy != null && host.provider != servedBy) break
            val sessionId = orNull { canPlay(row.id, host.id) }?.takeIf { it.isNotBlank() } ?: continue
            // A host carries either one file per quality (cr2, streamtape) or one file whose quality
            // is chosen at resolve time (rift-streamer), so the same request shape covers both, and
            // a quality a host does not have fails alone instead of taking the host down with it.
            for (quality in RiftParser.qualityOrder(host.qualities)) {
                if (streams.size >= MAX_STREAMS || resolved >= MAX_RESOLVES) break
                resolved++
                val link = orNull { directLink(host.id, quality, sessionId) } ?: continue
                val url = link.url?.takeIf { it.isNotBlank() } ?: continue
                streams += StreamItem(
                    url = url,
                    type = if (url.contains(".m3u8")) "hls" else "mp4",
                    quality = RiftParser.qualityLabel(quality),
                    audio = audio,
                    // Null on purpose: it is what keeps these off the cast proxy, and neither host
                    // needs one. The MP4 answers 206 with no headers at all, and the HLS playlist
                    // asks for its own two headers through [StreamItem.headers] instead.
                    referer = null,
                    isActive = streams.isEmpty(),
                    width = null,
                    height = RiftParser.qualityHeight(quality),
                    headers = link.headers,
                )
                servedBy = host.provider
                link.tracks.forEach { track -> subtitles[track.url] = track }
            }
        }
        if (streams.isEmpty()) throw NothingForThisRequest("Rift returned no playable stream for episode $episode")

        // The service's own intro/outro windows, in seconds, and `WatchViewModel` prefers a
        // provider window over the AniSkip lookup — so this is the one server here that can offer a
        // skip button without asking anyone else for one.
        val skip = RiftParser.skipTimes(row)?.takeIf(SkipTimes::hasUsableWindow)
        return SourcesResult(
            streams = streams,
            subtitles = subtitles.values.toList(),
            skip = skip,
            download = null,
        )
    }

    // ---- Catalogue lookup ------------------------------------------------------------------

    /**
     * The Rift id for an AniList title.
     *
     * There is no AniList id in this API, so it is a search plus a match. Rift files titles under
     * AniList's own romaji names, which makes the first query the one that lands for almost
     * everything; the English name follows, and the native name is only reached when neither found
     * anything acceptable — one request that is never spent on a title the first two already
     * resolved.
     */
    private fun resolveTitleId(media: Media): String {
        titleIds[media.id]?.takeIf { it.isNotBlank() }?.let { return it }
        val queries = listOfNotNull(media.title.romaji, media.title.english, media.title.native)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (queries.isEmpty()) error("Rift cannot be searched without a title")

        val found = ArrayList<RiftParser.RiftCandidate>()
        for (query in queries.take(MAX_QUERIES)) {
            found += orNull { search(query) }.orEmpty()
            if (RiftParser.best(found, queries, media.seasonYear, media.id) != null) break
        }
        val chosen = RiftParser.best(found.distinctBy { it.id }, queries, media.seasonYear, media.id)
            ?: error("Rift match not found")
        titleIds[media.id] = chosen.id
        return chosen.id
    }

    private fun search(query: String): List<RiftParser.RiftCandidate> {
        // The parameters are the extension's, one for one. `text_direction=jp` is what makes the
        // service match a romaji title against its Japanese-script index; without it the same query
        // came back empty for titles that resolve with it.
        val root = api(
            path = "/library/search?page=0&sort_by=release_year&sort_direction=1&text_direction=jp",
            method = "POST",
            body = buildJsonObject { put("query", query) },
        )
        return RiftParser.searchItems(root)
    }

    private fun episodes(titleId: String): List<RiftParser.RiftEpisode> {
        // The TTL is the map's own: an entry past it answers null and is dropped right there.
        episodeRows[titleId]?.let { return it }
        val root = api(path = "/library/episodes/$titleId?sort_by_latest=1&with_arcs=true&with_favorites=true")
        return RiftParser.episodes(root).also { episodeRows.put(titleId, it) }
    }

    /**
     * The row for [number], reading the list again once when the held copy has no such episode:
     * the episode that aired since the list was read is the one a viewer is most likely to open.
     */
    private fun episodeRow(titleId: String, number: Int): RiftParser.RiftEpisode? =
        episodes(titleId).firstOrNull { it.number == number }
            ?: run {
                episodeRows.remove(titleId)
                episodes(titleId).firstOrNull { it.number == number }
            }

    private fun hostRows(titleId: String, row: RiftParser.RiftEpisode): JsonObject = api(
        path = "/library/episode/sources",
        method = "POST",
        body = buildJsonObject {
            put("animeId", titleId)
            put("episodeId", row.id)
            put("episode_number", row.number)
        },
    )

    private fun canPlay(episodeId: String, hostId: String): String {
        val root = api(
            path = "/library/episode/source/can_play",
            method = "POST",
            body = buildJsonObject {
                put("episodeId", episodeId)
                put("hostId", hostId)
                put("is_download", false)
                put("event_name", PLAY_EVENT)
            },
        )
        return root.str("sessionId").orEmpty()
    }

    /**
     * The resolve itself. See the class comment for why no ad claim is made here.
     *
     * `streamtape` answers a ticket rather than a URL, and the ticket is only good after the wait
     * the service itself asks for — 5s on every host measured. That wait is why [RiftParser.hosts]
     * puts streamtape last whatever the service ranks it. From the app its API is also behind a
     * Cloudflare challenge (403 on a phone and an emulator, 2026-10-06, while a desktop curl got
     * 200), so it is a last resort in every sense.
     */
    private fun directLink(hostId: String, quality: String, sessionId: String): RiftParser.RiftLink {
        val root = api(
            path = "/library/episode/source/direct_link",
            method = "POST",
            body = buildJsonObject {
                put("id", hostId)
                put("quality", quality)
                put("with_internal_player", "1")
                put("sessionId", sessionId)
            },
        )
        return RiftParser.link(root) {
            val fileId = root.str("fileId").orEmpty()
            val ticket = root.str("ticket").orEmpty()
            if (fileId.isEmpty() || ticket.isEmpty()) return@link null
            Thread.sleep(((root.dbl("wait_time") ?: 5.0) * 1000).toLong() + 500)
            val url = "$STREAMTAPE?file=$fileId&ticket=$ticket"
            var answer = getJson(url)
            if (answer?.int("status") != 200) {
                Thread.sleep(RETRY_MS)
                answer = getJson(url)
            }
            answer?.obj("result")?.str("url")
        }
    }

    // ---- Session ---------------------------------------------------------------------------

    private fun session(): RiftParser.Session = synchronized(sessionLock) {
        session?.let { return it }
        val stored = RiftParser.Session(
            fid = prefs.getString(KEY_FID, null).orEmpty(),
            firebaseToken = prefs.getString(KEY_TOKEN, null).orEmpty(),
            gateway = prefs.getString(KEY_GATEWAY, null).orEmpty(),
            deviceId = prefs.getString(KEY_DEVICE, null).orEmpty(),
            buildId = prefs.getString(KEY_BUILD, null).orEmpty(),
        )
        if (stored.usable()) {
            session = stored
            return stored
        }
        bootstrap().also { session = it }
    }

    /** A fresh Firebase installation, the gateway it names, and a device registered against it. */
    private fun bootstrap(): RiftParser.Session {
        val (fid, token) = install()
        val gateway = remoteConfig(fid, token) ?: RiftParser.GATEWAY_FALLBACK
        val (buildId, deviceId) = register(fid, gateway)
        val built = RiftParser.Session(fid, token, gateway, deviceId, buildId)
        prefs.edit()
            .putString(KEY_FID, built.fid)
            .putString(KEY_TOKEN, built.firebaseToken)
            .putString(KEY_GATEWAY, built.gateway)
            .putString(KEY_DEVICE, built.deviceId)
            .putString(KEY_BUILD, built.buildId)
            .apply()
        return built
    }

    /** Drops the held device so the next call bootstraps a new one. */
    private fun rebuild(): RiftParser.Session = synchronized(sessionLock) {
        prefs.edit()
            .remove(KEY_FID).remove(KEY_TOKEN).remove(KEY_GATEWAY)
            .remove(KEY_DEVICE).remove(KEY_BUILD)
            .apply()
        session = null
        return bootstrap().also { session = it }
    }

    /**
     * Re-reads the gateway URL, which is the one part of the session that moves.
     *
     * Remote config is the only authority for it, which is why it is re-read on failure and not
     * only at bootstrap: a URL that has moved is otherwise indistinguishable from an outage, and
     * this one has already moved once. Returns false when the URL did not change, so the caller
     * raises the original failure rather than retrying the same host.
     */
    private fun refreshGateway(): Boolean = synchronized(sessionLock) {
        val held = session ?: return false
        val moved = orNull { remoteConfig(held.fid, held.firebaseToken) }
        if (moved.isNullOrBlank() || moved == held.gateway) return false
        prefs.edit().putString(KEY_GATEWAY, moved).apply()
        session = held.copy(gateway = moved)
        return true
    }

    /** A Firebase installation: its id, and the auth token remote config is read with. */
    private fun install(): Pair<String, String> {
        val body = buildJsonObject {
            put("fid", "")
            put("appId", RiftParser.FIREBASE_APP_ID)
            put("authVersion", "FIS_v2")
            put("sdkVersion", "a:19.1.0")
        }
        // Sent uncompressed, though the extension gzips it. Measured: the endpoint accepts a plain
        // JSON body, so there is no GZIPOutputStream on the bootstrap path for nothing.
        val root = exchange(
            url = RiftParser.INSTALLATIONS,
            method = "POST",
            body = body,
            headers = mapOf(
                "X-Android-Package" to RiftParser.ANDROID_PACKAGE,
                "X-Android-Cert" to RiftParser.ANDROID_CERT,
                "x-goog-api-key" to RiftParser.FIREBASE_API_KEY,
                "x-firebase-client" to RiftParser.FIREBASE_CLIENT,
                "Accept" to "application/json",
                "Cache-Control" to "no-cache",
            ),
            userAgent = FIREBASE_USER_AGENT,
        ) ?: error("Rift: Firebase returned nothing for the installation")
        val fid = root.str("fid").orEmpty()
        val token = root.obj("authToken")?.str("token").orEmpty()
        if (fid.isEmpty() || token.isEmpty()) {
            error("Rift: Firebase returned no installation (${root.str("errorCode") ?: "no error code"})")
        }
        return fid to token
    }

    private fun remoteConfig(fid: String, token: String): String? {
        val body = buildJsonObject {
            put("appVersion", RiftParser.APP_VERSION)
            put("appInstanceIdToken", token)
            put("appBuild", RiftParser.APP_BUILD)
            put("appInstanceId", fid)
            put("analyticsUserProperties", buildJsonObject { })
            put("appId", RiftParser.FIREBASE_APP_ID)
            put("platformVersion", "34")
            put("sdkVersion", "23.0.1")
            put("packageName", RiftParser.ANDROID_PACKAGE)
        }
        val root = exchange(
            url = RiftParser.REMOTE_CONFIG,
            method = "POST",
            body = body,
            headers = mapOf(
                "X-Goog-Api-Key" to RiftParser.FIREBASE_API_KEY,
                "X-Android-Package" to RiftParser.ANDROID_PACKAGE,
                "X-Android-Cert" to RiftParser.ANDROID_CERT,
                "X-Goog-Firebase-Installations-Auth" to token,
                "Accept" to "application/json",
            ),
            userAgent = FIREBASE_USER_AGENT,
        ) ?: return null
        return root.obj("entries")?.str(RiftParser.GATEWAY_ENTRY)?.replace("\"", "")?.trim()
    }

    private fun register(fid: String, gateway: String): Pair<String, String> {
        val (buildId, deviceInfo) = RiftParser.syntheticDevice()
        val deviceId = "$fid:${RiftParser.fcmToken()}"
        val body = buildJsonObject {
            put("deviceId", deviceId)
            put("current_app_version", RiftParser.APP_VERSION)
            put("device_os", "android")
            put("device_environment", "production")
            put("device_info", deviceInfo)
            put("install_source", RiftParser.INSTALL_SOURCE)
            put("deviceOsId", buildId)
            put("firebaseInstallationId", fid)
            put("apn_token", JsonNull)
            put("install_mode", 2)
        }
        val root = exchange(
            url = "$gateway/auth/register/device",
            method = "POST",
            body = body,
            headers = gatewayHeaders(fid, deviceId, buildId),
        ) ?: error("Rift: device registration returned nothing")
        // The service answers a refusal with 200 and an errorCode as readily as with a status code,
        // so the body is the authority here, not the status.
        root.str("errorCode")?.let { error("Rift: device registration refused ($it)") }
        if (root.str("sessionKey").isNullOrBlank()) error("Rift: device registration returned no session")
        return buildId to deviceId
    }

    // ---- Transport -------------------------------------------------------------------------

    private fun api(
        path: String,
        method: String = "GET",
        body: JsonObject? = null,
        attempt: Int = 0,
    ): JsonObject {
        val held = session()
        val url = if (path.startsWith("http")) path else held.gateway + path
        val root = exchange(url, method, body, gatewayHeaders(held.fid, held.deviceId, held.buildId))
        val code = root?.str("errorCode")
        if (root != null && code == null) return root

        // Both repairable failures are "the session is stale": a device the service has retired,
        // which only being refused can reveal, and a gateway URL that has moved, which is otherwise
        // indistinguishable from an outage. Neither is retried a second time.
        if (attempt == 0) {
            val repaired = when {
                // Being rate-limited says nothing about where the gateway is; re-reading remote
                // config for it is one more request into the limit.
                code == RATE_LIMITED -> false
                code == DEVICE_SUSPENDED -> orNull { rebuild() } != null
                else -> orNull { refreshGateway() } ?: false
            }
            if (repaired) return api(path, method, body, attempt + 1)
        }
        error("Rift failed $path (${code ?: "no response"})")
    }

    private fun gatewayHeaders(fid: String, deviceId: String, buildId: String): Map<String, String> = mapOf(
        "x-device-os-id" to buildId.ifBlank { RiftParser.DEFAULT_BUILD_ID },
        "user-agent" to RiftParser.DART_USER_AGENT,
        // Claimed, not reported. Measured against the live gateway: 3.10.0 is answered
        // `403 RELEASE_VERSION_NO_LONGER_SUPPORTED` while 3.13.5 and 99.0.0 are both accepted, and
        // no upper bound was found — so this states a version above any floor they have set rather
        // than the 3.13.5 the extension pins, which is inside the band today and will not be.
        "x-device-release-version" to RiftParser.APP_VERSION,
        "x-firebase-app-check" to "null",
        "authorization" to "Bearer null",
        "content-type" to "application/json; charset=UTF-8",
        "x-installation-source" to RiftParser.INSTALL_SOURCE,
        "accept" to "application/json",
        "x-firebase-id" to fid,
        "x-device-id" to deviceId,
        "x-device-timezone" to RiftParser.deviceTimezone(),
        "x-device-language" to "ar",
        "x-platform" to "Mobile",
        "x-os" to "android",
    )

    /**
     * One exchange, never throwing.
     *
     * A non-JSON body is turned into an `HTTP_<code>` errorCode rather than an empty object, so a
     * caller cannot mistake a gateway's 404 page for a successful empty answer.
     */
    private fun exchange(
        url: String,
        method: String,
        body: JsonObject?,
        headers: Map<String, String>,
        userAgent: String? = null,
    ): JsonObject? = orNull {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        userAgent?.let { builder.header("User-Agent", it) }
        if (method == "GET") {
            builder.get()
        } else {
            builder.method(method, (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            // A refusal with a JSON body that names no errorCode (a 429's does not) is still a
            // refusal: read as a success it was an empty search, which reads as "no match".
            if (parsed != null && (response.isSuccessful || parsed["errorCode"] != null)) parsed
            else JsonObject(mapOf("errorCode" to JsonPrimitive("HTTP_${response.code}")))
        }
    }

    private fun getJson(url: String): JsonObject? = orNull {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            runCatching { json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject }
                .getOrNull()
        }
    }

    /**
     * [block]'s value, or null when it failed — except when the failure is the resolve being
     * cancelled, which is rethrown.
     *
     * Every lookup here runs under `runInterruptible`, so a catalogue budget or a source timeout
     * running out arrives as an interrupt: [InterruptedException] out of a ticket wait, or
     * [InterruptedIOException] out of OkHttp. `runCatching` took those for a failed host and moved
     * on to the next, so an 8s source timeout let one resolve run for 51s (2026-10-06). A socket
     * timeout is also an [InterruptedIOException], but it is a host failing, not a cancellation.
     */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: InterruptedException) {
        throw e
    } catch (e: SocketTimeoutException) {
        null
    } catch (e: InterruptedIOException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEY_FID = "fid"
        const val KEY_TOKEN = "firebaseToken"
        const val KEY_GATEWAY = "gateway"
        const val KEY_DEVICE = "deviceId"
        const val KEY_BUILD = "buildId"
        const val PLAY_EVENT = "play_episode_unlocked"
        const val DEVICE_SUSPENDED = "DEVICE_SUSPENDED"
        const val RATE_LIMITED = "HTTP_429"
        const val SUB = "ar_sub"
        const val DUB = "ar_dub"
        const val STREAMTAPE = "https://api.streamtape.com/file/dl"
        const val RETRY_MS = 2_000L
        const val MAX_QUERIES = 3
        const val EPISODES_TTL_MS = 30 * 60 * 1000L

        /** Titles of episode rows held (#72). Rows only serve the title in play, and the TTL
         * already bounds their age; eight covers a resolver walk plus some browsing. */
        const val EPISODE_ROW_TITLES = 8

        /** Bounds on one resolve: each stream costs a `can_play` and a `direct_link`. */
        const val MAX_STREAMS = 6
        const val MAX_RESOLVES = 8
        const val FIREBASE_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Pro Build/AP2A.240805.015)"
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    }
}

/** Pure payload handling for [RiftProvider], kept network-free for unit tests. */
internal object RiftParser {
    const val PREFS = "rift_session"
    const val INSTALLATIONS =
        "https://firebaseinstallations.googleapis.com/v1/projects/anime-rift-4142e/installations"
    const val REMOTE_CONFIG =
        "https://firebaseremoteconfig.googleapis.com/v1/projects/536921039715/namespaces/firebase:fetch"
    const val GATEWAY_ENTRY = "anime_rift_android_gateway_base_url_v4"
    const val ANDROID_PACKAGE = "com.riftapps.animerift"
    const val ANDROID_CERT = "AF40CE82A52AA4107F311D8B9727D01C8D02250B"
    const val INSTALL_SOURCE = "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER"
    const val FIREBASE_APP_ID = "1:536921039715:android:78825c96b74de921b8e956"
    const val FIREBASE_API_KEY = "AIzaSyBiLkiGEm7ruugny3tDFHEZvqli8yv1k7I"
    const val FIREBASE_CLIENT =
        "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA"
    const val APP_BUILD = "68"
    const val DART_USER_AGENT = "Dart/3.10 (dart:io)"
    const val DEFAULT_BUILD_ID = "AP2A.240805.015"

    /** The version every request claims; see the header site in [RiftProvider]. */
    const val APP_VERSION = "99.0.0"

    /**
     * Where the gateway was last known to be, for the first run only.
     *
     * Never the authority: the live value comes from remote config at every bootstrap and is
     * re-read whenever a call fails, because this URL has already moved once. This constant only
     * covers a bootstrap where remote config itself cannot be reached.
     */
    const val GATEWAY_FALLBACK = "https://gateway.embrassme.top/api/v4"

    internal data class Session(
        val fid: String,
        val firebaseToken: String,
        val gateway: String,
        val deviceId: String,
        val buildId: String,
    ) {
        fun usable(): Boolean = fid.isNotBlank() && gateway.isNotBlank() && deviceId.isNotBlank()
    }

    internal data class RiftWindow(val start: Double, val end: Double)

    internal data class RiftEpisode(
        val id: String,
        val number: Int,
        val intro: RiftWindow?,
        val outro: RiftWindow?,
    )

    internal data class RiftCandidate(
        val id: String,
        val title: String,
        val alternativeTitle: String?,
        val year: Int?,
        val anilistId: Int?,
    ) {
        val names: List<String> get() = listOfNotNull(title, alternativeTitle)
    }

    internal data class RiftHost(
        val id: String,
        val provider: String,
        val server: String,
        /** The raw `sub_title` tag, blank where the service did not state one. */
        val audio: String,
        val qualities: List<String>,
        val rank: Int,
    )

    /** One resolved host: a URL to play, the headers it wants, and any tracks that came with it. */
    internal data class RiftLink(
        val url: String?,
        val headers: Map<String, String>,
        val tracks: List<SubtitleItem>,
    )

    // ---- Search ----------------------------------------------------------------------------

    fun searchItems(root: JsonObject): List<RiftCandidate> =
        root.arr("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RiftCandidate(
                id = id,
                title = item.str("title").orEmpty(),
                alternativeTitle = item.str("alternative_title"),
                year = item.int("release_year"),
                anilistId = anilistId(item),
            )
        }

    /**
     * The AniList id Rift's own cover URL gives away, where it has one.
     *
     * There is no AniList or MAL id field anywhere in this API — but `.item.medium_picture` is
     * still an `anilistcdn` URL for most entries, and AniList names those files after the media id:
     * `.../cover/large/bx21-ELSYx3yMPcKM.jpg` is AniList 21. Measured 7/7 exact on known titles and
     * 87 of 150 items across one home page, which makes it the only exact join available and the
     * strongest signal in [best].
     *
     * `main_picture` is deliberately not read: it is a proxied copy under `covers.embrassme.top`
     * whose path is a hash carrying no id at all. Older AniList filenames prefix the same number
     * with `b`, `nx` or `n`, or carry it bare, so all four shapes are accepted.
     */
    fun anilistId(item: JsonObject): Int? {
        for (field in listOf("medium_picture", "large_picture", "thumbnail_picture")) {
            val url = item.str(field) ?: continue
            ANILIST_ID.find(url)?.let { return it.groupValues[1].toIntOrNull() }
        }
        return null
    }

    /**
     * The candidate to use, or null when nothing is close enough to be worth playing.
     *
     * An exact AniList id wins outright. Otherwise the best title score is taken with the year as a
     * multiplier, and a candidate that positively identifies itself as a *different* AniList entry
     * is discounted heavily rather than dropped — a wrong id in their dataset should not be able to
     * hide a right answer, and it should not be able to beat one either.
     */
    fun best(
        candidates: List<RiftCandidate>,
        titles: List<String>,
        year: Int?,
        anilistId: Int,
    ): RiftCandidate? {
        candidates.firstOrNull { it.anilistId == anilistId }?.let { return it }
        val scored = candidates.map { it to score(titles, year, anilistId, it) }
        val (candidate, value) = scored.maxByOrNull { it.second } ?: return null
        return candidate.takeIf { value >= MIN_MATCH }
    }

    private fun score(titles: List<String>, year: Int?, anilistId: Int, candidate: RiftCandidate): Double {
        val title = titles.maxOfOrNull { query ->
            candidate.names.maxOfOrNull { NativeProviderParsers.titleSelectionScore(query, it) } ?: 0.0
        } ?: 0.0
        var score = title
        candidate.year?.let { candidateYear ->
            if (year != null) score *= if (candidateYear == year) 1.15 else 0.55
        }
        if (candidate.anilistId != null && candidate.anilistId != anilistId) score *= 0.4
        return score.coerceAtMost(1.0)
    }

    // ---- Episodes --------------------------------------------------------------------------

    fun episodes(root: JsonObject): List<RiftEpisode> =
        root.arr("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val number = item.int("episode_number")?.takeIf { it > 0 } ?: return@mapNotNull null
            RiftEpisode(id, number, window(item.obj("intro")), window(item.obj("outro")))
        }.sortedBy { it.number }

    private fun window(node: JsonObject?): RiftWindow? {
        val start = node?.dbl("start") ?: return null
        val end = node?.dbl("end") ?: return null
        return RiftWindow(start, end)
    }

    /**
     * Rift's own intro/outro windows as the player wants them.
     *
     * Present on 1164 of One Piece's 1180 episodes, so the coverage is real rather than a curio. No
     * recap window is published and none is invented: the rows carry `is_recap` but no timings.
     */
    fun skipTimes(episode: RiftEpisode): SkipTimes? {
        val (intro, outro) = episode.intro to episode.outro
        if (intro == null && outro == null) return null
        return SkipTimes(intro?.start, intro?.end, outro?.start, outro?.end)
    }

    // ---- Hosts -----------------------------------------------------------------------------

    /**
     * The hosts worth trying for one audio track, best first.
     *
     * Two things decide the order. The service's own ranking comes first: every sources response
     * carries a `configs` array with a `priority` per `server_key`, and the extension's hardcoded
     * `["cr2", "rift-streamer", "streamtape"]` disagrees with it in the middle — the service ranks
     * streamtape at 3 and rift-streamer at 5. Their published ranking leads because the people who
     * know which servers are healthy maintain it, and it is why a new server needs no edit here.
     * A host whose key is absent from `configs` sorts last. Then the best quality a host offers,
     * which puts the 1080p rows ahead of the 240p ones for the same server.
     *
     * Ahead of both: a host that answers with a ticket ([TICKET_HOSTS]) goes after every other,
     * whatever its published priority. The service ranks streamtape at 3, above rift-streamer at 5,
     * but a streamtape resolve costs a 5s wait and, from the app, a Cloudflare refusal — ranked by
     * the service alone it was reached before rift-streamer and spent the whole source budget.
     *
     * `wanted` is one of Rift's own tags — `ar_sub` or `ar_dub`. Arabic only: this provider exists
     * to be the Arabic server, and the `en_*` and `it_*` rows beside it are the same catalogue under
     * audio the other sixteen servers already carry. An untagged row is taken for the subtitle
     * track, after every tagged one, because the extension reads a blank tag as Arabic and dropping
     * it outright would lose the hosts that carry an untagged Arabic file.
     */
    fun hosts(root: JsonObject, wanted: String): List<RiftHost> {
        val priorities = priorities(root)
        return root.arr("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val audio = item.str("sub_title").orEmpty()
            val wantedRow = when {
                audio.startsWith(wanted) -> true
                wanted == "ar_sub" && audio.isBlank() -> true
                else -> false
            }
            if (!wantedRow) return@mapNotNull null
            val provider = item.str("provider").orEmpty()
            RiftHost(
                id = id,
                provider = provider,
                server = item.str("server_name").orEmpty(),
                audio = audio,
                qualities = item.arr("qualities").orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter { it.isNotBlank() },
                rank = priorities[provider] ?: UNRANKED,
            )
        }.sortedWith(
            compareBy<RiftHost> { if (it.provider in TICKET_HOSTS) 1 else 0 }
                .thenBy { it.rank }
                .thenBy { if (it.audio.isBlank()) 1 else 0 }
                .thenByDescending { host -> host.qualities.maxOfOrNull { qualityHeight(it) ?: 0 } ?: 0 },
        )
    }

    /** The service's own server ranking, by `server_key`. */
    fun priorities(root: JsonObject): Map<String, Int> =
        root.arr("configs").orEmpty().mapNotNull { element ->
            val config = element as? JsonObject ?: return@mapNotNull null
            val key = config.str("server_key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val priority = config.int("priority") ?: return@mapNotNull null
            key to priority
        }.toMap()

    /**
     * The qualities to ask a host for, highest first.
     *
     * A host that publishes none is asked for 720P, which is what the extension does with them. A
     * host that does not carry the one asked for fails that attempt alone: each quality is its own
     * request, so the host's remaining qualities are still tried.
     */
    fun qualityOrder(qualities: List<String>): List<String> =
        if (qualities.isEmpty()) listOf(DEFAULT_QUALITY)
        else qualities.distinct().sortedByDescending(::qualityHeight)

    /**
     * The resolve's answer, in either of the two shapes it arrives in.
     *
     * `url_response` carries a playable URL and `http_headers` naming what the CDN wants on it.
     * For `rift-streamer` that is `x-player` and `x-device-id`, and its playlist is refused
     * without them — measured: 404 and an HTML error page bare, `200 application/vnd.apple.mpegurl`
     * with them, while the segments themselves answered 200 either way. The synthetic `Host` header
     * the extension adds is not reproduced: nothing can set one through OkHttp or Media3, and the
     * MP4 hosts were measured playing with no headers at all, so it was never doing anything.
     *
     * `ticket_response` carries no URL at all; [ticket] turns it into one, or returns null.
     */
    fun link(root: JsonObject, ticket: (JsonObject) -> String?): RiftLink {
        val tracks = root.arr("tracks").orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val url = (track.str("file") ?: track.str("url"))?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = track.str("label") ?: track.str("language") ?: "Arabic"
            SubtitleItem(url, label, language(label))
        }.distinctBy(SubtitleItem::url)

        val headers = root.obj("http_headers")?.let { node ->
            node.keys.mapNotNull { name ->
                node.str(name)?.takeIf { it.isNotBlank() }?.let { name to it }
            }.toMap()
        }.orEmpty()

        val url = when {
            root.bool("url_response") == true -> root.str("videoUrl")
            root.bool("ticket_response") == true -> ticket(root)
            else -> null
        }
        return RiftLink(url = url, headers = headers, tracks = tracks)
    }

    private fun language(label: String): String = when {
        label.startsWith("ar", true) || label.contains("arabic", true) -> "ar"
        label.startsWith("en", true) || label.contains("english", true) -> "en"
        label.startsWith("it", true) || label.contains("italian", true) -> "it"
        label.startsWith("fr", true) || label.contains("french", true) -> "fr"
        else -> "und"
    }

    // ---- Quality ---------------------------------------------------------------------------

    /** `1080p` for a label the service writes as `1080P`, and the raw label when it names none. */
    fun qualityLabel(quality: String): String? =
        qualityHeight(quality)?.let { "${it}p" } ?: quality.takeIf { it.isNotBlank() }

    fun qualityHeight(quality: String): Int? =
        QUALITY.find(quality)?.groupValues?.get(1)?.toIntOrNull()

    // ---- Device ----------------------------------------------------------------------------

    /**
     * A device fingerprint for `device_info`, as a JSON *string* — the field is a serialised object
     * inside the registration body, not an object.
     *
     * None of it is checked against anything; it is the client's own claim about itself, and the
     * four profiles are the ones the extension ships. The build id is what the per-request
     * `x-device-os-id` header later echoes.
     */
    fun syntheticDevice(): Pair<String, String> {
        val (brand, manufacturer, model) = DEVICE_PROFILES.random()
        val buildId = "AP2A.240805.${RANDOM.nextInt(100000, 999999)}.015"
        val tag = UUID.randomUUID().toString().take(8)
        val info = buildJsonObject {
            put("model", model)
            put("brand", brand)
            put("manufacturer", manufacturer)
            put("device", brand)
            put("hardware", "qcom")
            put("product", brand)
            put("androidVersion", "14")
            put("sdkInt", 34)
            put("securityPatch", "2025-01-01")
            put("isPhysicalDevice", true)
            put("supportedAbis", JsonArray(listOf(JsonPrimitive("arm64-v8a"))))
            put("tags", "release-keys")
            put("type", "user")
            put("buildId", buildId)
            put("host", "build-server-$tag")
            put("fingerprint", "$brand/$model/$brand:14/$buildId/V.1e9a_$tag:user/release-keys")
        }
        return buildId to info.toString()
    }

    /** The FCM token half of a device id. Nothing registers it; the service only stores it. */
    fun fcmToken(): String =
        "APA91b" + (1..80).map { TOKEN_ALPHABET.random() }.joinToString("")

    /** The local timestamp the app sends, to the microsecond, with no zone attached. */
    fun deviceTimezone(): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        return "$now.${RANDOM.nextInt(0, 999999).toString().padStart(6, '0')}"
    }

    private val RANDOM = kotlin.random.Random.Default

    private const val UNRANKED = 50

    /** Hosts that answer `direct_link` with a ticket and a wait rather than a URL; see [hosts]. */
    private val TICKET_HOSTS = setOf("streamtape")
    private const val DEFAULT_QUALITY = "720P"

    /**
     * How good a title match has to be before it is played.
     *
     * Measured 2026-10-06 against the live catalogue: of ten well-known titles, seven resolved on
     * the exact AniList join and the other three on title alone, scoring 1.000, 1.000 and 0.550 —
     * so this floor is the one that admitted the tightest correct match and nothing looser. It is
     * deliberately stricter than the 0.28 `AnivexaClient.resolveSlug` accepts, because a wrong slug
     * on a scraper costs a failed lookup while a wrong Rift id plays a different show.
     */
    private const val MIN_MATCH = 0.55
    private const val TOKEN_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private val DEVICE_PROFILES = listOf(
        Triple("google", "Google", "Pixel 8 Pro"),
        Triple("google", "Google", "Pixel 7"),
        Triple("samsung", "samsung", "Galaxy S23"),
        Triple("xiaomi", "Xiaomi", "Xiaomi 13"),
    )

    /**
     * Every AniList cover filename shape seen: `bx21-…`, `b6702-…`, `nx10033-…`, `n106572-…`, and
     * the oldest form, a bare `15323.jpg`. All of them carry the media id in the same place.
     */
    private val ANILIST_ID = Regex(
        "anilistcdn/media/anime/(?:cover|banner)/[^/]+/(?:bx|b|nx|n)?(\\d+)(?:-[^/]*)?\\.(?:jpg|png|webp)",
        RegexOption.IGNORE_CASE,
    )

    private val QUALITY = Regex("(\\d{3,4})")
}

// Declared at file scope rather than inside [RiftParser] because both the parser and the provider
// read payloads, and `private` here means private to this file, which is where both of them live.
private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
private fun JsonObject.dbl(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

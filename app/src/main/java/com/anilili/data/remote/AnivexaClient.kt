package com.anilili.data.remote

import android.os.SystemClock
import android.app.ActivityManager
import android.content.Context
import com.anilili.data.ProviderCatalog
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.Media
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.util.cached
import kotlinx.serialization.builtins.ListSerializer
import com.anilili.diagnostics.DiagnosticsLog
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64

/** MegaPlay paths carry the episode's hash then the encode's: `/{episode}/{encode}/master.m3u8`. */
private val MEGAPLAY_ENCODE_TAG = Regex("""/([0-9a-f]{32})(?=/)""")

/**
 * The source route as MegaPlay's own client script spells it, and any suffixed successor.
 *
 * The literal is anchored to its opening quote but not its closing one: today the script assigns
 * whole routes (`var E="stream/getSources",C="stream/getSourcesNew"`), but a script that built the
 * URL inline as `"stream/getSources?id="+n` would otherwise name a route this never found.
 */
private val MEGAPLAY_SOURCE_ROUTE = Regex("""["'](stream/getSources[\w/-]*)""", RegexOption.IGNORE_CASE)

private val HTML_SCRIPT_SRC = Regex("""<script\b[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** 2Dhive names its BabaStream servers by their embed URL; the host moves, the shape does not. */
private val BABASTREAM_EMBED = Regex("""babastream\.[^/]+/embed/""", RegexOption.IGNORE_CASE)

/**
 * Every `getSources` route [script] knows about, most specific first.
 *
 * MegaPlay does not replace this route when it changes how the answer is encoded, it *adds* one and
 * leaves the old name serving something else. On 2026-09-09 the original `stream/getSources` stopped
 * returning a `sources` object at all — only an encrypted `enc` blob — while `stream/getSourcesNew`,
 * which the same script had been shipping alongside it, still answered in the clear. Every AniKoto
 * play in the app had quietly fallen back to the embed WebView in the meantime, because the one
 * route we knew about was hardcoded here.
 *
 * Eight days later the newer route went the same way, so today both answer `enc` and the reading
 * below is no longer what keeps AniKoto playable on its own — see [MEGAPLAY_ENC_KEY]. It is still
 * worth doing: a route that answers in the clear costs no key, and the next route MegaPlay adds is
 * found here rather than guessed.
 *
 * So the route is read back out of the page's own script rather than written down. Ordering is
 * longest-first because a successor is the old name plus a suffix, which puts the newest route in
 * front; it is only a running order, though. Which route is actually live is decided by asking, in
 * [AnivexaClient.megaPlaySources] — the first one that answers with a playable file wins, so
 * guessing wrong here costs a request rather than the provider.
 */
internal fun megaPlaySourceRoutes(script: String): List<String> = MEGAPLAY_SOURCE_ROUTE.findAll(script)
    .map { it.groupValues[1] }
    .distinct()
    .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
    .toList()

/**
 * The scripts on a MegaPlay embed page that might name its source routes, likeliest first.
 *
 * Only same-origin scripts: the page also pulls jQuery and an HLS build off public CDNs, and those
 * can neither carry MegaPlay's routes nor be worth a request. `newclient` leads because that is the
 * file the routes have lived in, but it is a preference and not a requirement, so a rename costs at
 * most the two extra fetches [AnivexaClient.MEGAPLAY_SCRIPT_LOOKUPS] allows.
 */
internal fun megaPlayClientScripts(pageHtml: String, embedUrl: String): List<String> {
    val origin = runCatching { URI(embedUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull()
    return HTML_SCRIPT_SRC.findAll(pageHtml)
        .map { NativeProviderParsers.absoluteUrl(embedUrl, it.groupValues[1]) }
        .filter { origin == null || it.startsWith("$origin/") }
        .distinct()
        .sortedByDescending { it.contains("newclient", ignoreCase = true) }
        .toList()
}

/**
 * The half of the cipher over MegaPlay's `enc` blob, as its own `newclient.min.js` spells them.
 *
 * On 2026-09-17 the rotation described above went one step further: `stream/getSources` *and*
 * `stream/getSourcesNew` both stopped carrying a `sources` object, answering only
 * `{tracks, t, intro, outro, server, enc}` — byte-identical bodies, verified 5/5 across AniList and
 * MAL id forms, sub and dub. Reading the route out of the script no longer rescues anything when
 * every route says the same thing, so decrypting is the only way left to a manifest.
 *
 * Pinned rather than discovered, unlike [megaPlaySourceRoutes]. A route costs one regex over a
 * script already in hand; recovering these costs a brute force — every string literal ≤32 bytes in
 * the script as a key against every 16-byte literal as an IV, which lands 52 hits out of 1120
 * pairs. That is not a price to pay on a resolve, and it is not a price to pay on a television box
 * at all. [AnivexaClient.megaPlaySourceFile] writes a diagnostics line when the decrypt misses, so
 * the next rotation arrives as a log entry rather than as another silent demotion to the embed.
 */
private const val MEGAPLAY_ENC_KEY = "i?LMTAx0Q6,:}50U"
private const val MEGAPLAY_ENC_IV = "W0;27ToaUpl_P%'c"

/**
 * MegaPlay's `enc` blob as the JSON it hides, or null when it is not this cipher.
 *
 * AES-256-CBC over base64url. The key is the literal above widened to 32 bytes with zeros — the
 * script does that rather than hashing it, which is what makes this AES-256 and not AES-128.
 */
internal fun megaPlayDecryptSources(enc: String): String? = runCatching {
    // An empty payload decrypts to an empty string rather than throwing, which would be a "success"
    // naming no file at all.
    val ciphertext = enc.decodeBase64()?.toByteArray()?.takeIf { it.isNotEmpty() } ?: return null
    val keyBytes = MEGAPLAY_ENC_KEY.toByteArray(Charsets.UTF_8)
    val key = ByteArray(32)
    keyBytes.copyInto(key, endIndex = minOf(key.size, keyBytes.size))
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        IvParameterSpec(MEGAPLAY_ENC_IV.toByteArray(Charsets.UTF_8)),
    )
    String(cipher.doFinal(ciphertext), Charsets.UTF_8)
}.getOrNull()

/**
 * The single-rendition playlist that sits beside a MegaPlay `master.m3u8`, or null for a URL that
 * is not shaped like one.
 *
 * The master was refused on 2026-09-17. Every host probed answered it `403` from the origin —
 * measured from a phone as well as from a desktop, under every referer, origin and browser header
 * set tried — while `index-f1-v1-a1.m3u8` in the same directory serves `200`, names its segments,
 * and carries `#EXT-X-ENDLIST`. So it is the file name that is blocked and not the credential, the
 * network or the directory: the segments that playlist names serve `200` under the same referer
 * this rewrite keeps. Verified across five titles, sub and dub.
 *
 * This is only a fallback candidate now. On 2026-09-22 masters worked again, and Bleach's gained
 * lower qualities while new Liar Game encodes used `index-f1.m3u8` instead. Always preferring this
 * sibling hid working quality choices. [resolveMegaPlayManifest] validates the master first.
 */
internal fun megaPlayRenditionUrl(masterUrl: String): String? {
    val query = masterUrl.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: masterUrl.length
    val path = masterUrl.substring(0, query)
    if (!path.endsWith("/master.m3u8")) return null
    return path.removeSuffix("master.m3u8") + "index-f1-v1-a1.m3u8" + masterUrl.substring(query)
}

/** The shape every host in MegaPlay's CDN pool serves: `/anime/{episode}/{encode}/…`. */
private val MEGAPLAY_POOL_PATH = Regex("""^/anime/[0-9a-f]{32}/[0-9a-f]{32}/""", RegexOption.IGNORE_CASE)

/**
 * [subtitleUrl] moved onto the host [streamUrl] was served from, or [subtitleUrl] unchanged when
 * either is not a MegaPlay pool path.
 *
 * The pool's hostnames are one origin under many names — the same path answers the same bytes on
 * each — but not at the same speed. On 2026-09-30 `getSourcesNew` named `forgotwhichbutton.online`
 * for One Piece 1180's subtitles and `broclickedskip.online` for 1179's: both send headers at once
 * and then hold a 17 KB body for 10-12 s, every time, past Cronet's 8 s read timeout. Media3 retried,
 * disabled the text track and held the first frame for the ~16 s that took. The manifest host
 * served the same file in 0.15 s, and it is the one host this resolve has just proven answers —
 * verified on One Piece, Frieren and a Solo Leveling dub borrowing the sub encode's file.
 */
internal fun megaPlaySubtitleOnStreamHost(subtitleUrl: String, streamUrl: String?): String {
    val subtitle = runCatching { URI(subtitleUrl) }.getOrNull() ?: return subtitleUrl
    val stream = streamUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return subtitleUrl
    if (subtitle.scheme != "https" || stream.scheme != "https") return subtitleUrl
    if (subtitle.rawAuthority.isNullOrBlank() || stream.rawAuthority.isNullOrBlank()) return subtitleUrl
    if (!MEGAPLAY_POOL_PATH.containsMatchIn(subtitle.rawPath.orEmpty())) return subtitleUrl
    if (!MEGAPLAY_POOL_PATH.containsMatchIn(stream.rawPath.orEmpty())) return subtitleUrl
    if (subtitle.rawAuthority == stream.rawAuthority) return subtitleUrl
    return "https://${stream.rawAuthority}" + subtitleUrl.substringAfter(subtitle.rawAuthority)
}

/** One `data-type` group of AniKoto's server list, from its opening tag to the next group's. */
private val ANIKOTO_SERVER_BLOCK = Regex(
    """<div\b[^>]*\bdata-type=["']([^"']+)["'][^>]*>([\s\S]*?)(?=<div\b[^>]*\bdata-type=|$)""",
    RegexOption.IGNORE_CASE,
)

private val ANIKOTO_SERVER_ITEM = Regex(
    """<li\b[^>]*\bdata-link-id=["'][^"']+["'][^>]*>[\s\S]*?</li>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Every anchor on a page, for the walks that have to read attributes before they can filter. */
private val ANY_ANCHOR_TAG = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * AnimeGG's series page, one list item per episode.
 *
 * Held rather than written inline because One Piece's page is ~1000 of these and the walk over it
 * used to build both of the patterns below afresh inside the loop — see [forEachMatch] for what a
 * per-match `Regex` costs on a TV box.
 */
private val ANIMEGG_LIST_ITEM = Regex("""<li\b[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
private val ANIMEGG_STRONG_NUMBER = Regex("""<strong[^>]*>[\s\S]*?(\d+)\s*</strong>""", RegexOption.IGNORE_CASE)
private val ANIMEGG_DETAIL_ANCHOR = Regex(
    """<a\b[^>]*class=["'][^"']*anm_det_pop[^"']*["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)

/** Provider fallbacks may ignore ordinary failures, but never consume coroutine cancellation. */
private inline fun <T> runProviderCatching(block: () -> T): Result<T> = runCatching(block).onFailure {
    if (it is CancellationException) throw it
}

/**
 * The encode's own hash in a MegaPlay URL, or null if the path is not shaped that way. Streams and
 * subtitle files that belong together carry the same one; a subtitle file quoting a different
 * encode than its stream is one cut for another release of the episode.
 */
internal fun megaPlayEncodeTag(url: String?): String? = url
    ?.let { MEGAPLAY_ENCODE_TAG.findAll(it).map { match -> match.groupValues[1] }.toList() }
    ?.getOrNull(1)

/**
 * How far a borrowed subtitle file sits from the stream it was handed to, judged from where each
 * encode's intro starts. Under a second is measurement noise in the provider's own marks; over a
 * minute is not a difference in head footage and is more likely mismatched episode data, so both
 * are treated as "leave the subtitles alone".
 */
internal fun borrowedSubtitleOffsetMs(dubIntroSeconds: Double?, subIntroSeconds: Double?): Long {
    val dubIntro = dubIntroSeconds ?: return 0L
    val subIntro = subIntroSeconds ?: return 0L
    val offsetMs = ((dubIntro - subIntro) * 1000.0).toLong()
    return if (kotlin.math.abs(offsetMs) in 1_000L..60_000L) offsetMs else 0L
}

/**
 * The link id of the AniKoto server to open for [audio], or null when the site lists none.
 *
 * The list is grouped by audio: one `<div class="type" data-type="sub|hsub|dub">` per group, each
 * holding its own `<ul>` of `<li data-link-id=…>`. The `<li>` carries no `data-type` of its own,
 * so reading that attribute off the item matched every item on the page — the filter was a no-op —
 * and the `e54`/Vidstream preference then took the first entry in document order, which belongs to
 * whichever group the site printed first. Measured live 2026-09-05 on Shangri-La Frontier Season 2
 * episode 16: seven items, every one with an empty `data-type`, sub printed first, and a dub
 * request came back with the sub group's link — MegaPlay file 3964 where the dub is 3956. Sub
 * requests were right only by accident.
 *
 * No group for the requested audio now answers null rather than borrowing another one's. The
 * borrowed stream was valid HLS, so nothing downstream could tell it apart, and the viewer got
 * Japanese under a DUB label — the outcome this exists to prevent, not a soft landing. Markup with
 * no typed groups at all is a different claim, the grouping being gone rather than the server, so
 * that still scans the whole document as it did before.
 */
internal fun anikotoServerLink(html: String, audio: String): String? {
    fun items(fragment: String) = ANIKOTO_SERVER_ITEM.findAll(fragment).map { it.value }.toList()
    val groups = ANIKOTO_SERVER_BLOCK.findAll(html)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    val candidates = if (groups.isEmpty()) {
        items(html)
    } else {
        groups.firstOrNull { (type, _) -> type.trim().equals(audio, ignoreCase = true) }
            ?.let { (_, fragment) -> items(fragment) }
            ?: return null
    }
    val preferred = candidates.firstOrNull {
        NativeProviderParsers.attr(it, "data-sv-id").equals("e54", true) ||
            it.contains("Vidstream", ignoreCase = true)
    } ?: candidates.firstOrNull()
    return preferred?.let { NativeProviderParsers.attr(it, "data-link-id").takeIf { link -> link.isNotBlank() } }
}

/**
 * Cache row holding every provider's answer for one title.
 *
 * Keyed by title alone, deliberately. The disk cache holds 800 entries — 300 on a low-RAM device,
 * which is what a TV box is — and evicts the oldest, so a key carrying the provider meant
 * sixteen rows per title and a TV cache filled by twenty titles of browsing, pushing out the
 * metadata that makes the rest of the app quick.
 */
internal fun availabilityCacheKey(anilistId: Int) = "avail:v2:$anilistId"

private fun Int?.orZero(): Int = this ?: 0

/**
 * Bound catalogue fan-out before each provider expands into several HTTP requests.
 *
 * Reads the *large* heap class, because that is the one this app gets:
 * `android:largeHeap="true"` is set, so `ActivityManager.memoryClass` describes a limit that does
 * not apply here and understates the real ceiling roughly twofold. Measured consequence of reading
 * the wrong one: a Samsung SM-A366W — Android 16, arm64, 120Hz, `isLowRamDevice=false`,
 * `largeMemoryClass=512` — reports `memoryClass=256` and so was handed the same two lanes as a TV
 * box. Sixteen providers through two lanes cannot finish inside [CATALOG_TIMEOUT_MS], and five
 * diagnostics bundles from 2026-09-11 show exactly that: `asked=16 answered=8 tookMs≈15000` on
 * every one, on two different devices.
 *
 * [isLowRamDevice] still decides on its own, and has to, because `largeMemoryClass` mis-describes
 * an old weak device in the opposite direction: a 2018 amlogic box on Android 5.1 with 377MB of
 * free RAM also reports 512. The heap class says what the app may allocate, never what the device
 * can afford — the low-RAM flag is the only input here that is about the device.
 */
internal fun providerCatalogParallelism(isLowRamDevice: Boolean, largeMemoryClassMb: Int): Int = when {
    isLowRamDevice || largeMemoryClassMb in 1..256 -> 2
    else -> 4
}

/**
 * Native Android implementation of the additional providers that used to be reached through
 * Anivexa-API. Episode rows are built from AniList immediately; provider matching and stream
 * extraction happen lazily when a user starts an episode.
 */
class AnivexaClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val aniList: AniListClient,
    private val cache: com.anilili.data.cache.AppCache,
    /**
     * Only [XfdmProvider] needs this, and only for the Chinese names AniList does not carry. It is
     * the app's own CDN read with a thirty-day cache behind it, so asking is typically free and
     * never blocks on a third party.
     */
    private val konoha: KonohaClient,
) {
    private data class Candidate(val slug: String, val title: String)

    /** Storage shape for [EpisodeAvailability]; sets are written sorted so the payload is stable. */
    @kotlinx.serialization.Serializable
    private data class CachedAvailability(val sub: List<Int>, val dub: List<Int>)
    private data class MegaPlayEmbed(val url: String, val referer: String, val skip: SkipTimes?)

    /** Discovered [megaPlaySourceRoutes] for one MegaPlay origin, and when to stop trusting them. */
    private data class MegaPlayRouteSet(val routes: List<String>, val expiresAtMs: Long)
    private data class MegaPlayResult(
        val embedUrl: String,
        val origin: String,
        val streams: List<StreamItem>,
        val subtitles: List<SubtitleItem>,
        val skip: SkipTimes?,
        val embedAvailable: Boolean,
    )

    // A provider host must never inherit the app-wide 45 second call budget. Coroutine timeouts
    // cancel the async calls below immediately; this client is also a hard backstop for the few
    // legacy provider parsers that still execute synchronous OkHttp calls.
    private val providerClient = client.newBuilder()
        // Outermost, so the retry it issues after a solved challenge still passes through the
        // response size limit below rather than around it.
        .addInterceptor(CloudflareInterceptor())
        .addInterceptor(ProviderResponseLimitInterceptor(PROVIDER_RESPONSE_MAX_BYTES))
        // Keeps a call made under NonInteractiveChallenge marked when enqueue runs it elsewhere.
        .eventListenerFactory(NonInteractiveChallenge.eventListenerFactory(client.eventListenerFactory))
        .connectTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROVIDER_HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val catalogGate = Semaphore(
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager).let { manager ->
            providerCatalogParallelism(
                isLowRamDevice = manager?.isLowRamDevice == true,
                largeMemoryClassMb = manager?.largeMemoryClass ?: 0,
            )
        },
    )

    /**
     * Where a lookup keeps running after the catalogue has stopped waiting for it.
     *
     * Deliberately outside the caller's scope. The providers block inside `runInterruptible`, and a
     * blocked OkHttp socket read does not answer an interrupt, so a lookup that has been cancelled
     * carries on regardless — the question is only what it takes down with it. As a child of the
     * fan-out's `coroutineScope` it took everything: that scope cannot return while a child runs,
     * so one stuck provider held the whole pass, and `withTimeoutOrNull` could not even report the
     * timeout it had already missed. Here it holds nothing.
     */
    private val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Runs one catalogue lookup under one lane, and gives both back when the caller stops waiting.
     *
     * `catalogGate.withPermit { block() }` releases in a `finally`, which cannot run until `block`
     * returns — so an uncancellable provider held its lane for as long as it ran, not for as long
     * as it was wanted. Measured: AnimeKai held one for 180 seconds against a 15 second budget on a
     * device whose `memoryClass` gives the catalogue two lanes, so half the fan-out's capacity sat
     * behind a lookup nobody was going to read.
     *
     * The lane is therefore released on the timeout rather than on completion, and the orphan drains
     * without one. That trades a hard cap on concurrent lookups for a soft one, which is only
     * sound because an orphan is now bounded from both ends: cancelling the deferred interrupts the
     * worker, which the providers check between requests (see [ProviderDeadline]), and OkHttp caps
     * a host at five concurrent calls regardless.
     *
     * Waiting for a lane still counts against [timeoutMs], as it did before: on a two-lane device
     * that is the only thing bounding a queue of sixteen providers.
     *
     * A lane is also released at [LANE_HOLD_CAP_MS] even when its holder is still working. The
     * timeout above only gives the lane back when the *caller* stops waiting, and by then the
     * damage is done: on a four-lane handset measured 2026-09-22, XFDM and KAA each held a lane
     * for 12–14 of the 15 seconds while the last twelve providers in the queue were cut off
     * waiting for one — hentaihaven ran only in the final second and was cancelled mid-download.
     * A provider still running at the cap is making progress on an answer nobody is waiting for,
     * while the queue behind it is nothing but waiting. The lookup is not cancelled; it drains
     * without a lane and its result is still learned, so the cap costs the early race nothing it
     * would have finished, and gives the tail the lanes it needs to be asked at all.
     */
    private suspend fun <T> withCatalogLane(timeoutMs: Long, block: suspend () -> T): T? {
        val holdsLane = AtomicBoolean(false)
        fun releaseLane() {
            if (holdsLane.compareAndSet(true, false)) catalogGate.release()
        }

        var work: Deferred<T>? = null
        var holdDeadline: Job? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                // No suspension point between these two, so cancellation cannot slip in and strand
                // a permit that nothing knows it is holding.
                catalogGate.acquire()
                holdsLane.set(true)
                // Launched rather than raced against the work: whoever finishes first releases,
                // and `holdsLane` makes the second release a no-op.
                holdDeadline = drainScope.launch {
                    delay(LANE_HOLD_CAP_MS)
                    releaseLane()
                }
                drainScope.async {
                    try {
                        block()
                    } finally {
                        releaseLane()
                    }
                }.also { work = it }.await()
            }
        } finally {
            holdDeadline?.cancel()
            releaseLane()
            work?.cancel()
        }
    }

    private val megaPlayRouteCache = ConcurrentHashMap<String, MegaPlayRouteSet>()

    /**
     * A device that should not be asked to solve a proof of work for a stream.
     *
     * `isLowRamDevice` is the same signal the catalogue fan-out narrows itself on, and it is the
     * one that separates a TV box from a phone here. See [BabaStreamTokenPool] for what declining
     * costs: the embed 2Dhive was already offering, and nothing else.
     */
    private val canSolveProofOfWork =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice != true
    private val babaStream = BabaStreamClient(
        providerClient,
        json,
        BabaStreamTokenPool(providerClient, json, drainScope, solvingAllowed = canSolveProofOfWork),
    )
    private val mediaCache = boundedMap<Int, Media>(100)
    // Slugs used to live in a 250-entry in-memory map that died with the process. They are the
    // expensive half of a catalogue lookup and the stable half, so they now persist and can be
    // seeded from the APK — see CatalogIdentityStore.
    private val identityStore = CatalogIdentityStore(context, cache, json)
    private val allAnime = AllAnimeProvider(
        providerClient,
        json,
        buildStore = AllAnimeBuildStore.preferences(context),
    )
    private val animeKai = AnimeKaiProvider(providerClient)
    // No OkHttp client: AnimePahe is reached through a WebView. See PaheBridge.
    private val pahe = PaheProvider(json)
    private val aniDbApp = AniDbAppProvider(providerClient, json)
    private val kickAssAnime = KickAssAnimeProvider(providerClient, json)
    private val animeDekho = AnimeDekhoProvider(providerClient)
    private val kartoonsTokenPool = KartoonsTokenPool(providerClient, json, drainScope)
    private val kartoons = KartoonsProvider(providerClient, json, kartoonsTokenPool)
    private val animeHeaven = AnimeHeavenProvider(providerClient)
    private val aniChan = AniChanProvider(providerClient, json)
    private val zoko = ZokoProvider(providerClient, json)
    private val aniPm = AniPmProvider(providerClient, json)
    private val animeOnsen = AnimeOnsenProvider(providerClient, json)
    private val animeAv1 = AnimeAv1Provider(providerClient, json)
    private val animekhor = AnimekhorProvider(providerClient)
    private val xfdm = XfdmProvider(providerClient, json)
    private val toonHub = ToonHubProvider(providerClient)
    private val rareAnimes = RareAnimesProvider(providerClient)
    private val uniqueStream = UniqueStreamProvider(providerClient, json)
    private val hanime = HanimeProvider(context, providerClient, json, cache)
    private val hentaiHaven = HentaiHavenProvider(providerClient, cache)
    private val hStream = HStreamProvider(providerClient, json, cache)
    private val onePace = OnePaceProvider(providerClient, json, cache)
    private val rift = RiftProvider(context, providerClient, json)

    /**
     * The Bangumi subject behind [media], or null when the join never placed it.
     *
     * Null is an ordinary answer, not a failure: roughly a third of the catalogue carries no
     * subject, almost all of it music videos and specials — TV is matched at 96.5%. Failures are
     * swallowed the same way because a provider that cannot be searched is a provider that
     * declines, and there is nothing useful to raise at a viewer who asked for a different source.
     */
    private suspend fun bangumiFor(media: Media): KonohaBangumi? =
        runCatching { konoha.details(media.id)?.bangumi }.getOrNull()

    /**
     * Lends XFDM the subject page a previous lookup found, and keeps whatever this one learns.
     *
     * Its catalogue lookup is two requests — a suggest call to turn a Bangumi id into the site's
     * own URL for the title, then that page for the episodes — and on this device the first cost
     * 796ms against the second's 500ms. The URL is derived from a Bangumi id and does not drift,
     * while the episode list changes weekly for anything airing, so only the URL is worth keeping;
     * [CatalogIdentityStore] is built on exactly that split and holds it for sixty days.
     *
     * A remembered URL the site has since moved costs one wasted request, after which
     * [withRememberedIdentity] drops it and retries unseeded — the ordinary search it would have
     * done anyway.
     */
    /**
     * Stops trusting a precomputed XFDM URL that the player refused.
     *
     * The metadata build resolves these ahead of time because the site's paths do not expire — but
     * it stages the current year under `/temp/{YYMM}/` and files each title into its archive at the
     * rollover, so an entry crawled while a show was airing eventually points at nothing. Rather
     * than schedule a re-crawl against a date nobody can predict exactly, the first failed play
     * retires that episode's shipped URL and the provider resolves it live from then on. One
     * wasted request, once, instead of a season that quietly stops working.
     */
    fun forgetShippedStream(provider: String, anilistId: Int, episode: Int) {
        if (provider == "xfdm") xfdm.distrustShipped(anilistId, episode)
    }

    private suspend fun <T> withAniDbPostId(media: Media, block: suspend () -> T): T =
        withRememberedIdentity(
            provider = "anidbapp",
            media = media,
            cached = aniDbApp::cachedPostId,
            seed = aniDbApp::seedPostId,
            forget = aniDbApp::forgetPostId,
            block = block,
        )

    private suspend fun <T> withXfdmSubjectPath(media: Media, block: suspend () -> T): T =
        withRememberedIdentity(
            provider = "xfdm",
            media = media,
            cached = xfdm::cachedSubjectPath,
            seed = xfdm::seedSubjectPath,
            forget = xfdm::forgetSubjectPath,
            block = block,
        )

    /**
     * The Rift id behind a title, remembered across runs.
     *
     * It is worth persisting for the same reason AniDB App's post id is: Rift has no AniList id
     * anywhere in its API, so the id is the product of a search and a match, and the match does not
     * drift once found. A remembered one that the service has since re-ided costs a single rejected
     * lookup before [withRememberedIdentity] drops it and searches again.
     */
    private suspend fun <T> withRiftTitleId(media: Media, block: suspend () -> T): T =
        withRememberedIdentity(
            provider = "rift",
            media = media,
            cached = rift::cachedTitleId,
            seed = rift::seedTitleId,
            forget = rift::forgetTitleId,
            keepsIdentity = { it is RiftProvider.NothingForThisRequest },
            block = block,
        )

    /**
     * Subtitle tracks taken from AniChan and offered against a different provider's video.
     *
     * The one caller is XFDM, whose picture has Chinese subtitles burned into it and which
     * therefore has no track of its own to publish. Rather than leave a viewer to hunt through the
     * OpenSubtitles panel on every episode, the tracks are borrowed from the provider best placed
     * to lend them: AniChan is keyed on the same AniList id, so this needs no title matching and
     * cannot fetch a different show's subtitles; it publishes ten languages, so the existing
     * preferred-language setting still has something to choose from; and its tracks were measured
     * needing no headers, so they load beside a stream from another origin.
     *
     * Borrowing timings across encodes is only defensible because it was measured. Across 22
     * episodes of two shows, AniChan's Crunchyroll-sourced cues against the text burned into
     * XFDM's picture gave 32 confident offsets with a median of +0.25s, all inside ±1.5s, and no
     * drift within an episode — both sides cut from the same broadcast master. That is why
     * [SourcesResult.subtitleOffsetMs] is left at zero here rather than seeded with a guess;
     * [com.anilili.playback.SubtitleDelay] remains the way out of the cases nobody measured.
     *
     * A failure is swallowed deliberately. AniChan not having the episode is not a reason to fail a
     * stream that plays perfectly well with the subtitles the site burned in, and the viewer can
     * still attach a file by hand.
     *
     * **Not called during a resolve.** With XFDM's own streams now precomputed, this pair of
     * requests was the only thing left between pressing play and a picture — 589ms of a 589ms
     * resolve. The view model asks for it once playback is already running and merges the tracks
     * in, which `PlayerSurface` handles as an ordinary subtitle-only re-prepare. The first line of
     * dialogue in the episode this was measured against is at 29.5s, so nothing is missed.
     *
     * The answer is cached because a binge asks for the same thing on every episode and a rewatch
     * asks again tomorrow. The TTL is deliberately shorter than the tracks' own life: AniChan
     * stamps them with a fixed expiry date rather than a rolling one, so a long cache would
     * eventually hand out URLs that had already lapsed.
     */
    suspend fun borrowedSubtitles(media: Media, episode: Int): List<SubtitleItem> = runCatching {
        cache.getOrFetch(
            key = "borrowedsubs:v1:${media.id}:$episode",
            serializer = ListSerializer(SubtitleItem.serializer()),
            ttlMs = BORROWED_SUBTITLE_TTL_MS,
        ) {
            runInterruptible(Dispatchers.IO) { aniChan.sources(media, "sub", episode).subtitles }
        }
    }.getOrDefault(emptyList())

    /** One Pace as a title; it has no AniList record, so this is its only source of truth. */
    suspend fun onePaceMedia(): Media = onePace.media()

    /**
     * One Pace's episodes, as the only server it has. Asked for directly rather than through the
     * provider fan-out: no other server carries the recut, and none of them could find it by name.
     */
    suspend fun onePaceEpisodes(): EpisodesResult = onePace.episodes()

    suspend fun onePaceMetadata(): List<EpisodeMetadata> = onePace.metadata()

    /** The device-side hanime library, also used to seed hentai results into search. */
    suspend fun hanimeCatalogue(): List<HanimeVideo> = hanime.catalogue()

    /** Pull a newer catalogue than the one shipped in the APK. */
    suspend fun refreshHanimeCatalogue() = hanime.refresh()

    suspend fun getEpisodes(
        anilistId: Int,
        seedMedia: Media? = null,
        // Adult-only providers drop out unless the viewer has asked to see adult content, so a
        // title that happens to match one by name never offers it uninvited, and so do the servers
        // they have switched off. Both live in the default rather than in every caller because a
        // caller that forgot either would quietly reintroduce a server the viewer had refused.
        providers: List<String> = ProviderCatalog.enabledProviders(
            ProviderCatalog.anivexaProvidersFor(
                com.anilili.data.settings.SettingsStore.hideAdultContent.value,
            ),
            com.anilili.data.settings.SettingsStore.disabledProviders.value,
        ),
        providerTimeoutMs: Long = CATALOG_TIMEOUT_MS,
        // A viewer who asks for a refresh is nearly always telling us the answer we have is wrong:
        // a source is missing the newest episode, or offers one that will not play. Serving them
        // the same remembered answer makes the button do nothing visible. This is also the only
        // moment a wrong remembered answer can be caught, since every other path short-circuits
        // before the provider is asked.
        refresh: Boolean = com.anilili.data.ProviderBenchmark.forceCatalogRefreshEnabled(),
        /**
         * Called with each provider's row the moment its lookup answers, from whichever thread it
         * answered on, so a screen can show a quick server without waiting for the slowest one in
         * the same pass. The returned result is unchanged and still in catalogue order.
         */
        onProvider: ((ProviderData) -> Unit)? = null,
    ): EpisodesResult = withContext(Dispatchers.IO) {
        require(providerTimeoutMs > 0L) { "providerTimeoutMs must be positive" }
        // The caller (repository) has usually already fetched this AniList Media through the shared
        // 24h cache; reuse it so a cold catalog doesn't fire a second, rate-limit-exposed request.
        seedMedia?.let { mediaCache[anilistId] = it }
        val media = media(anilistId)
        // A country-locked provider is dropped here rather than by the caller, because this is the
        // first point that is guaranteed to hold the AniList Media. Asking AnimeKhor about a
        // Japanese title costs a semaphore lane and a full search round trip to learn nothing.
        @Suppress("NAME_SHADOWING")
        val providers = ProviderCatalog.providersForCountry(providers, media.countryOfOrigin)
        val aired = media.nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
        val count = when {
            media.status == "RELEASING" && aired != null -> aired
            media.episodes != null && media.episodes > 0 -> media.episodes
            media.format == "MOVIE" -> 1
            else -> 1
        }

        // One read for the whole title, before the fan-out, so sixteen providers do not each
        // open a file to ask about the same title.
        val storedRow = rememberedTitleRow(anilistId)
        val storedAvailability = storedRow.providers
        val learnedAvailability = ConcurrentHashMap<String, EpisodeAvailability>()
        val tierCounts = ConcurrentHashMap<String, Int>()
        val servedFromSeed = ConcurrentHashMap<String, EpisodeAvailability>()
        val timedOut = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val catalogueStartedAt = SystemClock.elapsedRealtime()
        // Whoever ran out of budget last time goes to the front of the lane queue.
        //
        // A provider that timed out is not one that failed — a failure throws and is recorded
        // separately — it is one that was still working when the catalogue stopped waiting. On a
        // two-lane device the tail of the list can spend most of its budget queuing: measured on a
        // TV box, ToonHub waited 5.7s of its 15s for a lane and was cut off mid-parse. It then
        // cached nothing, because only a non-null answer is learned, so the next visit started it
        // cold in the same losing position and it never once completed. Promoting it costs the
        // providers ahead of it nothing they will not get back — every lane is released the moment
        // its holder is done — and it only takes one completed pass to fill the cache and stop.
        //
        // Only the queue is reordered. The result below is still assembled in catalogue order, so
        // this cannot move a server around in the viewer's picker.
        val queue = providers.sortedBy { if (it in storedRow.slow) 0 else 1 }
        coroutineScope {
            val lookups = queue.associateWith { provider ->
                // Enter the semaphore in queue order. A normally dispatched async block lets
                // worker scheduling scramble the order before withPermit is reached; on a
                // two-lane device that made early providers such as RareAnimes wait behind the
                // fifteenth scraper and expire without issuing a request.
                async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        // The timeout includes time waiting for a lane. That bounds the whole
                        // catalogue pass even when a constrained device admits only two providers
                        // at once; a series of dead scrapers cannot turn this into a minutes-long
                        // page load.
                        val startedAt = SystemClock.elapsedRealtime()
                        val remembered =
                            if (refresh) null
                            else rememberedAvailability(provider, anilistId, storedAvailability)
                        val availability = remembered?.availability ?: withCatalogLane(providerTimeoutMs) {
                            providerAvailability(provider, media, count)
                        }?.also { learnedAvailability[provider] = it }
                        val tookMs = SystemClock.elapsedRealtime() - startedAt
                        if (remembered != null) {
                            tierCounts.merge(remembered.tier, 1, Int::plus)
                            // A snapshot answer is copied into the device's own cache the first
                            // time it is used, so it then ages like anything else. Left purely in
                            // the asset it would be believed for the life of the install: the seed
                            // has no expiry, so nothing ever refetches to notice it had gone wrong,
                            // and a title a provider has since dropped would be offered forever.
                            if (remembered.tier == "seed") {
                                servedFromSeed[provider] = remembered.availability
                            }
                            DiagnosticsLog.event(
                                category = "catalog",
                                name = "catalog.availability.hit",
                                attributes = mapOf(
                                    "provider" to provider,
                                    "tookMs" to tookMs,
                                    "tier" to remembered.tier,
                                ),
                            )
                        }
                        if (availability == null) {
                            timedOut += provider
                            DiagnosticsLog.event(
                                "Native episode catalog timeout provider=$provider id=$anilistId " +
                                    "afterMs=$providerTimeoutMs",
                            )
                        } else if (remembered == null) {
                            // Only a real fetch is timed here. A cached answer logs
                            // catalog.availability.hit instead — counting those as fetches would
                            // quietly turn a measurement of the network into a measurement of the
                            // cache, and every conclusion drawn from it would be wrong.
                            //
                            // Which servers belong in the early race is decided by *this* number,
                            // not by how fast they resolve a stream once picked — the two are
                            // separate calls, and only resolve time was ever measurable before.
                            // The returned catalogue still awaits every member; a caller that
                            // passes onProvider sees each row as soon as it lands here.
                            DiagnosticsLog.event(
                                category = "catalog",
                                name = "catalog.provider",
                                attributes = mapOf(
                                    "provider" to provider,
                                    "tookMs" to tookMs,
                                    "waitedForLane" to (tookMs >= providerTimeoutMs),
                                ),
                            )
                        }
                        if (availability != null && onProvider != null) {
                            runCatching {
                                onProvider(
                                    ProviderData(
                                        name = provider,
                                        sub = episodeRows(provider, media, "sub", availability.sub),
                                        dub = episodeRows(provider, media, "dub", availability.dub),
                                    ),
                                )
                            }.onFailure { DiagnosticsLog.throwable("Catalog partial delivery failed provider=$provider", it) }
                        }
                        availability
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticsLog.throwable("Native episode catalog failed provider=$provider id=$anilistId", e)
                        null
                    }
                }
            }
            val result = EpisodesResult(
                providers.mapNotNull { provider ->
                    lookups.getValue(provider).await()?.let { availability ->
                        ProviderData(
                            name = provider,
                            sub = episodeRows(provider, media, "sub", availability.sub),
                            dub = episodeRows(provider, media, "dub", availability.dub),
                        )
                    }
                },
                // Carried out so the caller can decide how long to believe this. A provider that
                // was still working when the budget expired is missing from the list above and
                // looks exactly like one that had nothing to offer.
                cutOff = timedOut.sorted(),
            )
            rememberTitleAvailability(
                anilistId,
                media,
                learned = learnedAvailability,
                adopted = servedFromSeed,
                alreadyStored = storedAvailability,
                // Anything that answered this time has earned its place back in list order; only
                // what is still being cut off keeps the head start.
                slow = timedOut.toList(),
            )
            // One row per catalogue build. The per-provider events say what happened; this says
            // whether the caching is working at all, which is the question an uploaded bundle has
            // to answer without anyone reproducing it: a device seeing every provider fetched over
            // and over is a cache that is not being written or is being evicted, and neither shows
            // up in a single provider's timing.
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.summary",
                attributes = mapOf(
                    "anilistId" to anilistId,
                    "status" to (media.status ?: "UNKNOWN"),
                    "asked" to providers.size,
                    "answered" to result.providers.size,
                    // Without this, a refresh looks identical to a cache that is not working: both
                    // fetch everything.
                    "refresh" to refresh,
                    "fromDisk" to tierCounts["disk"].orZero(),
                    "fromSeed" to tierCounts["seed"].orZero(),
                    "fetched" to learnedAvailability.size,
                    "tookMs" to (SystemClock.elapsedRealtime() - catalogueStartedAt),
                ),
            )
            result
        }
    }

    suspend fun getSources(
        episodeId: String,
        seedMedia: Media? = null,
        allowInteractiveChallenges: Boolean = true,
    ): SourcesResult {
        val request = NativeProviderParsers.episodeRequest(episodeId)
            ?: error("Invalid native provider episode id: $episodeId")
        // Before the media lookup, which asks AniList about an id AniList has never heard of.
        if (request.provider == OnePaceProvider.PROVIDER) return onePace.sources(request.audio, request.episode)
        seedMedia?.let { mediaCache[request.anilistId] = it }
        val media = media(request.anilistId)
        return when (request.provider) {
            "animepahe" -> pahe.sources(media, request.audio, request.episode)
            "anidbapp" -> withAniDbPostId(media) {
                runInterruptible(Dispatchers.IO) { aniDbApp.sources(media, request.audio, request.episode) }
            }
            "anikoto" -> anikoto(media, request.audio, request.episode)
            "allanime" -> if (allowInteractiveChallenges) {
                allAnime.sourcesInteractive(media, request.audio, request.episode)
            } else {
                runInterruptible(Dispatchers.IO) { allAnime.sources(media, request.audio, request.episode) }
            }
            "animekai" -> runInterruptible(Dispatchers.IO) { animeKai.sources(media, request.audio, request.episode) }
            "kaa" -> runInterruptible(Dispatchers.IO) { kickAssAnime.sources(media, request.audio, request.episode) }
            "animedekho" -> runInterruptible(Dispatchers.IO) { animeDekho.sources(media, request.audio, request.episode) }
            "kartoons" -> runInterruptible(Dispatchers.IO) { kartoons.sources(media, request.audio, request.episode) }
            "animeheaven" -> runInterruptible(Dispatchers.IO) { animeHeaven.sources(media, request.audio, request.episode) }
            "anichan" -> runInterruptible(Dispatchers.IO) { aniChan.sources(media, request.audio, request.episode) }
            "zoko" -> runInterruptible(Dispatchers.IO) { zoko.sources(media, request.audio, request.episode) }
            "anipm" -> runInterruptible(Dispatchers.IO) { aniPm.sources(media, request.audio, request.episode) }
            "animeonsen" -> runInterruptible(Dispatchers.IO) { animeOnsen.sources(media, request.audio, request.episode) }
            "animeav1" -> runInterruptible(Dispatchers.IO) { animeAv1.sources(media, request.audio, request.episode) }
            "animekhor" -> runInterruptible(Dispatchers.IO) { animekhor.sources(media, request.audio, request.episode) }
            "xfdm" -> {
                val bangumi = bangumiFor(media)
                val shipped = konoha.liliSources(media.id)
                // Subtitles are borrowed from AniChan *after* this returns; see
                // [borrowedSubtitles]. Waiting for them here is two requests to another site on
                // the path to the first frame, for text nothing will draw for half a minute.
                withXfdmSubjectPath(media) {
                    runInterruptible(Dispatchers.IO) {
                        xfdm.sources(media, bangumi, shipped, request.audio, request.episode)
                    }
                }
            }
            "toonhub" -> runInterruptible(Dispatchers.IO) { toonHub.sources(media, request.audio, request.episode) }
            "rareanimes" -> runInterruptible(Dispatchers.IO) { rareAnimes.sources(media, request.audio, request.episode) }
            "uniquestream" -> runInterruptible(Dispatchers.IO) { uniqueStream.sources(media, request.audio, request.episode) }
            "rift" -> withRiftTitleId(media) {
                runInterruptible(Dispatchers.IO) { rift.sources(media, request.audio, request.episode) }
            }
            "reanime" -> reanime(media, request.audio, request.episode)
            "anizone" -> anizone(media, request.episode)
            "animegg" -> animegg(media, request.audio, request.episode)
            "anineko" -> anineko(media, request.audio, request.episode)
            "2dhive" -> twoDhive(media, request.audio, request.episode)
            "hanime" -> hanime.sources(media, request.episode)
            "hentaihaven" -> hentaiHaven.sources(media, request.episode)
            "hstream" -> hStream.sources(media, request.episode)
            else -> error("Unsupported native provider: ${request.provider}")
        }
    }

    private suspend fun media(id: Int): Media = mediaCache[id]
        ?: aniList.animeInfo(id)?.also { mediaCache[id] = it }
        ?: error("Anime $id was not found on AniList")

    private fun episodeRows(provider: String, media: Media, audio: String, numbers: Set<Int>): List<EpisodeItem> {
        // Senshi carried real episode titles and filler flags and was the only Anivexa provider
        // that did. It is gone (2026-09-10) and nothing replaced it, so these rows are numbered
        // only -- the titles the viewer sees come from AniList and TMDB, merged in above this.
        return numbers.sorted().map { number ->
            EpisodeItem(
                pipeId = "watch/$provider/${media.id}/$audio/$provider-$number",
                number = number.toDouble(),
                title = "Episode $number",
                image = null,
                filler = false,
            )
        }
    }

    /**
     * Lends a provider the title match a previous run worked out, and keeps whatever it learns.
     *
     * The providers resolve their matches synchronously, deep inside `runInterruptible`, so they
     * cannot reach a suspending store themselves. They keep their own in-memory map — the source
     * of truth for the session — and this warms it beforehand and harvests it afterwards.
     *
     * A remembered match that the site has since moved would otherwise be worse than none: the
     * provider caches it, fails, and keeps failing for the rest of the session. So a seeded run
     * that throws drops the identity and retries once, unseeded, which is the ordinary search it
     * would have done anyway. A run that was never seeded is left to fail as before.
     *
     * [keepsIdentity] names the failures that prove the match right rather than wrong — the title
     * was found, it just has nothing for this request. Those keep the identity: Rift's "no Arabic
     * dub for this episode" used to drop a correct match and search again, and the search, racing
     * the sub resolve for the same episode, was answered 429 and surfaced as "match not found".
     */
    private suspend fun <T> withRememberedIdentity(
        provider: String,
        media: Media,
        cached: (Int) -> String?,
        seed: (Int, String) -> Unit,
        forget: (Int) -> Unit,
        keepsIdentity: (Exception) -> Boolean = { false },
        block: suspend () -> T,
    ): T {
        val seeded = cached(media.id) == null && identityStore.slug(provider, media.id)
            ?.also { seed(media.id, it) } != null
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (keepsIdentity(e)) {
                cached(media.id)?.let { identityStore.remember(provider, media.id, it) }
                throw e
            }
            if (!seeded) throw e
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.identity.rejected",
                attributes = mapOf("provider" to provider, "anilistId" to media.id),
            )
            forget(media.id)
            block()
        }
        cached(media.id)?.let { identityStore.remember(provider, media.id, it) }
        return result
    }

    /**
     * How long a provider's answer about which episodes it carries stays true.
     *
     * Identity caching was the wrong lever: the search a slug saves is a few hundred milliseconds
     * of a nine-second lookup, because the cost is fetching and parsing the provider's own episode
     * pages. That page fetch cannot be made faster — but for most titles it does not have to
     * happen at all, because the answer cannot change. A finished series has the episode count it
     * will always have; only something still airing gains one, and only weekly.
     *
     * So the volatility that ruled this out is conditional, and [Media.status] already says which
     * case a title is. Anything still moving keeps a short life; anything settled is kept for
     * weeks and served without touching the network.
     */
    private fun availabilityTtlMs(media: Media): Long {
        // Deliberately not shortened by the measurement override. That override exists to force
        // the outer catalogue to refetch; applying it here too would expire the very entries the
        // refetch is supposed to find, and measure nothing.
        return when (media.status) {
            "FINISHED", "CANCELLED" -> 30L * 24 * 60 * 60 * 1000
            "RELEASING" -> 60L * 60 * 1000
            // NOT_YET_RELEASED and anything unfamiliar: short, since the next state change is the
            // one that matters and guessing wrong strands a title with an empty catalogue.
            else -> 6L * 60 * 60 * 1000
        }
    }

    private data class RememberedAvailability(val availability: EpisodeAvailability, val tier: String)

    /**
     * Every provider's answer for one title, in a single cache entry.
     *
     * Deliberately not one entry per provider. The disk cache holds 800 entries, 300 on a low-RAM
     * device — which is what a TV box is — and evicts the oldest. Seventeen rows per title would
     * have filled a TV's entire cache after eighteen titles of browsing and pushed out the AniList
     * metadata that makes the rest of the app quick, to cache something the viewer may never open.
     * One row per title is sixteen times fewer, and one file read instead of sixteen.
     */
    @kotlinx.serialization.Serializable
    private data class CachedTitleAvailability(
        val providers: Map<String, CachedAvailability>,
        /**
         * Providers the budget cut off rather than ones that answered or failed, so the next build
         * can give them a lane first. Defaulted so rows written before this field decode unchanged.
         */
        val slow: List<String> = emptyList(),
    )

    private suspend fun rememberedTitleRow(anilistId: Int): CachedTitleAvailability {
        val row = runCatching {
            cache.getIfFresh(availabilityCacheKey(anilistId), CachedTitleAvailability.serializer())
        }.getOrNull()
        // The one number that separates "never stored" from "stored and unreadable": the cache
        // deletes anything it cannot decode, silently, so a row that was written and comes back
        // absent looks exactly like a row that was never written unless the read says so itself.
        DiagnosticsLog.event(
            category = "catalog",
            name = "catalog.availability.row",
            attributes = mapOf(
                "anilistId" to anilistId,
                "providers" to (row?.providers?.size ?: -1),
                // A device that keeps promoting the same providers build after build is one whose
                // budget they still cannot fit inside, which is a different problem from a cache
                // that is not being written.
                "slow" to (row?.slow?.size ?: -1),
            ),
        )
        return row ?: CachedTitleAvailability(emptyMap())
    }

    private fun rememberedAvailability(
        provider: String,
        anilistId: Int,
        stored: Map<String, CachedAvailability>,
    ): RememberedAvailability? {
        stored[provider]?.let {
            return RememberedAvailability(EpisodeAvailability(it.sub.toSet(), it.dub.toSet()), "disk")
        }
        // Nothing learned on this device yet: the snapshot shipped in the APK covers the first
        // visit, which is the one case the on-disk cache can never help with. Reported separately
        // because "answered without the network" is two different claims — one about this device
        // having been here before, one about the shipped snapshot — and a measurement that cannot
        // tell them apart cannot show the second.
        return identityStore.seededAvailability(provider, anilistId)
            ?.let { (sub, dub) -> RememberedAvailability(EpisodeAvailability(sub, dub), "seed") }
    }

    /** One write per title, after every provider has answered, rather than a write per provider. */
    private suspend fun rememberTitleAvailability(
        anilistId: Int,
        media: Media,
        learned: Map<String, EpisodeAvailability>,
        adopted: Map<String, EpisodeAvailability>,
        alreadyStored: Map<String, CachedAvailability>,
        slow: List<String>,
    ) {
        val fresh = learned.filterValues { it.sub.isNotEmpty() || it.dub.isNotEmpty() }
            .mapValues { (_, a) -> CachedAvailability(a.sub.sorted(), a.dub.sorted()) }
        val fromSeed = adopted.mapValues { (_, a) -> CachedAvailability(a.sub.sorted(), a.dub.sorted()) }
        // A pass where everything was cut off still has something to record: which providers to
        // put first next time. That is the case this whole mechanism exists for, so returning
        // early on it would leave the worst devices exactly as stuck as before.
        if (fresh.isEmpty() && fromSeed.isEmpty() && slow.isEmpty()) return
        // The failure mode this cache introduces: an answer kept for 30 days, or shipped in the
        // APK, that the provider has since changed. It is invisible from the outside — the viewer
        // simply gets a source that does not play — so the one moment it can be caught is when a
        // real fetch finally disagrees with what was being served. Reported per provider so a
        // bundle says which one drifted and by how much, and so a seed that ships a wrong answer
        // can be found without waiting for someone to report a dead episode.
        fresh.forEach { (provider, now) ->
            val before = alreadyStored[provider]
                ?: identityStore.seededAvailability(provider, anilistId)
                    ?.let { (sub, dub) -> CachedAvailability(sub.sorted(), dub.sorted()) }
                ?: return@forEach
            val wasSeed = alreadyStored[provider] == null
            if (before.sub == now.sub && before.dub == now.dub) return@forEach
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.availability.changed",
                attributes = mapOf(
                    "provider" to provider,
                    "anilistId" to anilistId,
                    "status" to (media.status ?: "UNKNOWN"),
                    "from" to (if (wasSeed) "seed" else "disk"),
                    "wasSub" to (before.sub.maxOrNull() ?: 0),
                    "nowSub" to (now.sub.maxOrNull() ?: 0),
                    "wasDub" to (before.dub.maxOrNull() ?: 0),
                    "nowDub" to (now.dub.maxOrNull() ?: 0),
                ),
            )
        }
        fresh.forEach { (provider, entry) ->
            // Recorded so a snapshot can be harvested from a device that has browsed: this is the
            // only place a real, provider-confirmed answer exists, and only settled titles are
            // worth shipping — scripts/catalog_seed.py filters on the status carried here.
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.availability.learned",
                attributes = mapOf(
                    "provider" to provider,
                    "anilistId" to anilistId,
                    "status" to (media.status ?: "UNKNOWN"),
                    "sub" to (entry.sub.maxOrNull() ?: 0),
                    "dub" to (entry.dub.maxOrNull() ?: 0),
                ),
            )
        }
        val row = alreadyStored + fromSeed + fresh
        runCatching {
            cache.putBatch(
                mapOf(
                    availabilityCacheKey(anilistId) to json.encodeToString(
                        CachedTitleAvailability.serializer(),
                        CachedTitleAvailability(row, slow.sorted()),
                    ),
                ),
                availabilityTtlMs(media),
            )
            // Paired with fromDisk in catalog.summary. A device that stores rows and never reads
            // one back is a different bug from one that never stores — the cache decodes on read
            // and deletes anything it cannot parse, silently, so without both numbers the two look
            // identical from a bundle.
            DiagnosticsLog.event(
                category = "catalog",
                name = "catalog.availability.stored",
                attributes = mapOf(
                    "anilistId" to anilistId,
                    "providers" to row.size,
                    "slow" to slow.size,
                    "ttlMs" to availabilityTtlMs(media),
                ),
            )
        }.onFailure { DiagnosticsLog.throwable("Catalogue availability not stored id=$anilistId", it) }
    }


    private suspend fun providerAvailability(provider: String, media: Media, count: Int): EpisodeAvailability = when (provider) {
        "animepahe" -> pahe.episodeAvailability(media)
        "anidbapp" -> withAniDbPostId(media) {
            runInterruptible(Dispatchers.IO) { aniDbApp.episodeAvailability(media, count) }
        }
        "anikoto" -> anikotoAvailability(media, count)
        "allanime" -> runInterruptible(Dispatchers.IO) { allAnime.episodeAvailability(media) }
        "animekai" -> runInterruptible(Dispatchers.IO) { animeKai.episodeAvailability(media) }
        "kaa" -> runInterruptible(Dispatchers.IO) { kickAssAnime.episodeAvailability(media) }
        "animedekho" -> runInterruptible(Dispatchers.IO) { animeDekho.episodeAvailability(media) }
        "kartoons" -> runInterruptible(Dispatchers.IO) { kartoons.episodeAvailability(media) }
        "animeheaven" -> runInterruptible(Dispatchers.IO) { animeHeaven.episodeAvailability(media) }
        "anichan" -> runInterruptible(Dispatchers.IO) { aniChan.episodeAvailability(media) }
        "zoko" -> zokoAvailability(media, count)
        "anipm" -> runInterruptible(Dispatchers.IO) { aniPm.episodeAvailability(media) }
        "animeonsen" -> runInterruptible(Dispatchers.IO) { animeOnsen.episodeAvailability(media) }
        "animeav1" -> runInterruptible(Dispatchers.IO) { animeAv1.episodeAvailability(media) }
        "animekhor" -> runInterruptible(Dispatchers.IO) { animekhor.episodeAvailability(media) }
        "xfdm" -> {
            val shipped = konoha.liliSources(media.id)
            // A precomputed record answers without a lane, a request or a lookup, so the seeding
            // and retry [withXfdmSubjectPath] exists for is only worth paying for without one.
            if (shipped != null) {
                runInterruptible(Dispatchers.IO) { xfdm.episodeAvailability(media, null, shipped) }
            } else {
                val bangumi = bangumiFor(media)
                withXfdmSubjectPath(media) {
                    runInterruptible(Dispatchers.IO) { xfdm.episodeAvailability(media, bangumi, null) }
                }
            }
        }
        "toonhub" -> runInterruptible(Dispatchers.IO) { toonHub.episodeAvailability(media) }
        "rareanimes" -> runInterruptible(Dispatchers.IO) { rareAnimes.episodeAvailability(media) }
        "uniquestream" -> runInterruptible(Dispatchers.IO) { uniqueStream.episodeAvailability(media) }
        "rift" -> withRiftTitleId(media) {
            runInterruptible(Dispatchers.IO) { rift.episodeAvailability(media) }
        }
        "reanime" -> reanimeAvailability(media)
        "anizone" -> anizoneAvailability(media, count)
        "animegg" -> animeGgAvailability(media)
        "anineko" -> aniNekoAvailability(media)
        "2dhive" -> twoDhiveAvailability(media, count)
        "hanime" -> hanime.episodeAvailability(media)
        "hentaihaven" -> hentaiHaven.episodeAvailability(media)
        "hstream" -> hStream.episodeAvailability(media)
        else -> error("Unsupported native provider catalog: $provider")
    }

    private suspend fun anikotoAvailability(media: Media, count: Int): EpisodeAvailability = coroutineScope {
        suspend fun available(audio: String, episode: Int): Boolean {
            val ids = listOfNotNull(media.id, media.idMal).distinct()
            return ids.any { id ->
                val kind = if (id == media.id) "ani" else "mal"
                val url = "https://megaplay.buzz/stream/$kind/$id/$episode/$audio"
                val page = runProviderCatching {
                    getText(url, mapOf("Referer" to "https://hianimes.re/"))
                }.getOrDefault("")
                megaPlayPageAvailable(page)
            }
        }
        // Sub and dub availability are independent binary searches; probe them concurrently.
        val sub = async(Dispatchers.IO) { highestAvailable(count) { available("sub", it) } }
        val dub = async(Dispatchers.IO) { highestAvailable(count) { available("dub", it) } }
        EpisodeAvailability.counts(sub.await(), dub.await())
    }

    /**
     * Zoko publishes no episode list, so the count is found the way anikoto's is: a binary search
     * over the aired count, one ~2KB page per probe. A finished or fully available title costs two
     * probes per track (episode 1 and the last), and only a partial one pays for the search.
     */
    private suspend fun zokoAvailability(media: Media, count: Int): EpisodeAvailability = coroutineScope {
        val malId = zoko.malId(media)
        suspend fun available(audio: String, episode: Int): Boolean =
            runInterruptible(Dispatchers.IO) { zoko.hasEpisode(malId, audio, episode) }
        val sub = async(Dispatchers.IO) { highestAvailable(count) { available("sub", it) } }
        val dub = async(Dispatchers.IO) { highestAvailable(count) { available("dub", it) } }
        val subCount = sub.await()
        val dubCount = dub.await()
        if (subCount == 0 && dubCount == 0) error("Zoko has no entry for this title")
        EpisodeAvailability.counts(subCount, dubCount)
    }

    private suspend fun reanimeAvailability(media: Media): EpisodeAvailability {
        val base = "https://reanime.to"
        val results = titles(media).flatMap { query ->
            val root = runProviderCatching {
                getJson("$base/api/v1/search?q=${enc(query)}&limit=20&offset=0") as? JsonObject
            }.getOrNull()
            (root?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }.distinctBy { it.string("anime_id") }
        val chosen = results.maxByOrNull { item ->
            val title = item["title"] as? JsonObject
            titles(media).maxOfOrNull { expected ->
                maxOf(
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("english").orEmpty()),
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("romaji").orEmpty()),
                    NativeProviderParsers.titleSelectionScore(expected, title?.string("native").orEmpty()),
                )
            } ?: 0.0
        } ?: error("ReAnime match not found")
        val sub = chosen.number("subbed")?.toInt() ?: 0
        val dub = chosen.number("dubbed")?.toInt() ?: 0
        val availability = EpisodeAvailability.counts(sub, dub)
        return availability.copy(
            dub = availability.dub.filterNot { episode -> media.id to episode in REANIME_BAD_DUB_MUXES }.toSet(),
        )
    }

    private suspend fun anizoneAvailability(media: Media, airedCount: Int): EpisodeAvailability {
        val page = getText("https://anizone.to/anime/${anizoneSlug(media)}")
        val pageCount = NativeProviderParsers.labelledEpisodeCount(page)
            ?: error("AniZone returned no episode count")
        return EpisodeAvailability.counts(minOf(pageCount, airedCount), 0)
    }

    private suspend fun animeGgAvailability(media: Media): EpisodeAvailability {
        val page = getText("https://www.animegg.org/series/${animeGgSlug(media)}")
        return NativeProviderParsers.animeGgEpisodes(page).also {
            if (it.sub.isEmpty() && it.dub.isEmpty()) error("AnimeGG returned no episode catalog")
        }
    }

    private suspend fun aniNekoAvailability(media: Media): EpisodeAvailability {
        val page = getText("https://anineko.to/watch/${aniNekoSlug(media)}")
        return NativeProviderParsers.aniNekoEpisodes(page).also {
            if (it.sub.isEmpty() && it.dub.isEmpty()) error("AniNeko returned no episode catalog")
        }
    }

    private suspend fun twoDhiveAvailability(media: Media, fallbackCount: Int): EpisodeAvailability = coroutineScope {
        val malId = media.idMal ?: error("2Dhive needs a MyAnimeList id")
        // Sub and dub are searched concurrently and land on overlapping episodes, so without this
        // the same two pages are fetched twice — once for each audio — and a show with no dub pays
        // the MegaPlay fallback on every dub probe as well. Each page is fetched once per lookup
        // and both searches await the same result.
        val propsByEpisode = ConcurrentHashMap<Int, Deferred<JsonObject?>>()
        suspend fun props(episode: Int): JsonObject? = propsByEpisode.computeIfAbsent(episode) {
            async(Dispatchers.IO) {
                runProviderCatching {
                    extractAstroProps(getText("https://2dhive.com/episode?anime=$malId&ep_num=$episode"))
                }.getOrNull()
            }
        }.await()

        val megaPlayByProbe = ConcurrentHashMap<String, Deferred<Boolean>>()
        suspend fun megaPlayHas(audio: String, episode: Int): Boolean =
            megaPlayByProbe.computeIfAbsent("$audio:$episode") {
                async(Dispatchers.IO) {
                    val page = runProviderCatching {
                        getText(
                            "https://megaplay.buzz/stream/mal/$malId/$episode/$audio",
                            mapOf("Referer" to "https://2dhive.com/"),
                        )
                    }.getOrDefault("")
                    megaPlayPageAvailable(page)
                }
            }.await()

        val first = props(1)
        val total = first?.number("totalEpisodes")?.toInt()?.takeIf { it > 0 } ?: fallbackCount
        suspend fun available(audio: String, episode: Int): Boolean {
            val current = props(episode)
            val has2dHiveServer = (current?.get("servers") as? JsonArray).orEmpty().any { element ->
                val server = element as? JsonObject
                val isDub = (server?.get("dub") as? JsonPrimitive)?.booleanOrNull == true
                isDub == (audio == "dub")
            }
            return has2dHiveServer || megaPlayHas(audio, episode)
        }
        // Sub and dub availability are independent binary searches; probe them concurrently.
        val subDeferred = async(Dispatchers.IO) { highestAvailable(total) { episode -> available("sub", episode) } }
        val dubDeferred = async(Dispatchers.IO) { highestAvailable(total) { episode -> available("dub", episode) } }
        val sub = subDeferred.await()
        val dub = dubDeferred.await()
        if (sub == 0 && dub == 0) error("2Dhive returned no playable episode catalog")
        EpisodeAvailability.counts(sub, dub)
    }

    private suspend fun highestAvailable(limit: Int, available: suspend (Int) -> Boolean): Int {
        if (limit <= 0 || !available(1)) return 0
        if (limit == 1 || available(limit)) return limit
        var low = 1
        var high = limit - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (available(middle)) low = middle else high = middle - 1
        }
        return low
    }

    private fun megaPlayPageAvailable(page: String): Boolean = page.isNotBlank() &&
        !Regex.cached("""<title>\s*Error\b|\berror-container\b""", RegexOption.IGNORE_CASE).containsMatchIn(page) &&
        (page.data("id") != null || Regex.cached("""<iframe\b[^>]*src=["'][^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(page))

    // ---- AniKoto / MegaPlay ---------------------------------------------------------------

    private suspend fun anikoto(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://megaplay.buzz"
        val primary = MegaPlayEmbed("$base/stream/ani/${media.id}/$episode/$audio", "https://hianimes.re/", null)
        val primaryResult = megaPlaySources(primary)
        val result = when {
            primaryResult.streams.any { it.isHls } -> primaryResult
            // MegaPlay hosts the full library but doesn't map every show to an AniList id.
            // Retry by MyAnimeList id (when known) before falling back to scraping the site.
            else -> anikotoMalSources(base, media, audio, episode)
                ?: runProviderCatching { anikotoSiteEmbed(media, audio, episode)?.let { megaPlaySources(it) } }
                    .getOrNull()
                    ?.takeIf { it.streams.any(StreamItem::isHls) }
                ?: primaryResult
        }

        val streams = result.streams.toMutableList()
        if (result.embedAvailable) {
            streams += stream(result.embedUrl, "embed", "MegaPlay embed", MEGAPLAY_EMBED_REFERER, active = streams.isEmpty())
        }
        val offsetMs = if (audio == "dub") megaPlaySubtitleOffsetMs(result, "https://hianimes.re/") else 0L
        return SourcesResult(
            streams.distinctBy { it.url },
            result.subtitles.distinctBy { it.url },
            result.skip,
            null,
            offsetMs,
        )
    }

    /**
     * MegaPlay hands its dub streams the *sub* encode's subtitle file for shows it holds no
     * dub-timed subtitles for — verified 2026-07-20 on How a Realist Hero Rebuilt the Kingdom,
     * whose dub encode's own subtitle folder 404s. The two encodes are not the same cut: the dub
     * carries extra footage at the head, 7-15 s depending on the episode, so every borrowed line
     * lands that much early. The gap between the two payloads' intro marks measures it — across
     * the four episodes checked it matched the difference in stream duration to within a second.
     *
     * Returns 0 for the ordinary case where the subtitles belong to the stream they arrived with,
     * so the extra request only costs the shows that actually borrow.
     */
    private suspend fun megaPlaySubtitleOffsetMs(dub: MegaPlayResult, referer: String): Long {
        val streamTag = megaPlayEncodeTag(dub.streams.firstOrNull(StreamItem::isHls)?.url) ?: return 0L
        val subtitleTag = megaPlayEncodeTag(dub.subtitles.firstOrNull()?.url) ?: return 0L
        if (streamTag == subtitleTag) return 0L
        val subUrl = dub.embedUrl.takeIf { it.endsWith("/dub") }?.dropLast(3)?.plus("sub") ?: return 0L
        val subResult = runProviderCatching { megaPlaySources(MegaPlayEmbed(subUrl, referer, null)) }.getOrNull()
            ?: return 0L
        // Only trust the intro marks once the borrowed file is confirmed to be this encode's own.
        if (megaPlayEncodeTag(subResult.streams.firstOrNull(StreamItem::isHls)?.url) != subtitleTag) return 0L
        val offsetMs = borrowedSubtitleOffsetMs(dub.skip?.introStart, subResult.skip?.introStart)
        DiagnosticsLog.event(
            "MegaPlay borrowed subtitles stream=$streamTag subs=$subtitleTag offsetMs=$offsetMs",
        )
        return offsetMs
    }

    /** MegaPlay by MyAnimeList id — covers shows that aren't mapped to an AniList id. */
    private suspend fun anikotoMalSources(base: String, media: Media, audio: String, episode: Int): MegaPlayResult? {
        val malId = media.idMal ?: return null
        val embed = MegaPlayEmbed("$base/stream/mal/$malId/$episode/$audio", "https://hianimes.re/", null)
        return runProviderCatching { megaPlaySources(embed) }.getOrNull()
            ?.takeIf { it.streams.any(StreamItem::isHls) }
    }

    /**
     * The source routes to try on [origin], reading them off the embed page's own script.
     *
     * Cached per origin because the script is ~26 KB of minified JavaScript and the routes change on
     * MegaPlay's deploys rather than per episode — on a TV box, where a regex over a page costs tens
     * of times what it costs on a phone (see the catalogue parse measurements), paying that on every
     * resolve is the kind of tax that turns into a timeout.
     *
     * Only a successful discovery is cached. A failure falls back to the route we have always known
     * and remembers nothing, so a script that is briefly unreachable costs one pass rather than
     * pinning the provider to a stale route for the life of the entry.
     */
    private suspend fun megaPlayRoutes(origin: String, embedUrl: String, pageHtml: String): List<String> {
        megaPlayRouteCache[origin]?.takeIf { SystemClock.elapsedRealtime() < it.expiresAtMs }?.let {
            return it.routes
        }
        val discovered = megaPlayClientScripts(pageHtml, embedUrl)
            .take(MEGAPLAY_SCRIPT_LOOKUPS)
            .firstNotNullOfOrNull { url ->
                runProviderCatching { getText(url, mapOf("Referer" to embedUrl)) }
                    .getOrNull()
                    ?.let(::megaPlaySourceRoutes)
                    ?.takeIf { it.isNotEmpty() }
            }
            ?: return listOf(MEGAPLAY_FALLBACK_ROUTE)
        val routes = (discovered + MEGAPLAY_FALLBACK_ROUTE).distinct()
        megaPlayRouteCache[origin] = MegaPlayRouteSet(
            routes,
            SystemClock.elapsedRealtime() + MEGAPLAY_ROUTE_TTL_MS,
        )
        return routes
    }

    /**
     * The manifest URL in a `getSources` answer, however that answer is carrying it.
     *
     * `sources` is read first and in both of the shapes MegaPlay's own client accepts — an object
     * and a one-element array — because a route that still answers in the clear is cheaper and
     * needs no key. [megaPlayDecryptSources] is the fallback for the shape every route serves
     * today; a blob that will not decrypt is logged rather than swallowed, because that is the next
     * rotation and the symptom it produces is a provider that quietly plays in a WebView.
     */
    private fun megaPlaySourceFile(answer: JsonObject): String? {
        val sources = answer["sources"]
        val plain = when (sources) {
            is JsonObject -> sources.string("file")
            is JsonArray -> (sources.firstOrNull() as? JsonObject)?.string("file")
            else -> null
        }
        if (!plain.isNullOrBlank()) return plain
        val blob = answer.string("enc")?.takeIf { it.isNotBlank() } ?: return null
        val decrypted = megaPlayDecryptSources(blob)
        if (decrypted == null) {
            DiagnosticsLog.event("MegaPlay enc did not decrypt length=${blob.length}")
            return null
        }
        val root = runProviderCatching { json.parseToJsonElement(decrypted) }.getOrNull() as? JsonObject
        val file = root?.string("file")?.takeIf { it.isNotBlank() }
        if (file == null) DiagnosticsLog.event("MegaPlay enc decrypted but named no file")
        return file
    }

    /**
     * Keep a working master's full ladder; try the legacy sibling only after a master failure.
     * Return no native stream when neither playlist works, leaving the embed available.
     */
    private suspend fun megaPlayPlayableUrl(file: String, origin: String): String? {
        val chosen = resolveMegaPlayManifest(file) { getText(it, mapOf("Referer" to "$origin/")) }
        val kind = when (chosen) { null -> "unavailable"; file -> "master"; else -> "legacy" }
        DiagnosticsLog.event("MegaPlay manifest using=$kind")
        return chosen
    }

    private suspend fun megaPlaySources(embed: MegaPlayEmbed): MegaPlayResult {
        MegaPlayCdnHealth.refreshIfNeeded()
        var embedUrl = embed.url
        var page = runProviderCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault("")
        val iframe = Regex.cached("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(page)?.groupValues?.get(1)
        if (page.data("id").isNullOrBlank() && !iframe.isNullOrBlank()) {
            embedUrl = NativeProviderParsers.absoluteUrl(embedUrl, iframe)
            page = runProviderCatching { getText(embedUrl, mapOf("Referer" to embed.referer)) }.getOrDefault(page)
        }

        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        val fileId = page.data("id")
        val origin = origin(embedUrl)
        var skip = embed.skip
        if (!fileId.isNullOrBlank()) {
            // Ask each route in turn and keep the first answer that carries a playable file. Routes
            // MegaPlay has retired still answer — with tracks, intro and outro but no file of any
            // kind — so "responded" is not the test; having a file is. The first answer of any kind
            // is still worth keeping for its subtitles and skip marks, which the embed fallback
            // below can use even when no route hands over a stream.
            var metadata: JsonObject? = null
            var sourceFile: String? = null
            for (route in megaPlayRoutes(origin, embedUrl, page)) {
                val answer = runProviderCatching {
                    getJson("$origin/$route?id=${enc(fileId)}&id=${enc(fileId)}", mapOf(
                        "Referer" to "$origin/",
                        "X-Requested-With" to "XMLHttpRequest",
                    )) as? JsonObject
                }.getOrNull() ?: continue
                if (metadata == null) metadata = answer
                val file = megaPlaySourceFile(answer)
                if (!file.isNullOrBlank()) {
                    metadata = answer
                    sourceFile = file
                    break
                }
            }
            val source = metadata
            val playableUrl = sourceFile?.takeIf { it.isNotBlank() }?.let { megaPlayPlayableUrl(it, origin) }
            if (playableUrl != null) {
                streams += stream(playableUrl, "hls", "MegaPlay", "$origin/", active = true)
            }
            (source?.get("tracks") as? JsonArray).orEmpty().forEach { track ->
                val obj = track as? JsonObject ?: return@forEach
                val kind = obj.string("kind")?.lowercase()
                if (kind != null && kind != "captions" && kind != "subtitles") return@forEach
                val file = obj.string("file")?.let { megaPlaySubtitleOnStreamHost(it, playableUrl) } ?: return@forEach
                val item = SubtitleItem(file, obj.string("label") ?: "Subtitle", language(obj.string("label")))
                val isDefault = (obj["default"] as? JsonPrimitive)?.booleanOrNull == true
                if (isDefault) subtitles.add(0, item) else subtitles += item
            }
            skip = skipTimes(source) ?: skip
        }
        return MegaPlayResult(
            embedUrl,
            origin,
            streams.distinctBy { it.url },
            subtitles.distinctBy { it.url },
            skip,
            embedAvailable = megaPlayPageAvailable(page),
        )
    }

    private suspend fun anikotoSiteEmbed(media: Media, audio: String, episode: Int): MegaPlayEmbed? {
        val base = "https://anikototv.to"
        val cached = identityStore.slug(ANIKOTO_SITE, media.id)
            ?.let { listOf(Candidate(it, it.replace('-', ' '))) }
            .orEmpty()
        val candidates = (cached + anikotoSiteCandidates(base, media))
            .distinctBy { it.slug }
            .sortedByDescending { candidateScore(media, it) }
            .take(12)
        candidates.forEach { candidate ->
            val embed = runProviderCatching { anikotoSiteEmbedForSlug(base, candidate.slug, media, audio, episode) }
                .getOrNull()
            if (embed != null) {
                identityStore.remember(ANIKOTO_SITE, media.id, candidate.slug)
                return embed
            }
        }
        return null
    }

    private suspend fun anikotoSiteCandidates(base: String, media: Media): List<Candidate> =
        titles(media).flatMap { query ->
            runProviderCatching {
                val search = getJson("$base/ajax/anime/search?keyword=${enc(query)}", xhr("$base/"))
                val html = htmlResult(search)
                Regex.cached(
                    """<a\b([^>]*href=["'][^"']*/watch/[^"']+["'][^>]*)>([\s\S]*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ).findAll(html).mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val slug = Regex.cached("""/watch/([^/?#"']+)""").find(href)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    Candidate(
                        slug,
                        NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { slug.replace('-', ' ') },
                    )
                }.toList()
            }.getOrDefault(emptyList())
        }.distinctBy { it.slug }

    private suspend fun anikotoSiteEmbedForSlug(
        base: String,
        slug: String,
        media: Media,
        audio: String,
        episode: Int,
    ): MegaPlayEmbed? {
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val watch = getText(watchUrl, mapOf("Referer" to "$base/"))
        val animeId = Regex.cached("""data-anime-id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
            .find(watch)?.groupValues?.get(1)
            ?: return null
        val episodes = getJson("$base/ajax/episode/list/$animeId", xhr(watchUrl)) as? JsonObject ?: return null
        val episodeTag = anikotoEpisodeTag(htmlResult(episodes), media, audio, episode) ?: return null
        val serverToken = NativeProviderParsers.attr(episodeTag, "data-ids").takeIf { it.isNotBlank() } ?: return null
        val servers = getJson("$base/ajax/server/list?servers=${enc(serverToken)}", xhr(watchUrl)) as? JsonObject
            ?: return null
        val linkId = anikotoServerLink(htmlResult(servers), audio) ?: return null
        val resolved = getJson("$base/ajax/server?get=${enc(linkId)}", xhr(watchUrl)) as? JsonObject ?: return null
        val result = (resolved["result"] as? JsonObject) ?: resolved
        val url = result.string("url") ?: return null
        return MegaPlayEmbed(url, "$base/", anikotoSkipData(result["skip_data"] as? JsonObject))
    }

    private fun anikotoEpisodeTag(html: String, media: Media, audio: String, episode: Int): String? {
        val expectedMal = media.idMal?.toString()
        val audioAttr = if (audio == "dub") "data-dub" else "data-sub"
        val tags = ANY_ANCHOR_TAG.mapMatches(html) { match ->
            val tag = match.group()
            val num = NativeProviderParsers.attr(tag, "data-num").toIntOrNull()
            val slug = NativeProviderParsers.attr(tag, "data-slug").toIntOrNull()
            val wanted = (num == episode || slug == episode) &&
                NativeProviderParsers.attr(tag, audioAttr).let { it.isBlank() || it == "1" }
            tag.takeIf { wanted }
        }
        if (expectedMal != null) {
            return tags.firstOrNull { tag -> NativeProviderParsers.attr(tag, "data-mal") == expectedMal }
        }
        return tags.firstOrNull()
    }

    private fun anikotoSkipData(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val values = root?.get(name) as? JsonArray ?: return null to null
            return ((values.getOrNull(0) as? JsonPrimitive)?.doubleOrNull) to
                ((values.getOrNull(1) as? JsonPrimitive)?.doubleOrNull)
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    // ---- ReAnime --------------------------------------------------------------------------

    private suspend fun reanime(media: Media, audio: String, episode: Int): SourcesResult {
        if (audio == "dub" && (media.id to episode) in REANIME_BAD_DUB_MUXES) {
            error("ReAnime episode $episode DUB has a known unsynchronized audio track")
        }
        val base = "https://reanime.to"
        val flix = runProviderCatching { getJson("$base/api/flix/${media.id}/$episode") as? JsonObject }.getOrNull()
        val links = (flix?.get("servers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val accepted = if (audio == "dub") setOf("dub", "s-dub") else setOf("sub", "s-sub")
        val embeds = links.mapNotNull { link ->
            val kind = link.string("dataType")?.lowercase() ?: return@mapNotNull null
            if (kind !in accepted) return@mapNotNull null
            var url = link.string("dataLink") ?: return@mapNotNull null
            // ReAnime serves dual-audio embeds: sub and dub share one URL and the site
            // selects the English track by appending a=1 (audio track index).
            if (audio == "dub" && !url.contains(Regex.cached("""[?&]a="""))) {
                url += if ('?' in url) "&a=1" else "?a=1"
            }
            ReanimeEmbed(url, link.string("serverName") ?: "ReAnime")
        }.distinctBy { it.url }.sortedWith(
            compareBy<ReanimeEmbed> { if (it.name.equals("HD-2", true)) 0 else 1 }
                .thenBy { it.name },
        )

        if (embeds.isEmpty()) error("ReAnime episode $episode has no ${audio.uppercase()} servers")

        val primary = embeds.first()
        val embedHtml = runProviderCatching { getText(primary.url, mapOf("Referer" to "$base/")) }.getOrDefault("")
        val subtitles = ReanimeFlixcloudParser.subtitles(embedHtml, audio)
        val skip = ReanimeFlixcloudParser.skip(embedHtml)
        val nativeHls = runProviderCatching { FlixcloudBridge.resolve(primary.url, "$base/") }
            .getOrNull()

        val streams = mutableListOf<StreamItem>()
        if (nativeHls != null) {
            streams += stream(
                nativeHls.url,
                "hls",
                "${primary.name} native",
                "${origin(primary.url)}/",
                active = true,
            ).copy(playlistKey = nativeHls.playlistKey)
        }
        embeds.take(4).forEachIndexed { index, embed ->
            streams += stream(
                embed.url,
                "embed",
                "${embed.name} embed",
                "$base/",
                active = streams.isEmpty() && index == 0,
            )
        }
        return SourcesResult(streams.distinctBy { it.url }, subtitles, skip, null)
    }

    private data class ReanimeEmbed(val url: String, val name: String)

    // ---- AniZone --------------------------------------------------------------------------

    private suspend fun anizone(media: Media, episode: Int): SourcesResult {
        val base = "https://anizone.to"
        val slug = anizoneSlug(media)
        val pageUrl = "$base/anime/$slug/$episode"
        val html = getText(pageUrl, mapOf("Referer" to "$base/anime/$slug"))
        val hls = Regex.cached("""<media-player[^>]+src=["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)
            ?: NativeProviderParsers.hlsUrls(html).firstOrNull()
            ?: error("AniZone episode $episode has no HLS stream")
        val subtitles = Regex.cached("""<track\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
            val tag = match.value
            if (!NativeProviderParsers.attr(tag, "kind").equals("subtitles", true)) return@mapNotNull null
            val url = NativeProviderParsers.attr(tag, "src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = NativeProviderParsers.attr(tag, "label").ifBlank { "Subtitle" }
            SubtitleItem(NativeProviderParsers.absoluteUrl(base, url), label, NativeProviderParsers.attr(tag, "srclang").ifBlank { language(label) })
        }.toList()
        return SourcesResult(listOf(stream(hls, "hls", "AniZone", pageUrl, true)), subtitles, null, null)
    }

    private suspend fun anizoneSlug(media: Media): String {
        val base = "https://anizone.to"
        return resolveSlug("anizone", media) { query ->
            val html = getText("$base/anime?search=${enc(query)}")
            val animeLinks = Regex.cached(
                """href=["'](?:https://anizone\.to)?/anime/([a-z0-9-]+)(?:[/?#][^"']*)?["']""",
                RegexOption.IGNORE_CASE,
            )
            val fallbackTitle = Regex.cached(
                """getTitle\(this\.anmTitles,\s*'((?:\\'|[^'])*)'\)""",
                RegexOption.IGNORE_CASE,
            )
            animeLinks.findAll(html)
                .map { link ->
                    val start = (link.range.first - 3_000).coerceAtLeast(0)
                    val prefix = html.substring(start, link.range.first)
                    val title = fallbackTitle.findAll(prefix).lastOrNull()?.groupValues?.get(1)
                        ?.replace("\\'", "'")
                        ?: link.groupValues[1].replace('-', ' ')
                    Candidate(link.groupValues[1], title)
                }
                .distinctBy { it.slug }
                .toList()
        }
    }

    // ---- AnimeGG --------------------------------------------------------------------------

    private suspend fun animegg(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://www.animegg.org"
        val slug = animeGgSlug(media)
        val series = getText("$base/series/$slug")
        val episodeSlug = ANIMEGG_LIST_ITEM.firstMatchNotNullOf(series) { match ->
            val block = match.group(1).orEmpty()
            if (!block.contains("anm_det_pop")) return@firstMatchNotNullOf null
            val number = ANIMEGG_STRONG_NUMBER.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@firstMatchNotNullOf null
            if (number != episode) return@firstMatchNotNullOf null
            val tag = ANIMEGG_DETAIL_ANCHOR.find(block)?.value ?: return@firstMatchNotNullOf null
            NativeProviderParsers.attr(tag, "href").trimStart('/').substringBefore('#')
        } ?: error("AnimeGG episode $episode was not found")

        val watchUrl = "$base/$episodeSlug"
        val watch = getText(watchUrl, mapOf("Referer" to base))
        val tabs = Regex.cached("""<a\b[^>]*data-toggle=["']tab["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(watch)
            .mapNotNull { match ->
                val id = NativeProviderParsers.attr(match.value, "data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val version = NativeProviderParsers.attr(match.value, "data-version")
                val tabAudio = if (version.startsWith("dub", true)) "dub" else "sub"
                if (tabAudio != audio) return@mapNotNull null
                id to NativeProviderParsers.attr(match.value, "data-mirror").ifBlank { "AnimeGG" }
            }.toList()
        val streams = mutableListOf<StreamItem>()
        tabs.take(4).forEachIndexed { index, (id, server) ->
            val embed = "$base/embed/$id"
            val embedHtml = runProviderCatching { getText(embed, mapOf("Referer" to base)) }.getOrDefault("")
            val hlsUrls = NativeProviderParsers.hlsUrls(embedHtml)
            hlsUrls.forEach { url ->
                streams += stream(NativeProviderParsers.absoluteUrl(base, url), "hls", server, embed, streams.isEmpty())
            }
            // AnimeGG serves progressive MP4s through jwplayer (`file: "/play/.../video.mp4?for=..."`).
            val mp4Urls = Regex.cached("""file:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
                .findAll(embedHtml).map { it.groupValues[1] }.toList()
            mp4Urls.forEach { rawUrl ->
                val url = NativeProviderParsers.absoluteUrl(base, rawUrl)
                streams += stream(url, "video", server, embed, streams.isEmpty())
            }
            if (animeGgEmbedCanPlay(embedHtml, hlsUrls.isNotEmpty() || mp4Urls.isNotEmpty())) {
                streams += stream(embed, "embed", "$server embed", embed, streams.isEmpty() && index == 0)
            }
        }
        return SourcesResult(streams.distinctBy { it.url }, emptyList(), null, null)
    }

    private suspend fun animeGgSlug(media: Media): String {
        val base = "https://www.animegg.org"
        return resolveSlug("animegg", media) { query ->
            val html = getText("$base/search/?q=${enc(query)}")
            Regex.cached("""<a\b([^>]*class=["'][^"']*\bmse\b[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex.cached("""^/series/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    val strong = Regex.cached("""<strong[^>]*>([\s\S]*?)</strong>""", RegexOption.IGNORE_CASE)
                        .find(match.groupValues[2])?.groupValues?.get(1)
                    Candidate(id, strong?.let(NativeProviderParsers::stripTags) ?: id.replace('-', ' '))
                }.toList()
        }
    }

    // ---- AniNeko --------------------------------------------------------------------------

    private suspend fun anineko(media: Media, audio: String, episode: Int): SourcesResult {
        val base = "https://anineko.to"
        val slug = aniNekoSlug(media)
        val watchUrl = "$base/watch/$slug/ep-$episode"
        val html = getText(watchUrl, mapOf("Referer" to "$base/watch/$slug"))
        val embeds = mutableListOf<String>()
        Regex.cached("""<div\b[^>]*class=["'][^"']*nv-server-grid[^"']*["'][^>]*data-id=["']([^"']+)["'][^>]*>([\s\S]*?)(?=<div\b[^>]*class=["'][^"']*nv-server-grid|$)""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { panel ->
                val panelAudio = if (panel.groupValues[1].contains("dub", true)) "dub" else "sub"
                if (panelAudio == audio) {
                    Regex.cached("""data-video=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .findAll(panel.groupValues[2]).forEach { embeds += NativeProviderParsers.decodeEntities(it.groupValues[1]) }
                }
            }
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()
        embeds.distinct().take(4).forEachIndexed { index, embed ->
            val embedHtml = runProviderCatching { getText(embed, mapOf("Referer" to "$base/")) }.getOrDefault("")
            NativeProviderParsers.hlsUrls(embedHtml).forEach { hls ->
                streams += stream(hls, "hls", "AniNeko", embed, streams.isEmpty())
            }
            subtitles += embedQuerySubtitles(embed)
            streams += stream(embed, "embed", "AniNeko embed", embed, streams.isEmpty() && index == 0)
        }
        return SourcesResult(streams.distinctBy { it.url }, subtitles.distinctBy { it.url }, null, null)
    }

    private suspend fun aniNekoSlug(media: Media): String {
        val base = "https://anineko.to"
        return resolveSlug("anineko", media) { query ->
            val html = getText("$base/browser?keyword=${enc(query)}")
            Regex.cached("""<a\b([^>]*class=["'][^"']*nv-anime-thumb[^"']*["'][^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { match ->
                    val href = NativeProviderParsers.attr(match.groupValues[1], "href")
                    val id = Regex.cached("""/watch/([^/?#]+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                    Candidate(id, NativeProviderParsers.stripTags(match.groupValues[2]).ifBlank { id.replace('-', ' ') })
                }.toList()
        }
    }

    /**
     * AniNeko softsub servers pass their subtitle tracks to the embed player through the URL
     * (`?sub=<vtt>` or `?caption_1=<vtt>&sub_1=English`); recover them for native playback.
     */
    private fun embedQuerySubtitles(embedUrl: String): List<SubtitleItem> {
        val query = embedUrl.substringAfter('?', "")
        if (query.isBlank()) return emptyList()
        val params = query.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }
        val byName = params.toMap()
        return params.mapNotNull { (key, value) ->
            if (!value.startsWith("http")) return@mapNotNull null
            val label = when {
                key == "sub" -> "English"
                key.startsWith("caption") -> byName["sub${key.removePrefix("caption")}"] ?: "Subtitle"
                else -> return@mapNotNull null
            }
            SubtitleItem(value, label, language(label))
        }
    }

    // ---- 2Dhive ---------------------------------------------------------------------------

    /**
     * 2Dhive's BabaStream servers, resolved to files where the device is willing to pay for one.
     *
     * The embed is offered either way and always last. Resolving costs a Cap proof of work that
     * [BabaStreamTokenPool] declines outright on constrained hardware, so on a TV box this is the
     * only entry that appears — which is exactly what 2Dhive offered there before, rather than a
     * loss. Where a token is available the direct file leads, because it is the better stream in
     * every respect that matters here: no WebView, no Referer, and downloadable as it stands.
     */
    private suspend fun babaStreamStreams(
        servers: List<JsonObject>,
        referer: String,
        active: Boolean,
    ): List<StreamItem> {
        val embeds = servers.mapNotNull { it.string("slug") }
            .filter { BABASTREAM_EMBED.containsMatchIn(it) }
            .distinct()
        if (embeds.isEmpty()) return emptyList()

        val resolved = embeds.mapNotNull { embed ->
            runProviderCatching { babaStream.resolve(embed, referer) }.getOrNull()
        }
        val direct = resolved.mapIndexed { index, source ->
            stream(source.url, source.type, "BabaStream", "${source.origin}/", active && index == 0)
        }
        val fallback = embeds.map { embed ->
            stream(embed, "embed", "BabaStream embed", referer, active && direct.isEmpty() && embed == embeds.first())
        }
        return direct + fallback
    }

    private suspend fun twoDhive(media: Media, audio: String, episode: Int): SourcesResult {
        val malId = media.idMal ?: error("2Dhive needs a MyAnimeList id")
        val base = "https://2dhive.com"
        val referer = "$base/episode?anime=$malId&ep_num=$episode"
        val streams = mutableListOf<StreamItem>()
        val subtitles = mutableListOf<SubtitleItem>()

        val props = runProviderCatching { extractAstroProps(getText(referer)) }.getOrNull()
        val forThisAudio = (props?.get("servers") as? JsonArray).orEmpty().mapNotNull { element ->
            val server = element as? JsonObject ?: return@mapNotNull null
            val isDub = server["dub"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
            if (isDub != (audio == "dub")) null else server
        }
        val matchingServers = forThisAudio.mapNotNull { server ->
            if (server.string("server_name").equals("HAdfree", true)) server.string("slug") else null
        }

        // BabaStream first: it is the only server 2Dhive still lists for most episodes, and alone
        // among everything here it answers with a progressive MP4 that needs no Referer — the one
        // stream shape Cast and external players can actually take.
        streams += babaStreamStreams(forThisAudio, referer, active = streams.isEmpty())
        matchingServers.take(3).forEach { slug ->
            val direct = runProviderCatching {
                (getJson("$base/api/hadfree?slug=${enc(slug)}", mapOf("Referer" to referer)) as? JsonObject)
                    ?.string("streamUrl")
            }.getOrNull()
            if (!direct.isNullOrBlank()) {
                val type = if (direct.contains(".m3u8", true)) "hls" else "video"
                streams += stream(direct, type, "2Dhive HAdfree", referer, streams.isEmpty())
            }
        }

        if (audio == "sub") {
            val hiAnime = runProviderCatching {
                getJson("$base/api/hianime?mal_id=$malId&ep_num=$episode", mapOf("Referer" to referer)) as? JsonObject
            }.getOrNull()
            val hls = hiAnime?.string("m3u8")
            val usableHls = hls?.takeIf { url ->
                runProviderCatching { getText(url, mapOf("Referer" to referer)).trimStart().startsWith("#EXTM3U") }
                    .getOrDefault(false)
            }
            usableHls?.let { streams += stream(it, "hls", "2Dhive hiAnime", referer, streams.isEmpty()) }
            if (usableHls != null) {
                hiAnime?.string("subtitle")?.let { subtitles += SubtitleItem(it, "English", "en") }
            }
        }
        val embed = "https://megaplay.buzz/stream/mal/$malId/$episode/${if (audio == "dub") "dub" else "sub"}"
        var subtitleOffsetMs = 0L
        runProviderCatching { megaPlaySources(MegaPlayEmbed(embed, referer, null)) }.getOrNull()?.let { megaPlay ->
            streams += megaPlay.streams
            subtitles += megaPlay.subtitles
            if (megaPlay.embedAvailable) {
                streams += stream(megaPlay.embedUrl, "embed", "2Dhive MegaPlay", MEGAPLAY_EMBED_REFERER, streams.isEmpty())
            }
            // The dub fallback here is MegaPlay's, so it borrows subtitles the same way AniKoto does.
            if (audio == "dub") subtitleOffsetMs = megaPlaySubtitleOffsetMs(megaPlay, referer)
        }
        return SourcesResult(streams, subtitles, null, null, subtitleOffsetMs)
    }

    // ---- Shared ---------------------------------------------------------------------------

    private suspend fun resolveSlug(
        provider: String,
        media: Media,
        search: suspend (String) -> List<Candidate>,
    ): String {
        identityStore.slug(provider, media.id)?.let { return it }
        val candidates = titles(media).flatMap { title ->
            runProviderCatching { search(title) }.getOrDefault(emptyList())
        }
            .distinctBy { it.slug }
        val chosen = candidates.maxByOrNull { candidateScore(media, it) }
            ?: error("${ProviderCatalog.label(provider)} match not found")
        if (candidateScore(media, chosen) < 0.28) error("${ProviderCatalog.label(provider)} title match was too weak")
        identityStore.remember(provider, media.id, chosen.slug)
        return chosen.slug
    }

    private fun <K, V> boundedMap(maxEntries: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                size > maxEntries
        },
    )

    private fun candidateScore(media: Media, candidate: Candidate): Double {
        var score = titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.title) } ?: 0.0
        score = maxOf(score, titles(media).maxOfOrNull { NativeProviderParsers.titleSelectionScore(it, candidate.slug) } ?: 0.0)
        media.seasonYear?.let { year ->
            val candidateYear = Regex.cached("""\b(19|20)\d{2}\b""").find(candidate.title)?.value?.toIntOrNull()
            if (candidateYear != null) score *= if (candidateYear == year) 1.15 else 0.55
        }
        return score.coerceAtMost(1.0)
    }

    private fun titles(media: Media): List<String> = listOfNotNull(
        media.title.english,
        media.title.romaji,
        media.title.native,
    ).filter { it.isNotBlank() }.distinct()

    private fun xhr(referer: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun htmlResult(element: JsonElement?): String = when (element) {
        is JsonObject -> element.string("html") ?: element.string("result") ?: htmlResult(element["result"])
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

    private suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,*/*")
            headers.forEach(builder::header)
            val call = providerClient.newCall(builder.get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code} fetching $url")
                            }
                            response.body?.string().orEmpty()
                        }
                        continuation.resumeWith(Result.success(body))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            })
        }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(getText(url, headers + ("Accept" to "application/json, */*")))

    private fun stream(url: String, type: String, label: String, referer: String?, active: Boolean) = StreamItem(
        url = url,
        type = type,
        quality = label,
        audio = null,
        referer = referer,
        isActive = active,
        width = null,
        height = null,
    )

    private fun skipTimes(root: JsonObject?): SkipTimes? {
        fun range(name: String): Pair<Double?, Double?> {
            val obj = root?.get(name) as? JsonObject
            return (obj?.number("start")) to (obj?.number("end"))
        }
        val intro = range("intro")
        val outro = range("outro")
        if (intro.first == null && intro.second == null && outro.first == null && outro.second == null) return null
        return SkipTimes(intro.first, intro.second, outro.first, outro.second)
    }

    /** Decode Astro's compact `[type, value]` serialization used in 2Dhive component props. */
    private fun extractAstroProps(html: String): JsonObject? {
        val marker = html.indexOf("prefetchedHls")
        if (marker < 0) return null
        val valueStart = html.lastIndexOf("props=\"", marker).takeIf { it >= 0 }?.plus(7) ?: return null
        val valueEnd = html.indexOf('"', valueStart).takeIf { it > valueStart } ?: return null
        val raw = NativeProviderParsers.decodeEntities(html.substring(valueStart, valueEnd))
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        return JsonObject(root.mapValues { (_, value) -> astroDecode(value) })
    }

    private fun astroDecode(value: JsonElement): JsonElement {
        if (value !is JsonArray || value.size < 2) {
            return if (value is JsonObject) JsonObject(value.mapValues { astroDecode(it.value) }) else value
        }
        val type = (value[0] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return value
        val data = value[1]
        return when (type) {
            0 -> if (data is JsonObject) JsonObject(data.mapValues { astroDecode(it.value) }) else data
            1 -> if (data is JsonArray) JsonArray(data.map(::astroDecode)) else data
            else -> data
        }
    }

    /**
     * MegaPlay/AniKoto label the track by its full language name ("Indonesian", "Thai captions",
     * "Spanish(Latin America)"). Match on a contained keyword rather than an exact string so the
     * suffixes and " captions" qualifiers still resolve. Anything unrecognised stays "und" but the
     * human label is preserved for the picker either way.
     */
    private fun language(label: String?): String {
        val text = label?.lowercase() ?: return "und"
        return when {
            text.contains("english") || text == "en" -> "en"
            text.contains("japanese") || text == "ja" -> "ja"
            text.contains("indonesian") || text == "id" -> "id"
            text.contains("thai") || text == "th" -> "th"
            text.contains("arabic") || text == "ar" -> "ar"
            text.contains("french") || text == "fr" -> "fr"
            text.contains("german") || text == "de" -> "de"
            text.contains("spanish") || text == "es" -> "es"
            text.contains("portuguese") || text == "pt" -> "pt"
            text.contains("italian") || text == "it" -> "it"
            text.contains("russian") || text == "ru" -> "ru"
            else -> "und"
        }
    }

    private fun origin(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.authority}" }
    }.getOrDefault(url.substringBefore('/', ""))

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun String.data(name: String): String? = Regex("""data-${Regex.escape(name)}=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(this)?.groupValues?.get(1)?.let(NativeProviderParsers::decodeEntities)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

    companion object {
        private const val CATALOG_TIMEOUT_MS = 15_000L

        /**
         * How long one lookup may hold a catalogue lane before it is handed to whoever is next.
         *
         * Deliberately a third of [CATALOG_TIMEOUT_MS]: slower than every provider that answered
         * on the measured cold pass, and short enough that three queued lookups can still run
         * inside one budget. See [withCatalogLane] for what was measured.
         */
        private const val LANE_HOLD_CAP_MS = 5_000L

        /**
         * anikoto reaches its own site as well as the MegaPlay API, and the two use different
         * slugs, so the site's identity is kept under its own name rather than colliding with the
         * provider's.
         */
        private const val ANIKOTO_SITE = "anikoto-site"

        /** The route MegaPlay served for years, kept as the answer when discovery finds nothing. */
        private const val MEGAPLAY_FALLBACK_ROUTE = "stream/getSources"

        /** How many same-origin scripts to open looking for the routes before giving up. */
        private const val MEGAPLAY_SCRIPT_LOOKUPS = 3

        /**
         * The referer a MegaPlay embed page is opened under, whichever provider found it.
         *
         * MegaPlay's `app.main.js` reads `document.referrer`, checks it against its partner list
         * (`/domains`, base64 JSON) and, for anyone else, injects `nekostream.site/mt/3p.js` — an
         * ad loader whose interstitial painted a full-screen "Confirm you're not a robot" QR code
         * over a playing TV episode. The embed used to go out under its own origin, which is not
         * a partner; neither is 2dhive.com. WebView turns a `Referer` handed to `loadUrl` into the
         * document's real referrer, so naming a partner is the whole fix. `hianimes.re` is the
         * one this client already fetches MegaPlay pages under.
         */
        private const val MEGAPLAY_EMBED_REFERER = "https://hianimes.re/"

        /** Routes change on MegaPlay's deploys, so re-reading them twice an hour is plenty. */
        private const val MEGAPLAY_ROUTE_TTL_MS = 30 * 60 * 1000L
        /**
         * Long enough that a binge and a rewatch the next evening cost one lookup between them,
         * short enough to stay well inside the tracks' own life. AniChan stamps a subtitle URL
         * with a fixed calendar expiry rather than a rolling one — every episode of a title
         * measured carried the same date, three weeks out — so a cache measured in weeks would
         * eventually hand the player a link that had already lapsed.
         */
        private const val BORROWED_SUBTITLE_TTL_MS = 3L * 24 * 60 * 60 * 1000

        private const val PROVIDER_HTTP_CALL_TIMEOUT_MS = 15_000L
        private const val PROVIDER_RESPONSE_MAX_BYTES = 8L * 1024L * 1024L
        private val REANIME_BAD_DUB_MUXES = setOf(113415 to 2)
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

internal fun animeGgEmbedCanPlay(html: String, hasExtractedMedia: Boolean): Boolean {
    if (hasExtractedMedia) return true
    if (html.isBlank()) return false
    val explicitlyEmpty = Regex(
        """\b(?:var|let|const)\s+videoSources\s*=\s*\[\s*]\s*;?""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(html)
    if (explicitlyEmpty) return false
    return html.contains("jwplayer", ignoreCase = true) ||
        Regex.cached("""<iframe\b[^>]*src=["'][^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)
}

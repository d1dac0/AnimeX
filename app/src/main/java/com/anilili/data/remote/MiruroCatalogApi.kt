package com.anilili.data.remote

import android.os.SystemClock
import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import com.anilili.diagnostics.DiagnosticsHttpEventListener
import com.anilili.diagnostics.DiagnosticsLog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Miruro's catalog API: plain same-origin GETs under the site's `/api`, which replaced the pipe.
 *
 * Found 2026-10-03. Every Miruro server had stopped resolving — the pipe's `sources` call answered
 * 404 with the site's HTML shell on all four mirrors, and three of them 404'd `episodes` too — because
 * the site had moved to a REST catalog: `v1/anime?anilist_id_in=…` maps an AniList id to Miruro's own
 * 32-character one, `v1/anime/{id}/episodes?kind=regular` lists a title's episodes once (no longer
 * per provider), and `v1/anime/{id}/episodes/{n}/play` answers every provider and server for one
 * episode in a single response. `config` carries the provider labels the app has always shown
 * (`anikoto` is "bee", `animepahe` is "kiwi"…) and which are iframe embeds of which.
 *
 * Responses are JSON, or — for the catalog paths — `application/octet-stream`: the bytes XOR'd with
 * the ASCII of `miruro/catalog`, then gzip. That is the site's own decoder (`classNames-*.js`), and
 * the key is a literal in its bundle, not a secret.
 *
 * Errors are RFC 7807 problem documents. One trap worth knowing: a query the server does not
 * recognise answers `400 Unsupported catalog request` rather than ignoring the parameter — the
 * AniList lookup needs `limit=100` exactly — so a well-formed request missing one looks exactly like
 * a broken API. The set moves without notice: the episode list *required* `kind=regular` on
 * 2026-10-03 and *refused* it on 2026-10-12, which took every Miruro server out of the app at once.
 *
 * Measured from the dev machine over plain HTTP with no cookie: all five mirrors answered `config`
 * 200 in 0.15-0.29s. The pipe's Cloudflare rule never applied to these paths.
 */
internal object MiruroCatalogApi {

    /**
     * The client every Miruro request shares, in one form or another.
     *
     * [OkHttpPipe] derives its own from this one with `newBuilder()` (#73): the catalog and the
     * retired pipe speak to the same five mirrors, and two independent clients meant two
     * ConnectionPools and two Dispatchers against them — a TLS handshake apiece per mirror and
     * idle sockets held twice over. Deriving shares both and inherits the trust anchors and the
     * diagnostics listener; only the deadlines differ, and the pipe states its own on top.
     * Internal so [OkHttpPipe] can derive and `MirrorConnectionPoolTest` can prove the sharing.
     */
    internal val client = OkHttpClient.Builder()
        .withLegacyTrustAnchors()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .eventListenerFactory(DiagnosticsHttpEventListener.Factory)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    private val CATALOG_KEY = "miruro/catalog".toByteArray(Charsets.US_ASCII)

    /** The mirror that last answered, tried first next time. */
    @Volatile private var preferred: String? = null

    /** A refusal the API itself made (a problem document), as opposed to a mirror being unreachable. */
    class ApiRefusal(val status: Int, detail: String) : IOException("Miruro catalog HTTP $status: $detail")

    /**
     * GET `{mirror}/api/{path}` with [query], decoded to the response's JSON text.
     *
     * Walks the mirrors only on a transport failure or a 5xx. Any 4xx is the API's answer about this
     * request and every mirror serves the same API, so asking the next one would only repeat it.
     */
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), budgetMs: Long = 25_000): String =
        withContext(Dispatchers.IO) {
            val order = preferred?.let { first -> listOf(first) + catalogOrigins.filterNot { it == first } }
                ?: catalogOrigins
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            var lastFailure: IOException? = null
            for (origin in order) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                try {
                    val text = request(origin, path, query, remaining)
                    if (preferred != origin) {
                        preferred = origin
                        DiagnosticsLog.event("MiruroCatalogApi serving from $origin")
                    }
                    return@withContext text
                } catch (refusal: ApiRefusal) {
                    if (refusal.status < 500) throw refusal
                    lastFailure = refusal
                } catch (failure: IOException) {
                    lastFailure = failure
                }
                DiagnosticsLog.event("MiruroCatalogApi $origin failed path=$path: ${lastFailure?.message}")
            }
            throw lastFailure ?: IOException("Miruro catalog budget of ${budgetMs}ms spent")
        }

    private fun request(origin: String, path: String, query: Map<String, String>, timeoutMs: Long): String {
        val url = "$origin/api/$path".toHttpUrl().newBuilder()
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$origin/")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8")
            .build()
        val call = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request)
        call.execute().use { response ->
            val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                val text = String(bytes, Charsets.UTF_8)
                val detail = Regex(""""detail"\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
                    ?: text.take(120).ifBlank { "no body" }
                throw ApiRefusal(response.code, detail)
            }
            return if (type == "application/octet-stream") decodeCatalogBody(bytes) else String(bytes, Charsets.UTF_8)
        }
    }

    /** The site's catalog decoder: XOR with `miruro/catalog`, then gunzip. */
    internal fun decodeCatalogBody(bytes: ByteArray): String {
        val unmasked = ByteArray(bytes.size) { (bytes[it].toInt() xor CATALOG_KEY[it % CATALOG_KEY.size].toInt()).toByte() }
        return GZIPInputStream(ByteArrayInputStream(unmasked)).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}

/**
 * The mirrors the catalog API answers on, in the order to try them. `.cx` is new with the catalog —
 * the site's own module list names it — and all five answered on 2026-10-03.
 */
internal val catalogOrigins: List<String> = listOf(
    "https://www.miruro.to",
    "https://www.miruro.tv",
    "https://www.miruro.bz",
    "https://www.miruro.ru",
    "https://www.miruro.cx",
)

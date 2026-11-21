package com.anilili.data.remote

import android.os.SystemClock
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Miruro's own "I do not have that episode in that variant".
 *
 * Not a Cloudflare refusal — those are 403 and 503, and they carry `cf-mitigated`. This one is the
 * origin answering, which is why the engine build's clearance recovery deliberately left it out of
 * its "lost clearance" set: no amount of re-clearing changes it.
 */
internal const val PIPE_NOT_THIS_EPISODE_OR_VARIANT = 444

/**
 * Whether the remaining mirrors could still answer differently from the one that just refused.
 *
 * They cannot for a 444: all four origins serve the same catalogue, so a "not this episode or
 * variant" is the same answer four times over. Measured across six diagnostics bundles on
 * 2026-09-10 — 20 per-origin refusals in five walks, every walk a complete set of four, never once
 * a mirror disagreeing — and the cost of asking anyway was 1.5s on a phone and **20s** on an armv7
 * television, in front of a fallback the resolver already had ready.
 *
 * Anything else keeps walking. A 403 or 503 is the zone re-hardening on one edge and another may
 * still be open, which is the whole reason there are four.
 */
internal fun pipeWalkContinuesAfter(status: Int): Boolean = status != PIPE_NOT_THIS_EPISODE_OR_VARIANT

/**
 * The pipe over a plain HTTP client, with no browser engine anywhere.
 *
 * For most of this project's history that was impossible: the secure API paths answered
 * Cloudflare's block page to anything that was not a same-origin fetch inside a cleared page,
 * which is the entire reason GeckoView was taken on — and with it about 85 MB of a 97 MB APK.
 *
 * (Editors: no literal slash-star anywhere in this KDoc. Kotlin nests block comments, so writing
 * a path like that one out opens a comment that the "star-slash-star" Accept header below then
 * closes, and the rest of the file parses as garbage. Same note as [PipeBridge]'s.)
 *
 * Measured on 2026-09-10 from the S25 on home Wi-Fi, the zone no longer does this. All four
 * mirrors served the real site with **no challenge at all** — no interstitial, no `cf_clearance`
 * issued, no `cf_chl_rc_ni` retry marker — and this exact request returned `200`,
 * `cf-mitigated` absent, with a 3.87 MB gzip payload, on every one of `.to`, `.tv`, `.bz` and
 * `.ru`, holding no cookie whatsoever. That directly contradicts §0.18 and §0.21's own no-cookie
 * control, which answered `403 challenge` on 2026-09-09: the zone changed, not the client.
 *
 * So this is a **trial**, and its assumption is the zone's posture, which is the one thing here
 * that has always moved. If Cloudflare tightens again this returns 403 and the Miruro providers go
 * with it; the native scrapers are untouched either way. Keep the header set below exactly as it
 * is — §0.21 established that the block is a header-shape rule, so a missing `Referer` or
 * `Sec-Fetch-*` is enough to be refused even when the zone is otherwise open.
 *
 * The reply is deliberately the same JSON string [PipeBridge.fetch] has always returned, so
 * `PipeClient` and its decoder are untouched: `{ok,status,obf,body}`, where `obf` is the
 * `x-obfuscated` response header the decoder keys off.
 */
object OkHttpPipe {

    /**
     * Derived from [MiruroCatalogApi]'s client with `newBuilder()` (#73): the catalog and this pipe
     * speak to the same five mirrors, and separate clients meant separate ConnectionPools and
     * Dispatchers against them — a TLS handshake apiece per mirror, and idle sockets held twice
     * over. Deriving shares both, and inherits the catalog client's trust anchors and diagnostics
     * listener; only the deadlines differ, kept exactly as this client stated them before the
     * sharing. Internal so `MirrorConnectionPoolTest` can prove the sharing.
     *
     * That inherited listener is load-bearing: the pipe not reporting meant a diagnostics bundle
     * carried 400-odd `http.call.complete` rows without a single Miruro host in them — the one
     * request the whole Miruro half of the app depends on was the one request that could not be
     * read. Successes are invisible otherwise — only [preferred] changing logs anything — so "did
     * the pipe work at all" was a question the archive could not answer.
     */
    internal val client = MiruroCatalogApi.client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * A current Chrome-on-Android identity. It has to be a real browser's, and it has to stay
     * consistent with the `Sec-Fetch-*` set below: the refusal is on the shape of the whole
     * request, not on any single field.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Mirrors in [pipeOrigins] order; the first that answers wins and is remembered for the run. */
    @Volatile private var preferred: String? = null

    /**
     * One pipe request, walking the mirrors only while walking them can still change the answer.
     *
     * [timeoutMs] bounds the whole walk, not each hop. It used to bound neither: the parameter was
     * accepted and never read, so the only limit was the client's own 30s per call — four of them,
     * for a worst case of two minutes on one request. A television bundle caught a real 36.2s.
     */
    suspend fun fetch(e: String, timeoutMs: Long = 30_000): String = withContext(Dispatchers.IO) {
        val order = preferred?.let { first -> listOf(first) + pipeOrigins.filterNot { it == first } }
            ?: pipeOrigins
        var lastStatus = -1
        var lastError: String? = null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        for (origin in order) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                lastError = lastError ?: "pipe budget of ${timeoutMs}ms spent before $origin"
                DiagnosticsLog.event("OkHttpPipe out of budget after ${timeoutMs}ms before $origin")
                break
            }
            val attempt = runCatching { request(origin, e, remainingMs) }
                .getOrElse { failure ->
                    lastError = "${failure.javaClass.simpleName}: ${failure.message}"
                    null
                }
            if (attempt != null) {
                if (attempt.optBoolean("ok", false)) {
                    if (preferred != origin) {
                        preferred = origin
                        DiagnosticsLog.event("OkHttpPipe serving from $origin")
                    }
                    return@withContext attempt.toString()
                }
                lastStatus = attempt.optInt("status", -1)
                // A challenge here is the zone re-hardening, which is the whole risk this build
                // carries. Name it plainly so a viewer's bundle says so rather than "no servers".
                DiagnosticsLog.event(
                    "OkHttpPipe refused origin=$origin status=$lastStatus " +
                        "cfMitigated=${attempt.optString("cfMitigated", "none")}",
                )
                if (!pipeWalkContinuesAfter(lastStatus)) break
            }
        }
        // Two different failures used to print the same sentence, and a bundle full of "every
        // mirror refused" read as an outage when it was Miruro answering a question about one
        // episode. Say which, so the next person reading a report does not have to count 444s.
        val settled = !pipeWalkContinuesAfter(lastStatus)
        DiagnosticsLog.event(
            if (settled) {
                "OkHttpPipe refused for this episode or variant status=$lastStatus (same on every mirror)"
            } else {
                "OkHttpPipe every mirror refused lastStatus=$lastStatus err=$lastError"
            },
        )
        JSONObject()
            .put("ok", false)
            .put("status", lastStatus)
            .put(
                "error",
                when {
                    settled -> "Miruro has no source for this episode or variant (status $lastStatus)"
                    else -> lastError ?: "pipe refused on every mirror (status $lastStatus)"
                },
            )
            .toString()
    }

    private fun request(origin: String, e: String, timeoutMs: Long): JSONObject {
        // Shares the connection pool and dispatcher; only the deadline differs per hop.
        val hop = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("$origin/api/secure/pipe?e=$e")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$origin/")
            .header("Origin", origin)
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        hop.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return JSONObject()
                .put("ok", response.isSuccessful)
                .put("status", response.code)
                .put("obf", response.header("x-obfuscated").orEmpty())
                .put("cfMitigated", response.header("cf-mitigated") ?: "none")
                .put("body", body)
        }
    }
}

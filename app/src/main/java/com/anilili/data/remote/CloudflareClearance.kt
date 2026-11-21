package com.anilili.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A Cloudflare clearance a browser earned for one host.
 *
 * [userAgent] is part of the credential, not decoration. Cloudflare binds `cf_clearance` to the
 * client that solved the challenge, so replaying the cookie under a different User-Agent is
 * refused exactly as if there were no cookie at all — which reads, from the provider's side, as an
 * unsolvable challenge that keeps coming back.
 */
internal data class CloudflareClearance(
    val cookie: String,
    val userAgent: String,
    val earnedAtMs: Long,
)

/**
 * Per-host Cloudflare clearances, kept across launches.
 *
 * CloudStream's `CloudflareKiller` clears every cookie on construction and keeps what it earns in
 * memory, so its users re-solve once per app launch. Persisting instead is the whole point of the
 * "shows once" behaviour: the cookie is valid for as long as Cloudflare says it is, and there is no
 * reason to make someone prove they are human again because they backed out of the app.
 *
 * What is *not* kept is a clearance that has since been refused. [forget] is called the moment a
 * host challenges a request that already carried one, because a stale clearance is worse than none:
 * it turns every retry into the same rejection.
 */
internal object CloudflareClearanceStore {
    private const val PREFS = "anilili_cloudflare"
    private const val COOKIE_SUFFIX = ".cookie"
    private const val AGENT_SUFFIX = ".agent"
    private const val EARNED_SUFFIX = ".earned"

    /**
     * How long a stored clearance is trusted before it is discarded unread.
     *
     * Cloudflare does not publish the lifetime and it varies by zone, so this is not an expiry so
     * much as a floor on how long a wrong answer can persist: a clearance that outlives the
     * server's own is caught on its next use by [forget] anyway.
     */
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var defaultUserAgent: String? = null

    @Volatile
    private var appContext: Context? = null

    private val memory = ConcurrentHashMap<String, CloudflareClearance>()

    /**
     * Warms the store off the main thread.
     *
     * `getSharedPreferences` stats and creates the prefs directory, which in `Application.onCreate`
     * tripped StrictMode's DiskReadViolation on a Galaxy S25.
     *
     * The WebView's default User-Agent is deliberately not looked up here. `getDefaultUserAgent`
     * loads the WebView provider — on current builds the whole Chromium renderer (see
     * EpisodeDownloads) — and doing it on every process start, WorkManager's included, paid for a
     * browser nobody had asked for, ahead of the first-content gate MainActivity keeps for its
     * resolver WebViews (#83). Only [harvest] needs the agent, and it runs inside the challenge
     * WebView, where the provider is loaded anyway.
     */
    fun init(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        appContext = app
        scope.launch(Dispatchers.IO) {
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    fun clearance(host: String): CloudflareClearance? {
        val key = host.lowercase()
        memory[key]?.let { cached ->
            if (!isExpired(cached)) return cached
            forget(key)
            return null
        }
        val store = prefs ?: return null
        val cookie = store.getString(key + COOKIE_SUFFIX, null)?.takeIf { it.isNotBlank() } ?: return null
        val agent = store.getString(key + AGENT_SUFFIX, null)?.takeIf { it.isNotBlank() } ?: return null
        val earned = store.getLong(key + EARNED_SUFFIX, 0L)
        val clearance = CloudflareClearance(cookie, agent, earned)
        if (isExpired(clearance)) {
            forget(host)
            return null
        }
        memory[key] = clearance
        return clearance
    }

    fun remember(host: String, clearance: CloudflareClearance) {
        val key = host.lowercase()
        memory[key] = clearance
        prefs?.edit()
            ?.putString(key + COOKIE_SUFFIX, clearance.cookie)
            ?.putString(key + AGENT_SUFFIX, clearance.userAgent)
            ?.putLong(key + EARNED_SUFFIX, clearance.earnedAtMs)
            ?.apply()
        DiagnosticsLog.event(
            category = "cloudflare",
            name = "cloudflare.clearance.saved",
            attributes = mapOf("host" to key),
        )
    }

    fun forget(host: String) {
        val key = host.lowercase()
        memory.remove(key)
        prefs?.edit()
            ?.remove(key + COOKIE_SUFFIX)
            ?.remove(key + AGENT_SUFFIX)
            ?.remove(key + EARNED_SUFFIX)
            ?.apply()
        DiagnosticsLog.event(
            category = "cloudflare",
            name = "cloudflare.clearance.dropped",
            attributes = mapOf("host" to key),
        )
    }

    /**
     * Harvests whatever the challenge WebView left in the shared cookie jar for [url].
     *
     * The whole cookie header is kept, not just `cf_clearance`. Cloudflare pairs clearance with
     * `__cf_bm` and some zones add their own, and a partial header is refused the same as a wrong
     * one — so the test is that clearance is *present*, while what gets stored is everything.
     */
    fun harvest(url: String, host: String): CloudflareClearance? {
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (cookies.isNullOrBlank() || !cookies.contains(CLEARANCE_COOKIE)) {
            // Names only — a clearance cookie is a credential and must not reach a shared log.
            // Worth recording because "the page loaded but there is nothing to replay" is the one
            // outcome that looks identical to a broken harvester and is in fact unfixable here: it
            // means the zone let the browser through on its TLS fingerprint rather than on a cookie.
            DiagnosticsLog.event(
                category = "cloudflare",
                name = "cloudflare.clearance.absent",
                attributes = mapOf(
                    "host" to host,
                    "cookies" to cookies.orEmpty().split(';')
                        .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                        .joinToString("|"),
                ),
            )
            return null
        }
        // Called from the challenge WebView, so the provider is loaded and this is cheap. Looked up
        // on the first harvest of the process and kept for the rest of it; [init] no longer
        // fetches it ahead of time.
        val agent = defaultUserAgent
            ?: appContext?.let { ctx ->
                runCatching { WebSettings.getDefaultUserAgent(ctx) }
                    .onFailure {
                        DiagnosticsLog.throwable("Cloudflare default user agent unavailable", it)
                    }
                    .getOrNull()
            }?.also { defaultUserAgent = it }
            ?: return null
        return CloudflareClearance(cookies, agent, System.currentTimeMillis())
            .also { remember(host, it) }
    }

    internal fun resetForTest() {
        memory.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun isExpired(clearance: CloudflareClearance): Boolean =
        clearance.earnedAtMs <= 0L || System.currentTimeMillis() - clearance.earnedAtMs > MAX_AGE_MS

    const val CLEARANCE_COOKIE = "cf_clearance"
}

/**
 * One visible Cloudflare challenge at a time, shared by every provider.
 *
 * Providers are resolved in parallel, so a Cloudflare zone that starts challenging will be hit by
 * several requests at once. Without the per-host de-duplication here that is one dialog per
 * in-flight request, stacked; with it, the first request opens the challenge and the rest await the
 * same answer.
 */
internal object CloudflareChallengeCoordinator {
    data class Challenge(val id: Long, val host: String, val url: String)

    private val _challenge = MutableStateFlow<Challenge?>(null)
    val challenge = _challenge.asStateFlow()

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<CloudflareClearance?>>()
    private var nextId = 0L

    @Volatile
    private var pending: Pair<Challenge, CompletableDeferred<CloudflareClearance?>>? = null

    /**
     * Shows the challenge for [url] if one is not already up for its host, and hands back the
     * answer everyone waiting on that host will share.
     *
     * The result is deliberately **not** tied to the lifetime of whoever asked for it. The request
     * that trips a challenge can only wait a few seconds — the provider client caps a call at 15s —
     * whereas a person needs as long as they need. Returning a standalone [CompletableDeferred]
     * means a caller can stop waiting without taking the dialog down with it: the challenge stays
     * on screen, and the clearance it eventually earns is in the store for the retry.
     *
     * This was not a theoretical concern. Tearing the dialog down when the first request's wait
     * expired left exactly twelve seconds to notice a dialog, read it, and tap a checkbox, which is
     * not enough — measured on device, both attempts timed out with the challenge never solved.
     *
     * Completes with `null` when the viewer dismisses it. A dismissal is not remembered: the next
     * request re-asks, because the alternative is a server that stays silently dead with no way back.
     */
    fun begin(host: String, url: String): CompletableDeferred<CloudflareClearance?> {
        val key = host.lowercase()
        inFlight[key]?.let { return it }

        val result = CompletableDeferred<CloudflareClearance?>()
        synchronized(this) {
            // Only one challenge can be on screen, and there is exactly one [pending] slot. A
            // second host arriving while the first is unanswered used to overwrite that slot,
            // which dismissed the first dialog *and* stranded its deferred in [inFlight] forever —
            // so that host could never be challenged again for the rest of the session. Refuse
            // instead: this request fails now and re-asks once the screen is free.
            if (pending != null) return CompletableDeferred(null)
            inFlight.putIfAbsent(key, result)?.let { return it }
            val challenge = Challenge(++nextId, key, url)
            pending = challenge to result
            _challenge.value = challenge
        }
        // Filed under NET-10 as well as its own event. The dialog itself deliberately shows no code
        // — a Cloudflare interstitial centres its checkbox in the viewport, and every extra line
        // here pushed that checkbox off a phone screen — but "how often is a viewer challenged, and
        // on which host" is exactly the question a bundle should be able to answer by code.
        AnililiError.CLOUDFLARE_CHALLENGE.report(
            attributes = mapOf("host" to key, "event" to "cloudflare.challenge.shown"),
        )
        return result
    }

    /** Takes the dialog down and releases the host, whatever [outcome] the challenge reached. */
    private fun finish(id: Long, outcome: CloudflareClearance?) {
        val current = synchronized(this) { pending } ?: return
        if (current.first.id != id) return
        synchronized(this) {
            if (pending?.first?.id == id) {
                pending = null
                _challenge.value = null
            }
        }
        inFlight.remove(current.first.host, current.second)
        current.second.complete(outcome)
    }

    /** Called by the challenge UI once the WebView has a clearance cookie for the host. */
    fun submit(id: Long, clearance: CloudflareClearance) {
        val host = synchronized(this) { pending }?.takeIf { it.first.id == id }?.first?.host ?: return
        DiagnosticsLog.event(
            category = "cloudflare",
            name = "cloudflare.challenge.solved",
            attributes = mapOf("host" to host),
        )
        finish(id, clearance)
    }

    /** Called when the viewer dismisses the dialog, or the challenge page gives up. */
    fun cancel(id: Long) {
        val host = synchronized(this) { pending }?.takeIf { it.first.id == id }?.first?.host ?: return
        DiagnosticsLog.event(
            category = "cloudflare",
            name = "cloudflare.challenge.cancelled",
            attributes = mapOf("host" to host),
        )
        finish(id, null)
    }

    internal fun resetForTest() {
        synchronized(this) {
            pending?.second?.complete(null)
            pending = null
            _challenge.value = null
        }
        inFlight.values.forEach { it.complete(null) }
        inFlight.clear()
    }
}

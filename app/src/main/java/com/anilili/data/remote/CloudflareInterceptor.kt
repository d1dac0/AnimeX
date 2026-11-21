package com.anilili.data.remote

import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Replays a Cloudflare clearance on every provider request, and earns one when a host starts
 * challenging.
 *
 * Modelled on CloudStream's `CloudflareKiller`, with two deliberate differences. It only treats a
 * response as a challenge when the body or the `cf-mitigated` header says so — a 403 from a
 * Cloudflare-fronted origin is far more often a hotlink or geo block, and popping a dialog at
 * someone for one of those asks them to solve a puzzle that cannot help. And the clearance it earns
 * is persisted by [CloudflareClearanceStore] rather than being thrown away with the process, so the
 * challenge appears once rather than once per launch.
 *
 * **Bounded wait.** The provider client caps a call at 15 seconds and the catalogue gives each
 * provider its own 15-second lane, and neither can be extended from inside an interceptor. So the
 * request that triggers a challenge waits only [SOLVE_WAIT_MS] for the answer and then gives up on
 * itself — but *only on itself*. The challenge stays on screen for as long as the viewer needs, and
 * the clearance it earns lands in the store for the retry, for the other providers still resolving,
 * and for every request after that. Someone who takes their time over the checkbox loses the one
 * request that opened the dialog, not the session.
 */
internal class CloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        val stored = CloudflareClearanceStore.clearance(host)
        val first = chain.proceed(if (stored != null) request.withClearance(stored) else request)
        if (!first.isCloudflareChallenge()) return first

        // A challenge on a request that already carried a clearance means the clearance is dead —
        // bound to an IP or a User-Agent that no longer matches, or simply expired. Keeping it
        // would make every future request re-challenge with no way for the retry to differ.
        if (stored != null) {
            CloudflareClearanceStore.forget(host)
        }

        // Nobody asked for this request while looking at it: a validation sweep or a next-episode
        // preheat running under playback. Raising the dialog there takes the remote away from the
        // episode on screen mid-watch — judged unacceptable for the pipe (PipeBridge.humanCheckPending)
        // and missed here. The challenge response goes back as the answer; the next request a
        // viewer actually waits on will ask.
        if (NonInteractiveChallenge.appliesTo(chain)) {
            DiagnosticsLog.event(
                category = "cloudflare",
                name = "cloudflare.challenge.deferred",
                attributes = mapOf("host" to host, "code" to first.code),
            )
            return first
        }

        // begin() returns a deferred that outlives this call on purpose. Cancelling the wait must
        // not cancel the challenge — see CloudflareChallengeCoordinator.begin.
        val pending = CloudflareChallengeCoordinator.begin(host, request.url.toString())
        val earned = runBlocking { withTimeoutOrNull(SOLVE_WAIT_MS) { pending.await() } }
        if (earned == null) {
            DiagnosticsLog.event(
                category = "cloudflare",
                name = "cloudflare.challenge.unresolved",
                attributes = mapOf(
                    "host" to host,
                    "code" to first.code,
                    // Distinguishes "they dismissed it" from "this request ran out of budget while
                    // the challenge is still up", which are opposite problems.
                    "stillOpen" to !pending.isCompleted,
                ),
            )
            return first
        }

        first.close()
        return chain.proceed(request.withClearance(earned))
    }

    /**
     * Attaches the clearance and the User-Agent that earned it.
     *
     * The User-Agent is overwritten rather than merged: providers set their own, and Cloudflare
     * checks the cookie against the client presenting it, so sending a provider's spoofed agent
     * alongside a browser's clearance is a guaranteed re-challenge. Any Cookie header the provider
     * set is preserved ahead of the clearance, since some providers carry session state of their own.
     */
    private fun Request.withClearance(clearance: CloudflareClearance): Request {
        val existing = header("Cookie")?.takeIf { it.isNotBlank() }
        val merged = if (existing == null) clearance.cookie else "$existing; ${clearance.cookie}"
        return newBuilder()
            .header("Cookie", merged)
            .header("User-Agent", clearance.userAgent)
            .build()
    }

    /**
     * Whether this response is a solvable Cloudflare interstitial rather than an ordinary refusal.
     *
     * The body is peeked, not consumed, and only up to [CHALLENGE_PEEK_BYTES] — a challenge page
     * announces itself in the first few kilobytes, and reading further would buffer a whole
     * provider page for nothing.
     */
    private fun Response.isCloudflareChallenge(): Boolean {
        if (code !in CHALLENGE_CODES) return false
        val server = header("Server").orEmpty().lowercase()
        if (CLOUDFLARE_SERVERS.none { server.contains(it) }) return false
        // Cloudflare labels its own mitigations on newer zones; that is conclusive on its own.
        if (!header("cf-mitigated").isNullOrBlank()) return true
        val body = runCatching { peekBody(CHALLENGE_PEEK_BYTES).string() }.getOrNull().orEmpty()
        return CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private companion object {
        /** Long enough for a JS interstitial to clear itself and for one deliberate tap. */
        const val SOLVE_WAIT_MS = 12_000L
        const val CHALLENGE_PEEK_BYTES = 64L * 1024L
        val CHALLENGE_CODES = setOf(403, 503)
        val CLOUDFLARE_SERVERS = listOf("cloudflare", "cloudflare-nginx")
        val CHALLENGE_MARKERS = listOf(
            "challenge-platform",
            "cf_chl_opt",
            "cf-challenge",
            "Just a moment",
            "__cf_chl",
        )
    }
}

/**
 * Marks provider requests whose caller cannot put a Cloudflare challenge on screen.
 *
 * `allowInteractiveChallenges = false` used to stop at the AllAnime CAPTCHA; the interceptor never
 * heard of it, so a background request during playback could still open a full-screen dialog.
 * Three ways to carry the mark, because providers make their requests three ways:
 *
 * - [asContextElement] around the work. It sets a thread-local wherever that coroutine runs —
 *   `runInterruptible` blocks included — which covers every synchronous `execute()`.
 * - [eventListenerFactory] on the client. OkHttp creates the listener on the thread that calls
 *   `newCall`, so a call made under the mark stays marked when `enqueue` runs it on a dispatcher
 *   thread the thread-local never reaches.
 * - A request tag of this type, for a caller that builds the request itself.
 */
internal object NonInteractiveChallenge {
    private val active = ThreadLocal<Boolean?>()
    private val markedCalls: MutableSet<Call> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Call, Boolean>())

    fun asContextElement(): ThreadContextElement<Boolean?> = active.asContextElement(true)

    private fun activeOnThisThread(): Boolean = active.get() == true

    fun eventListenerFactory(delegate: EventListener.Factory): EventListener.Factory =
        object : EventListener.Factory {
            override fun create(call: Call): EventListener {
                if (activeOnThisThread()) synchronized(markedCalls) { markedCalls.add(call) }
                return delegate.create(call)
            }
        }

    fun appliesTo(chain: Interceptor.Chain): Boolean =
        chain.request().tag(NonInteractiveChallenge::class.java) != null ||
            activeOnThisThread() ||
            synchronized(markedCalls) { chain.call() in markedCalls }
}

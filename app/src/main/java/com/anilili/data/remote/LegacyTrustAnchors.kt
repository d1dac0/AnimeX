package com.anilili.data.remote

import android.os.Build
import com.anilili.diagnostics.DiagnosticsLog
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Teaches pre-7.1.1 Android about ISRG Root X1, the root every Let's Encrypt certificate now
 * chains to.
 *
 * Android's trust store is baked into the system image, and ISRG Root X1 was only added in 7.1.1
 * (API 25). Older devices used to reach Let's Encrypt hosts anyway because the chain carried a
 * cross-signature from DST Root CA X3, which those images do have — but that expired on
 * 2021-09-30 and the cross-signed chain is no longer served. So on anything below API 25 every
 * Let's Encrypt host now fails the handshake, and nothing else about the app is wrong.
 *
 * `minSdk` here is 22, so this is a supported configuration, and it is in the field: a diagnostics
 * bundle on 2026-09-12 came from an Amlogic set-top box on a 2018 AOSP 5.1.1 build (API 22,
 * `isLowRamDevice`, no leanback flags, mains-powered) whose episode thumbnails were all blank.
 * Nothing in the log looked like a failure, because every *other* host the app uses happens to
 * chain to a root old images do have — AniList's posters and API go through GTS Root R4 and
 * jsDelivr through GlobalSign R3, both cross-signed for exactly this reason, and the pipe's
 * provider hosts sit behind Cloudflare on GTS as well. Measured on 2026-09-12, the only two the
 * app touches that do not are:
 *
 * - `image.tmdb.org` → Let's Encrypt YR2 → ISRG Root YR → **ISRG Root X1**. Every episode still,
 *   from Konoha and from TMDB alike, which is why the titles loaded and the pictures did not.
 *   AniList's `streamingEpisodes` thumbnails are Crunchyroll-hosted and *did* load, so a list
 *   showed a stray working thumbnail wherever that last-resort source had filled a row.
 * - `flixcloud.cc` → Let's Encrypt YE2 → ISRG Root YE → **ISRG Root X2, cross-signed by X1**.
 *   ReAnime's streams. Fixed by the same anchor, since the chain is rooted in X1.
 *
 * One certificate covers both, and it is the self-signed root rather than any cross-signature, so
 * it does not expire with one: valid to 2035-06-04.
 *
 * Additive, and only below API 25. The bundled root is added *beside* everything the platform
 * already trusts rather than replacing it, so nothing the device trusts stops being trusted and no
 * user or enterprise anchor is dropped. From API 25 up the platform has this root itself and gets
 * to do its own verification, which is better than ours — hence the version gate rather than
 * installing this everywhere.
 *
 * Since #88, none of that work happens at startup. AppGraph.init used to enumerate the system
 * store through `acceptedIssuers`, copy it into a fresh KeyStore and run a second
 * TrustManagerFactory just to have the merged manager ready — on the main thread of the slowest
 * devices supported. Now the client gets a delegating manager that answers with the platform's own
 * verdict first and consults the bundled root only when the platform has said no, and the
 * ISRG-only manager behind it is built on demand, on whichever thread first presents a chain the
 * platform cannot verify — the first Let's Encrypt handshake. Chains the platform accepts never
 * reach the fallback, so what the device trusts is not re-decided here.
 */
object LegacyTrustAnchors {

    /**
     * ISRG Root X1, self-signed.
     *
     * SHA-256 `96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6` — checked against
     * the published fingerprint, and pinned by [LegacyTrustAnchorsTest] so a careless edit to this
     * blob cannot quietly install a different certificate.
     */
    internal const val ISRG_ROOT_X1_PEM: String = """
-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
"""

    /** API 25 is where ISRG Root X1 arrives in the system image; below it, the app must supply it. */
    internal fun needsBundledIsrgRoot(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.N_MR1

    internal fun parseIsrgRootX1(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(ISRG_ROOT_X1_PEM.trim().toByteArray()))
            as X509Certificate

    /**
     * The platform's own manager, from the same default TrustManagerFactory the default client
     * uses, so what the device trusts is not re-decided here. Shared by every client that installs
     * the delegating manager — a trust check keeps no state between calls — and built the first
     * time a client builder asks.
     *
     * Building this is all the trust work left on the startup path (#88). Over the system
     * AndroidCAStore the platform loads its own roots lazily on the first check — the AOSP source
     * bears this out for every API level the gate below covers, 22 through 24 — and it is
     * `acceptedIssuers` that forces a whole-store enumeration, which is exactly what the old
     * merged manager called during AppGraph.init. Nothing here does.
     */
    private val platformTrustManager: X509TrustManager? by lazy {
        runCatching {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        }.onFailure {
            DiagnosticsLog.throwable("LegacyTrustAnchors could not read the platform trust store", it)
        }.getOrNull()
    }

    /**
     * The ISRG-only trust manager, or null when it could not be built.
     *
     * Built on demand (#88), the first time the platform manager rejects a chain. Its store holds
     * exactly one certificate, so the enumeration a custom KeyStore forces on the manager built
     * from it is trivial next to the old merged store's, and it runs on the handshake's thread
     * rather than Main.
     *
     * `lazy` is SYNCHRONIZED, so two handshakes rejected at once build it once, and a failure is
     * cached as null: from there on every handshake gets the platform verdict, which is exactly
     * the behavior a device had before this file existed.
     */
    private val isrgOnlyTrustManager: X509TrustManager? by lazy {
        runCatching {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // ISRG Root X1 alone. The platform's anchors had their say before this manager is ever
            // consulted — the delegating one tries them first — so a merged copy of every system
            // certificate is not needed to answer the one question this manager is asked.
            store.setCertificateEntry("isrg-root-x1", parseIsrgRootX1())
            val manager = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(store) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull() ?: error("TrustManagerFactory produced no X509TrustManager")
            DiagnosticsLog.event(
                "LegacyTrustAnchors merged ISRG Root X1 sdk=${Build.VERSION.SDK_INT} installed=true",
            )
            manager
        }.onFailure {
            DiagnosticsLog.throwable("LegacyTrustAnchors could not merge ISRG Root X1", it)
        }.getOrNull()
    }

    /** Set by the first successful install; the event is a startup signal, not a per-client one. */
    private val installEventLogged = AtomicBoolean(false)

    /**
     * Adds the bundled root to [this] on the devices that need it, and changes nothing on the rest.
     *
     * Every client the app builds goes through here. The thumbnails were the visible symptom, but
     * the fault is one the whole process shares — Coil derives its client from the shared one,
     * playback and downloads build their own, and a Let's Encrypt stream host fails for all of
     * them identically.
     *
     * Since #88 this costs the builder almost nothing: no store is enumerated or copied here, and
     * the manager that goes into the SSLContext only reaches for the bundled root after the
     * platform has already said no.
     */
    fun OkHttpClient.Builder.withLegacyTrustAnchors(): OkHttpClient.Builder {
        if (!needsBundledIsrgRoot(Build.VERSION.SDK_INT)) return this
        val platform = platformTrustManager ?: return this
        return runCatching {
            val manager = PlatformFirstTrustManager(platform) { isrgOnlyTrustManager }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            sslSocketFactory(context.socketFactory, manager)
            if (installEventLogged.compareAndSet(false, true)) {
                DiagnosticsLog.event(
                    "LegacyTrustAnchors delegating trust to platform sdk=${Build.VERSION.SDK_INT}",
                )
            }
            this
        }.onFailure {
            DiagnosticsLog.throwable("LegacyTrustAnchors could not install ISRG Root X1", it)
        }.getOrDefault(this)
    }
}

/**
 * Tries the platform manager first and falls back to the ISRG-only one built on demand (#88).
 *
 * Installed below API 25 in place of the old merged manager, whose whole store-enumeration cost
 * used to run during AppGraph.init. Acceptance is the same union the merged store gave: a chain
 * the platform verifies is accepted immediately and never reaches the fallback, and a chain it
 * rejects gets one more chance against the bundled root. When the fallback cannot be built, the
 * platform's own rejection is rethrown unchanged, so the handshake fails exactly as it would have
 * without us.
 *
 * Stateless between checks, so concurrent handshakes can call it freely; the caching that matters
 * lives on the far side of [fallback], which the real wiring backs with a SYNCHRONIZED lazy.
 */
internal class PlatformFirstTrustManager(
    private val platform: X509TrustManager,
    private val fallback: () -> X509TrustManager?,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        checkChain(chain, authType) { it.checkClientTrusted(chain, authType) }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        checkChain(chain, authType) { it.checkServerTrusted(chain, authType) }
    }

    private fun checkChain(
        chain: Array<X509Certificate>,
        authType: String,
        verdict: (X509TrustManager) -> Unit,
    ) {
        try {
            verdict(platform)
            return
        } catch (platformRejection: CertificateException) {
            // Not trusted by the platform. The only chains this can still rescue are ones rooted
            // in ISRG Root X1, which the platform image below API 25 does not carry; anything else
            // the fallback will reject too, with its own word for why.
            val fallbackManager = fallback() ?: throw platformRejection
            verdict(fallbackManager)
        }
    }

    /**
     * The platform's issuers, unchanged. Nothing on the handshake path consults this — the
     * SSLSocket runs the checks above — so answering from the platform manager keeps any future
     * caller's question about platform trust honest without forcing the fallback to build.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
}

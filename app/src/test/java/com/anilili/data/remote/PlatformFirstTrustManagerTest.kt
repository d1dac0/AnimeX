package com.anilili.data.remote

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decision logic of [PlatformFirstTrustManager] only, against scripted collaborators. The JVM has
 * no AndroidCAStore, so the real on-demand build of the ISRG-only manager and the SSLContext
 * install stay untested here — what these tests pin is who gets asked, in what order, and what
 * reaches the handshake when someone says no.
 */
class PlatformFirstTrustManagerTest {

    /**
     * A trust manager whose verdicts are scripted and which counts the checks it is handed, so a
     * test can see who was asked. The delegating manager routes verdicts without inspecting the
     * chain it was given, so an empty one does.
     */
    private class ScriptedTrustManager(
        private val issuers: Array<X509Certificate> = arrayOf(),
        private val clientVerdict: () -> Unit = {},
        // Last, so a test can pass it as the trailing lambda.
        private val serverVerdict: () -> Unit = {},
    ) : X509TrustManager {
        var serverChecks = 0
            private set
        var clientChecks = 0
            private set

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            serverChecks++
            serverVerdict()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            clientChecks++
            clientVerdict()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = issuers
    }

    private val chain = arrayOf<X509Certificate>()
    private val authType = "RSA"

    @Test
    fun thePlatformVerdictEndsTheCheckBeforeTheFallbackIsConsidered() {
        // Every chain the device already trusts must be settled by the platform alone; reaching
        // the fallback at all would mean opening a KeyStore the handshake never needed.
        val platform = ScriptedTrustManager()
        var fallbackConsultations = 0
        val manager = PlatformFirstTrustManager(platform) {
            fallbackConsultations++
            null
        }

        manager.checkServerTrusted(chain, authType)

        assertEquals(1, platform.serverChecks)
        assertEquals(0, fallbackConsultations)
    }

    @Test
    fun aChainThePlatformRejectsGetsOneChanceAgainstTheBundledRoot() {
        val platform = ScriptedTrustManager { throw CertificateException("platform: no ISRG anchor") }
        val fallback = ScriptedTrustManager()
        val manager = PlatformFirstTrustManager(platform) { fallback }

        manager.checkServerTrusted(chain, authType)

        assertEquals(1, platform.serverChecks)
        assertEquals(1, fallback.serverChecks)
    }

    @Test
    fun whenTheFallbackAlsoRejectsItsVerdictIsWhatReachesTheHandshake() {
        val platform = ScriptedTrustManager { throw CertificateException("platform") }
        val fallback = ScriptedTrustManager { throw CertificateException("fallback") }
        val manager = PlatformFirstTrustManager(platform) { fallback }

        try {
            manager.checkServerTrusted(chain, authType)
            fail("a chain neither manager trusts must fail the handshake")
        } catch (rejected: CertificateException) {
            assertEquals("fallback", rejected.message)
        }
    }

    @Test
    fun whenTheFallbackCannotBeBuiltThePlatformRejectionStandsUnchanged() {
        // The degradation contract for a failed lazy build: the handshake sees the platform's own
        // exception, not a new one invented by the plumbing around it.
        val platformRejection = CertificateException("platform")
        val platform = ScriptedTrustManager { throw platformRejection }
        val manager = PlatformFirstTrustManager(platform) { null }

        try {
            manager.checkServerTrusted(chain, authType)
            fail("without a fallback there is nothing to rescue the chain with")
        } catch (rejected: CertificateException) {
            assertSame(platformRejection, rejected)
        }
    }

    @Test
    fun clientCertificatesFollowTheSameOrderPlatformFirstThenFallback() {
        val platform = ScriptedTrustManager(clientVerdict = { throw CertificateException("platform") })
        val fallback = ScriptedTrustManager()
        val manager = PlatformFirstTrustManager(platform) { fallback }

        manager.checkClientTrusted(chain, authType)

        assertEquals(1, platform.clientChecks)
        assertEquals(1, fallback.clientChecks)
    }

    @Test
    fun repeatedRejectionsBuildTheFallbackOnceBecauseTheProviderCachesIt() {
        // The delegating manager stays stateless and consults its provider on every platform
        // rejection; caching lives on the other side of that lambda — the object's SYNCHRONIZED
        // lazy in production. A memoizing stand-in shows the shape: many checks, one build.
        var consultations = 0
        var builds = 0
        var built: ScriptedTrustManager? = null
        val platform = ScriptedTrustManager { throw CertificateException("platform") }
        val manager = PlatformFirstTrustManager(platform) {
            consultations++
            built ?: ScriptedTrustManager().also { newManager ->
                built = newManager
                builds++
            }
        }

        manager.checkServerTrusted(chain, authType)
        manager.checkServerTrusted(chain, authType)

        assertEquals(2, consultations)
        assertEquals(1, builds)
    }

    @Test
    fun theIssuerQuestionIsAnsweredByThePlatformManagerAlone() {
        // Asking for issuers must not wake the fallback: the question is about what the platform
        // trusts, and nothing on the handshake path consults the answer anyway.
        val issuers = arrayOf<X509Certificate>()
        val platform = ScriptedTrustManager(issuers = issuers)
        val manager = PlatformFirstTrustManager(platform) {
            throw AssertionError("the fallback must not be built to answer an issuer question")
        }

        assertSame(issuers, manager.acceptedIssuers)
    }
}

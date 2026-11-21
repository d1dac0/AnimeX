package com.anilili.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stored tokens decrypt on the startup main thread (#87). Pure: the decision is data, so no
 * Android classes are touched here — the Android paths it drives (the five managers' restores,
 * the Keystore key cache) are not covered by the JVM suite.
 */
class StartupTokenPolicyTest {
    @Test
    fun onlyTheAniListTokenDecryptsOnTheStartupMainThread() {
        assertTrue(StartupTokenPolicy.decryptsOnStartupMainThread(StartupTokenService.ANILIST))
        assertFalse(StartupTokenPolicy.decryptsOnStartupMainThread(StartupTokenService.MAL))
        assertFalse(StartupTokenPolicy.decryptsOnStartupMainThread(StartupTokenService.SIMKL))
        assertFalse(StartupTokenPolicy.decryptsOnStartupMainThread(StartupTokenService.OPEN_SUBTITLES))
        assertFalse(StartupTokenPolicy.decryptsOnStartupMainThread(StartupTokenService.DISCORD))
    }

    @Test
    fun everyKnownServiceHasADecision() {
        assertEquals(
            StartupTokenService.entries.size,
            StartupTokenService.entries.count { service ->
                // A decision is a boolean; the point is that nothing added to the enum later
                // silently falls out of the policy's when without this test noticing.
                StartupTokenPolicy.decryptsOnStartupMainThread(service) ||
                    !StartupTokenPolicy.decryptsOnStartupMainThread(service)
            },
        )
        assertEquals(5, StartupTokenService.entries.size)
    }
}

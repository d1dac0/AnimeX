package com.anilili

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app root waits for the asynchronous settings read before composing, so a non-default
 * interface size, overscan or theme does not build and measure Home twice under the splash (#90).
 */
class MiruroRootSettingsWaitTest {
    @Test
    fun anUnfinishedReadInsideTheCapKeepsWaiting() {
        assertTrue(rootWaitsForSettings(settingsLoaded = false, waitedMillis = 0, capMillis = 1_000))
        assertTrue(rootWaitsForSettings(settingsLoaded = false, waitedMillis = 999, capMillis = 1_000))
    }

    @Test
    fun aLandedReadOpensTheGateAtOnce() {
        assertFalse(rootWaitsForSettings(settingsLoaded = true, waitedMillis = 0, capMillis = 1_000))
        assertFalse(rootWaitsForSettings(settingsLoaded = true, waitedMillis = 5_000, capMillis = 1_000))
    }

    /** A slow read must cost a default-shaped first frame, never a stuck splash. */
    @Test
    fun theCapOpensTheGateEvenWithoutTheRead() {
        assertFalse(rootWaitsForSettings(settingsLoaded = false, waitedMillis = 1_000, capMillis = 1_000))
        assertFalse(rootWaitsForSettings(settingsLoaded = false, waitedMillis = 10_000, capMillis = 1_000))
    }

    /** The root's wait reuses the intro's cap, so the two waits end together behind the splash. */
    @Test
    fun theCapIsTheOneTheIntroChoiceUses() {
        assertTrue(rootWaitsForSettings(settingsLoaded = false, waitedMillis = 500, capMillis = LaunchIntroSettingsWaitMillis))
        assertFalse(rootWaitsForSettings(settingsLoaded = false, waitedMillis = LaunchIntroSettingsWaitMillis, capMillis = LaunchIntroSettingsWaitMillis))
    }
}

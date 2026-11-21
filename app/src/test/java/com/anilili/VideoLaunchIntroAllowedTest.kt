package com.anilili

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLaunchIntroAllowedTest {
    @Test
    fun anOrdinaryTvLaunchMayPlayTheVideo() {
        assertTrue(videoLaunchIntroAllowed(tvFlavor = true, profileSwitchLaunch = false, launchHasDestination = false, lowCostTv = false))
    }

    @Test
    fun aLaunchAimedAtARouteOrLinkGetsTheMark() {
        // Watch Next starts the episode's player underneath the intro straight away.
        assertFalse(videoLaunchIntroAllowed(tvFlavor = true, profileSwitchLaunch = false, launchHasDestination = true, lowCostTv = false))
    }

    @Test
    fun aLowCostTelevisionGetsTheMark() {
        assertFalse(videoLaunchIntroAllowed(tvFlavor = true, profileSwitchLaunch = false, launchHasDestination = false, lowCostTv = true))
    }

    @Test
    fun aProfileSwitchGetsTheMark() {
        assertFalse(videoLaunchIntroAllowed(tvFlavor = true, profileSwitchLaunch = true, launchHasDestination = false, lowCostTv = false))
    }

    @Test
    fun theMobileFlavourNeverHasTheVideo() {
        assertFalse(videoLaunchIntroAllowed(tvFlavor = false, profileSwitchLaunch = false, launchHasDestination = false, lowCostTv = false))
    }
}

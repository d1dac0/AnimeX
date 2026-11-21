package com.anilili.ui.theme

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackdropTest {

    @Test
    fun `a wide picture on a tall phone keeps its middle`() {
        // 1920x1080 onto 540x1200: the full height, a centred 486-wide column.
        assertEquals(IntRect(717, 0, 1203, 1080), centreCrop(1920, 1080, 540, 1200))
    }

    @Test
    fun `a tall picture on a television keeps its middle band`() {
        assertEquals(IntRect(0, 577, 1080, 1185), centreCrop(1080, 1763, 960, 540))
    }

    @Test
    fun `a picture already the window's shape is used whole`() {
        assertEquals(IntRect(0, 0, 1920, 1080), centreCrop(1920, 1080, 960, 540))
    }

    private val windowLongSide = 1920f

    @Test
    fun aBlurredPictureIsBakedFromHalfTheWindowOnAFullCostDevice() {
        assertEquals(0.5f, backdropRenderScale(lowRam = false, lowCostTvEffects = false, blur = true, longSide = windowLongSide))
    }

    @Test
    fun aLowCostTelevisionBakesTheSmallBlurredPictureWithoutBeingLowRam() {
        // #48: the memory class alone misses cheap boxes with a roomy heap, so the profile's
        // answer scales the baked picture down the same way a low-RAM device does.
        assertEquals(1f / 3, backdropRenderScale(lowRam = false, lowCostTvEffects = true, blur = true, longSide = windowLongSide), 1e-6f)
    }

    @Test
    fun aSharpPictureIsCappedAtTheLowCostLongSideForALowCostTelevision() {
        assertEquals(960f / 1920f, backdropRenderScale(lowRam = false, lowCostTvEffects = true, blur = false, longSide = windowLongSide), 1e-6f)
    }

    @Test
    fun aSharpPictureOnAFullCostDeviceKeepsTheFullLongSideCap() {
        assertEquals(1600f / 1920f, backdropRenderScale(lowRam = false, lowCostTvEffects = false, blur = false, longSide = windowLongSide), 1e-6f)
    }

    @Test
    fun aSmallWindowIsNeverScaledUp() {
        // The sharp branch caps without upscaling; the blurred branch takes its fraction whatever
        // the window is, and renderAppBackdrop clamps the pixel size to at least one.
        assertEquals(1f, backdropRenderScale(lowRam = false, lowCostTvEffects = false, blur = false, longSide = 800f))
        assertEquals(1f / 3, backdropRenderScale(lowRam = true, lowCostTvEffects = false, blur = true, longSide = 120f), 1e-6f)
    }
}

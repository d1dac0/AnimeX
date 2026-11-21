package com.anilili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Coil memory-cache sizing and RGB_565 decisions (#89). The caller injects the environment
 * (`Runtime.maxMemory()`, `Build.VERSION.SDK_INT`, `AppGraph.isTv`, `AppGraph.isLowRamDevice`),
 * so the JVM suite drives the arithmetic deterministically — the Android wiring in
 * `MiruroApp.newImageLoader` itself is not covered here.
 */
class ImageLoaderMemoryPolicyTest {
    @Test
    fun aTelevisionCacheTakesTwelvePercentOfTheHeapTheProcessActuallyHas() {
        val heap = 256L * 1024 * 1024
        assertEquals((heap * 0.12).toLong(), ImageLoaderMemoryPolicy.coilMemoryCacheBytes(heap, isTv = true).toLong())
    }

    @Test
    fun aPhoneCacheTakesTwentyFivePercent() {
        val heap = 256L * 1024 * 1024
        assertEquals((heap * 0.25).toLong(), ImageLoaderMemoryPolicy.coilMemoryCacheBytes(heap, isTv = false).toLong())
    }

    @Test
    fun aSmallHeapKeepsItsShareRatherThanCollapsingToZero() {
        // A low-RAM box reporting a 64 MB heap: 12% of it is what a TV stick really gets to keep.
        val heap = 64L * 1024 * 1024
        assertEquals((heap * 0.12).toLong(), ImageLoaderMemoryPolicy.coilMemoryCacheBytes(heap, isTv = true).toLong())
    }

    @Test
    fun rgb565IsAllowedForTelevisionBelowApi26() {
        assertTrue(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 22, isTv = true, lowCost = false))
        assertTrue(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 25, isTv = true, lowCost = false))
    }

    @Test
    fun rgb565IsDroppedForTelevisionAtApi26UnlessTheDeviceIsLowCost() {
        assertFalse(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 26, isTv = true, lowCost = false))
        assertFalse(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 30, isTv = true, lowCost = false))
        assertTrue(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 30, isTv = true, lowCost = true))
    }

    @Test
    fun rgb565NeverAppliesOffTelevision() {
        assertFalse(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 22, isTv = false, lowCost = false))
        assertFalse(ImageLoaderMemoryPolicy.rgb565Allowed(sdkInt = 22, isTv = false, lowCost = true))
    }
}

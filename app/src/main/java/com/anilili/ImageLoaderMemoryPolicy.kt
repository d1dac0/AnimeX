package com.anilili

/**
 * Pure sizing decisions for [MiruroApp.newImageLoader], extracted so the JVM suite can hold them
 * (#89). Nothing here touches Android: the caller injects `Runtime.maxMemory()`,
 * `Build.VERSION.SDK_INT`, `AppGraph.isTv` and `AppGraph.isLowRamDevice`.
 */
internal object ImageLoaderMemoryPolicy {
    /** The shares the memory cache kept when it was sized from the memory classes (#89). */
    private const val TV_HEAP_SHARE = 0.12
    private const val PHONE_HEAP_SHARE = 0.25

    private const val MIN_SDK_WITHOUT_RGB565 = 26 // O: bitmap pixels move to GPU-resident hardware

    /**
     * #89: Coil's `maxSizePercent` multiplies the *large* memory class whenever `largeHeap` is
     * declared — and low-RAM devices ignore `largeHeap`, so on exactly the boxes that can least
     * afford it the declared share of the heap the process actually gets came out larger than
     * intended. Multiplying the same shares by `Runtime.maxMemory()` — the heap this process
     * really has, large where largeHeap applies and normal where it does not — keeps the share
     * true on every device. Coil's `maxSizeBytes` takes an `Int`; no Android heap share crosses
     * that line, and the coercion is here rather than at the call site so the arithmetic the
     * suite tests is the arithmetic that runs.
     */
    fun coilMemoryCacheBytes(maxHeapBytes: Long, isTv: Boolean): Int =
        (maxHeapBytes * if (isTv) TV_HEAP_SHARE else PHONE_HEAP_SHARE)
            .toLong()
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * #89: RGB_565 halves the bytes a decoded poster holds, at a colour-fidelity cost the issue
     * accepts. On API 22-25 bitmaps live on the Java heap, where the saving matters most; from
     * API 26 decoded bitmaps are GPU-resident hardware bitmaps instead and the flag stops paying.
     * A low-cost device — `AppGraph.isLowRamDevice`, the same hardware-pressure hint background
     * work already throttles on — keeps the flag past 26, because what heap it does have stays
     * worth sparing. TV only: the phone flavour is not built in this checkout.
     */
    fun rgb565Allowed(sdkInt: Int, isTv: Boolean, lowCost: Boolean): Boolean =
        isTv && (sdkInt < MIN_SDK_WITHOUT_RGB565 || lowCost)
}

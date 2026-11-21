package com.anilili.diagnostics

import android.os.SystemClock

/**
 * Decides whether a repeating snapshot may be collected now, or has arrived too soon after the
 * last accepted one (#82).
 *
 * `onTrimMemory` arrives in bursts under memory pressure during playback — exactly when the device
 * is already struggling — and every call used to collect a full system snapshot. The lifecycle
 * call site does not pass the trim *level* to [DiagnosticsLog.snapshot], only a label, so the
 * throttle is keyed by that label; one snapshot per label per window is strictly within the
 * "at most one per level" budget the issue asks for.
 *
 * Pure apart from the injected clock, so the JVM suite can drive it deterministically. The map is
 * guarded by the receiver: [accepts] runs on whichever thread the snapshot call arrives on, which
 * is Main for every lifecycle call site, and must not corrupt state when a background report
 * generation races it.
 */
internal class SnapshotThrottle(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lastAcceptedAt = HashMap<String, Long>()

    /** True when a snapshot under [key] may be collected now; records the acceptance when so. */
    @Synchronized
    fun accepts(key: String): Boolean {
        val now = nowMs()
        val last = lastAcceptedAt[key]
        if (last != null && now - last < minIntervalMs) return false
        lastAcceptedAt[key] = now
        return true
    }
}

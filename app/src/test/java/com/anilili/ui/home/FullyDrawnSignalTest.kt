package com.anilili.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report decision behind the launch's fully-drawn signal (#85). Compose and UiAutomator do not
 * run on the JVM, so the wiring itself is pinned only by the decision here: the report happens
 * exactly once, and only when both an Activity has resumed and the first Home load has settled.
 */
class FullyDrawnSignalTest {
    @Test
    fun settledAndResumedReports() {
        assertTrue(FullyDrawnSignal.shouldReport(activityResumed = true, homeSettled = true, alreadyReported = false))
    }

    @Test
    fun resumeAloneDoesNotReport() {
        // The first frame is the intro's black frame; reporting there is what the old benchmark
        // measured and what the signal exists to replace.
        assertFalse(FullyDrawnSignal.shouldReport(activityResumed = true, homeSettled = false, alreadyReported = false))
    }

    @Test
    fun settleAloneDoesNotReport() {
        // A HomeViewModel can settle before any Activity has resumed; there is no window to report.
        assertFalse(FullyDrawnSignal.shouldReport(activityResumed = false, homeSettled = true, alreadyReported = false))
    }

    @Test
    fun aSecondSettleNeverReReports() {
        // One report per process: Home refreshes and activity recreation arrive after the first.
        assertFalse(FullyDrawnSignal.shouldReport(activityResumed = true, homeSettled = true, alreadyReported = true))
    }

    @Test
    fun neitherHalfReports() {
        assertFalse(FullyDrawnSignal.shouldReport(activityResumed = false, homeSettled = false, alreadyReported = false))
    }
}

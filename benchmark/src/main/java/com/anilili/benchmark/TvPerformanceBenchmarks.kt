package com.anilili.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvPerformanceBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithBaselineProfile() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // The app reports fully drawn when the first Home load settles (#85), so the iteration has
        // to stay open until real Home content is on screen: ending at the package window alone —
        // the old wait — measured only timeToInitialDisplayMs, the intro's black frame, and never
        // saw the report. A rail title only exists once the settle has happened, and it also
        // replaces waitForIdle(), which returns while the intro still holds every key.
        check(waitTvHomeDrivable(device)) { "Home content never became drivable" }
    }

    @Test
    fun tvHomeDpadFocus() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            // Same wait as the cold benchmark (#85): a rail title, not waitForIdle(). MainActivity
            // drops every key while the intro plays and "Who's watching?" is due, and idle is
            // exactly when that is true, so the old setup's D-pad pass recorded nothing.
            check(waitTvHomeDrivable(device)) { "Home content never became drivable" }
        },
    ) {
        repeat(12) { device.pressDPadRight() }
        repeat(12) { device.pressDPadLeft() }
        repeat(4) { device.pressDPadDown() }
        repeat(12) { device.pressDPadRight() }
        device.waitForIdle()
    }
}

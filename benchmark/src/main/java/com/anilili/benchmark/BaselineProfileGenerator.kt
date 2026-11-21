package com.anilili.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateTvCriticalJourneys() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        // Each pass now drives four launches — Home, the title page, Search and Settings — so the
        // default of up to fifteen passes costs far more than rules that have stopped changing
        // after the second are worth.
        maxIterations = 5,
        stableIterations = 2,
    ) {
        // Cold start and the Home focus pass — one launch, one pass through the intro. The wait
        // names a rail title rather than trusting waitForIdle(), which returns while the intro is
        // still holding every key: MainActivity drops them until ProfilesUi.holdsLaunchInput
        // clears, and idle is exactly when that is true (#85).
        pressHome()
        startActivityAndWait()
        check(waitTvHomeDrivable(device)) { "Home content never became drivable" }

        // Compile the Compose focus/navigation paths that every remote-driven TV session uses.
        repeat(5) { device.pressDPadRight() }
        repeat(5) { device.pressDPadLeft() }
        repeat(3) { device.pressDPadDown() }
        repeat(8) { device.pressDPadRight() }
        repeat(4) { device.pressDPadLeft() }
        device.waitForIdle()

        // The title page, then the player: its first frame, the controls over a playing video and
        // one panel. These are the paths a television runs interpreted until the JIT catches up —
        // the ones where a dropped frame is noticed — and the home screen alone never reached them.
        // The route extra is the same one the app's own deep links use. The launch replays the
        // intro over the deep-linked page, so the page's primary action is waited for and then the
        // input ceiling is waited out before the first key (#85).
        startActivityAndWait { intent -> intent.putExtra(ROUTE_EXTRA, PROFILE_TITLE_ROUTE) }
        check(waitTvLaunchReleased(device, tvDetailPrimaryAction(), TvHomeContentWaitMs)) {
            "The title page never reached its primary action"
        }
        device.pressDPadCenter() // the title page's primary action: play or continue
        Thread.sleep(PLAYBACK_SETTLE_MS)
        device.pressDPadDown() // raise the controls
        Thread.sleep(CONTROL_STEP_MS)
        repeat(4) {
            device.pressDPadRight()
            Thread.sleep(CONTROL_STEP_MS)
        }
        device.pressDPadCenter() // the servers panel
        Thread.sleep(PANEL_SETTLE_MS)
        device.pressBack()
        Thread.sleep(CONTROL_STEP_MS)
        device.pressBack()
        device.waitForIdle()

        // Back from Detail to Home — the navigation every TV session ends with, which no journey
        // covered before (#85). The layers between the player and Home (a panel, the controls, the
        // player, the page) make the number of presses vary, so they repeat until Home's rail
        // titles answer and the title's own primary action has left the hierarchy.
        check(pressBackToHome(device, tvDetailPrimaryAction(), BACK_PRESSES)) {
            "Back from the title page never reached Home"
        }

        // Search: the native field, a typed query and the results it draws (#85). The query is set
        // on the editor directly — the same TextWatcher bridge a keystroke takes — because the
        // system IME's own keys are not this app's code to profile; the settle covers the
        // debounced request and the results.
        startActivityAndWait { intent -> intent.putExtra(ROUTE_EXTRA, SEARCH_ROUTE) }
        check(waitTvLaunchReleased(device, tvSearchField(), TvHomeContentWaitMs)) {
            "The search field never appeared"
        }
        check(typeTvSearchQuery(device, SEARCH_QUERY)) { "The search field never took the query" }
        Thread.sleep(TvSearchSettleMs)
        check(pressBackToHome(device, tvOpenFiltersButton(), BACK_PRESSES)) {
            "Back from Search never reached Home"
        }

        // Settings, and the app's own keyboard: its search pane raises TvOnScreenKeyboard, whose
        // first key holds focus the moment the pane opens, so OK types into it (#85). The pane is
        // opened by tapping the rail's search row — the cursor's landing spot on Settings arrival
        // is not fixed, and this journey is for the keyboard path, not the focus path; the focus
        // paths are what the Home pass above compiles.
        startActivityAndWait { intent -> intent.putExtra(ROUTE_EXTRA, SETTINGS_ROUTE) }
        check(waitTvLaunchReleased(device, tvSettingsSearchBar(), TvHomeContentWaitMs)) {
            "Settings never reached its search row"
        }
        device.findObject(tvSettingsSearchBar())?.click()
        check(waitTvOnScreenKeyboard(device, TvHomeContentWaitMs)) {
            "The settings search keyboard never appeared"
        }
        device.pressDPadCenter() // the first key, already focused, types into the query
        Thread.sleep(TvKeyboardKeySettleMs)
        check(pressBackToHome(device, tvOnScreenKeyboardKey(), BACK_PRESSES)) {
            "Back from Settings never reached Home"
        }

        device.waitForIdle()
    }
}

internal const val TARGET_PACKAGE = "com.miruronative"

private const val ROUTE_EXTRA = "com.miruronative.extra.ROUTE"

/** A long-finished series every source carries, so the journey does not depend on a new episode. */
private const val PROFILE_TITLE_ROUTE = "detail/166873"

private const val SEARCH_ROUTE = "search"
private const val SETTINGS_ROUTE = "settings"

/** A query every source answers, so the results the journey waits past are real ones. */
private const val SEARCH_QUERY = "one piece"

/** The most Back that ever sits between the player and Home: panel, controls, player, page. */
private const val BACK_PRESSES = 5

private const val PLAYBACK_SETTLE_MS = 25_000L
private const val PANEL_SETTLE_MS = 3_000L
private const val CONTROL_STEP_MS = 500L

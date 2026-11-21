package com.anilili.benchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * What every TV journey waits for before it sends a key.
 *
 * #85: MainActivity drops every key while the launch intro plays and while "Who's watching?" is
 * due (`ProfilesUi.holdsLaunchInput`), and `waitForIdle()` returns during all of that — the system
 * is idle precisely because the remote is being held. Keys sent after an idle alone never landed,
 * so the focus passes the journeys were written for recorded nothing. Each wait below names a real
 * element of the screen it is about to drive, and each journey keeps a single launch through the
 * intro; no step relaunches the activity to get past it.
 */

/**
 * The rail titles TV Home draws once its catalogue has loaded. They are not in the hierarchy until
 * the intro has left, the profile picker (if any) has been answered and the data has landed —
 * exactly the moment the remote starts working — so a rail title is the "Home is drivable" marker.
 */
internal val TvHomeRailTitles =
    listOf("Continue watching", "Trending now", "Popular this season", "Latest releases", "Top rated")

/** "Who's watching?" marks a launch waiting for a profile choice; the remote is dropped until one. */
internal val TvProfilePickerTitles = listOf("Who's watching?", "Welcome! Who's watching?")

/** Settings' search bar, the first row of the TV settings rail; the same page answers to "Buscar ajustes". */
internal val TvSettingsSearchBarTitles = listOf("Search settings", "Buscar ajustes")

/**
 * How long a cold launch may take to reach drivable Home content. The intro alone can spend eight
 * seconds waiting for its first frame and then plays to its end; a slow box fetches the catalogue
 * behind that. Generous on purpose: a timeout here fails the journey, and there is no way for a
 * key sent earlier to do anything but land on a dropped queue.
 */
internal const val TvHomeContentWaitMs = 45_000L

/**
 * How long a launch can hold the remote after its content is already in the hierarchy. The video
 * intro can spend eight seconds waiting for its first frame, then plays the clip and leaves with a
 * fade, and "Who's watching?" opens its own window only once that has finished — all while a page
 * that composes underneath the intro (a deep link, or Home on a warm cache) is already findable.
 * No element marks the gate opening, so the ceiling is what bounds it: once this much has passed
 * over content that is present, the keys land.
 */
internal const val TvLaunchInputCeilingMs = 25_000L

/** How long a launch may sit on "Who's watching?" before the journey gives up on it. */
internal const val TvProfilePickerWaitMs = 15_000L

/** How long one Back press gets to show its effect before the next one is sent. */
internal const val TvBackStepMs = 3_000L

/** How long the search field may take to carry the text the journey set on it. */
internal const val TvSearchTextWaitMs = 5_000L

/** How long the search may take to run its debounced request and draw the results. */
internal const val TvSearchSettleMs = 12_000L

/** How long a typed key may take to reach the settings search results. */
internal const val TvKeyboardKeySettleMs = 2_000L

/**
 * The title page's primary action, whatever the page calls it: "Watch" (and the movie's
 * "Watch movie"), the resume's "Continue", "Continue E10" and "Continue movie". [By.text] over a
 * pattern is a full-string match, so each alternative is a whole label.
 */
internal const val TvDetailPrimaryActionPattern = "Watch|Watch movie|Continue E.*|Continue|Continue movie"

/** [By.text] over a [Pattern] is a full-string match, so each alternative is quoted, metacharacters and all. */
private fun byTextAlternatives(titles: List<String>) =
    By.text(Pattern.compile(titles.joinToString("|") { Pattern.quote(it) }))

/** The title page's primary action button, as a selector. */
internal fun tvDetailPrimaryAction(): BySelector = By.text(Pattern.compile(TvDetailPrimaryActionPattern))

/** Search's filter button — the screen's own marker that is not a result or a suggestion. */
internal fun tvOpenFiltersButton(): BySelector = By.desc("Open filters")

/** The settings rail's search row, which opens the pane this journey is here to compile. */
internal fun tvSettingsSearchBar(): BySelector = byTextAlternatives(TvSettingsSearchBarTitles)

/** The app-drawn keyboard's first key, the element whose presence marks the keyboard ready. */
internal fun tvOnScreenKeyboardKey(): BySelector = By.text("A")

/** The TV search field: the screen's one native [android.widget.EditText]. */
internal fun tvSearchField(): BySelector = By.clazz("android.widget.EditText")

/** Home's rail titles are present in the hierarchy. */
internal fun waitTvHomeRail(device: UiDevice, timeoutMs: Long): Boolean =
    device.wait(Until.hasObject(byTextAlternatives(TvHomeRailTitles)), timeoutMs)

/**
 * Drives the app from launch to a Home screen the remote can move on.
 *
 * Waits for Home content, then clears the profile picker when one is up — a launch with more than
 * one profile opens it over the rails and holds every key until someone is chosen; pressing the
 * highlighted profile is the choice a benchmark run wants. A single-profile launch, which is what
 * the benchmark device is expected to be, never shows it and the picker wait doubles as the wait
 * for the intro itself. A first-run setup ("Welcome! Who's watching?") is not driven; seed the
 * device before generating.
 */
internal fun waitTvHomeDrivable(device: UiDevice): Boolean {
    if (!waitTvHomeRail(device, TvHomeContentWaitMs)) {
        return false
    }
    if (device.wait(Until.hasObject(byTextAlternatives(TvProfilePickerTitles)), TvLaunchInputCeilingMs)) {
        device.pressDPadCenter()
    }
    return device.wait(Until.gone(byTextAlternatives(TvProfilePickerTitles)), TvProfilePickerWaitMs)
}

/**
 * Waits for [ready] on a launch that went straight to a screen — the ROUTE_EXTRA deep links — and
 * then waits the input ceiling out. The deep-linked page composes underneath the replayed intro,
 * so its own elements are in the hierarchy while every key is still being dropped; the ceiling is
 * the only wait that guarantees the gate has opened.
 */
internal fun waitTvLaunchReleased(device: UiDevice, ready: BySelector, timeoutMs: Long): Boolean {
    if (!device.wait(Until.hasObject(ready), timeoutMs)) {
        return false
    }
    Thread.sleep(TvLaunchInputCeilingMs)
    return true
}

/**
 * Back presses on the TV app until it has landed on Home: Home's rail titles are back AND
 * [leaving] — the marker of the screen the journey is escaping — has left the hierarchy, because
 * a page pushed over Home keeps it out of composition and the two together are the only honest
 * "we are on Home" the accessibility tree can offer. The presses repeat rather than being counted
 * out, because the layers between where a journey ends and Home (a panel, the player's controls,
 * a search pane) make the number of presses vary.
 */
internal fun pressBackToHome(device: UiDevice, leaving: BySelector, presses: Int): Boolean {
    repeat(presses) {
        device.pressBack()
        if (waitTvHomeRail(device, TvBackStepMs) && !device.hasObject(leaving)) {
            return true
        }
    }
    return false
}

/**
 * Types [query] into the search field without the system IME. [UiObject2.setText] reaches the
 * native editor's TextWatcher — the same bridge a keystroke takes, and the code worth profiling —
 * and the field's own text is waited for afterwards, so a silent no-op cannot pass for typing.
 */
internal fun typeTvSearchQuery(device: UiDevice, query: String): Boolean {
    val field = device.findObject(By.clazz("android.widget.EditText")) ?: return false
    field.setText(query)
    return device.wait(Until.hasObject(By.text(query)), TvSearchTextWaitMs)
}

/** The TV search field has appeared. */
internal fun waitTvSearchField(device: UiDevice, timeoutMs: Long): Boolean =
    device.wait(Until.hasObject(tvSearchField()), timeoutMs)

/** The TV settings page has drawn, marked by its rail's first row. */
internal fun waitTvSettingsPage(device: UiDevice, timeoutMs: Long): Boolean =
    device.wait(Until.hasObject(tvSettingsSearchBar()), timeoutMs)

/**
 * The app's own on-screen keyboard, drawn by Settings' search pane. Its first key — "A", uppercase,
 * exact — is the only element anywhere with that text, and it holds focus the moment the pane
 * opens, so its presence marks the keyboard ready to type on.
 */
internal fun waitTvOnScreenKeyboard(device: UiDevice, timeoutMs: Long): Boolean =
    device.wait(Until.hasObject(tvOnScreenKeyboardKey()), timeoutMs)

package com.anilili

import com.anilili.ui.nav.Routes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MainActivity.onStart runs the full remote-library refresh only when the viewer is not on or
 * heading into a player; a refresh skipped there is parked and taken at the next tab-route entry
 * (#78).
 */
class LibraryRefreshOnStartTest {
    /** The launcher's Continue Watching card and anilili://watch links cold-start into the player. */
    @Test
    fun aDeepLinkColdStartIntoWatchDoesNotRefresh() {
        assertFalse(shouldRefreshOnStart(route = "watch/16/auto/sub/1", sessionActive = false))
        assertFalse(shouldRefreshOnStart(route = Routes.WATCH, sessionActive = false))
    }

    /** Returning from the launcher or the screensaver lands in onStart with the route unchanged. */
    @Test
    fun returningToTheDownloadedPlayerDoesNotRefresh() {
        assertFalse(shouldRefreshOnStart(route = "download/16-auto-sub-1", sessionActive = false))
        assertFalse(shouldRefreshOnStart(route = Routes.DOWNLOAD, sessionActive = false))
    }

    /** A session still open — paused, buffering, an embed — is watching, whatever the route says. */
    @Test
    fun anOpenPlaybackSessionDoesNotRefresh() {
        assertFalse(shouldRefreshOnStart(route = Routes.HOME, sessionActive = true))
        assertFalse(shouldRefreshOnStart(route = null, sessionActive = true))
    }

    @Test
    fun anOrdinaryForegroundReturnRefreshes() {
        assertTrue(shouldRefreshOnStart(route = Routes.HOME, sessionActive = false))
        assertTrue(shouldRefreshOnStart(route = "detail/16", sessionActive = false))
        assertTrue(shouldRefreshOnStart(route = "notifications", sessionActive = false))
        assertTrue(shouldRefreshOnStart(route = null, sessionActive = false))
    }

    /** The Downloads tab shares the player prefix; the trailing slash is what keeps it out. */
    @Test
    fun theDownloadsTabIsABrowsingPageNotAPlayer() {
        assertTrue(shouldRefreshOnStart(route = Routes.DOWNLOADS, sessionActive = false))
        assertTrue(routeIsPlayerScreen(Routes.DOWNLOADS).not())
    }

    @Test
    fun playerRoutesAreRecognisedInPatternAndInstantiatedForm() {
        assertTrue(routeIsPlayerScreen("watch/16/auto/sub/1"))
        assertTrue(routeIsPlayerScreen(Routes.WATCH))
        assertTrue(routeIsPlayerScreen("download/16-auto-sub-1"))
        assertTrue(routeIsPlayerScreen(Routes.DOWNLOAD))
        assertFalse(routeIsPlayerScreen(null))
        assertFalse(routeIsPlayerScreen(Routes.HOME))
        assertFalse(routeIsPlayerScreen(Routes.DETAIL))
        assertFalse(routeIsPlayerScreen(Routes.tabRoute(Routes.SEARCH_DESTINATION)))
    }
}

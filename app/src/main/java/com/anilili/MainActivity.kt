package com.anilili

import com.anilili.ui.theme.appCanvas
import com.anilili.ui.theme.canvasColor
import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.ui.random.RandomAnimeButton
import com.anilili.ui.tour.TourTarget
import com.anilili.ui.tour.tourTarget
import com.anilili.ui.random.RandomAnimeDialog
import com.anilili.ui.random.RandomAnimeViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anilili.data.ProviderBenchmark
import com.anilili.data.announce.AnnouncementManager
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.device.isLeanbackLaunch
import com.anilili.data.library.AUTO_PROVIDER
import com.anilili.data.library.LibraryStore
import com.anilili.data.reminder.AiringPollScheduler
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.remote.HanimeBridge
import com.anilili.data.remote.PaheBridge
import com.anilili.data.remote.canHostPipeEngine
import com.anilili.data.settings.MenuLanguage
import com.anilili.data.settings.SettingsStore
import com.anilili.data.update.UpdateManager
import com.anilili.diagnostics.CrashReportDialog
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsJankMonitor
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.chromiumRestartIsFatal
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.PlaybackService
import com.anilili.playback.PlaybackStatus
import com.anilili.playback.playableOfflineEpisodeId
import com.anilili.playback.supportsMiniPlayer
import com.anilili.ui.AllAnimeCaptchaHost
import com.anilili.ui.CloudflareChallengeHost
import com.anilili.ui.FlixcloudResolverWebView
import com.anilili.ui.HanimeResolverWebView
import com.anilili.ui.PaheResolverWebView
import com.anilili.ui.adaptive.FocusFillContentColor
import com.anilili.ui.adaptive.FocusRingGap
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.adaptive.AppOverscan
import com.anilili.ui.adaptive.AppUiScale
import com.anilili.ui.adaptive.ConfirmExitOnBack
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.TvRefreshRequest
import com.anilili.ui.adaptive.UnscaledUi
import com.anilili.ui.adaptive.dpadClickable
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberAppDeviceProfile
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.adaptive.rememberTvRefreshRequest
import com.anilili.ui.adaptive.shouldUseLowCostTvEffects
import com.anilili.ui.adaptive.tvFocusHandoff
import com.anilili.ui.announce.AnnouncementHost
import com.anilili.ui.components.DefaultNavEnter
import com.anilili.ui.components.DefaultNavExit
import com.anilili.ui.components.TabSwipeState
import com.anilili.ui.components.rememberTabSwipeState
import com.anilili.ui.components.swipeBetweenTabs
import com.anilili.ui.components.AnililiWordmark
import com.anilili.ui.components.AppChromeTakeover
import com.anilili.ui.components.LocalAppChromeTakeover
import com.anilili.ui.components.AppLaunchSplash
import com.anilili.ui.components.VideoLaunchSplash
import com.anilili.ui.components.VideoLaunchSplashExitMillis
import com.anilili.ui.components.FloatingNavBarExpandedHeight
import com.anilili.ui.components.FloatingNavBarMargin
import com.anilili.ui.components.FloatingNavItem
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.anilili.ui.components.FloatingNavigationBar
import com.anilili.ui.components.LaunchSplashFadeMillis
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.LocalAppChromeReveal
import com.anilili.ui.components.LocalAppChromeVisible
import com.anilili.ui.components.LocalSystemUses24Hour
import com.anilili.ui.components.LocalTimeOfDayFormatter
import com.anilili.ui.components.TvTopNavigationHeight
import com.anilili.ui.components.TvTopNavigationInset
import com.anilili.ui.components.rememberSystemUses24Hour
import com.anilili.ui.components.timeOfDayFormatter
import com.anilili.ui.detail.DetailScreen
import com.anilili.ui.downloads.DownloadsScreen
import com.anilili.ui.home.HomeScreen
import com.anilili.errors.AnililiError
import com.anilili.errors.report
import com.anilili.ui.nav.AnililiLink
import com.anilili.ui.nav.Routes
import com.anilili.ui.notifications.NotificationsScreen
import com.anilili.ui.profile.ProfileScreen
import com.anilili.ui.schedule.ScheduleScreen
import com.anilili.ui.search.SearchScreen
import com.anilili.ui.settings.SettingsScreen
import com.anilili.ui.settings.UpdatePromptHost
import com.anilili.ui.theme.MiruroTheme
import com.anilili.ui.watch.DownloadedEpisodeScreen
import com.anilili.ui.watch.WatchScreen
import com.anilili.ui.watch.episodeToHandBack
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.glassEnabled

class MainActivity : FragmentActivity() {
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)
    private var pendingLink by mutableStateOf<AnililiLink?>(null)
    private var pictureInPictureReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticsLog.event("MainActivity.onCreate start savedState=${savedInstanceState != null}")
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.decorView.setBackgroundColor(Color.BLACK)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Before the activity starts, so a result for a recreated activity is still delivered.
        com.anilili.playback.ExternalPlaybackReturns.register(this)
        DiagnosticsJankMonitor.install(this)
        window.decorView.setBackgroundColor(Color.BLACK)
        DiagnosticsLog.snapshot(this, "MainActivity.afterSuper")
        DiagnosticsLog.event(
            "MainActivity intent action=${intent.action ?: "none"} " +
                "data=${intent.dataString ?: "none"} categories=${intent.categories?.joinToString() ?: "none"} " +
                "routeExtra=${intent.getStringExtra(Routes.EXTRA_ROUTE) ?: "none"}",
        )
        // A launch from a television home screen is the one signal that reaches boxes running a
        // leanback launcher over a stock handheld image, where the ui mode says NORMAL forever.
        // Recorded rather than read live: the categories only arrive on a launcher start.
        if (isLeanbackLaunch(intent.categories)) SettingsStore.recordLeanbackLaunch()
        DiagnosticsLog.watchFirstDraw(window.decorView, "MainActivity")
        val profileSwitchLaunch = intent.getBooleanExtra(
            com.anilili.data.profiles.ProfileRestartActivity.EXTRA_PROFILE_SWITCHED,
            false,
        )
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        // Only a fresh activity: a recreated one was already taken where the link pointed.
        if (savedInstanceState == null) readLink(intent)
        // A fresh activity always opens on the splash, even in a process an earlier one left behind.
        if (savedInstanceState == null) com.anilili.ui.profiles.ProfilesUi.resetLaunchIntro()
        // "Who's watching?" on every launch, once there is more than one profile to choose from —
        // or when the profile in use is locked, which asks for its PIN even when it is the only one.
        // Not after a switch (the viewer has just chosen, PIN and all), not when the activity is only
        // being recreated, and not for a launch aimed at an episode (the launcher's Continue
        // Watching card belongs to the profile that is already active).
        if (savedInstanceState == null && !profileSwitchLaunch && pendingRoute == null &&
            (com.anilili.data.profiles.Profiles.profiles.value.size > 1 ||
                com.anilili.data.profiles.Profiles.active?.locked == true)
        ) {
            com.anilili.ui.profiles.ProfilesUi.showPicker(atLaunch = true)
        }
        DiagnosticsLog.event("MainActivity pendingRoute=${pendingRoute ?: "none"}")
        // Taken now, before the root consumes the route or link: a launch aimed at an episode (the
        // launcher's Watch Next card, an anilili://watch link) starts its player underneath the
        // intro straight away, and the intro's own ExoPlayer would then compete with it for the
        // decoder on a single-decoder stick, both soundtracks playing. Low-memory boxes get the
        // mark for the same reason the rest of the app takes its low-cost path on them.
        val launchHasDestination = pendingRoute != null || pendingLink != null
        val launchActivityManager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
        val launchLowCostTv = shouldUseLowCostTvEffects(
            isTv = BuildConfig.FLAVOR == "tv",
            isLowRamDevice = launchActivityManager?.isLowRamDevice == true,
            memoryClassMb = launchActivityManager?.memoryClass,
        )
        val videoIntroAllowed = videoLaunchIntroAllowed(
            tvFlavor = BuildConfig.FLAVOR == "tv",
            profileSwitchLaunch = profileSwitchLaunch,
            launchHasDestination = launchHasDestination,
            lowCostTv = launchLowCostTv,
        )
        if (BuildConfig.FLAVOR == "tv" && !videoIntroAllowed) {
            DiagnosticsLog.event(
                "Video intro skipped profileSwitch=$profileSwitchLaunch " +
                    "destination=$launchHasDestination lowCostTv=$launchLowCostTv",
            )
        }
        openAnnouncement(intent)
        applyBenchmarkPin(intent)
        handleAuthRedirect(intent)
        DiagnosticsLog.event("MainActivity.setContent start")
        setContent {
            LaunchedEffect(Unit) {
                DiagnosticsLog.event("MainActivity content composed")
            }
            MiruroTheme {
                // The window shows through during transitions and before first draw, so it follows
                // the Appearance canvas rather than staying the AMOLED black it starts as.
                val canvas = MaterialTheme.colorScheme.background.toArgb()
                SideEffect { window.decorView.setBackgroundColor(canvas) }
                // Outside everything that measures, so the viewer's chosen interface size and
                // overscan reach every screen from one place (the size reaches dialogs and popups too).
                AppUiScale { AppOverscan {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        var showLaunchSplash by rememberSaveable { mutableStateOf(true) }
                        val launchSplashState = remember { MutableTransitionState(showLaunchSplash) }
                        launchSplashState.targetState = showLaunchSplash
                        // "Who's watching?" waits until the splash has finished leaving, not just
                        // started to: its dialog window draws above the activity, so opening it on
                        // the exit put it over the last of the intro while the intro's sound played on.
                        val launchSplashGone = launchSplashState.isIdle && !launchSplashState.currentState
                        LaunchedEffect(launchSplashGone) {
                            if (launchSplashGone) com.anilili.ui.profiles.ProfilesUi.markLaunchIntroDone()
                        }
                        Box(Modifier.fillMaxSize()) {
                            MiruroRoot(
                                inPictureInPicture = inPictureInPicture,
                                onPictureInPictureReadyChanged = ::setPictureInPictureReady,
                                pendingRoute = pendingRoute,
                                onRouteConsumed = { pendingRoute = null },
                                pendingLink = pendingLink,
                                onLinkConsumed = { pendingLink = null },
                            )
                            AllAnimeCaptchaHost()
                            CloudflareChallengeHost()
                            AnnouncementHost()
                            var crashReport by remember { mutableStateOf(CrashReporter.pendingReport()) }
                            crashReport?.let { report ->
                                CrashReportDialog(
                                    report = report,
                                    onAccepted = {
                                        // The report is now in the user-controlled Android share flow.
                                        CrashReporter.clear()
                                        crashReport = null
                                    },
                                    onDiscard = {
                                        CrashReporter.clear()
                                        crashReport = null
                                    },
                                )
                            }
                            // Over the app, under the intro: keeps Home out of sight between the
                            // intro leaving and "Who's watching?" drawing its own window.
                            com.anilili.ui.profiles.LaunchPickerCover()
                            // The TV flavor's video intro can be turned off in Settings. Settings load
                            // asynchronously, so the choice waits for them rather than starting one
                            // intro and swapping to the other; the mobile flavor has only the mark.
                            // See videoLaunchIntroAllowed for the launches that never get the video.
                            val videoIntro by produceState<Boolean?>(
                                initialValue = if (videoIntroAllowed) null else false,
                            ) {
                                if (value == null) {
                                    value = withTimeoutOrNull(LaunchIntroSettingsWaitMillis) {
                                        SettingsStore.awaitLoaded()
                                        SettingsStore.videoLaunchIntro.value
                                    } ?: false
                                }
                            }
                            AnimatedVisibility(
                                visibleState = launchSplashState,
                                modifier = Modifier.fillMaxSize(),
                                // The video intro leaves by pushing through towards the viewer while the
                                // app shows through it; the mark's splash keeps its short fade.
                                exit = if (videoIntro == true) {
                                    fadeOut(tween(VideoLaunchSplashExitMillis, easing = FastOutSlowInEasing)) +
                                        scaleOut(
                                            tween(VideoLaunchSplashExitMillis, easing = FastOutSlowInEasing),
                                            targetScale = 1.15f,
                                        )
                                } else {
                                    fadeOut(tween(LaunchSplashFadeMillis))
                                },
                            ) {
                                when (videoIntro) {
                                    // The window's own colour, which the mark's splash also opens on.
                                    null -> Box(Modifier.fillMaxSize().background(ComposeColor(0xFF0B0B0F)))
                                    true -> VideoLaunchSplash(onFinished = { showLaunchSplash = false })
                                    false -> AppLaunchSplash(onFinished = { showLaunchSplash = false })
                                }
                            }
                        }
                    }
                } }
            }
        }
        DiagnosticsLog.event("MainActivity.setContent complete")
        window.decorView.post {
            DiagnosticsLog.event(
                "MainActivity decor after setContent attached=${window.decorView.isAttachedToWindow} " +
                    "shown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height} " +
                    "visibility=${window.decorView.visibilityName()} focus=${window.decorView.hasWindowFocus()}",
            )
        }
        lifecycleScope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                DiagnosticsLog.event("PlaybackStatus.isPlaying=$playing")
                if (playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // Android 12 and up enters the mini player from these params rather than from any call of
        // ours, so turning the setting off has to reach them while the episode is still on screen.
        // Leaving that until the next episode would answer the switch one episode late.
        lifecycleScope.launch {
            SettingsStore.pictureInPicture.collect { applyPictureInPictureParams() }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLog.event("MainActivity.onStart")
        PlaybackService.allowMediaButtonResume()
        LibraryStore.refreshRemoteLibrary()
        // A save the account never received — made offline, or during a service outage — is
        // re-offered here. No-ops unless something is actually outstanding.
        LibraryStore.retryPendingSavedSync()
    }

    override fun onResume() {
        super.onResume()
        DiagnosticsLog.event("MainActivity.onResume")
        DiagnosticsLog.snapshot(this, "MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        DiagnosticsLog.event("MainActivity.onPause")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Volume stays live: the video intro plays with sound.
        val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (!volume && com.anilili.ui.profiles.ProfilesUi.holdsLaunchInput) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        readLink(intent)
        applyBenchmarkPin(intent)
        // singleTop: a viewer who returns to a running app from the leanback home screen arrives
        // here rather than in onCreate, and on a box that fits no other signal this is the launch
        // that would otherwise be the only one to prove the device is a television.
        if (isLeanbackLaunch(intent.categories)) SettingsStore.recordLeanbackLaunch()
        openAnnouncement(intent)
        DiagnosticsLog.event(
            "MainActivity.onNewIntent pendingRoute=${pendingRoute ?: "none"} " +
                "action=${intent.action ?: "none"} data=${intent.dataString ?: "none"}",
        )
        handleAuthRedirect(intent)
    }

    /**
     * Takes an `anilili://` link the intent carries (from [AnililiLinkActivity], or adb) to
     * [pendingLink], or tells the viewer it can't be opened. Intents carrying anything else are
     * left alone: the sign-in redirects arrive here as data too.
     */
    private fun readLink(intent: Intent?) {
        val text = intent?.dataString ?: return
        if (!intent.data?.scheme.equals(AnililiLink.SCHEME, ignoreCase = true)) return
        // Reopening the app from Recents replays the intent that first started its task. That
        // link has been opened once; doing it again would restart an episode the viewer had left.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (val reading = AnililiLink.read(text)) {
            is AnililiLink.Reading.Read -> {
                // Never the search term: diagnostics bundles leave the device.
                val logged = (reading.link as? AnililiLink.Search)?.let { "${AnililiLink.SCHEME}://search" }
                    ?: reading.link.toUrl()
                DiagnosticsLog.event("Link received $logged")
                pendingLink = reading.link
            }
            is AnililiLink.Reading.Refused -> {
                val failure = AnililiError.LINK_UNKNOWN.report(detail = reading.why)
                Toast.makeText(this, failure.withCode(), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Opens the announcement a notification tap named, if it named one.
     *
     * Both entry points call this: a tap can either launch the process cold or, far more often,
     * arrive at a singleTop activity that is already running. Missing the second would make the
     * dialog appear only for people who had the app closed.
     */
    private fun openAnnouncement(intent: Intent?) {
        AnnouncementManager.openFromNotification(
            this,
            intent?.getStringExtra(AnnouncementManager.EXTRA_ANNOUNCEMENT_ID),
        )
    }

    /**
     * Debug-only: pin one provider for the next resolve so a benchmark measures the server it
     * names. [ProviderBenchmark.pinnedProviderOrNull] is gated on BuildConfig.DEBUG, so a release
     * build ignores the extra entirely; clearing it on an absent extra keeps a stale pin from
     * leaking into ordinary navigation.
     */
    private fun applyBenchmarkPin(intent: Intent?) {
        // Debug builds: `--es com.anilili.debug.PREVIEW_CHANGELOG "## A\n- b"` opens the update
        // prompt with those notes (escaped newlines unescaped), to check a changelog's layout.
        // `file:<name>` reads them from the app's files directory instead, which is how emoji
        // survive the trip (write the file with `run-as`).
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra(EXTRA_PREVIEW_CHANGELOG)?.let { notes ->
                val text = if (notes.startsWith("file:")) {
                    runCatching { java.io.File(filesDir, notes.removePrefix("file:")).readText() }.getOrDefault("")
                } else {
                    notes.replace("\\n", "\n")
                }
                com.anilili.data.update.UpdateManager.previewForDebug(text)
            }
        }
        val pin = intent?.getStringExtra(ProviderBenchmark.EXTRA_PIN)?.takeIf { it.isNotBlank() }
        ProviderBenchmark.pinnedProvider = pin
        ProviderBenchmark.catalogTtlMs =
            intent?.getLongExtra(ProviderBenchmark.EXTRA_CATALOG_TTL, 0L)?.takeIf { it > 0L }
        ProviderBenchmark.forceCatalogRefresh =
            intent?.getBooleanExtra(ProviderBenchmark.EXTRA_CATALOG_REFRESH, false) == true
        if (pin != null) DiagnosticsLog.event("Benchmark provider pinned provider=$pin")
        // Sticky, unlike the pin: a simulated block has to survive the navigation a test makes
        // inside it. Passing the extra empty lifts it.
        intent?.getStringExtra(ProviderBenchmark.EXTRA_DNS_SINKHOLE)?.let { raw ->
            ProviderBenchmark.dnsSinkhole = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            DiagnosticsLog.event("Benchmark DNS sinkhole hosts=${ProviderBenchmark.dnsSinkhole.joinToString()}")
        }
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (!AuthManager.isRedirect(url)) return
        DiagnosticsLog.event("Auth redirect received")
        AuthManager.extractToken(url)?.let { token ->
            AuthManager.setToken(token)
            // Same switch as ProfileViewModel.onLoggedIn: one active service, and the outgoing
            // account's snapshot dropped before the incoming one's first refresh can inherit it.
            MalAuthManager.logout()
            LibraryStore.clearRemoteLibrary()
            LibraryStore.syncSavedToRemote()
            LibraryStore.refreshRemoteLibrary(force = true)
            pendingRoute = Routes.MORE
            DiagnosticsLog.event("Auth redirect accepted")
        }
    }

    override fun onStop() {
        super.onStop()
        DiagnosticsLog.event("MainActivity.onStop")
        PlaybackService.pauseActivePlayback()
        // The library encodes and writes its lists off the main thread now (#65); hold the
        // stop briefly so the last save still lands ahead of Android's queued-write wait.
        LibraryStore.flushPendingWrites()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticsLog.event("MainActivity.onDestroy finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagnosticsLog.event(
            "MainActivity.onWindowFocusChanged hasFocus=$hasFocus " +
                "decorShown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height}",
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DiagnosticsLog.event(
            "MainActivity.onConfigurationChanged orientation=${newConfig.orientation} " +
                "screenDp=${newConfig.screenWidthDp}x${newConfig.screenHeightDp} uiMode=${newConfig.uiMode}",
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        DiagnosticsLog.event("PictureInPicture changed active=$isInPictureInPictureMode")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O until Build.VERSION_CODES.S) return
        if (!pictureInPictureReady || inPictureInPicture) return
        // Below Android 12 the window is entered by hand, so this is where the switch is read.
        // Declining leaves the activity to stop as usual, and onStop pauses the episode.
        if (!SettingsStore.pictureInPicture.value) {
            DiagnosticsLog.event("PictureInPicture declined by setting")
            return
        }
        enterPictureInPicture()
    }

    private fun setPictureInPictureReady(ready: Boolean) {
        if (pictureInPictureReady == ready) return
        pictureInPictureReady = ready
        applyPictureInPictureParams()
    }

    /**
     * Publishes whether this activity is currently a mini player candidate.
     *
     * Both halves matter: an episode has to be on screen, and the viewer has to want the window at
     * all. `setAutoEnterEnabled(false)` is what actually withholds it on Android 12 and up — there
     * is no call to skip there, because the system enters as the app leaves the foreground.
     */
    private fun applyPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        val autoEnter = pictureInPictureReady && SettingsStore.pictureInPicture.value
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(autoEnter)
                        setSeamlessResizeEnabled(true)
                    }
                }
                .build()
            setPictureInPictureParams(params)
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture params failed", it) }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }.onSuccess { entered ->
            DiagnosticsLog.event("PictureInPicture manual enter accepted=$entered")
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture enter failed", it) }
    }

    private fun supportsPictureInPicture(): Boolean = supportsMiniPlayer(this)

}

private fun View.visibilityName(): String = when (visibility) {
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    View.GONE -> "gone"
    else -> visibility.toString()
}

private enum class Tab(
    val route: String,
    private val englishLabel: String,
    private val spanishLabel: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", "Inicio", Icons.Default.Home),
    SEARCH(Routes.SEARCH, "Search", "Buscar", Icons.Default.Search),
    SCHEDULE(Routes.SCHEDULE, "Schedule", "Calendario", Icons.Default.DateRange),
    // "My List" rather than "Library", because that is already what the rest of the app calls it:
    // every poster's long press, both players' overflow and the television's control row all offer
    // "Add to My List", and the destination those additions land in was the one place still calling
    // itself something else. A shelf of books is also the wrong metaphor for what the page holds —
    // it is watchlist statuses and progress, not a collection of things owned.
    MORE(Routes.MORE, "My List", "Mi lista", Icons.Default.CollectionsBookmark),
    DOWNLOADS(Routes.DOWNLOADS, "Downloads", "Descargas", Icons.Default.Download),
    SETTINGS(Routes.SETTINGS, "Settings", "Ajustes", Icons.Default.Settings),
    ;

    fun label(language: MenuLanguage): String = if (language.usesSpanish()) spanishLabel else englishLabel
}

/** Search is launched from Home's top action on phones; TV keeps it in the navigation rail. */
/**
 * The destinations in the capsule, in the order they sit in it.
 *
 * Search sits second, beside Home, which is where the reference this bar is modelled on puts it.
 * It was last for a while for a reason that had since expired: Search used to be a disc of its own
 * off the right-hand end of the bar, and when it joined the capsule it kept the far edge so the
 * thumb would still find it where it had always been. That habit belonged to people who had used
 * the disc, and browsing pairs with searching far more often than it pairs with Settings, which is
 * what Search was sitting next to.
 *
 * Schedule is not here: it belongs to Home, whose top bar is where it sits. Five destinations is
 * what the icon-only bar holds with comfortable touch targets on narrow phones.
 */
private val phoneTabs = listOf(Tab.HOME, Tab.SEARCH, Tab.DOWNLOADS, Tab.MORE, Tab.SETTINGS)

/**
 * Room the floating phone navigation capsule occupies above the system navigation inset — the
 * capsule plus the gap it floats on. Reserved at its *expanded* height whatever it is currently
 * showing, so a list that settles under a growing bar does not have its last row swallowed.
 */
private val PhoneNavigationBarHeight = FloatingNavBarExpandedHeight + FloatingNavBarMargin

/** The dice's right edge on the capsule's, which is inset this far from the screen's. */
private val RandomButtonEndInset = 12.dp

/** Air between the dice and the capsule top, so the two read as a pair rather than as one shape. */
private val RandomButtonGap = 12.dp

@Composable
private fun MiruroRoot(
    inPictureInPicture: Boolean,
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
    pendingLink: AnililiLink?,
    onLinkConsumed: () -> Unit,
) {
    val deviceProfile = rememberAppDeviceProfile()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = Routes.tabRoute(backStack?.destination?.route)
    val showBottomBar = currentRoute in Routes.tabRoutes
    // The anime page keeps the phone's tab bar (and the tablet's rail): it is where most browsing
    // lands, and without the bar every jump to another tab from there cost a Back first. No tab is
    // lit on it, because it belongs to whichever tab the viewer came from.
    val showPhoneNavigation = showBottomBar || currentRoute == Routes.DETAIL
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    var chromeVisible by remember { mutableStateOf(true) }
    val tvSearchRailFocusRequester = remember { FocusRequester() }
    // Redirect destinations, not plain requesters: both live on screens that are only composed on
    // some routes and only once their data lands, and a redirect to an absent one crashes the app.
    val tvSearchFieldFocusTarget = rememberTvFocusTarget()
    val tvHomePrimaryFocusTarget = rememberTvFocusTarget()
    // Held above the navigation graph, like the focus targets: the bar that raises it is drawn
    // over the NavHost rather than inside it, so the two ends share no parent.
    //
    // One per screen rather than one shared counter. Home stays composed behind an anime page on
    // some navigations, and a single counter would hand the same press to both — the page in front
    // reloading is what was asked for; the one behind it reloading as well is a second round of
    // network for a screen nobody is looking at.
    val tvHomeRefresh = rememberTvRefreshRequest()
    val tvDetailRefresh = rememberTvRefreshRequest()
    // One signal, direction-based like YouTube or Chrome, and it stays where a scroll left it: a
    // downward scroll past a small threshold takes the top bar away, and only an upward scroll
    // gives it back. The capsule keeps one height throughout. The threshold stops micro-scrolls from flickering
    // them, and hide/show firing once per direction change (instead of on every scroll frame) is
    // what keeps the animation smooth.
    //
    // Both used to come back on their own — the top bar after a second of stillness, the capsule
    // the moment the list stopped moving — and both were wrong the same way. Stopping to look at
    // something halfway down a page is not a request to have the chrome back, and having it arrive
    // anyway spends the top of the window on branding the user has already scrolled past. The
    // capsule growing under a thumb that had merely paused was the loudest version of it. The way
    // back is the gesture that asks for it.
    //
    // All of it is opt-out: Settings ▸ General ▸ "Keep the bars on screen" pins them, for anyone
    // who reads chrome that moves under the thumb as the app twitching rather than making room.
    val chromeHideThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val staticChrome by SettingsStore.staticAppChrome.collectAsState()
    // Whatever state a scroll left behind, pinning has to put the bars back — otherwise turning the
    // setting on while mid-page leaves them hidden with nothing left that would restore them.
    LaunchedEffect(staticChrome) {
        if (staticChrome) chromeVisible = true
    }
    // A screen you have just arrived on starts with its chrome. Nothing restores it on a timer any
    // more, so without this a hidden bar would be inherited by the next destination — which lands
    // you on a page with no title, no search and no way to ask for them but a scroll it may not
    // even have.
    LaunchedEffect(backStack?.destination?.route) { chromeVisible = true }
    val revealChrome = remember { { chromeVisible = true } }
    val chromeScrollConnection = remember(deviceProfile.isTv, chromeHideThresholdPx, staticChrome) {
        object : NestedScrollConnection {
            private var accumulated = 0f

            // Consumed, not available. Reading the offered delta meant a drag on a list that had
            // nothing left to scroll still counted toward the hide threshold, so dragging a short
            // page — Settings with every section collapsed — took its title away and gave back an
            // empty screen. The bars are paying for room the content never uses. Consumed also
            // ends the accumulation at the top and bottom of a list, where a thumb that keeps
            // going is asking for nothing.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (deviceProfile.isTv || staticChrome || consumed.y == 0f) return Offset.Zero
                if ((accumulated < 0f) != (consumed.y < 0f)) accumulated = 0f
                accumulated += consumed.y
                if (accumulated < -chromeHideThresholdPx && chromeVisible) {
                    chromeVisible = false
                } else if (accumulated > chromeHideThresholdPx && !chromeVisible) {
                    chromeVisible = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) {
        DiagnosticsLog.event(
            "MiruroRoot composed formFactor=${deviceProfile.formFactor} " +
                "widthDp=${deviceProfile.widthDp} navRail=${deviceProfile.useNavigationRail} " +
                // Which fact decided it, so a report of the wrong layout says whether the device
                // claimed nothing or whether the app ignored what it claimed.
                "televisionSignal=${deviceProfile.televisionSignal ?: "none"}",
        )
    }

    LaunchedEffect(currentRoute) {
        DiagnosticsLog.event("Nav route=${currentRoute ?: "none"}")
    }

    LaunchedEffect(pendingRoute) {
        pendingRoute?.takeIf { it.isNotBlank() }?.let { route ->
            DiagnosticsLog.event("Consuming pending route=$route")
            // Tabs must go through navigateTab: a plain navigate pushes the tab on top of the
            // start destination, and the next Home tap then restores that entry as Home's state.
            if (route in Routes.tabRoutes) nav.navigateTab(route) else nav.navigate(route) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        // Unlike a notification's route, a link doesn't skip "Who's watching?": it comes from
        // outside, and a locked profile's PIN is not something a link gets to walk past. It opens
        // once someone has been chosen. Choosing another profile restarts the process and drops it.
        com.anilili.ui.profiles.ProfilesUi.overlay.first {
            it != com.anilili.ui.profiles.ProfileOverlay.Picker
        }
        SettingsStore.awaitLoaded()
        val route = linkRoute(link)
        DiagnosticsLog.event("Opening link route=$route")
        nav.navigate(route) { launchSingleTop = true }
        onLinkConsumed()
    }

    val context = LocalContext.current
    // The Miruro pipe has no WebView in this build (it is a plain HTTP request; see OkHttpPipe),
    // so nothing here mounts one for PipeBridge or collects its state: with no tab attached, its
    // challenge watchdog never runs and its human check can never be raised.
    val flixcloudResolverRequired by FlixcloudBridge.resolverRequired.collectAsState()
    val hanimeResolverRequired by HanimeBridge.resolverRequired.collectAsState()
    val paheResolverRequired by PaheBridge.resolverRequired.collectAsState()
    val paheEverUsed by PaheBridge.everUsed.collectAsState()
    // Read once: the answer is a property of the system image, and the lookup touches the package
    // manager. It deliberately does not start a WebView to ask — see chromiumRestartIsFatal.
    val chromiumCannotRestart = remember(context) { chromiumRestartIsFatal(context) }
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()

    LaunchedEffect(deviceProfile.isTv, currentRoute) {
        // A watch deep link can be the process's first screen. Do not let a launch-only library
        // refresh wake up halfway through that episode; returning to a top-level tab starts this
        // bounded delay afresh. Explicit login/list/permission syncs still bypass this path.
        if (currentRoute !in Routes.tabRoutes) return@LaunchedEffect
        SettingsStore.awaitLoaded()
        // Keep notification/library network fan-out away from first composition and the initial
        // D-pad focus hand-off. A freshness check below prevents activity recreation from turning
        // this bounded delay into repeated work.
        delay(if (deviceProfile.isTv) 15_000 else 8_000)
        ReleaseSyncScheduler.runAfterStartupIfStale(context)
    }

    LaunchedEffect(Unit) {
        SettingsStore.awaitLoaded()
        // The setting silences the pop-up, not the check: Settings' App badge still needs to know.
        val prompt = SettingsStore.updateCheckOnLaunch.value
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue start prompt=$prompt")
        UpdateManager.autoCheckIfDue(context, prompt = prompt)
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue complete")
    }

    // Held here rather than by Home, so the deck — and the titles it has already dealt — outlives
    // the popup closing and Home leaving composition.
    val randomVm: RandomAnimeViewModel = viewModel()
    var showRandomPick by rememberSaveable { mutableStateOf(false) }

    val navBarInsetForChrome = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasPhoneBottomBarForChrome = showPhoneNavigation && !deviceProfile.useNavigationRail
    // Resolved here, once, for the two screens that print a time of day: the television's top-bar
    // clock and Schedule's air times.
    val systemUses24Hour = rememberSystemUses24Hour()
    val clockFormat by SettingsStore.clockFormat.collectAsState()
    val chromeTakeover = remember { AppChromeTakeover() }
    CompositionLocalProvider(
        LocalAppChromeTakeover provides chromeTakeover,
        LocalAppDeviceProfile provides deviceProfile,
        // Material pads chips, icon buttons, tabs and text buttons out to a 48dp touch target round
        // what it draws. That is for fingers; on a television it only put the focus ring — which
        // traces the padded box — several dp off a pill or circle it was meant to hug, and taller
        // than it was. A remote lands by focus, so the television goes without it.
        LocalMinimumInteractiveComponentSize provides if (deviceProfile.isTv) {
            Dp.Unspecified
        } else {
            LocalMinimumInteractiveComponentSize.current
        },
        LocalSystemUses24Hour provides systemUses24Hour,
        LocalTimeOfDayFormatter provides timeOfDayFormatter(clockFormat, systemUses24Hour),
        LocalAppChromeVisible provides (chromeVisible || deviceProfile.isTv),
        LocalAppChromeReveal provides revealChrome,
        LocalAppChromeBottomInset provides if (hasPhoneBottomBarForChrome) {
            PhoneNavigationBarHeight + navBarInsetForChrome
        } else {
            0.dp
        },
    ) {
        NotificationPermissionEffect()
        UpdatePromptHost()
        Box(Modifier.fillMaxSize().nestedScroll(chromeScrollConnection)) {
            val hasPhoneBottomBar = showPhoneNavigation && !deviceProfile.useNavigationRail
            // A sign-in page drawn over the tab keeps the layout beneath it as it was and only
            // loses the capsule, its blur and the tab swipe — see [AppChromeTakeover].
            val showPhoneCapsule = hasPhoneBottomBar && !chromeTakeover.active
            // The capsule floats over the content rather than displacing it — lists reserve its
            // height once, via LocalAppChromeBottomInset, and keep it. Tab content also draws
            // behind the system navigation area for a fully edge-to-edge viewport; non-tab screens
            // retain Scaffold's safe inset.
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // What the phone capsule blurs: everything the Scaffold draws, which the capsule sits
            // outside of, so it never samples itself.
            val navBackdrop = if (glassEnabled(GlassSection.NAVIGATION)) remember { HazeState() } else null
            // A sideways swipe turns the phone's tab pages in capsule order. Only on the tab pages
            // themselves: an anime page shows the capsule too, but a swipe there is not a request
            // to leave it for the tab beside Home.
            val tabSwipe = rememberTabSwipeState()
            val swipeTabIndex = phoneTabs.indexOfFirst { it.route == currentRoute }
            Scaffold(
                containerColor = canvasColor(),
            ) { innerPadding ->
                val showTvTopNavigation = deviceProfile.isTv &&
                    (showBottomBar || currentRoute == Routes.DETAIL)
                // The canvas is painted inside the capsule's blur source, so the glass frosts the
                // background picture too rather than showing it sharp through a tint.
                Box((if (showPhoneCapsule && navBackdrop != null) Modifier.fillMaxSize().hazeSource(navBackdrop) else Modifier.fillMaxSize()).appCanvas()) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                bottom = if (hasPhoneBottomBar) 0.dp else innerPadding.calculateBottomPadding(),
                            ),
                    ) {
                        if (showPhoneNavigation && deviceProfile.useNavigationRail && !deviceProfile.isTv) {
                            AppNavigationRail(
                                currentRoute = currentRoute,
                                menuLanguage = menuLanguage,
                                onNavigate = nav::navigateTab,
                                searchRailFocusRequester = tvSearchRailFocusRequester,
                                searchFieldFocusTarget = tvSearchFieldFocusTarget,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        // The room the TV top navigation needs is reserved by each destination
                        // that has to clear it (see TvTopNavigationInset), not here: a route-driven
                        // inset on the host arrives a frame after the screen it belongs to.
                        AppNavHost(
                            nav = nav,
                            inPictureInPicture = inPictureInPicture,
                            onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                            tvSearchFieldFocusTarget = tvSearchFieldFocusTarget,
                            tvHomePrimaryFocusTarget = tvHomePrimaryFocusTarget,
                            tvHomeRefresh = tvHomeRefresh,
                            tvDetailRefresh = tvDetailRefresh,
                            tabSwipe = tabSwipe,
                            modifier = Modifier
                                .weight(1f)
                                .swipeBetweenTabs(
                                    state = tabSwipe,
                                    enabled = showPhoneCapsule && swipeTabIndex >= 0,
                                    hasPrevious = swipeTabIndex > 0,
                                    hasNext = swipeTabIndex in 0 until phoneTabs.lastIndex,
                                    onTurn = { dir ->
                                        phoneTabs.getOrNull(swipeTabIndex + dir)?.let { nav.navigateTab(it.route) }
                                    },
                                ),
                        )
                    }
                    if (showTvTopNavigation) {
                        AppTvTopNavigation(
                            currentRoute = currentRoute,
                            menuLanguage = menuLanguage,
                            onNavigate = nav::navigateTab,
                            onNotificationsClick = {
                                nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                            },
                            onRandomClick = {
                                randomVm.ensurePick()
                                showRandomPick = true
                            },
                            searchNavFocusRequester = tvSearchRailFocusRequester,
                            searchFieldFocusTarget = tvSearchFieldFocusTarget,
                            homeContentFocusTarget = tvHomePrimaryFocusTarget,
                            // Whatever is on display, not whatever tab is lit: on an anime page
                            // the bar still lights "Home", because that is the tab the page
                            // belongs to, and a hold there has to reload the page in front of the
                            // viewer rather than the list behind it.
                            onRefreshCurrent = when (currentRoute) {
                                Routes.HOME -> tvHomeRefresh::request
                                Routes.DETAIL -> tvDetailRefresh::request
                                else -> null
                            },
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
            // Read through `backStack` so this re-evaluates on every navigation: the guard has to
            // arm and disarm exactly in step with whether the graph still has somewhere to pop to,
            // which is also the condition NavHost's own back handler uses.
            val backLeavesTheApp = backStack != null && nav.previousBackStackEntry == null
            ConfirmExitOnBack(
                enabled = deviceProfile.isTv && backLeavesTheApp,
                message = if (menuLanguage.usesSpanish()) {
                    "Pulsa Atrás otra vez para salir"
                } else {
                    "Press Back again to exit"
                },
            )
            if (showPhoneCapsule) {
                // Every item takes the glyph its Tab carries, My List included. It used to override
                // that one with a drawn books-on-a-shelf vector, which was the right picture for a
                // destination called "Library" and the wrong one for a list: a shelf says "things
                // you own", and a bookmark says "things you marked", which is what the page is.
                val navItems = remember(menuLanguage) {
                    phoneTabs.map { tab ->
                        FloatingNavItem(
                            route = tab.route,
                            label = tab.label(menuLanguage),
                            icon = tab.icon,
                        )
                    }
                }
                FloatingNavigationBar(
                    items = navItems,
                    selectedRoute = currentRoute,
                    onSelect = nav::navigateTab,
                    bottomInset = navBarInset,
                    backdrop = navBackdrop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            // Home's dice, over the capsule's right-hand end (or the corner, beside a rail). Home
            // only: it is a way into the catalogue, and Home is where browsing starts. It stays put
            // while the feed scrolls, either way: it used to step aside with the top bar on a
            // downward scroll, which hid it exactly while someone was browsing for something to
            // watch — and the capsule it sits on never hides, so it read as a button that vanished.
            if (currentRoute == Routes.HOME && !deviceProfile.isTv && !chromeTakeover.active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = if (hasPhoneBottomBar) RandomButtonEndInset else 20.dp,
                            bottom = navBarInset + if (hasPhoneBottomBar) {
                                PhoneNavigationBarHeight + RandomButtonGap
                            } else {
                                20.dp
                            },
                        )
                        .tourTarget(TourTarget.DICE),
                ) {
                    RandomAnimeButton(
                        onClick = {
                            randomVm.ensurePick()
                            showRandomPick = true
                        },
                        backdrop = navBackdrop,
                    )
                }
            }
            if (showRandomPick) {
                val randomState by randomVm.state.collectAsState()
                RandomAnimeDialog(
                    state = randomState,
                    onSkip = { randomVm.skipped() },
                    onOpen = { id ->
                        showRandomPick = false
                        nav.navigate(Routes.detail(id)) { launchSingleTop = true }
                        randomVm.opened()
                    },
                    onSource = randomVm::selectSource,
                    onDismiss = { showRandomPick = false },
                )
            }
            if (flixcloudResolverRequired) {
                FlixcloudResolverWebView()
            }
            if (!hideAdult && hanimeResolverRequired) {
                HanimeResolverWebView()
            }
            // Mounted while a resolve is in flight and let go afterwards, because AnimePahe's
            // engine is a way of reaching the site rather than something that has to stay warm.
            // The exception is the boxes that cannot rebuild Chromium: there, every later mount
            // would be the fatal rebuild-after-a-gap, so once built the view stays.
            if (paheResolverRequired || (chromiumCannotRestart && paheEverUsed)) {
                PaheResolverWebView()
            }
            // Over the app and its bars, the dice included, since it points at them; under the
            // profile screens, which it waits for anyway.
            com.anilili.ui.tour.WhatsNewTourOverlay()
            // Last, so the first-run setup and the profile switcher draw over everything else.
            com.anilili.ui.profiles.ProfilesHost()
        }
    }
}

/** Frames to keep retrying the rail's initial focus on slow TV boxes before giving up. */
/**
 * How long the TV launch waits for Settings before picking an intro. The read takes milliseconds;
 * past this the water-fill mark plays rather than keeping the screen blank.
 */
private const val LaunchIntroSettingsWaitMillis = 1_000L

/**
 * Whether this launch may play the video intro at all; when it may, the Settings switch still
 * decides. Only the TV flavour has one. A profile switch restarts the app, and the viewer has just
 * seen the intro. A launch aimed at a route or link (Watch Next, an `anilili://` link) navigates
 * underneath the intro at once, and its player would share the decoder with the intro's. A
 * low-cost television ([shouldUseLowCostTvEffects]) gets the mark rather than a second ExoPlayer.
 */
internal fun videoLaunchIntroAllowed(
    tvFlavor: Boolean,
    profileSwitchLaunch: Boolean,
    launchHasDestination: Boolean,
    lowCostTv: Boolean,
): Boolean = tvFlavor && !profileSwitchLaunch && !launchHasDestination && !lowCostTv

private const val TV_FOCUS_ATTEMPTS = 10

/**
 * How long the TV top navigation stays on screen once nothing in it is focused.
 *
 * Long enough to read the row of labels and the clock from a sofa, short enough that it is gone
 * before a viewer has settled into the page under it.
 */
private const val TV_TOP_NAVIGATION_LINGER_MS = 4_000L

private val tvPrimaryTabs = Tab.entries.filterNot { it == Tab.SETTINGS }

internal fun tvBrandSuffixFor(flavor: String): String? = "TV".takeIf { flavor == "tv" }

@Composable
private fun AppTvTopNavigation(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    onNotificationsClick: () -> Unit,
    /** Opens the random pick's card stack — the phone's dice, given a seat in the bar. */
    onRandomClick: () -> Unit,
    searchNavFocusRequester: FocusRequester,
    searchFieldFocusTarget: TvFocusTarget,
    homeContentFocusTarget: TvFocusTarget,
    /** Raised by a hold on the lit tab; null on the screens that have no reload to offer. */
    onRefreshCurrent: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember(searchNavFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchNavFocusRequester else FocusRequester()
        }
    }
    val unread by com.anilili.data.reminder.NotificationCenter.unread.collectAsState()
    // Keyed on the formatter, not left to the thirty-second tick: a viewer who has just changed the
    // setting is looking straight at this clock, and waiting half a minute reads as nothing having
    // happened.
    val clockFormatter = LocalTimeOfDayFormatter.current
    var clockText by remember(clockFormatter) { mutableStateOf(LocalTime.now().format(clockFormatter)) }

    // Down leaves the bar for the screen that is on display — which has nothing to do with which
    // tab happens to hold focus. Keying it to the focused item's own `selected` flag meant exactly
    // one item in the bar had a Down at all: standing on Home, Down worked from "Home" and did
    // nothing from "Schedule" or "My List", and a D-pad that ignores a press reads as a dead remote
    // rather than as a bar being careful. Null on the routes with nowhere named to go, which leaves
    // Down to the ordinary spatial search rather than to a hand-off that cannot land — see
    // tvFocusHandoff for why a hand-off that fails is worse than never having offered one.
    val contentFocusTarget = when (currentRoute) {
        Routes.HOME -> homeContentFocusTarget
        Routes.SEARCH -> searchFieldFocusTarget
        else -> null
    }

    LaunchedEffect(clockFormatter) {
        while (true) {
            clockText = LocalTime.now().format(clockFormatter)
            delay(30_000)
        }
    }
    // The bar is the only thing that places the cursor on a route change, so the request has to
    // actually land. Slow Fire TV boxes run this before the item's focus node is attached and
    // requestFocus() throws outright; a single swallowed attempt left the television with nothing
    // focused and a remote that did nothing, which Home used to paper over by grabbing focus for
    // its banner on every visit. Retry across a few frames instead, exactly as the phone rail does.
    LaunchedEffect(currentRoute) {
        val tab = Tab.entries.firstOrNull { it.route == currentRoute } ?: return@LaunchedEffect
        val requester = focusRequesters.getValue(tab)
        repeat(TV_FOCUS_ATTEMPTS) { attempt ->
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos {}
            if (attempt == TV_FOCUS_ATTEMPTS - 1) {
                DiagnosticsLog.event("TV top navigation focus never attached route=$currentRoute")
            }
        }
    }

    var holdsFocus by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(true) }
    // The same switch that pins the phone's bars. Some viewers want the menu where they can see
    // it rather than something that comes and goes, and the fade is exactly that on a television.
    val pinned by SettingsStore.staticAppChrome.collectAsState()
    // The bar gets out of the way of the screen behind it. It is worth a glance on arrival — it
    // says where the viewer is and what else there is — and worth nothing at all for the rest of
    // the visit, so it says its piece and goes. Faded rather than moved or unmounted: an item that
    // has left the layout cannot be found by a focus search, and Up is how it comes back.
    // Held up through the What's new tour too: two of its steps point at the dice and Settings in
    // this bar, and the cursor is in the tour's card, so the bar would otherwise have faded out.
    val touring = com.anilili.ui.tour.WhatsNewTour.active
    LaunchedEffect(holdsFocus, currentRoute, pinned, touring) {
        revealed = true
        if (holdsFocus || pinned || touring) return@LaunchedEffect
        delay(TV_TOP_NAVIGATION_LINGER_MS)
        revealed = false
    }
    val chromeAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "tvTopNavigationAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TvTopNavigationHeight)
            .onFocusChanged { holdsFocus = it.isFocused || it.hasFocus }
            .alpha(chromeAlpha)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(.74f),
                    1f to ComposeColor.Transparent,
                ),
            )
            .padding(horizontal = 34.dp, vertical = 14.dp),
    ) {
        TvTopNavigationLayout(
            modifier = Modifier.fillMaxSize(),
            brand = {
                Row(verticalAlignment = Alignment.Bottom) {
                    // The wordmark is a mark, not a destination: it should read as roughly the same
                    // weight as the labels beside it rather than overhanging them. The image is
                    // full-bleed type with no internal padding, so 30.dp put its cap height half
                    // again above a 14.sp label and made the bar look like a logo with a menu
                    // tucked under one corner. 22.dp is the height the phone header uses against
                    // the same label size.
                    AnililiWordmark(modifier = Modifier.height(22.dp))
                    tvBrandSuffixFor(BuildConfig.FLAVOR)?.let { suffix ->
                        Text(
                            text = suffix,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, bottom = 1.dp),
                        )
                    }
                }
            },
            tabs = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tvPrimaryTabs.forEach { tab ->
                        val isSelected = currentRoute == tab.route ||
                            (currentRoute == Routes.DETAIL && tab == Tab.HOME)
                        TvTopNavigationItem(
                            tab = tab,
                            label = tab.label(menuLanguage),
                            selected = isSelected,
                            onClick = { onNavigate(tab.route) },
                            focusRequester = focusRequesters.getValue(tab),
                            contentFocusTarget = contentFocusTarget,
                            // The lit tab only. The others keep a plain press, so a hold there
                            // still just navigates rather than doing something undiscoverable.
                            onLongClick = onRefreshCurrent?.takeIf { isSelected },
                        )
                    }
                }
            },
            actions = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // First of the actions, nearest the tabs: it is a way into the catalogue, which
                    // is what the tabs are, rather than a setting or an inbox.
                    val randomFocus = rememberFocusFill()
                    IconButton(
                        colors = randomFocus.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = onRandomClick,
                        modifier = Modifier
                            .tourTarget(TourTarget.DICE)
                            .tvFocusHandoff(Key.DirectionDown, contentFocusTarget)
                            .focusFill(randomFocus, CircleShape, focusedScale = 1.08f),
                    ) {
                        Icon(
                            Icons.Default.Casino,
                            contentDescription = if (menuLanguage.usesSpanish()) "Anime al azar" else "Random anime",
                            tint = randomFocus.content(ComposeColor.White.copy(.78f)),
                        )
                    }
                    BadgedBox(
                        badge = {
                            if (unread > 0) {
                                Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                            }
                        },
                    ) {
                        val controlFocus1 = rememberFocusFill()
                        IconButton(
                            colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                            onClick = onNotificationsClick,
                            modifier = Modifier
                                .tvFocusHandoff(Key.DirectionDown, contentFocusTarget)
                                .focusFill(controlFocus1, CircleShape, focusedScale = 1.08f),
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                                tint = controlFocus1.content(ComposeColor.White.copy(.78f)),
                            )
                        }
                    }
                    val controlFocus2 = rememberFocusFill()
                    IconButton(
                        colors = controlFocus2.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                        onClick = { onNavigate(Tab.SETTINGS.route) },
                        modifier = Modifier
                            .tourTarget(TourTarget.ARRANGE_SETTINGS)
                            .focusRequester(focusRequesters.getValue(Tab.SETTINGS))
                            .tvFocusHandoff(Key.DirectionDown, contentFocusTarget)
                            .background(
                                if (currentRoute == Tab.SETTINGS.route) {
                                    MaterialTheme.colorScheme.primary.copy(.24f)
                                } else {
                                    ComposeColor.Transparent
                                },
                                CircleShape,
                            )
                            .focusFill(controlFocus2, CircleShape, focusedScale = 1.08f),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = Tab.SETTINGS.label(menuLanguage),
                            tint = controlFocus2.content(if (currentRoute == Tab.SETTINGS.route) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                ComposeColor.White.copy(.78f)
                            }),
                        )
                    }
                    // Netflix's place for it: the person watching, at the far end of the bar.
                    com.anilili.ui.profiles.ProfileSwitchButton(
                        modifier = Modifier.tvFocusHandoff(Key.DirectionDown, contentFocusTarget),
                    )
                }
            },
            clock = {
                Text(
                    clockText,
                    color = ComposeColor.White.copy(.72f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp),
                )
            },
        )
    }
}

/**
 * Fits the header's five pieces into the width the panel actually has, dropping the ones it cannot
 * seat rather than letting any two of them share a pixel.
 *
 * The bar used to be a Box with three independently aligned rows, which keeps them apart only for
 * as long as they happen to be narrow enough. Interface scale breaks that from the other end: every
 * size in here is a fixed `dp`, and a larger scale makes the panel *fewer* dp wide — a 1080p screen
 * measures 960dp at Medium but about 835dp at Large, and the centred tabs slid straight under the
 * wordmark, Home first. Spanish labels are longer again and reach it sooner.
 *
 * So the pieces are measured in the order they earn their place: the destinations, then the
 * notifications and settings buttons that live nowhere else on television, then the wordmark, and
 * last the clock. Whatever no longer fits is composed but not placed. The tabs stay
 * exactly centred while there is room on both sides for that, and slide off centre instead of
 * colliding when there is not.
 */
@Composable
private fun TvTopNavigationLayout(
    brand: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    clock: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            brand()
            tabs()
            actions()
            clock()
        },
    ) { measurables, constraints ->
        val brandMark = 0
        val tabStrip = 1
        val actionButtons = 2
        val time = 3

        val placeables = arrayOfNulls<Placeable>(measurables.size)
        // Measured at their natural size: nothing in the header wants to be squeezed, and a
        // half-drawn tab label reads worse than one that stepped aside. The tab strip is never
        // dropped whatever it measures — a header without its destinations would leave the remote
        // with nowhere to go.
        placeables[tabStrip] = measurables[tabStrip].measure(Constraints())
        var remaining = constraints.maxWidth - placeables[tabStrip]!!.width
        for (index in intArrayOf(actionButtons, brandMark, time)) {
            val placeable = measurables[index].measure(Constraints())
            if (placeable.width > remaining) continue
            remaining -= placeable.width
            placeables[index] = placeable
        }

        val width = constraints.maxWidth
        val height = constraints.constrainHeight(
            placeables.filterNotNull().maxOfOrNull { it.height } ?: 0,
        )
        val leadWidth = placeables[brandMark]?.width ?: 0
        val trailWidth = (placeables[actionButtons]?.width ?: 0) + (placeables[time]?.width ?: 0)
        val tabsWidth = placeables[tabStrip]?.width ?: 0

        layout(width, height) {
            fun place(placeable: Placeable?, x: Int) {
                placeable?.placeRelative(x, (height - placeable.height) / 2)
            }
            place(placeables[brandMark], 0)
            place(
                placeables[tabStrip],
                ((width - tabsWidth) / 2)
                    .coerceAtMost((width - trailWidth - tabsWidth).coerceAtLeast(leadWidth))
                    .coerceAtLeast(leadWidth),
            )
            place(placeables[actionButtons], width - trailWidth)
            place(placeables[time], width - (placeables[time]?.width ?: 0))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvTopNavigationItem(
    tab: Tab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    contentFocusTarget: TvFocusTarget?,
    /**
     * Hold OK here to reload the screen on display, or null where a hold means nothing.
     *
     * Offered only by the lit tab: holding OK on a tab the viewer is not on would reload a screen
     * they cannot see instead of taking them to it. "The screen on display" is not always this
     * tab's own route — an anime page lights Home — which is why the announced label below says
     * what the hold does rather than naming the tab it is standing on.
     */
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    // Focus is the ring the rest of the television uses (see focusHighlight), not a white fill: the
    // tab keeps its own colours and only brightens its label.
    val container = when {
        selected -> MaterialTheme.colorScheme.primary.copy(.24f)
        else -> ComposeColor.Transparent
    }
    val content = when {
        focused || selected -> ComposeColor.White
        else -> ComposeColor.White.copy(.68f)
    }

    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .tvFocusHandoff(Key.DirectionDown, contentFocusTarget)
            .onFocusChanged { focused = it.isFocused }
            // Ahead of the clip, which would shave off a ring drawn outside the tab.
            .focusHalo(focused, shape, FocusRingGap)
            .clip(shape)
            // The tab on display is marked by its tint and its bright label only. It used to carry a
            // 1dp outline in the accent too, which on Monochrome is white and read as a second focus
            // ring beside the real one.
            .background(container)
            // dpadClickable consumes OK and issues the short click itself, so it is applied only
            // where a hold has somewhere to go; everywhere else the ordinary clickable is left to
            // handle the key on its own. Both are kept: the clickable is what makes this focusable
            // and carries the labels a screen reader announces.
            .then(
                if (onLongClick == null) {
                    Modifier
                } else {
                    Modifier.dpadClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClickLabel = label, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClickLabel = label,
                        onLongClickLabel = "Reload this page",
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val enabled by SettingsStore.releaseNotifications.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        AiringPollScheduler.sync(context, granted)
        if (granted) {
            ReleaseSyncScheduler.runNow(context)
            AiringPollScheduler.runNow(context)
        } else {
            AutomaticReleaseManager.cancelAll()
        }
    }

    LaunchedEffect(enabled, device.isTv) {
        if (!enabled) {
            AutomaticReleaseManager.cancelAll()
            AiringPollScheduler.sync(context, false)
            return@LaunchedEffect
        }
        if (device.isTv || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // The launch-wide, freshness-gated effect handles an already-granted permission.
            // A permission granted by the user in this session still runs immediately above.
            return@LaunchedEffect
        }
        val prefs = context.getSharedPreferences("anilili_permissions", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("release_notifications_prompted", false)) {
            prefs.edit().putBoolean("release_notifications_prompted", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
            AiringPollScheduler.sync(context, false)
        }
    }
}

@Composable
private fun AppNavigationRail(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    searchRailFocusRequester: FocusRequester,
    searchFieldFocusTarget: TvFocusTarget,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val focusRequesters = remember(searchRailFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchRailFocusRequester else FocusRequester()
        }
    }
    // Slow Fire TV boxes (AFTTIFF43) can run this before the rail's focus targets are attached,
    // and FocusRequester.requestFocus() throws outright when its node is not there yet. Retry
    // across a few frames instead of swallowing the failure: a silent catch left the rail with
    // nothing focused at all, which on a D-pad means the user cannot move.
    LaunchedEffect(currentRoute, device.isTv) {
        if (!device.isTv) return@LaunchedEffect
        val tab = Tab.entries.firstOrNull { it.route == currentRoute } ?: return@LaunchedEffect
        val requester = focusRequesters.getValue(tab)
        repeat(TV_FOCUS_ATTEMPTS) { attempt ->
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos {}
            if (attempt == TV_FOCUS_ATTEMPTS - 1) {
                DiagnosticsLog.event("Navigation rail focus never attached route=$currentRoute")
            }
        }
    }
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Text(
                stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        },
    ) {
        Tab.entries.forEach { tab ->
            val label = tab.label(menuLanguage)
            val railFocus = rememberFocusFill()
            NavigationRailItem(
                colors = if (railFocus.focused) NavigationRailItemDefaults.colors(
                    selectedIconColor = FocusFillContentColor,
                    unselectedIconColor = FocusFillContentColor,
                    selectedTextColor = FocusFillContentColor,
                    unselectedTextColor = FocusFillContentColor,
                    indicatorColor = ComposeColor.Transparent,
                ) else NavigationRailItemDefaults.colors(),
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
                alwaysShowLabel = device.isTv,
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(tab))
                    // Spatial focus search prefers the Movies chip because it is horizontally
                    // aligned with the rail item. Route Right to the actual search box — but only
                    // while that box is really attached, since resolving a hand-off to a requester
                    // nothing holds throws out of the focus owner.
                    .tvFocusHandoff(
                        Key.DirectionRight,
                        searchFieldFocusTarget.takeIf {
                            device.isTv && tab == Tab.SEARCH && currentRoute == Routes.SEARCH
                        },
                    )
                    .focusFill(railFocus)
                    .background(railFocus.background(ComposeColor.Transparent), RoundedCornerShape(12.dp)),
            )
        }
    }
}

/**
 * Where a resume press should land: the downloaded player when this device already holds a playable
 * copy of that exact episode, the streaming player otherwise.
 *
 * Continue Watching used to always take the streaming route. Resuming a downloaded episode with no
 * connection therefore went looking for a server, exhausted them all and reported that none had a
 * stream — with the episode sitting on disk the whole time. A local copy is also the better answer
 * when there *is* a connection: no buffering and no provider roulette for a file already here.
 *
 * The lookup reads the offline stores rather than collecting them, so Home pays nothing for it
 * until something is actually pressed; both are populated during app start ([MiruroApp]).
 */
/**
 * Where an `anilili://` link lands. A watch link plays the way the anime page's Play would: on the
 * server and language this title was last watched with, else on the automatic choice in the
 * preferred language — the link names an episode, not a source.
 */
private fun linkRoute(link: AnililiLink): String = when (link) {
    is AnililiLink.Anime -> Routes.detail(link.id)
    is AnililiLink.Search -> Routes.querySearch(link.query)
    is AnililiLink.Watch -> {
        val saved = LibraryStore.historyFor(link.id)
        Routes.watchFrom(
            id = link.id,
            provider = saved?.provider ?: AUTO_PROVIDER,
            category = saved?.category ?: if (SettingsStore.preferDub.value) "dub" else "sub",
            episode = link.episodeLabel,
            startMs = link.startSeconds?.let { (it * 1000).toLong() },
        )
    }
}

private fun resumeRoute(
    context: Context,
    anilistId: Int,
    provider: String,
    category: String,
    episode: String,
): String {
    val offlineId = playableOfflineEpisodeId(
        anilistId = anilistId,
        category = category,
        episodeNumber = episode,
        downloads = EpisodeDownloads.downloads(context).value,
        exported = EpisodeExport.exported(context).value,
    )
    return offlineId?.let(Routes::download)
        ?: Routes.watch(anilistId, provider, category, episode)
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    inPictureInPicture: Boolean,
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    tvSearchFieldFocusTarget: TvFocusTarget,
    tvHomePrimaryFocusTarget: TvFocusTarget,
    tvHomeRefresh: TvRefreshRequest? = null,
    tvDetailRefresh: TvRefreshRequest? = null,
    /** Makes a swipe between tabs hand over sideways; every other navigation keeps the fade. */
    tabSwipe: TabSwipeState? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { tabSwipe?.enter(DefaultNavEnter) ?: DefaultNavEnter },
        exitTransition = { tabSwipe?.exit(DefaultNavExit) ?: DefaultNavExit },
        popEnterTransition = { tabSwipe?.enter(DefaultNavEnter) ?: DefaultNavEnter },
        popExitTransition = { tabSwipe?.exit(DefaultNavExit) ?: DefaultNavExit },
    ) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route HOME content entered") }
                HomeScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onWatchNow = { id ->
                        val saved = com.anilili.data.library.LibraryStore.historyFor(id)
                        if (saved != null) nav.navigate(resumeRoute(context, id, saved.provider, saved.category, saved.episodeLabel))
                        else nav.navigate(resumeRoute(context, id, "auto", if (com.anilili.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                    },
                    onResume = { e -> nav.navigate(resumeRoute(context, e.anilistId, e.provider, e.category, e.episodeLabel)) },
                    onOpenDetails = { e -> nav.navigate(Routes.detail(e.anilistId)) },
                    onSearchClick = { nav.navigateTab(Routes.SEARCH) },
                    onScheduleClick = { nav.navigateTab(Routes.SCHEDULE) },
                    onGenreClick = { genre ->
                        if (genre == null) nav.navigateTab(Routes.SEARCH)
                        else nav.navigate(Routes.genreSearch(genre)) { launchSingleTop = true }
                    },
                    onNotificationsClick = { nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true } },
                    tvPrimaryFocusTarget = tvHomePrimaryFocusTarget,
                    tvRefreshRequest = tvHomeRefresh,
                )
            }
            composable(Routes.NOTIFICATIONS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route NOTIFICATIONS content entered") }
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                )
            }
            composable(
                route = Routes.SEARCH_DESTINATION,
                arguments = listOf(
                    navArgument(Routes.Arg.STUDIO_ID) {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument(Routes.Arg.GENRE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Routes.Arg.STUDIO_NAME) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Routes.Arg.QUERY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SEARCH content entered") }
                TvTopNavigationInset {
                    SearchScreen(
                        onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                        tvFieldFocusTarget = tvSearchFieldFocusTarget,
                        initialStudioId = entry.arguments?.getInt(Routes.Arg.STUDIO_ID)?.takeIf { it > 0 },
                        initialStudioName = entry.arguments?.getString(Routes.Arg.STUDIO_NAME),
                        initialGenre = entry.arguments?.getString(Routes.Arg.GENRE),
                        initialQuery = entry.arguments?.getString(Routes.Arg.QUERY),
                    )
                }
            }
            composable(Routes.SCHEDULE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SCHEDULE content entered") }
                TvTopNavigationInset {
                    ScheduleScreen(onAnimeClick = { id -> nav.navigate(Routes.detail(id)) })
                }
            }
            composable(Routes.MORE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route MORE content entered") }
                TvTopNavigationInset {
                    ProfileScreen(
                        onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                        onResume = { e ->
                            nav.navigate(resumeRoute(context, e.anilistId, e.provider, e.category, e.episodeLabel))
                        },
                        onOpenDetails = { e -> nav.navigate(Routes.detail(e.anilistId)) },
                        onPlayDownload = { downloadId ->
                            nav.navigate(Routes.download(downloadId))
                        },
                    )
                }
            }
            composable(Routes.DOWNLOADS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route DOWNLOADS content entered") }
                TvTopNavigationInset {
                    DownloadsScreen(
                        onPlayDownload = { downloadId -> nav.navigate(Routes.download(downloadId)) },
                    )
                }
            }
            composable(Routes.SETTINGS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SETTINGS content entered") }
                TvTopNavigationInset { SettingsScreen() }
            }

            composable(
                route = Routes.DOWNLOAD,
                arguments = listOf(navArgument(Routes.Arg.DOWNLOAD_ID) { type = NavType.StringType }),
            ) { entry ->
                val downloadId = entry.arguments?.getString(Routes.Arg.DOWNLOAD_ID)
                    ?: return@composable
                LaunchedEffect(downloadId) {
                    DiagnosticsLog.event("Route DOWNLOAD content entered id=$downloadId")
                }
                // Player, so it keeps the device's real density — see UnscaledUi.
                UnscaledUi {
                    DownloadedEpisodeScreen(
                        downloadId = downloadId,
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            composable(
                route = Routes.DETAIL_DESTINATION,
                arguments = listOf(
                    navArgument(Routes.Arg.ID) { type = NavType.IntType },
                    navArgument(Routes.Arg.EPISODE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val id = entry.arguments?.getInt(Routes.Arg.ID) ?: return@composable
                // Only present when the player sent the viewer here; a page opened from a list has
                // no episode to single out and opens the way it always has.
                val arrivalEpisode = entry.arguments?.getString(Routes.Arg.EPISODE)
                // The route argument is the episode the page was built with; a player started from
                // this page and left again writes the episode it was actually on into the entry's
                // saved state, and that wins. Kept rather than consumed: removing it would put the
                // page back on the stale argument at its next composition. A null written there
                // (the player was another anime's) clears the argument too.
                val returnEpisode by entry.savedStateHandle
                    .getStateFlow(PLAYER_RETURN_EPISODE_KEY, arrivalEpisode)
                    .collectAsState()
                LaunchedEffect(id) { DiagnosticsLog.event("Route DETAIL content entered id=$id") }
                DetailScreen(
                    animeId = id,
                    focusEpisode = returnEpisode,
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { relatedId ->
                        if (relatedId != id) nav.navigate(Routes.detail(relatedId))
                    },
                    onStudioClick = { studio ->
                        val name = studio.name
                        if (studio.id > 0 && !name.isNullOrBlank()) {
                            nav.navigate(Routes.studioSearch(studio.id, name)) { launchSingleTop = true }
                        }
                    },
                    onPlay = { playId, provider, category, episode ->
                        // Play/Resume means play on every device. On TV the Watch route starts
                        // fullscreen and PlayerSurface starts immediately; the detail page's
                        // Episodes tab remains the explicit route for choosing another episode.
                        // playId may be another season hosted by this detail-page season chain.
                        nav.navigate(Routes.watch(playId, provider, category, episode))
                    },
                    onSeasonWatch = { seasonId ->
                        val saved = com.anilili.data.library.LibraryStore.historyFor(seasonId)
                        if (saved != null) {
                            nav.navigate(Routes.episodes(seasonId, saved.provider, saved.category, saved.episodeLabel))
                        } else {
                            nav.navigate(Routes.episodes(seasonId, "auto", if (com.anilili.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                        }
                    },
                    tvRefreshRequest = tvDetailRefresh,
                )
            }

            composable(
                route = Routes.WATCH,
                arguments = listOf(
                    navArgument(Routes.Arg.ID) { type = NavType.IntType },
                    navArgument(Routes.Arg.PROVIDER) { type = NavType.StringType },
                    navArgument(Routes.Arg.CATEGORY) { type = NavType.StringType },
                    navArgument(Routes.Arg.EPISODE) { type = NavType.StringType },
                    navArgument(Routes.Arg.SHOW_EPISODES) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument(Routes.Arg.AUTO_ADVANCED) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument(Routes.Arg.FULLSCREEN) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument(Routes.Arg.START_MS) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val watchId = args.getInt(Routes.Arg.ID)
                val watchProvider = args.getString(Routes.Arg.PROVIDER).orEmpty()
                val watchCategory = args.getString(Routes.Arg.CATEGORY).orEmpty()
                val watchEpisode = args.getString(Routes.Arg.EPISODE).orEmpty()
                val showEpisodes = args.getBoolean(Routes.Arg.SHOW_EPISODES)
                LaunchedEffect(watchId, watchProvider, watchCategory, watchEpisode) {
                    DiagnosticsLog.event(
                        "Route WATCH content entered id=$watchId provider=$watchProvider " +
                            "category=$watchCategory episode=$watchEpisode",
                    )
                }
                // Not wrapped in UnscaledUi as a whole: below the video this route draws an
                // episode summary, an episode grid and the source pickers, which are browse
                // surfaces and scale like every other one. WatchScreen exempts the video pane
                // itself.
                val device = LocalAppDeviceProfile.current
                WatchScreen(
                    animeId = watchId,
                    provider = watchProvider,
                    category = watchCategory,
                    episode = watchEpisode,
                    showEpisodeListInitially = showEpisodes,
                    inPictureInPicture = inPictureInPicture,
                    onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                    onBack = { playingEpisode ->
                        // WatchScreen's Back is documented as leaving for the anime page, but that
                        // page is only under the player when the player was opened from it. A
                        // resume from Continue Watching launches Watch straight from a tab, so on
                        // television Back landed on that tab with the anime page never having
                        // been in the stack. Put the page there, carrying the episode just left so
                        // it opens on that episode rather than on episode one. A player opened
                        // from the anime page itself keeps its ordinary pop, which lands the same
                        // place without rebuilding it.
                        //
                        // "The episode just left" is the one on screen, not this route's argument:
                        // Next and auto-play move on inside the route, so the argument is only
                        // where the session started.
                        val previousEntry = nav.previousBackStackEntry
                        val previous = Routes.tabRoute(previousEntry?.destination?.route)
                        if (device.isTv && previous in Routes.tabRoutes) {
                            val handBack = episodeToHandBack(watchId, watchId, watchEpisode, playingEpisode)
                            nav.navigate(Routes.detail(watchId, handBack)) {
                                popUpTo(Routes.WATCH) { inclusive = true }
                            }
                        } else {
                            if (previousEntry?.destination?.route == Routes.DETAIL_DESTINATION) {
                                val detailId = previousEntry.arguments?.getInt(Routes.Arg.ID)
                                if (detailId != null) {
                                    previousEntry.savedStateHandle[PLAYER_RETURN_EPISODE_KEY] =
                                        episodeToHandBack(detailId, watchId, watchEpisode, playingEpisode)
                                }
                            }
                            nav.popBackStack()
                        }
                    },
                    onOpenAnime = { nav.navigate(Routes.detail(watchId)) },
                    autoAdvanced = args.getBoolean(Routes.Arg.AUTO_ADVANCED),
                    startFullscreen = args.getBoolean(Routes.Arg.FULLSCREEN),
                    startAtMs = args.getLong(Routes.Arg.START_MS).takeIf { it > 0 },
                    onOpenNextSeason = { request, fullscreen ->
                        // Replaces this season's player rather than stacking on it, so Back from
                        // the next season leaves the player as it would have from this one.
                        nav.navigate(
                            Routes.nextSeason(
                                id = request.anilistId,
                                provider = request.provider,
                                category = request.category,
                                autoAdvanced = request.autoAdvanced,
                                fullscreen = fullscreen,
                            ),
                        ) {
                            popUpTo(Routes.WATCH) { inclusive = true }
                        }
                    },
                )
            }
        }
}

private fun NavController.navigateTab(route: String) {
    // Already inside this tab, on a page stacked above it — an anime opened from My List: the tab
    // takes you back to its own page. Navigating instead restored the tab's saved stack, which is
    // the page already on screen, so the tap did nothing at all and My List could not be reached
    // again from any anime opened out of it.
    if (Routes.tabRoute(currentDestination?.route) != route) {
        val pattern = Routes.tabDestinationPattern(route)
        if (runCatching { getBackStackEntry(pattern) }.isSuccess && popBackStack(pattern, inclusive = false)) return
    }
    val restoreTabState = Routes.shouldRestoreTabState(route)
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = restoreTabState }
        launchSingleTop = true
        restoreState = restoreTabState
    }
}

/**
 * Saved-state key on an anime page's back-stack entry: the episode the player was on when Back
 * left it for that page. Overrides the entry's `episode` route argument; see [episodeToHandBack].
 */
private const val PLAYER_RETURN_EPISODE_KEY = "playerReturnEpisode"

/** Debug builds: update-prompt notes to preview; see MainActivity.applyBenchmarkPin. */
private const val EXTRA_PREVIEW_CHANGELOG = "com.anilili.debug.PREVIEW_CHANGELOG"

package com.anilili

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.diagnostics.CrashReporter
import com.anilili.data.library.LibraryStore
import com.anilili.data.settings.AppIcon
import com.anilili.data.settings.SettingsStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.anilili.data.reminder.ReminderManager
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.AniListNotificationPushManager
import com.anilili.data.reminder.AiringPollScheduler
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.announce.AnnouncementManager
import com.anilili.data.announce.AnnouncementScheduler
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsPerformanceMonitor
import com.anilili.diagnostics.DiagnosticsSystemMonitor
import com.anilili.diagnostics.DiagnosticsShareManager
import com.anilili.playback.EpisodeDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiruroApp : Application(), ImageLoaderFactory {
    /** Lives as long as the process; only used for work deliberately kept out of onCreate. */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether this is the app's own process rather than a child one the engine started. A child
     * process always carries a `:suffix`; an unreadable name is treated as main, which is the
     * behaviour that predates the engine.
     */
    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            currentProcessName()
        }
        return name.isNullOrEmpty() || name == packageName
    }

    override fun onCreate() {
        super.onCreate()
        // Application.onCreate runs in EVERY process, and a browser engine brings its own: the
        // WebView the embed player and the AnimePahe bridge use runs its renderer in
        // :sandboxed_process*. Full init there is not merely wasteful, it is fatal:
        // ReleaseSyncScheduler asks for WorkManager, which is only initialized in the main
        // process, so the child dies with "WorkManager is not initialized properly" and takes the
        // engine down with it -- a blank page and no load events, which looks exactly like a
        // network failure. (The rule predates the OkHttp pipe, when GeckoView started :gpu, :tab*
        // and :crashhelper of its own; it still holds for the same reason.)
        if (!isMainProcess()) return
        DiagnosticsLog.init(this)
        // Install before any other app work so initialization failures are captured synchronously.
        CrashReporter.init(this)
        DiagnosticsLog.event("MiruroApp.onCreate start")
        DiagnosticsLog.installLifecycleCallbacks(this)
        DiagnosticsLog.startMainThreadWatchdog()
        DiagnosticsLog.installApplicationStartInfoListener(this)
        DiagnosticsLog.snapshot(this, "MiruroApp.onCreate")
        DiagnosticsLog.event(
            "MiruroApp process=${currentProcessName() ?: "unknown"} pid=${Process.myPid()} " +
                "thread=${Thread.currentThread().name} abis=${Build.SUPPORTED_ABIS.joinToString()}",
        )
        // Do not query WebView during ordinary startup. Some provider builds initialize the
        // Chromium process even for metadata-looking calls. The actual resolver/login/player
        // factories record the provider after a WebView is genuinely needed.
        if (isDiagnosticsProcess()) {
            DiagnosticsLog.event("MiruroApp diagnostics process; skipping normal app init")
            return
        }
        DiagnosticsSystemMonitor.install(this)
        DiagnosticsPerformanceMonitor.install(this)
        // Off the startup path deliberately. Opening Media3's download index scans the cache
        // directory and opens SQLite — measured at 211ms of the 440ms spent in onCreate on a TV
        // stick, with an empty cache, and it grows with the number of downloads. Nothing during
        // startup reads it: the first real consumer is the Library screen, and every entry point
        // into EpisodeDownloads initializes on demand behind the same lock, so a caller that
        // arrives early simply waits for this to finish rather than racing it.
        startupScope.launch {
            // First, so a report from a device that "just closed" opens with the reason it closed.
            DiagnosticsLog.deviceProfile(this@MiruroApp)
            DiagnosticsLog.logPreviousExits(this@MiruroApp)
            // Uploads are user-driven now. Cancel requests and remove reports left by versions
            // that handed diagnostic sending to WorkManager.
            runCatching {
                androidx.work.WorkManager.getInstance(this@MiruroApp)
                    .cancelAllWorkByTag("diagnostics-upload")
                    .result
                    .get()
            }.onFailure { DiagnosticsLog.throwable("Legacy diagnostics work cancellation failed", it) }
            DiagnosticsShareManager.discardLegacyPendingReports(this@MiruroApp)
            runCatching { EpisodeDownloads.initialize(this@MiruroApp) }
                .onFailure { DiagnosticsLog.throwable("EpisodeDownloads initialization failed", it) }
            // Downloads and exports enqueue one uniquely-named work request per episode, so a
            // batch leaves a long tail of finished rows behind. A device on Android 10 died
            // reading that table — CursorWindowAllocationException, error -12 (out of memory) —
            // so keep it from growing without bound.
            runCatching { androidx.work.WorkManager.getInstance(this@MiruroApp).pruneWork() }
                .onFailure { DiagnosticsLog.throwable("WorkManager prune failed", it) }
        }
        if (BuildConfig.DEBUG) configureStrictMode()
        // First of the stores: a profile switch moves the account files into place, and nothing may
        // have opened them yet (Profiles.init).
        diagnosticsStep("Profiles.init") { com.anilili.data.profiles.Profiles.init(this) }
        // Warmed on a background thread; the first challenged request is many seconds away and a
        // request that arrives before this lands simply finds no stored clearance and asks again.
        diagnosticsStep("CloudflareClearanceStore.init") {
            com.anilili.data.remote.CloudflareClearanceStore.init(this, startupScope)
        }
        diagnosticsStep("AppGraph.init") { AppGraph.init(this) }
        diagnosticsStep("LibraryStore.init") { LibraryStore.init(this) }
        diagnosticsStep("SearchHistoryStore.init") { com.anilili.data.library.SearchHistoryStore.init(this) }
        diagnosticsStep("NetworkIdentity.init") { com.anilili.data.NetworkIdentity.init(this) }
        diagnosticsStep("ProviderSpeed.init") { com.anilili.data.ProviderSpeed.init(this) }
        diagnosticsStep("SettingsSearchHistory.init") {
            com.anilili.data.settings.SettingsSearchHistory.init(this)
        }
        diagnosticsStep("AuthManager.init") { AuthManager.init(this) }
        diagnosticsStep("MalAuthManager.init") { MalAuthManager.init(this, AppGraph.httpClient) }
        diagnosticsStep("SimklAuthManager.init") {
            com.anilili.data.auth.SimklAuthManager.init(this, AppGraph.httpClient)
            com.anilili.data.remote.SimklTracker.init(this)
        }
        diagnosticsStep("OpenSubtitlesAuthManager.init") {
            com.anilili.data.auth.OpenSubtitlesAuthManager.init(this, AppGraph.openSubtitles)
        }
        diagnosticsStep("SettingsStore.init") { SettingsStore.init(this) }
        diagnosticsStep("DiscordAuthManager.init") {
            com.anilili.data.discord.DiscordAuthManager.init(this, AppGraph.httpClient)
        }
        // After SettingsStore, whose switch it observes to decide whether to publish at all.
        diagnosticsStep("DiscordPresence.init") { com.anilili.data.discord.DiscordPresence.init(this) }
        diagnosticsStep("ReminderManager.init") { ReminderManager.init(this) }
        diagnosticsStep("AutomaticReleaseManager.init") { AutomaticReleaseManager.init(this) }
        diagnosticsStep("AniListNotificationPushManager.init") { AniListNotificationPushManager.init(this) }
        diagnosticsStep("ReleaseSyncScheduler.schedule") { ReleaseSyncScheduler.schedule(this) }
        // After SettingsStore has *loaded*, not merely started: its switch decides whether the poll
        // is scheduled at all, and until the DataStore read lands it reads as the default "on",
        // which re-enqueued the poll on every process start for a viewer who had turned it off.
        startupScope.launch {
            SettingsStore.awaitLoaded()
            runCatching { AiringPollScheduler.sync(this@MiruroApp) }
                .onFailure { DiagnosticsLog.throwable("AiringPollScheduler.sync failed", it) }
        }
        // After SettingsStore, whose switch the check consults before it fetches anything.
        diagnosticsStep("AnnouncementManager.init") { AnnouncementManager.init(this) }
        diagnosticsStep("AnnouncementScheduler.schedule") { AnnouncementScheduler.schedule(this) }
        diagnosticsStep("UsagePing.init") { com.anilili.data.usage.UsagePing.init(this) }
        // The launcher icon follows the accent, but only switches once the app is out of sight:
        // some launchers redraw or drop the icon when its entry moves (AppIcon).
        diagnosticsStep("AppIcon observer") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) AppIcon.sync(this)
                },
            )
        }
        // A profile switch is a change of account, so it gets what a fresh sign-in gets.
        if (com.anilili.data.profiles.Profiles.justSwitched) {
            diagnosticsStep("Profiles account refresh") {
                AniListNotificationPushManager.clearDelivered(this)
                ReleaseSyncScheduler.runNow(this)
                AiringPollScheduler.runNow(this)
            }
        }
        DiagnosticsLog.event("MiruroApp.onCreate complete")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticsLog.event("MiruroApp.onTrimMemory level=$level")
        DiagnosticsLog.snapshot(this, "trimMemory")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DiagnosticsLog.event("MiruroApp.onLowMemory")
        DiagnosticsLog.snapshot(this, "lowMemory")
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Share the app's connection pool and dispatcher, but not its response cache: Coil keeps
        // its own disk cache below, and leaving OkHttp's 50 MB one attached would store every
        // poster twice over — and let image traffic evict the API responses that cache exists for.
        .okHttpClient { AppGraph.httpClient.newBuilder().cache(null).build() }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(if (AppGraph.isTv) 0.12 else 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(imageDiskCacheBytes())
                .build()
        }
        .crossfade(150)
        .respectCacheHeaders(false)
        .build()

    /**
     * Poster disk cache: 128 MB on a television, 256 MB elsewhere, and on a device nearly out of
     * space no more than a twentieth of what is free — posters re-download cheaply, and a full disk
     * is what stops downloads and updates. Measured once, when Coil first opens the cache.
     */
    private fun imageDiskCacheBytes(): Long {
        val ceiling = if (AppGraph.isTv) 128L * 1024 * 1024 else 256L * 1024 * 1024
        val free = runCatching { android.os.StatFs(cacheDir.absolutePath).availableBytes }.getOrNull()
            ?: return ceiling
        return (free / 20).coerceIn(32L * 1024 * 1024, ceiling)
    }

    private inline fun diagnosticsStep(name: String, block: () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        DiagnosticsLog.event("$name start")
        try {
            block()
            DiagnosticsLog.event("$name complete in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (throwable: Throwable) {
            DiagnosticsLog.throwable("$name failed after ${SystemClock.elapsedRealtime() - startedAt}ms", throwable)
            throw throwable
        }
    }

    private fun configureStrictMode() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder().detectAll()
        val vmPolicy = StrictMode.VmPolicy.Builder().detectLeakedClosableObjects()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "anilili-strict-mode").apply { isDaemon = true }
            }
            threadPolicy.penaltyListener(executor) { violation ->
                DiagnosticsLog.throwable("StrictMode thread violation", violation)
            }
            vmPolicy.penaltyListener(executor) { violation ->
                DiagnosticsLog.throwable("StrictMode VM violation", violation)
            }
        } else {
            threadPolicy.penaltyLog()
            vmPolicy.penaltyLog()
        }
        StrictMode.setThreadPolicy(threadPolicy.build())
        StrictMode.setVmPolicy(vmPolicy.build())
    }

    private fun isDiagnosticsProcess(): Boolean = currentProcessName() == "$packageName:diagnostics"

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            @Suppress("DEPRECATION")
            (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
}

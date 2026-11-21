package com.anilili.data.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Polls AniList's notification feed and pushes whatever is new.
 *
 * AniList offers no push channel — no webhooks, no FCM — so an airing alert can only be a poll,
 * and the poll interval is the worst-case lateness. This is one GraphQL call, so it can run on
 * WorkManager's fifteen-minute floor; [ReleaseSyncWorker] keeps its three-hour cadence because
 * its job is the far heavier list-and-media fetch behind the local release alarms.
 */
class AiringPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A wake that cold-started the process gets here before the DataStore read lands, and the
        // in-memory default is "on": wait for the stored switch rather than poll against it.
        SettingsStore.awaitLoaded()
        if (!SettingsStore.releaseNotifications.value) return Result.success()
        // Logged-out users have no feed to read; their alerts are local alarms instead.
        if (!AuthManager.isLoggedIn) return Result.success()

        return try {
            val (items, unread) = AppGraph.repository.notifications(markAllRead = false)
            NotificationCenter.setUnread(unread)
            AniListNotificationPushManager.notifyUnread(applicationContext, items)
            DiagnosticsLog.event(
                "airing_poll",
                "work.succeeded",
                mapOf("items" to items.size, "unread" to unread),
            )
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            DiagnosticsLog.event("airing_poll", "work.failed", mapOf("error" to error.javaClass.simpleName))
            Result.retry()
        }
    }
}

object AiringPollScheduler {
    private const val PERIODIC_NAME = "anilist-airing-poll"
    private const val IMMEDIATE_NAME = "anilist-airing-poll-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Whether the periodic poll should exist at all: only for a viewer who wants release
     * notifications *and* has an AniList feed to read. The worker returns at once for anyone else,
     * but each fifteen-minute wake of a dead process still pays for the whole of
     * `MiruroApp.onCreate`, so a viewer it cannot serve must not have it scheduled.
     */
    internal fun shouldPoll(releaseNotifications: Boolean, loggedIn: Boolean): Boolean =
        releaseNotifications && loggedIn

    /**
     * Brings the schedule in line with the setting and the AniList sign-in. Safe to call on every
     * launch: `KEEP` leaves an already-scheduled poll on its own cadence rather than restarting its
     * period each time. [enabled] is the release-notifications switch; the sign-in is read here, so
     * every caller gets the same rule.
     */
    fun sync(context: Context, enabled: Boolean = SettingsStore.releaseNotifications.value) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (!shouldPoll(enabled, AuthManager.isLoggedIn)) {
            work.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AiringPollWorker>(15, TimeUnit.MINUTES)
            .addTag("airing-poll")
            .setConstraints(network)
            // Well past AniList's one-minute rate-limit timeout, so a 429 cannot turn into a
            // retry storm on the next tick.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One immediate pass, for the moment the user signs in or turns notifications on. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AiringPollWorker>()
            .addTag("airing-poll")
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

package com.anilili.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.anilili.MainActivity
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.R
import com.anilili.ui.nav.Routes

/**
 * Runs one episode export as long-running foreground work.
 *
 * WorkManager rather than a hand-rolled service: an export is usually kicked off by a download
 * finishing, which can happen while the app is in the background — exactly the case where starting
 * a foreground service directly throws on Android 12+. WorkManager owns that dance, and it also
 * survives process death, so a transmux interrupted by a low-memory kill resumes instead of leaving
 * a half-written file behind.
 */
class EpisodeExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val downloadId: String? get() = inputData.getString(KEY_DOWNLOAD_ID)

    override suspend fun doWork(): Result {
        val id = downloadId ?: return Result.failure()
        // A transmux and a running player are the two biggest heap consumers in this process, and
        // they share one. A device report caught both dying together: OutOfMemoryError on
        // ExoPlayer:Playback and on Transformer:Internal in the same second, at a 256 MB ceiling,
        // on a current flagship. Exports are background convenience work with no deadline, so
        // they yield to playback rather than compete with it. "Playback" is the whole session —
        // paused, buffering, an embed or an external hand-off included — plus a quiet window
        // after it, not Media3's isPlaying, which is false through every pause and rebuffer.
        if (!PlaybackStatus.quietForBackgroundWork()) {
            DiagnosticsLog.event("Episode export deferred while a playback session is open id=$id")
            return Result.retry()
        }
        // Likewise for a download queue that is still running: the next episode's segments being
        // fetched and this one's being rewrapped is two full-size writes at once on one heap, and
        // two progress notifications at once, which Samsung's System UI groups and re-renders on
        // every tick. EpisodeExport re-arms this the moment the queue goes idle.
        if (EpisodeExport.downloadQueueActive(applicationContext)) {
            DiagnosticsLog.event("Episode export deferred behind running downloads id=$id")
            return Result.retry()
        }
        // One transmux at a time across the whole process. Yielding the worker slot rather than
        // blocking on it lets WorkManager reuse the thread and bring this item back on its own
        // backoff schedule.
        if (!EpisodeExport.tryAcquireExportSlot()) {
            DiagnosticsLog.event("Episode export deferred behind another export id=$id")
            return Result.retry()
        }
        return try {
            // Best effort: if the system refuses the foreground promotion the export still runs,
            // it just loses its notification and the protection that comes with it.
            runCatching { setForeground(foregroundInfo(null)) }
            // Progress arrives every 700ms for the in-app list, but each setForeground re-posts the
            // notification and the system UI re-renders it. Re-posting only a changed percentage,
            // and no more than every NOTIFICATION_MIN_INTERVAL_MS, keeps the shade current without
            // the churn that had System UI janking during a multi-episode download.
            var postedPercent: Int? = null
            var postedAtMs = 0L
            val outcome = EpisodeExport.performExport(applicationContext, id) { percent ->
                val now = SystemClock.elapsedRealtime()
                if (percent != postedPercent && now - postedAtMs >= NOTIFICATION_MIN_INTERVAL_MS) {
                    postedPercent = percent
                    postedAtMs = now
                    runCatching { setForeground(foregroundInfo(percent)) }
                }
            }
            // A failure is already recorded in the export status and surfaced in the library, so
            // the work itself is done either way; retrying a codec or storage failure would loop.
            // An export that stood aside for playback is not a failure: it goes round again.
            when {
                outcome.isSuccess -> Result.success()
                outcome.exceptionOrNull() is ExportYieldedToPlayback -> Result.retry()
                else -> Result.failure()
            }
        } finally {
            EpisodeExport.releaseExportSlot()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(percent: Int?): ForegroundInfo {
        val context = applicationContext
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.export_notification_title))
            .setContentText(
                percent?.let { context.getString(R.string.export_notification_progress, it) }
                    ?: context.getString(R.string.export_notification_preparing),
            )
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setContentIntent(downloadsPendingIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun downloadsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Routes.EXTRA_ROUTE, Routes.DOWNLOADS)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.export_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.export_notification_channel_description)
            },
        )
    }

    companion object {
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val CHANNEL_ID = "episode_exports"
        private const val NOTIFICATION_ID = 7_301
        private const val NOTIFICATION_MIN_INTERVAL_MS = 3_000L

        private fun workName(downloadId: String) = "episode-export:$downloadId"

        /**
         * Queues the export for [downloadId].
         *
         * [restart] replaces any work already queued under the same name, which is how a deferred
         * export gets its attempt count back. Everything else keeps what is there: the observer
         * fires on every download-list change, and an export already under way must not be
         * restarted from zero each time.
         */
        fun enqueue(context: Context, downloadId: String, restart: Boolean = false) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(downloadId),
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EpisodeExportWorker>()
                    .addTag("episode-export")
                    .setInputData(workDataOf(KEY_DOWNLOAD_ID to downloadId))
                    // WorkManager's default is exponential from 30s, which is right for a failing
                    // request and wrong for one that merely waited its turn: a handful of early
                    // deferrals while the viewer was watching pushed the next attempt hours out,
                    // and a device report caught exactly that — four exports finished while two
                    // sat ENQUEUED for over forty minutes. Linear keeps a deferral cheap.
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                    .build(),
            )
        }

    }
}

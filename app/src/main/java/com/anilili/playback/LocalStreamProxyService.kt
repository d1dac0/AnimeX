package com.anilili.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.anilili.MainActivity
import com.anilili.R
import com.anilili.diagnostics.DiagnosticsLog

/**
 * Keeps the in-process loopback proxy alive while another app is the visible player.
 *
 * Without a foreground component Android is free to kill Anilili as soon as VLC covers it. The
 * loopback address then dies even though VLC is still playing. This service owns no second server;
 * it gives the existing one a media-playback lifetime and an explicit stop action.
 */
class LocalStreamProxyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DiagnosticsLog.event("LocalStreamProxy stopped from notification")
            LocalStreamProxy.shutdown()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocalStreamProxy.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.external_playback_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.external_playback_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.external_playback_notification_title))
        .setContentText(getString(R.string.external_playback_notification_description))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            getString(R.string.external_playback_notification_stop),
            PendingIntent.getService(
                this,
                STOP_REQUEST_CODE,
                intent(this).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "external_playback"
        // Distinct from EpisodeExportWorker's 7_301: both can be foreground at once, and a shared
        // id let the export's setForeground overwrite this ongoing notification and WorkManager
        // clear it when the export finished.
        private const val NOTIFICATION_ID = 7_401
        private const val OPEN_REQUEST_CODE = 7_302
        private const val STOP_REQUEST_CODE = 7_303
        private const val ACTION_STOP = "com.anilili.playback.STOP_LOCAL_STREAM_PROXY"

        fun intent(context: Context): Intent = Intent(context, LocalStreamProxyService::class.java)

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, intent(context))
            true
        }.getOrElse { error ->
            DiagnosticsLog.throwable("LocalStreamProxy foreground service failed to start", error)
            false
        }
    }
}

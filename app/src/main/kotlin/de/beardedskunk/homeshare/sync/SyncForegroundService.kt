package de.beardedskunk.homeshare.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import de.beardedskunk.homeshare.ClipApplication
import de.beardedskunk.homeshare.MainActivity

/**
 * Hält den Gerät-zu-Gerät-Sync am Leben, auch wenn die App im Hintergrund/Standby ist.
 *
 * Hintergrund (W8): Ohne Vordergrund-Service stellt Android schlafende Geräte (Doze /
 * App-Standby) netzwerkseitig ab – der eingebettete Sync-Server ist dann von außen NICHT
 * erreichbar (Verbindungen laufen in den Timeout). Das war die eigentliche Ursache der
 * „mal erreichbar, mal nicht"-Sync-Aussetzer. Ein Foreground-Service mit dauerhafter
 * (unaufdringlicher) Notification nimmt die App von diesen Einschränkungen aus; ein
 * Partial-WakeLock hält zusätzlich die CPU für den Abgleich wach.
 *
 * Benötigt im Manifest: <service android:name=".sync.SyncForegroundService"
 * android:foregroundServiceType="dataSync"/> + FOREGROUND_SERVICE(_DATA_SYNC)/POST_NOTIFICATIONS/WAKE_LOCK.
 */
class SyncForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homeshare:sync")
            .apply { setReferenceCounted(false); runCatching { acquire() } }
        // Sync starten (idempotent); nur, wenn der Schalter an ist.
        (application as ClipApplication).graph.autoSync.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Geräte-Sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Hält den Abgleich zwischen deinen Geräten aktiv."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HomeShare")
            .setContentText("Geräte-Abgleich aktiv")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIF_ID = 1

        /** Service starten; faellt zurueck auf direkten AutoSync-Start, falls (noch) nicht im Manifest. */
        fun start(context: Context) {
            val i = Intent(context, SyncForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure {
                    runCatching { (context.applicationContext as ClipApplication).graph.autoSync.start() }
                }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncForegroundService::class.java)) }
        }
    }
}

package com.example.phoneremotewatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps one shared Bluetooth link for the whole app. */
object LinkHolder {
    @Volatile
    private var instance: BleLink? = null

    fun get(context: Context): BleLink {
        val existing = instance
        if (existing != null) return existing
        synchronized(this) {
            val again = instance
            if (again != null) return again
            val created = BleLink(context.applicationContext)
            instance = created
            return created
        }
    }
}

/** Foreground service so the link and the music notification keep working in the background. */
class LinkService : Service() {

    companion object {
        const val ACTION_START = "com.example.phoneremotewatch.START"
        const val ACTION_RESTART = "com.example.phoneremotewatch.RESTART"
        const val ACTION_STOP = "com.example.phoneremotewatch.STOP"
        const val EXTRA_OPEN_MUSIC = "open_music"
        private const val CHANNEL_ID = "link"
        private const val NOTIFICATION_ID = 7
    }

    private lateinit var link: BleLink

    override fun onCreate() {
        super.onCreate()
        link = LinkHolder.get(this)
        link.listener = { refreshNotification() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            link.listener = null
            link.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            link.status = "Service start failed: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART || !link.running) {
            link.start()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone link", NotificationManager.IMPORTANCE_LOW)
        )
        val hasMusic = link.musicTitle.isNotEmpty() && link.connectedAddress != null
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_MUSIC, hasMusic)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (hasMusic) "♪ ${link.musicTitle}" else "Phone Remote"
        val text = when {
            hasMusic -> if (link.musicArtist.isNotEmpty()) link.musicArtist else "Playing on iPhone"
            link.connectedAddress != null -> "iPhone connected"
            else -> "Waiting for the iPhone"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Ignore notification problems.
        }
    }

    override fun onDestroy() {
        link.listener = null
        super.onDestroy()
    }
}

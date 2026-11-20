package com.example.phoneremotewatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

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

/** Foreground service: link, song notification, call alerts and music haptics keep working in the background. */
class LinkService : Service() {

    companion object {
        const val ACTION_START = "com.example.phoneremotewatch.START"
        const val ACTION_RESTART = "com.example.phoneremotewatch.RESTART"
        const val ACTION_STOP = "com.example.phoneremotewatch.STOP"
        const val ACTION_ANSWER = "com.example.phoneremotewatch.ANSWER"
        const val ACTION_DECLINE = "com.example.phoneremotewatch.DECLINE"
        const val ACTION_PLAYPAUSE = "com.example.phoneremotewatch.PLAYPAUSE"
        const val ACTION_NEXT = "com.example.phoneremotewatch.NEXT"
        const val ACTION_PREV = "com.example.phoneremotewatch.PREV"
        const val EXTRA_OPEN_MUSIC = "open_music"
        const val EXTRA_OPEN_CALL = "open_call"
        private const val CHANNEL_ID = "link"
        private const val CALL_CHANNEL = "calls"
        private const val NOTIFICATION_ID = 7
        private const val CALL_NOTIFICATION_ID = 8
    }

    private lateinit var link: BleLink
    private var session: MediaSession? = null
    private var haptics: HapticsEngine? = null
    private var ringtone: Ringtone? = null
    private var ringing = false
    private var micType = false
    private var started = false

    override fun onCreate() {
        super.onCreate()
        link = LinkHolder.get(this)
        createChannels()
        val openMusic = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MUSIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession(this, "PhoneRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!link.musicPlaying) link.sendEvent("MP")
                }

                override fun onPause() {
                    if (link.musicPlaying) link.sendEvent("MP")
                }

                override fun onSkipToNext() {
                    link.sendEvent("MN")
                }

                override fun onSkipToPrevious() {
                    link.sendEvent("MV")
                }
            })
            setSessionActivity(openMusic)
        }
        link.listener = { onLinkChanged() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_ANSWER -> {
                link.answerCall()
                return START_NOT_STICKY
            }
            ACTION_DECLINE -> {
                link.declineCall()
                return START_NOT_STICKY
            }
            ACTION_PLAYPAUSE -> {
                link.sendEvent("MP")
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                link.sendEvent("MN")
                return START_NOT_STICKY
            }
            ACTION_PREV -> {
                link.sendEvent("MV")
                return START_NOT_STICKY
            }
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART || !link.running) {
            link.start()
        }
        return START_NOT_STICKY
    }

    private fun micGranted(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun enterForeground(): Boolean {
        val wantMic = link.hapticsOn && micGranted()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (wantMic) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
            micType = wantMic
            started = true
            return true
        } catch (e: Exception) {
            if (wantMic) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                    micType = false
                    started = true
                    return true
                } catch (e2: Exception) {
                    link.status = "Service start failed: ${e2.message}"
                    return false
                }
            }
            link.status = "Service start failed: ${e.message}"
            return false
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone link and music", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun servicePending(action: String, code: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            code,
            Intent(this, LinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun action(resId: Int, label: String, pending: PendingIntent): Notification.Action {
        return Notification.Action.Builder(Icon.createWithResource(this, resId), label, pending).build()
    }

    private fun buildNotification(): Notification {
        val hasMusic = link.musicTitle.isNotEmpty() && link.connectedAddress != null
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MUSIC, hasMusic)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
        if (hasMusic) {
            builder.setContentTitle(link.musicTitle)
            builder.setContentText(if (link.musicArtist.isNotEmpty()) link.musicArtist else "Playing on iPhone")
            val art = link.musicArt
            if (art != null) {
                builder.setLargeIcon(art)
            }
            builder.addAction(action(android.R.drawable.ic_media_previous, "Previous", servicePending(ACTION_PREV, 21)))
            builder.addAction(
                action(
                    if (link.musicPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (link.musicPlaying) "Pause" else "Play",
                    servicePending(ACTION_PLAYPAUSE, 22)
                )
            )
            builder.addAction(action(android.R.drawable.ic_media_next, "Next", servicePending(ACTION_NEXT, 23)))
            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            val s = session
            if (s != null) {
                style.setMediaSession(s.sessionToken)
            }
            builder.setStyle(style)
            builder.setCategory(Notification.CATEGORY_TRANSPORT)
        } else {
            builder.setContentTitle("Phone Remote")
            builder.setContentText(
                if (link.connectedAddress != null) "iPhone connected" else "Waiting for the iPhone"
            )
        }
        return builder.build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Ignore notification problems.
        }
    }

    private fun updateSession() {
        val s = session ?: return
        val has = link.musicTitle.isNotEmpty() && link.connectedAddress != null
        if (!has) {
            s.isActive = false
            return
        }
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, link.musicTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, link.musicArtist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, link.musicDurationSec * 1000L)
        val art = link.musicArt
        if (art != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        }
        s.setMetadata(md.build())
        val elapsed = if (link.musicPlaying) SystemClock.elapsedRealtime() - link.musicStamp else 0L
        val position = link.musicPositionSec * 1000L + elapsed
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (link.musicPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (link.musicPlaying) 1f else 0f,
                SystemClock.elapsedRealtime()
            )
            .build()
        s.setPlaybackState(state)
        s.isActive = true
    }

    private fun updateCall() {
        val manager = getSystemService(NotificationManager::class.java)
        if (link.callActive) {
            val open = PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_CALL, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = Notification.Builder(this, CALL_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming call")
                .setContentText(link.callName)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
            if (link.callNegative.isNotEmpty()) {
                builder.addAction(
                    action(android.R.drawable.ic_menu_close_clear_cancel, link.callNegative, servicePending(ACTION_DECLINE, 31))
                )
            }
            if (link.callPositive.isNotEmpty()) {
                builder.addAction(
                    action(android.R.drawable.sym_action_call, link.callPositive, servicePending(ACTION_ANSWER, 32))
                )
            }
            try {
                manager.notify(CALL_NOTIFICATION_ID, builder.build())
            } catch (e: Exception) {
                // Ignore.
            }
            if (link.ringOnWatch && !ringing) {
                startRinging()
            } else if (!link.ringOnWatch && ringing) {
                stopRinging()
            }
        } else {
            try {
                manager.cancel(CALL_NOTIFICATION_ID)
            } catch (e: Exception) {
                // Ignore.
            }
            stopRinging()
        }
    }

    private fun startRinging() {
        ringing = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(this, uri)
            if (r != null) {
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.isLooping = true
                r.play()
                ringtone = r
            }
        } catch (e: Exception) {
            // The watch may have no ringtone. Vibration still works.
        }
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400), 0))
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun stopRinging() {
        if (!ringing) return
        ringing = false
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // Ignore.
        }
        ringtone = null
        try {
            getSystemService(Vibrator::class.java)?.cancel()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun updateHaptics() {
        val want = link.hapticsOn && link.musicPlaying && link.musicTitle.isNotEmpty() && micGranted()
        if (want) {
            if (haptics == null) {
                haptics = HapticsEngine(this)
            }
            haptics?.start(link.hapticsLevel)
        } else {
            haptics?.stop()
            haptics = null
        }
    }

    private fun onLinkChanged() {
        if (!started) return
        val wantMic = link.hapticsOn && micGranted()
        if (wantMic != micType) {
            enterForeground()
        } else {
            refreshNotification()
        }
        updateSession()
        updateCall()
        updateHaptics()
    }

    private fun shutdown() {
        link.listener = null
        haptics?.stop()
        haptics = null
        stopRinging()
        link.stop()
        try {
            getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Ignore.
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        link.listener = null
        haptics?.stop()
        stopRinging()
        try {
            session?.release()
        } catch (e: Exception) {
            // Ignore.
        }
        session = null
        super.onDestroy()
    }
}

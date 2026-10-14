package com.pockettranspose.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.pockettranspose.MainActivity
import com.pockettranspose.R

/**
 * Media-playback foreground service that keeps the app process (and its WebView
 * renderer) alive and unfrozen while page media plays with the app in the
 * background. It does not touch the page: whether a site allows background
 * playback (e.g. YouTube only for Premium) is decided by the site itself.
 *
 * Started while media plays; after a pause it stays briefly (so a playlist moving
 * to the next item does not need a background restart, which Android 12+ forbids)
 * and then stops itself.
 */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_MEDIA -> pauseMediaHandler?.invoke()
            ACTION_STOP -> {
                pauseMediaHandler?.invoke()
                stopSelf()
            }
            else -> showPlaying(intent?.getStringExtra(EXTRA_DETAIL).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun showPlaying(detail: String) {
        handler.removeCallbacks(stopRunnable)
        val notification = buildNotification(playing = true, detail = detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun showPaused() {
        releaseWakeLock()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(playing = false, detail = "Paused"))
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, STOP_AFTER_PAUSE_MS)
    }

    private fun buildNotification(playing: Boolean, detail: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (playing) "PocketTranspose is playing" else "PocketTranspose")
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (playing) {
            builder.addAction(Notification.Action.Builder(null, "Pause", serviceIntent(ACTION_PAUSE_MEDIA, 1)).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Stop", serviceIntent(ACTION_STOP, 2)).build())
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while PocketTranspose plays audio in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Keeps decoding running with the screen off; bounded so it can never leak.
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketTranspose:playback")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    companion object {
        private const val TAG = "PocketTransposeJS"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAYING = "com.pockettranspose.action.PLAYING"
        private const val ACTION_PAUSE_MEDIA = "com.pockettranspose.action.PAUSE_MEDIA"
        private const val ACTION_STOP = "com.pockettranspose.action.STOP"
        private const val EXTRA_DETAIL = "detail"
        private const val STOP_AFTER_PAUSE_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        private var instance: PlaybackService? = null

        /** Set by the activity: pauses page media (notification "Pause"/"Stop"). */
        var pauseMediaHandler: (() -> Unit)? = null

        fun onPlaying(context: Context, detail: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAYING)
                .putExtra(EXTRA_DETAIL, detail)
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // Android 12+ refuses to start a foreground service from the background
                // (ForegroundServiceStartNotAllowedException extends IllegalStateException).
                Log.w(TAG, "playback service not started: ${e.javaClass.simpleName}")
            }
        }

        fun onPaused() {
            instance?.showPaused()
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}

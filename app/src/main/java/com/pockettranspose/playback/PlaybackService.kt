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
import android.widget.RemoteViews
import com.pockettranspose.MainActivity
import com.pockettranspose.R
import com.pockettranspose.settings.AudioControl
import com.pockettranspose.settings.AudioValues
import java.util.Locale

/**
 * Media-playback foreground service that keeps the app process (and its WebView
 * renderer) alive and unfrozen while page media plays with the app in the
 * background. It does not touch the page: whether a site allows background
 * playback (e.g. YouTube only for Premium) is decided by the site itself.
 *
 * Started while media plays; after a pause it stays briefly (so a playlist moving
 * to the next item does not need a background restart, which Android 12+ forbids)
 * and then stops itself.
 *
 * Its notification doubles as a small remote control: -/+ for key and speed when
 * collapsed, plus volume when expanded. Taps are forwarded to the activity, which
 * steps the value exactly like the control sheet's own -/+ buttons.
 */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }
    private var wakeLock: PowerManager.WakeLock? = null
    private var playing = false

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
            ACTION_ADJUST -> {
                val control = intent.getStringExtra(EXTRA_CONTROL)
                    ?.let { name -> AudioControl.entries.firstOrNull { it.name == name } }
                val direction = intent.getIntExtra(EXTRA_DIRECTION, 0)
                if (control != null && direction != 0) adjustHandler?.invoke(control, direction)
            }
            else -> showPlaying()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun showPlaying() {
        handler.removeCallbacks(stopRunnable)
        playing = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
    }

    private fun showPaused() {
        releaseWakeLock()
        playing = false
        refreshNotification()
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, STOP_AFTER_PAUSE_MS)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val values = currentValues
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (playing) "PocketTranspose is playing" else "PocketTranspose")
            .setContentText(summary(values))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(controlsView(R.layout.notification_controls_small, values, withGain = false))
            .setCustomBigContentView(controlsView(R.layout.notification_controls_big, values, withGain = true))
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

    private fun controlsView(layout: Int, values: AudioValues, withGain: Boolean): RemoteViews =
        RemoteViews(packageName, layout).apply {
            setTextViewText(R.id.pitch_value, "Key %+d".format(Locale.US, values.pitchSemitone))
            setTextViewText(R.id.speed_value, "Speed %.2fx".format(Locale.US, values.playbackRate))
            bindStepper(this, AudioControl.PITCH, R.id.pitch_minus, R.id.pitch_plus, "key")
            bindStepper(this, AudioControl.SPEED, R.id.speed_minus, R.id.speed_plus, "speed")
            if (withGain) {
                setTextViewText(R.id.state, if (playing) "Playing" else "Paused")
                setTextViewText(R.id.gain_value, "Volume %.2f".format(Locale.US, values.gain))
                bindStepper(this, AudioControl.GAIN, R.id.gain_minus, R.id.gain_plus, "volume")
            }
        }

    private fun bindStepper(views: RemoteViews, control: AudioControl, minusId: Int, plusId: Int, label: String) {
        views.setOnClickPendingIntent(minusId, adjustIntent(control, -1))
        views.setOnClickPendingIntent(plusId, adjustIntent(control, +1))
        views.setContentDescription(minusId, "Lower $label")
        views.setContentDescription(plusId, "Raise $label")
    }

    // One PendingIntent per control and direction: distinct request codes keep their
    // extras from overwriting each other.
    private fun adjustIntent(control: AudioControl, direction: Int): PendingIntent = PendingIntent.getService(
        this, ADJUST_REQUEST_BASE + control.ordinal * 2 + (if (direction > 0) 1 else 0),
        Intent(this, PlaybackService::class.java)
            .setAction(ACTION_ADJUST)
            .putExtra(EXTRA_CONTROL, control.name)
            .putExtra(EXTRA_DIRECTION, direction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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
        private const val ACTION_ADJUST = "com.pockettranspose.action.ADJUST"
        private const val EXTRA_CONTROL = "control"
        private const val EXTRA_DIRECTION = "direction"
        private const val ADJUST_REQUEST_BASE = 10
        private const val STOP_AFTER_PAUSE_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        private var instance: PlaybackService? = null

        private var currentValues = AudioValues(pitchSemitone = 0, playbackRate = 1f, gain = 1f)

        /** Set by the activity: pauses page media (notification "Pause"/"Stop"). */
        var pauseMediaHandler: (() -> Unit)? = null

        /** Set by the activity: steps a value (notification -/+). */
        var adjustHandler: ((AudioControl, Int) -> Unit)? = null

        fun summary(values: AudioValues): String =
            "Key %+d · %.2fx · volume %.2f".format(Locale.US, values.pitchSemitone, values.playbackRate, values.gain)

        /** Shows new values in the notification if it is up; remembered for the next one. */
        fun updateValues(values: AudioValues) {
            if (values == currentValues) return
            currentValues = values
            instance?.refreshNotification()
        }

        fun onPlaying(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAYING)
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

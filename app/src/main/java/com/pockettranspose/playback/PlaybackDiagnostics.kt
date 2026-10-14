package com.pockettranspose.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.pockettranspose.MainActivity
import com.pockettranspose.R

/**
 * Explains, as a notification, why background playback stopped, so it can be
 * diagnosed on the phone without adb. Driven by the hook's
 * "media paused while page hidden by=<source>" line.
 */
object PlaybackDiagnostics {
    private const val CHANNEL_ID = "playback_diagnostics"
    private const val NOTIFICATION_ID = 2

    fun notifyHiddenPause(context: Context, source: String, identifyAsChrome: Boolean) {
        val (title, text) = when (source) {
            "page-script" -> "Background playback stopped by the page" to
                if (identifyAsChrome) {
                    "The site's own script paused it (by=page-script) even with \"Identify as Chrome\" on. " +
                        "For YouTube this means background play is not offered to this account or app."
                } else {
                    "The site's own script paused it (by=page-script). For YouTube Premium, turn on " +
                        "\"Identify as Chrome\" in the Transpose controls and try again."
                }
            "browser" -> "Background playback stopped by WebView" to
                "WebView paused the hidden media itself (by=browser), without any page script. " +
                    "\"Identify as Chrome\" will not help with this."
            else -> "Background playback stopped" to "Paused while hidden (by=$source)."
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback diagnostics", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Explains why background playback stopped"
            }
        )
        val openApp = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

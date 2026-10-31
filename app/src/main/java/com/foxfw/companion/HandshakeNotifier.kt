package com.foxfw.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Fires a system notification + buzz when the Marauder relay reports a captured handshake. */
object HandshakeNotifier {
    private const val CHANNEL_ID = "foxfw_captures"
    private var nextId = 2200

    fun notifyCapture(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()
        // Posting is a no-op (not a crash) if POST_NOTIFICATIONS isn't granted on API 33+.
        runCatching { NotificationManagerCompat.from(ctx).notify(nextId++, n) }
        buzz(ctx)
    }

    private fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Captures", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "WiFi handshake / PMKID captures from the Marauder"
                    enableVibration(true)
                },
            )
        }
    }

    private fun buzz(ctx: Context) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        runCatching {
            vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

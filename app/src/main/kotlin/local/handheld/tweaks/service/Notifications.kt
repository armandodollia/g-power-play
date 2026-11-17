package local.handheld.tweaks.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import local.handheld.tweaks.MainActivity
import local.handheld.tweaks.R

object Notifications {
    const val ONGOING_ID = 1
    private const val ALERT_ID = 2
    private const val CH_STATUS = "status"
    private const val CH_ALERTS = "alerts"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Problems", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun ongoing(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_tile_rotation)
            .setContentTitle("Handheld Tweaks active")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateOngoing(ctx: Context, text: String) =
        ctx.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoing(ctx, text))

    fun alert(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_tile_rotation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }
}

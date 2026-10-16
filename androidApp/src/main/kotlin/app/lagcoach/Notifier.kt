package app.lagcoach

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lagcoach.engine.Alert
import app.lagcoach.engine.AlertKind

object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_WATCHING = "watching"
    const val ONGOING_ID = 1
    private const val BRAND = 0xFF0077C8.toInt()

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Bus alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Late, early and cancelled buses on your journeys"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCHING, "Watching a journey", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Lagcoach is checking live times. Required by Android for background checking."
                setShowBadge(false)
            },
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(ctx: Context, title: String, text: String) =
        NotificationCompat.Builder(ctx, CH_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx))
            .build()

    fun updateOngoing(ctx: Context, title: String, text: String) = post(ctx, ONGOING_ID) { ongoing(ctx, title, text) }

    fun alert(ctx: Context, a: Alert) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (a.kind == AlertKind.Cancelled) 0xFFC8102E.toInt() else BRAND)
            .setContentTitle(a.title)
            .setContentText(a.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setSubText(a.watch.label)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setTimeoutAfter(3 * 60 * 60 * 1000L)
            .build()
        post(ctx, a.notificationKey.hashCode() and 0x7FFFFFFF or 0x100) { n }
    }

    fun test(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND)
            .setContentTitle("42 at 07:32 running 8 min late")
            .setContentText("This is a test. Real alerts look like this.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, 2) { n }
    }

    private inline fun post(ctx: Context, id: Int, build: () -> android.app.Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, build())
        } catch (_: SecurityException) {
        }
    }
}

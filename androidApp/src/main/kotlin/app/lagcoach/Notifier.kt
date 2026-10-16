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

    fun tripSummary(t: app.lagcoach.model.OneOffTrip): String {
        val eta = (t.expectedArrival ?: t.scheduledArrival)?.let { app.lagcoach.engine.Schedule.hhmm(kotlin.time.Instant.fromEpochSeconds(it)) }
        val next = t.stops.firstOrNull { it.atco == t.nextStop }?.name
        return when {
            !t.departed -> "Waiting for the bus" + (t.expectedDeparture ?: t.scheduledDeparture)?.let { " · due ${app.lagcoach.engine.Schedule.hhmm(kotlin.time.Instant.fromEpochSeconds(it))}" }.orEmpty()
            next != null -> "Next: $next" + (eta?.let { " · arrive $it" } ?: "")
            else -> eta?.let { "Arrive $it" } ?: "Following the bus"
        }
    }

    fun nextStop(ctx: Context, t: app.lagcoach.model.OneOffTrip) {
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND)
            .setContentTitle("Your stop is next")
            .setContentText(t.to.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setTimeoutAfter(30 * 60 * 1000L)
            .build()
        post(ctx, 3) { n }
    }

    fun tripEnded(ctx: Context, r: app.lagcoach.model.OneOffRecord) {
        if (r.end != app.lagcoach.model.TripEnd.Arrived) return
        val delay = r.arrivalDelayMinutes
        val text = when {
            delay == null -> "Logged in your one-off trips."
            delay >= 2 -> "$delay min later than the timetable. Logged in your one-off trips."
            delay <= -2 -> "${-delay} min early. Logged in your one-off trips."
            else -> "On time. Logged in your one-off trips."
        }
        val n = NotificationCompat.Builder(ctx, CH_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND)
            .setContentTitle("Arrived at ${r.toName.substringBefore(" (")}")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setTimeoutAfter(20 * 60 * 1000L)
            .build()
        post(ctx, 3) { n }
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

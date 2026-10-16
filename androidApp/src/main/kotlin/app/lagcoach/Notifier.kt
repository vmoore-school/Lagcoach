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
    const val CH_TRIP = "trip"
    const val EXTRA_OPEN = "open"
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
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, "Bus you're on", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Live progress of a bus you're tracking to your stop (a Live Update on Android 16+)"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun openApp(ctx: Context, screen: String? = null) = PendingIntent.getActivity(
        ctx, if (screen == null) 0 else 1,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { screen?.let { putExtra(EXTRA_OPEN, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The bus you're on, as an Android 16+ Live Update: a status bar chip with minutes to
     * your stop, and a progress bar of the route with the bus moving along it. Older
     * Android versions show it as a normal ongoing notification with the same progress bar.
     */
    fun tripLive(
        ctx: Context, t: app.lagcoach.model.OneOffTrip, extra: String? = null,
        watching: List<Pair<app.lagcoach.model.Watch, String>> = emptyList(),
    ): android.app.Notification {
        val now = System.currentTimeMillis() / 1000
        val eta = t.expectedArrival ?: t.scheduledArrival
        val due = if (t.departed) eta else (t.expectedDeparture ?: t.scheduledDeparture)
        val mins = due?.let { ((it - now + 30) / 60).toInt() }
        val unit = 12
        val n = t.stops.size.coerceAtLeast(2)
        val nextIdx = t.stops.indexOfFirst { it.atco == t.nextStop }
        val progress = when {
            !t.departed -> 0
            nextIdx <= 0 -> 0
            else -> (((nextIdx - 1) + (t.segmentProgress ?: 0.5).coerceIn(0.0, 1.0)) * unit).toInt()
        }.coerceIn(0, (n - 1) * unit)
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment((n - 1) * unit).setColor(BRAND)))
            .setProgress(progress)
            .setProgressTrackerIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.drawable.ic_bus))
            .setProgressEndIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.drawable.ic_stop_flag))
        // A dot per stop when there's room (the system caps the number of points).
        if (n in 3..14) {
            style.setProgressPoints((1 until n - 1).map { NotificationCompat.ProgressStyle.Point(it * unit).setColor(BRAND) })
        }
        val title = "${t.line} to ${t.to.name.substringBefore(" (")}"
        val text = tripSummary(t) + (extra?.let { "\n$it" } ?: "")
        return NotificationCompat.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setRequestPromotedOngoing(true)
            .apply {
                // Status bar chip: minutes to your stop (or to the bus, while waiting).
                if (mins != null) setShortCriticalText(if (mins <= 0) "Now" else "$mins min")
                setShowWhen(false)
            }
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx, "trip"))
            .apply { notTakingActions(ctx, watching) }
            .build()
    }

    fun updateTripLive(ctx: Context, t: app.lagcoach.model.OneOffTrip, extra: String?, watching: List<Pair<app.lagcoach.model.Watch, String>>) =
        post(ctx, ONGOING_ID) { tripLive(ctx, t, extra, watching) }

    /** A "Not taking" button per journey being watched (the system shows at most three). */
    private fun NotificationCompat.Builder.notTakingActions(ctx: Context, watching: List<Pair<app.lagcoach.model.Watch, String>>) {
        watching.take(3).forEach { (w, date) ->
            val pi = PendingIntent.getBroadcast(
                ctx, w.id.hashCode(),
                Intent(ctx, NotTakingReceiver::class.java)
                    .putExtra(NotTakingReceiver.EXTRA_WATCH, w.id)
                    .putExtra(NotTakingReceiver.EXTRA_DATE, date),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            addAction(0, if (watching.size == 1) "Not taking" else "Not taking ${w.label}", pi)
        }
    }

    fun ongoing(ctx: Context, title: String, text: String, watching: List<Pair<app.lagcoach.model.Watch, String>> = emptyList()) =
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
            .apply { notTakingActions(ctx, watching) }
            .build()

    fun updateOngoing(ctx: Context, title: String, text: String, watching: List<Pair<app.lagcoach.model.Watch, String>>) =
        post(ctx, ONGOING_ID) { ongoing(ctx, title, text, watching) }

    /** Clears alerts already showing for a journey (e.g. after "Not taking"). */
    fun cancelAlerts(ctx: Context, watchId: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.tag == watchId }.forEach { nm.cancel(it.tag, it.id) }
    }

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
        post(ctx, a.notificationKey.hashCode() and 0x7FFFFFFF or 0x100, tag = a.watch.id) { n }
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
            .setContentIntent(openApp(ctx, "trip"))
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

    private inline fun post(ctx: Context, id: Int, tag: String? = null, build: () -> android.app.Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(tag, id, build())
        } catch (_: SecurityException) {
        }
    }
}

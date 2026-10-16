package app.lagcoach

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.lagcoach.engine.Schedule
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * There's no push service on GrapheneOS (or any phone without Play services), so the
 * phone wakes itself: an exact alarm fires when a journey window is about to open and
 * starts [WatchService]. While a window is open a 5-minute watchdog alarm restarts the
 * service if Android killed it.
 */
object Scheduler {
    private const val TAG = "Lagcoach"
    private const val REQ = 10

    fun reschedule(ctx: Context) {
        val now = Clock.System.now()
        val watches = AppGraph.repo.data.value.watches
        val anyActive = watches.any { Schedule.activeWindow(it, now) != null } || AppGraph.repo.data.value.activeTrip != null
        val next = if (anyActive) now + 5.minutes else watches.mapNotNull { Schedule.nextMonitorStart(it, now) }.minOrNull()

        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (next != null) {
            val at = next.toEpochMilliseconds()
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i(TAG, "Next wake at $next (active=$anyActive)")
        }
        if (anyActive) startService(ctx)
    }

    fun startService(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        } catch (e: Exception) {
            // Android refuses background FGS starts in some states; the watchdog alarm retries.
            Log.w(TAG, "Couldn't start watcher", e)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.reschedule(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.reschedule(context)
}

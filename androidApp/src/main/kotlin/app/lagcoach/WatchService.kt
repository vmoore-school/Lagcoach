package app.lagcoach

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import app.lagcoach.engine.Watcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Runs only while a journey window is open: polls bustimes.org every N seconds, raises
 * alerts and keeps a quiet ongoing notification with the next bus. Holds a partial wake
 * lock so polling keeps going with the screen off.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val watcher by lazy { Watcher(AppGraph.repo, AppGraph.client, AppGraph.live) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.ongoing(this, "Watching your journey", "Checking live times…")
        try {
            ServiceCompat.startForeground(
                this, Notifier.ONGOING_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            Log.w("Lagcoach", "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lagcoach:watch").apply { setReferenceCounted(false) }
        try {
            while (true) {
                wakeLock?.acquire(10 * 60 * 1000L)
                val r = runCatching { watcher.tick(Clock.System.now()) }.getOrElse {
                    Log.w("Lagcoach", "tick failed", it)
                    null
                }
                if (r != null) {
                    if (r.active.isEmpty()) break
                    r.alerts.forEach { Notifier.alert(this, it) }
                    val title = if (r.active.size == 1) r.active[0].first.label else "Watching ${r.active.size} journeys"
                    val text = r.active.joinToString("\n") { (w, s) -> if (r.active.size == 1) s else "${w.label}: $s" }
                    Notifier.updateOngoing(this, title, text)
                }
                delay(AppGraph.repo.data.value.settings.pollSeconds.coerceIn(20, 600) * 1000L)
            }
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Scheduler.reschedule(this)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}

package app.lagcoach

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** "Not taking" on the watching notification: stop watching today's window and leave it out of stats. */
class NotTakingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val watchId = intent.getStringExtra(EXTRA_WATCH) ?: return
        val date = intent.getStringExtra(EXTRA_DATE) ?: return
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                notTaking(context, watchId, date)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_WATCH = "watch"
        const val EXTRA_DATE = "date"

        private suspend fun notTaking(ctx: Context, watchId: String, date: String) {
            AppGraph.repo.setSkipped(watchId, date, true)
            Notifier.cancelAlerts(ctx, watchId)
            AppGraph.wake.trySend(Unit)
        }
    }
}

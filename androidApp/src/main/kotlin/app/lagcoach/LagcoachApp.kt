package app.lagcoach

import android.app.Application
import app.lagcoach.data.FileIO
import app.lagcoach.data.Repository

class LagcoachApp : Application() {
    lateinit var platform: AndroidPlatform
        private set

    override fun onCreate() {
        super.onCreate()
        FileIO.dir = filesDir
        platform = AndroidPlatform(this)
        AppGraph.platform = platform
        AppGraph.repo = Repository().also { it.onWatchesChanged = { Scheduler.reschedule(this) } }
        Notifier.createChannels(this)
        Scheduler.reschedule(this)
    }
}

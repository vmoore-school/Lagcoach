package app.lagcoach

import app.lagcoach.data.BusTimesClient
import app.lagcoach.data.Repository
import app.lagcoach.model.Departure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the background watcher last saw for a watch; read by the UI. */
data class WatchLive(
    val watchId: String,
    val updatedAt: Long,
    val relevant: List<Departure>,
    val summary: String,
    val error: String? = null,
)

class LiveStatus {
    private val _state = MutableStateFlow<Map<String, WatchLive>>(emptyMap())
    val state: StateFlow<Map<String, WatchLive>> = _state
    fun put(w: WatchLive) = _state.update { it + (w.watchId to w) }
    fun clear(id: String) = _state.update { it - id }
    fun retain(ids: Set<String>) = _state.update { m -> m.filterKeys { it in ids } }
}

sealed interface GeoResult {
    data class Ok(val lat: Double, val lon: Double) : GeoResult
    data object Denied : GeoResult
    data object Unavailable : GeoResult
}

/** Something the app needs from the OS to work reliably, shown on the settings screen. */
data class HealthItem(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    val action: String?,
    val required: Boolean = true,
    /** Short phrase for the warning banner, e.g. "Notifications are off". */
    val problem: String = title,
)

interface Platform {
    val appVersion: String
    /** Material You (Android 12+). */
    val supportsDynamicColor: Boolean
    val healthTick: StateFlow<Int>
    suspend fun currentLocation(): GeoResult
    fun healthChecks(): List<HealthItem>
    fun fixHealth(id: String)
    fun testNotification()
    fun openUrl(url: String)
    fun watchesChanged()
}

object AppGraph {
    lateinit var repo: Repository
    lateinit var platform: Platform
    val client: BusTimesClient by lazy { BusTimesClient() }
    val live = LiveStatus()
    /** For work that must outlive the screen that started it. */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val tracker: app.lagcoach.engine.OneOffTracker by lazy { app.lagcoach.engine.OneOffTracker(repo, client) }
}

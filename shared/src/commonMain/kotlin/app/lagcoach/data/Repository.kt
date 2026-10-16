package app.lagcoach.data

import app.lagcoach.model.AppData
import app.lagcoach.model.Observation
import app.lagcoach.model.Settings
import app.lagcoach.model.Watch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Reads/writes small text files in the app's private storage. */
expect object FileIO {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

/**
 * Single source of truth for watches, settings and the observation log. Data is small
 * (a few KB of config, roughly one record per tracked bus per day) so it lives in
 * JSON files rather than a database.
 */
class Repository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }
    private val mutex = Mutex()

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _observations = MutableStateFlow(loadObservations())
    val observations: StateFlow<List<Observation>> = _observations.asStateFlow()

    /** Called whenever watches change so the platform can reschedule alarms. */
    var onWatchesChanged: (() -> Unit)? = null

    private fun load(): AppData =
        FileIO.read(CONFIG)?.let { runCatching { json.decodeFromString(AppData.serializer(), it) }.getOrNull() }
            ?: AppData()

    private fun loadObservations(): List<Observation> =
        FileIO.read(OBS)?.let {
            runCatching { json.decodeFromString(ListSerializer(Observation.serializer()), it) }.getOrNull()
        } ?: emptyList()

    fun reload() {
        _data.value = load()
        _observations.value = loadObservations()
    }

    suspend fun updateSettings(f: (Settings) -> Settings) = edit { it.copy(settings = f(it.settings)) }

    suspend fun upsertWatch(w: Watch) {
        edit { d ->
            val list = d.watches.toMutableList()
            val i = list.indexOfFirst { it.id == w.id }
            if (i >= 0) list[i] = w else list += w
            d.copy(watches = list)
        }
        onWatchesChanged?.invoke()
    }

    suspend fun deleteWatch(id: String) {
        edit { d -> d.copy(watches = d.watches.filterNot { it.id == id }) }
        mutex.withLock {
            _observations.value = _observations.value.filterNot { it.watchId == id }
            saveObservations()
        }
        onWatchesChanged?.invoke()
    }

    private suspend fun edit(f: (AppData) -> AppData) = mutex.withLock {
        val next = f(_data.value)
        _data.value = next
        FileIO.write(CONFIG, json.encodeToString(AppData.serializer(), next))
    }

    suspend fun upsertObservations(changed: Collection<Observation>) {
        if (changed.isEmpty()) return
        mutex.withLock {
            val byKey = _observations.value.associateByTo(LinkedHashMap()) { it.key }
            changed.forEach { byKey[it.key] = it }
            val cutoff = (Clock.System.now() - 400.days).epochSeconds
            _observations.value = byKey.values.filter { it.scheduled >= cutoff }.sortedBy { it.scheduled }
            saveObservations()
        }
    }

    suspend fun clearObservations() = mutex.withLock {
        _observations.value = emptyList()
        saveObservations()
    }

    private fun saveObservations() {
        FileIO.write(OBS, json.encodeToString(ListSerializer(Observation.serializer()), _observations.value))
    }

    companion object {
        private const val CONFIG = "lagcoach.json"
        private const val OBS = "observations.json"
    }
}

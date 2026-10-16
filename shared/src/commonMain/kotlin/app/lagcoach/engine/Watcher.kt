package app.lagcoach.engine

import app.lagcoach.LiveStatus
import app.lagcoach.WatchLive
import app.lagcoach.data.BusTimesClient
import app.lagcoach.data.Repository
import app.lagcoach.model.Departure
import app.lagcoach.model.Observation
import app.lagcoach.model.Watch
import kotlin.time.Instant

data class TickResult(
    val alerts: List<Alert>,
    /** Watches whose window is open now, with a one-line status each. */
    val active: List<Pair<Watch, String>>,
    val anyError: Boolean,
)

/**
 * One polling pass over every watch whose window is open. The platform decides how
 * often to call [tick] and how to show the results (notifications on Android).
 */
class Watcher(
    private val repo: Repository,
    private val client: BusTimesClient,
    private val live: LiveStatus,
) {
    /** departure key + origin + destination -> scheduled minutes between them. */
    private val travelCache = mutableMapOf<String, Int?>()

    suspend fun tick(now: Instant): TickResult {
        val watches = repo.data.value.watches
        val active = watches.mapNotNull { w -> Schedule.activeWindow(w, now)?.let { w to it } }
        live.retain(active.map { it.first.id }.toSet())
        if (active.isEmpty()) return TickResult(emptyList(), emptyList(), false)

        val boards = mutableMapOf<String, Result<List<Departure>>>()
        for ((w, window) in active) {
            if (w.stop.atco in boards) continue
            boards[w.stop.atco] = runCatching {
                val first = client.departures(w.stop.atco)
                val last = first.departures.maxOfOrNull { it.scheduled }
                // A busy stop may not list the whole window on one page.
                if (first.nextPage != null && last != null && last < window.end) {
                    val more = runCatching { client.departures(w.stop.atco, first.nextPage).departures }.getOrDefault(emptyList())
                    (first.departures + more).distinctBy { it.key }
                } else first.departures
            }
        }

        val alerts = mutableListOf<Alert>()
        val statuses = mutableListOf<Pair<Watch, String>>()
        val changed = mutableListOf<Observation>()
        var anyError = false
        val allObs = repo.observations.value

        for ((w, window) in active) {
            val board = boards.getValue(w.stop.atco)
            val deps = board.getOrNull()
            if (deps == null) {
                anyError = true
                val prev = live.state.value[w.id]
                val msg = "Can't reach bustimes.org, retrying"
                live.put(WatchLive(w.id, now.epochSeconds, prev?.relevant.orEmpty(), prev?.summary ?: msg, msg))
                statuses += w to (prev?.summary?.let { "$it (offline)" } ?: msg)
                continue
            }
            val existing = allObs.filter { it.watchId == w.id }.associateBy { it.key }
            if (w.destination != null) resolveTravel(w, deps.filter { w.matchesLine(it.line) && it.scheduled <= window.end }, existing)
            val result = WatchEngine.evaluate(w, window, deps, now, existing) { d -> travelCache[travelKey(w, d)] }
            changed += result.observations
            alerts += result.alerts
            val summary = WatchEngine.summary(w, result.relevant, now)
            live.put(WatchLive(w.id, now.epochSeconds, result.relevant, summary))
            statuses += w to summary
        }
        repo.upsertObservations(changed)
        return TickResult(alerts, statuses, anyError)
    }

    private fun travelKey(w: Watch, d: Departure) = "${d.key}|${w.stop.atco}|${w.destination?.atco}"

    private suspend fun resolveTravel(w: Watch, deps: List<Departure>, existing: Map<String, Observation>) {
        val dest = w.destination ?: return
        var budget = 4 // keep request count low per tick; the rest fill in on later ticks
        for (d in deps) {
            val k = travelKey(w, d)
            if (k in travelCache) continue
            if (existing["${w.id}|${d.line}|${d.scheduled.epochSeconds}"]?.travelMinutes != null) continue
            if (budget-- <= 0) return
            travelCache[k] = runCatching {
                val trip = d.tripId ?: d.journeyId?.let { client.tripIdForJourney(it) } ?: return@runCatching null
                val stops = client.tripStops(trip)
                val from = stops.indexOfFirst { it.stop.atco == w.stop.atco }
                val to = stops.indexOfLast { it.stop.atco == dest.atco }
                if (from < 0 || to <= from) return@runCatching null
                val a = stops[from].time ?: return@runCatching null
                val b = stops[to].time ?: return@runCatching null
                ((b.hour * 60 + b.minute) - (a.hour * 60 + a.minute)).mod(24 * 60)
            }.getOrNull()
        }
    }
}

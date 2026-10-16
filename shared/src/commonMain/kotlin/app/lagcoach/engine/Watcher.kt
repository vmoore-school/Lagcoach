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
        val allObs = repo.observations.value
        val todays = { w: Watch, window: WindowSpan ->
            allObs.filter { it.watchId == w.id && it.date == window.date.toString() }
        }
        // After the window closes, a watch stays active only while a bus is still on its way.
        val active = watches.mapNotNull { w -> Schedule.activeWindow(w, now)?.let { w to it } }
            .filter { (w, window) -> now <= window.end || ArrivalTracker.pending(todays(w, window), now).isNotEmpty() }
        live.retain(active.map { it.first.id }.toSet())
        if (active.isEmpty()) return TickResult(emptyList(), emptyList(), false)

        val boards = mutableMapOf<String, Result<List<Departure>>>()
        suspend fun board(atco: String, until: Instant?): Result<List<Departure>> = boards.getOrPut(atco) {
            runCatching {
                val first = client.departures(atco)
                val last = first.departures.maxOfOrNull { it.scheduled }
                // A busy stop may not list the whole window on one page.
                if (until != null && first.nextPage != null && last != null && last < until) {
                    val more = runCatching { client.departures(atco, first.nextPage).departures }.getOrDefault(emptyList())
                    (first.departures + more).distinctBy { it.key }
                } else first.departures
            }
        }

        val alerts = mutableListOf<Alert>()
        val statuses = mutableListOf<Pair<Watch, String>>()
        val changed = mutableListOf<Observation>()
        var anyError = false

        for ((w, window) in active) {
            val existing = allObs.filter { it.watchId == w.id }.associateBy { it.key }
            val inWindow = now <= window.end
            var relevant = live.state.value[w.id]?.relevant.orEmpty()
            var summary: String? = null

            if (inWindow) {
                val deps = board(w.stop.atco, window.end).getOrNull()
                if (deps == null) {
                    anyError = true
                    val prev = live.state.value[w.id]
                    val msg = "Can't reach bustimes.org, retrying"
                    live.put(WatchLive(w.id, now.epochSeconds, prev?.relevant.orEmpty(), prev?.summary ?: msg, msg))
                    statuses += w to (prev?.summary?.let { "$it (offline)" } ?: msg)
                    continue
                }
                if (w.destination != null) resolveTravel(w, deps.filter { w.matchesLine(it.line) && it.scheduled <= window.end }, existing)
                val result = WatchEngine.evaluate(w, window, deps, now, existing) { d -> travelCache[travelKey(w, d)] }
                changed += result.observations
                alerts += result.alerts
                relevant = result.relevant
                summary = WatchEngine.summary(w, result.relevant, now)
            }

            // Follow buses that have left to the destination stop.
            val dest = w.destination
            if (dest != null) {
                val latest = (existing + changed.filter { it.watchId == w.id }.associateBy { it.key }).values
                    .filter { it.date == window.date.toString() }
                val pending = ArrivalTracker.pending(latest, now)
                if (pending.isNotEmpty()) {
                    board(dest.atco, null).getOrNull()?.let { destBoard ->
                        val updated = ArrivalTracker.update(pending, destBoard, now)
                        changed.removeAll { c -> updated.any { it.key == c.key } }
                        changed += updated
                    }
                    if (!inWindow) {
                        val next = pending.minByOrNull { it.scheduled }!!
                        val nextNow = changed.firstOrNull { it.key == next.key } ?: next
                        val eta = nextNow.estimatedArrival?.let { Schedule.hhmm(Instant.fromEpochSeconds(it)) }
                        summary = "${next.line} on its way" + (eta?.let { " · due at ${dest.name.substringBefore(" (")} $it" } ?: "")
                    }
                }
            }
            val s = summary ?: "Watching"
            live.put(WatchLive(w.id, now.epochSeconds, relevant, s))
            statuses += w to s
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

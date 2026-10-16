package app.lagcoach.engine

import app.lagcoach.data.BBox
import app.lagcoach.data.BusTimesClient
import app.lagcoach.data.LiveVehicle
import app.lagcoach.data.Repository
import app.lagcoach.model.Departure
import app.lagcoach.model.OneOffRecord
import app.lagcoach.model.OneOffTrip
import app.lagcoach.model.TripEnd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.time.Instant

data class TripStep(
    val trip: OneOffTrip,
    /** Raised once when your stop is the bus's next one. */
    val nextStopAlert: Boolean,
    val end: TripEnd?,
)

/**
 * Follows one specific bus to your stop. [step] is pure (unit tested): given the bus's
 * live position report and the departure boards at your boarding and alighting stops,
 * it updates the trip, decides whether to say "your stop is next", and spots arrival.
 */
object OneOffLogic {
    /** Give up this long after the bus should have arrived without hearing it has. */
    const val LOST_AFTER_SECONDS = 45 * 60L

    fun matchesBus(v: LiveVehicle, t: OneOffTrip) =
        (t.journeyId != null && v.journeyId == t.journeyId) || (t.tripId != null && v.tripId == t.tripId)

    fun matchesRow(d: Departure, t: OneOffTrip, scheduled: Long?): Boolean {
        if (!d.line.equals(t.line, ignoreCase = true)) return false
        if ((t.journeyId != null && d.journeyId == t.journeyId) || (t.tripId != null && d.tripId == t.tripId)) return true
        return scheduled != null && abs(d.scheduled.epochSeconds - scheduled) <= 90
    }

    private fun indexOf(t: OneOffTrip, atco: String?) = if (atco == null) -1 else t.stops.indexOfFirst { it.atco == atco }

    fun step(t: OneOffTrip, bus: LiveVehicle?, fromRow: Departure?, toRow: Departure?, toBoardLoaded: Boolean, now: Instant): TripStep {
        val nowS = now.epochSeconds
        var trip = t.copy(lastUpdate = nowS)
        if (bus != null) {
            trip = trip.copy(
                lastLat = bus.lat, lastLon = bus.lon, nextStop = bus.nextStop ?: trip.nextStop,
                delaySeconds = bus.delaySeconds ?: trip.delaySeconds,
                journeyId = trip.journeyId ?: bus.journeyId, tripId = trip.tripId ?: bus.tripId,
                vehicle = trip.vehicle ?: bus.name,
            )
        }
        if (fromRow != null && !trip.departed) {
            trip = trip.copy(
                scheduledDeparture = fromRow.scheduled.epochSeconds,
                expectedDeparture = fromRow.expected?.epochSeconds ?: trip.expectedDeparture,
            )
        }
        if (toRow != null) {
            trip = trip.copy(
                scheduledArrival = toRow.scheduled.epochSeconds,
                expectedArrival = toRow.expected?.epochSeconds ?: trip.expectedArrival,
            )
        } else if (trip.scheduledArrival != null && trip.delaySeconds != null && toRow == null) {
            // Not listed at the destination (e.g. it terminates there): use the bus's own delay.
            trip = trip.copy(expectedArrival = trip.scheduledArrival!! + trip.delaySeconds!!)
        }

        val fromIdx = indexOf(trip, trip.from?.atco)
        val toIdx = indexOf(trip, trip.to.atco)
        val nextIdx = indexOf(trip, trip.nextStop)
        val prevIdx = indexOf(trip, bus?.prevStop)

        if (!trip.departed) {
            val left = (fromIdx >= 0 && (nextIdx > fromIdx || prevIdx >= fromIdx)) ||
                trip.from == null ||
                ((trip.expectedDeparture ?: trip.scheduledDeparture)?.let { it < nowS - 60 } ?: false)
            if (left) trip = trip.copy(departed = true, expectedDeparture = trip.expectedDeparture ?: trip.scheduledDeparture)
        }

        var alert = false
        val eta = trip.expectedArrival ?: trip.scheduledArrival
        val nextIsMine = trip.departed && (trip.nextStop == trip.to.atco || (toIdx > 0 && nextIdx == toIdx))
        if (!trip.notifiedNext && (nextIsMine || (trip.departed && eta != null && (eta - nowS) in 0L..60L))) {
            alert = true
            trip = trip.copy(notifiedNext = true)
        }

        val passed = (toIdx >= 0 && (prevIdx >= toIdx || nextIdx > toIdx)) || bus?.prevStop == trip.to.atco
        val dueGone = eta != null && eta < nowS - 120 && toBoardLoaded && toRow == null
        val end = when {
            passed || dueGone -> TripEnd.Arrived
            (eta ?: (trip.startedAt + 3 * 3600)) < nowS - LOST_AFTER_SECONDS -> TripEnd.Lost
            else -> null
        }
        if (end == TripEnd.Arrived && trip.expectedArrival == null) trip = trip.copy(expectedArrival = minOf(nowS, eta ?: nowS))
        return TripStep(trip, alert, end)
    }

    fun record(t: OneOffTrip, end: TripEnd): OneOffRecord = OneOffRecord(
        id = t.id, date = t.date, line = t.line, headsign = t.headsign,
        fromName = t.from?.name, toName = t.to.name, vehicle = t.vehicle,
        scheduledDeparture = t.scheduledDeparture, actualDeparture = if (t.departed) t.expectedDeparture else null,
        scheduledArrival = t.scheduledArrival,
        actualArrival = if (end == TripEnd.Arrived) t.expectedArrival else null,
        startedAt = t.startedAt, end = end,
    )
}

data class TripTick(val trip: OneOffTrip?, val nextStopAlert: Boolean, val ended: OneOffRecord?)

/** Fetches what [OneOffLogic] needs and persists the result. Called by the watch service. */
class OneOffTracker(private val repo: Repository, private val client: BusTimesClient) {
    private val _live = MutableStateFlow<OneOffTrip?>(null)
    val live: StateFlow<OneOffTrip?> = _live

    suspend fun tick(now: Instant): TripTick {
        val t = repo.data.value.activeTrip ?: return TripTick(null, false, null).also { _live.value = null }

        // Find the bus near where we last saw it (or around the stops), widening once if needed.
        val anchor = t.lastLat?.let { it to t.lastLon!! }
            ?: t.stops.firstOrNull { it.atco == t.nextStop }?.let { s -> s.lat?.let { it to s.lon!! } }
            ?: t.from?.lat?.let { it to t.from.lon!! }
            ?: t.stops.firstNotNullOfOrNull { s -> s.lat?.let { it to s.lon!! } }
        var bus: LiveVehicle? = null
        if (anchor != null) {
            for (radius in listOf(1_500.0, 5_000.0)) {
                bus = runCatching { client.vehiclesIn(BBox.around(anchor.first, anchor.second, radius)) }
                    .getOrNull()?.firstOrNull { OneOffLogic.matchesBus(it, t) }
                if (bus != null) break
            }
        }
        val fromRow = if (!t.departed && t.from != null) {
            runCatching { client.departures(t.from.atco).departures }.getOrNull()
                ?.firstOrNull { OneOffLogic.matchesRow(it, t, t.scheduledDeparture) }
        } else null
        val toBoard = runCatching { client.departures(t.to.atco).departures }.getOrNull()
        val toRow = toBoard?.firstOrNull { OneOffLogic.matchesRow(it, t, t.scheduledArrival) }

        val step = OneOffLogic.step(t, bus, fromRow, toRow, toBoard != null, now)
        if (step.end != null) {
            val rec = OneOffLogic.record(step.trip, step.end)
            if (step.trip.departed || step.end == TripEnd.Arrived) repo.addOneOff(rec)
            repo.setActiveTrip(null)
            _live.value = null
            return TripTick(null, false, rec)
        }
        // Don't clobber a trip the user replaced or stopped while we were fetching.
        if (repo.data.value.activeTrip?.id == t.id) repo.updateActiveTrip { step.trip }
        _live.value = step.trip
        return TripTick(step.trip, step.nextStopAlert, null)
    }

    /** User tapped "Stop tracking". Keeps the trip in stats if the bus had already left. */
    suspend fun stop() {
        val t = repo.data.value.activeTrip ?: return
        if (t.departed) repo.addOneOff(OneOffLogic.record(t, TripEnd.Stopped))
        repo.setActiveTrip(null)
        _live.value = null
    }
}

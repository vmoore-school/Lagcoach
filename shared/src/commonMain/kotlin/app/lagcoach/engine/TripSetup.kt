package app.lagcoach.engine

import app.lagcoach.data.BusTimesClient
import app.lagcoach.data.LiveVehicle
import app.lagcoach.data.TripStop
import app.lagcoach.model.Departure
import app.lagcoach.model.OneOffTrip
import app.lagcoach.model.RouteStop
import app.lagcoach.model.StopRef
import app.lagcoach.model.UkZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Instant

/** Where a one-off trip starts from: a row on a stop's board, or a bus on the map. */
sealed interface TrackSource {
    val line: String
    val headsign: String
    data class FromStop(val stop: StopRef, val departure: Departure) : TrackSource {
        override val line get() = departure.line
        override val headsign get() = departure.destination
    }
    data class FromBus(val bus: LiveVehicle) : TrackSource {
        override val line get() = bus.line
        override val headsign get() = bus.destination
    }
}

data class TripPlan(val tripId: Long, val all: List<TripStop>, val startIndex: Int) {
    /** Stops you could get off at. */
    val onward: List<TripStop> get() = all.drop(startIndex + 1)
}

object TripSetup {
    suspend fun plan(client: BusTimesClient, src: TrackSource): TripPlan? {
        val tripId = when (src) {
            is TrackSource.FromStop -> src.departure.tripId ?: src.departure.journeyId?.let { client.tripIdForJourney(it) }
            is TrackSource.FromBus -> src.bus.tripId ?: src.bus.journeyId?.let { client.tripIdForJourney(it) }
        } ?: return null
        val all = client.tripStops(tripId)
        if (all.isEmpty()) return null
        val start = when (src) {
            is TrackSource.FromStop -> all.indexOfFirst { it.stop.atco == src.stop.atco }
            // On board already: everything from the stop the bus is heading to is still ahead.
            is TrackSource.FromBus -> all.indexOfFirst { it.stop.atco == src.bus.nextStop }.let { if (it > 0) it - 1 else it }
        }.coerceAtLeast(-1)
        return TripPlan(tripId, all, start)
    }

    fun build(src: TrackSource, plan: TripPlan, to: TripStop, now: Instant): OneOffTrip {
        val firstIdx = plan.startIndex.coerceAtLeast(0)
        val toIdx = plan.all.indexOf(to)
        val route = plan.all.subList(firstIdx, (toIdx + 1).coerceAtMost(plan.all.size))
        // Timetable times have no date: anchor on the service day, rolling past midnight.
        val serviceDay = when (src) {
            is TrackSource.FromStop -> src.departure.scheduled.toLocalDateTime(UkZone).date
            is TrackSource.FromBus -> now.toLocalDateTime(UkZone).date
        }
        fun at(ts: TripStop): Long? {
            val t = ts.time ?: return null
            val first = plan.all.firstNotNullOfOrNull { it.time } ?: t
            val date = if (t < first) serviceDay.plus(DatePeriod(days = 1)) else serviceDay
            return LocalDateTime(date, t).toInstant(UkZone).epochSeconds
        }
        val from = (src as? TrackSource.FromStop)?.stop?.let { s ->
            val ts = plan.all.getOrNull(plan.startIndex)?.stop
            s.copy(lat = s.lat ?: ts?.lat, lon = s.lon ?: ts?.lon)
        }
        val bus = (src as? TrackSource.FromBus)?.bus
        return OneOffTrip(
            id = now.toEpochMilliseconds().toString(36) + Random.nextInt(100, 999),
            line = src.line,
            headsign = src.headsign,
            date = serviceDay.toString(),
            tripId = plan.tripId,
            journeyId = (src as? TrackSource.FromStop)?.departure?.journeyId ?: bus?.journeyId,
            vehicle = (src as? TrackSource.FromStop)?.departure?.vehicle ?: bus?.name,
            from = from,
            to = to.stop,
            stops = route.map { RouteStop(it.stop.atco, it.stop.name, it.stop.lat, it.stop.lon, it.time?.let { t -> "${Schedule.pad(t.hour)}:${Schedule.pad(t.minute)}" }) },
            scheduledDeparture = (src as? TrackSource.FromStop)?.departure?.scheduled?.epochSeconds,
            expectedDeparture = (src as? TrackSource.FromStop)?.departure?.expected?.epochSeconds,
            departed = src is TrackSource.FromBus,
            scheduledArrival = at(to),
            startedAt = now.epochSeconds,
            lastLat = bus?.lat, lastLon = bus?.lon, nextStop = bus?.nextStop, delaySeconds = bus?.delaySeconds,
        )
    }
}

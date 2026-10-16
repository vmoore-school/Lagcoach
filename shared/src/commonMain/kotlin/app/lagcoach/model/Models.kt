package app.lagcoach.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** All bustimes.org times are UK local. */
val UkZone: TimeZone = TimeZone.of("Europe/London")

@Serializable
data class StopRef(
    val atco: String,
    val name: String,
    val indicator: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val lines: List<String> = emptyList(),
) {
    /** "Piccadilly Gardens (Stop D)" -> "Stop D" etc. */
    val displayName: String get() = name
}

@Serializable
data class AlertPrefs(
    /** Minutes late before a "running late" alert. */
    val lateThresholdMin: Int = 5,
    val cancellations: Boolean = true,
    /** Alert when a bus scheduled before the window is late enough to land inside it. */
    val lateIntoWindow: Boolean = true,
    val runningEarly: Boolean = true,
    val earlyThresholdMin: Int = 2,
    /** Start checking this many minutes before the window opens. */
    val leadMinutes: Int = 20,
)

@Serializable
data class Watch(
    val id: String,
    val label: String,
    val stop: StopRef,
    val destination: StopRef? = null,
    /** Route numbers to include; empty means every route at the stop. */
    val lines: List<String> = emptyList(),
    /** Minutes after local midnight. */
    val windowStart: Int,
    val windowEnd: Int,
    /** ISO day numbers, Monday = 1. */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val enabled: Boolean = true,
    val alerts: AlertPrefs = AlertPrefs(),
    /** Window date (ISO) the user said they're not taking: no watching, and kept out of stats. */
    val skipDate: String? = null,
) {
    fun runsOn(day: DayOfWeek) = day.isoDayNumber() in days
    fun matchesLine(line: String) = lines.isEmpty() || lines.any { it.equals(line, ignoreCase = true) }
}

fun DayOfWeek.isoDayNumber(): Int = ordinal + 1

@Serializable
data class Settings(
    val pollSeconds: Int = 60,
    val onboarded: Boolean = false,
    /** Use the phone's wallpaper-based Material You colours instead of the Stagecoach palette. */
    val dynamicColor: Boolean = false,
    /** Where the map was last left. */
    val mapLat: Double? = null,
    val mapLon: Double? = null,
    val mapZoom: Double? = null,
)

/** One live/scheduled row from a stop's departure board. */
data class Departure(
    val line: String,
    val destination: String,
    val scheduled: Instant,
    val expected: Instant?,
    val cancelled: Boolean,
    val tripId: Long?,
    val journeyId: Long?,
    val vehicle: String?,
) {
    /** Stable across the row switching from a timetable trip link to a live journey link. */
    val key: String get() = "$line|${scheduled.epochSeconds}"
    val delaySeconds: Long? get() = expected?.let { (it - scheduled).inWholeSeconds }
    val effective: Instant get() = expected ?: scheduled
}

/**
 * What we saw (and told the user) about one scheduled bus on one day for one watch.
 * Doubles as the statistics record.
 */
@Serializable
data class Observation(
    val watchId: String,
    val date: String,
    val line: String,
    val destination: String,
    val scheduled: Long,
    val expected: Long? = null,
    val cancelled: Boolean = false,
    val inWindow: Boolean = true,
    /** Scheduled minutes from boarding stop to the watch's destination stop. */
    val travelMinutes: Int? = null,
    val lastSeen: Long = 0,
    /** Timetabled time at the destination stop. */
    val arrivalScheduled: Long? = null,
    /** Last live estimate at the destination stop, read from its own board. */
    val arrivalExpected: Long? = null,
    val tripId: Long? = null,
    val journeyId: Long? = null,
    val notifiedLateMin: Int? = null,
    val notifiedCancelled: Boolean = false,
    val notifiedIntoWindow: Boolean = false,
    val notifiedEarly: Boolean = false,
) {
    val key: String get() = "$watchId|$line|$scheduled"
    val delayMinutes: Int? get() = expected?.let { ((it - scheduled) / 60.0).let(::roundHalfAway) }
    /** Best arrival time we have: measured at the destination, else departure + timetabled journey. */
    val estimatedArrival: Long? get() = arrivalExpected ?: travelMinutes?.let { (expected ?: scheduled) + it * 60L }
    val plannedArrival: Long? get() = arrivalScheduled ?: travelMinutes?.let { scheduled + it * 60L }
    val arrivalMeasured: Boolean get() = arrivalExpected != null
}

internal fun roundHalfAway(x: Double): Int = if (x >= 0) (x + 0.5).toInt() else -((-x + 0.5).toInt())

@Serializable
data class AppData(
    val settings: Settings = Settings(),
    val watches: List<Watch> = emptyList(),
    val activeTrip: OneOffTrip? = null,
)

/** A calling point on a tracked trip, with its timetabled time ("HH:MM"). */
@Serializable
data class RouteStop(
    val atco: String,
    val name: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val time: String? = null,
)

/** The bus you're riding right now, followed until it reaches your stop. */
@Serializable
data class OneOffTrip(
    val id: String,
    val line: String,
    val headsign: String,
    val date: String,
    val tripId: Long? = null,
    val journeyId: Long? = null,
    val vehicle: String? = null,
    /** Where you got on; null when picked from the map while already aboard. */
    val from: StopRef? = null,
    val to: StopRef,
    /** Calling points from boarding (or the bus's position) to your stop. */
    val stops: List<RouteStop> = emptyList(),
    val scheduledDeparture: Long? = null,
    val expectedDeparture: Long? = null,
    val departed: Boolean = false,
    val scheduledArrival: Long? = null,
    val expectedArrival: Long? = null,
    val startedAt: Long,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val nextStop: String? = null,
    /** Fraction of the way from the previous stop to [nextStop]. */
    val segmentProgress: Double? = null,
    val delaySeconds: Long? = null,
    val notifiedNext: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
enum class TripEnd { Arrived, Stopped, Lost }

/** A finished one-off trip, for the "One-off trips" stats. */
@Serializable
data class OneOffRecord(
    val id: String,
    val date: String,
    val line: String,
    val headsign: String,
    val fromName: String? = null,
    val toName: String,
    val vehicle: String? = null,
    val scheduledDeparture: Long? = null,
    val actualDeparture: Long? = null,
    val scheduledArrival: Long? = null,
    val actualArrival: Long? = null,
    val startedAt: Long,
    val end: TripEnd,
) {
    val arrivalDelayMinutes: Int? get() =
        if (scheduledArrival != null && actualArrival != null) roundHalfAway((actualArrival - scheduledArrival) / 60.0) else null
    val rideMinutes: Int? get() = actualArrival?.let { a -> (actualDeparture ?: startedAt).let { ((a - it) / 60).toInt() } }
}

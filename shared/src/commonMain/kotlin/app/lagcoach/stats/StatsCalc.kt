package app.lagcoach.stats

import app.lagcoach.model.Observation
import app.lagcoach.model.UkZone
import app.lagcoach.model.roundHalfAway
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmName
import kotlin.time.Instant

/**
 * Punctuality follows the UK Traffic Commissioner standard: a bus is on time if it leaves
 * between 1 minute early and 5 minutes 59 seconds late.
 */
object Punctuality {
    const val LATE_FROM_MIN = 6
    const val EARLY_FROM_MIN = -2
    fun isLate(delay: Int) = delay >= LATE_FROM_MIN
    fun isEarly(delay: Int) = delay <= EARLY_FROM_MIN
}

data class SlotStats(
    val watchId: String,
    val line: String,
    val scheduled: LocalTime,
    val count: Int,
    val withLive: Int,
    val avgDelayMin: Double?,
    val late: Int,
    val early: Int,
    val cancelled: Int,
    /** Average estimated arrival at the journey's destination, minutes after midnight. */
    val avgArrivalMinutes: Double?,
    val scheduledArrivalMinutes: Int?,
    val arrival: ArrivalStats? = null,
) {
    val avgDepartureMinutes: Double? get() = avgDelayMin?.let { scheduled.hour * 60 + scheduled.minute + it }
}

/** How a set of buses got on at the destination. */
data class ArrivalStats(
    val count: Int,
    /** How many were timed at the destination stop itself rather than estimated. */
    val measured: Int,
    val onTime: Int,
    val late: Int,
    val avgDelayMin: Double?,
    /** Average time on the bus, boarding stop to destination. */
    val avgJourneyMin: Double?,
    /** 9 in 10 arrivals were by this time (minutes after midnight). */
    val planForMinutes: Double?,
    val avgArrivalMinutes: Double?,
    val earliestMinutes: Double?,
    val latestMinutes: Double?,
) {
    val onTimePct: Int? get() = if (count == 0) null else roundHalfAway(onTime * 100.0 / count)
}

data class DayStats(val date: LocalDate, val count: Int, val avgDelayMin: Double?, val cancelled: Int, val late: Int)

data class Summary(
    val total: Int,
    val withLive: Int,
    val late: Int,
    val early: Int,
    val cancelled: Int,
    val flagged: Int,
    val avgDelayMin: Double?,
    val slots: List<SlotStats>,
    val days: List<DayStats>,
    val arrival: ArrivalStats? = null,
) {
    val onTime: Int get() = withLive - late - early
    val onTimePct: Int? get() = if (withLive == 0) null else roundHalfAway(onTime * 100.0 / withLive)
}

object StatsCalc {
    fun summarise(all: List<Observation>, since: Instant?, now: Instant? = null): Summary {
        val obs = all.filter { (since == null || it.scheduled >= since.epochSeconds) && (now == null || it.scheduled <= now.epochSeconds) }
        // Late-into-window buses are interesting for alerts but aren't "your" departures.
        val mine = obs.filter { it.inWindow }
        val live = mine.filter { it.expected != null && !it.cancelled }
        val delays = live.mapNotNull { it.delayMinutes }
        val slots = mine.groupBy { Triple(it.watchId, it.line, localTime(it.scheduled)) }.map { (k, list) ->
            val l = list.filter { it.expected != null && !it.cancelled }
            val d = l.mapNotNull { it.delayMinutes }
            val arrivals = l.mapNotNull { o -> o.estimatedArrival?.let { minutesOfDay(it, o.scheduled) } }
            val schedArr = list.firstNotNullOfOrNull { o -> o.travelMinutes?.let { minutesOfDay(o.scheduled, o.scheduled).toInt() + it } }
            SlotStats(
                watchId = k.first, line = k.second, scheduled = k.third, count = list.size, withLive = l.size,
                avgDelayMin = d.averageOrNull(), late = d.count(Punctuality::isLate), early = d.count(Punctuality::isEarly),
                cancelled = list.count { it.cancelled }, avgArrivalMinutes = arrivals.averageOrNull(), scheduledArrivalMinutes = schedArr,
                arrival = arrivalStats(list),
            )
        }.sortedWith(compareBy({ it.scheduled }, { it.line }))
        val days = mine.groupBy { LocalDate.parse(it.date) }.map { (date, list) ->
            val d = list.filter { it.expected != null && !it.cancelled }.mapNotNull { it.delayMinutes }
            DayStats(date, list.size, d.averageOrNull(), list.count { it.cancelled }, d.count(Punctuality::isLate))
        }.sortedBy { it.date }
        return Summary(
            total = mine.size,
            withLive = live.size,
            late = delays.count(Punctuality::isLate),
            early = delays.count(Punctuality::isEarly),
            cancelled = mine.count { it.cancelled },
            flagged = obs.count { it.notifiedLateMin != null || it.notifiedCancelled || it.notifiedIntoWindow || it.notifiedEarly },
            avgDelayMin = delays.averageOrNull(),
            slots = slots,
            days = days,
            arrival = arrivalStats(mine),
        )
    }

    /** Arrivals count as on time up to 5 min 59 s after the timetable, like departures. */
    fun arrivalStats(obs: List<Observation>): ArrivalStats? {
        val done = obs.filter { !it.cancelled && it.estimatedArrival != null && (it.arrivalMeasured || it.expected != null) }
        if (done.isEmpty()) return null
        val delays = done.mapNotNull { o -> o.plannedArrival?.let { p -> (o.estimatedArrival!! - p) / 60.0 } }
        val journeys = done.filter { it.arrivalMeasured }.map { (it.estimatedArrival!! - (it.expected ?: it.scheduled)) / 60.0 }
        val clock = done.map { minutesOfDay(it.estimatedArrival!!, it.scheduled) }.sorted()
        return ArrivalStats(
            count = done.size,
            measured = done.count { it.arrivalMeasured },
            onTime = delays.count { roundHalfAway(it) < Punctuality.LATE_FROM_MIN },
            late = delays.count { roundHalfAway(it) >= Punctuality.LATE_FROM_MIN },
            avgDelayMin = delays.averageOrNull(),
            avgJourneyMin = journeys.averageOrNull(),
            planForMinutes = clock[(kotlin.math.ceil(clock.size * 0.9).toInt() - 1).coerceIn(0, clock.lastIndex)],
            avgArrivalMinutes = clock.averageOrNull(),
            earliestMinutes = clock.first(),
            latestMinutes = clock.last(),
        )
    }

    private fun localTime(epoch: Long) = Instant.fromEpochSeconds(epoch).toLocalDateTime(UkZone).time.let { LocalTime(it.hour, it.minute) }

    /** Minutes after the midnight before [refEpoch]'s local date; can exceed 1440 past midnight. */
    private fun minutesOfDay(epoch: Long, refEpoch: Long): Double {
        val ref = Instant.fromEpochSeconds(refEpoch).toLocalDateTime(UkZone)
        val refMin = ref.hour * 60 + ref.minute
        return refMin + (epoch - refEpoch) / 60.0
    }

    private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
    @JvmName("avgD")
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}

fun formatClock(minutes: Double): String {
    val m = roundHalfAway(minutes).mod(24 * 60)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

fun formatDelay(min: Double): String {
    val r = roundHalfAway(min)
    return when {
        r > 0 -> "+$r min"
        r < 0 -> "−${-r} min"
        else -> "on time"
    }
}

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
) {
    val avgDepartureMinutes: Double? get() = avgDelayMin?.let { scheduled.hour * 60 + scheduled.minute + it }
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

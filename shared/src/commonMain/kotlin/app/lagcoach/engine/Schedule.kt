package app.lagcoach.engine

import app.lagcoach.model.UkZone
import app.lagcoach.model.Watch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class WindowSpan(val date: LocalDate, val start: Instant, val end: Instant, val monitorFrom: Instant) {
    operator fun contains(t: Instant) = t >= start && t <= end
}

object Schedule {
    fun minutesToTime(m: Int): LocalTime = LocalTime((m / 60) % 24, m % 60)

    fun formatMinutes(m: Int): String = minutesToTime(m).let { "${pad(it.hour)}:${pad(it.minute)}" }

    fun windowOn(watch: Watch, date: LocalDate, zone: TimeZone = UkZone): WindowSpan {
        val start = LocalDateTime(date, minutesToTime(watch.windowStart)).toInstant(zone)
        val endDate = if (watch.windowEnd <= watch.windowStart) date.plus(DatePeriod(days = 1)) else date
        val end = LocalDateTime(endDate, minutesToTime(watch.windowEnd)).toInstant(zone)
        return WindowSpan(date, start, end, start - watch.alerts.leadMinutes.minutes)
    }

    /** The window being monitored right now, if any. */
    fun activeWindow(watch: Watch, now: Instant, zone: TimeZone = UkZone): WindowSpan? {
        if (!watch.enabled) return null
        val today = now.toLocalDateTime(zone).date
        // Check yesterday too, for windows that run past midnight.
        for (d in listOf(today.plus(DatePeriod(days = -1)), today)) {
            if (!watch.runsOn(d.dayOfWeek)) continue
            val w = windowOn(watch, d, zone)
            if (now >= w.monitorFrom && now <= w.end) return w
        }
        return null
    }

    /** When monitoring should next begin for this watch (strictly after [now]). */
    fun nextMonitorStart(watch: Watch, now: Instant, zone: TimeZone = UkZone): Instant? {
        if (!watch.enabled || watch.days.isEmpty()) return null
        val today = now.toLocalDateTime(zone).date
        for (i in 0..8) {
            val d = today.plus(DatePeriod(days = i))
            if (!watch.runsOn(d.dayOfWeek)) continue
            val w = windowOn(watch, d, zone)
            if (w.monitorFrom > now) return w.monitorFrom
        }
        return null
    }

    fun pad(n: Int) = n.toString().padStart(2, '0')

    fun hhmm(t: Instant, zone: TimeZone = UkZone): String =
        t.toLocalDateTime(zone).let { "${pad(it.hour)}:${pad(it.minute)}" }
}

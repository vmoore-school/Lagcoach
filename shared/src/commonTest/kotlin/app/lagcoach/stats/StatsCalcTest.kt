package app.lagcoach.stats

import app.lagcoach.model.Observation
import app.lagcoach.model.UkZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsCalcTest {
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime(2026, 10, d, h, m).toInstant(UkZone).epochSeconds

    private fun obs(d: Int, delayMin: Int?, cancelled: Boolean = false, inWindow: Boolean = true, travel: Int? = 30) = Observation(
        watchId = "w", date = "2026-10-${d.toString().padStart(2, '0')}", line = "42", destination = "City",
        scheduled = at(d, 7, 30), expected = delayMin?.let { at(d, 7, 30) + it * 60L }, cancelled = cancelled,
        inWindow = inWindow, travelMinutes = travel,
    )

    @Test
    fun punctualityAndAverages() {
        val s = StatsCalc.summarise(
            listOf(obs(12, 0), obs(13, 8), obs(14, -3), obs(15, null, cancelled = true), obs(16, 4), obs(16, 20, inWindow = false)),
            since = null,
        )
        assertEquals(5, s.total) // late-into-window bus excluded
        assertEquals(4, s.withLive)
        assertEquals(1, s.late)
        assertEquals(1, s.early)
        assertEquals(1, s.cancelled)
        assertEquals(50, s.onTimePct)
        assertEquals(2.25, s.avgDelayMin)

        val slot = s.slots.single()
        assertEquals(LocalTime(7, 30), slot.scheduled)
        assertEquals(5, slot.count)
        // Average departure 07:32.25, arrival = that + 30 min timetabled journey
        assertEquals("07:32", formatClock(slot.avgDepartureMinutes!!))
        assertEquals("08:02", formatClock(slot.avgArrivalMinutes!!))
        assertEquals(8 * 60, slot.scheduledArrivalMinutes)
        assertEquals(5, s.days.size)
    }

    @Test
    fun excludesFutureBuses() {
        val s = StatsCalc.summarise(listOf(obs(12, 2), obs(20, null)), since = null, now = kotlin.time.Instant.fromEpochSeconds(at(15, 0, 0)))
        assertEquals(1, s.total)
    }

    @Test
    fun emptyIsSafe() {
        val s = StatsCalc.summarise(emptyList(), null)
        assertEquals(0, s.total)
        assertNull(s.onTimePct)
        assertNull(s.avgDelayMin)
    }
}

class ArrivalStatsTest {
    private fun at(h: Int, m: Int, d: Int = 12) =
        kotlinx.datetime.LocalDateTime(2026, 10, d, h, m).toInstant(app.lagcoach.model.UkZone).epochSeconds

    private fun o(d: Int, depDelay: Int, arrival: Pair<Int, Int>?) = app.lagcoach.model.Observation(
        watchId = "w", date = "2026-10-$d", line = "42", destination = "X", scheduled = at(7, 30, d),
        expected = at(7, 30, d) + depDelay * 60L, travelMinutes = 25,
        arrivalScheduled = at(7, 55, d), arrivalExpected = arrival?.let { at(it.first, it.second, d) },
    )

    @Test
    fun measuredAndEstimatedArrivals() {
        val a = StatsCalc.arrivalStats(
            listOf(o(12, 2, 7 to 58), o(13, 0, 8 to 5), o(14, 4, null), o(15, 1, 7 to 55)),
        )!!
        assertEquals(4, a.count)
        assertEquals(3, a.measured)
        // delays: +3, +10, +4 (estimated 07:59), 0
        assertEquals(1, a.late)
        assertEquals(3, a.onTime)
        assertEquals(75, a.onTimePct)
        assertEquals("08:05", formatClock(a.planForMinutes!!))
        assertEquals("07:55", formatClock(a.earliestMinutes!!))
        // journey times measured: 26, 35, 24
        assertEquals((26 + 35 + 24) / 3.0, a.avgJourneyMin)
    }
}

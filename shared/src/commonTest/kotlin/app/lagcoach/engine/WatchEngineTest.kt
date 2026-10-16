package app.lagcoach.engine

import app.lagcoach.model.Departure
import app.lagcoach.model.Observation
import app.lagcoach.model.StopRef
import app.lagcoach.model.UkZone
import app.lagcoach.model.Watch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchEngineTest {
    private val date = LocalDate(2026, 10, 19) // Monday
    private fun at(h: Int, m: Int) = LocalDateTime(date.year, date.month, date.day, h, m).toInstant(UkZone)
    private val watch = Watch(
        id = "w", label = "Morning", stop = StopRef("1800X", "Test Stop"),
        lines = listOf("42"), windowStart = 7 * 60 + 20, windowEnd = 7 * 60 + 45,
    )
    private val window = Schedule.windowOn(watch, date)

    private fun dep(line: String, sh: Int, sm: Int, eh: Int? = null, em: Int? = null, cancelled: Boolean = false) =
        Departure(line, "City", at(sh, sm), eh?.let { at(it, em!!) }, cancelled, 1, null, null)

    private fun run(deps: List<Departure>, now: Instant, existing: Map<String, Observation> = emptyMap()) =
        WatchEngine.evaluate(watch, window, deps, now, existing)

    @Test
    fun lateBusInWindowAlertsOnceThenOnGrowth() {
        val now = at(7, 10)
        val r1 = run(listOf(dep("42", 7, 30, 7, 37)), now)
        assertEquals(listOf(AlertKind.Late), r1.alerts.map { it.kind })
        assertEquals(7, r1.alerts[0].delayMinutes)
        val seen = r1.observations.associateBy { it.key }

        assertTrue(run(listOf(dep("42", 7, 30, 7, 39)), now, seen).alerts.isEmpty())
        val r3 = run(listOf(dep("42", 7, 30, 7, 43)), now, seen)
        assertEquals(listOf(AlertKind.Late), r3.alerts.map { it.kind })
    }

    @Test
    fun filtersOtherRoutes() {
        val r = run(listOf(dep("50", 7, 30, 7, 50)), at(7, 10))
        assertTrue(r.alerts.isEmpty())
        assertTrue(r.observations.isEmpty())
    }

    @Test
    fun cancellationAlertsOnce() {
        val r = run(listOf(dep("42", 7, 25, cancelled = true)), at(7, 0))
        assertEquals(AlertKind.Cancelled, r.alerts.single().kind)
        assertTrue(run(listOf(dep("42", 7, 25, cancelled = true)), at(7, 1), r.observations.associateBy { it.key }).alerts.isEmpty())
    }

    @Test
    fun earlierBusDelayedIntoWindow() {
        val r = run(listOf(dep("42", 7, 5, 7, 24)), at(7, 2))
        val a = r.alerts.single()
        assertEquals(AlertKind.IntoWindow, a.kind)
        assertEquals(19, a.delayMinutes)
        assertEquals(false, r.observations.single().inWindow)
    }

    @Test
    fun earlierBusNotLateEnoughIsIgnored() {
        val r = run(listOf(dep("42", 7, 5, 7, 10)), at(7, 2))
        assertTrue(r.alerts.isEmpty())
        assertTrue(r.relevant.isEmpty())
    }

    @Test
    fun runningEarly() {
        val r = run(listOf(dep("42", 7, 30, 7, 27)), at(7, 15))
        assertEquals(AlertKind.Early, r.alerts.single().kind)
    }

    @Test
    fun noAlertForDepartedBus() {
        val r = run(listOf(dep("42", 7, 21, 7, 30)), at(7, 35))
        assertTrue(r.alerts.isEmpty())
        assertEquals(1, r.observations.size)
    }

    @Test
    fun keepsLastExpectedWhenLiveDisappears() {
        val r1 = run(listOf(dep("42", 7, 30, 7, 33)), at(7, 20))
        val r2 = run(listOf(dep("42", 7, 30)), at(7, 31), r1.observations.associateBy { it.key })
        assertEquals(at(7, 33).epochSeconds, r2.observations.single().expected)
    }

    @Test
    fun scheduleWindows() {
        val active = Schedule.activeWindow(watch, at(7, 5))
        assertEquals(window, active)
        assertNull(Schedule.activeWindow(watch, at(6, 59)))
        assertNull(Schedule.activeWindow(watch, at(7, 46)))
        // Saturday/Sunday skipped -> next is Tuesday 07:00
        val next = Schedule.nextMonitorStart(watch, at(8, 0))
        assertEquals(LocalDateTime(2026, 10, 20, 7, 0).toInstant(UkZone), next)
    }
}

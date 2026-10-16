package app.lagcoach.engine

import app.lagcoach.model.StopRef
import app.lagcoach.model.UkZone
import app.lagcoach.model.Watch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotTakingTest {
    private val monday = LocalDate(2026, 10, 19)
    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime(d.year, d.month, d.day, h, m).toInstant(UkZone)
    private val watch = Watch(
        id = "w", label = "Morning", stop = StopRef("1800X", "Test Stop"),
        windowStart = 7 * 60 + 20, windowEnd = 7 * 60 + 45,
    )

    @Test
    fun skippedWindowIsNotWatched() {
        val now = at(monday, 7, 30)
        assertNotNull(Schedule.activeWindow(watch, now))
        val skipped = watch.copy(skipDate = monday.toString())
        assertNull(Schedule.activeWindow(skipped, now))
        // Still offered so it can be undone.
        assertEquals(monday, Schedule.skippableWindow(skipped, now)?.date)
    }

    @Test
    fun skipOnlyAffectsThatDay() {
        val skipped = watch.copy(skipDate = monday.toString())
        val tuesday = LocalDate(2026, 10, 20)
        assertNotNull(Schedule.activeWindow(skipped, at(tuesday, 7, 30)))
    }

    @Test
    fun skippingBeforeTheWindowMovesNextCheckToTomorrow() {
        val now = at(monday, 6, 0)
        assertEquals(at(monday, 7, 0), Schedule.nextMonitorStart(watch, now))
        val skipped = watch.copy(skipDate = monday.toString())
        assertEquals(at(LocalDate(2026, 10, 20), 7, 0), Schedule.nextMonitorStart(skipped, now))
        assertEquals(monday, Schedule.skippableWindow(watch, now)?.date)
    }

    @Test
    fun nothingToSkipOnceTodaysWindowIsOver() {
        assertNull(Schedule.skippableWindow(watch, at(monday, 12, 0)))
        assertNull(Schedule.skippableWindow(watch, at(LocalDate(2026, 10, 18), 7, 30))) // Sunday
    }
}

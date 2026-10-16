package app.lagcoach.data

import app.lagcoach.model.UkZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DepartureParserTest {
    private val today = LocalDate(2026, 10, 16)
    private fun at(h: Int, m: Int, d: LocalDate = today) = LocalDateTime(d.year, d.month, d.day, h, m).toInstant(UkZone)

    private val live = """
        <div class="aside" id="departures">
        <form autocomplete="off"><input type="date" name="date" value="2026-10-16"></form>
        <table>
        <thead><tr><th></th><th scope="col">To</th><th scope="col">Sched&shy;uled</th><th scope="col">Ex&shy;pected</th></tr></thead>
        <tbody>
        <tr><td class="nowrap"><a href="/services/53-x">53</a></td>
          <td>Cheetham Hill <div class="vehicle"><a href="/vehicles/bnsm-bv24-lsu">21430 - BV24 LSU</a></div></td>
          <td><a href="/journeys/949954590">05:12</a></td>
          <td><a href="/journeys/949954590">05:19</a></td></tr>
        <tr><td class="nowrap"><a href="/services/43-x">43</a></td>
          <td>Manchester Airport</td>
          <td><a href="/trips/654497175"> <s> 05:41 </s> </a></td>
          <td></td></tr>
        <tr><td class="nowrap"><a href="/services/41-x">41</a></td>
          <td>Sale</td>
          <td><a href="/trips/653934814">23:58</a></td>
          <td><a href="/trips/653934814">00:04</a></td></tr>
        </tbody></table>
        <h3>Saturday 17 October</h3>
        <table><thead><tr><th></th><th scope="col">To</th><th scope="col">Scheduled</th></tr></thead>
        <tbody><tr><td class="nowrap"><a href="/services/41-x">41</a></td><td>Sale</td><td><a href="/trips/1">06:01</a></td></tr></tbody></table>
        <p class="next"><a href="?date=2026-10-17&amp;time=06%3A01" rel="nofollow">Later</a></p>
        </div>
    """.trimIndent()

    @Test
    fun parsesLiveBoard() {
        val board = DepartureParser.parse(live, today)
        assertEquals(4, board.departures.size)
        val first = board.departures[0]
        assertEquals("53", first.line)
        assertEquals("Cheetham Hill", first.destination)
        assertEquals("21430 - BV24 LSU", first.vehicle)
        assertEquals(at(5, 12), first.scheduled)
        assertEquals(at(5, 19), first.expected)
        assertEquals(7 * 60L, first.delaySeconds)
        assertEquals(949954590L, first.journeyId)
        assertNull(first.tripId)
        assertFalse(first.cancelled)
    }

    @Test
    fun detectsCancellation() {
        val d = DepartureParser.parse(live, today).departures[1]
        assertTrue(d.cancelled)
        assertNull(d.expected)
        assertEquals(654497175L, d.tripId)
    }

    @Test
    fun expectedPastMidnightRollsOver() {
        val d = DepartureParser.parse(live, today).departures[2]
        assertEquals(at(0, 4, LocalDate(2026, 10, 17)), d.expected)
        assertEquals(6 * 60L, d.delaySeconds)
    }

    @Test
    fun dayHeadingsSetDate() {
        val board = DepartureParser.parse(live, today)
        assertEquals(at(6, 1, LocalDate(2026, 10, 17)), board.departures[3].scheduled)
        assertEquals("?date=2026-10-17&time=06%3A01", board.nextPage)
    }

    @Test
    fun yearWrap() {
        assertEquals(LocalDate(2027, 1, 1), DepartureParser.parseDayHeading("Friday 1 January", LocalDate(2026, 12, 31)))
    }

    @Test
    fun scheduledOnlyBoard() {
        val html = """<div id="departures"><table><thead><tr><th></th><th scope="col">To</th><th scope="col">Scheduled</th></tr></thead>
            <tbody><tr><td class="nowrap"><a href="/services/197">197</a></td><td>Stockport</td><td><a href="/trips/653511326">06:26</a></td></tr></tbody></table></div>"""
        val d = DepartureParser.parse(html, today).departures.single()
        assertEquals("197", d.line)
        assertEquals("Stockport", d.destination)
        assertNull(d.expected)
        assertEquals(653511326L, d.tripId)
    }
}

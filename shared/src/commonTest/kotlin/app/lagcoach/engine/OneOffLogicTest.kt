package app.lagcoach.engine

import app.lagcoach.data.LiveVehicle
import app.lagcoach.model.Departure
import app.lagcoach.model.OneOffTrip
import app.lagcoach.model.RouteStop
import app.lagcoach.model.StopRef
import app.lagcoach.model.TripEnd
import app.lagcoach.model.UkZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneOffLogicTest {
    private fun at(h: Int, m: Int) = LocalDateTime(2026, 10, 19, h, m).toInstant(UkZone)
    private val stops = listOf("A", "B", "C", "D").map { RouteStop(it, "Stop $it") }
    private val trip = OneOffTrip(
        id = "t", line = "42", headsign = "City", date = "2026-10-19", tripId = 7, journeyId = 70,
        from = StopRef("A", "Stop A"), to = StopRef("D", "Stop D"), stops = stops,
        scheduledDeparture = at(7, 30).epochSeconds, scheduledArrival = at(7, 50).epochSeconds, startedAt = at(7, 20).epochSeconds,
    )

    private fun bus(next: String?, prev: String?, delay: Long = 120) =
        LiveVehicle(1, 70, 7, "42", "City", 52.0, -0.4, 90, delay, next, prev, "123", null)

    private fun row(h: Int, m: Int, eh: Int?, em: Int?) = Departure("42", "City", at(h, m), eh?.let { at(it, em!!) }, false, 7, null, null)

    @Test
    fun waitingThenDeparts() {
        val waiting = OneOffLogic.step(trip, bus("A", null), row(7, 30, 7, 33), row(7, 50, 7, 53), true, at(7, 25))
        assertFalse(waiting.trip.departed)
        assertEquals(at(7, 33).epochSeconds, waiting.trip.expectedDeparture)
        assertEquals(at(7, 53).epochSeconds, waiting.trip.expectedArrival)
        assertNull(waiting.end)

        val moving = OneOffLogic.step(waiting.trip, bus("B", "A"), null, row(7, 50, 7, 53), true, at(7, 35))
        assertTrue(moving.trip.departed)
        assertFalse(moving.nextStopAlert)
    }

    @Test
    fun alertsOnceWhenYourStopIsNext() {
        val t = trip.copy(departed = true)
        val s1 = OneOffLogic.step(t, bus("D", "C"), null, row(7, 50, 7, 52), true, at(7, 47))
        assertTrue(s1.nextStopAlert)
        val s2 = OneOffLogic.step(s1.trip, bus("D", "C"), null, row(7, 50, 7, 52), true, at(7, 48))
        assertFalse(s2.nextStopAlert)
    }

    @Test
    fun arrivesWhenBusPassesYourStop() {
        val t = trip.copy(departed = true, expectedArrival = at(7, 52).epochSeconds)
        val s = OneOffLogic.step(t, bus(null, "D"), null, null, true, at(7, 53))
        assertEquals(TripEnd.Arrived, s.end)
        val rec = OneOffLogic.record(s.trip, TripEnd.Arrived)
        assertEquals(2, rec.arrivalDelayMinutes)
    }

    @Test
    fun arrivesWhenDestinationBoardDropsIt() {
        val t = trip.copy(departed = true, expectedArrival = at(7, 52).epochSeconds)
        assertEquals(TripEnd.Arrived, OneOffLogic.step(t, null, null, null, true, at(7, 56)).end)
        // Board failed to load: don't conclude anything yet.
        assertNull(OneOffLogic.step(t, null, null, null, false, at(7, 56)).end)
    }

    @Test
    fun givesUpEventually() {
        val t = trip.copy(departed = true)
        assertEquals(TripEnd.Lost, OneOffLogic.step(t, null, null, null, false, at(8, 40)).end)
    }
}

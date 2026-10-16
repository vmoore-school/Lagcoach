package app.lagcoach.engine

import app.lagcoach.model.Departure
import app.lagcoach.model.Observation
import app.lagcoach.model.UkZone
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ArrivalTrackerTest {
    private fun at(h: Int, m: Int) = LocalDateTime(2026, 10, 19, h, m).toInstant(UkZone)
    private val left = Observation(
        watchId = "w", date = "2026-10-19", line = "42", destination = "X",
        scheduled = at(7, 30).epochSeconds, expected = at(7, 33).epochSeconds, travelMinutes = 25, journeyId = 99,
    )

    private fun dep(sh: Int, sm: Int, eh: Int?, em: Int?, journey: Long? = null) =
        Departure("42", "X", at(sh, sm), eh?.let { at(it, em!!) }, false, null, journey, null)

    @Test
    fun pendingOnlyAfterDeparture() {
        assertTrue(ArrivalTracker.pending(listOf(left), at(7, 32)).isEmpty())
        assertEquals(1, ArrivalTracker.pending(listOf(left), at(7, 40)).size)
        // Well past the expected arrival with nothing measured: settled.
        assertTrue(ArrivalTracker.pending(listOf(left), at(8, 30)).isEmpty())
    }

    @Test
    fun matchesByJourneyThenByTime() {
        val byJourney = ArrivalTracker.update(listOf(left), listOf(dep(7, 55, 8, 1, journey = 99)), at(7, 45)).single()
        assertEquals(at(8, 1).epochSeconds, byJourney.arrivalExpected)
        assertEquals(at(7, 55).epochSeconds, byJourney.arrivalScheduled)

        val byTime = ArrivalTracker.update(listOf(left.copy(journeyId = null)), listOf(dep(7, 40, null, null), dep(7, 55, 7, 59)), at(7, 45)).single()
        assertEquals(at(7, 59).epochSeconds, byTime.arrivalExpected)
    }

    @Test
    fun keepsLastEstimateWhenLiveDrops() {
        val seen = left.copy(arrivalExpected = at(8, 0).epochSeconds, arrivalScheduled = at(7, 55).epochSeconds)
        assertTrue(ArrivalTracker.update(listOf(seen), listOf(dep(7, 55, null, null, journey = 99)), at(7, 59)).isEmpty())
    }

    @Suppress("unused")
    private fun i(e: Long) = Instant.fromEpochSeconds(e)
}

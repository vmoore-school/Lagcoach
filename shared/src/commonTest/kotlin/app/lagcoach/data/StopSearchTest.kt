package app.lagcoach.data

import app.lagcoach.model.StopRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopSearchTest {
    private val bedford = Place("Bedford, Beds", "bedford-beds")
    private val leigh = Place("Bedford, Leigh", "bedford-leigh")
    private val bedfordPark = Place("Bedford Park", "bedford-park")
    private val fake: suspend (String) -> List<Place> = { q ->
        when (q.lowercase()) {
            "bedford" -> listOf(bedfordPark, leigh, bedford)
            "piccadilly gardens" -> listOf(Place("Piccadilly Gardens", "piccadilly-gardens"))
            else -> emptyList()
        }
    }
    private val stops: suspend (Place) -> List<StopRef> = { p ->
        when (p) {
            bedford -> listOf(StopRef("a", "Bedford Bus Station (Stand A)"), StopRef("b", "Bedford Midland Road"))
            leigh -> listOf(StopRef("c", "Bedford Church"))
            else -> emptyList()
        }
    }

    @Test
    fun placeThenStopWords() = runTest {
        val o = StopSearch.resolve("Bedford bus station", fake, stops)
        assertEquals(bedford, o.chosen) // the Bedford that has a bus station, not the first listed
        assertEquals("bus station", o.filter)
        assertEquals(2, o.stops?.size)
    }

    @Test
    fun leftoverWordsMatchingNothingShowPlaces() = runTest {
        val o = StopSearch.resolve("Bedford zebra", fake, stops)
        assertNull(o.chosen)
        assertEquals(3, o.places.size)
    }

    @Test
    fun plainPlace() = runTest {
        val o = StopSearch.resolve("piccadilly gardens", fake, stops)
        assertNull(o.chosen)
        assertEquals(1, o.places.size)
    }

    @Test
    fun nothing() = runTest {
        assertTrue(StopSearch.resolve("zzzz qqqq", fake, stops).places.isEmpty())
    }

    @Test
    fun filterMatching() {
        val stand = StopRef("1", "Bedford Bus Station (Stand A)", lines = listOf("44"))
        assertTrue(StopSearch.matches(stand, "bus station"))
        assertTrue(StopSearch.matches(stand, "stand a"))
        assertTrue(StopSearch.matches(stand, "bus stat"))
        assertTrue(StopSearch.matches(stand, "44"))
        assertFalse(StopSearch.matches(stand, "railway"))
    }
}

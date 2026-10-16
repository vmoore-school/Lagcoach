package app.lagcoach.data

import app.lagcoach.model.StopRef

/**
 * bustimes.org search only matches place names, so "bedford bus station" finds nothing.
 * Work out the place from the leading words ("bedford") and use the rest ("bus station")
 * to filter that place's stops.
 */
object StopSearch {
    data class Outcome(
        val places: List<Place>,
        /** Place to open straight away, when part of the query was about the stop itself. */
        val chosen: Place?,
        /** Leftover words to filter the chosen place's stops with. */
        val filter: String,
        /** The chosen place's stops, already loaded. */
        val stops: List<StopRef>? = null,
    )

    suspend fun resolve(
        query: String,
        searchPlaces: suspend (String) -> List<Place>,
        placeStops: suspend (Place) -> List<StopRef>,
    ): Outcome {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Outcome(emptyList(), null, "")
        // Longest prefix first; cap the number of requests.
        for (k in words.size downTo maxOf(1, words.size - 3)) {
            val prefix = words.take(k).joinToString(" ")
            if (prefix.length < 3) break
            val places = searchPlaces(prefix)
            if (places.isEmpty()) continue
            val rest = words.drop(k).joinToString(" ")
            if (rest.isEmpty()) return Outcome(places, null, "")
            // Several places can share a name (Bedford, Beds / Bedford, Leigh): look in the
            // likeliest few and take the one with the most stops matching the rest.
            val ranked = places.sortedByDescending { it.name.substringBefore(",").trim().equals(prefix, ignoreCase = true) }
            var best: Triple<Place, List<StopRef>, Int>? = null
            for (p in ranked.take(3)) {
                val stops = runCatching { placeStops(p) }.getOrNull() ?: continue
                val hits = stops.count { matches(it, rest) }
                if (best == null || hits > best.third) best = Triple(p, stops, hits)
            }
            if (best == null || best.third == 0) {
                // Leftover words might be part of a place name we didn't find; show the places.
                return Outcome(places, null, rest)
            }
            return Outcome(places, best.first, rest, best.second)
        }
        return Outcome(emptyList(), null, "")
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")

    /** Every word of [filter] appears in the stop name (or its routes, e.g. "42"). */
    fun matches(stop: StopRef, filter: String): Boolean {
        val terms = norm(filter).split(" ").filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val name = norm(stop.name)
        val nameWords = name.split(" ").filter { it.isNotBlank() }
        return terms.all { t -> name.contains(t) || stop.lines.any { it.equals(t, ignoreCase = true) } || nameWords.any { it.startsWith(t) } }
    }
}

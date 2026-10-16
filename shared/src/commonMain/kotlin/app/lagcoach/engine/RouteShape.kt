package app.lagcoach.engine

import app.lagcoach.data.distanceMetres

/** A point as (lat, lon). */
typealias LatLon = Pair<Double, Double>

/**
 * Turns a service's route shape (bustimes.org gives one road-following line per pair of
 * neighbouring stops, for every variant of the route) into the path of one trip.
 */
object RouteShape {
    /** How far a shape's end can be from the stop it serves... */
    const val MATCH_METRES = 60.0
    /** ...or, if nothing is that close, how far it can be at most (some shapes start a little up the road). */
    const val LOOSE_MATCH_METRES = 150.0

    /**
     * Walks [stops] in order, using the shape piece that runs from each stop to the next
     * (or the reverse of one that runs the other way), and a straight line where none fits.
     * The path is made of the pieces themselves, so it doesn't spur out to stop markers
     * that sit a little off the road.
     */
    fun stitch(stops: List<LatLon>, pieces: List<List<LatLon>>): List<LatLon> {
        if (stops.size < 2) return stops
        val usable = pieces.filter { it.size >= 2 }
        val path = mutableListOf<LatLon>()
        for (i in 0 until stops.lastIndex) {
            val a = stops[i]
            val b = stops[i + 1]
            val leg = bestPiece(a, b, usable, MATCH_METRES) ?: bestPiece(a, b, usable, LOOSE_MATCH_METRES) ?: listOf(a, b)
            // Neighbouring legs usually share their end point, so skip the repeat.
            path += if (path.isNotEmpty() && dist(leg.first(), path.last()) < 1.0) leg.drop(1) else leg
        }
        return path
    }

    /** Shortest distance in metres from [p] to the line [path]. */
    fun distanceTo(path: List<LatLon>, p: LatLon): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1) return dist(path[0], p)
        // Flat projection around p is plenty accurate over a few km.
        val kx = 111_320.0 * kotlin.math.cos(p.first * kotlin.math.PI / 180)
        val ky = 110_540.0
        fun xy(q: LatLon) = (q.second - p.second) * kx to (q.first - p.first) * ky
        var best = Double.MAX_VALUE
        for (i in 0 until path.lastIndex) {
            val (ax, ay) = xy(path[i])
            val (bx, by) = xy(path[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, kotlin.math.hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private fun bestPiece(a: LatLon, b: LatLon, pieces: List<List<LatLon>>, within: Double): List<LatLon>? {
        var best: List<LatLon>? = null
        var bestScore = Double.MAX_VALUE
        // Each end must sit nearer its own stop than the other one, or a loose match can grab the previous leg.
        fun fits(start: LatLon, end: LatLon) =
            dist(start, a) <= within && dist(end, b) <= within && dist(start, a) < dist(start, b) && dist(end, b) < dist(end, a)
        for (p in pieces) {
            val forward = dist(p.first(), a) + dist(p.last(), b)
            val backward = dist(p.last(), a) + dist(p.first(), b)
            if (forward < bestScore && fits(p.first(), p.last())) {
                best = p; bestScore = forward
            }
            if (backward < bestScore && fits(p.last(), p.first())) {
                best = p.reversed(); bestScore = backward
            }
        }
        return best
    }

    private fun dist(x: LatLon, y: LatLon) = distanceMetres(x.first, x.second, y.first, y.second)
}

/** Fetches and caches route shapes so the map can trace a bus's path. */
class RouteLoader(private val client: app.lagcoach.data.BusTimesClient) {
    private val trips = mutableMapOf<Long, app.lagcoach.data.TripInfo>()
    private val shapes = mutableMapOf<Long, List<List<LatLon>>>()
    private val journeys = mutableMapOf<Long, Long?>()

    /**
     * The path of a trip along the roads, or only the part between [fromAtco] and
     * [toAtco] when given. Empty if the trip can't be found.
     */
    suspend fun path(tripId: Long?, journeyId: Long?, fromAtco: String? = null, toAtco: String? = null): List<LatLon> {
        val id = tripId ?: journeyId?.let { j -> journeys.getOrPut(j) { runCatching { client.tripIdForJourney(j) }.getOrNull() } }
            ?: return emptyList()
        val info = trips[id] ?: client.trip(id).also { trips[id] = it }
        var stops = info.stops
        val from = fromAtco?.let { a -> stops.indexOfFirst { it.stop.atco == a } } ?: -1
        if (from >= 0) stops = stops.drop(from)
        val to = toAtco?.let { a -> stops.indexOfLast { it.stop.atco == a } } ?: -1
        if (to > 0) stops = stops.take(to + 1)
        val points = stops.mapNotNull { s -> s.stop.lat?.let { it to s.stop.lon!! } }
        val shape = info.serviceId?.let { sid ->
            shapes[sid] ?: runCatching { client.serviceShape(sid) }.getOrNull()?.also { shapes[sid] = it }
        }.orEmpty()
        return RouteShape.stitch(points, shape)
    }
}

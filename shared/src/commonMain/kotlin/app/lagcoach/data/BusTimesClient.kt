package app.lagcoach.data

import app.lagcoach.model.StopRef
import app.lagcoach.model.UkZone
import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

class BusTimesException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One stop on a trip, used to pick a destination and to work out travel time. */
data class TripStop(val stop: StopRef, val time: LocalTime?)

data class TripInfo(val serviceId: Long?, val stops: List<TripStop>)

data class Place(val name: String, val slug: String)

data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun query() = "ymax=$north&xmax=$east&ymin=$south&xmin=$west"
    companion object {
        fun around(lat: Double, lon: Double, metres: Double): BBox {
            val dLat = metres / 111_320.0
            val dLon = metres / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180))
            return BBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        }
    }
}

/** A bus currently reporting its position. */
data class LiveVehicle(
    val id: Long,
    val journeyId: Long?,
    val tripId: Long?,
    val line: String,
    val destination: String,
    val lat: Double,
    val lon: Double,
    val heading: Int?,
    /** Seconds behind (positive) or ahead of schedule, when known. */
    val delaySeconds: Long?,
    val nextStop: String?,
    val prevStop: String?,
    val name: String?,
    val updated: String?,
    /** How far between [prevStop] and [nextStop], 0..1. */
    val progress: Double? = null,
    val serviceId: Long? = null,
)

/**
 * Thin client for bustimes.org. Departure boards are HTML (there is no JSON departures
 * endpoint); everything else uses the public JSON API. Requests are kept small and
 * infrequent: only stops being watched are polled, and only inside their window.
 */
class BusTimesClient(
    private val http: HttpClient = createHttpClient(),
    private val base: String = "https://bustimes.org",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun departures(atco: String, page: String? = null): DepartureBoard {
        val query = page?.takeIf { it.startsWith("?") } ?: ""
        val html = getText("$base/stops/${atco.encodeURLQueryComponent()}/departures$query")
        return DepartureParser.parse(html, Clock.System.todayIn(UkZone))
    }

    suspend fun stop(atco: String): StopRef? =
        stopsQuery("atco_code=${atco.encodeURLQueryComponent()}").firstOrNull()

    /** The code printed on stop flags / used for SMS ("MANGPJGA"), or an ATCO code. */
    suspend fun stopByCode(code: String): StopRef? {
        val c = code.trim().replace(" ", "")
        if (c.isEmpty()) return null
        // NaPTAN code case varies by area (Manchester "MANGPJGA", Bedford "bfsamadm").
        for (q in listOf(c, c.uppercase(), c.lowercase()).distinct()) {
            stopsQuery("naptan_code=${q.encodeURLQueryComponent()}").firstOrNull()?.let { return it }
        }
        return stopsQuery("atco_code=${c.uppercase().encodeURLQueryComponent()}").firstOrNull()
    }

    private suspend fun stopsQuery(q: String): List<StopRef> {
        val body = json.parseToJsonElement(getText("$base/api/stops/?$q"))
        return body.jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { parseApiStop(it) }
    }

    private fun parseApiStop(e: JsonElement): StopRef? {
        val o = e as? JsonObject ?: return null
        val atco = o.str("atco_code") ?: return null
        if (o["active"]?.jsonPrimitive?.contentOrNull == "false") return null
        val loc = o["location"] as? JsonArray
        return StopRef(
            atco = atco,
            name = o.str("long_name") ?: o.str("name") ?: o.str("common_name") ?: atco,
            indicator = o.str("indicator"),
            lon = loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull,
            lat = loc?.getOrNull(1)?.jsonPrimitive?.doubleOrNull,
            lines = (o["line_names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    }

    /** Stops inside a small box around a point, nearest first. */
    suspend fun nearbyStops(lat: Double, lon: Double, radiusMetres: Double = 500.0): List<StopRef> =
        stopsIn(BBox.around(lat, lon, radiusMetres)).sortedBy { s -> distanceMetres(lat, lon, s.lat ?: lat, s.lon ?: lon) }

    /** Buses reporting a position inside [box]. */
    suspend fun vehiclesIn(box: BBox): List<LiveVehicle> {
        val arr = json.parseToJsonElement(getText("$base/vehicles.json?${box.query()}")) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val coords = o["coordinates"] as? JsonArray ?: return@mapNotNull null
            val service = o["service"] as? JsonObject
            val progress = o["progress"] as? JsonObject
            LiveVehicle(
                id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                journeyId = o["journey_id"]?.jsonPrimitive?.longOrNull,
                tripId = o["trip_id"]?.jsonPrimitive?.longOrNull,
                line = service?.str("line_name") ?: return@mapNotNull null,
                destination = o.str("destination") ?: "",
                lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                heading = o["heading"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
                delaySeconds = o["delay"]?.jsonPrimitive?.longOrNull,
                nextStop = progress?.str("next_stop"),
                prevStop = progress?.str("prev_stop"),
                name = (o["vehicle"] as? JsonObject)?.str("name"),
                updated = o.str("datetime"),
                progress = progress?.get("progress")?.jsonPrimitive?.doubleOrNull,
                serviceId = o["service_id"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    /** Stops inside [box] (unsorted). */
    suspend fun stopsIn(box: BBox): List<StopRef> {
        val body = json.parseToJsonElement(getText("$base/stops.json?${box.query()}")).jsonObject
        val stops = body["features"]?.jsonArray.orEmpty().mapNotNull { f ->
            val fo = f.jsonObject
            val coords = fo["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val p = fo["properties"]?.jsonObject ?: return@mapNotNull null
            val url2 = p.str("url") ?: return@mapNotNull null
            StopRef(
                atco = url2.substringAfterLast("/stops/").trim('/'),
                name = p.str("name") ?: return@mapNotNull null,
                indicator = p.str("indicator"),
                lon = coords[0].jsonPrimitive.doubleOrNull,
                lat = coords[1].jsonPrimitive.doubleOrNull,
                lines = (p["services"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }
        return stops
    }

    /** bustimes.org search: returns matching places (localities). */
    suspend fun searchPlaces(query: String): List<Place> {
        val html = getText("$base/search?q=${query.trim().encodeURLQueryComponent()}")
        val doc = Ksoup.parse(html)
        return doc.select("a[href^=/localities/]").map {
            Place(it.text().trim(), it.attr("href").removePrefix("/localities/").trim('/'))
        }.filter { it.name.isNotBlank() }.distinctBy { it.slug }
    }

    /**
     * Stops listed on a locality page. Names there are often relative to the place
     * ("Bus Station (Stand A)" under Bedford), so the place name is put in front.
     */
    suspend fun placeStops(place: Place): List<StopRef> {
        val doc = Ksoup.parse(getText("$base/localities/${place.slug}"))
        val town = place.name.substringBefore(",").trim()
        return doc.select("li > a[href^=/stops/]").map { a ->
            val raw = (a.selectFirst("span")?.text()?.trim() ?: a.ownText().trim())
                .trimEnd('↖', '↑', '↗', '→', '↘', '↓', '↙', '←', ' ')
            StopRef(
                atco = a.attr("href").removePrefix("/stops/").trim('/'),
                name = if (town.isEmpty() || raw.contains(town, ignoreCase = true)) raw else "$town $raw",
                lines = a.select("small").map { it.text().trim() }.filter { it.isNotEmpty() },
            )
        }.distinctBy { it.atco }
    }

    /** Timetabled calling points for a trip. */
    suspend fun tripStops(tripId: Long): List<TripStop> = trip(tripId).stops

    suspend fun trip(tripId: Long): TripInfo {
        val o = json.parseToJsonElement(getText("$base/api/trips/$tripId/")).jsonObject
        val serviceId = (o["service"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull
        return TripInfo(serviceId, o["times"]?.jsonArray.orEmpty().mapNotNull { t ->
            val to = t.jsonObject
            val s = to["stop"]?.jsonObject ?: return@mapNotNull null
            val atco = s.str("atco_code") ?: return@mapNotNull null
            val loc = s["location"] as? JsonArray
            val time = (to.str("aimed_arrival_time") ?: to.str("aimed_departure_time"))
                ?.let { DepartureParser.parseTime(it) }
            TripStop(
                StopRef(
                    atco = atco,
                    name = s.str("name") ?: atco,
                    lon = loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull,
                    lat = loc?.getOrNull(1)?.jsonPrimitive?.doubleOrNull,
                ),
                time,
            )
        })
    }

    /** A service's route shape: road-following lines between neighbouring stops, as (lat, lon). */
    suspend fun serviceShape(serviceId: Long): List<List<Pair<Double, Double>>> {
        val o = json.parseToJsonElement(getText("$base/services/$serviceId.json")).jsonObject
        val geometry = o["geometry"] as? JsonObject ?: return emptyList()
        val lines = geometry["coordinates"] as? JsonArray ?: return emptyList()
        fun line(l: kotlinx.serialization.json.JsonElement) = (l as? JsonArray).orEmpty().mapNotNull { p ->
            val c = p as? JsonArray ?: return@mapNotNull null
            val lon = c.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = c.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            lat to lon
        }
        return when (geometry.str("type")) {
            "MultiLineString" -> lines.map(::line)
            "LineString" -> listOf(line(lines))
            else -> emptyList()
        }
    }

    /** Live journeys link to /journeys/{id}; map that back to the timetable trip. */
    suspend fun tripIdForJourney(journeyId: Long): Long? {
        val o = json.parseToJsonElement(getText("$base/api/vehiclejourneys/$journeyId/")).jsonObject
        return o["trip_id"]?.jsonPrimitive?.longOrNull
    }

    private suspend fun getText(url: String): String {
        val resp: HttpResponse = try {
            http.get(url)
        } catch (e: Exception) {
            throw BusTimesException("Couldn't reach bustimes.org", e)
        }
        if (!resp.status.isSuccess()) throw BusTimesException("bustimes.org returned ${resp.status.value}")
        return resp.bodyAsText()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
}

fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p = kotlin.math.PI / 180
    val dLat = (lat2 - lat1) * p
    val dLon = (lon2 - lon1) * p
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(lat1 * p) * kotlin.math.cos(lat2 * p) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
}

const val USER_AGENT = "Lagcoach/1 (personal bus alert app; +https://github.com/vmoore-school/Lagcoach)"

expect fun platformHttpClient(): HttpClient

fun createHttpClient(): HttpClient = platformHttpClient().config {
    install(HttpTimeout) {
        requestTimeoutMillis = 20_000
        connectTimeoutMillis = 10_000
    }
    defaultRequest { header("User-Agent", USER_AGENT) }
}

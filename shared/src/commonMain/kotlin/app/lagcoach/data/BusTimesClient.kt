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

data class Place(val name: String, val slug: String)

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
        return stopsQuery("naptan_code=${c.uppercase().encodeURLQueryComponent()}").firstOrNull()
            ?: stopsQuery("atco_code=${c.uppercase().encodeURLQueryComponent()}").firstOrNull()
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
    suspend fun nearbyStops(lat: Double, lon: Double, radiusMetres: Double = 500.0): List<StopRef> {
        val dLat = radiusMetres / 111_320.0
        val dLon = radiusMetres / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180))
        val url = "$base/stops.json?ymax=${lat + dLat}&xmax=${lon + dLon}&ymin=${lat - dLat}&xmin=${lon - dLon}"
        val body = json.parseToJsonElement(getText(url)).jsonObject
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
        return stops.sortedBy { s -> distanceMetres(lat, lon, s.lat ?: lat, s.lon ?: lon) }
    }

    /** bustimes.org search: returns matching places (localities). */
    suspend fun searchPlaces(query: String): List<Place> {
        val html = getText("$base/search?q=${query.trim().encodeURLQueryComponent()}")
        val doc = Ksoup.parse(html)
        return doc.select("a[href^=/localities/]").map {
            Place(it.text().trim(), it.attr("href").removePrefix("/localities/").trim('/'))
        }.filter { it.name.isNotBlank() }.distinctBy { it.slug }
    }

    /** Stops listed on a locality page. */
    suspend fun placeStops(slug: String): List<StopRef> {
        val doc = Ksoup.parse(getText("$base/localities/${slug}"))
        return doc.select("li > a[href^=/stops/]").map { a ->
            val name = a.selectFirst("span")?.text()?.trim() ?: a.ownText().trim()
            StopRef(
                atco = a.attr("href").removePrefix("/stops/").trim('/'),
                name = name.trimEnd('↖', '↑', '↗', '→', '↘', '↓', '↙', '←', ' '),
                lines = a.select("small").map { it.text().trim() }.filter { it.isNotEmpty() },
            )
        }.distinctBy { it.atco }
    }

    /** Timetabled calling points for a trip. */
    suspend fun tripStops(tripId: Long): List<TripStop> {
        val o = json.parseToJsonElement(getText("$base/api/trips/$tripId/")).jsonObject
        return o["times"]?.jsonArray.orEmpty().mapNotNull { t ->
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

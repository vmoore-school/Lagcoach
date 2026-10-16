package app.lagcoach.data

import app.lagcoach.model.Departure
import app.lagcoach.model.UkZone
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

data class DepartureBoard(
    val departures: List<Departure>,
    /** Query string for the next page ("?date=...&time=..."), scheduled times only. */
    val nextPage: String?,
)

/**
 * Parses the departures fragment served at bustimes.org/stops/{atco}/departures.
 * Layout (busstops/templates/departures.html): one table per service day, an optional
 * <h3> day heading before tables that aren't today, columns: route, destination,
 * Scheduled, Expected (only when any live data). Cancelled rows wrap the time in <s>.
 */
object DepartureParser {

    fun parse(html: String, today: LocalDate, zone: TimeZone = UkZone): DepartureBoard {
        val doc = Ksoup.parse(html)
        val root = doc.selectFirst("#departures") ?: doc.body()
        var date = today
        val out = mutableListOf<Departure>()
        for (el in root.children()) {
            when (el.tagName()) {
                "h3" -> parseDayHeading(el.text(), today)?.let { date = it }
                "table" -> parseTable(el, date, zone, out)
            }
        }
        val next = root.selectFirst("p.next a")?.attr("href")?.takeIf { it.isNotBlank() }
        return DepartureBoard(out, next)
    }

    private fun parseTable(table: Element, date: LocalDate, zone: TimeZone, out: MutableList<Departure>) {
        val headers = table.select("thead th").map { it.text().replace("­", "").trim().lowercase() }
        var scheduledCol = headers.indexOfFirst { it.startsWith("sched") }
        var expectedCol = headers.indexOfFirst { it.startsWith("expected") }
        if (headers.isEmpty()) scheduledCol = 2
        for (row in table.select("tbody > tr")) {
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 3) continue
            val line = cells[0].text().trim()
            val destCell = cells[1]
            val vehicle = destCell.selectFirst(".vehicle")?.text()?.trim()?.ifBlank { null }
            val destination = destCell.ownText().trim().ifBlank {
                destCell.text().removeSuffix(vehicle ?: "").trim()
            }
            val schedCell = cells.getOrNull(if (scheduledCol >= 0) scheduledCol else 2) ?: continue
            val schedTime = parseTime(schedCell.text()) ?: continue
            val scheduled = LocalDateTime(date, schedTime).toInstant(zone)
            val cancelled = schedCell.selectFirst("s, del, strike") != null
            val expected = if (expectedCol >= 0) {
                cells.getOrNull(expectedCol)?.let { parseTime(it.text()) }?.let { t ->
                    alignNear(LocalDateTime(date, t).toInstant(zone), scheduled)
                }
            } else null
            val link = schedCell.selectFirst("a")?.attr("href")
                ?: cells.getOrNull(expectedCol)?.selectFirst("a")?.attr("href")
            out += Departure(
                line = line,
                destination = destination,
                scheduled = scheduled,
                expected = expected,
                cancelled = cancelled,
                tripId = link?.let { idAfter(it, "/trips/") },
                journeyId = link?.let { idAfter(it, "/journeys/") },
                vehicle = vehicle,
            )
        }
    }

    /** Expected times have no date; pick the one within 12h of the scheduled time. */
    private fun alignNear(t: Instant, ref: Instant): Instant = when {
        t - ref > 12.hours -> t - 1.days
        ref - t > 12.hours -> t + 1.days
        else -> t
    }

    private val timeRe = Regex("""(\d{1,2}):(\d{2})""")

    internal fun parseTime(text: String): LocalTime? {
        val m = timeRe.find(text) ?: return null
        val h = m.groupValues[1].toInt() % 24
        val min = m.groupValues[2].toInt()
        return if (min in 0..59) LocalTime(h, min) else null
    }

    private fun idAfter(href: String, prefix: String): Long? {
        val i = href.indexOf(prefix)
        if (i < 0) return null
        return href.substring(i + prefix.length).takeWhile { it.isDigit() }.toLongOrNull()
    }

    private val months = Month.entries.associateBy { it.name.lowercase() }

    /** "Saturday 17 October" -> the nearest such date on/after [today]. */
    internal fun parseDayHeading(text: String, today: LocalDate): LocalDate? {
        val parts = text.trim().split(Regex("\\s+"))
        val day = parts.firstNotNullOfOrNull { it.toIntOrNull() } ?: return null
        val month = parts.firstNotNullOfOrNull { months[it.lowercase()] } ?: return null
        for (year in listOf(today.year, today.year + 1)) {
            val d = runCatching { LocalDate(year, month, day) }.getOrNull() ?: continue
            if (today.daysUntil(d) >= -1) return d
        }
        return today.plus(DatePeriod(days = 1))
    }
}

package app.lagcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.model.UkZone
import app.lagcoach.stats.DayStats
import app.lagcoach.stats.Punctuality
import app.lagcoach.stats.SlotStats
import app.lagcoach.stats.StatsCalc
import app.lagcoach.stats.Summary
import app.lagcoach.stats.formatClock
import app.lagcoach.stats.formatDelay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Clock

private enum class Range(val label: String, val days: Int?) { Week("7 days", 7), Month("30 days", 30), All("All time", null) }

@Composable
fun StatsScreen() {
    val c = Lag.colors
    val data by AppGraph.repo.data.collectAsState()
    val obs by AppGraph.repo.observations.collectAsState()
    var range by remember { mutableStateOf(Range.Month) }
    var watchId by remember { mutableStateOf<String?>(null) }
    val now = rememberNow(60_000)

    val since = range.days?.let { Clock.System.todayIn(UkZone).minus(DatePeriod(days = it - 1)).atStartOfDayIn(UkZone) }
    val filtered = obs.filter { watchId == null || it.watchId == watchId }
    val summary = remember(filtered, range, now) { StatsCalc.summarise(filtered, since, now) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LagHeader("Stats", subtitle = "How your buses have actually run", below = {
                Spacer(Modifier.height(16.dp))
                Segmented(Range.entries.map { it.label }, range.ordinal) { range = Range.entries[it] }
            })
        }
        if (data.watches.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceChip("All journeys", watchId == null) { watchId = null }
                    data.watches.forEach { w -> ChoiceChip(w.label, watchId == w.id) { watchId = w.id } }
                }
            }
        }
        if (summary.total == 0) {
            item {
                Message(
                    "No buses logged yet",
                    "Every bus in your journey windows gets logged while Lagcoach is watching. " +
                        "After a few trips you'll see how often they're late and what time they really turn up.",
                )
            }
            return@LazyColumn
        }
        item {
            Column(Modifier.padding(16.dp)) { StatTiles(summary) }
        }
        if (summary.days.size >= 2) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Average delay by day")
                    Panel { DelayChart(summary.days) }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp)) {
                SectionLabel("Your departures")
                Panel(padding = PaddingValues(0.dp)) {
                    val names = data.watches.associate { it.id to it }
                    summary.slots.forEachIndexed { i, s ->
                        if (i > 0) Divider(inset = 16)
                        SlotRow(s, names[s.watchId]?.destination?.name, showJourney = watchId == null && data.watches.size > 1, journey = names[s.watchId]?.label)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "On time means leaving between 1 min early and 5 min 59 s late, the UK standard. " +
                        "Times are the last live estimate before the bus reached your stop.",
                    style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
                )
            }
        }
        val arr = summary.arrival
        val hasDestination = data.watches.filter { it.destination != null }.map { it.id }.toSet()
        val withArrivals = summary.slots.filter { it.arrival != null && it.watchId in hasDestination }
        if (arr != null && withArrivals.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Getting there")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("Arrived on time", arr.onTimePct?.let { "$it%" } ?: "–", "${arr.onTime} of ${arr.count}", Modifier.weight(1f))
                        Tile("Arrival delay", arr.avgDelayMin?.let { formatDelay(it) } ?: "–", "vs timetable", Modifier.weight(1f),
                            tone = arr.avgDelayMin?.let { if (it >= Punctuality.LATE_FROM_MIN) Tone.Warn else null })
                        Tile("On the bus", arr.avgJourneyMin?.let { "${it.roundToInt()} min" } ?: "–", "average", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Panel(padding = PaddingValues(0.dp)) {
                        val names = data.watches.associate { it.id to it }
                        withArrivals.forEachIndexed { i, sl ->
                            if (i > 0) Divider(inset = 16)
                            ArrivalRow(sl, names[sl.watchId]?.destination?.name, sl.arrival!!)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "\"Plan for\" is the time 9 in 10 of these buses had arrived by. Arrivals are timed from the live board at your " +
                            "destination stop (${arr.measured} of ${arr.count}); the rest are estimated from the departure time plus the timetabled journey.",
                        style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArrivalRow(s: SlotStats, destination: String?, a: app.lagcoach.stats.ArrivalStats) {
    val c = Lag.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        RouteBadge(s.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val sched = "${s.scheduled.hour.toString().padStart(2, '0')}:${s.scheduled.minute.toString().padStart(2, '0')}"
            Text("$sched bus", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TNUM), color = c.ink)
            if (destination != null) {
                Text("to ${destination.substringBefore(" (")}", style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val parts = buildList {
                a.avgArrivalMinutes?.let { add("usually ${formatClock(it)}") }
                s.scheduledArrivalMinutes?.let { add("timetable ${formatClock(it.toDouble())}") }
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.inkMuted)
            val range = if (a.earliestMinutes != null && a.latestMinutes != null && a.count > 1)
                "between ${formatClock(a.earliestMinutes)} and ${formatClock(a.latestMinutes)}" else null
            Text(
                listOfNotNull(range, if (a.late > 0) "${a.late} late" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.inkFaint,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Plan for", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            Text(
                a.planForMinutes?.let { formatClock(it) } ?: "–",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TNUM), color = c.ink,
            )
        }
    }
}

@Composable
fun StatTiles(s: Summary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("On time", s.onTimePct?.let { "$it%" } ?: "–", "${s.onTime} of ${s.withLive} with live data", Modifier.weight(1f))
            Tile("Average delay", s.avgDelayMin?.let { formatDelay(it) } ?: "–", "across ${s.withLive} buses", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Late", "${s.late}", "6+ min", Modifier.weight(1f), tone = if (s.late > 0) Tone.Warn else null)
            Tile("Cancelled", "${s.cancelled}", "of ${s.total}", Modifier.weight(1f), tone = if (s.cancelled > 0) Tone.Bad else null)
            Tile("Alerts", "${s.flagged}", "sent", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, detail: String, modifier: Modifier = Modifier, tone: Tone? = null) {
    val c = Lag.colors
    Panel(modifier, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = TNUM),
            color = tone?.let { toneColors(it).first } ?: c.ink,
            maxLines = 1,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SlotRow(s: SlotStats, destination: String?, showJourney: Boolean, journey: String?) {
    val c = Lag.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        RouteBadge(s.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val sched = "${s.scheduled.hour.toString().padStart(2, '0')}:${s.scheduled.minute.toString().padStart(2, '0')}"
            Text("$sched departure", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TNUM), color = c.ink)
            if (showJourney && journey != null) {
                Text(journey, style = MaterialTheme.typography.labelMedium, color = c.brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val parts = buildList {
                add("${s.count} logged")
                if (s.late > 0) add("${s.late} late")
                if (s.early > 0) add("${s.early} early")
                if (s.cancelled > 0) add("${s.cancelled} cancelled")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                s.avgDepartureMinutes?.let { formatClock(it) } ?: "–",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TNUM), color = c.ink,
            )
            Text(
                s.avgDelayMin?.let { "avg ${formatDelay(it)}" } ?: "no live data",
                style = MaterialTheme.typography.labelSmall, color = c.inkMuted,
            )
        }
    }
}

/**
 * Average delay per day as thin bars from a zero baseline (late up, early down), with a
 * dashed line at the 6-minute "late" threshold. Tap a bar to read its exact value.
 */
@Composable
private fun DelayChart(days: List<DayStats>) {
    val c = Lag.colors
    val shown = days.takeLast(30)
    var selected by remember(shown) { mutableStateOf(shown.lastIndex) }
    val sel = shown.getOrNull(selected)
    val maxUp = max(Punctuality.LATE_FROM_MIN + 2.0, shown.maxOf { it.avgDelayMin ?: 0.0 })
    val maxDown = max(2.0, shown.maxOf { -(it.avgDelayMin ?: 0.0) })

    Column {
        if (sel != null) {
            Text(dayLabel(sel.date), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    sel.avgDelayMin?.let { formatDelay(it) } ?: "no live data",
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TNUM), color = c.ink,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${sel.count} bus${if (sel.count == 1) "" else "es"}" +
                        (if (sel.late > 0) " · ${sel.late} late" else "") +
                        (if (sel.cancelled > 0) " · ${sel.cancelled} cancelled" else ""),
                    Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        val desc = shown.joinToString("; ") { "${dayLabel(it.date)}: ${it.avgDelayMin?.let(::formatDelay) ?: "no data"}" }
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Average delay by day. $desc" }
                .pointerInput(shown) {
                    detectTapGestures { p ->
                        val slot = size.width / shown.size.toFloat()
                        selected = (p.x / slot).toInt().coerceIn(0, shown.lastIndex)
                    }
                },
        ) {
            val h = size.height
            val zeroY = h * (maxUp / (maxUp + maxDown)).toFloat()
            val unit = zeroY / maxUp.toFloat()
            val slot = size.width / shown.size
            val barW = (slot - 2.dp.toPx()).coerceIn(2.dp.toPx(), 14.dp.toPx())
            val r = minOf(4.dp.toPx(), barW / 2)

            // Late threshold, dashed and recessive.
            val ty = zeroY - Punctuality.LATE_FROM_MIN * unit
            drawLine(c.warn.copy(alpha = 0.6f), Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

            shown.forEachIndexed { i, d ->
                val cx = slot * i + slot / 2
                val v = d.avgDelayMin
                val color = if (i == selected) c.brand else c.brand.copy(alpha = 0.55f)
                if (v == null || abs(v) < 0.05) {
                    drawCircle(c.inkFaint, 2.dp.toPx(), Offset(cx, zeroY))
                } else {
                    val len = (abs(v) * unit).toFloat().coerceAtLeast(2.dp.toPx())
                    val top = if (v > 0) zeroY - len else zeroY
                    // Round only the data end; the baseline end stays square.
                    val path = Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = cx - barW / 2, top = top, right = cx + barW / 2, bottom = top + len,
                                topLeftCornerRadius = if (v > 0) CornerRadius(r) else CornerRadius.Zero,
                                topRightCornerRadius = if (v > 0) CornerRadius(r) else CornerRadius.Zero,
                                bottomLeftCornerRadius = if (v < 0) CornerRadius(r) else CornerRadius.Zero,
                                bottomRightCornerRadius = if (v < 0) CornerRadius(r) else CornerRadius.Zero,
                            ),
                        )
                    }
                    drawPath(path, color)
                }
                if (d.cancelled > 0) {
                    // Just under the baseline (or under a negative bar), so it reads as part of that day.
                    val y = zeroY + (if ((v ?: 0.0) < 0) (abs(v!!) * unit).toFloat() else 0f) + 4.dp.toPx()
                    drawRect(c.bad, Offset(cx - barW / 2, y), Size(barW, 3.dp.toPx()))
                }
            }
            drawLine(c.inkFaint, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(dayLabel(shown.first().date), style = MaterialTheme.typography.labelSmall, color = c.inkFaint)
            Spacer(Modifier.weight(1f))
            Text(dayLabel(shown.last().date), style = MaterialTheme.typography.labelSmall, color = c.inkFaint)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(16.dp).height(2.dp)) {
                drawLine(c.warn, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
            }
            Text("  6 min late", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            if (shown.any { it.cancelled > 0 }) {
                Spacer(Modifier.width(14.dp))
                Canvas(Modifier.width(12.dp).height(3.dp)) { drawRect(c.bad) }
                Text("  had a cancellation", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            }
        }
    }
}

private fun dayLabel(d: LocalDate): String {
    val dow = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val mon = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$dow ${d.day} $mon"
}

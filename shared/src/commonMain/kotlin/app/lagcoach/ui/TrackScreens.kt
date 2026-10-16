package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.engine.Schedule
import app.lagcoach.engine.TrackSource
import app.lagcoach.engine.TripPlan
import app.lagcoach.engine.TripSetup
import app.lagcoach.model.OneOffTrip
import app.lagcoach.model.roundHalfAway
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** "Where are you getting off?" for a bus picked on the map or from a board. */
@Composable
fun TrackSetupScreen(nav: Nav, src: TrackSource) {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<TripPlan?>(null) }
    var failed by remember { mutableStateOf(false) }
    val replacing = AppGraph.repo.data.collectAsState().value.activeTrip
    LaunchedEffect(src) {
        val r = runCatching { TripSetup.plan(AppGraph.client, src) }
        plan = r.getOrNull(); failed = r.isFailure || plan == null
    }
    Column(Modifier.fillMaxSize()) {
        val sub = when (src) {
            is TrackSource.FromStop -> "The ${Schedule.hhmm(src.departure.scheduled)} from ${src.stop.name.substringBefore(" (")}"
            is TrackSource.FromBus -> "to ${src.headsign}"
        }
        LagHeader("Track the ${src.line}", subtitle = sub, onBack = nav::pop)
        if (replacing != null) {
            Text(
                "This replaces tracking the ${replacing.line} to ${replacing.to.name.substringBefore(" (")}.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), style = MaterialTheme.typography.bodySmall, color = c.warn,
            )
        }
        Text(
            "Where are you getting off?",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium, color = c.ink,
        )
        when {
            plan == null && !failed -> Loading()
            plan == null -> Message("Couldn't load this bus's route", "It may not be matched to a timetable on bustimes.org.")
            plan!!.onward.isEmpty() -> Message("This bus doesn't stop anywhere after here")
            else -> {
                val stops = plan!!.onward
                LazyColumn(Modifier.windowInsetsPadding(WindowInsets.navigationBars), contentPadding = PaddingValues(bottom = 32.dp)) {
                    itemsIndexed(stops, key = { i, s -> "$i-${s.stop.atco}" }) { i, ts ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    val trip = TripSetup.build(src, plan!!, ts, Clock.System.now())
                                    scope.launch {
                                        AppGraph.repo.setActiveTrip(trip)
                                        AppGraph.platform.watchesChanged()
                                        nav.pop()
                                        nav.push(Screen.TrackTrip)
                                    }
                                }
                                .routeLine(i, stops.lastIndex)
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c.surface).border(2.dp, c.brand, CircleShape))
                            Spacer(Modifier.width(14.dp))
                            Text(ts.stop.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ts.time?.let {
                                Text("${Schedule.pad(it.hour)}:${Schedule.pad(it.minute)}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = c.inkMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.routeLine(i: Int, last: Int, passed: Boolean = false): Modifier {
    val c = Lag.colors
    return drawBehind {
        val x = 25.dp.toPx()
        drawLine(
            if (passed) c.line else c.brand, Offset(x, if (i == 0) size.height / 2 else 0f),
            Offset(x, if (i == last) size.height / 2 else size.height), 3.dp.toPx(),
        )
    }
}

@Composable
fun TrackTripScreen(nav: Nav) {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    val data by AppGraph.repo.data.collectAsState()
    val records by AppGraph.repo.oneOffs.collectAsState()
    val trip = data.activeTrip
    val now = rememberNow(15_000)

    if (trip == null) {
        // Finished (or stopped) while open: show how it went.
        val last = records.lastOrNull()
        Column(Modifier.fillMaxSize()) {
            LagHeader("Trip finished", onBack = nav::pop)
            if (last != null) {
                Panel(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RouteBadge(last.line)
                        Spacer(Modifier.width(12.dp))
                        Text("to ${last.toName}", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        last.actualArrival?.let { "Arrived ${Schedule.hhmm(Instant.fromEpochSeconds(it))}" } ?: "Stopped tracking",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = TNUM), color = c.ink,
                    )
                    last.arrivalDelayMinutes?.let { StatusTag(delayText(it), delayTone(it)) }
                    Spacer(Modifier.height(8.dp))
                    Text("Saved under Stats → One-off trips.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                }
            } else {
                Message("Not tracking a bus", "Pick a bus on the map, or tap a departure on a stop's board.")
            }
        }
        return
    }

    val eta = trip.expectedArrival ?: trip.scheduledArrival
    val arrDelay = if (trip.expectedArrival != null && trip.scheduledArrival != null)
        roundHalfAway((trip.expectedArrival - trip.scheduledArrival) / 60.0) else null
    val nextIdx = trip.stops.indexOfFirst { it.atco == trip.nextStop }

    Column(Modifier.fillMaxSize()) {
        LagHeader(
            "${trip.line} to ${trip.to.name.substringBefore(" (")}",
            subtitle = listOfNotNull(trip.headsign.takeIf { it.isNotBlank() }?.let { "Towards $it" }, trip.vehicle?.substringAfter(" - ")).joinToString(" · "),
            onBack = nav::pop,
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Panel(Modifier.padding(16.dp)) {
                    if (!trip.departed && trip.from != null) {
                        Text("Waiting at ${trip.from.name}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                        val due = trip.expectedDeparture ?: trip.scheduledDeparture
                        Text(
                            due?.let { "Bus due ${Schedule.hhmm(Instant.fromEpochSeconds(it))}" } ?: "Bus due soon",
                            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = TNUM), color = c.ink,
                        )
                        val dd = if (trip.expectedDeparture != null && trip.scheduledDeparture != null)
                            roundHalfAway((trip.expectedDeparture - trip.scheduledDeparture) / 60.0) else null
                        dd?.let { StatusTag(delayText(it), delayTone(it)) }
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Arriving at ${trip.to.name}", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            eta?.let { Schedule.hhmm(Instant.fromEpochSeconds(it)) } ?: "–",
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = TNUM), color = c.ink,
                        )
                        Spacer(Modifier.width(10.dp))
                        eta?.let {
                            val mins = ((it - now.epochSeconds) / 60).toInt()
                            if (mins in 0..180) Text(if (mins == 0) "due now" else "in $mins min", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        arrDelay?.let { StatusTag(delayText(it), delayTone(it)); Spacer(Modifier.width(8.dp)) }
                        trip.scheduledArrival?.let {
                            Text("Timetable ${Schedule.hhmm(Instant.fromEpochSeconds(it))}", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.inkMuted)
                        }
                    }
                    if (trip.lastUpdate > 0 && now.epochSeconds - trip.lastUpdate > 150) {
                        Spacer(Modifier.height(6.dp))
                        Text("No update for a few minutes; retrying.", style = MaterialTheme.typography.bodySmall, color = c.warn)
                    }
                }
            }
            item { SectionLabel("Stops", Modifier.padding(horizontal = 16.dp)) }
            itemsIndexed(trip.stops, key = { i, s -> "$i-${s.atco}" }) { i, s ->
                val passed = trip.departed && nextIdx >= 0 && i < nextIdx
                val isNext = i == nextIdx && trip.departed
                val mine = s.atco == trip.to.atco
                Row(
                    Modifier.fillMaxWidth().routeLine(i, trip.stops.lastIndex, passed).padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dot = when {
                        isNext -> Modifier.size(14.dp).clip(CircleShape).background(c.brand)
                        mine -> Modifier.size(14.dp).clip(CircleShape).background(c.surface).border(3.dp, c.ink, CircleShape)
                        passed -> Modifier.size(10.dp).clip(CircleShape).background(c.line)
                        else -> Modifier.size(10.dp).clip(CircleShape).background(c.surface).border(2.dp, c.brand, CircleShape)
                    }
                    Box(Modifier.width(14.dp).offset(x = (-2).dp), contentAlignment = Alignment.Center) { Box(dot) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (mine || isNext) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (passed) c.inkFaint else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (isNext) Text("Next stop", style = MaterialTheme.typography.labelSmall, color = c.brand)
                        if (mine) Text("Your stop", style = MaterialTheme.typography.labelSmall, color = c.ink)
                    }
                    s.time?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = if (passed) c.inkFaint else c.inkMuted)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                trip.lastLat?.let { lat ->
                    SecondaryButton("Show on map", {
                        SharedMap.show(lat, trip.lastLon!!, 15.5)
                        nav.tab = Tab.Live
                        nav.popTo { false }
                    }, Modifier.weight(1f))
                }
                SecondaryButton("Stop tracking", { scope.launch { AppGraph.tracker.stop(); AppGraph.platform.watchesChanged() } }, Modifier.weight(1f), color = c.bad)
            }
        }
    }
}

fun delayText(m: Int) = when {
    m >= 2 -> "$m min late"
    m <= -2 -> "${-m} min early"
    else -> "On time"
}

fun delayTone(m: Int) = when {
    m >= 6 -> Tone.Bad
    m >= 2 || m <= -2 -> Tone.Warn
    else -> Tone.Good
}

/** Card on the Journeys screen while a one-off trip is being tracked. */
@Composable
fun ActiveTripCard(trip: OneOffTrip, onClick: () -> Unit) {
    val c = Lag.colors
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(c.brandTint).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(trip.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("On the bus to ${trip.to.name.substringBefore(" (")}", style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripLine(trip), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

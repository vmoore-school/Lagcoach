package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.GeoResult
import app.lagcoach.data.BBox
import app.lagcoach.data.LiveVehicle
import app.lagcoach.engine.OneOffLogic
import app.lagcoach.engine.Schedule
import app.lagcoach.engine.TrackSource
import app.lagcoach.model.StopRef
import app.lagcoach.model.roundHalfAway
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

private fun Color.argbLong() = toArgb().toLong() and 0xFFFFFFFFL

/** Lets other screens (e.g. a tracked trip) point the map somewhere. */
val SharedMap = MapState()

class MapState {
    var focus by mutableStateOf<MapFocus?>(null)
    fun show(lat: Double, lon: Double, zoom: Double = 16.0) {
        focus = MapFocus(lat, lon, zoom, Clock.System.now().toEpochMilliseconds())
    }
}

@Composable
fun MapScreen(nav: Nav) {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    val data by AppGraph.repo.data.collectAsState()
    val trip = data.activeTrip
    var box by remember { mutableStateOf<BBox?>(null) }
    var zoom by remember { mutableStateOf(0.0) }
    var stops by remember { mutableStateOf<List<StopRef>>(emptyList()) }
    var vehicles by remember { mutableStateOf<List<LiveVehicle>>(emptyList()) }
    var me by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    var selectedStop by remember { mutableStateOf<StopRef?>(null) }
    var selectedBus by remember { mutableStateOf<LiveVehicle?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val initial = remember {
        val s = data.settings
        val saved = data.watches.flatMap { listOfNotNull(it.stop, it.destination) }.firstOrNull { it.lat != null }
        when {
            s.mapLat != null && s.mapLon != null -> MapFocus(s.mapLat, s.mapLon, s.mapZoom ?: 15.0, 0)
            saved != null -> MapFocus(saved.lat!!, saved.lon!!, 15.0, 0)
            else -> MapFocus(53.5, -2.0, 5.5, 0) // Great Britain
        }
    }

    // Stops: only when zoomed in far enough that there's a sensible number of them.
    LaunchedEffect(box, zoom) {
        val b = box ?: return@LaunchedEffect
        delay(350)
        if (zoom >= 13.5) {
            runCatching { AppGraph.client.stopsIn(b) }.onSuccess { stops = it }
        } else stops = emptyList()
        AppGraph.repo.updateSettings {
            it.copy(mapLat = (b.north + b.south) / 2, mapLon = (b.east + b.west) / 2, mapZoom = zoom)
        }
    }
    // Buses: refresh every 15 s while visible.
    LaunchedEffect(box, zoom) {
        val b = box ?: return@LaunchedEffect
        delay(350)
        while (true) {
            if (zoom >= 11.5) {
                runCatching { AppGraph.client.vehiclesIn(b) }
                    .onSuccess { vehicles = it; note = null }
                    .onFailure { note = "Can't reach bustimes.org" }
            } else vehicles = emptyList()
            delay(15_000)
        }
    }
    val highlight = trip?.let { t -> vehicles.firstOrNull { OneOffLogic.matchesBus(it, t) }?.id }

    Box(Modifier.fillMaxSize()) {
        BusMap(
            modifier = Modifier.fillMaxSize(),
            dark = isSystemInDarkTheme(),
            colors = MapColors(
                stopFill = c.surface.argbLong(), stopRing = c.brand.argbLong(),
                bus = c.routeBadge.argbLong(), busText = c.onRouteBadge.argbLong(), busRing = c.surface.argbLong(),
                highlight = c.brand.argbLong(), me = c.brand.argbLong(),
            ),
            stops = stops,
            vehicles = vehicles,
            me = me,
            highlightVehicle = highlight,
            focus = SharedMap.focus,
            initial = initial,
            onCamera = { b, z -> box = b; zoom = z },
            onStopTap = { selectedStop = it },
            onVehicleTap = { selectedBus = it },
        )

        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .clickable {
                        nav.push(Screen.PickStop("Find a stop") { s ->
                            // The map screen isn't composed while the board is open, so use the app scope.
                            AppGraph.scope.launch {
                                val full = if (s.lat == null) runCatching { AppGraph.client.stop(s.atco) }.getOrNull() ?: s else s
                                full.lat?.let { SharedMap.show(it, full.lon!!, 17.0) }
                            }
                            nav.push(Screen.Board(s))
                        })
                    }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.inkMuted)
                Spacer(Modifier.width(10.dp))
                Text("Search stops and places", style = MaterialTheme.typography.bodyLarge, color = c.inkMuted)
            }
            val hint = when {
                note != null -> note
                zoom in 0.1..11.5 -> "Zoom in to see buses and stops"
                zoom in 11.5..13.5 -> "Zoom in further to see stops"
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    Modifier.align(Alignment.CenterHorizontally).shadow(2.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)).background(c.header).padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = c.onHeader,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(52.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(c.surface)
                    .clickable(enabled = !locating) {
                        scope.launch {
                            locating = true
                            when (val g = AppGraph.platform.currentLocation()) {
                                is GeoResult.Ok -> { me = g.lat to g.lon; SharedMap.show(g.lat, g.lon, 16.0) }
                                GeoResult.Denied -> note = "Location permission is off"
                                GeoResult.Unavailable -> note = "Couldn't get a location fix"
                            }
                            locating = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.MyLocation, "Show my location", tint = if (locating) c.inkFaint else c.brand) }
        }

        if (trip != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 76.dp, bottom = 14.dp)
                    .shadow(4.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(c.header)
                    .clickable { nav.push(Screen.TrackTrip) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RouteBadge(trip.line)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("To ${trip.to.name.substringBefore(" (")}", style = MaterialTheme.typography.titleSmall, color = c.onHeader, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tripLine(trip), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.onHeaderMuted, maxLines = 1)
                }
            }
        }
    }

    selectedStop?.let { s -> StopSheet(nav, s) { selectedStop = null } }
    selectedBus?.let { v -> BusSheet(nav, v, stops) { selectedBus = null } }
}

fun tripLine(t: app.lagcoach.model.OneOffTrip): String {
    val eta = (t.expectedArrival ?: t.scheduledArrival)?.let { Schedule.hhmm(kotlin.time.Instant.fromEpochSeconds(it)) }
    val next = t.stops.firstOrNull { it.atco == t.nextStop }?.name
    return when {
        !t.departed -> "Waiting for the bus" + ((t.expectedDeparture ?: t.scheduledDeparture)?.let { " · due ${Schedule.hhmm(kotlin.time.Instant.fromEpochSeconds(it))}" } ?: "")
        next != null && eta != null -> "Next: $next · arrive $eta"
        eta != null -> "Arrive $eta"
        else -> "Following the bus"
    }
}

@Composable
private fun StopSheet(nav: Nav, stop: StopRef, onDismiss: () -> Unit) {
    val c = Lag.colors
    val (board, _) = rememberBoard(stop.atco)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleLarge, color = c.ink)
            if (stop.lines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { stop.lines.sortedWith(lineOrder).take(7).forEach { RouteBadge(it) } }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tap a bus to track it to your stop.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Spacer(Modifier.height(8.dp))
        when {
            board.departures == null && board.error == null -> Loading()
            board.departures.isNullOrEmpty() -> Message(if (board.error != null) "Couldn't load departures" else "No departures listed")
            else -> board.departures!!.take(5).forEachIndexed { i, d ->
                if (i > 0) Divider(inset = 72)
                DepartureRow(d, onClick = { onDismiss(); nav.push(Screen.TrackSetup(TrackSource.FromStop(stop, d))) })
            }
        }
        Box(Modifier.padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            SecondaryButton("Full timetable", { onDismiss(); nav.push(Screen.Board(stop)) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BusSheet(nav: Nav, v: LiveVehicle, stops: List<StopRef>, onDismiss: () -> Unit) {
    val c = Lag.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(v.line)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("to ${v.destination}", style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    v.name?.let { Text(it.substringAfter(" - ", it), style = MaterialTheme.typography.bodySmall, color = c.inkMuted) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val delay = v.delaySeconds?.let { roundHalfAway(it / 60.0) }
            val (text, tone) = when {
                delay == null -> "No timing info" to Tone.Neutral
                delay >= 6 -> "$delay min late" to Tone.Bad
                delay >= 2 -> "$delay min late" to Tone.Warn
                delay <= -2 -> "${-delay} min early" to Tone.Warn
                else -> "On time" to Tone.Good
            }
            var next by remember(v.nextStop) { mutableStateOf(stops.firstOrNull { it.atco == v.nextStop }?.name) }
            LaunchedEffect(v.nextStop) {
                if (next == null && v.nextStop != null) next = runCatching { AppGraph.client.stop(v.nextStop) }.getOrNull()?.name
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusTag(text, tone)
                if (next != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("Next: $next", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                "I'm on this bus",
                { onDismiss(); nav.push(Screen.TrackSetup(TrackSource.FromBus(v))) },
                Modifier.fillMaxWidth(),
                enabled = v.tripId != null || v.journeyId != null,
            )
            if (v.tripId == null && v.journeyId == null) {
                Text("This bus isn't matched to a timetable, so it can't be tracked to a stop.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

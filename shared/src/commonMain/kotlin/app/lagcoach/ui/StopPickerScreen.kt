package app.lagcoach.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.GeoResult
import app.lagcoach.data.Place
import app.lagcoach.data.StopSearch
import app.lagcoach.data.distanceMetres
import app.lagcoach.model.StopRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PickMode(val label: String) { Nearby("Nearby"), Search("Search"), Code("Stop code") }

@Composable
fun StopPickerScreen(title: String, onBack: () -> Unit, onPicked: (StopRef) -> Unit) {
    var mode by remember { mutableStateOf(PickMode.Search) }
    Column(Modifier.fillMaxSize()) {
        LagHeader(title, onBack = onBack, below = {
            Spacer(Modifier.height(16.dp))
            Segmented(PickMode.entries.map { it.label }, mode.ordinal) { mode = PickMode.entries[it] }
        })
        Box(Modifier.weight(1f)) {
            when (mode) {
                PickMode.Nearby -> NearbyStops(onPicked)
                PickMode.Search -> SearchStops(onPicked)
                PickMode.Code -> CodeLookup(onPicked)
            }
        }
    }
}

/** Segmented control on the navy header. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Lag.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.onHeader.copy(alpha = 0.12f)).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.ink else c.onHeader)
            }
        }
    }
}

@Composable
private fun NearbyStops(onPicked: (StopRef) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>("idle") }
    var here by remember { mutableStateOf<GeoResult.Ok?>(null) }
    var stops by remember { mutableStateOf<List<StopRef>>(emptyList()) }
    fun load() = scope.launch {
        state = "locating"
        when (val g = AppGraph.platform.currentLocation()) {
            is GeoResult.Ok -> {
                here = g
                state = "loading"
                stops = runCatching { AppGraph.client.nearbyStops(g.lat, g.lon) }.getOrElse { state = "error"; return@launch }
                state = null
            }
            GeoResult.Denied -> state = "denied"
            GeoResult.Unavailable -> state = "unavailable"
        }
    }
    when (state) {
        "idle" -> Message(
            "Find stops near you",
            "Uses your phone's location once to list stops within about 500 m. It isn't stored or sent anywhere except as a map area to bustimes.org.",
            action = { PrimaryButton("Use my location", { load() }, icon = Icons.Outlined.MyLocation) },
        )
        "locating" -> Column { Loading(); Text("Getting a location fix…", Modifier.fillMaxWidth().padding(horizontal = 24.dp), color = Lag.colors.inkMuted) }
        "loading" -> Loading()
        "denied" -> Message("Location permission is off", "Allow location for Lagcoach, or search by place name instead.",
            action = { SecondaryButton("Try again", { load() }) })
        "unavailable" -> Message("Couldn't get a location", "Check location is switched on. Indoors a GPS fix can take a minute.",
            action = { SecondaryButton("Try again", { load() }) })
        "error" -> Message("Couldn't load stops", "Check your connection and try again.", action = { SecondaryButton("Try again", { load() }) })
        else -> if (stops.isEmpty()) Message("No stops within 500 m") else StopList(stops, here, onPicked)
    }
}

@Composable
private fun SearchStops(onPicked: (StopRef) -> Unit) {
    val c = Lag.colors
    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<StopSearch.Outcome?>(null) }
    var place by remember { mutableStateOf<Place?>(null) }
    var filter by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf<List<StopRef>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        place = null
        if (query.trim().length < 3) { outcome = null; return@LaunchedEffect }
        delay(400)
        busy = true
        val r = runCatching {
            StopSearch.resolve(query, { AppGraph.client.searchPlaces(it) }, { AppGraph.client.placeStops(it) })
        }
        failed = r.isFailure
        outcome = r.getOrNull()
        outcome?.let { o -> if (o.chosen != null) { stops = o.stops; filter = o.filter; place = o.chosen } }
        busy = false
    }
    LaunchedEffect(place) {
        val p = place ?: return@LaunchedEffect
        if (p == outcome?.chosen && outcome?.stops != null) { stops = outcome!!.stops; return@LaunchedEffect }
        stops = null; busy = true
        val r = runCatching { AppGraph.client.placeStops(p) }
        stops = r.getOrNull(); failed = r.isFailure
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, "Town and stop, e.g. Bedford bus station", KeyboardCapitalization.Words) { query = it }
        val o = outcome
        when {
            busy -> Loading()
            failed -> Message("Couldn't reach bustimes.org", "Check your connection and try again.")
            place != null && stops != null -> {
                val shown = stops!!.filter { StopSearch.matches(it, filter) }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stops in ${place!!.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.ink)
                    if ((o?.places?.size ?: 0) > 1) TextAction("Other places", { place = null; filter = "" })
                }
                FilterField(filter, "Filter ${stops!!.size} stops") { filter = it }
                if (shown.isEmpty()) {
                    Message("No stops match \"${filter.trim()}\"", "Try fewer words, or clear the filter to see every stop here.",
                        action = { SecondaryButton("Show all stops", { filter = "" }) })
                } else {
                    StopList(shown, null, onPicked)
                }
            }
            o == null -> Message(
                "Search by place, then stop",
                "Type the town or area, optionally followed by the stop, like \"Bedford bus station\" or \"Piccadilly Gardens\".",
            )
            o.places.isEmpty() -> Message("No places match \"${query.trim()}\"", "Start with the town or area name, or use the stop code.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(o.places, key = { it.slug }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { place = p; filter = o.filter }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    }
                    Divider(inset = 20)
                }
            }
        }
    }
}

@Composable
private fun FilterField(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Lag.colors
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
        placeholder = { Text(hint, color = c.inkFaint) },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(10.dp),
        trailingIcon = if (value.isNotEmpty()) {
            { TextAction("Clear", { onChange("") }, color = c.inkMuted) }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.brand, unfocusedBorderColor = c.line,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        ),
    )
}

@Composable
private fun CodeLookup(onPicked: (StopRef) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<StopRef?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    fun find() = scope.launch {
        status = "busy"
        val r = runCatching { AppGraph.client.stopByCode(code) }
        result = r.getOrNull()
        status = when {
            r.isFailure -> "Couldn't reach bustimes.org."
            result == null -> "No stop found with that code."
            else -> null
        }
    }
    Column(Modifier.fillMaxSize()) {
        SearchField(code, "e.g. MANGPJGA or 1800SB04801", KeyboardCapitalization.Characters, onDone = { find() }) { code = it }
        Text(
            "The stop code is on the sign or timetable case at the stop, often next to \"text this code\".",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = Lag.colors.inkMuted,
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { PrimaryButton("Find stop", { find() }, enabled = code.isNotBlank()) }
        when (status) {
            "busy" -> Loading()
            null -> result?.let { StopList(listOf(it), null, onPicked) }
            else -> Message(status!!)
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    hint: String,
    caps: KeyboardCapitalization,
    onDone: (() -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    val c = Lag.colors
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
        placeholder = { Text(hint, color = c.inkFaint) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = c.inkMuted) },
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(capitalization = caps, imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.brand, unfocusedBorderColor = c.line,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        ),
    )
}

@Composable
fun StopList(stops: List<StopRef>, here: GeoResult.Ok?, onPicked: (StopRef) -> Unit) {
    val c = Lag.colors
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(stops, key = { it.atco }) { s ->
            Row(Modifier.fillMaxWidth().clickable { onPicked(s) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (s.lines.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val shown = s.lines.sortedWith(lineOrder).take(6)
                            shown.forEach { RouteBadge(it) }
                            if (s.lines.size > shown.size) Text("+${s.lines.size - shown.size}", Modifier.align(Alignment.CenterVertically), color = c.inkMuted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (here != null && s.lat != null && s.lon != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${distanceMetres(here.lat, here.lon, s.lat, s.lon).toInt()} m",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TNUM), color = c.inkMuted,
                    )
                }
            }
            Divider(inset = 20)
        }
    }
}

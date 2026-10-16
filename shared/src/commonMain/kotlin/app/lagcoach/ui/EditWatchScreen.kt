package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.data.TripStop
import app.lagcoach.engine.Schedule
import app.lagcoach.model.AlertPrefs
import app.lagcoach.model.StopRef
import app.lagcoach.model.Watch
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock

/** Draft held outside composition so it survives a trip to the stop picker. */
class EditorState(
    val id: String,
    val isNew: Boolean,
    label: String,
    stop: StopRef?,
    destination: StopRef?,
    lines: Set<String>,
    windowStart: Int,
    windowEnd: Int,
    days: Set<Int>,
    alerts: AlertPrefs,
    val enabled: Boolean,
) {
    var label by mutableStateOf(label)
    var stop by mutableStateOf(stop)
    var destination by mutableStateOf(destination)
    var lines by mutableStateOf(lines)
    var windowStart by mutableStateOf(windowStart)
    var windowEnd by mutableStateOf(windowEnd)
    var days by mutableStateOf(days)
    var alerts by mutableStateOf(alerts)
    var stopLines by mutableStateOf(stop?.lines.orEmpty())

    fun toWatch(): Watch? {
        val s = stop ?: return null
        return Watch(
            id = id, label = label.trim().ifEmpty { "My journey" }, stop = s, destination = destination,
            lines = lines.toList().sortedWith(lineOrder), windowStart = windowStart, windowEnd = windowEnd,
            days = days, enabled = enabled, alerts = alerts,
        )
    }

    companion object {
        fun new() = EditorState(
            id = Clock.System.now().toEpochMilliseconds().toString(36) + Random.nextInt(1000, 9999),
            isNew = true, label = "Morning commute", stop = null, destination = null, lines = emptySet(),
            windowStart = 7 * 60 + 30, windowEnd = 8 * 60, days = setOf(1, 2, 3, 4, 5), alerts = AlertPrefs(), enabled = true,
        )

        fun of(w: Watch) = EditorState(
            w.id, false, w.label, w.stop, w.destination, w.lines.toSet(), w.windowStart, w.windowEnd, w.days, w.alerts, w.enabled,
        )
    }
}

/** Sort route numbers naturally: 2, 9, 42, 42A, 101, X50. */
val lineOrder = Comparator<String> { a, b ->
    val na = a.takeWhile { it.isDigit() }.toIntOrNull()
    val nb = b.takeWhile { it.isDigit() }.toIntOrNull()
    when {
        na != null && nb != null && na != nb -> na.compareTo(nb)
        na != null && nb == null -> -1
        na == null && nb != null -> 1
        else -> a.compareTo(b, ignoreCase = true)
    }
}

@Composable
fun EditWatchScreen(nav: Nav, st: EditorState) {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    var pickingTime by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var pickingDestination by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fill in the routes that serve the stop if we don't have them yet.
    LaunchedEffect(st.stop?.atco) {
        val s = st.stop ?: return@LaunchedEffect
        if (st.stopLines.isEmpty()) {
            val fromApi = runCatching { AppGraph.client.stop(s.atco)?.lines }.getOrNull().orEmpty()
            val fromBoard = runCatching { AppGraph.client.departures(s.atco).departures.map { it.line } }.getOrNull().orEmpty()
            st.stopLines = (fromApi + fromBoard).distinct().sortedWith(lineOrder)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LagHeader(if (st.isNew) "New journey" else "Edit journey", onBack = nav::pop)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    SectionLabel("Name")
                    OutlinedTextField(
                        st.label, { st.label = it }, Modifier.fillMaxWidth(), singleLine = true,
                        shape = RoundedCornerShape(10.dp), textStyle = MaterialTheme.typography.bodyLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = c.brand, unfocusedBorderColor = c.line,
                            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                        ),
                    )
                }

                Column {
                    SectionLabel("Catching the bus from")
                    PickerRow(
                        title = st.stop?.name ?: "Choose a stop",
                        detail = st.stop?.let { "Stop code ${it.atco}" } ?: "Nearby, by place name or by the code on the stop",
                        placeholder = st.stop == null,
                    ) {
                        nav.push(Screen.PickStop("Boarding stop") { picked ->
                            if (picked.atco != st.stop?.atco) {
                                st.stop = picked
                                st.stopLines = picked.lines.sortedWith(lineOrder)
                                st.lines = emptySet()
                                st.destination = null
                            }
                        })
                    }
                }

                if (st.stop != null) {
                    Column {
                        SectionLabel("Routes")
                        Panel {
                            Text(
                                if (st.lines.isEmpty()) "Watching every route from this stop." else "Only watching the routes you've picked.",
                                style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (st.stopLines.isEmpty()) {
                                Text("Loading routes…", style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoiceChip("All", st.lines.isEmpty()) { st.lines = emptySet() }
                                st.stopLines.forEach { l ->
                                    ChoiceChip(l, l in st.lines) { st.lines = if (l in st.lines) st.lines - l else st.lines + l }
                                }
                            }
                        }
                    }

                    Column {
                        SectionLabel("Getting off at (optional)")
                        PickerRow(
                            title = st.destination?.name ?: "Add where you get off",
                            detail = if (st.destination != null) "Used to estimate when you'll arrive" else "Lets Stats show your expected arrival time",
                            placeholder = st.destination == null,
                            trailing = if (st.destination != null) {
                                { TextAction("Remove", { st.destination = null }, color = c.inkMuted) }
                            } else null,
                        ) { pickingDestination = true }
                    }
                }

                Column {
                    SectionLabel("When you travel")
                    Panel(padding = PaddingValues(0.dp)) {
                        TimeRow("Earliest bus", st.windowStart) { pickingTime = true }
                        Divider(inset = 16)
                        TimeRow("Latest bus", st.windowEnd) { pickingTime = false }
                        Divider(inset = 16)
                        Column(Modifier.padding(16.dp)) {
                            DayToggles(st.days) { st.days = it }
                            Spacer(Modifier.height(10.dp))
                            Text(daysLabel(st.days), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                        }
                    }
                }

                Column {
                    SectionLabel("Tell me when")
                    Panel(padding = PaddingValues(0.dp)) {
                        val a = st.alerts
                        StepperRow("A bus is running late by", a.lateThresholdMin, 1..30, "min") { st.alerts = a.copy(lateThresholdMin = it) }
                        Divider(inset = 16)
                        SwitchRow("A bus is cancelled", null, a.cancellations) { st.alerts = a.copy(cancellations = it) }
                        Divider(inset = 16)
                        SwitchRow(
                            "An earlier bus is late enough to catch",
                            "e.g. the bus before yours is so late it'll now arrive in your window",
                            a.lateIntoWindow,
                        ) { st.alerts = a.copy(lateIntoWindow = it) }
                        Divider(inset = 16)
                        SwitchRow("A bus is running early", "Early buses leave without you", a.runningEarly) { st.alerts = a.copy(runningEarly = it) }
                        Divider(inset = 16)
                        StepperRow("Start checking before", a.leadMinutes, 0..90, "min", step = 5) { st.alerts = a.copy(leadMinutes = it) }
                    }
                }
                if (!st.isNew) {
                    SecondaryButton("Delete journey", {
                        scope.launch {
                            AppGraph.repo.deleteWatch(st.id)
                            nav.popTo { false }
                        }
                    }, Modifier.fillMaxWidth(), color = c.bad)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            Divider()
            error?.let {
                Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), color = c.bad, style = MaterialTheme.typography.bodySmall)
            }
            PrimaryButton(
                if (st.isNew) "Save journey" else "Save changes",
                {
                    val w = st.toWatch()
                    error = when {
                        w == null -> "Choose the stop you catch the bus from."
                        st.days.isEmpty() -> "Pick at least one day."
                        st.windowStart == st.windowEnd -> "The earliest and latest bus can't be the same time."
                        else -> null
                    }
                    if (w != null && error == null) scope.launch {
                        AppGraph.repo.upsertWatch(w)
                        nav.popTo { false }
                    }
                },
                Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }

    pickingTime?.let { start ->
        TimeDialog(
            title = if (start) "Earliest bus" else "Latest bus",
            minutes = if (start) st.windowStart else st.windowEnd,
            onDismiss = { pickingTime = null },
        ) {
            if (start) {
                val len = (st.windowEnd - st.windowStart).let { if (it <= 0) 30 else it }
                st.windowStart = it
                if (st.windowEnd <= it) st.windowEnd = (it + len).coerceAtMost(23 * 60 + 59)
            } else st.windowEnd = it
            pickingTime = null
        }
    }

    if (pickingDestination) {
        DestinationSheet(st, onDismiss = { pickingDestination = false }, onSearch = {
            pickingDestination = false
            nav.push(Screen.PickStop("Where you get off") { st.destination = it })
        }) {
            st.destination = it
            pickingDestination = false
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    detail: String,
    placeholder: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Lag.colors
    Panel(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (placeholder) c.brand else c.ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            }
            if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.inkFaint)
        }
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lag.colors
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.routeBadge else c.surface)
            .border(1.5.dp, if (selected) c.routeBadge else c.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) c.onRouteBadge else c.ink, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TNUM))
    }
}

@Composable
private fun TimeRow(label: String, minutes: Int, onClick: () -> Unit) {
    val c = Lag.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
        Text(
            Schedule.formatMinutes(minutes),
            Modifier.clip(RoundedCornerShape(6.dp)).background(c.brandTint).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TNUM), color = c.ink,
        )
    }
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lag.colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.brand, checkedThumbColor = c.onBrand, uncheckedBorderColor = c.line))
    }
}

@Composable
fun StepperRow(title: String, value: Int, range: IntRange, unit: String, step: Int = 1, onChange: (Int) -> Unit) {
    val c = Lag.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
        StepButton(Icons.Filled.Remove, "Less", value - step >= range.first) { onChange((value - step).coerceIn(range)) }
        Text(
            "$value $unit", Modifier.width(64.dp), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TNUM),
            color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton(Icons.Filled.Add, "More", value + step <= range.last) { onChange((value + step).coerceIn(range)) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Lag.colors
    Box(
        Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, if (enabled) c.brand else c.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) c.brand else c.inkFaint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun TimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val c = Lag.colors
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = c.surfaceAlt, selectorColor = c.brand,
                    timeSelectorSelectedContainerColor = c.brandTint, timeSelectorUnselectedContainerColor = c.surfaceAlt,
                    timeSelectorSelectedContentColor = c.ink, timeSelectorUnselectedContentColor = c.inkMuted,
                ),
            )
        },
        confirmButton = { TextAction("Set", { onPicked(state.hour * 60 + state.minute) }) },
        dismissButton = { TextAction("Cancel", onDismiss, color = c.inkMuted) },
    )
}

/** Pick a destination from the stops the chosen route actually calls at after the boarding stop. */
@Composable
private fun DestinationSheet(st: EditorState, onDismiss: () -> Unit, onSearch: () -> Unit, onPicked: (StopRef) -> Unit) {
    val c = Lag.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stops by remember { mutableStateOf<List<TripStop>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(st.stop?.atco, st.lines) {
        val s = st.stop ?: return@LaunchedEffect
        val result = runCatching {
            val board = AppGraph.client.departures(s.atco).departures
            val d = board.firstOrNull { st.lines.isEmpty() || it.line in st.lines } ?: return@runCatching emptyList()
            val trip = d.tripId ?: d.journeyId?.let { AppGraph.client.tripIdForJourney(it) } ?: return@runCatching emptyList()
            val all = AppGraph.client.tripStops(trip)
            val i = all.indexOfFirst { it.stop.atco == s.atco }
            if (i >= 0) all.drop(i + 1) else all
        }
        stops = result.getOrNull()
        failed = result.isFailure
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Where do you get off?", style = MaterialTheme.typography.titleLarge, color = c.ink)
            Text(
                "Stops on the next ${if (st.lines.isEmpty()) "bus" else st.lines.sortedWith(lineOrder).joinToString("/")} from ${st.stop?.name}",
                style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
            )
            Spacer(Modifier.height(8.dp))
            TextAction("Search for a different stop", onSearch)
        }
        when {
            stops == null && !failed -> Loading()
            stops.isNullOrEmpty() -> Message(
                if (failed) "Couldn't load the route" else "No buses to read the route from",
                "Search for the stop instead.",
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                val list = stops!!
                itemsIndexed(list, key = { _, it -> it.stop.atco + it.time }) { i, ts ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPicked(ts.stop) }
                            .drawBehind {
                                val x = 25.dp.toPx()
                                drawLine(
                                    c.brand, Offset(x, if (i == 0) size.height / 2 else 0f),
                                    Offset(x, if (i == list.lastIndex) size.height / 2 else size.height), 2.dp.toPx(),
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
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

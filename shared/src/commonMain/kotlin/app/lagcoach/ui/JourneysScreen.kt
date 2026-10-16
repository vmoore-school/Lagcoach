package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.WatchLive
import app.lagcoach.engine.Schedule
import app.lagcoach.model.UkZone
import app.lagcoach.model.Watch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun rememberNow(periodMs: Long = 20_000): Instant {
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(periodMs)
            value = Clock.System.now()
        }
    }
    return now
}

@Composable
fun JourneysScreen(nav: Nav) {
    val data by AppGraph.repo.data.collectAsState()
    val live by AppGraph.live.state.collectAsState()
    val now = rememberNow()
    val watches = data.watches
    val activeCount = watches.count { Schedule.activeWindow(it, now) != null }
    val health = rememberHealth()
    val problems = health.filter { !it.ok && it.required }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LagHeader(
                "Your journeys",
                subtitle = when {
                    watches.isEmpty() -> "Get told when your bus is late or cancelled"
                    activeCount > 0 -> "Watching $activeCount now"
                    else -> "${watches.size} saved · ${watches.count { it.enabled }} on"
                },
            )
        }
        data.activeTrip?.let { t -> item { ActiveTripCard(t) { nav.push(Screen.TrackTrip) } } }
        if (problems.isNotEmpty()) {
            item {
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Text("Alerts may not arrive", style = MaterialTheme.typography.titleSmall, color = Lag.colors.bad)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        problems.joinToString(". ") { it.problem } + ".",
                        style = MaterialTheme.typography.bodySmall, color = Lag.colors.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextAction(problems.first().action ?: "Fix", { AppGraph.platform.fixHealth(problems.first().id) }, inline = true)
                }
            }
        }
        if (watches.isEmpty()) {
            item {
                Message(
                    "No journeys yet",
                    "Add the stop you catch your bus from and the time you usually leave. " +
                        "Lagcoach checks live times in that window and lets you know if it's running late, early or cancelled.",
                    action = { PrimaryButton("Add a journey", { nav.push(Screen.EditWatch(EditorState.new())) }, icon = Icons.Filled.Add) },
                )
            }
        } else {
            item { Spacer(Modifier.height(16.dp)) }
            items(watches, key = { it.id }) { w ->
                WatchCard(w, live[w.id], now, onClick = { nav.push(Screen.WatchDetail(w.id)) })
                Spacer(Modifier.height(12.dp))
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SecondaryButton("Add another journey", { nav.push(Screen.EditWatch(EditorState.new())) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun WatchCard(w: Watch, live: WatchLive?, now: Instant, onClick: () -> Unit) {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    val active = Schedule.activeWindow(w, now)
    val skippable = Schedule.skippableWindow(w, now)
    val skipped = skippable != null && Schedule.isSkipped(w, skippable)
    Panel(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), padding = PaddingValues(0.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.label, style = MaterialTheme.typography.titleMedium, color = if (w.enabled) c.ink else c.inkMuted)
                    Text(
                        w.stop.name + (w.destination?.let { "  →  ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = c.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = w.enabled,
                    onCheckedChange = { on -> scope.launch { AppGraph.repo.upsertWatch(w.copy(enabled = on)) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.brand, checkedThumbColor = c.onBrand, uncheckedBorderColor = c.line),
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (w.lines.isEmpty()) StatusTag("All routes", Tone.Neutral)
                w.lines.forEach { RouteBadge(it, muted = !w.enabled) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "${windowLabel(w.windowStart, w.windowEnd)} · ${daysLabel(w.days)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TNUM), color = c.ink,
            )
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, text) = when {
                !w.enabled -> c.inkFaint to "Off"
                skipped -> c.inkFaint to "Not taking today"
                active != null && live != null -> c.good to (live.error?.let { "Watching · $it" } ?: live.summary)
                active != null -> c.good to "Watching now"
                else -> c.inkFaint to (Schedule.nextMonitorStart(w, now)?.let { "Next check ${relativeDay(it, now)}" } ?: "Pick at least one day")
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM),
                color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (skippable != null) {
                TextAction(if (skipped) "Undo" else "Not taking", { scope.launch { app.lagcoach.setNotTaking(w, skippable, !skipped) } })
            }
        }
    }
}

fun relativeDay(t: Instant, now: Instant): String {
    val d = t.toLocalDateTime(UkZone)
    val today = now.toLocalDateTime(UkZone).date
    val day = when (d.date) {
        today -> "today"
        today.plus(DatePeriod(days = 1)) -> "tomorrow"
        else -> d.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    }
    return "$day ${Schedule.pad(d.hour)}:${Schedule.pad(d.minute)}"
}

@Composable
fun rememberHealth(): List<app.lagcoach.HealthItem> {
    val tick by AppGraph.platform.healthTick.collectAsState()
    val state = androidx.compose.runtime.remember { mutableStateOf(AppGraph.platform.healthChecks()) }
    LaunchedEffect(tick) { state.value = AppGraph.platform.healthChecks() }
    return state.value
}

package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.engine.Schedule
import app.lagcoach.model.Departure
import app.lagcoach.model.StopRef
import app.lagcoach.model.UkZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun LiveScreen(nav: Nav) {
    val data by AppGraph.repo.data.collectAsState()
    val c = Lag.colors
    val saved = data.watches.flatMap { listOfNotNull(it.stop, it.destination) }.distinctBy { it.atco }
    Column(Modifier.fillMaxSize()) {
        LagHeader("Live times", subtitle = "Departures from any stop", below = {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
                    .clickable { nav.push(Screen.PickStop("Find a stop") { nav.push(Screen.Board(it)) }) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.inkMuted)
                Spacer(Modifier.padding(start = 10.dp))
                Text("Find a stop", style = MaterialTheme.typography.bodyLarge, color = c.inkMuted)
            }
        })
        if (saved.isEmpty()) {
            Message("Your stops will show here", "Stops from your saved journeys appear here for quick access.")
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)) {
                item { SectionLabel("Your stops", Modifier.padding(horizontal = 16.dp)) }
                item {
                    Panel(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(0.dp)) {
                        saved.forEachIndexed { i, s ->
                            if (i > 0) Divider(inset = 16)
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.push(Screen.Board(s)) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keeps a stop's board fresh while on screen. */
class BoardLoader {
    var departures by mutableStateOf<List<Departure>?>(null)
    var error by mutableStateOf<String?>(null)
    var updated by mutableStateOf<Instant?>(null)
    var refreshing by mutableStateOf(false)
}

@Composable
fun rememberBoard(atco: String, refreshMs: Long = 30_000): Pair<BoardLoader, () -> Unit> {
    val loader = remember(atco) { BoardLoader() }
    var trigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(atco, trigger) {
        while (true) {
            loader.refreshing = true
            val r = runCatching { AppGraph.client.departures(atco) }
            r.onSuccess { loader.departures = it.departures; loader.error = null; loader.updated = Clock.System.now() }
            r.onFailure { loader.error = it.message ?: "Couldn't load departures" }
            loader.refreshing = false
            delay(refreshMs)
        }
    }
    return loader to { trigger++ }
}

@Composable
fun BoardScreen(nav: Nav, stop: StopRef, initialLines: List<String>) {
    val c = Lag.colors
    val (board, refresh) = rememberBoard(stop.atco)
    var filter by remember { mutableStateOf(initialLines.toSet()) }
    val all = board.departures.orEmpty()
    val lines = all.map { it.line }.distinct().sortedWith(lineOrder)
    val shown = all.filter { filter.isEmpty() || it.line in filter }

    Column(Modifier.fillMaxSize()) {
        LagHeader(
            stop.name,
            subtitle = board.updated?.let { "Updated ${Schedule.hhmm(it)} · from bustimes.org" } ?: "Loading…",
            onBack = nav::pop,
            hasActions = true,
            actions = {
                IconButton({
                    val st = EditorState.new().apply { this.stop = stop; this.lines = filter; label = "Journey from ${stop.name.substringBefore(" (")}" }
                    nav.push(Screen.EditWatch(st))
                }) { Icon(Icons.Outlined.Edit, "Make a journey from this stop", tint = c.onHeader) }
            },
        )
        if (lines.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChoiceChip("All", filter.isEmpty()) { filter = emptySet() }
                lines.forEach { l -> ChoiceChip(l, l in filter) { filter = if (l in filter) filter - l else filter + l } }
            }
        }
        PullToRefreshBox(isRefreshing = board.refreshing && board.departures != null, onRefresh = refresh, modifier = Modifier.weight(1f)) {
            when {
                board.departures == null && board.error == null -> Loading()
                board.departures == null -> Message("Couldn't load departures", board.error, action = { SecondaryButton("Try again", refresh) })
                shown.isEmpty() -> Message("No departures listed", "bustimes.org has nothing for this stop in the next few hours.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    board.error?.let { item { Text("Showing older times: $it", Modifier.padding(16.dp), color = c.warn, style = MaterialTheme.typography.bodySmall) } }
                    items(shown, key = { it.key }) { d ->
                        DepartureRow(d)
                        Divider(inset = 72)
                    }
                    item {
                        Text(
                            "Live times come from operators' vehicle tracking via bustimes.org. \"Timetable\" means no live tracking yet.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WatchDetailScreen(nav: Nav, watchId: String) {
    val c = Lag.colors
    val data by AppGraph.repo.data.collectAsState()
    val obs by AppGraph.repo.observations.collectAsState()
    val w = data.watches.firstOrNull { it.id == watchId }
    if (w == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val now = rememberNow()
    val (board, refresh) = rememberBoard(w.stop.atco)
    val active = Schedule.activeWindow(w, now)
    val upcoming = board.departures.orEmpty().filter { w.matchesLine(it.line) }.take(8)
    val mine = obs.filter { it.watchId == w.id }
    val stats = remember(mine, now) { app.lagcoach.stats.StatsCalc.summarise(mine, null, now) }

    Column(Modifier.fillMaxSize()) {
        LagHeader(
            w.label,
            subtitle = "${w.stop.name}${w.destination?.let { " → ${it.name}" } ?: ""}",
            onBack = nav::pop,
            hasActions = true,
            actions = {
                IconButton({ nav.push(Screen.EditWatch(EditorState.of(w))) }) { Icon(Icons.Outlined.Edit, "Edit", tint = c.onHeader) }
            },
        )
        PullToRefreshBox(isRefreshing = board.refreshing && board.departures != null, onRefresh = refresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Panel {
                        Text(
                            when {
                                !w.enabled -> "Alerts are off for this journey"
                                active != null -> "Watching now until ${Schedule.hhmm(active.end)}"
                                else -> Schedule.nextMonitorStart(w, now)?.let { "Next check ${relativeDay(it, now)}" } ?: "No days selected"
                            },
                            style = MaterialTheme.typography.titleSmall, color = c.ink,
                        )
                        Text(
                            "${windowLabel(w.windowStart, w.windowEnd)} · ${daysLabel(w.days)} · " +
                                (if (w.lines.isEmpty()) "all routes" else "routes ${w.lines.joinToString(", ")}"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TNUM), color = c.inkMuted,
                        )
                    }
                }
                item {
                    SectionLabel("Next from this stop")
                    Panel(padding = PaddingValues(0.dp)) {
                        when {
                            board.departures == null && board.error == null -> Loading()
                            board.departures == null -> Message("Couldn't load departures", board.error)
                            upcoming.isEmpty() -> Message("Nothing due on your routes")
                            else -> upcoming.forEachIndexed { i, d ->
                                if (i > 0) Divider(inset = 72)
                                DepartureRow(d, dim = Schedule.windowOn(w, active?.date ?: d.scheduled.ukDate()).let { d.scheduled !in it && d.effective !in it })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextAction("Full departure board", { nav.push(Screen.Board(w.stop, w.lines)) }, inline = true)
                }
                item {
                    SectionLabel("Track record")
                    if (stats.total == 0) {
                        Panel { Text("Nothing recorded yet. Lagcoach logs each bus it watches in your window.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted) }
                    } else {
                        StatTiles(stats)
                    }
                }
            }
        }
    }
}

private fun Instant.ukDate() = toLocalDateTime(UkZone).date

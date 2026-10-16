package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DepartureBoard
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.model.StopRef

enum class Tab(val label: String, val icon: ImageVector) {
    Journeys("Journeys", Icons.Outlined.NotificationsActive),
    Live("Live times", Icons.Outlined.DepartureBoard),
    Stats("Stats", Icons.Outlined.BarChart),
    Settings("Settings", Icons.Outlined.Settings),
}

sealed interface Screen {
    data class EditWatch(val state: EditorState) : Screen
    data class PickStop(val title: String, val onPicked: (StopRef) -> Unit) : Screen
    data class Board(val stop: StopRef, val lines: List<String> = emptyList()) : Screen
    data class WatchDetail(val watchId: String) : Screen
}

/** Minimal back stack: tabs at the root, full-screen pages pushed on top. */
class Nav {
    var tab by mutableStateOf(Tab.Journeys)
    val stack = mutableStateListOf<Screen>()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun popTo(predicate: (Screen) -> Boolean) {
        while (stack.isNotEmpty() && !predicate(stack.last())) pop()
    }
}

@Composable
fun App() {
    val data by AppGraph.repo.data.collectAsState()
    LagTheme(dynamic = data.settings.dynamicColor && AppGraph.platform.supportsDynamicColor) {
        val nav = remember { Nav() }
        val c = Lag.colors
        PlatformBackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.Journeys) {
            if (nav.stack.isNotEmpty()) nav.pop() else nav.tab = Tab.Journeys
        }
        Box(Modifier.fillMaxSize().background(c.page)) {
            if (!data.settings.onboarded) {
                OnboardingScreen()
                return@Box
            }
            when (val top = nav.stack.lastOrNull()) {
                null -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (nav.tab) {
                            Tab.Journeys -> JourneysScreen(nav)
                            Tab.Live -> LiveScreen(nav)
                            Tab.Stats -> StatsScreen()
                            Tab.Settings -> SettingsScreen()
                        }
                    }
                    BottomBar(nav)
                }
                is Screen.EditWatch -> EditWatchScreen(nav, top.state)
                is Screen.PickStop -> StopPickerScreen(top.title, onBack = nav::pop) { nav.pop(); top.onPicked(it) }
                is Screen.Board -> BoardScreen(nav, top.stop, top.lines)
                is Screen.WatchDetail -> WatchDetailScreen(nav, top.watchId)
            }
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.header))
        }
    }
}

@Composable
private fun BottomBar(nav: Nav) {
    val c = Lag.colors
    Column {
        Divider()
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = nav.tab == t,
                    onClick = { nav.tab = t },
                    icon = { Icon(t.icon, null) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.brand,
                        selectedTextColor = c.brand,
                        indicatorColor = c.brandTint,
                        unselectedIconColor = c.inkMuted,
                        unselectedTextColor = c.inkMuted,
                    ),
                )
            }
        }
    }
}

@Composable
fun BottomSpacer() = Box(Modifier.fillMaxWidth().height(24.dp))

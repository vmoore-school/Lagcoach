package app.lagcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lagcoach.AppGraph
import app.lagcoach.HealthItem
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    val data by AppGraph.repo.data.collectAsState()
    val health = rememberHealth()
    var confirmClear by remember { mutableStateOf(false) }
    val polls = listOf(30, 60, 120)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LagHeader("Settings")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column {
                SectionLabel("Making sure alerts arrive")
                HealthPanel(health)
                Spacer(Modifier.height(8.dp))
                TextAction("Send a test alert", { AppGraph.platform.testNotification() }, inline = true)
            }
            Column {
                SectionLabel("Checking")
                Panel {
                    Text("How often to check during a journey window", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Text(
                        "More often catches changes sooner but uses a little more battery and data.",
                        style = MaterialTheme.typography.bodySmall, color = c.inkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        polls.forEach { p ->
                            ChoiceChip(if (p < 60) "${p}s" else "${p / 60} min", data.settings.pollSeconds == p) {
                                scope.launch { AppGraph.repo.updateSettings { it.copy(pollSeconds = p) } }
                            }
                        }
                    }
                }
            }
            Column {
                SectionLabel("Data")
                Panel(padding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { confirmClear = true }.padding(16.dp)) {
                        Text("Clear statistics", style = MaterialTheme.typography.bodyLarge, color = c.bad)
                    }
                }
            }
            Column {
                SectionLabel("About")
                Panel(padding = PaddingValues(0.dp)) {
                    AboutRow("Version", AppGraph.platform.appVersion)
                    Divider(inset = 16)
                    AboutRow("Bus times", "bustimes.org") { AppGraph.platform.openUrl("https://bustimes.org") }
                    Divider(inset = 16)
                    AboutRow("Source code", "GitHub") { AppGraph.platform.openUrl("https://github.com/vmoore-school/Lagcoach") }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Lagcoach isn't made by or affiliated with Stagecoach or bustimes.org. Live times are from bus operators' open data " +
                        "via bustimes.org, and can be wrong or missing. Everything stays on this phone; the only requests made are to bustimes.org.",
                    style = MaterialTheme.typography.bodySmall, color = c.inkFaint,
                )
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.surface,
            title = { Text("Clear statistics?") },
            text = { Text("This deletes every logged bus. Your journeys stay.") },
            confirmButton = { TextAction("Clear", { scope.launch { AppGraph.repo.clearObservations() }; confirmClear = false }, color = c.bad) },
            dismissButton = { TextAction("Cancel", { confirmClear = false }, color = c.inkMuted) },
        )
    }
}

@Composable
fun HealthPanel(items: List<HealthItem>) {
    val c = Lag.colors
    Panel(padding = PaddingValues(0.dp)) {
        items.forEachIndexed { i, h ->
            if (i > 0) Divider(inset = 52)
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                val (fg, bg) = toneColors(if (h.ok) Tone.Good else if (h.required) Tone.Bad else Tone.Neutral)
                Box(Modifier.size(24.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Icon(if (h.ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, if (h.ok) "Done" else "Needs attention", tint = fg, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text(h.detail, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                    if (!h.ok && h.action != null) {
                        Spacer(Modifier.height(4.dp))
                        TextAction(h.action, { AppGraph.platform.fixHealth(h.id) }, inline = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val c = Lag.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.ink)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) c.brand else c.inkMuted)
    }
}

@Composable
fun OnboardingScreen() {
    val c = Lag.colors
    val scope = rememberCoroutineScope()
    val health = rememberHealth()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            LagHeader("Lagcoach", subtitle = "Know before you go")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Save the stop and time you usually catch your bus. During that window Lagcoach checks live times every minute " +
                        "and alerts you if your bus is late, early or cancelled, or if an earlier bus is running so late you can catch it instead.",
                    style = MaterialTheme.typography.bodyLarge, color = c.ink,
                )
                Text(
                    "There's no account or server: your phone does the checking itself, so it works without Google Play services. " +
                        "That needs a few permissions so Android doesn't put it to sleep.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
                HealthPanel(health)
            }
        }
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            Divider()
            PrimaryButton(
                "Get started",
                { scope.launch { AppGraph.repo.updateSettings { it.copy(onboarded = true) } } },
                Modifier.fillMaxWidth().padding(16.dp).windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}

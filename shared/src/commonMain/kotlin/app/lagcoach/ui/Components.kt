package app.lagcoach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import app.lagcoach.engine.Schedule
import app.lagcoach.model.Departure
import app.lagcoach.model.roundHalfAway

/** Navy title bar, the way Stagecoach's app frames each screen. */
@Composable
fun LagHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    hasActions: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Lag.colors
    Column(
        Modifier.fillMaxWidth().background(c.header).windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (onBack != null || hasActions) 56.dp else 28.dp)
                .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onHeader)
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = c.onHeader, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.onHeaderMuted)
            }
            below()
        }
    }
}

/** Route number as a solid block, like the number on a bus blind. */
@Composable
fun RouteBadge(line: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    val c = Lag.colors
    Box(
        modifier.defaultMinSize(minWidth = 44.dp).height(30.dp).clip(RoundedCornerShape(6.dp))
            .background(if (muted) c.surfaceAlt else c.routeBadge).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            line,
            color = if (muted) c.inkMuted else c.onRouteBadge,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFeatureSettings = TNUM),
            maxLines = 1,
        )
    }
}

enum class Tone { Good, Warn, Bad, Neutral }

fun Departure.tone(lateThreshold: Int = 2): Tone {
    if (cancelled) return Tone.Bad
    val delay = delaySeconds?.let { roundHalfAway(it / 60.0) } ?: return Tone.Neutral
    return when {
        delay >= lateThreshold + 3 -> Tone.Bad
        delay >= lateThreshold -> Tone.Warn
        delay <= -2 -> Tone.Warn
        else -> Tone.Good
    }
}

@Composable
fun toneColors(t: Tone): Pair<Color, Color> {
    val c = Lag.colors
    return when (t) {
        Tone.Good -> c.good to c.goodTint
        Tone.Warn -> c.warn to c.warnTint
        Tone.Bad -> c.bad to c.badTint
        Tone.Neutral -> c.inkMuted to c.surfaceAlt
    }
}

/** Small text tag. Colour is never the only signal: the words say the state. */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(tone)
    Text(
        text,
        modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        color = fg,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = Lag.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold),
        color = Lag.colors.inkMuted,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lag.colors.brand, contentColor = Lag.colors.onBrand),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Lag.colors.brand) {
    OutlinedButton(
        onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.5.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = Lag.colors.brand, inline: Boolean = false) {
    Text(
        text,
        Modifier.then(if (inline) Modifier.offset(x = (-8).dp) else Modifier)
            .clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Lag.colors.brand, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Lag.colors.ink)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Lag.colors.inkMuted)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, inset: Int = 0) {
    Box(modifier.padding(start = inset.dp).fillMaxWidth().height(1.dp).background(Lag.colors.line))
}

/** One row of a departure board: badge, destination, time and status. */
@Composable
fun DepartureRow(d: Departure, onClick: (() -> Unit)? = null, dim: Boolean = false) {
    val c = Lag.colors
    val tone = d.tone()
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteBadge(d.line, muted = dim)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.destination, style = MaterialTheme.typography.titleSmall, color = if (dim) c.inkMuted else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                d.vehicle?.let { append(it.substringAfter(" - ", it)) }
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.inkFaint, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val time = if (d.cancelled) Schedule.hhmm(d.scheduled) else Schedule.hhmm(d.effective)
            Text(
                time,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TNUM),
                color = if (d.cancelled) c.inkFaint else c.ink,
                textDecoration = if (d.cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            val label = app.lagcoach.engine.WatchEngine.status(d)
            if (d.expected != null && label != "On time" || d.cancelled) {
                StatusTag(label, tone)
            } else if (d.expected != null) {
                Text("On time", style = MaterialTheme.typography.labelSmall, color = c.good)
            } else {
                Text("Timetable", style = MaterialTheme.typography.labelSmall, color = c.inkFaint)
            }
        }
    }
}

@Composable
fun DayToggles(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val c = Lag.colors
    val names = listOf("M", "T", "W", "T", "F", "S", "S")
    val full = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        names.forEachIndexed { i, n ->
            val iso = i + 1
            val on = iso in days
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (on) c.brand else c.surface)
                    .border(1.5.dp, if (on) c.brand else c.line, CircleShape)
                    .clickable { onChange(if (on) days - iso else days + iso) }
                    .semantics { contentDescription = full[i]; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(n, color = if (on) c.onBrand else c.inkMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

fun daysLabel(days: Set<Int>): String {
    val sorted = days.sorted()
    val short = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return when {
        sorted == (1..7).toList() -> "Every day"
        sorted == (1..5).toList() -> "Weekdays"
        sorted == listOf(6, 7) -> "Weekends"
        sorted.isEmpty() -> "No days"
        sorted.zipWithNext().all { (a, b) -> b == a + 1 } && sorted.size > 2 -> "${short[sorted.first() - 1]}–${short[sorted.last() - 1]}"
        else -> sorted.joinToString(", ") { short[it - 1] }
    }
}

fun windowLabel(start: Int, end: Int) = "${Schedule.formatMinutes(start)}–${Schedule.formatMinutes(end)}"

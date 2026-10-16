package app.lagcoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lagcoach.resources.Res
import app.lagcoach.resources.figtree_bold
import app.lagcoach.resources.figtree_medium
import app.lagcoach.resources.figtree_regular
import app.lagcoach.resources.figtree_semibold
import org.jetbrains.compose.resources.Font

/**
 * Palette taken from Stagecoach's own web styles: their blue (#0077C8), deep navy
 * (#051C2C) for headers and ink, green for "on time", red for problems, on a very
 * light blue-grey page. Dark mode keeps the navy family rather than going neutral grey.
 */
@Immutable
data class LagColors(
    val page: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val header: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val line: Color,
    val brand: Color,
    val onBrand: Color,
    val brandTint: Color,
    val good: Color,
    val goodTint: Color,
    val warn: Color,
    val warnTint: Color,
    val bad: Color,
    val badTint: Color,
    val routeBadge: Color,
    val onRouteBadge: Color,
)

val LightLag = LagColors(
    page = Color(0xFFF6F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEEF3F8),
    header = Color(0xFF051C2C),
    onHeader = Color(0xFFFFFFFF),
    onHeaderMuted = Color(0xFFA9BAC7),
    ink = Color(0xFF051C2C),
    inkMuted = Color(0xFF55626D),
    inkFaint = Color(0xFF8A96A0),
    line = Color(0xFFE2E7EC),
    brand = Color(0xFF0077C8),
    onBrand = Color(0xFFFFFFFF),
    brandTint = Color(0xFFE3F0FA),
    good = Color(0xFF00775B),
    goodTint = Color(0xFFDDF1EA),
    warn = Color(0xFF9A5B00),
    warnTint = Color(0xFFFFF0D6),
    bad = Color(0xFFC8102E),
    badTint = Color(0xFFFCE4E6),
    routeBadge = Color(0xFF051C2C),
    onRouteBadge = Color(0xFFFFFFFF),
)

val DarkLag = LagColors(
    page = Color(0xFF07131D),
    surface = Color(0xFF0E1F2C),
    surfaceAlt = Color(0xFF15293A),
    header = Color(0xFF0E1F2C),
    onHeader = Color(0xFFF1F5F8),
    onHeaderMuted = Color(0xFF8FA3B3),
    ink = Color(0xFFE8EEF3),
    inkMuted = Color(0xFFA3B2BE),
    inkFaint = Color(0xFF6E8191),
    line = Color(0xFF213646),
    brand = Color(0xFF4FA9E8),
    onBrand = Color(0xFF04131E),
    brandTint = Color(0xFF12324A),
    good = Color(0xFF4CC9A0),
    goodTint = Color(0xFF113A31),
    warn = Color(0xFFF2B04A),
    warnTint = Color(0xFF3A2C12),
    bad = Color(0xFFFF7A85),
    badTint = Color(0xFF431B22),
    routeBadge = Color(0xFFE8EEF3),
    onRouteBadge = Color(0xFF051C2C),
)

val LocalLag = staticCompositionLocalOf { LightLag }

object Lag {
    val colors: LagColors @Composable get() = LocalLag.current
}

@Composable
fun figtree(): FontFamily = FontFamily(
    Font(Res.font.figtree_regular, FontWeight.Normal),
    Font(Res.font.figtree_medium, FontWeight.Medium),
    Font(Res.font.figtree_semibold, FontWeight.SemiBold),
    Font(Res.font.figtree_bold, FontWeight.Bold),
)

/** Tabular figures so times line up in columns. */
const val TNUM = "tnum"

@Composable
fun LagTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val lag = if (dark) DarkLag else LightLag
    val ff = figtree()
    val scheme: ColorScheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = lag.brand,
        onPrimary = lag.onBrand,
        primaryContainer = lag.brandTint,
        onPrimaryContainer = lag.ink,
        secondary = lag.header,
        onSecondary = lag.onHeader,
        background = lag.page,
        onBackground = lag.ink,
        surface = lag.surface,
        onSurface = lag.ink,
        surfaceVariant = lag.surfaceAlt,
        onSurfaceVariant = lag.inkMuted,
        surfaceContainer = lag.surface,
        surfaceContainerHigh = lag.surface,
        surfaceContainerHighest = lag.surfaceAlt,
        surfaceContainerLow = lag.surface,
        outline = lag.line,
        outlineVariant = lag.line,
        error = lag.bad,
    )
    fun s(size: Int, weight: FontWeight, line: Int, track: Double = 0.0) =
        TextStyle(fontFamily = ff, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = track.sp)
    val type = Typography(
        displaySmall = s(32, FontWeight.Bold, 38, -0.5),
        headlineMedium = s(26, FontWeight.Bold, 32, -0.3),
        headlineSmall = s(22, FontWeight.Bold, 28, -0.2),
        titleLarge = s(19, FontWeight.SemiBold, 24),
        titleMedium = s(16, FontWeight.SemiBold, 22),
        titleSmall = s(14, FontWeight.SemiBold, 20),
        bodyLarge = s(16, FontWeight.Normal, 23),
        bodyMedium = s(14, FontWeight.Normal, 20),
        bodySmall = s(13, FontWeight.Normal, 18),
        labelLarge = s(15, FontWeight.SemiBold, 20),
        labelMedium = s(13, FontWeight.Medium, 16),
        labelSmall = s(12, FontWeight.Medium, 16, 0.2),
    )
    CompositionLocalProvider(LocalLag provides lag) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

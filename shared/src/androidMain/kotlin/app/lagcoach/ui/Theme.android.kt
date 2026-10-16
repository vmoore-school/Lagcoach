package app.lagcoach.ui

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun platformDynamicScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < 31) return null
    val ctx = LocalContext.current
    return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
}

@Composable
actual fun SystemBarIcons(lightStatusIcons: Boolean, lightNavIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightStatusIcons
            isAppearanceLightNavigationBars = !lightNavIcons
        }
    }
}

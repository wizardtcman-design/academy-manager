package com.example.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

@Composable
fun MyApplicationTheme(
    themeName: String = "BLUE", // BLUE, GREEN, PURPLE, ORANGE, NAVY, TEAL, ROSE, AMBER, SLATE, FOREST
    themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode.uppercase()) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemDark
    }

    val palette = AllColorSchemes[themeName.uppercase()] ?: AllColorSchemes.getValue("BLUE")
    val target: ColorScheme = if (isDark) palette.second else palette.first

    // Smoothly cross-fade colors when the user switches theme or day/night mode.
    val animationSpec = tween<Color>(durationMillis = 420)
    val colorScheme = target.copy(
        primary = animateColorAsState(target.primary, animationSpec, label = "primary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, animationSpec, label = "primaryContainer").value,
        secondary = animateColorAsState(target.secondary, animationSpec, label = "secondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, animationSpec, label = "secondaryContainer").value,
        tertiary = animateColorAsState(target.tertiary, animationSpec, label = "tertiary").value,
        background = animateColorAsState(target.background, animationSpec, label = "background").value,
        surface = animateColorAsState(target.surface, animationSpec, label = "surface").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, animationSpec, label = "surfaceContainer").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, animationSpec, label = "surfaceVariant").value,
        onBackground = animateColorAsState(target.onBackground, animationSpec, label = "onBackground").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
        outlineVariant = animateColorAsState(target.outlineVariant, animationSpec, label = "outlineVariant").value
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
        }
    }

    // Persian / Right-to-Left (RTL) Layout Direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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

    val colorScheme: ColorScheme = when (themeName.uppercase()) {
        "GREEN" -> if (isDark) GreenDarkColorScheme else GreenLightColorScheme
        "PURPLE" -> if (isDark) PurpleDarkColorScheme else PurpleLightColorScheme
        "ORANGE" -> if (isDark) OrangeDarkColorScheme else OrangeLightColorScheme
        "NAVY" -> if (isDark) NavyDarkColorScheme else NavyLightColorScheme
        "TEAL" -> if (isDark) TealDarkColorScheme else TealLightColorScheme
        "ROSE" -> if (isDark) RoseDarkColorScheme else RoseLightColorScheme
        "AMBER" -> if (isDark) AmberDarkColorScheme else AmberLightColorScheme
        "SLATE" -> if (isDark) SlateDarkColorScheme else SlateLightColorScheme
        "FOREST" -> if (isDark) ForestDarkColorScheme else ForestLightColorScheme
        else -> if (isDark) BlueDarkColorScheme else BlueLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
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

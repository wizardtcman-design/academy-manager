package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Status Accents (shared by every palette)
val StatusSuccess = Color(0xFF34D399)
val StatusWarning = Color(0xFFFBBF24)
val StatusError = Color(0xFFF87171)
val StatusInfo = Color(0xFF60A5FA)

// Brand accents & backward compatibility
val IndigoPrimary = Color(0xFF6C8CFF)
val TealSecondary = Color(0xFF2DD4BF)
val AmberTertiary = Color(0xFFFBBF24)

/**
 * Builds a complete Material 3 scheme from a small set of brand colors, so every
 * palette (light and dark) has correct surfaces, containers and outlines and no
 * screen ever has to hardcode a color.
 */
private fun palette(
    primary: Color,
    primaryContainer: Color,
    onPrimary: Color,
    onPrimaryContainer: Color,
    accent: Color,
    accentContainer: Color,
    onAccent: Color,
    onAccentContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
    onTertiary: Color,
    onTertiaryContainer: Color,
    background: Color,
    surface: Color,
    surfaceHigh: Color,
    surfaceVariantIn: Color,
    onBackgroundIn: Color,
    onSurfaceVariantIn: Color,
    outlineIn: Color,
    outlineVariantIn: Color,
    error: Color,
    errorContainer: Color,
    onErrorContainer: Color,
    isDark: Boolean
): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = if (isDark) Color(0xFF3B4BA8) else Color(0xFFAFC0FF),
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentContainer,
        onSecondaryContainer = onAccentContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackgroundIn,
        surface = surface,
        onSurface = onBackgroundIn,
        surfaceVariant = surfaceVariantIn,
        onSurfaceVariant = onSurfaceVariantIn,
        surfaceContainerLowest = if (isDark) surfaceHigh else Color.White,
        surfaceContainerLow = surface,
        surfaceContainer = surfaceHigh,
        surfaceContainerHigh = if (isDark) surfaceVariantIn else surfaceHigh,
        surfaceContainerHighest = if (isDark) surfaceVariantIn else surfaceVariantIn,
        inverseSurface = if (isDark) Color(0xFFF3F5FB) else Color(0xFF1B2033),
        inverseOnSurface = if (isDark) Color(0xFF141828) else Color(0xFFF3F5FB),
        outline = outlineIn,
        outlineVariant = outlineVariantIn,
        error = error,
        onError = if (isDark) Color(0xFF2A0B0F) else Color.White,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer
    )
}

// ==========================================
// 1. BLUE THEME (آبی حرفه‌ای)
// ==========================================
val BlueLightColorScheme = palette(
    primary = Color(0xFF2F5BEA), primaryContainer = Color(0xFFDDE5FF), onPrimary = Color.White, onPrimaryContainer = Color(0xFF0B2A9B),
    accent = Color(0xFF0E8FA8), accentContainer = Color(0xFFD3F2F7), onAccent = Color.White, onAccentContainer = Color(0xFF05414E),
    tertiary = Color(0xFF5B5BE8), tertiaryContainer = Color(0xFFE4E2FF), onTertiary = Color.White, onTertiaryContainer = Color(0xFF23207E),
    background = Color(0xFFF5F7FC), surface = Color.White, surfaceHigh = Color(0xFFEDF1F9), surfaceVariantIn = Color(0xFFE8EDF7),
    onBackgroundIn = Color(0xFF11162B), onSurfaceVariantIn = Color(0xFF5A6483), outlineIn = Color(0xFFB9C2D8), outlineVariantIn = Color(0xFFDDE3EF),
    error = Color(0xFFD6303B), errorContainer = Color(0xFFFFDAD9), onErrorContainer = Color(0xFF7A0C14), isDark = false
)

val BlueDarkColorScheme = palette(
    primary = Color(0xFF8AA4FF), primaryContainer = Color(0xFF27357A), onPrimary = Color(0xFF0B1638), onPrimaryContainer = Color(0xFFDDE5FF),
    accent = Color(0xFF3ED0E0), accentContainer = Color(0xFF0C4A57), onAccent = Color(0xFF022C34), onAccentContainer = Color(0xFFD3F2F7),
    tertiary = Color(0xFFB7B5FF), tertiaryContainer = Color(0xFF34328E), onTertiary = Color(0xFF15134D), onTertiaryContainer = Color(0xFFE4E2FF),
    background = Color(0xFF0A0D16), surface = Color(0xFF11162A), surfaceHigh = Color(0xFF161C33), surfaceVariantIn = Color(0xFF1C2340),
    onBackgroundIn = Color(0xFFF1F4FE), onSurfaceVariantIn = Color(0xFF98A3C4), outlineIn = Color(0xFF38415F), outlineVariantIn = Color(0xFF232A45),
    error = Color(0xFFFF8A8A), errorContainer = Color(0xFF5A1319), onErrorContainer = Color(0xFFFFDAD9), isDark = true
)

// ==========================================
// 2. GREEN THEME (سبز زمردی)
// ==========================================
val GreenLightColorScheme = palette(
    primary = Color(0xFF0B8F6A), primaryContainer = Color(0xFFC9F7E4), onPrimary = Color.White, onPrimaryContainer = Color(0xFF003626),
    accent = Color(0xFF0F8B9B), accentContainer = Color(0xFFCDEFF3), onAccent = Color.White, onAccentContainer = Color(0xFF003A42),
    tertiary = Color(0xFF3E8A2E), tertiaryContainer = Color(0xFFDCF3CE), onTertiary = Color.White, onTertiaryContainer = Color(0xFF123406),
    background = Color(0xFFF2FAF5), surface = Color.White, surfaceHigh = Color(0xFFE8F6ED), surfaceVariantIn = Color(0xFFE1F2E8),
    onBackgroundIn = Color(0xFF0B1A13), onSurfaceVariantIn = Color(0xFF4C665B), outlineIn = Color(0xFFA9C6B8), outlineVariantIn = Color(0xFFD3E6DC),
    error = Color(0xFFCF3434), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF7A0C0C), isDark = false
)

val GreenDarkColorScheme = palette(
    primary = Color(0xFF57E0B0), primaryContainer = Color(0xFF0B4B39), onPrimary = Color(0xFF00311F), onPrimaryContainer = Color(0xFFC9F7E4),
    accent = Color(0xFF43D3E0), accentContainer = Color(0xFF074650), onAccent = Color(0xFF00272E), onAccentContainer = Color(0xFFCDEFF3),
    tertiary = Color(0xFFA8DE8C), tertiaryContainer = Color(0xFF2A5C1C), onTertiary = Color(0xFF102A06), onTertiaryContainer = Color(0xFFDCF3CE),
    background = Color(0xFF06120E), surface = Color(0xFF0C1E18), surfaceHigh = Color(0xFF112620), surfaceVariantIn = Color(0xFF16312A),
    onBackgroundIn = Color(0xFFEAF7F0), onSurfaceVariantIn = Color(0xFF8CB3A2), outlineIn = Color(0xFF2D4E43), outlineVariantIn = Color(0xFF1B342C),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1512), onErrorContainer = Color(0xFFFFDAD6), isDark = true
)

// ==========================================
// 3. PURPLE THEME (بنفش سلطنتی)
// ==========================================
val PurpleLightColorScheme = palette(
    primary = Color(0xFF6B3FE0), primaryContainer = Color(0xFFE8DEFF), onPrimary = Color.White, onPrimaryContainer = Color(0xFF2C0B7A),
    accent = Color(0xFFA02BB8), accentContainer = Color(0xFFF7DCFF), onAccent = Color.White, onAccentContainer = Color(0xFF4A0A57),
    tertiary = Color(0xFFC2317A), tertiaryContainer = Color(0xFFFFDDEA), onTertiary = Color.White, onTertiaryContainer = Color(0xFF5A0A32),
    background = Color(0xFFF9F6FF), surface = Color.White, surfaceHigh = Color(0xFFF1EAFE), surfaceVariantIn = Color(0xFFECE3FD),
    onBackgroundIn = Color(0xFF150B2A), onSurfaceVariantIn = Color(0xFF615277), outlineIn = Color(0xFFC0B2D8), outlineVariantIn = Color(0xFFE2D9F0),
    error = Color(0xFFD02E4E), errorContainer = Color(0xFFFFDADE), onErrorContainer = Color(0xFF790B22), isDark = false
)

val PurpleDarkColorScheme = palette(
    primary = Color(0xFFC0A3FF), primaryContainer = Color(0xFF40268C), onPrimary = Color(0xFF1E0A52), onPrimaryContainer = Color(0xFFE8DEFF),
    accent = Color(0xFFE39BF5), accentContainer = Color(0xFF5A1069), onAccent = Color(0xFF37003F), onAccentContainer = Color(0xFFF7DCFF),
    tertiary = Color(0xFFFF9EC0), tertiaryContainer = Color(0xFF6E1039), onTertiary = Color(0xFF44001C), onTertiaryContainer = Color(0xFFFFDDEA),
    background = Color(0xFF0E0818), surface = Color(0xFF171024), surfaceHigh = Color(0xFF1D1430), surfaceVariantIn = Color(0xFF261A3D),
    onBackgroundIn = Color(0xFFF5F0FF), onSurfaceVariantIn = Color(0xFFAB9BC4), outlineIn = Color(0xFF463762), outlineVariantIn = Color(0xFF2B2046),
    error = Color(0xFFFF8FA0), errorContainer = Color(0xFF5C1020), onErrorContainer = Color(0xFFFFDADE), isDark = true
)

// ==========================================
// 4. ORANGE THEME (نارنجی پرانرژی)
// ==========================================
val OrangeLightColorScheme = palette(
    primary = Color(0xFFDD5A0B), primaryContainer = Color(0xFFFFE0CC), onPrimary = Color.White, onPrimaryContainer = Color(0xFF6A2300),
    accent = Color(0xFFA8730A), accentContainer = Color(0xFFFFF0C9), onAccent = Color.White, onAccentContainer = Color(0xFF4C3100),
    tertiary = Color(0xFFC42B4C), tertiaryContainer = Color(0xFFFFDDE2), onTertiary = Color.White, onTertiaryContainer = Color(0xFF5E0A1C),
    background = Color(0xFFFFF8F2), surface = Color.White, surfaceHigh = Color(0xFFFDEFE4), surfaceVariantIn = Color(0xFFFBE9DC),
    onBackgroundIn = Color(0xFF23130A), onSurfaceVariantIn = Color(0xFF74584A), outlineIn = Color(0xFFD6B7A4), outlineVariantIn = Color(0xFFEFDCD0),
    error = Color(0xFFCC2E28), errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF7A0B08), isDark = false
)

val OrangeDarkColorScheme = palette(
    primary = Color(0xFFFFAB6B), primaryContainer = Color(0xFF7A3505), onPrimary = Color(0xFF451A00), onPrimaryContainer = Color(0xFFFFE0CC),
    accent = Color(0xFFFFC94D), accentContainer = Color(0xFF6A4A00), onAccent = Color(0xFF3D2900), onAccentContainer = Color(0xFFFFF0C9),
    tertiary = Color(0xFFFF9EAE), tertiaryContainer = Color(0xFF7A1B33), onTertiary = Color(0xFF4A0012), onTertiaryContainer = Color(0xFFFFDDE2),
    background = Color(0xFF150D07), surface = Color(0xFF22150D), surfaceHigh = Color(0xFF2B1B11), surfaceVariantIn = Color(0xFF362216),
    onBackgroundIn = Color(0xFFFFF2E8), onSurfaceVariantIn = Color(0xFFC8A995), outlineIn = Color(0xFF5C4434), outlineVariantIn = Color(0xFF3A281C),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1410), onErrorContainer = Color(0xFFFFDAD5), isDark = true
)

// ==========================================
// 5. NAVY THEME (سرمه‌ای مدرن)
// ==========================================
val NavyLightColorScheme = palette(
    primary = Color(0xFF1B2748), primaryContainer = Color(0xFFDCE3F5), onPrimary = Color.White, onPrimaryContainer = Color(0xFF0E1730),
    accent = Color(0xFF2C4B8C), accentContainer = Color(0xFFDDE6FB), onAccent = Color.White, onAccentContainer = Color(0xFF0F2450),
    tertiary = Color(0xFF0E7C93), tertiaryContainer = Color(0xFFD2F0F6), onTertiary = Color.White, onTertiaryContainer = Color(0xFF03343F),
    background = Color(0xFFF4F6FB), surface = Color.White, surfaceHigh = Color(0xFFEBEFF8), surfaceVariantIn = Color(0xFFE6EAF4),
    onBackgroundIn = Color(0xFF0E1424), onSurfaceVariantIn = Color(0xFF5A6480), outlineIn = Color(0xFFB6BFD2), outlineVariantIn = Color(0xFFDBE0EC),
    error = Color(0xFFC82F3A), errorContainer = Color(0xFFFFDAD9), onErrorContainer = Color(0xFF770A12), isDark = false
)

val NavyDarkColorScheme = palette(
    primary = Color(0xFFB9C8EC), primaryContainer = Color(0xFF2A3A62), onPrimary = Color(0xFF131C31), onPrimaryContainer = Color(0xFFDCE3F5),
    accent = Color(0xFF8FB2FF), accentContainer = Color(0xFF1E3A70), onAccent = Color(0xFF0A1730), onAccentContainer = Color(0xFFDDE6FB),
    tertiary = Color(0xFF5FD6E8), tertiaryContainer = Color(0xFF0A4A58), onTertiary = Color(0xFF002F38), onTertiaryContainer = Color(0xFFD2F0F6),
    background = Color(0xFF060A14), surface = Color(0xFF0D1426), surfaceHigh = Color(0xFF121B31), surfaceVariantIn = Color(0xFF17213A),
    onBackgroundIn = Color(0xFFF0F3FB), onSurfaceVariantIn = Color(0xFF98A3BE), outlineIn = Color(0xFF33405F), outlineVariantIn = Color(0xFF202A44),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5A1118), onErrorContainer = Color(0xFFFFDAD9), isDark = true
)

// ==========================================
// 6. TEAL THEME (فیروزه‌ای ایرانی)
// ==========================================
val TealLightColorScheme = palette(
    primary = Color(0xFF0A8A80), primaryContainer = Color(0xFFC3F5EE), onPrimary = Color.White, onPrimaryContainer = Color(0xFF00332E),
    accent = Color(0xFF1F72B8), accentContainer = Color(0xFFD6E9FA), onAccent = Color.White, onAccentContainer = Color(0xFF0A3055),
    tertiary = Color(0xFF7A5AD6), tertiaryContainer = Color(0xFFE8E1FF), onTertiary = Color.White, onTertiaryContainer = Color(0xFF30196F),
    background = Color(0xFFF2FBF9), surface = Color.White, surfaceHigh = Color(0xFFE9F7F4), surfaceVariantIn = Color(0xFFE2F3EF),
    onBackgroundIn = Color(0xFF0A1D1A), onSurfaceVariantIn = Color(0xFF4B6662), outlineIn = Color(0xFFA6C6C0), outlineVariantIn = Color(0xFFD2E7E3),
    error = Color(0xFFC93434), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF770B0B), isDark = false
)

val TealDarkColorScheme = palette(
    primary = Color(0xFF4EE0D0), primaryContainer = Color(0xFF0B4A45), onPrimary = Color(0xFF00312C), onPrimaryContainer = Color(0xFFC3F5EE),
    accent = Color(0xFF7FBFFF), accentContainer = Color(0xFF163E62), onAccent = Color(0xFF041E33), onAccentContainer = Color(0xFFD6E9FA),
    tertiary = Color(0xFFC4B2FF), tertiaryContainer = Color(0xFF3C2A81), onTertiary = Color(0xFF1C0D4C), onTertiaryContainer = Color(0xFFE8E1FF),
    background = Color(0xFF041210), surface = Color(0xFF0A1F1C), surfaceHigh = Color(0xFF0F2723), surfaceVariantIn = Color(0xFF14312C),
    onBackgroundIn = Color(0xFFE9FAF6), onSurfaceVariantIn = Color(0xFF8CB2AB), outlineIn = Color(0xFF2B4E49), outlineVariantIn = Color(0xFF19332F),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5A1412), onErrorContainer = Color(0xFFFFDAD6), isDark = true
)

// ==========================================
// 7. ROSE THEME (زرشکی و رزگلد)
// ==========================================
val RoseLightColorScheme = palette(
    primary = Color(0xFFC42E52), primaryContainer = Color(0xFFFFDDE3), onPrimary = Color.White, onPrimaryContainer = Color(0xFF5C0A1C),
    accent = Color(0xFFB02C87), accentContainer = Color(0xFFFBDBEF), onAccent = Color.White, onAccentContainer = Color(0xFF4E0A3A),
    tertiary = Color(0xFF9B6B1C), tertiaryContainer = Color(0xFFFBEBCB), onTertiary = Color.White, onTertiaryContainer = Color(0xFF3F2900),
    background = Color(0xFFFFF7F8), surface = Color.White, surfaceHigh = Color(0xFFFDECEF), surfaceVariantIn = Color(0xFFFBE5E9),
    onBackgroundIn = Color(0xFF25090F), onSurfaceVariantIn = Color(0xFF74545B), outlineIn = Color(0xFFD6B0B8), outlineVariantIn = Color(0xFFEFD7DB),
    error = Color(0xFFC42E52), errorContainer = Color(0xFFFFDDE3), onErrorContainer = Color(0xFF5C0A1C), isDark = false
)

val RoseDarkColorScheme = palette(
    primary = Color(0xFFFF9EB2), primaryContainer = Color(0xFF6E1230), onPrimary = Color(0xFF44000F), onPrimaryContainer = Color(0xFFFFDDE3),
    accent = Color(0xFFF59BD6), accentContainer = Color(0xFF5E1047), onAccent = Color(0xFF3B002C), onAccentContainer = Color(0xFFFBDBEF),
    tertiary = Color(0xFFE6C173), tertiaryContainer = Color(0xFF4D3600), onTertiary = Color(0xFF2C1E00), onTertiaryContainer = Color(0xFFFBEBCB),
    background = Color(0xFF14070A), surface = Color(0xFF200D12), surfaceHigh = Color(0xFF291218), surfaceVariantIn = Color(0xFF331820),
    onBackgroundIn = Color(0xFFFFF0F2), onSurfaceVariantIn = Color(0xFFC4A0A8), outlineIn = Color(0xFF583C43), outlineVariantIn = Color(0xFF372127),
    error = Color(0xFFFF9EB2), errorContainer = Color(0xFF6E1230), onErrorContainer = Color(0xFFFFDDE3), isDark = true
)

// ==========================================
// 8. AMBER THEME (طلایی و کهربایی)
// ==========================================
val AmberLightColorScheme = palette(
    primary = Color(0xFFB4740A), primaryContainer = Color(0xFFFFEBBD), onPrimary = Color.White, onPrimaryContainer = Color(0xFF3E2700),
    accent = Color(0xFF8A6A0E), accentContainer = Color(0xFFF8E9C0), onAccent = Color.White, onAccentContainer = Color(0xFF3E2E00),
    tertiary = Color(0xFFB2501C), tertiaryContainer = Color(0xFFFFE0D0), onTertiary = Color.White, onTertiaryContainer = Color(0xFF501C00),
    background = Color(0xFFFFFAF0), surface = Color.White, surfaceHigh = Color(0xFFFDF2DC), surfaceVariantIn = Color(0xFFFAECD3),
    onBackgroundIn = Color(0xFF221807), onSurfaceVariantIn = Color(0xFF6E5A38), outlineIn = Color(0xFFD0B98C), outlineVariantIn = Color(0xFFEBDFC4),
    error = Color(0xFFC43418), errorContainer = Color(0xFFFFDAD0), onErrorContainer = Color(0xFF741305), isDark = false
)

val AmberDarkColorScheme = palette(
    primary = Color(0xFFF7CD6A), primaryContainer = Color(0xFF6B4C00), onPrimary = Color(0xFF3D2A00), onPrimaryContainer = Color(0xFFFFEBBD),
    accent = Color(0xFFE9D187), accentContainer = Color(0xFF4D3E00), onAccent = Color(0xFF2C2300), onAccentContainer = Color(0xFFF8E9C0),
    tertiary = Color(0xFFFFB68C), tertiaryContainer = Color(0xFF6E2A00), onTertiary = Color(0xFF441500), onTertiaryContainer = Color(0xFFFFE0D0),
    background = Color(0xFF120E05), surface = Color(0xFF1D170B), surfaceHigh = Color(0xFF261E10), surfaceVariantIn = Color(0xFF302616),
    onBackgroundIn = Color(0xFFFFF6E4), onSurfaceVariantIn = Color(0xFFC2AE86), outlineIn = Color(0xFF554830), outlineVariantIn = Color(0xFF352B1B),
    error = Color(0xFFFF9E82), errorContainer = Color(0xFF5C1A0C), onErrorContainer = Color(0xFFFFDAD0), isDark = true
)

// ==========================================
// 9. SLATE THEME (نوک‌مدادی گرافیتی)
// ==========================================
val SlateLightColorScheme = palette(
    primary = Color(0xFF3A4658), primaryContainer = Color(0xFFDFE5EE), onPrimary = Color.White, onPrimaryContainer = Color(0xFF131B26),
    accent = Color(0xFF4C6285), accentContainer = Color(0xFFDDE4F1), onAccent = Color.White, onAccentContainer = Color(0xFF152239),
    tertiary = Color(0xFF2C6E8F), tertiaryContainer = Color(0xFFD5ECF7), onTertiary = Color.White, onTertiaryContainer = Color(0xFF062B3D),
    background = Color(0xFFF5F7FA), surface = Color.White, surfaceHigh = Color(0xFFECF0F5), surfaceVariantIn = Color(0xFFE7EBF1),
    onBackgroundIn = Color(0xFF0F151F), onSurfaceVariantIn = Color(0xFF59636F), outlineIn = Color(0xFFB6BEC9), outlineVariantIn = Color(0xFFDCE1E8),
    error = Color(0xFFC52D38), errorContainer = Color(0xFFFFDAD8), onErrorContainer = Color(0xFF770A11), isDark = false
)

val SlateDarkColorScheme = palette(
    primary = Color(0xFFC6D0E0), primaryContainer = Color(0xFF2C3746), onPrimary = Color(0xFF141B25), onPrimaryContainer = Color(0xFFDFE5EE),
    accent = Color(0xFF9DB8E8), accentContainer = Color(0xFF22334D), onAccent = Color(0xFF0A1729), onAccentContainer = Color(0xFFDDE4F1),
    tertiary = Color(0xFF7FD1F0), tertiaryContainer = Color(0xFF0B4154), onTertiary = Color(0xFF002A38), onTertiaryContainer = Color(0xFFD5ECF7),
    background = Color(0xFF080B10), surface = Color(0xFF10141B), surfaceHigh = Color(0xFF151B24), surfaceVariantIn = Color(0xFF1B222D),
    onBackgroundIn = Color(0xFFF0F3F7), onSurfaceVariantIn = Color(0xFF99A2B0), outlineIn = Color(0xFF343E4C), outlineVariantIn = Color(0xFF222A35),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5A1113), onErrorContainer = Color(0xFFFFDAD8), isDark = true
)

// ==========================================
// 10. FOREST THEME (سبز زیتونی جنگلی)
// ==========================================
val ForestLightColorScheme = palette(
    primary = Color(0xFF4A6B12), primaryContainer = Color(0xFFDBF2B0), onPrimary = Color.White, onPrimaryContainer = Color(0xFF1E2E00),
    accent = Color(0xFF256B45), accentContainer = Color(0xFFCDEFDA), onAccent = Color.White, onAccentContainer = Color(0xFF06301B),
    tertiary = Color(0xFF7A5A18), tertiaryContainer = Color(0xFFFBE9C0), onTertiary = Color.White, onTertiaryContainer = Color(0xFF3E2A00),
    background = Color(0xFFF7FBEE), surface = Color.White, surfaceHigh = Color(0xFFEEF6DF), surfaceVariantIn = Color(0xFFEAF2DA),
    onBackgroundIn = Color(0xFF161E0A), onSurfaceVariantIn = Color(0xFF5B6747), outlineIn = Color(0xFFB2C193), outlineVariantIn = Color(0xFFDCE5CB),
    error = Color(0xFFC72E2E), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF780909), isDark = false
)

val ForestDarkColorScheme = palette(
    primary = Color(0xFFC3E97C), primaryContainer = Color(0xFF3A5410), onPrimary = Color(0xFF1E2E00), onPrimaryContainer = Color(0xFFDBF2B0),
    accent = Color(0xFF86DDA9), accentContainer = Color(0xFF0F4429), onAccent = Color(0xFF002912), onAccentContainer = Color(0xFFCDEFDA),
    tertiary = Color(0xFFE8C777), tertiaryContainer = Color(0xFF523B00), onTertiary = Color(0xFF2E2000), onTertiaryContainer = Color(0xFFFBE9C0),
    background = Color(0xFF0A1005), surface = Color(0xFF121A0B), surfaceHigh = Color(0xFF182211), surfaceVariantIn = Color(0xFF1F2B17),
    onBackgroundIn = Color(0xFFF1F8E6), onSurfaceVariantIn = Color(0xFFA1B28A), outlineIn = Color(0xFF3A4A2C), outlineVariantIn = Color(0xFF25321B),
    error = Color(0xFFFF8A80), errorContainer = Color(0xFF5C1212), onErrorContainer = Color(0xFFFFDAD6), isDark = true
)

val AllColorSchemes: Map<String, Pair<ColorScheme, ColorScheme>> = mapOf(
    "BLUE" to (BlueLightColorScheme to BlueDarkColorScheme),
    "GREEN" to (GreenLightColorScheme to GreenDarkColorScheme),
    "PURPLE" to (PurpleLightColorScheme to PurpleDarkColorScheme),
    "ORANGE" to (OrangeLightColorScheme to OrangeDarkColorScheme),
    "NAVY" to (NavyLightColorScheme to NavyDarkColorScheme),
    "TEAL" to (TealLightColorScheme to TealDarkColorScheme),
    "ROSE" to (RoseLightColorScheme to RoseDarkColorScheme),
    "AMBER" to (AmberLightColorScheme to AmberDarkColorScheme),
    "SLATE" to (SlateLightColorScheme to SlateDarkColorScheme),
    "FOREST" to (ForestLightColorScheme to ForestDarkColorScheme)
)

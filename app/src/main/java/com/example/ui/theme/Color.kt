package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Status Accents
val StatusSuccess = Color(0xFF10B981)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
val StatusInfo = Color(0xFF3B82F6)

// Brand accents & backward compatibility
val IndigoPrimary = Color(0xFF4F46E5)
val TealSecondary = Color(0xFF0D9488)
val AmberTertiary = Color(0xFFF59E0B)

// ==========================================
// 1. BLUE THEME (آبی حرفه‌ای)
// ==========================================
val BluePrimaryLight = Color(0xFF1D4ED8)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BlueContainerLight = Color(0xFFDBEAFE)
val BlueOnContainerLight = Color(0xFF1E3A8A)

val BluePrimaryDark = Color(0xFF60A5FA)
val BlueOnPrimaryDark = Color(0xFF172554)
val BlueContainerDark = Color(0xFF1E40AF)
val BlueOnContainerDark = Color(0xFFDBEAFE)

val BlueLightColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    onPrimary = BlueOnPrimaryLight,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = BlueOnContainerLight,
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEF2FF),
    onTertiaryContainer = Color(0xFF4338CA),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

val BlueDarkColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = BlueOnPrimaryDark,
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = BlueOnContainerDark,
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0369A1),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF4338CA),
    onTertiaryContainer = Color(0xFFEEF2FF),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569)
)

// ==========================================
// 2. GREEN THEME (سبز زمردی)
// ==========================================
val GreenLightColorScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA7F3D0),
    onTertiaryContainer = Color(0xFF065F46),
    background = Color(0xFFF7FEE7),
    onBackground = Color(0xFF064E3B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFECFDF5),
    onSurfaceVariant = Color(0xFF047857),
    outline = Color(0xFFA7F3D0)
)

val GreenDarkColorScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF064E3B),
    primaryContainer = Color(0xFF047857),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF134E4A),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF065F46),
    tertiaryContainer = Color(0xFF059669),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF06281E),
    onBackground = Color(0xFFECFDF5),
    surface = Color(0xFF0E3E30),
    onSurface = Color(0xFFF0FDF4),
    surfaceVariant = Color(0xFF164E3F),
    onSurfaceVariant = Color(0xFF6EE7B7),
    outline = Color(0xFF065F46)
)

// ==========================================
// 3. PURPLE THEME (بنفش سلطنتی)
// ==========================================
val PurpleLightColorScheme = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF9333EA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E8FF),
    onSecondaryContainer = Color(0xFF581C87),
    tertiary = Color(0xFFC026D3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAE8FF),
    onTertiaryContainer = Color(0xFF701A75),
    background = Color(0xFFFAF5FF),
    onBackground = Color(0xFF3B0764),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1235),
    surfaceVariant = Color(0xFFF3E8FF),
    onSurfaceVariant = Color(0xFF6B21A8),
    outline = Color(0xFFD8B4FE)
)

val PurpleDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF3B0764),
    primaryContainer = Color(0xFF6D28D9),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC084FC),
    onSecondary = Color(0xFF581C87),
    secondaryContainer = Color(0xFF7E22CE),
    onSecondaryContainer = Color(0xFFF3E8FF),
    tertiary = Color(0xFFE879F9),
    onTertiary = Color(0xFF701A75),
    tertiaryContainer = Color(0xFFA21CAF),
    onTertiaryContainer = Color(0xFFFAE8FF),
    background = Color(0xFF13091F),
    onBackground = Color(0xFFFAF5FF),
    surface = Color(0xFF231438),
    onSurface = Color(0xFFFAF5FF),
    surfaceVariant = Color(0xFF3B215C),
    onSurfaceVariant = Color(0xFFC084FC),
    outline = Color(0xFF581C87)
)

// ==========================================
// 4. ORANGE THEME (نارنجی پرانرژی)
// ==========================================
val OrangeLightColorScheme = lightColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    tertiary = Color(0xFFE11D48),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE4E6),
    onTertiaryContainer = Color(0xFF881337),
    background = Color(0xFFFFFBEB),
    onBackground = Color(0xFF451A03),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1610),
    surfaceVariant = Color(0xFFFFF7ED),
    onSurfaceVariant = Color(0xFF9A3412),
    outline = Color(0xFFFED7AA)
)

val OrangeDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFFC2410C),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF78350F),
    secondaryContainer = Color(0xFFB45309),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = Color(0xFFFB7185),
    onTertiary = Color(0xFF881337),
    tertiaryContainer = Color(0xFFBE123C),
    onTertiaryContainer = Color(0xFFFFE4E6),
    background = Color(0xFF1C0D06),
    onBackground = Color(0xFFFFF7ED),
    surface = Color(0xFF2E170C),
    onSurface = Color(0xFFFFF7ED),
    surfaceVariant = Color(0xFF451A08),
    onSurfaceVariant = Color(0xFFFDBA74),
    outline = Color(0xFF7C2D12)
)

// ==========================================
// 5. NAVY THEME (سرمه‌ای مدرن)
// ==========================================
val NavyLightColorScheme = lightColorScheme(
    primary = Color(0xFF0F172A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF334155),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0369A1),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

val NavyDarkColorScheme = darkColorScheme(
    primary = Color(0xFF94A3B8),
    onPrimary = Color(0xFF020617),
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF082F49),
    tertiaryContainer = Color(0xFF0369A1),
    onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF020617),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155)
)

// ==========================================
// 6. TEAL THEME (فیروزه‌ای ایرانی)
// ==========================================
val TealLightColorScheme = lightColorScheme(
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = Color(0xFF14B8A6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF99F6E4),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF0FDFA),
    onBackground = Color(0xFF134E4A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF042F2E),
    surfaceVariant = Color(0xFFE6FFFA),
    onSurfaceVariant = Color(0xFF0F766E),
    outline = Color(0xFF99F6E4)
)

val TealDarkColorScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF134E4A),
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF0C4A6E),
    secondaryContainer = Color(0xFF0369A1),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF115E59),
    tertiaryContainer = Color(0xFF14B8A6),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF04201D),
    onBackground = Color(0xFFF0FDFA),
    surface = Color(0xFF0C3834),
    onSurface = Color(0xFFF0FDFA),
    surfaceVariant = Color(0xFF134E4A),
    onSurfaceVariant = Color(0xFF5EEAD4),
    outline = Color(0xFF0F766E)
)

// ==========================================
// 7. ROSE THEME (زرشکی و رزگلد)
// ==========================================
val RoseLightColorScheme = lightColorScheme(
    primary = Color(0xFFE11D48),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = Color(0xFF881337),
    secondary = Color(0xFFDB2777),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCE7F3),
    onSecondaryContainer = Color(0xFF831843),
    tertiary = Color(0xFFF43F5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFECDD3),
    onTertiaryContainer = Color(0xFF9F1239),
    background = Color(0xFFFFF1F2),
    onBackground = Color(0xFF4C0519),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2E0914),
    surfaceVariant = Color(0xFFFFE4E6),
    onSurfaceVariant = Color(0xFF9F1239),
    outline = Color(0xFFFECDD3)
)

val RoseDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFB7185),
    onPrimary = Color(0xFF4C0519),
    primaryContainer = Color(0xFF9F1239),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFFF472B6),
    onSecondary = Color(0xFF500724),
    secondaryContainer = Color(0xFF9D174D),
    onSecondaryContainer = Color(0xFFFCE7F3),
    tertiary = Color(0xFFFDA4AF),
    onTertiary = Color(0xFF881337),
    tertiaryContainer = Color(0xFFE11D48),
    onTertiaryContainer = Color(0xFFFFE4E6),
    background = Color(0xFF1F040C),
    onBackground = Color(0xFFFFF1F2),
    surface = Color(0xFF350B18),
    onSurface = Color(0xFFFFF1F2),
    surfaceVariant = Color(0xFF4C0519),
    onSurfaceVariant = Color(0xFFFDA4AF),
    outline = Color(0xFF881337)
)

// ==========================================
// 8. AMBER THEME (طلایی و کهربایی)
// ==========================================
val AmberLightColorScheme = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = Color(0xFFCA8A04),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFEF9C3),
    onSecondaryContainer = Color(0xFF713F12),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color(0xFF1F1206),
    tertiaryContainer = Color(0xFFFDE68A),
    onTertiaryContainer = Color(0xFF78350F),
    background = Color(0xFFFFFBEB),
    onBackground = Color(0xFF451A03),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF221606),
    surfaceVariant = Color(0xFFFEF3C7),
    onSurfaceVariant = Color(0xFF92400E),
    outline = Color(0xFFFDE68A)
)

val AmberDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFF92400E),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondary = Color(0xFFFACC15),
    onSecondary = Color(0xFF422006),
    secondaryContainer = Color(0xFF854D0E),
    onSecondaryContainer = Color(0xFFFEF9C3),
    tertiary = Color(0xFFFDE047),
    onTertiary = Color(0xFF713F12),
    tertiaryContainer = Color(0xFFB45309),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF1E1004),
    onBackground = Color(0xFFFFFBEB),
    surface = Color(0xFF2F1B08),
    onSurface = Color(0xFFFFFBEB),
    surfaceVariant = Color(0xFF451A03),
    onSurfaceVariant = Color(0xFFFDE68A),
    outline = Color(0xFF78350F)
)

// ==========================================
// 9. SLATE THEME (نوک‌مدادی گرافیتی)
// ==========================================
val SlateLightColorScheme = lightColorScheme(
    primary = Color(0xFF475569),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E8F0),
    onPrimaryContainer = Color(0xFF1E293B),
    secondary = Color(0xFF64748B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1F5F9),
    onSecondaryContainer = Color(0xFF334155),
    tertiary = Color(0xFF3B82F6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

val SlateDarkColorScheme = darkColorScheme(
    primary = Color(0xFF94A3B8),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF334155),
    onPrimaryContainer = Color(0xFFF1F5F9),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFF475569),
    onSecondaryContainer = Color(0xFFF8FAFC),
    tertiary = Color(0xFF60A5FA),
    onTertiary = Color(0xFF172554),
    tertiaryContainer = Color(0xFF1D4ED8),
    onTertiaryContainer = Color(0xFFDBEAFE),
    background = Color(0xFF090D14),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF141C28),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569)
)

// ==========================================
// 10. FOREST THEME (سبز زیتونی جنگلی)
// ==========================================
val ForestLightColorScheme = lightColorScheme(
    primary = Color(0xFF3F6212),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFECFCCB),
    onPrimaryContainer = Color(0xFF1A2E05),
    secondary = Color(0xFF65A30D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7FEE7),
    onSecondaryContainer = Color(0xFF365314),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF14532D),
    background = Color(0xFFF7FEE7),
    onBackground = Color(0xFF1A2E05),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF142403),
    surfaceVariant = Color(0xFFECFCCB),
    onSurfaceVariant = Color(0xFF4D7C0F),
    outline = Color(0xFFBEF264)
)

val ForestDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA3E635),
    onPrimary = Color(0xFF1A2E05),
    primaryContainer = Color(0xFF365314),
    onPrimaryContainer = Color(0xFFECFCCB),
    secondary = Color(0xFF84CC16),
    onSecondary = Color(0xFF1E3A06),
    secondaryContainer = Color(0xFF4D7C0F),
    onSecondaryContainer = Color(0xFFF7FEE7),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF15803D),
    onTertiaryContainer = Color(0xFFDCFCE7),
    background = Color(0xFF0B1402),
    onBackground = Color(0xFFF7FEE7),
    surface = Color(0xFF162406),
    onSurface = Color(0xFFF7FEE7),
    surfaceVariant = Color(0xFF273E0A),
    onSurfaceVariant = Color(0xFFA3E635),
    outline = Color(0xFF4D7C0F)
)

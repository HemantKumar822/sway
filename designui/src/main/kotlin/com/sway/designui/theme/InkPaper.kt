package com.sway.designui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Ink & Paper" monochrome schemes (story 9.1, UX §7.1 MONO mode) — the
 * Notion philosophy: paper neutrals carry the interface, ink does the
 * emphasis, hairlines replace shadows, and color appears ONLY as meaning
 * (rose = like, amber = caution, standard error ramp).
 */
object InkPaper {

    private val Rose = Color(0xFFB45350)          // like (semantic, reserved)
    private val RoseDark = Color(0xFFE5877F)
    private val AmberCautionLight = Color(0xFF8A6100) // offline/stale (reserved)
    private val AmberCautionDark = Color(0xFFFFB951)

    fun scheme(dark: Boolean, amoledBlack: Boolean = false): ColorScheme =
        if (!dark) {
            lightColorScheme(
                primary = Color(0xFF191918),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFF2E2D2C),
                onPrimaryContainer = Color(0xFFF4F3F1),
                inversePrimary = Color(0xFFEDECEA),
                secondary = Color(0xFF5F5E5B),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFEFEFED),
                onSecondaryContainer = Color(0xFF2A2927),
                tertiary = Rose,
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFF6E4E2),
                onTertiaryContainer = Color(0xFF5C2B28),
                background = Color(0xFFFFFFFF),
                onBackground = Color(0xFF191918),
                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF191918),
                surfaceVariant = Color(0xFFF7F7F5),
                onSurfaceVariant = Color(0xFF6B6A66),
                inverseSurface = Color(0xFF2A2927),
                inverseOnSurface = Color(0xFFF4F3F1),
                error = Color(0xFFB3261E),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                outline = Color(0xFFB9B8B5),
                outlineVariant = Color(0xFFDEDEDC),
                scrim = Color(0xFF000000),
            )
        } else {
            darkColorScheme(
                primary = if (amoledBlack) Color(0xFFEDECEA) else Color(0xFFEDECEA),
                onPrimary = Color(0xFF141413),
                primaryContainer = Color(0xFF33322F),
                onPrimaryContainer = Color(0xFFE9E8E5),
                inversePrimary = Color(0xFF191918),
                secondary = Color(0xFFA8A7A3),
                onSecondary = Color(0xFF141413),
                secondaryContainer = Color(0xFF262625),
                onSecondaryContainer = Color(0xFFDDDCD9),
                tertiary = RoseDark,
                onTertiary = Color(0xFF2B1210),
                tertiaryContainer = Color(0xFF4A211E),
                onTertiaryContainer = Color(0xFFF6DBD8),
                background = if (amoledBlack) Color(0xFF000000) else Color(0xFF101010),
                onBackground = Color(0xFFEDECEA),
                surface = if (amoledBlack) Color(0xFF000000) else Color(0xFF101010),
                onSurface = Color(0xFFEDECEA),
                surfaceVariant = if (amoledBlack) Color(0xFF0C0C0C) else Color(0xFF171717),
                onSurfaceVariant = Color(0xFFA8A7A3),
                inverseSurface = Color(0xFFEDECEA),
                inverseOnSurface = Color(0xFF141413),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                outline = Color(0xFF555450),
                outlineVariant = Color(0xFF2A2A28),
                scrim = Color(0xFF000000),
            )
        }

    /** Semantic accents exposed for kit components (never used decoratively). */
    val LikeAccentLight: Color get() = Rose
    val LikeAccentDark: Color get() = RoseDark
    val CautionLight: Color get() = AmberCautionLight
    val CautionDark: Color get() = AmberCautionDark
}

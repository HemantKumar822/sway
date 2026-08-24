package com.sway.designui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Derives a full Material ColorScheme from an artwork seed (story 9.1,
 * DYNAMIC mode): hue comes from the artwork; lightness/chroma discipline
 * keeps every role inside the NFR-5 contrast floors. Deterministic — the
 * same seed always yields the same scheme (screenshot-stable).
 *
 * Tonal math is deliberately compact (HSL tone ladder, SuvMusic-style
 * dominant extraction) rather than a full Material You engine: covers are
 * already high-chroma art, and the MONO mode remains the design anchor.
 */
object DynamicSchemeFactory {

    fun scheme(seed: Int, dark: Boolean): ColorScheme {
        val hsl = seed.toHsl()
        val hue = hsl[0]

        val primary = if (!dark) hslColor(hue, hsl[1].coerceIn(0.45f, 0.72f), 0.38f)
        else hslColor(hue, hsl[1].coerceIn(0.35f, 0.62f), 0.78f)

        val bg = tintedNeutral(hue, dark, baseLightness = if (dark) 0.065f else 0.985f, satCap = 0.10f)

        val tertiaryHue = (hue + 30f + 360f) % 360f

        return if (!dark) {
            lightColorScheme(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = hslColor(hue, 0.60f, 0.90f),
                onPrimaryContainer = hslColor(hue, 0.55f, 0.22f),
                secondary = hslColor(hue, 0.18f, 0.32f),
                onSecondary = Color.White,
                secondaryContainer = hslColor(hue, 0.12f, 0.91f),
                onSecondaryContainer = hslColor(hue, 0.20f, 0.24f),
                tertiary = hslColor(tertiaryHue, 0.42f, 0.40f),
                onTertiary = Color.White,
                tertiaryContainer = hslColor(tertiaryHue, 0.45f, 0.90f),
                onTertiaryContainer = hslColor(tertiaryHue, 0.42f, 0.22f),
                background = bg,
                onBackground = inkOn(bg),
                surface = bg,
                onSurface = inkOn(bg),
                surfaceVariant = tintedNeutral(hue, dark, 0.945f, 0.08f),
                onSurfaceVariant = hslColor(hue, 0.08f, 0.36f),
                outline = hslColor(hue, 0.07f, 0.58f),
                outlineVariant = hslColor(hue, 0.07f, 0.87f),
                error = Color(0xFFB3261E),
                onError = Color.White,
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
            )
        } else {
            darkColorScheme(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = hslColor(hue, 0.45f, 0.26f),
                onPrimaryContainer = hslColor(hue, 0.50f, 0.88f),
                secondary = hslColor(hue, 0.14f, 0.74f),
                onSecondary = Color(0xFF101010),
                secondaryContainer = hslColor(hue, 0.12f, 0.20f),
                onSecondaryContainer = hslColor(hue, 0.10f, 0.88f),
                tertiary = hslColor(tertiaryHue, 0.34f, 0.76f),
                onTertiary = Color(0xFF101010),
                tertiaryContainer = hslColor(tertiaryHue, 0.30f, 0.28f),
                onTertiaryContainer = hslColor(tertiaryHue, 0.34f, 0.88f),
                background = bg,
                onBackground = inkOn(bg),
                surface = bg,
                onSurface = inkOn(bg),
                surfaceVariant = tintedNeutral(hue, dark, 0.115f, 0.08f),
                onSurfaceVariant = hslColor(hue, 0.07f, 0.70f),
                outline = hslColor(hue, 0.06f, 0.48f),
                outlineVariant = hslColor(hue, 0.06f, 0.22f),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
            )
        }
    }

    // --- tonal helpers ------------------------------------------------------

    /** Neutral whose HUE carries the artwork while chroma stays whisper-quiet. */
    private fun tintedNeutral(hue: Float, dark: Boolean, baseLightness: Float, satCap: Float): Color =
        hslColor(hue, satCap * 0.8f, baseLightness)

    /** Ink that always reads on [bg] (contrast-first text law). */
    private fun inkOn(bg: Color): Color =
        if (relativeLuminance(bg) > 0.4f) Color(0xFF191918) else Color(0xFFEDECEA)

    /** Flips to black/white when the seed sits mid-tone (NFR-5 floor >=3:1). */
    private fun readableOn(c: Color): Color =
        if (relativeLuminance(c) > 0.25f) Color(0xFF141413) else Color(0xFFFFFFFF)

}

internal fun Int.toHsl(): FloatArray {
        val r = ((this shr 16) and 0xFF) / 255f
        val g = ((this shr 8) and 0xFF) / 255f
        val b = (this and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f) * 60f
            g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
        return floatArrayOf(h, s, l)
    }

internal fun hslColor(h: Float, s: Float, l: Float): Color {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * ll - 1f)) * ss
        val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
        val m = ll - c / 2f
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f),
        )
    }

internal fun relativeLuminance(c: Color): Float {
    fun lin(ch: Float): Float =
        if (ch <= 0.03928f) ch / 12.92f else Math.pow(((ch + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}
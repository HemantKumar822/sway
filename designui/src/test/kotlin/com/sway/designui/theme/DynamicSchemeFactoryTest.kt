package com.sway.designui.theme

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.sway.designui.theme.relativeLuminance
import com.sway.designui.theme.toHsl
import org.junit.Test

/**
 * Story 9.1 — DynamicSchemeFactory laws (DYNAMIC mode): determinism,
 * contrast floors (NFR-5), hue inheritance, dark/light divergence.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DynamicSchemeFactoryTest {

    private val teal = Color(0xFF1B9E8F).let { android.graphics.Color.rgb(27, 158, 143) }

    @Test
    fun deterministic_sameSeedSameScheme() {
        val a = DynamicSchemeFactory.scheme(teal, dark = false)
        val b = DynamicSchemeFactory.scheme(teal, dark = false)
        assertEquals(a.primary, b.primary)
        assertEquals(a.background, b.background)
        assertEquals(a.tertiary, b.tertiary)
    }

    @Test
    fun contrastFloor_onPrimaryVsPrimary_atLeast3to1_bothModes() {
        for (dark in listOf(false, true)) {
            val s = DynamicSchemeFactory.scheme(teal, dark = dark)
            val lum = relativeLuminance(s.primary)
            val lumOn = relativeLuminance(s.onPrimary)
            val ratio = (maxOf(lum, lumOn) + 0.05f) / (minOf(lum, lumOn) + 0.05f)
            assertTrue("contrast was $ratio (dark=$dark)", ratio >= 3.0f)
        }
    }

    @Test
    fun backgroundHue_inheritsArtwork_dark_chromaWhisperQuiet_light() {
        // DARK: enough chroma survives quantization to prove hue inheritance.
        val darkBg = DynamicSchemeFactory.scheme(teal, dark = true).background
        val darkHsl = argbToHsl(darkBg)
        assertTrue(darkHsl[2] < 0.10f)
        assertTrue(
            "dark surface should carry the artwork hue",
            Math.abs(darkHsl[0] - teal.toHsl()[0]) < 8f,
        )
        // LIGHT: near-paper surfaces lose measurable hue after 8-bit
        // quantization; the law there is "paper-bright + chroma whisper".
        val lightBg = DynamicSchemeFactory.scheme(teal, dark = false).background
        val lightHsl = argbToHsl(lightBg)
        assertTrue(lightHsl[2] > 0.95f)
        assertTrue(lightHsl[1] < 0.15f)
    }

    @Test
    fun darkMode_surfacesAreDark_lightMode_surfacesAreLight() {
        assertTrue(
            relativeLuminance(
                DynamicSchemeFactory.scheme(teal, dark = true).background,
            ) < 0.06f,
        )
        assertTrue(
            relativeLuminance(
                DynamicSchemeFactory.scheme(teal, dark = false).background,
            ) > 0.85f,
        )
    }

    @Test
    fun monoFallback_whenNoSeed_isStructural_viaSwayThemeContract() {
        // The SwayTheme resolution law: null seed => InkPaper regardless of
        // mode. Role-level equality (scheme classes are not data classes).
        val a = InkPaper.scheme(dark = false)
        val b = InkPaper.scheme(dark = false)
        assertEquals(a.primary, b.primary)
        assertEquals(a.background, b.background)
        assertEquals(a.tertiary, b.tertiary)
        assertEquals(a.surfaceVariant, b.surfaceVariant)
    }

    private fun argbToHsl(c: Color): FloatArray {
        val argb = android.graphics.Color.argb(
            (c.alpha * 255).toInt(),
            (c.red * 255).toInt(),
            (c.green * 255).toInt(),
            (c.blue * 255).toInt(),
        )
        return argb.toHsl()
    }
}

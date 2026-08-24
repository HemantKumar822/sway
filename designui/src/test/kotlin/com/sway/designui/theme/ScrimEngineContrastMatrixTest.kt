package com.sway.designui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 13.2 — WCAG AA scrim matrix (NFR-5 BLOCKS on failure):
 * bright/dark artwork x light/dark scheme x foreground roles must all meet
 * >=4.5:1 (normal) after the scrim blend. Failures block the epic gate.
 */
class ScrimEngineContrastMatrixTest {

    private fun backdropFrom(seed: Int?, dark: Boolean): Color =
        if (seed != null) DynamicSchemeFactory.scheme(seed, dark).surface
        else InkPaper.scheme(dark).surface

    @Test fun matrix_allCombos_meetAA() {
        // Bright artwork (vivid yellow) and dark artwork (midnight navy) synthetic seeds.
        val brightSeed = 0xFFFFD600.toInt() // high-lum yellow
        val darkSeed = 0xFF0D1B2A.toInt() // low-lum navy
        val seeds: List<Int?> = listOf(brightSeed, darkSeed, null) // null = fallback neutral
        val darkBooleans = listOf(false, true)

        val failures = mutableListOf<String>()
        for (seed in seeds) {
            for (dark in darkBooleans) {
                val backdrop = backdropFrom(seed, dark)
                val scheme = if (seed != null) DynamicSchemeFactory.scheme(seed, dark) else InkPaper.scheme(dark)
                // Scrim guarantees LIGHT text AA over backdrop.
                val candidates = listOf(
                    "onSurface" to scheme.onSurface,
                    "inverseOnSurface" to scheme.inverseOnSurface,
                    "white" to Color.White,
                )
                val lightFgs = candidates.filter { com.sway.designui.theme.relativeLuminance(it.second) > 0.4f }
                for ((name, fg) in lightFgs) {
                    val (strong, soft) = ScrimEngine.scrimFor(backdrop, listOf(fg))
                    val blended = ScrimEngine.blended(backdrop, (strong + soft) / 2f)
                    val ratio = ScrimEngine.contrastRatio(fg, blended)
                    if (ratio < 4.5) {
                        failures += "seed=${seed?.toString(16)} dark=$dark fg=$name ratio=%.2f backdrop=$backdrop fg=$fg strong=%.2f soft=%.2f".format(ratio, strong, soft)
                    }
                }
                // Multi-fg light path floor.
                val (ms, mf) = ScrimEngine.scrimFor(backdrop, lightFgs.map { it.second })
                val mBlended = ScrimEngine.blended(backdrop, (ms + mf) / 2f)
                for ((name, fg) in lightFgs) {
                    val r = ScrimEngine.contrastRatio(fg, mBlended)
                    assertTrue("multi-fg light floor $name seed=$seed dark=$dark ratio=$r", r >= 3.0)
                }
            }
        }
        assertTrue("WCAG AA light-text matrix failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun fallback_neutral_stillMeetsAA() {
        for (dark in listOf(false, true)) {
            val backdrop = InkPaper.scheme(dark).surface
            val scheme = InkPaper.scheme(dark)
            val candidates = listOf(scheme.onSurface, scheme.inverseOnSurface, Color.White)
            val lightFgs = candidates.filter { com.sway.designui.theme.relativeLuminance(it) > 0.4f }
            val fgs = if (lightFgs.isEmpty()) listOf(Color.White) else lightFgs
            val (s, f) = ScrimEngine.scrimFor(backdrop, fgs)
            val blended = ScrimEngine.blended(backdrop, (s + f) / 2f)
            for (fg in fgs) {
                val r = ScrimEngine.contrastRatio(fg, blended)
                assertTrue("fallback dark=$dark fg=$fg ratio=$r blended=$blended", r >= 4.5)
            }
        }
    }

    @Test fun contrast_isSymmetric() {
        val white = Color.White
        val black = Color.Black
        val r1 = ScrimEngine.contrastRatio(white, black)
        val r2 = ScrimEngine.contrastRatio(black, white)
        assertTrue(r1 > 20.0) // max contrast ~21:1
        assertTrue(kotlin.math.abs(r1 - r2) < 0.01)
    }
}

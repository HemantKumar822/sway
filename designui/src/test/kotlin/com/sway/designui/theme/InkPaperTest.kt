package com.sway.designui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 9.1 — "Ink & Paper" MONO laws: paper neutrals, ink primaries,
 * AMOLED collapse, semantic accents present in both modes.
 */
class InkPaperTest {

    @Test
    fun lightScheme_paperWhites_withInkPrimary() {
        val s = InkPaper.scheme(dark = false)
        assertEquals(Color.White, s.background)
        assertEquals(Color(0xFF191918), s.primary)
        assertEquals(Color.White, s.onPrimary)
        assertEquals(Color(0xFFF7F7F5), s.surfaceVariant)
    }

    @Test
    fun darkScheme_midnightInk_amoledCollapsesToPureBlack() {
        val normal = InkPaper.scheme(dark = true)
        assertEquals(Color(0xFF101010), normal.background)

        val amoled = InkPaper.scheme(dark = true, amoledBlack = true)
        assertEquals(Color.Black, amoled.background)
        assertEquals(Color(0xFF0C0C0C), amoled.surfaceVariant)
        // Text stays readable in AMOLED too.
        assertEquals(normal.onBackground, amoled.onBackground)
    }

    @Test
    fun semanticAccents_presentInBothModes() {
        for (dark in listOf(false, true)) {
            val s: ColorScheme = InkPaper.scheme(dark = dark)
            assertNotNull(s.tertiary)          // rose = like
            assertTrue(s.tertiary != s.primary) // like is NOT ink
        }
        assertTrue(InkPaper.LikeAccentLight != InkPaper.LikeAccentDark)
    }

    @Test
    fun hairlinesReplaceShadows_outlineRolesPresent() {
        for (dark in listOf(false, true)) {
            val s = InkPaper.scheme(dark = dark)
            assertTrue(s.outline != s.outlineVariant)
            assertTrue(s.outlineVariant != s.surface)
        }
    }
}

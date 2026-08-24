package com.sway.designui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 9.1 — reduced-motion law (NFR-6 / UX §12): with reduced motion on,
 * every animated token degrades to a linear fade <=120 ms; otherwise springs
 * and expressive durations apply.
 */
class MotionSchemeTest {

    @Test
    fun reducedMotion_everySpecIsLinearFadeWithin120ms() {
        val m = MotionScheme(reducedMotion = true)
        val fade = MotionScheme.fadeSpec()
        m.enterSpec.let { assertTrue(it::class == fade::class) }
        m.pressSpec.let { assertTrue(it::class == fade::class) }
        // Color spec: a tween (fade), duration <= 120 ms, linear easing.
        val colorTween = m.colorSpec as androidx.compose.animation.core.TweenSpec<Color>
        assertTrue(colorTween.durationMillis <= 120)
        assertEquals(androidx.compose.animation.core.LinearEasing, colorTween.easing)
    }

    @Test
    fun normalMotion_usesSprings_notFades() {
        val m = MotionScheme(reducedMotion = false)
        val spring = MotionScheme.springSpec()
        assertTrue(m.enterSpec::class == spring::class)
        assertTrue(m.pressSpec::class == spring::class)
    }

    @Test
    fun durationTokens_withinUxBudgets() {
        assertTrue(MotionScheme.FADE_MS <= 120)
        assertEquals(150, MotionScheme.MICRO_MS)
        assertEquals(250, MotionScheme.STANDARD_MS)
        assertEquals(300, MotionScheme.EXPRESSIVE_MS)
    }
}

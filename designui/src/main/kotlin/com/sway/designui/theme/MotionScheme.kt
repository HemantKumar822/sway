package com.sway.designui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

/**
 * Motion token mapping (story 9.1, NFR-6 substrate / UX §12): durations,
 * easings and springs in ONE place, with the reduced-motion law — any
 * animated token degrades to a short opacity-style fade <=120 ms.
 */
data class MotionScheme(
    val reducedMotion: Boolean,
) {
    /** Standard expressive enter (spring). Degrades under reduced motion. */
    val enterSpec: AnimationSpec<Float> =
        if (reducedMotion) fadeSpec() else springSpec()

    /** Scheme/color transitions: springy when allowed, quick fade otherwise. */
    val colorSpec: AnimationSpec<Color> =
        if (reducedMotion) {
            tween(durationMillis = FADE_MS, easing = LinearEasing)
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SPRING_STIFFNESS_COLOR)
        }

    /** Press/bounce feedback. */
    val pressSpec: AnimationSpec<Float> =
        if (reducedMotion) {
            fadeSpec()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        }

    companion object {
        /** P-5-style tunables (UX §12 durations). */
        const val FADE_MS = 100
        const val MICRO_MS = 150
        const val STANDARD_MS = 250
        const val EXPRESSIVE_MS = 300
        const val SPRING_STIFFNESS_COLOR = 400f

        /** M3 emphasized-ish curve for non-spring fades. */
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** THE reduced-motion law: linear opacity fade <=120 ms. */
        fun fadeSpec() = tween<Float>(durationMillis = FADE_MS, easing = LinearEasing)

        fun springSpec(
            dampingRatio: Float = Spring.DampingRatioLowBouncy,
            stiffness: Float = Spring.StiffnessMediumLow,
        ) = spring<Float>(dampingRatio = dampingRatio, stiffness = stiffness)
    }
}

package com.sway.designui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * SwayTheme — the single theme entry (story 9.1, UX §7.1 two-mode system).
 *
 * Mode resolution: DYNAMIC renders the artwork-derived scheme when a seed
 * exists; otherwise MONO "Ink & Paper" applies (default personality). Every
 * color role animates between schemes with the MotionScheme spec (springy by
 * default; reduced-motion degrades to a <=120 ms fade), so track changes
 * recolor the app as one fluid gesture rather than a snap.
 */
@Composable
fun SwayTheme(
    config: ThemeConfig = ThemeConfig(),
    dynamicSeed: PaletteExtractor.SeedColors? = null,
    content: @Composable () -> Unit,
) {
    val target: ColorScheme = when {
        config.mode == ThemeMode.DYNAMIC && dynamicSeed != null ->
            DynamicSchemeFactory.scheme(dynamicSeed.schemeSeed(), dark = config.darkTheme)
        else -> InkPaper.scheme(dark = config.darkTheme, amoledBlack = config.amoledBlack)
    }

    val motion = MotionScheme(config.reducedMotion)
    val scheme = target.animated(motion)

    MaterialTheme(
        colorScheme = scheme,
        typography = swayTypography(),
        shapes = SwayShapes.m3,
        content = content,
    )
}

/** Single-role animated binding. */
@Composable
private fun animatedColor(color: Color, spec: androidx.compose.animation.core.AnimationSpec<Color>): Color {
    val v by animateColorAsState(color, animationSpec = spec, label = "swayColor")
    return v
}

/** Per-role animation so scheme changes flow instead of snapping. */
@Composable
private fun ColorScheme.animated(motion: MotionScheme): ColorScheme = copy(
    primary = animatedColor(primary, motion.colorSpec),
        onPrimary = animatedColor(onPrimary, motion.colorSpec),
        primaryContainer = animatedColor(primaryContainer, motion.colorSpec),
        onPrimaryContainer = animatedColor(onPrimaryContainer, motion.colorSpec),
        inversePrimary = animatedColor(inversePrimary, motion.colorSpec),
        secondary = animatedColor(secondary, motion.colorSpec),
        onSecondary = animatedColor(onSecondary, motion.colorSpec),
        secondaryContainer = animatedColor(secondaryContainer, motion.colorSpec),
        onSecondaryContainer = animatedColor(onSecondaryContainer, motion.colorSpec),
        tertiary = animatedColor(tertiary, motion.colorSpec),
        onTertiary = animatedColor(onTertiary, motion.colorSpec),
        tertiaryContainer = animatedColor(tertiaryContainer, motion.colorSpec),
        onTertiaryContainer = animatedColor(onTertiaryContainer, motion.colorSpec),
        background = animatedColor(background, motion.colorSpec),
        onBackground = animatedColor(onBackground, motion.colorSpec),
        surface = animatedColor(surface, motion.colorSpec),
        onSurface = animatedColor(onSurface, motion.colorSpec),
        surfaceVariant = animatedColor(surfaceVariant, motion.colorSpec),
        onSurfaceVariant = animatedColor(onSurfaceVariant, motion.colorSpec),
        inverseSurface = animatedColor(inverseSurface, motion.colorSpec),
        inverseOnSurface = animatedColor(inverseOnSurface, motion.colorSpec),
        error = animatedColor(error, motion.colorSpec),
        onError = animatedColor(onError, motion.colorSpec),
        errorContainer = animatedColor(errorContainer, motion.colorSpec),
        onErrorContainer = animatedColor(onErrorContainer, motion.colorSpec),
        outline = animatedColor(outline, motion.colorSpec),
        outlineVariant = animatedColor(outlineVariant, motion.colorSpec),
        scrim = animatedColor(scrim, motion.colorSpec),
    )
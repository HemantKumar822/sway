package com.sway.designui.theme

/**
 * Theme configuration vocabulary (story 9.1, UX §7.1 owner amendment).
 *
 * [ThemeMode.MONO] — "Ink & Paper": Notion-philosophy monochrome default;
 * paper neutrals, ink primaries, hairlines over shadows. Color only means,
 * never decorates (rose = like, amber = caution).
 *
 * [ThemeMode.DYNAMIC] — artwork-driven: the whole app recolors from the
 * playing track's cover ([dynamicSeed] supplied by the palette extractor);
 * falls back to MONO whenever no seed exists (no artwork / extraction
 * failure / offline placeholder).
 */
enum class ThemeMode { MONO, DYNAMIC }

/**
 * @param mode      which personality renders
 * @param darkTheme system dark/light
 * @param amoledBlack MONO-only pure-black variant (surfaces collapse to #000)
 * @param reducedMotion accessibility override: every animated token degrades
 *   to an opacity-style fade <=120 ms (NFR-6/UX §12 motion law)
 */
data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.MONO,
    val darkTheme: Boolean = false,
    val amoledBlack: Boolean = false,
    val reducedMotion: Boolean = false,
)

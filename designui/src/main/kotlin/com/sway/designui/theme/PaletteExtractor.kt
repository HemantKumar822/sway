package com.sway.designui.theme

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/**
 * Extracts seed colors from artwork bitmaps (story 9.1, DYNAMIC mode; the
 * SuvMusic-style engine head). Bitmap LOADING from URLs arrives with the
 * Coil image pipeline (13.1) — this extractor consumes any decoded Bitmap,
 * which keeps it hermetically testable.
 *
 * Selection law: the VIBRANT swatch wins when present (music covers want
 * energy), else the most-populated non-black/non-white swatch (dominant).
 */
object PaletteExtractor {

    data class SeedColors(
        val dominant: Int,
        val vibrant: Int?,
        val muted: Int?,
    ) {
        /** The scheme seed: vibrant first, dominant as the reliable fallback. */
        fun schemeSeed(): Int = vibrant ?: dominant
    }

    fun extract(bitmap: Bitmap): SeedColors? {
        val palette = Palette.from(bitmap).maximumColorCount(24).clearFilters().generate()
        val dominant = palette.dominantSwatch ?: return null
        return SeedColors(
            dominant = dominant.rgb,
            vibrant = palette.vibrantSwatch?.rgb,
            muted = palette.mutedSwatch?.rgb,
        )
    }
}

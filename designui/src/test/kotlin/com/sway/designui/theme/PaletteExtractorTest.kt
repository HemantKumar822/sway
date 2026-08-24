package com.sway.designui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 9.1 — palette extraction laws (DYNAMIC mode engine head): dominant
 * swatch selection, vibrant preference, determinism, null-safety.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PaletteExtractorTest {

    private fun solidBitmap(color: Int, size: Int = 32): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
            val px = IntArray(size * size) { color }
            b.setPixels(px, 0, size, 0, 0, size, size)
        }

    @Test
    fun solidCrimson_dominantIsRedHued() {
        val crimson = AndroidColor.rgb(200, 30, 40)
        val seed = PaletteExtractor.extract(solidBitmap(crimson))
        assertNotNull(seed)
        val h: Float = seed!!.dominant.toHsl()[0]
        assertTrue("hue should sit in the red band, got $h", h < 25f || h > 335f)
    }

    @Test
    fun vibrantPreferred_overDominantWhenPresent() {
        // Half calm navy (dominant by area), half hot orange (vibrant).
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).also { b ->
            for (y in 0 until 20) for (x in 0 until 40) {
                b.setPixel(x, y, if (x < 30) AndroidColor.rgb(20, 30, 70) else AndroidColor.rgb(255, 120, 0))
            }
        }
        val seed = PaletteExtractor.extract(bmp)!!
        val vibrantHue: Float = seed.vibrant!!.let { it.toHsl()[0] }
        assertEquals(
            "seed should ride the vibrant swatch",
            vibrantHue,
            seed.schemeSeed().toHsl()[0],
            0.01f,
        )
    }

    @Test
    fun deterministic_sameBitmapSameSeed() {
        val bmp = solidBitmap(AndroidColor.rgb(90, 140, 220))
        val a = PaletteExtractor.extract(bmp)!!
        val b = PaletteExtractor.extract(bmp)!!
        assertEquals(a.dominant, b.dominant)
        assertEquals(a.schemeSeed(), b.schemeSeed())
    }

    @Test
    fun neutralCanvas_stillYieldsSeed_factoryKeepsContrast() {
        val seed = PaletteExtractor.extract(solidBitmap(AndroidColor.WHITE))!!
        val s = DynamicSchemeFactory.scheme(seed.schemeSeed(), dark = false)
        val lum = relativeLuminance(s.primary)
        val lumOn = relativeLuminance(s.onPrimary)
        val ratio = (maxOf(lum, lumOn) + 0.05f) / (minOf(lum, lumOn) + 0.05f)
        assertTrue("even neutral seeds honor the 3:1 floor ($ratio)", ratio >= 3.0f)
    }
}

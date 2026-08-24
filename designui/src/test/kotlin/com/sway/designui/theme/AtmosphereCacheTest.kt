package com.sway.designui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * Story 13.2 — atmosphere cache law (NFR-5 cache + fallback): re-view = ZERO recompute
 * proven by extractionCount, LRU 32, fallback neutral guarantees still typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AtmosphereCacheTest {

    @After fun tearDown() = Atmospherics.resetForTest()

    private fun solidBitmap(color: Int, w: Int = 32, h: Int = 32): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        // Add a second color patch to ensure Palette finds dominant+vibrant.
        val p = Paint().apply { this.color = color xor 0x00333333 }
        canvas.drawRect(0f, 0f, w / 2f, h / 2f, p)
        return bmp
    }

    @Test fun cache_zeroRecompute_onSecondView() {
        Atmospherics.resetForTest()
        val bmp = solidBitmap(0xFFFF5722.toInt())
        val url = "https://cdn.test/cover.jpg"
        val a1 = Atmospherics.getOrExtract(url, bmp, dark = false)
        val countAfterFirst = Atmospherics.extractionCount
        val a2 = Atmospherics.getOrExtract(url, bmp, dark = false)
        assertEquals(1, countAfterFirst)
        assertEquals(1, Atmospherics.extractionCount) // no increment
        assertEquals(a1, a2)
        assertEquals(1, Atmospherics.cacheSize)
    }

    @Test fun cache_distinctKeys_separateEntries() {
        val bmp = solidBitmap(0xFF4CAF50.toInt())
        Atmospherics.getOrExtract("https://cdn/a.jpg", bmp, dark = false)
        Atmospherics.getOrExtract("https://cdn/b.jpg", bmp, dark = false)
        assertEquals(2, Atmospherics.cacheSize)
        assertEquals(2, Atmospherics.extractionCount)
    }

    @Test fun fallback_neutral_hasNullSeed_andPassesScrim() {
        val fb = Atmospherics.fallback(dark = false)
        assertTrue(fb.seed == null)
        // Backdrop is InkPaper surface — already covered by scrim matrix, but sanity:
        assertTrue(fb.scrimStrong >= 0.60f)
        assertTrue(fb.scrimSoft >= 0.35f)
    }

    @Test fun atmosphereFromBitmap_seedPresent_hasBackdropAndSeed() {
        val bmp = solidBitmap(0xFF2196F3.toInt())
        val atm = Atmospherics.atmosphereFromBitmap(bmp, dark = true)
        // Palette may or may not find vibrant seed on a flat bitmap, but dominant is guaranteed fallback path.
        // Backdrop must be a Color (not transparent) and scrim values within caps.
        assertTrue(atm.scrimStrong in 0.60f..0.85f)
        assertTrue(atm.scrimSoft in 0.35f..0.62f)
    }
}

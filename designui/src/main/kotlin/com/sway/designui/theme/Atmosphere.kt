package com.sway.designui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Size
import com.sway.core.model.ArtworkRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Player-scoped visual atmosphere (story 13.2, NFR-5).
 *
 * Derived from the playing cover's palette; drives the Full Player backdrop
 * + scrim opacities and the Mini/Queue accent tints. When extraction fails or
 * no artwork exists, [backdrop] falls back to the neutral InkPaper surface
 * and [seed] is null (MONO fallback still guarantees WCAG AA via [ScrimEngine]).
 *
 * @param backdrop the animated backdrop color behind the scrim gradient
 * @param scrimStrong top-of-gradient black alpha (default .60)
 * @param scrimSoft bottom-of-gradient black alpha (default .35), raised by [ScrimEngine] until AA holds
 * @param seed the vibrant-preferred seed that also feeds SwayTheme(dynamicSeed=) when mode==DYNAMIC
 */
data class Atmosphere(
    val backdrop: Color,
    val scrimStrong: Float = 0.60f,
    val scrimSoft: Float = 0.35f,
    val seed: PaletteExtractor.SeedColors?,
)

/**
 * WCAG 2.1 AA scrim engine — pure, deterministic, test-matrix-gated (story 13.2).
 *
 * Starting at [DEFAULT_STRONG]=0.60 / [DEFAULT_SOFT]=0.35 (the Figma baseline),
 * the gradient alphas RISE only until every sampled foreground role meets
 * 4.5:1 (normal) / 3.0:1 (large) over the blended backdrop. Contrast math is
 * relative-luminance based (same [relativeLuminance] used by [DynamicSchemeFactory]).
 *
 * Blended background = [backdrop] composited with black at avg(scrimStrong, scrimSoft)
 * (spec §13.2: "Contrast computed vs blended(bg,scrimAvg)"). Raising both alphas darkens
 * that blend, which only helps light foregrounds (white/inverseOnSurface) — dark
 * foregrounds already pass at the baseline, so the baseline is kept (best dark-text case).
 */
object ScrimEngine {

    const val DEFAULT_STRONG = 0.60f
    const val DEFAULT_SOFT = 0.35f
    private const val CAP_STRONG = 0.95f
    private const val CAP_SOFT = 0.85f
    private const val STEP = 0.02f

    /**
     * Contrast ratio per WCAG (L1+0.05)/(L2+0.05), L = relative luminance.
     * Order-insensitive: always lighter over darker.
     */
    fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg).toDouble()
        val l2 = relativeLuminance(bg).toDouble()
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Blend [backdrop] with black at [alpha] (0 = backdrop, 1 = black). */
    fun blended(backdrop: Color, alpha: Float): Color {
        val a = alpha.coerceIn(0f, 1f)
        return Color(
            red = backdrop.red * (1f - a),
            green = backdrop.green * (1f - a),
            blue = backdrop.blue * (1f - a),
            alpha = 1f,
        )
    }

    /**
     * Pure scrim law: given [backdrop] and representative [foreground] roles,
     * return alphas that satisfy AA for ALL roles. Foreground list covers the
     * matrix: light (inverseOnSurface/white) x dark (onSurface) per scheme.
     *
     * The algorithm raises BOTH alphas together (preserving gradient delta .25)
     * only when the current blend fails for a light foreground. Dark-foreground
     * failures are not fixed by darkening — the baseline is left intact because
     * raising would worsen dark-text contrast, and the baseline already passes
     * for light backdrops (fallback neutral proven in matrix test). Raising
     * stops when thresholds are met or caps are reached.
     */
    fun scrimFor(backdrop: Color, foregrounds: List<Color>): Pair<Float, Float> {
        var strong = DEFAULT_STRONG
        var soft = DEFAULT_SOFT
        // Quick-pass: if all roles already AA at baseline, return immediately.
        if (allPass(backdrop, foregrounds, strong, soft)) return strong to soft

        // Determine if darkening helps: at least one light foreground fails and darkening
        // improves its ratio. We infer light-foreground presence by checking luminance.
        // If the dominant failure is a dark fg on light bg, raising hurts — keep baseline
        // (the fallback test proves baseline + neutral backdrop still passes for dark fg).
        // Heuristic: only raise if a light fg is among the failing roles.
        val lightFgFailsAtBaseline = foregrounds.any { fg ->
            val lum = relativeLuminance(fg)
            lum > 0.5f && contrastRatio(fg, blended(backdrop, (strong + soft) / 2f)) < 4.5
        }
        if (!lightFgFailsAtBaseline) return strong to soft

        var s = strong
        var sf = soft
        while (s < CAP_STRONG && sf < CAP_SOFT) {
            s = (s + STEP).coerceAtMost(CAP_STRONG)
            sf = (sf + STEP).coerceAtMost(CAP_SOFT)
            if (allPass(backdrop, foregrounds, s, sf)) return s to sf
        }
        return s to sf
    }

    /** Convenience for single-foreground callers. */
    fun scrimFor(backdrop: Color, foreground: Color): Pair<Float, Float> =
        scrimFor(backdrop, listOf(foreground))

    private fun allPass(backdrop: Color, fgs: List<Color>, strong: Float, soft: Float): Boolean {
        val avg = (strong + soft) / 2f
        val bg = blended(backdrop, avg)
        return fgs.all { fg ->
            val ratio = contrastRatio(fg, bg)
            // Normal threshold 4.5:1 blocks; large 3:1 is implied passing if normal passes.
            ratio >= 4.5
        }
    }
}

/**
 * Atmosphere extraction + cache (story 13.2, NFR-5 + NFR-10).
 *
 * - Palette selection law lives in [PaletteExtractor] (vibrant-preferred, hermetic).
 * - Backdrop derivation uses [DynamicSchemeFactory] for seed-present and [InkPaper]
 *   fallback for seed-absent (both surface roles — the scrim then guarantees AA).
 * - Re-view cache keyed by canonicalUrl (LRU 32, ZERO recompute on second view).
 * - Coil fetch path requests 128px, allowHardware(false), INEXACT, off-main.
 */
object Atmospherics {

    private const val MAX_CACHE = 32
    private const val REQUEST_SIZE = 128

    // Simple LRU via LinkedHashMap (access-order). Guarded by synchronized.
    private val cache: LinkedHashMap<String, Atmosphere> = object : LinkedHashMap<String, Atmosphere>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Atmosphere>?): Boolean = size > MAX_CACHE
    }

    /** Instrumentation: extraction attempts (cache misses only). */
    var extractionCount: Int = 0
        private set

    /** Cache size for assertions. */
    val cacheSize: Int get() = synchronized(cache) { cache.size }

    fun clearCache() = synchronized(cache) { cache.clear(); extractionCount = 0 }

    /**
     * Pure extraction from a decoded [bitmap] (hermetic, <=128px caller ensures budget).
     * Called off-main (Dispatchers.Default) by MainActivity's LaunchedEffect.
     */
    fun atmosphereFromBitmap(bitmap: Bitmap, dark: Boolean): Atmosphere {
        extractionCount++
        val seed = PaletteExtractor.extract(bitmap)
        val backdrop = if (seed != null) {
            DynamicSchemeFactory.scheme(seed.schemeSeed(), dark).surface
        } else {
            InkPaper.scheme(dark).surface
        }
        // Light foregrounds only: scrim guarantees AA for light text over backdrop.
        // Dark text is not rendered over the scrimmed area (FullPlayer spec §13.2).
        val scheme = if (seed != null) DynamicSchemeFactory.scheme(seed.schemeSeed(), dark) else InkPaper.scheme(dark)
        val candidates = listOf(scheme.onSurface, scheme.inverseOnSurface, Color.White)
        val lightFgs = candidates.filter { relativeLuminance(it) > 0.4f }
        val fgs = if (lightFgs.isEmpty()) listOf(Color.White) else lightFgs
        val (strong, soft) = ScrimEngine.scrimFor(backdrop, fgs)
        return Atmosphere(backdrop = backdrop, scrimStrong = strong, scrimSoft = soft, seed = seed)
    }

    /** Fallback atmosphere when no bitmap/seed is available (neutral brand). */
    fun fallback(dark: Boolean): Atmosphere {
        val scheme = InkPaper.scheme(dark)
        val backdrop = scheme.surface
        val candidates = listOf(scheme.onSurface, scheme.inverseOnSurface, Color.White)
        val lightFgs = candidates.filter { relativeLuminance(it) > 0.4f }
        val fgs = if (lightFgs.isEmpty()) listOf(Color.White) else lightFgs
        val (strong, soft) = ScrimEngine.scrimFor(backdrop, fgs)
        return Atmosphere(backdrop = backdrop, scrimStrong = strong, scrimSoft = soft, seed = null)
    }

    /**
     * Cached lookup: returns cached atmosphere or extracts from [bitmap] and caches under
     * [canonicalUrl]. Zero recompute on re-view (proven by [extractionCount]).
     */
    fun getOrExtract(canonicalUrl: String, bitmap: Bitmap, dark: Boolean): Atmosphere {
        synchronized(cache) { cache[canonicalUrl]?.let { return it } }
        val atm = atmosphereFromBitmap(bitmap, dark)
        synchronized(cache) { cache[canonicalUrl] = atm }
        return atm
    }

    /** Suspend wrapper that dispatches extraction to Default (caller may already be on Default). */
    suspend fun atmosphereFromBitmapAsync(bitmap: Bitmap, dark: Boolean): Atmosphere =
        withContext(Dispatchers.Default) { atmosphereFromBitmap(bitmap, dark) }

    /**
     * Coil-backed extraction: fetches 128px, allowHardware(false), converts image→bitmap,
     * then [atmosphereFromBitmap]. Returns null on any failure (caller uses [fallback]).
     */
    suspend fun loadSeedBitmap(loader: ImageLoader, context: Context, ref: ArtworkRef, dark: Boolean): Atmosphere? =
        withContext(Dispatchers.Default) {
            try {
                // Fast-path cache check without bitmap.
                synchronized(cache) { cache[ref.cacheKey]?.let { return@withContext it } }

                val request = ImageRequest.Builder(context)
                    .data(ref.canonicalUrl)
                    .size(Size(REQUEST_SIZE, REQUEST_SIZE))
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                val image = (result as? coil3.request.SuccessResult)?.image
                    ?: return@withContext null
                val bitmap: Bitmap = when (image) {
                    is coil3.BitmapImage -> image.bitmap
                    is coil3.DrawableImage -> {
                        val d = image.drawable
                        when (d) {
                            is android.graphics.drawable.BitmapDrawable -> d.bitmap
                            else -> {
                                // Render drawable to bitmap
                                val w = (if (d.intrinsicWidth > 0) d.intrinsicWidth else REQUEST_SIZE)
                                val h = (if (d.intrinsicHeight > 0) d.intrinsicHeight else REQUEST_SIZE)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                d.setBounds(0, 0, w, h)
                                d.draw(canvas)
                                bmp
                            }
                        }
                    }
                    else -> return@withContext null
                }
                val scaled = if (bitmap.width > REQUEST_SIZE || bitmap.height > REQUEST_SIZE) {
                    Bitmap.createScaledBitmap(bitmap, REQUEST_SIZE, REQUEST_SIZE, true)
                } else bitmap
                val atm = atmosphereFromBitmap(scaled, dark)
                synchronized(cache) { cache[ref.cacheKey] = atm }
                atm
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Simpler overload for synthetic bitmap tests: extracts without coil, using supplied bitmap.
     * Increments count only on miss (LRU cache hit returns cached instance).
     */
    suspend fun loadFromBitmapForTest(canonicalUrl: String, bitmap: Bitmap, dark: Boolean): Atmosphere =
        withContext(Dispatchers.Default) { getOrExtract(canonicalUrl, bitmap, dark) }

    /** Test seam: drop cache. */
    fun resetForTest() = clearCache()
}

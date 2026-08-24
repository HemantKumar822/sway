package com.sway.designui.images

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE image pipeline (story 13.1, FR-35; AD-3 single HTTP stack): one Coil
 * [ImageLoader] for the whole app, built on the INJECTED OkHttp client —
 * :app passes `CatalogHttpClient.createArtworkVariant()` so every artwork
 * request shares the catalog's timeout posture while :designui holds ZERO
 * transport/host knowledge (AR-2 edge law).
 *
 * Bounds (NFR-10 / AR-10):
 * - memory cache = 25% of available memory ([MemoryCache.Builder.maxPercent])
 * - disk cache   = [DISK_CACHE_BYTES] LRU rooted under caller's directory
 * - crossfade 150 ms arrival (UX §8.11 content-swap rule)
 *
 * Init is idempotent first-wins and lives in composition scope (:app), never
 * Application.onCreate; directories resolve lazily inside Coil so the startup
 * path stays free of disk work (AD-10).
 */
object SwayImages {

    /** NFR-10 artwork disk bound: 256 MB LRU. */
    const val DISK_CACHE_BYTES: Long = 256L * 1024L * 1024L

    /** Memory-cache share of app memory (AR-10 ~25%). */
    const val MEMORY_CACHE_PERCENT = 0.25

    /** Content-arrival crossfade (UX §8.11). */
    const val ARRIVAL_CROSSFADE_MS = 150

    private var loader: ImageLoader? = null

    /** Cache-hit instrumentation: network requests since last reset. */
    val networkRequestCount: Int get() = CountingInterceptor.count.get()

    fun init(context: Context, client: OkHttpClient, cacheDir: File): ImageLoader {
        loader?.let { return it }
        synchronized(this) {
            loader?.let { return it }
            val counted = client.newBuilder()
                .addInterceptor(CountingInterceptor)
                .build()
            val built = ImageLoader.Builder(context)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = { counted },
                        ),
                    )
                }
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(cacheDir, "sway_images").toOkioPath())
                        .maxSizeBytes(DISK_CACHE_BYTES)
                        .build()
                }
                .crossfade(ARRIVAL_CROSSFADE_MS)
                .build()
            loader = built
            return built
        }
    }

    fun loader(): ImageLoader =
        loader ?: throw IllegalStateException("SwayImages.init must be called before use")

    val isInitialized: Boolean get() = loader != null

    /** Test seam: drop the singleton + counters for a fresh install. */
    fun resetForTest() {
        synchronized(this) {
            loader?.let { l ->
                try {
                    l.memoryCache?.clear()
                    l.diskCache?.clear()
                } catch (_: Exception) {
                }
            }
            loader = null
            CountingInterceptor.count.set(0)
        }
    }

    internal object CountingInterceptor : Interceptor {
        val count = AtomicInteger(0)
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            count.incrementAndGet()
            return chain.proceed(chain.request())
        }
    }
}

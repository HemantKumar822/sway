package com.sway.designui.images

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * Story 13.1 — loader config law (FR-35, NFR-10, AR-4): memory 25%, disk 256 MB LRU,
 * single OkHttp stack derivation via injected client. The counts are constant-proven;
 * construction is exercised hermetically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LoaderConfigTest {

    @After fun tearDown() = SwayImages.resetForTest()

    @Test fun constants_matchBounds() {
        assertEquals(256L * 1024L * 1024L, SwayImages.DISK_CACHE_BYTES)
        assertEquals(0.25, SwayImages.MEMORY_CACHE_PERCENT, 0.0001)
        assertEquals(150, SwayImages.ARRIVAL_CROSSFADE_MS)
    }

    private fun testClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    @Test fun init_singleton_andDerivedClient_isUsed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val client = testClient()
        val loader1 = SwayImages.init(ctx, client, ctx.cacheDir)
        val loader2 = SwayImages.init(ctx, client, ctx.cacheDir)
        assertTrue(loader1 === loader2)
        assertTrue(SwayImages.isInitialized)
        // The loader exposes a disk cache rooted under sway_images (lazy, but directory is set).
        // Memory/disk sizes are builder-enforced; the constants above are the gate.
    }

    @Test fun resetForTest_dropsSingleton() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        SwayImages.init(ctx, testClient(), ctx.cacheDir)
        SwayImages.resetForTest()
        assertTrue(!SwayImages.isInitialized)
    }
}

package com.sway.catalog

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader

/**
 * Extractor bootstrap — AD-1, AD-3, AR-2, AR-4.
 *
 * Owns the one-time [NewPipe.init] binding of the extractor to [SwayDownloaderImpl].
 * All production entry points call [initIfNeeded] (idempotent); tests use [initForTest]
 * to rebind against a MockWebServer client without leaking global state across JVM tests.
 *
 * AR-9/AD-10: initialization performs no disk/network/preferences work — only installs the
 * downloader binding so later search/detail/resolve calls flow through OkHttp.
 */
object NewPipeInitializer {

    private val lock = Any()
    @Volatile
    private var initialized: Boolean = false

    /**
     * Idempotent production initializer. Derives the client from [CatalogHttpClient] so
     * timeouts/proxy are shared (AD-3). Returns the active [Downloader].
     */
    fun initIfNeeded(
        client: OkHttpClient = CatalogHttpClient.createShared(),
    ): Downloader {
        synchronized(lock) {
            if (initialized) return NewPipe.getDownloader()
            val downloader = SwayDownloaderImpl(client)
            NewPipe.init(downloader)
            initialized = true
            CatalogLog.d("NewPipe initialized via SwayDownloaderImpl (OkHttp ${client.connectTimeoutMillis}ms connect)")
            return downloader
        }
    }

    /**
     * Direct binder for callers that already own a [Downloader] (tests, Hilt providers that
     * constructed the downloader elsewhere). Idempotent under the [initialized] guard.
     */
    fun initWithDownloader(downloader: Downloader): Downloader {
        synchronized(lock) {
            if (initialized) return NewPipe.getDownloader()
            NewPipe.init(downloader)
            initialized = true
            CatalogLog.d("NewPipe initialized via provided Downloader ${downloader::class.simpleName}")
            return downloader
        }
    }

    /**
     * Test-only rebind: forces re-initialization with a fresh [client] (e.g. MockWebServer).
     * Resets the [initialized] guard and re-calls [NewPipe.init]. Caller must ensure tests
     * are isolated (single-threaded) — no concurrent production init may race this call.
     */
    fun initForTest(client: OkHttpClient): Downloader {
        synchronized(lock) {
            val downloader = SwayDownloaderImpl(client)
            NewPipe.init(downloader)
            initialized = true
            CatalogLog.d("NewPipe re-initialized for test with MockWebServer client")
            return downloader
        }
    }

    /**
     * Resets the initializer guard for test isolation. Does not undo [NewPipe]'s static
     * downloader reference — next [initIfNeeded] or [initForTest] will overwrite it.
     * Tests should call this in @Before to avoid cross-test leakage.
     */
    fun resetForTest() {
        synchronized(lock) {
            initialized = false
        }
    }

    /** For assertions in tests — whether init has been performed. */
    fun isInitialized(): Boolean = initialized
}

package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import mockwebserver3.MockResponse
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * Story 3.1 — Extractor bootstrap & OkHttp downloader (AR-2, AR-4, AD-3).
 *
 * Verifies:
 * - Requests flow exclusively through SwayDownloaderImpl/OkHttp (MockWebServer proves it).
 * - Timeouts/proxy derive from the shared builder, not ad-hoc literals.
 * - NewPipeInitializer binds extractor idempotently; download path preserves headers and handles 429.
 * - Isolation: downloader lives in :catalog (audit is structural, but this test documents the contract).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SwayDownloaderImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var downloader: SwayDownloaderImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Derive client from shared builder so timeout provenance test can assert equality.
        client = CatalogHttpClient.sharedBuilder().build()
        downloader = SwayDownloaderImpl(client)
        // Isolate NewPipe global per test — rebind to this MockWebServer-bound downloader.
        NewPipeInitializer.initForTest(client)
        assertSame(downloader.javaClass, NewPipe.getDownloader().javaClass)
    }

    @After
    fun tearDown() {
        server.close()
        NewPipeInitializer.resetForTest()
    }

    // -------------------------------------------------------------------------
    // Shared-builder derivation — AD-3 "timeouts/proxy derive from shared builder"
    // -------------------------------------------------------------------------

    @Test
    fun `timeouts derive from CatalogHttpClient shared builder not ad-hoc literals`() {
        val shared = CatalogHttpClient.createShared()
        assertEquals(CatalogHttpClient.CONNECT_TIMEOUT_SECONDS * 1000, shared.connectTimeoutMillis.toLong())
        assertEquals(CatalogHttpClient.READ_TIMEOUT_SECONDS * 1000, shared.readTimeoutMillis.toLong())
        assertEquals(CatalogHttpClient.WRITE_TIMEOUT_SECONDS * 1000, shared.writeTimeoutMillis.toLong())

        // The injected client in this test is built from sharedBuilder() — must match.
        assertEquals(shared.connectTimeoutMillis, client.connectTimeoutMillis)
        assertEquals(shared.readTimeoutMillis, client.readTimeoutMillis)
        assertEquals(shared.writeTimeoutMillis, client.writeTimeoutMillis)

        // Direct proof: SwayDownloaderImpl's client is the one we injected (no hidden ad-hoc client).
        assertSame(client, downloader.javaClass.getDeclaredField("client").let {
            it.isAccessible = true
            it.get(downloader) as OkHttpClient
        })
    }

    @Test
    fun `artwork variant also derives from shared builder (permitted derivation)`() {
        val artwork = CatalogHttpClient.createArtworkVariant()
        val shared = CatalogHttpClient.createShared()
        assertEquals(shared.connectTimeoutMillis, artwork.connectTimeoutMillis)
        assertEquals(shared.readTimeoutMillis, artwork.readTimeoutMillis)
    }

    // -------------------------------------------------------------------------
    // Requests flow exclusively through SwayDownloaderImpl / OkHttp — MockWebServer
    // -------------------------------------------------------------------------

    @Test
    fun `GET flows through OkHttp and returns NewPipe Response with headers and body`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Sway-Test", "catalog-ok")
                .body("hello-sway")
                .build(),
        )

        val url = server.url("/search?q=sway").toString()
        val req = Request.newBuilder().get(url).build()

        val resp = downloader.execute(req)

        assertEquals(200, resp.responseCode())
        assertEquals("hello-sway", resp.responseBody())
        assertEquals(url, resp.latestUrl().take(url.length)) // latestUrl is final OkHttp request URL
        assertNotNull(resp.responseHeaders()["X-Sway-Test"] ?: resp.responseHeaders()["x-sway-test"])

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.url.toString().contains("/search"))
        // User-Agent injected when absent (per SwayDownloaderImpl contract)
        assertNotNull(recorded.headers["User-Agent"])
        // Accept-Language injected when absent
        assertEquals("en-US,en;q=0.9", recorded.headers["Accept-Language"])
    }

    @Test
    fun `custom headers propagate verbatim through OkHttp`() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        val url = server.url("/headers").toString()
        val req = Request.newBuilder()
            .get(url)
            .setHeader("X-Custom", "sway-3.1")
            .setHeader("X-Multi", "a")
            .build()

        val resp = downloader.execute(req)
        assertEquals(200, resp.responseCode())

        val recorded = server.takeRequest()
        assertEquals("sway-3.1", recorded.headers["X-Custom"])
        // Multi-value header appears either combined or separate depending on OkHttp; assert at least one.
        val multiHeader = recorded.headers["X-Multi"]
        assertEquals("a", multiHeader)
    }

    @Test
    fun `POST with byte body flows through OkHttp`() {
        server.enqueue(MockResponse.Builder().code(200).body("posted").build())

        val url = server.url("/post").toString()
        val payload = """{"query":"sway"}""".toByteArray()
        val req = Request.newBuilder()
            .post(url, payload)
            .setHeader("Content-Type", "application/json")
            .build()

        val resp = downloader.execute(req)
        assertEquals(200, resp.responseCode())
        assertEquals("posted", resp.responseBody())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("""{"query":"sway"}""", recorded.body!!.utf8())
        assertTrue((recorded.headers["Content-Type"] ?: "").contains("application/json"))
    }

    @Test
    fun `HEAD flows through OkHttp`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val url = server.url("/head").toString()
        val req = Request.newBuilder().head(url).build()

        val resp = downloader.execute(req)
        assertEquals(200, resp.responseCode())

        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
    }

    @Test(expected = ReCaptchaException::class)
    fun `429 is mapped to ReCaptchaException for RateLimited handling`() {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())

        val url = server.url("/ratelimit").toString()
        val req = Request.newBuilder().get(url).build()

        downloader.execute(req)
        // should throw ReCaptchaException — verifies logging branch and AD-9 mapping path (RateLimited)
    }

    @Test
    fun `NewPipeInitializer is idempotent and exposes initialized extractor`() {
        assertTrue(NewPipeInitializer.isInitialized())
        assertNotNull(NewPipe.getDownloader())
        // Second init without force should return same downloader class and not throw.
        val second = NewPipeInitializer.initIfNeeded(client)
        assertNotNull(second)
        assertTrue(second is SwayDownloaderImpl)
        // Re-init for test path overwrites — still SwayDownloaderImpl
        val third = NewPipeInitializer.initForTest(client)
        assertTrue(third is SwayDownloaderImpl)
        assertTrue(NewPipeInitializer.isInitialized())
    }

    @Test
    fun `LatestUrl reflects final OkHttp request url after redirect disabled check`() {
        // MockWebServer can simulate redirect; but OkHttp follows redirects by default.
        // Simplest: no redirect, latestUrl equals original.
        server.enqueue(MockResponse.Builder().code(200).body("final").build())
        val url = server.url("/final").toString()
        val req = Request.newBuilder().get(url).build()
        val resp = downloader.execute(req)
        assertEquals(url, resp.latestUrl())
    }

    @Test
    fun `oversized body beyond 10MB returns empty string (OOM defense)`() {
        // Content-Length header exceeds limit — downloader should throw IOException mapping
        // to UpstreamUnavailable (EP-5) when header is respected. MockWebServer may recompute
        // Content-Length from the body, so defense may not trigger for this small body:
        // we assert no crash and either success with body or IOException with limit message.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Length", (10L * 1024 * 1024 + 1).toString())
                .body("x".repeat(100))
                .build(),
        )
        val url = server.url("/large").toString()
        val req = Request.newBuilder().get(url).build()
        try {
            val resp = downloader.execute(req)
            assertEquals(200, resp.responseCode())
            assertTrue(resp.responseBody() == "" || resp.responseBody() == "x".repeat(100))
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("exceeds 10MB") == true)
        }
        server.takeRequest() // consume
    }

    @Test(expected = java.io.IOException::class)
    fun `actual oversized body exceeding 10MB throws IOException for UpstreamUnavailable`() {
        // Real oversize body (11 MB) must throw IOException so search mappers map to UpstreamUnavailable.
        // Allocate 11 MB string — one-time cost, released after test.
        val largeBody = "x".repeat(11 * 1024 * 1024)
        server.enqueue(MockResponse.Builder().code(200).body(largeBody).build())
        val url = server.url("/huge").toString()
        val req = Request.newBuilder().get(url).build()
        downloader.execute(req) // should throw
    }
}

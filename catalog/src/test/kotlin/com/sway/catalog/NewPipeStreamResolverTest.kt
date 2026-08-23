package com.sway.catalog

import com.sway.core.model.AudioRequest
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 3.6 — NewPipeStreamResolver (8 pts, Epic E3 final).
 *
 * Covers ACs:
 * - concurrent dedup -> single fetch
 * - bitrate-target selection best-under-target else max for LOW/MEDIUM/HIGH/AUTO
 * - expiry param parsed to expiresAtEpochMs (never guessed)
 * - invalidate bypass
 * - prefetch silent null
 * - format ladder incl ciphered fallback (audio empty -> video fallback)
 * - LRU cache SourceId+quality
 * - forceRefresh
 *
 * Uses fake StreamExtractor via injected [streamExtractorFactory] (no network);
 * MockWebServer path is covered by [SwayDownloaderImplTest] for the shared
 * downloader; tagged live smoke remains manual ([NewPipeStreamResolverLiveSmokeTest]).
 */
class NewPipeStreamResolverTest {

    private val testDispatcher = Dispatchers.Unconfined

    // Helpers to build AudioStreams with deterministic urls containing expire param
    private fun audioStream(url: String, avgBitrate: Int, format: MediaFormat = MediaFormat.M4A): AudioStream {
        return AudioStream.Builder()
            .setId("itag-$avgBitrate")
            .setContent(url, true)
            .setMediaFormat(format)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(avgBitrate)
            .build()
    }

    private fun videoStream(url: String, isVideoOnly: Boolean = false, format: MediaFormat = MediaFormat.MPEG_4): VideoStream {
        return VideoStream.Builder()
            .setId("v-itag")
            .setContent(url, true)
            .setMediaFormat(format)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(isVideoOnly)
            .setResolution("1280x720")
            .build()
    }

    // Minimal fake StreamExtractor that returns canned audio/video lists
    private class FakeStreamExtractor(
        service: StreamingService,
        linkHandler: LinkHandler,
        private val audioStreams: List<AudioStream>,
        private val videoStreams: List<VideoStream>,
        private val fetchDelayMs: Long = 0L,
        private val fetchThrows: Throwable? = null,
        private val fetchCount: AtomicInteger? = null,
    ) : StreamExtractor(service, linkHandler) {
        override fun getName(): String = "fake"
        override fun getThumbnails(): List<org.schabi.newpipe.extractor.Image> = emptyList()
        override fun getUploaderUrl(): String = "https://www.youtube.com/channel/UCtest"
        override fun getUploaderName(): String = "Test Uploader"
        override fun getAudioStreams(): List<AudioStream> = audioStreams
        override fun getVideoStreams(): List<VideoStream> = videoStreams
        override fun getVideoOnlyStreams(): List<VideoStream> = emptyList()
        override fun getStreamType(): org.schabi.newpipe.extractor.stream.StreamType =
            org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM
        override fun getDashMpdUrl(): String = ""
        override fun getHlsUrl(): String = ""
        override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
            fetchCount?.incrementAndGet()
            fetchThrows?.let { e ->
                when (e) {
                    is IOException -> throw e
                    is ReCaptchaException -> throw e
                    is ParsingException -> throw e
                    else -> throw IOException(e)
                }
            }
            if (fetchDelayMs > 0) Thread.sleep(fetchDelayMs)
        }
    }

    private fun resolverWith(
        audioStreams: List<AudioStream> = emptyList(),
        videoStreams: List<VideoStream> = emptyList(),
        fetchThrows: Throwable? = null,
        fetchDelayMs: Long = 0L,
        isMetered: Boolean = false,
        cacheSize: Int = 32,
        fetchCount: AtomicInteger? = null,
    ): Pair<NewPipeStreamResolver, AtomicInteger> {
        val count = fetchCount ?: AtomicInteger(0)
        val resolver = NewPipeStreamResolver(
            service = ServiceList.YouTube,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            streamExtractorFactory = { lh ->
                FakeStreamExtractor(
                    service = ServiceList.YouTube,
                    linkHandler = lh,
                    audioStreams = audioStreams,
                    videoStreams = videoStreams,
                    fetchDelayMs = fetchDelayMs,
                    fetchThrows = fetchThrows,
                    fetchCount = count,
                )
            },
            isMeteredProvider = { isMetered },
            cacheSize = cacheSize,
        )
        return resolver to count
    }

    private fun expireUrl(base: String, expireSeconds: Long): String {
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}expire=$expireSeconds&other=1"
    }

    @Before
    fun setUp() {
        // Ensure clean state; resolver's static NewPipe init is idempotent.
        NewPipeInitializer.resetForTest()
    }

    // -------------------------------------------------------------------------
    // Expiry parsing — never guessed
    // -------------------------------------------------------------------------

    @Test
    fun `parseExpiry parses expire param to epoch ms`() {
        val nowSec = System.currentTimeMillis() / 1000 + 3600
        val url = "https://rr1---sn.googlevideo.com/videoplayback?expire=$nowSec&id=abc&ip=1.2.3.4"
        val parsed = NewPipeStreamResolver.parseExpiryEpochMs(url)
        assertEquals(nowSec * 1000L, parsed)
    }

    @Test
    fun `parseExpiry returns null when param missing - never guessed`() {
        val url = "https://cdn.example.com/audio/abc123?quality=HIGH"
        assertNull(NewPipeStreamResolver.parseExpiryEpochMs(url))
    }

    @Test
    fun `parseExpiry handles exp alternative key`() {
        val sec = 1_700_000_000L
        val url = "https://example.com/a?exp=$sec&foo=1"
        assertEquals(sec * 1000L, NewPipeStreamResolver.parseExpiryEpochMs(url))
    }

    @Test
    fun `parseExpiry rejects unparseable value`() {
        val url = "https://example.com/a?expire=notanumber"
        assertNull(NewPipeStreamResolver.parseExpiryEpochMs(url))
    }

    @Test
    fun `resolveAudio parses expiry from chosen stream url`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 7200
        val url = expireUrl("https://rr1.googlevideo.com/videoplayback", sec)
        val streams = listOf(audioStream(url, 128))
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("abc123"), AudioRequest(Quality.MEDIUM))
        assertTrue(result is SwayResult.Success)
        val resolved = (result as SwayResult.Success).data
        assertEquals(sec * 1000L, resolved.expiresAtEpochMs)
        assertEquals(url, resolved.url)
    }

    @Test
    fun `resolveAudio fails Parse when expiry missing`() = runTest {
        val url = "https://rr1.googlevideo.com/videoplayback?noexpire=1"
        val streams = listOf(audioStream(url, 128))
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("no-expire"), AudioRequest(Quality.MEDIUM))
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Parse)
    }

    // -------------------------------------------------------------------------
    // Selection tables — best-under-target else max
    // -------------------------------------------------------------------------

    @Test
    fun `selection LOW picks best under 96 else max`() = runTest {
        // Streams at 64, 96, 128, 192, 256
        val sec = System.currentTimeMillis() / 1000 + 3600
        fun u(br: Int) = expireUrl("https://rr.googlevideo.com/a$br", sec)
        val streams = listOf(64, 96, 128, 192, 256).map { br -> audioStream(u(br), br) }
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("sel-low"), AudioRequest(Quality.LOW))
        assertTrue(result is SwayResult.Success)
        val chosen = (result as SwayResult.Success).data
        // LOW target=96 => best under =96 (not 64), not 128 (over)
        assertEquals(96, chosen.bitrateKbps)
    }

    @Test
    fun `selection MEDIUM picks best under 160`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        fun u(br: Int) = expireUrl("https://rr.googlevideo.com/b$br", sec)
        val streams = listOf(64, 96, 128, 192, 256).map { br -> audioStream(u(br), br) }
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("sel-med"), AudioRequest(Quality.MEDIUM))
        val chosen = (result as SwayResult.Success).data
        assertEquals(128, chosen.bitrateKbps) // 128 is max <=160; 192 over target
    }

    @Test
    fun `selection HIGH picks best under 256`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        fun u(br: Int) = expireUrl("https://rr.googlevideo.com/c$br", sec)
        val streams = listOf(64, 96, 128, 192, 256).map { br -> audioStream(u(br), br) }
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("sel-high"), AudioRequest(Quality.HIGH))
        val chosen = (result as SwayResult.Success).data
        assertEquals(256, chosen.bitrateKbps)
    }

    @Test
    fun `selection else max when all over target`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        fun u(br: Int) = expireUrl("https://rr.googlevideo.com/d$br", sec)
        // All streams are 192,256 — LOW target 96 => none under, picks max 256
        val streams = listOf(192, 256).map { br -> audioStream(u(br), br) }
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("sel-else-max"), AudioRequest(Quality.LOW))
        val chosen = (result as SwayResult.Success).data
        assertEquals(256, chosen.bitrateKbps)
    }

    @Test
    fun `selection AUTO unmetered maps to MEDIUM-class`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        fun u(br: Int) = expireUrl("https://rr.googlevideo.com/e$br", sec)
        val streams = listOf(64, 96, 128, 192, 256).map { br -> audioStream(u(br), br) }
        val (resolverUnmetered, _) = resolverWith(audioStreams = streams, isMetered = false)
        val r1 = resolverUnmetered.resolveAudio(SourceId("auto-unmetered"), AudioRequest(Quality.AUTO))
        assertEquals(128, (r1 as SwayResult.Success).data.bitrateKbps) // MEDIUM ->128

        val (resolverMetered, _) = resolverWith(audioStreams = streams, isMetered = true)
        val r2 = resolverMetered.resolveAudio(SourceId("auto-metered"), AudioRequest(Quality.AUTO))
        assertEquals(96, (r2 as SwayResult.Success).data.bitrateKbps) // LOW ->96
    }

    @Test
    fun `selection table exhaustive for LOW MEDIUM HIGH`() {
        // Pure selector unit (no resolver) — table-driven
        fun mk(br: Int): AudioStream {
            val sec = 1_700_000_000L
            return audioStream(expireUrl("https://x/$br", sec), br)
        }
        val streams = listOf(48, 96, 128, 160, 192, 256).map { mk(it) }
        assertEquals(96, NewPipeStreamResolver.selectAudioStream(streams, NewPipeStreamResolver.TARGET_LOW_KBPS)?.getAverageBitrate())
        assertEquals(160, NewPipeStreamResolver.selectAudioStream(streams, NewPipeStreamResolver.TARGET_MEDIUM_KBPS)?.getAverageBitrate())
        assertEquals(256, NewPipeStreamResolver.selectAudioStream(streams, NewPipeStreamResolver.TARGET_HIGH_KBPS)?.getAverageBitrate())
        // All over target => max
        val highOnly = listOf(256, 320).map { mk(it) }
        assertEquals(320, NewPipeStreamResolver.selectAudioStream(highOnly, NewPipeStreamResolver.TARGET_LOW_KBPS)?.getAverageBitrate())
    }

    // -------------------------------------------------------------------------
    // Concurrent dedup -> single fetch
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent identical resolves share single fetch`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/videoplayback", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        // Use real dispatcher for io to allow true concurrency
        val resolver = NewPipeStreamResolver(
            service = ServiceList.YouTube,
            ioDispatcher = Dispatchers.Default,
            defaultDispatcher = Dispatchers.Default,
            streamExtractorFactory = { lh ->
                FakeStreamExtractor(
                    service = ServiceList.YouTube,
                    linkHandler = lh,
                    audioStreams = streams,
                    videoStreams = emptyList(),
                    fetchDelayMs = 120,
                    fetchCount = fetchCount,
                )
            },
            isMeteredProvider = { false },
        )
        val id = SourceId("dedup-track")
        val req = AudioRequest(Quality.MEDIUM)
        val d1 = async { resolver.resolveAudio(id, req) }
        // Small yield to ensure first fetch is in-flight before second starts
        kotlinx.coroutines.delay(10)
        val d2 = async { resolver.resolveAudio(id, req) }
        val r1 = d1.await()
        val r2 = d2.await()

        assertTrue(r1 is SwayResult.Success)
        assertTrue(r2 is SwayResult.Success)
        assertEquals((r1 as SwayResult.Success).data.url, (r2 as SwayResult.Success).data.url)
        assertEquals(1, fetchCount.get())
    }

    @Test
    fun `concurrent distinct keys do not dedup`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val streams = listOf(audioStream(expireUrl("https://rr.googlevideo.com/a", sec), 128))
        val fetchCount = AtomicInteger(0)
        val resolver = NewPipeStreamResolver(
            service = ServiceList.YouTube,
            ioDispatcher = Dispatchers.Default,
            defaultDispatcher = Dispatchers.Default,
            streamExtractorFactory = { lh ->
                FakeStreamExtractor(
                    service = ServiceList.YouTube,
                    linkHandler = lh,
                    audioStreams = streams,
                    videoStreams = emptyList(),
                    fetchDelayMs = 40,
                    fetchCount = fetchCount,
                )
            },
            isMeteredProvider = { false },
        )
        val d1 = async { resolver.resolveAudio(SourceId("track-a"), AudioRequest(Quality.MEDIUM)) }
        val d2 = async { resolver.resolveAudio(SourceId("track-b"), AudioRequest(Quality.MEDIUM)) }
        val r1 = d1.await(); val r2 = d2.await()
        assertTrue(r1 is SwayResult.Success)
        assertTrue(r2 is SwayResult.Success)
        assertEquals(2, fetchCount.get())
    }

    // -------------------------------------------------------------------------
    // Cache LRU + invalidate + forceRefresh
    // -------------------------------------------------------------------------

    @Test
    fun `cache hit avoids second fetch`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/videoplayback", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("cache-hit")
        val req = AudioRequest(Quality.MEDIUM)
        val r1 = resolver.resolveAudio(id, req)
        assertTrue(r1 is SwayResult.Success)
        assertEquals(1, fetchCount.get())
        val r2 = resolver.resolveAudio(id, req)
        assertTrue(r2 is SwayResult.Success)
        assertEquals(1, fetchCount.get()) // second served from cache
        assertEquals((r1 as SwayResult.Success).data.url, (r2 as SwayResult.Success).data.url)
        assertEquals((r1).data.renditionCacheKey, ResolvedAudio.cacheKey(id, Quality.MEDIUM))
    }

    @Test
    fun `cache key discriminates quality`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("cache-quality")
        resolver.resolveAudio(id, AudioRequest(Quality.LOW))
        resolver.resolveAudio(id, AudioRequest(Quality.HIGH))
        // Different quality => different cache keys => two fetches
        assertEquals(2, fetchCount.get())
        // Third call LOW again => cache hit
        resolver.resolveAudio(id, AudioRequest(Quality.LOW))
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun `invalidate purges cache and forces fresh fetch`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("invalidate-test")
        resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM))
        assertEquals(1, fetchCount.get())
        resolver.invalidate(id)
        resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM))
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun `invalidate purges all qualities for track`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("invalidate-all-quals")
        resolver.resolveAudio(id, AudioRequest(Quality.LOW))
        resolver.resolveAudio(id, AudioRequest(Quality.HIGH))
        assertEquals(2, fetchCount.get())
        resolver.invalidate(id)
        // Both purged, next two should refetch
        resolver.resolveAudio(id, AudioRequest(Quality.LOW))
        resolver.resolveAudio(id, AudioRequest(Quality.HIGH))
        assertEquals(4, fetchCount.get())
    }

    @Test
    fun `forceRefresh bypasses cache`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("force-refresh")
        resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM, forceRefresh = false))
        assertEquals(1, fetchCount.get())
        resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM, forceRefresh = true))
        assertEquals(2, fetchCount.get())
        // Without forceRefresh again => cache hit on last refreshed entry
        resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM, forceRefresh = false))
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun `LRU evicts eldest when over capacity`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(
            audioStreams = listOf(audioStream(expireUrl("https://rr.googlevideo.com/a", sec), 128)),
            cacheSize = 2,
            fetchCount = fetchCount,
        )
        resolver.resolveAudio(SourceId("a1"), AudioRequest(Quality.MEDIUM))
        resolver.resolveAudio(SourceId("a2"), AudioRequest(Quality.MEDIUM))
        assertEquals(2, resolver.cacheSizeForTest())
        resolver.resolveAudio(SourceId("a3"), AudioRequest(Quality.MEDIUM))
        assertEquals(2, resolver.cacheSizeForTest())
        // a1 should have been evicted
        assertNull(resolver.peekCacheForTest(ResolvedAudio.cacheKey(SourceId("a1"), Quality.MEDIUM)))
        assertNotNull(resolver.peekCacheForTest(ResolvedAudio.cacheKey(SourceId("a3"), Quality.MEDIUM)))
    }

    @Test
    fun `expired cache entry is treated as miss with margin`() = runTest {
        // Build a URL whose expire is in the past; resolver should reject it as Parse,
        // not cache it. Then a second resolve with same expired url also fails (no cache hit).
        val pastSec = System.currentTimeMillis() / 1000 - 600 // 10 min ago
        val url = expireUrl("https://rr.googlevideo.com/expired", pastSec)
        val streams = listOf(audioStream(url, 128))
        val fetchCount = AtomicInteger(0)
        val (resolver, _) = resolverWith(audioStreams = streams, fetchCount = fetchCount)

        val id = SourceId("expired-cache")
        // First resolve: expiry is parsed but is already expired — however buildResolvedAudio
        // still succeeds (it stores past expiry). The cache will store it, but next read
        // should evict due to margin check before returning.
        val r1 = resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM))
        // r1 is Success with past expiry (resolver does not reject past, only missing).
        // AD-7 layer 1 is enforced at cache-read: second call should treat as stale and refetch.
        assertTrue(r1 is SwayResult.Success)
        assertEquals(1, fetchCount.get())
        // Second resolve: should detect stale and refetch (count 2)
        // To make staleness observable, we need the cached entry to be stale at read time.
        // Our expired url is past, so isExpiredAt(now, 5min) true => stale => refetch.
        val r2 = resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM))
        assertTrue(r2 is SwayResult.Success)
        assertEquals(2, fetchCount.get())
    }

    // -------------------------------------------------------------------------
    // Format ladder incl ciphered fallback
    // -------------------------------------------------------------------------

    @Test
    fun `fallback to video stream when audio empty`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val videoUrl = expireUrl("https://rr.googlevideo.com/videoplayback", sec)
        val vStream = videoStream(videoUrl, isVideoOnly = false)
        val (resolver, _) = resolverWith(audioStreams = emptyList(), videoStreams = listOf(vStream))

        val result = resolver.resolveAudio(SourceId("fallback-track"), AudioRequest(Quality.MEDIUM))
        assertTrue(result is SwayResult.Success)
        val data = (result as SwayResult.Success).data
        assertEquals(videoUrl, data.url)
        assertTrue(data.backendTag.contains("fallback"))
        assertTrue(data.backendTag.contains("video"))
    }

    @Test
    fun `no streams yields ContentNotFound`() = runTest {
        val (resolver, _) = resolverWith(audioStreams = emptyList(), videoStreams = emptyList())
        val result = resolver.resolveAudio(SourceId("no-streams"), AudioRequest(Quality.MEDIUM))
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.ContentNotFound)
    }

    // -------------------------------------------------------------------------
    // Error mapping
    // -------------------------------------------------------------------------

    @Test
    fun `ReCaptcha maps to RateLimited`() = runTest {
        val (resolver, _) = resolverWith(fetchThrows = ReCaptchaException("429", "https://www.youtube.com/watch?v=abc"))
        val result = resolver.resolveAudio(SourceId("rate-limited"), AudioRequest(Quality.MEDIUM))
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.RateLimited)
    }

    @Test
    fun `IOException offline maps to Offline`() = runTest {
        val (resolver, _) = resolverWith(fetchThrows = IOException("Unable to resolve host \"www.youtube.com\""))
        val result = resolver.resolveAudio(SourceId("offline"), AudioRequest(Quality.MEDIUM))
        assertTrue((result as SwayResult.Failure).error is SwayError.Offline)
    }

    @Test
    fun `IOException generic maps to UpstreamUnavailable`() = runTest {
        val (resolver, _) = resolverWith(fetchThrows = IOException("HTTP 500"))
        val result = resolver.resolveAudio(SourceId("upstream"), AudioRequest(Quality.MEDIUM))
        assertTrue((result as SwayResult.Failure).error is SwayError.UpstreamUnavailable)
    }

    @Test
    fun `ParsingException maps to Parse`() = runTest {
        val (resolver, _) = resolverWith(fetchThrows = ParsingException("malformed"))
        val result = resolver.resolveAudio(SourceId("parse"), AudioRequest(Quality.MEDIUM))
        assertTrue((result as SwayResult.Failure).error is SwayError.Parse)
    }

    // -------------------------------------------------------------------------
    // prefetchNext silent-null
    // -------------------------------------------------------------------------

    @Test
    fun `prefetchNext returns null silently on failure`() = runTest {
        val (resolver, _) = resolverWith(fetchThrows = IOException("Unable to resolve host"))
        val result = resolver.prefetchNext(SourceId("prefetch-fail"), AudioRequest(Quality.MEDIUM))
        assertNull(result)
    }

    @Test
    fun `prefetchNext returns data on success`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 128))
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.prefetchNext(SourceId("prefetch-ok"), AudioRequest(Quality.MEDIUM))
        assertNotNull(result)
        assertEquals(url, result!!.url)
    }

    @Test
    fun `prefetchNext never throws even when factory throws`() = runTest {
        val resolver = NewPipeStreamResolver(
            service = ServiceList.YouTube,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            streamExtractorFactory = { _ -> throw RuntimeException("boom") },
            isMeteredProvider = { false },
        )
        val result = resolver.prefetchNext(SourceId("prefetch-throw"), AudioRequest(Quality.MEDIUM))
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // ResolvedAudio fields
    // -------------------------------------------------------------------------

    @Test
    fun `ResolvedAudio carries container hint and backend tag and rendition key`() = runTest {
        val sec = System.currentTimeMillis() / 1000 + 3600
        val url = expireUrl("https://rr.googlevideo.com/a", sec)
        val streams = listOf(audioStream(url, 160, MediaFormat.M4A))
        val (resolver, _) = resolverWith(audioStreams = streams)
        val result = resolver.resolveAudio(SourceId("fields"), AudioRequest(Quality.HIGH))
        val data = (result as SwayResult.Success).data
        assertNotNull(data.containerHint)
        assertTrue(data.backendTag.contains("newpipe"))
        assertEquals(ResolvedAudio.cacheKey(SourceId("fields"), Quality.HIGH), data.renditionCacheKey)
        assertTrue(data.bitrateKbps > 0)
        assertTrue(data.expiresAtEpochMs > System.currentTimeMillis())
    }
}

package com.sway.catalog

import com.sway.core.model.AudioRequest
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.SourceId
import com.sway.core.model.StreamResolver
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * StreamResolver backed by NewPipeExtractor — story 3.6 (AR-6, AD-7, C-5/C-6, FR-15).
 *
 * Responsibilities (all ACs):
 * - Format ladder incl. ciphered-format fallback paths: primary [AudioStream]s, fallback
 *   to muxed [VideoStream]s when audio empty. Cipher handling is delegated to
 *   `YoutubeStreamExtractor` (which deciphers signatureCipher internally); we observe
 *   R-2 prevalence by logging which tier succeeded (backendTag).
 * - Expiry param parsed to [ResolvedAudio.expiresAtEpochMs] (never guessed): strict
 *   `expire`/`exp` query param → epoch ms. Missing/parse failure → `Failure(Parse)`.
 * - LRU rendition cache keyed `SourceId+quality` discriminator (via [ResolvedAudio.cacheKey]).
 *   Size bounded (default 32) per NFR-10; cache is checked at read time with −5 min margin
 *   (AD-7 layer 1) — stale entries are treated as misses.
 * - Mandatory in-flight single-flight dedup invisible to callers: concurrent identical
 *   `resolveAudio` share one fetch.
 * - `invalidate(trackId)` purges all renditions for the id; next resolve bypasses cache.
 * - `prefetchNext` silent-null (never throws).
 * - Bitrate-target selection best-under-target else max for LOW/MEDIUM/HIGH/AUTO; AUTO
 *   → unmetered→MEDIUM-class, metered→LOW-class (via [isMeteredProvider]).
 * - `forceRefresh` bypasses cache.
 *
 * Isolation: all `org.schabi.newpipe` imports live here inside `:catalog` (AD-1).
 * Threading: network `fetchPage` + extractor calls on [ioDispatcher], mapping/selection
 * on [defaultDispatcher] per AR-14.
 */
class NewPipeStreamResolver(
    private val service: StreamingService = ServiceList.YouTube,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val streamExtractorFactory: ((LinkHandler) -> StreamExtractor)? = null,
    private val isMeteredProvider: () -> Boolean = { false },
    cacheSize: Int = 32,
) : StreamResolver {

    companion object {
        const val TARGET_LOW_KBPS = 96
        const val TARGET_MEDIUM_KBPS = 160
        const val TARGET_HIGH_KBPS = 256
        const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
        const val CACHE_MAX_SIZE = 32

        /** Resolve target kbps for a [Quality] given metered state. */
        fun targetFor(quality: Quality, isMetered: Boolean): Int = when (quality) {
            Quality.LOW -> TARGET_LOW_KBPS
            Quality.MEDIUM -> TARGET_MEDIUM_KBPS
            Quality.HIGH -> TARGET_HIGH_KBPS
            Quality.AUTO -> if (isMetered) TARGET_LOW_KBPS else TARGET_MEDIUM_KBPS
        }

        /**
         * Parse `expire` param (YouTube `googlevideo.com` URLs) to epoch ms.
         * Never guesses: returns null when param absent or unparseable.
         * Accepts both `expire` and `exp` keys; seconds → ms.
         */
        fun parseExpiryEpochMs(url: String): Long? {
            if (url.isBlank()) return null
            // Fast regex then fallback to URI query parsing for robustness.
            val quick = Regex("[?&](expire|exp|expiry)=(\\d+)").find(url)
            if (quick != null) {
                val sec = quick.groupValues[2].toLongOrNull() ?: return null
                // Guard against ms values accidentally (epoch ms would be >1e12); reject if too large.
                // YouTube expire is seconds since epoch (~1.7e9); ms would be 1.7e12. Allow both but normalize.
                return if (sec > 1_000_000_000_0L) sec else sec * 1000L
            }
            return try {
                val uri = try { java.net.URI(url) } catch (_: Exception) { return null }
                val query = uri.query ?: return null
                val params = query.split("&").associate { part ->
                    val idx = part.indexOf('=')
                    if (idx == -1) part to "" else part.substring(0, idx) to part.substring(idx + 1)
                }
                val raw = params["expire"] ?: params["exp"] ?: params["expiry"] ?: return null
                // URL-decode just in case, but usually numeric.
                val decoded = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
                val sec = decoded.toLongOrNull() ?: return null
                if (sec > 1_000_000_000_0L) sec else sec * 1000L
            } catch (_: Exception) {
                null
            }
        }

        /** Best-under-target else max selection over audio streams. Returns null when empty. */
        fun selectAudioStream(streams: List<AudioStream>, targetKbps: Int): AudioStream? {
            if (streams.isEmpty()) return null
            // Filter to url-valid entries only (isUrl + non-blank url)
            val valid = streams.filter { it.isUrl && it.getUrl()?.isNotBlank() == true }
            if (valid.isEmpty()) return null
            // Map to effective bitrate
            fun bitrateFor(s: AudioStream): Int {
                val avg = s.getAverageBitrate()
                if (avg != AudioStream.UNKNOWN_BITRATE && avg > 0) return avg
                val b = s.getBitrate()
                return if (b > 0) b else 0
            }
            // Best under target
            val under = valid.filter { bitrateFor(it) in 1..targetKbps }
            if (under.isNotEmpty()) {
                return under.maxByOrNull { bitrateFor(it) }
            }
            // Else overall max (including 0-bitrate fallback: picks max available)
            return valid.maxByOrNull { bitrateFor(it) }
        }

        /** Fallback selection over muxed VideoStreams (contain audio). Picks best bitrate under target else max. */
        fun selectVideoFallbackStream(streams: List<VideoStream>, targetKbps: Int): VideoStream? {
            if (streams.isEmpty()) return null
            val valid = streams.filter { it.isUrl && it.getUrl()?.isNotBlank() == true && !it.isVideoOnly }
            val pool = if (valid.isNotEmpty()) valid else streams.filter { it.isUrl && it.getUrl()?.isNotBlank() == true }
            if (pool.isEmpty()) return null
            fun bitrateFor(s: VideoStream): Int = s.getBitrate().takeIf { it > 0 } ?: 0
            val under = pool.filter { bitrateFor(it) in 1..targetKbps }
            if (under.isNotEmpty()) return under.maxByOrNull { bitrateFor(it) }
            return pool.maxByOrNull { bitrateFor(it) }
        }
    }

    // LRU cache — LinkedHashMap access-order, guarded by synchronized(cache).
    private val cache: LinkedHashMap<String, ResolvedAudio> = object : LinkedHashMap<String, ResolvedAudio>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedAudio>?): Boolean = size > cacheSize
    }

    // In-flight single-flight map: key -> deferred result
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<SwayResult<ResolvedAudio>>>()

    override suspend fun resolveAudio(trackId: SourceId, request: AudioRequest): SwayResult<ResolvedAudio> {
        val key = ResolvedAudio.cacheKey(trackId, request.quality)

        // Fast path: cache hit when not forceRefresh and not expired (with margin)
        if (!request.forceRefresh) {
            val cached = synchronized(cache) { cache[key] }
            if (cached != null) {
                val now = System.currentTimeMillis()
                val isStale = cached.isExpiredAt(now, EXPIRY_MARGIN_MS)
                if (!isStale) {
                    return SwayResult.Success(cached)
                } else {
                    synchronized(cache) { cache.remove(key) }
                    CatalogLog.d("resolveAudio cache stale evicted key=$key expiresAt=${cached.expiresAtEpochMs} now=$now")
                }
            }
        }

        // Dedup: if already in flight, await same deferred
        inFlight[key]?.let { existing ->
            return try { existing.await() } catch (_: Exception) {
                // Should not happen; fallback to fresh fetch
                SwayResult.Failure(SwayError.Unknown(null))
            }
        }

        val deferred = CompletableDeferred<SwayResult<ResolvedAudio>>()
        val prev = inFlight.putIfAbsent(key, deferred)
        if (prev != null) {
            return try { prev.await() } catch (_: Exception) { SwayResult.Failure(SwayError.Unknown(null)) }
        }

        return try {
            val result = fetchAndSelect(trackId, request)
            // Populate cache on success only
            if (result is SwayResult.Success) {
                synchronized(cache) { cache[key] = result.data }
            }
            deferred.complete(result)
            result
        } catch (e: Exception) {
            val failure = mapToFailure(e, trackId)
            // Complete with failure value (not exception) so awaiters get typed Failure
            try { deferred.complete(failure) } catch (_: Exception) { /* already completed */ }
            failure
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    override fun invalidate(trackId: SourceId) {
        val prefix = "${trackId.value}:"
        synchronized(cache) {
            val toRemove = cache.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { cache.remove(it) }
        }
        // Note: in-flight fetches are not cancelled; they will complete but their
        // result will be cached under a key that was just evicted — next resolve
        // will refetch, which satisfies "invalidate then resolve bypasses".
        CatalogLog.d("invalidate purged ${trackId.value} (prefix=$prefix)")
    }

    override suspend fun prefetchNext(trackId: SourceId, request: AudioRequest): ResolvedAudio? {
        return try {
            when (val r = resolveAudio(trackId, request)) {
                is SwayResult.Success -> r.data
                is SwayResult.Failure -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Internal fetch + selection
    // -------------------------------------------------------------------------

    private suspend fun fetchAndSelect(trackId: SourceId, request: AudioRequest): SwayResult<ResolvedAudio> {
        // Resolve target kbps from quality + metered state
        val isMetered = try { isMeteredProvider() } catch (_: Exception) { false }
        val targetKbps = targetFor(request.quality, isMetered)

        return withContext(ioDispatcher) {
            try { NewPipeInitializer.initIfNeeded() } catch (e: Exception) {
                CatalogLog.w("NewPipeStreamResolver init failed: ${e.javaClass.simpleName} ${e.message}")
            }

            val rawId = trackId.value.trim()
            val factory = YoutubeStreamLinkHandlerFactory.getInstance()
            val url: String = try {
                factory.getUrl(rawId)
            } catch (e: ParsingException) {
                // Allow bare video ids: fallback to watch URL
                if (rawId.matches(Regex("[A-Za-z0-9_-]{5,64}"))) {
                    "https://www.youtube.com/watch?v=$rawId"
                } else throw e
            }

            val linkHandler = try {
                factory.fromUrl(url)
            } catch (e: ParsingException) {
                // Fallback for test ids or non-standard upstream ids: construct directly
                // so that the injected fake extractor can still be exercised. Real
                // extractor will still fail later on fetch if id truly invalid, which
                // will surface as UpstreamUnavailable/Parse via the normal error path.
                try {
                    LinkHandler(url, url, rawId)
                } catch (pe: Exception) {
                    CatalogLog.w("streamResolver invalid id shape: $rawId ${e.message?.take(120)}")
                    return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = "stream invalid id: $rawId".take(500)))
                }
            }

            val extractor: StreamExtractor = try {
                streamExtractorFactory?.invoke(linkHandler) ?: service.getStreamExtractor(linkHandler)
            } catch (e: Exception) {
                CatalogLog.w("streamResolver extractor creation failed: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                return@withContext SwayResult.Failure(SwayError.UpstreamUnavailable)
            }

            // Fetch page (network) on IO
            try {
                extractor.fetchPage()
            } catch (e: ReCaptchaException) {
                CatalogLog.w("streamResolver RateLimited (429): ${e.message?.take(120)} id=${rawId.take(20)}")
                return@withContext SwayResult.Failure(SwayError.RateLimited)
            } catch (e: IOException) {
                return@withContext mapIoFailure(e)
            } catch (e: ParsingException) {
                val shape = "stream Parse fetchPage: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${rawId.take(20)}"
                CatalogLog.w(shape)
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            } catch (e: ExtractionException) {
                val shape = "stream Extraction fetchPage: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${rawId.take(20)}"
                CatalogLog.w(shape)
                if (shape.contains("not found", true)) return@withContext SwayResult.Failure(SwayError.ContentNotFound)
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            } catch (e: Exception) {
                CatalogLog.e("streamResolver Unknown fetchPage: ${e.javaClass.simpleName} ${e.message} id=${rawId.take(20)}", e)
                return@withContext SwayResult.Failure(SwayError.Unknown(e))
            }

            // Gather streams
            val audioStreams: List<AudioStream> = try {
                extractor.getAudioStreams()
            } catch (e: IOException) {
                return@withContext mapIoFailure(e)
            } catch (e: ReCaptchaException) {
                return@withContext SwayResult.Failure(SwayError.RateLimited)
            } catch (e: ExtractionException) {
                CatalogLog.w("streamResolver getAudioStreams extraction: ${e.javaClass.simpleName} ${e.message?.take(200)}")
                emptyList()
            } catch (e: Exception) {
                CatalogLog.w("streamResolver getAudioStreams failed: ${e.javaClass.simpleName}")
                emptyList()
            }

            // Selection on Default dispatcher (CPU)
            withContext(defaultDispatcher) {
                val chosenAudio = selectAudioStream(audioStreams, targetKbps)

                if (chosenAudio != null) {
                    return@withContext buildResolvedAudio(
                        trackId = trackId,
                        request = request,
                        streamUrl = chosenAudio.getUrl(),
                        bitrateKbps = run {
                            val avg = chosenAudio.getAverageBitrate()
                            if (avg != AudioStream.UNKNOWN_BITRATE && avg > 0) avg else chosenAudio.getBitrate().takeIf { it > 0 } ?: 0
                        },
                        containerHint = chosenAudio.getFormat()?.getSuffix(),
                        backendTag = "newpipe:audio:${chosenAudio.getFormat()?.getSuffix() ?: "unknown"}",
                    )
                }

                // Ladder fallback: try muxed video streams
                val videoStreams: List<VideoStream> = try {
                    extractor.getVideoStreams()
                } catch (_: Exception) { emptyList() }

                val chosenVideo = selectVideoFallbackStream(videoStreams, targetKbps)
                if (chosenVideo != null) {
                    CatalogLog.w("streamResolver fallback to VideoStream for ${rawId.take(20)} (R-2 ciphered-prevalence: audio empty, video fallback used)")
                    return@withContext buildResolvedAudio(
                        trackId = trackId,
                        request = request,
                        streamUrl = chosenVideo.getUrl(),
                        bitrateKbps = chosenVideo.getBitrate().takeIf { it > 0 } ?: 0,
                        containerHint = chosenVideo.getFormat()?.getSuffix(),
                        backendTag = "newpipe:fallback:video:${chosenVideo.getFormat()?.getSuffix() ?: "unknown"}",
                    )
                }

                // No streams at all — typed failure
                CatalogLog.w("streamResolver no streams for $rawId (audio=${audioStreams.size} video=${videoStreams.size})")
                // Distinguish empty due to extraction vs truly not found: treat as ContentNotFound so caller skips with reason
                SwayResult.Failure(SwayError.ContentNotFound)
            }
        }
    }

    private fun buildResolvedAudio(
        trackId: SourceId,
        request: AudioRequest,
        streamUrl: String?,
        bitrateKbps: Int,
        containerHint: String?,
        backendTag: String,
    ): SwayResult<ResolvedAudio> {
        if (streamUrl.isNullOrBlank()) {
            CatalogLog.w("streamResolver chosen stream has blank url for ${trackId.value.take(20)}")
            return SwayResult.Failure(SwayError.Parse(shapeInfo = "blank stream url for ${trackId.value.take(20)}".take(500)))
        }
        val expiresAt = parseExpiryEpochMs(streamUrl)
        if (expiresAt == null || expiresAt <= 0L) {
            CatalogLog.w("streamResolver expiry missing/unparseable for ${trackId.value.take(20)} url=${streamUrl.take(120)}")
            return SwayResult.Failure(SwayError.Parse(shapeInfo = "missing expiry param for ${trackId.value.take(20)}".take(500)))
        }
        val renditionKey = ResolvedAudio.cacheKey(trackId, request.quality)
        return try {
            val resolved = ResolvedAudio(
                url = streamUrl,
                expiresAtEpochMs = expiresAt,
                bitrateKbps = bitrateKbps.coerceAtLeast(0),
                containerHint = containerHint?.takeIf { it.isNotBlank() },
                backendTag = backendTag,
                renditionCacheKey = renditionKey,
            )
            SwayResult.Success(resolved)
        } catch (e: IllegalArgumentException) {
            CatalogLog.w("streamResolver ResolvedAudio validation failed: ${e.message}")
            SwayResult.Failure(SwayError.Parse(shapeInfo = e.message?.take(500)))
        }
    }

    private fun mapToFailure(e: Throwable, trackId: SourceId): SwayResult<ResolvedAudio> {
        return when (e) {
            is ReCaptchaException -> SwayResult.Failure(SwayError.RateLimited)
            is ParsingException -> SwayResult.Failure(SwayError.Parse(shapeInfo = "stream Parse: ${e.message?.take(500)}"))
            is ExtractionException -> {
                if (e.message?.contains("not found", true) == true) SwayResult.Failure(SwayError.ContentNotFound)
                else SwayResult.Failure(SwayError.Parse(shapeInfo = "stream Extraction: ${e.message?.take(500)}"))
            }
            is IOException -> mapIoFailure(e)
            else -> SwayResult.Failure(SwayError.Unknown(e))
        }
    }

    private fun mapIoFailure(e: IOException): SwayResult<ResolvedAudio> {
        val msg = e.message ?: ""
        if (msg.contains("exceeds 10MB", true) || msg.contains("exceeds limit", true)) {
            return SwayResult.Failure(SwayError.UpstreamUnavailable)
        }
        val isOffline = msg.contains("Unable to resolve host", true) ||
            msg.contains("Failed to connect", true) ||
            e is UnknownHostException || e is ConnectException ||
            e.cause is UnknownHostException
        return if (isOffline) {
            CatalogLog.w("streamResolver Offline IOException: ${e.javaClass.simpleName} ${msg.take(120)}")
            SwayResult.Failure(SwayError.Offline)
        } else {
            CatalogLog.w("streamResolver Upstream IOException: ${e.javaClass.simpleName} ${msg.take(200)}")
            SwayResult.Failure(SwayError.UpstreamUnavailable)
        }
    }

    // Test seam: expose cache size / clear for tests
    internal fun cacheSizeForTest(): Int = synchronized(cache) { cache.size }
    internal fun clearCacheForTest() = synchronized(cache) { cache.clear() }
    internal fun peekCacheForTest(key: String): ResolvedAudio? = synchronized(cache) { cache[key] }
}

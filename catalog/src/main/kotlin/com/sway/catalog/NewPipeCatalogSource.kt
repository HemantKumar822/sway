package com.sway.catalog

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.CatalogSource
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.IOException

/**
 * Four-type search adapter — story 3.2 (AR-2, AR-8, AR-10, FR-1 trace, FR-2 pagination).
 *
 * AD-1 isolation: all `org.schabi.newpipe` imports live here inside `:catalog`; public
 * signatures speak exclusively in `core:model` types returning [SwayResult] (AD-9).
 *
 * Responsibilities:
 * - Four `search*` methods with opaque page continuation via [SearchPageTokenCodec].
 * - Parse-time [com.sway.core.model.ArtworkRef] normalization + duration ms conversion via [SearchMappers].
 * - Blank-id items dropped with logged shape info (AR-8).
 * - Typed error mapping: 429→RateLimited, malformed→Parse (shape logged), oversized→UpstreamUnavailable (EP-5),
 *   IOException connectivity→Offline vs UpstreamUnavailable.
 *
 * Threading: network (fetch) on [ioDispatcher], mapping on [defaultDispatcher] (AR-14).
 *
 * @param service YouTube service (default [ServiceList.YouTube]); inject fake for tests.
 * @param ioDispatcher adapter dispatcher (IO).
 * @param defaultDispatcher parse/mapping dispatcher (Default).
 * @param extractorFactory test seam: `(SearchQueryHandler) -> SearchExtractor`; default delegates to [service].
 */
class NewPipeCatalogSource(
    private val service: StreamingService = ServiceList.YouTube,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val extractorFactory: ((SearchQueryHandler) -> SearchExtractor)? = null,
) : CatalogSource {

    // Detail methods not in scope for 3.2 — stubbed as ContentNotFound to keep port contract.
    override suspend fun album(id: SourceId): SwayResult<Album> =
        SwayResult.Failure(SwayError.ContentNotFound)

    override suspend fun artist(id: SourceId): SwayResult<Artist> =
        SwayResult.Failure(SwayError.ContentNotFound)

    override suspend fun catalogPlaylist(id: SourceId): SwayResult<CatalogPlaylist> =
        SwayResult.Failure(SwayError.ContentNotFound)

    // -------------------------------------------------------------------------
    // Search — four groups
    // -------------------------------------------------------------------------

    override suspend fun searchSongs(query: String, pageToken: String?): SwayResult<PagedResult<Song>> =
        searchInternal(
            query = query,
            pageToken = pageToken,
            contentFilter = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
            mapper = SearchMappers::toSong,
            logTag = "searchSongs",
        )

    override suspend fun searchAlbums(query: String, pageToken: String?): SwayResult<PagedResult<Album>> =
        searchInternal(
            query = query,
            pageToken = pageToken,
            contentFilter = YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS,
            mapper = SearchMappers::toAlbum,
            logTag = "searchAlbums",
        )

    override suspend fun searchArtists(query: String, pageToken: String?): SwayResult<PagedResult<Artist>> =
        searchInternal(
            query = query,
            pageToken = pageToken,
            contentFilter = YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS,
            mapper = SearchMappers::toArtist,
            logTag = "searchArtists",
        )

    override suspend fun searchCatalogPlaylists(
        query: String,
        pageToken: String?,
    ): SwayResult<PagedResult<CatalogPlaylist>> = searchInternal(
        query = query,
        pageToken = pageToken,
        contentFilter = YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS,
        mapper = SearchMappers::toCatalogPlaylist,
        logTag = "searchCatalogPlaylists",
    )

    // -------------------------------------------------------------------------
    // Internal generic search driver
    // -------------------------------------------------------------------------

    private suspend fun <T : Any> searchInternal(
        query: String,
        pageToken: String?,
        contentFilter: String,
        mapper: (InfoItem) -> T?,
        logTag: String,
    ): SwayResult<PagedResult<T>> = withContext(ioDispatcher) {
        if (query.isBlank()) {
            return@withContext SwayResult.Success(PagedResult.empty())
        }

        // Ensure extractor is bound to the shared OkHttp downloader (AD-3). Idempotent.
        try {
            NewPipeInitializer.initIfNeeded()
        } catch (e: Exception) {
            CatalogLog.w("$logTag NewPipe init failed: ${e.javaClass.simpleName} ${e.message}")
        }

        try {
            val factory = YoutubeSearchQueryHandlerFactory.getInstance()
            val handler = factory.fromQuery(query, listOf(contentFilter), "")
            val extractor = extractorFactory?.invoke(handler) ?: service.getSearchExtractor(handler)

            // Fetch first page or continuation.
            val infoPage = if (pageToken.isNullOrBlank()) {
                extractor.fetchPage()
                extractor.initialPage
            } else {
                val decoded = SearchPageTokenCodec.decode(pageToken)
                if (decoded == null) {
                    val shape = "$logTag invalid page token shape: ${pageToken.take(120)}"
                    CatalogLog.w(shape)
                    return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
                }
                // For continuation, ensure first page was fetched at least once to prime cookies.
                // Some extractors require fetchPage before getPage; call it if not already.
                try {
                    extractor.fetchPage()
                } catch (_: Exception) {
                    // Best-effort: getPage may still succeed.
                }
                extractor.getPage(decoded)
            }

            // Edge: extractor may return null nextPage at end-of-results.
            val nextToken: String? = infoPage.nextPage?.takeIf { Page.isValid(it) }?.let {
                SearchPageTokenCodec.encode(it)
            }

            // Map items on Default dispatcher (parse/extraction).
            val mapped: List<T> = withContext(defaultDispatcher) {
                val out = mutableListOf<T>()
                var dropped = 0
                for (item in infoPage.items) {
                    val m = try {
                        mapper(item)
                    } catch (e: Exception) {
                        CatalogLog.w("$logTag mapper threw ${e.javaClass.simpleName} for ${item.javaClass.simpleName}: ${e.message?.take(120)}")
                        null
                    }
                    if (m != null) out.add(m) else dropped++
                }
                if (dropped > 0) {
                    CatalogLog.w("$logTag dropped $dropped blank-id items of ${infoPage.items.size} (query=${query.take(40)})")
                }
                // Log extractor-side errors (per-item parse failures reported by NewPipe).
                val extractorErrors = infoPage.errors
                if (!extractorErrors.isNullOrEmpty()) {
                    CatalogLog.w("$logTag extractor reported ${extractorErrors.size} item errors: ${extractorErrors.first().message?.take(200)}")
                }
                out
            }

            SwayResult.Success(PagedResult.of(mapped, nextToken))
        } catch (e: ReCaptchaException) {
            CatalogLog.w("$logTag RateLimited (429): ${e.message?.take(120)}")
            SwayResult.Failure(SwayError.RateLimited)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            // EP-5 oversized body maps to UpstreamUnavailable, not Parse.
            if (msg.contains("exceeds 10MB", ignoreCase = true) || msg.contains("exceeds limit", ignoreCase = true)) {
                CatalogLog.w("$logTag UpstreamUnavailable (oversized): $msg")
                return@withContext SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
            // Heuristic offline detection (AD-9 Offline row).
            val isOffline = msg.contains("Unable to resolve host", true) ||
                msg.contains("Failed to connect", true) ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e.cause is java.net.UnknownHostException
            if (isOffline) {
                CatalogLog.w("$logTag Offline IOException: ${e.javaClass.simpleName} ${msg.take(120)}")
                SwayResult.Failure(SwayError.Offline)
            } else {
                CatalogLog.w("$logTag Upstream IOException: ${e.javaClass.simpleName} ${msg.take(200)}")
                SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
        } catch (e: ParsingException) {
            val shape = "$logTag Parse: ${e.javaClass.simpleName} ${e.message?.take(300)}"
            CatalogLog.w("$shape url=${e.stackTraceToString().take(200)}")
            SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
        } catch (e: ExtractionException) {
            // Includes NothingFoundException etc; treat as Parse/Upstream depending on message.
            val shape = "$logTag Extraction: ${e.javaClass.simpleName} ${e.message?.take(300)}"
            CatalogLog.w(shape)
            // NothingFound is honest empty only when query truly has no results; but at search layer
            // we map to Parse to preserve typed error vs empty distinction? For now map NothingFound to Success(empty)
            // if message indicates nothing found, to avoid surfacing Parse for honest empty.
            if (e.javaClass.simpleName.contains("NothingFound", true)) {
                return@withContext SwayResult.Success(PagedResult.empty())
            }
            SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
        } catch (e: Exception) {
            CatalogLog.e("$logTag Unknown: ${e.javaClass.simpleName} ${e.message}", e)
            SwayResult.Failure(SwayError.Unknown(e))
        }
    }
}

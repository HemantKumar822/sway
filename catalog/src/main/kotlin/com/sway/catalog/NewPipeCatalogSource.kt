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
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

/**
 * Four-type search adapter + album detail — stories 3.2, 3.3 (AR-2, AR-8, AR-10, FR-1/FR-5 trace).
 *
 * AD-1 isolation: all `org.schabi.newpipe` imports live here inside `:catalog`; public
 * signatures speak exclusively in `core:model` types returning [SwayResult] (AD-9).
 *
 * Responsibilities:
 * - Four `search*` methods with opaque page continuation via [SearchPageTokenCodec].
 * - Album detail `album(id)` via [PlaylistExtractor] → [AlbumMappers] (year nullable,
 *   track order preserved, artwork chain per track + hero).
 * - Parse-time [com.sway.core.model.ArtworkRef] normalization + duration ms conversion.
 * - Blank-id items dropped with logged shape info (AR-8).
 * - Typed error mapping: 429→RateLimited, malformed→Parse (shape logged), oversized→UpstreamUnavailable,
 *   IOException connectivity→Offline vs UpstreamUnavailable.
 *
 * Threading: network (fetch) on [ioDispatcher], mapping on [defaultDispatcher] (AR-14).
 *
 * @param service YouTube service (default [ServiceList.YouTube]); inject fake for tests.
 * @param ioDispatcher adapter dispatcher (IO).
 * @param defaultDispatcher parse/mapping dispatcher (Default).
 * @param extractorFactory test seam: `(SearchQueryHandler) -> SearchExtractor`; default delegates to [service].
 * @param playlistExtractorFactory test seam for album detail: `(ListLinkHandler) -> PlaylistExtractor`.
 * @param channelExtractorFactory test seam for artist detail: `(ListLinkHandler) -> ChannelExtractor`.
 * @param channelTabExtractorFactory test seam for artist tab fetches: `(ListLinkHandler) -> ChannelTabExtractor`.
 */
class NewPipeCatalogSource(
    private val service: StreamingService = ServiceList.YouTube,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val extractorFactory: ((SearchQueryHandler) -> SearchExtractor)? = null,
    private val playlistExtractorFactory: ((ListLinkHandler) -> PlaylistExtractor)? = null,
    private val channelExtractorFactory: ((ListLinkHandler) -> ChannelExtractor)? = null,
    private val channelTabExtractorFactory: ((ListLinkHandler) -> ChannelTabExtractor)? = null,
) : CatalogSource {

    override suspend fun album(id: SourceId): SwayResult<Album> = albumInternal(id)

    override suspend fun artist(id: SourceId): SwayResult<Artist> = artistInternal(id)

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
    // Album detail — story 3.3 (FR-5 trace)
    // -------------------------------------------------------------------------

    private suspend fun albumInternal(id: SourceId): SwayResult<Album> = withContext(ioDispatcher) {
        try {
            NewPipeInitializer.initIfNeeded()
        } catch (e: Exception) {
            CatalogLog.w("album NewPipe init failed: ${e.javaClass.simpleName} ${e.message}")
        }

        try {
            val rawId = id.value.trim()
            // Build link handler via playlist factory (handles OLAK/MPRE/PL ids).
            val factory = YoutubePlaylistLinkHandlerFactory.getInstance()
            val url: String = try {
                factory.getUrl(rawId)
            } catch (e: ParsingException) {
                // Fallback for browse-style ids (MPREb_...) that factory may reject: use music browse URL.
                if (rawId.startsWith("MPREb") || rawId.startsWith("OLAK") || rawId.startsWith("VL")) {
                    "https://music.youtube.com/browse/$rawId"
                } else {
                    throw e
                }
            }
            val linkHandler = try {
                factory.fromUrl(url)
            } catch (e: ParsingException) {
                CatalogLog.w("album invalid id shape: $rawId ${e.message?.take(120)}")
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = "album invalid id: $rawId".take(500)))
            }

            val extractor = playlistExtractorFactory?.invoke(linkHandler) ?: service.getPlaylistExtractor(linkHandler)

            // Fetch playlist/album detail (hero + tracklist) on IO dispatcher.
            extractor.fetchPage()

            val name: String = try {
                extractor.name
            } catch (e: ParsingException) {
                val shape = "album Parse name: ${e.message?.take(300)} id=$rawId"
                CatalogLog.w(shape)
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            }

            val uploaderName: String? = try { extractor.uploaderName } catch (_: Exception) { null }
            val uploaderUrl: String? = try { extractor.uploaderUrl } catch (_: Exception) { null }
            val descriptionText: String? = try {
                extractor.description?.content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            val thumbs: List<Image> = try { extractor.thumbnails } catch (_: Exception) { emptyList() }
            val initialPage = try {
                extractor.initialPage
            } catch (e: Exception) {
                val shape = "album Parse initialPage: ${e.javaClass.simpleName} ${e.message?.take(300)} id=$rawId"
                CatalogLog.w(shape)
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            }

            val streamItems: List<StreamInfoItem> = initialPage.items ?: emptyList()

            // Map on Default dispatcher (parse/extraction).
            val album: Album? = withContext(defaultDispatcher) {
                AlbumMappers.toAlbum(
                    albumId = rawId,
                    rawTitle = name,
                    artistName = uploaderName,
                    artistUrl = uploaderUrl,
                    rawYearText = descriptionText,
                    heroImages = thumbs,
                    trackItems = streamItems,
                )
            }

            if (album == null) {
                CatalogLog.w("album mapper returned null for id=$rawId name=${name.take(40)} — blank id law")
                return@withContext SwayResult.Failure(SwayError.ContentNotFound)
            }

            // Log extractor-side errors if any.
            val errors = initialPage.errors
            if (!errors.isNullOrEmpty()) {
                CatalogLog.w("album extractor reported ${errors.size} item errors: ${errors.first().message?.take(200)}")
            }

            SwayResult.Success(album)
        } catch (e: ReCaptchaException) {
            CatalogLog.w("album RateLimited (429): ${e.message?.take(120)} id=${id.value.take(20)}")
            SwayResult.Failure(SwayError.RateLimited)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("exceeds 10MB", ignoreCase = true) || msg.contains("exceeds limit", ignoreCase = true)) {
                CatalogLog.w("album UpstreamUnavailable (oversized): $msg")
                return@withContext SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
            val isOffline = msg.contains("Unable to resolve host", true) ||
                msg.contains("Failed to connect", true) ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e.cause is java.net.UnknownHostException
            if (isOffline) {
                CatalogLog.w("album Offline IOException: ${e.javaClass.simpleName} ${msg.take(120)}")
                SwayResult.Failure(SwayError.Offline)
            } else {
                CatalogLog.w("album Upstream IOException: ${e.javaClass.simpleName} ${msg.take(200)}")
                SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
        } catch (e: ParsingException) {
            val shape = "album Parse: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${id.value.take(20)}"
            CatalogLog.w(shape)
            SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
        } catch (e: ExtractionException) {
            val shape = "album Extraction: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${id.value.take(20)}"
            CatalogLog.w(shape)
            if (e.javaClass.simpleName.contains("NotFound", true) || e.message?.contains("not found", true) == true) {
                SwayResult.Failure(SwayError.ContentNotFound)
            } else {
                SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            }
        } catch (e: Exception) {
            CatalogLog.e("album Unknown: ${e.javaClass.simpleName} ${e.message} id=${id.value.take(20)}", e)
            SwayResult.Failure(SwayError.Unknown(e))
        }
    }

    // -------------------------------------------------------------------------
    // Artist detail — story 3.4 (FR-6 trace: top songs Must, albums/singles Should degraded)
    // -------------------------------------------------------------------------

    private suspend fun artistInternal(id: SourceId): SwayResult<Artist> = withContext(ioDispatcher) {
        try { NewPipeInitializer.initIfNeeded() } catch (e: Exception) {
            CatalogLog.w("artist NewPipe init failed: ${e.javaClass.simpleName} ${e.message}")
        }
        try {
            val rawId = id.value.trim()
            val factory = YoutubeChannelLinkHandlerFactory.getInstance()
            val url: String = try {
                factory.getUrl(rawId)
            } catch (e: ParsingException) {
                // Artist ids may be UC channel ids, handles (@name), or browse ids.
                // Fallback: construct generic channel URL for UC ids, or music browse for others.
                when {
                    rawId.startsWith("UC") -> "https://www.youtube.com/channel/$rawId"
                    rawId.startsWith("@") -> "https://www.youtube.com/$rawId"
                    rawId.startsWith("MPRE") || rawId.startsWith("UC") -> "https://music.youtube.com/browse/$rawId"
                    else -> throw e
                }
            }
            val linkHandler = try {
                factory.fromUrl(url)
            } catch (e: ParsingException) {
                CatalogLog.w("artist invalid id shape: $rawId ${e.message?.take(120)}")
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = "artist invalid id: $rawId".take(500)))
            }

            val channelExtractor = channelExtractorFactory?.invoke(linkHandler) ?: service.getChannelExtractor(linkHandler)
            channelExtractor.fetchPage()

            val name: String = try {
                channelExtractor.name
            } catch (e: ParsingException) {
                val shape = "artist Parse name: ${e.message?.take(300)} id=$rawId"
                CatalogLog.w(shape)
                return@withContext SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            }

            val avatarImages: List<Image> = try { channelExtractor.avatars } catch (_: Exception) { emptyList() }

            // Tabs drive discography discovery; degraded tier => no tabs means unavailable.
            val tabs: List<ListLinkHandler> = try {
                channelExtractor.tabs ?: emptyList()
            } catch (_: Exception) { emptyList() }

            // Collect raw items for mapping on Default dispatcher.
            // We fetch each tab's initial page best-effort; failures mark that section unavailable
            // rather than failing the whole artist.
            val topSongCandidates = mutableListOf<StreamInfoItem>()
            var albumItems: MutableList<PlaylistInfoItem>? = null
            var singleItems: MutableList<PlaylistInfoItem>? = null

            if (tabs.isNotEmpty()) {
                for (tabHandler in tabs) {
                    val tabExtractor: ChannelTabExtractor = try {
                        channelTabExtractorFactory?.invoke(tabHandler) ?: service.getChannelTabExtractor(tabHandler)
                    } catch (e: Exception) {
                        CatalogLog.w("artist tab extractor creation failed for ${tabHandler.url}: ${e.javaClass.simpleName}")
                        continue
                    }
                    val tabPage = try {
                        tabExtractor.fetchPage()
                        tabExtractor.initialPage
                    } catch (e: ReCaptchaException) {
                        throw e
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        CatalogLog.w("artist tab fetch failed for ${tabHandler.url}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                        continue
                    }
                    val items = tabPage.items ?: emptyList()
                    if (items.isEmpty()) continue
                    val first = items.firstOrNull() ?: continue
                    when {
                        first is StreamInfoItem -> {
                            // Top songs: preserve insertion order across tabs (first tab wins as primary)
                            if (topSongCandidates.isEmpty()) {
                                topSongCandidates.addAll(items.filterIsInstance<StreamInfoItem>())
                            } else {
                                // Additional stream tabs appended but not duplicated — keep first tab primary
                                CatalogLog.w("artist additional StreamInfo tab ignored for topSongs (primary already filled)")
                            }
                        }
                        first is PlaylistInfoItem -> {
                            val tabUrl = tabHandler.url ?: ""
                            val isSingles = tabUrl.contains("single", ignoreCase = true) || tabUrl.contains("singles", ignoreCase = true)
                            if (isSingles) {
                                if (singleItems == null) singleItems = mutableListOf()
                                singleItems!!.addAll(items.filterIsInstance<PlaylistInfoItem>())
                            } else {
                                if (albumItems == null) albumItems = mutableListOf()
                                albumItems!!.addAll(items.filterIsInstance<PlaylistInfoItem>())
                            }
                        }
                        else -> {
                            CatalogLog.w("artist tab with unknown InfoItem type ${first.javaClass.simpleName} url=${tabHandler.url}")
                        }
                    }
                    // Log per-tab extractor errors
                    val errs = tabPage.errors
                    if (!errs.isNullOrEmpty()) {
                        CatalogLog.w("artist tab extractor reported ${errs.size} errors for ${tabHandler.url}: ${errs.first().message?.take(200)}")
                    }
                }
            } else {
                // No tabs: degraded path — if extractor itself exposes no discography, we treat
                // albums/singles as unavailable (null) and top songs stays empty unless
                // the test double provided data via a custom path. For live sources that don't
                // expose tabs (e.g., music topic channels behind different endpoint), this is
                // correct degraded behavior.
                CatalogLog.w("artist no tabs for $rawId — discography unavailable (OQ-1 degraded)")
            }

            // If top songs still empty and tabs were empty, we allow empty list (will still be Success
            // but caller sees empty topSongs). Fixture contract expects playable non-empty when payload present;
            // empty here reflects degraded/no-song case — still Success with empty list, not Failure.
            // Mapping on Default dispatcher
            val artist: Artist? = withContext(defaultDispatcher) {
                ArtistMappers.toArtist(
                    artistId = rawId,
                    rawName = name,
                    avatarImages = avatarImages,
                    topSongItems = topSongCandidates,
                    albumItems = albumItems, // null => unavailable
                    singleItems = singleItems,
                )
            }

            if (artist == null) {
                CatalogLog.w("artist mapper returned null for id=$rawId name=${name.take(40)} — blank id law")
                return@withContext SwayResult.Failure(SwayError.ContentNotFound)
            }

            SwayResult.Success(artist)
        } catch (e: ReCaptchaException) {
            CatalogLog.w("artist RateLimited (429): ${e.message?.take(120)} id=${id.value.take(20)}")
            SwayResult.Failure(SwayError.RateLimited)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("exceeds 10MB", ignoreCase = true) || msg.contains("exceeds limit", ignoreCase = true)) {
                CatalogLog.w("artist UpstreamUnavailable (oversized): $msg")
                return@withContext SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
            val isOffline = msg.contains("Unable to resolve host", true) ||
                msg.contains("Failed to connect", true) ||
                e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e.cause is java.net.UnknownHostException
            if (isOffline) {
                CatalogLog.w("artist Offline IOException: ${e.javaClass.simpleName} ${msg.take(120)}")
                SwayResult.Failure(SwayError.Offline)
            } else {
                CatalogLog.w("artist Upstream IOException: ${e.javaClass.simpleName} ${msg.take(200)}")
                SwayResult.Failure(SwayError.UpstreamUnavailable)
            }
        } catch (e: ParsingException) {
            val shape = "artist Parse: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${id.value.take(20)}"
            CatalogLog.w(shape)
            SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
        } catch (e: ExtractionException) {
            val shape = "artist Extraction: ${e.javaClass.simpleName} ${e.message?.take(300)} id=${id.value.take(20)}"
            CatalogLog.w(shape)
            if (e.javaClass.simpleName.contains("NotFound", true) || e.message?.contains("not found", true) == true) {
                SwayResult.Failure(SwayError.ContentNotFound)
            } else {
                SwayResult.Failure(SwayError.Parse(shapeInfo = shape.take(500)))
            }
        } catch (e: Exception) {
            CatalogLog.e("artist Unknown: ${e.javaClass.simpleName} ${e.message} id=${id.value.take(20)}", e)
            SwayResult.Failure(SwayError.Unknown(e))
        }
    }

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

package com.sway.core.data

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.CatalogSource
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One search group's outcome (story 10.1, group-isolation law): each of the
 * four catalog types fails/succeeds INDEPENDENTLY - a failing group carries
 * its own error without blanking siblings.
 *
 * [Stale] is true ONLY when the payload was served from the Offline Fallback
 * Cache after a network failure (FR-4). Fresh network results are never
 * stale; cache misses surface the ORIGINAL typed failure.
 */
sealed interface GroupResult<out T> {
    data class Fresh<T>(val page: PagedResult<T>) : GroupResult<T>
    data class Stale<T>(val page: PagedResult<T>) : GroupResult<T>
    data class Failed(val error: SwayError) : GroupResult<Nothing>
}

data class SearchResults(
    val songs: GroupResult<Song>,
    val albums: GroupResult<Album>,
    val artists: GroupResult<Artist>,
    val playlists: GroupResult<CatalogPlaylist>,
)

/**
 * THE repository boundary over [CatalogSource] (story 10.1, NFR-2 exemplar,
 * FR-4 data integration): every port call maps to typed results, writes
 * through to the fallback cache keyed by request shape, and on
 * Offline/UpstreamUnavailable serves the stale cached payload when present.
 * Cache is NEVER consulted before the network fails (fresh-first law).
 *
 * Stale-service coverage: the SONGS group carries a full page codec today
 * (FR-1/FR-2/FR-4 are search-shaped); album/artist/catalogPlaylist search
 * groups surface typed failures offline until their detail screens demand
 * otherwise - their DETAIL fetches already stale-serve via [detail].
 */
class CatalogRepository(
    private val source: CatalogSource,
    cacheFactory: () -> FallbackCacheStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * LAZY cache construction: creating the store must never touch disk on the
     * caller's thread — the factory runs on first use, inside [io]-confined
     * paths only (AD-10 startup law).
     */
    private val cache: FallbackCacheStore by lazy(cacheFactory)

    /** Convenience for callers already holding a constructed store (tests). */
    constructor(
        source: CatalogSource,
        cache: FallbackCacheStore,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(source, { cache }, io)

    /** Page codec pair enabling stale service for one search group. */
    interface PageCodec<T> {
        fun encode(page: PagedResult<T>): String
        fun decode(raw: String): List<T>
    }

    suspend fun search(query: String, pageToken: String? = null): SearchResults {
        val suffix = pageToken?.let { ":$it" }.orEmpty()
        return SearchResults(
            songs = searchGroup(
                "songs", query, suffix,
                call = { q, t -> source.searchSongs(q, t) },
                codec = songPageCodec,
            ),
            albums = searchGroup("albums", query, suffix) { q, t -> source.searchAlbums(q, t) },
            artists = searchGroup("artists", query, suffix) { q, t -> source.searchArtists(q, t) },
            playlists = searchGroup("playlists", query, suffix) { q, t -> source.searchCatalogPlaylists(q, t) },
        )
    }

    // --- details ---------------------------------------------------------------

    suspend fun album(id: com.sway.core.model.SourceId): SwayResult<Album> =
        detail("album", id.value) { source.album(id) }

    suspend fun artist(id: com.sway.core.model.SourceId): SwayResult<Artist> =
        detail("artist", id.value) { source.artist(id) }

    suspend fun catalogPlaylist(id: com.sway.core.model.SourceId): SwayResult<CatalogPlaylist> =
        detail("catalogplaylist", id.value) { source.catalogPlaylist(id) }

    // --- internals ---------------------------------------------------------------

    private inner class SongPageCodecImpl : PageCodec<Song> {
        override fun encode(page: PagedResult<Song>): String = SongListJson.encodePage(page)
        override fun decode(raw: String): List<Song> = SongListJson.decodePage(raw)
    }

    private val songPageCodec: PageCodec<Song> = object : PageCodec<Song> {
        override fun encode(page: PagedResult<Song>): String = SongListJson.encodePage(page)
        override fun decode(raw: String): List<Song> = SongListJson.decodePage(raw)
    }

    private suspend fun <T> searchGroup(
        type: String,
        query: String,
        suffix: String,
        codec: PageCodec<T>? = null,
        call: suspend (String, String?) -> SwayResult<PagedResult<T>>,
    ): GroupResult<T> {
        val key = "search:$type:$query$suffix"
        val pageToken = suffix.removePrefix(":").takeIf { it.isNotEmpty() }
        return when (val fresh = call(query, pageToken)) {
            is SwayResult.Success -> {
                withContext(io) { codec?.let { cache.write(key, it.encode(fresh.data)) } }
                GroupResult.Fresh(fresh.data)
            }
            is SwayResult.Failure -> {
                if (!isFallbackEligible(fresh.error) || codec == null) {
                    return GroupResult.Failed(fresh.error)
                }
                val cachedRaw = withContext(io) { cache.readOnFailure(key) }
                    ?: return GroupResult.Failed(fresh.error)
                val items = codec.decode(cachedRaw)
                if (items.isEmpty()) return GroupResult.Failed(fresh.error)
                GroupResult.Stale(PagedResult(items, pageToken))
            }
        }
    }

    private suspend fun <T : Any> detail(
        type: String,
        id: String,
        call: suspend () -> SwayResult<T>,
    ): SwayResult<T> {
        val key = "$type:$id"
        return when (val fresh = call()) {
            is SwayResult.Success -> {
                withContext(io) { cache.write(key, DetailJson.encode(fresh.data)) }
                fresh
            }
            is SwayResult.Failure ->
                if (!isFallbackEligible(fresh.error)) fresh
                else {
                    val raw = withContext(io) { cache.readOnFailure(key) }
                    raw?.let { DetailJson.decode<T>(type, it).let { d -> d?.let { SwayResult.Success(it) } } }
                        ?: fresh
                }
        }
    }

    /** Only these failure categories earn stale service (FR-4). */
    private fun isFallbackEligible(error: SwayError): Boolean =
        error == SwayError.Offline || error == SwayError.UpstreamUnavailable
}

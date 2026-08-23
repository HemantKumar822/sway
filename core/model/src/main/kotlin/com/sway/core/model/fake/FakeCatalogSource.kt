package com.sway.core.model.fake

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.CatalogSource
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult

/**
 * Test/fake implementation of [CatalogSource] — story 2.4 compile-time contract.
 *
 * Exercises the port without real network/extractor (AD-1). All methods return
 * [SwayResult] wrapping [PagedResult] or typed models — never bare lists/strings
 * (NFR-2/AD-9). Use the mutable [Behavior] helpers in tests to inject per-category
 * failures (FR-37/NFR-2 verification clause).
 *
 * Pure Kotlin — zero Android imports.
 */
class FakeCatalogSource(
    var songsBehavior: suspend (query: String, token: String?) -> SwayResult<PagedResult<Song>> =
        { _, _ -> SwayResult.Success(PagedResult.empty()) },
    var albumsBehavior: suspend (query: String, token: String?) -> SwayResult<PagedResult<Album>> =
        { _, _ -> SwayResult.Success(PagedResult.empty()) },
    var artistsBehavior: suspend (query: String, token: String?) -> SwayResult<PagedResult<Artist>> =
        { _, _ -> SwayResult.Success(PagedResult.empty()) },
    var playlistsBehavior: suspend (query: String, token: String?) -> SwayResult<PagedResult<CatalogPlaylist>> =
        { _, _ -> SwayResult.Success(PagedResult.empty()) },
    var albumBehavior: suspend (SourceId) -> SwayResult<Album> =
        { SwayResult.Failure(SwayError.ContentNotFound) },
    var artistBehavior: suspend (SourceId) -> SwayResult<Artist> =
        { SwayResult.Failure(SwayError.ContentNotFound) },
    var catalogPlaylistBehavior: suspend (SourceId) -> SwayResult<CatalogPlaylist> =
        { SwayResult.Failure(SwayError.ContentNotFound) },
) : CatalogSource {

    val calls: MutableList<String> = mutableListOf()

    override suspend fun searchSongs(query: String, pageToken: String?): SwayResult<PagedResult<Song>> {
        calls += "searchSongs:$query:${pageToken ?: "null"}"
        return songsBehavior(query, pageToken)
    }

    override suspend fun searchAlbums(query: String, pageToken: String?): SwayResult<PagedResult<Album>> {
        calls += "searchAlbums:$query:${pageToken ?: "null"}"
        return albumsBehavior(query, pageToken)
    }

    override suspend fun searchArtists(query: String, pageToken: String?): SwayResult<PagedResult<Artist>> {
        calls += "searchArtists:$query:${pageToken ?: "null"}"
        return artistsBehavior(query, pageToken)
    }

    override suspend fun searchCatalogPlaylists(query: String, pageToken: String?): SwayResult<PagedResult<CatalogPlaylist>> {
        calls += "searchCatalogPlaylists:$query:${pageToken ?: "null"}"
        return playlistsBehavior(query, pageToken)
    }

    override suspend fun album(id: SourceId): SwayResult<Album> {
        calls += "album:${id.value}"
        return albumBehavior(id)
    }

    override suspend fun artist(id: SourceId): SwayResult<Artist> {
        calls += "artist:${id.value}"
        return artistBehavior(id)
    }

    override suspend fun catalogPlaylist(id: SourceId): SwayResult<CatalogPlaylist> {
        calls += "catalogPlaylist:${id.value}"
        return catalogPlaylistBehavior(id)
    }

    /** Helper to stub a one-page success for songs. */
    fun stubSongsPage(songs: List<Song>, nextToken: String? = null) {
        songsBehavior = { _, _ -> SwayResult.Success(PagedResult.of(songs, nextToken)) }
    }

    /** Helper to inject a failure for all search types. */
    fun injectSearchFailure(error: SwayError) {
        val failure: SwayResult<PagedResult<Song>> = SwayResult.Failure(error)
        songsBehavior = { _, _ -> failure }
        @Suppress("UNCHECKED_CAST")
        albumsBehavior = { _, _ -> SwayResult.Failure(error) as SwayResult<PagedResult<Album>> }
        artistsBehavior = { _, _ -> SwayResult.Failure(error) as SwayResult<PagedResult<Artist>> }
        playlistsBehavior = { _, _ -> SwayResult.Failure(error) as SwayResult<PagedResult<CatalogPlaylist>> }
    }
}

package com.sway.core.data

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.CatalogSource
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Story 10.3 — per-group page fetch laws (FR-2): request-shape cache keys
 * include the token, write-through on success, stale service ONLY on eligible
 * failures and ONLY where a codec exists (songs), group isolation intact.
 */
class CatalogPaginationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun song(id: String) = Song.create(id, "Song $id")!!

    private class FakeSource(
        var songsByToken: MutableMap<String?, SwayResult<PagedResult<Song>>> = mutableMapOf(),
        var albumsOnline: Boolean = true,
    ) : CatalogSource {
        override suspend fun searchSongs(query: String, pageToken: String?) =
            songsByToken[pageToken] ?: SwayResult.Failure(SwayError.ContentNotFound)
        override suspend fun searchAlbums(query: String, pageToken: String?): SwayResult<PagedResult<Album>> =
            if (albumsOnline) {
                SwayResult.Success(PagedResult.singlePage(emptyList()))
            } else {
                SwayResult.Failure(SwayError.Offline)
            }
        override suspend fun searchArtists(query: String, pageToken: String?) =
            SwayResult.Success<PagedResult<Artist>>(PagedResult.singlePage(emptyList()))
        override suspend fun searchCatalogPlaylists(query: String, pageToken: String?) =
            SwayResult.Success<PagedResult<CatalogPlaylist>>(PagedResult.singlePage(emptyList()))
        override suspend fun album(id: SourceId) = SwayResult.Failure(SwayError.ContentNotFound)
        override suspend fun artist(id: SourceId) = SwayResult.Failure(SwayError.ContentNotFound)
        override suspend fun catalogPlaylist(id: SourceId) = SwayResult.Failure(SwayError.ContentNotFound)
    }

    private fun repo(source: FakeSource): Pair<CatalogRepository, File> {
        val dir = File(tmp.root, "cache-${System.nanoTime()}")
        return CatalogRepository(source, FallbackCacheStore(dir)) to dir
    }

    @Test
    fun songsPage_tokenKey_writeThrough_thenOfflineServesStale() = runTest {
        val source = FakeSource(
            mutableMapOf(
                "t1" to SwayResult.Success(PagedResult.singlePage(listOf(song("s2")))),
            ),
        )
        val (repository, _) = repo(source)

        val fresh = repository.songsPage("neon", "t1")
        assertTrue(fresh is GroupResult.Fresh)
        assertEquals(listOf("Song s2"), (fresh as GroupResult.Fresh).page.items.map { it.title })

        // Network dies; the fresh-first law serves the cached token page STALE.
        source.songsByToken["t1"] = SwayResult.Failure(SwayError.Offline)
        val stale = repository.songsPage("neon", "t1")
        assertTrue(stale is GroupResult.Stale)
        assertEquals(listOf("Song s2"), (stale as GroupResult.Stale).page.items.map { it.title })
    }

    @Test
    fun albumsPage_noCodec_offline_failsTyped_neverStale() = runTest {
        val source = FakeSource(albumsOnline = false)
        val (repository, _) = repo(source)
        val result = repository.albumsPage("neon", "t1")
        assertTrue(result is GroupResult.Failed)
        assertEquals(SwayError.Offline::class, (result as GroupResult.Failed).error::class)
    }

    @Test
    fun pageCacheKeys_separatePerToken() = runTest {
        val source = FakeSource(
            mutableMapOf(
                "t1" to SwayResult.Success(PagedResult.singlePage(listOf(song("s1")))),
                "t2" to SwayResult.Success(PagedResult.singlePage(listOf(song("s2")))),
            ),
        )
        val (repository, dir) = repo(source)
        repository.songsPage("neon", "t1")
        repository.songsPage("neon", "t2")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".fallbackcache.json") } ?: emptyArray()
        assertEquals(2, files.size)
    }
}

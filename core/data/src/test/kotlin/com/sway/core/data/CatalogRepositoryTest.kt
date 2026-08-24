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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 10.1 — NFR-2 pattern-exemplar verification: every SwayError category
 * mapped through the repository boundary; stale-vs-miss distinction; group
 * isolation; fresh-first write-through.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CatalogRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var failWith: SwayError? = null

    private fun song(id: String) =
        Song.create(id = id, rawTitle = "Song $id", artistName = "A", durationMs = 60_000)!!

    private fun source() = object : CatalogSource {
        override suspend fun searchSongs(query: String, pageToken: String?): SwayResult<PagedResult<Song>> {
            failWith?.let { return SwayResult.Failure(it) }
            val n = if (pageToken == null) "1" else "2"
            return SwayResult.Success(PagedResult(listOf(song("$query-n$n")), nextPageToken = "$query-page2"))
        }
        override suspend fun searchAlbums(query: String, pageToken: String?): SwayResult<PagedResult<Album>> {
            failWith?.let { return SwayResult.Failure(it) }
            return SwayResult.Success(PagedResult(emptyList(), nextPageToken = null))
        }
        override suspend fun searchArtists(query: String, pageToken: String?): SwayResult<PagedResult<Artist>> {
            failWith?.let { return SwayResult.Failure(it) }
            return SwayResult.Success(PagedResult(emptyList(), nextPageToken = null))
        }
        override suspend fun searchCatalogPlaylists(query: String, pageToken: String?): SwayResult<PagedResult<CatalogPlaylist>> {
            failWith?.let { return SwayResult.Failure(it) }
            return SwayResult.Success(PagedResult(emptyList(), nextPageToken = null))
        }
        override suspend fun album(id: SourceId): SwayResult<Album> {
            failWith?.let { return SwayResult.Failure(it) }
            return Album.create(id = id.value, rawTitle = "Album $id", tracks = listOf(song("t1")))!!
                .let { SwayResult.Success(it) }
        }
        override suspend fun artist(id: SourceId): SwayResult<Artist> =
            Artist.create(id = id.value, rawName = "Artist $id")!!.let { SwayResult.Success(it) }
        override suspend fun catalogPlaylist(id: SourceId): SwayResult<CatalogPlaylist> {
            failWith?.let { return SwayResult.Failure(it) }
            return SwayResult.Success(
                CatalogPlaylist.create(id = id.value, rawTitle = "CP $id", curator = null)!!,
            )
        }
    }

    @Test
    fun freshSearch_persistsToCache_andReturnsNonStale() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)

        val r = repo.search("lofi")
        assertTrue(r.songs is GroupResult.Fresh)
        assertEquals(listOf("lofi-n1"), (r.songs as GroupResult.Fresh).page.items.map { it.id.value })

        // Write-through proof: file exists in the cache dir.
        assertTrue(cache.dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun offline_withCacheHit_returnsStaleSuccess_distinctFromMiss() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)

        // Prime the cache while healthy.
        repo.search("lofi")

        // Network dies.
        failWith = SwayError.Offline
        val hit = repo.search("lofi")
        assertTrue(hit.songs is GroupResult.Stale)
        assertEquals(listOf("lofi-n1"), (hit.songs as GroupResult.Stale).page.items.map { it.id.value })

        // Miss (never searched before) -> original typed failure, NOT empty success.
        val miss = repo.search("brandnew")
        assertTrue(miss.songs is GroupResult.Failed)
        assertEquals(SwayError.Offline, (miss.songs as GroupResult.Failed).error)
        failWith = null
    }

    @Test
    fun upstreamUnavailable_alsoEligibleForStale() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)
        repo.search("chill")
        failWith = SwayError.UpstreamUnavailable
        val r = repo.search("chill")
        assertTrue(r.songs is GroupResult.Stale)
        failWith = null
    }

    @Test
    fun rateLimited_NOTEligibleForStale_surfacesTyped() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)
        repo.search("x")
        failWith = SwayError.RateLimited
        val r = repo.search("x")
        assertTrue(r.songs is GroupResult.Failed)
        assertEquals(SwayError.RateLimited, (r.songs as GroupResult.Failed).error)
        failWith = null
    }

    @Test
    fun groupIsolation_songsFailWhileOthersSucceed_siblingsNotBlanked() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())

        // Custom source failing ONLY songs.
        val src = object : CatalogSource by source() {
            override suspend fun searchSongs(query: String, pageToken: String?) =
                SwayResult.Failure(SwayError.Offline)
        }
        val repo = CatalogRepository(src, cache)
        val r = repo.search("mix")

        assertTrue("songs group carries its own failure", r.songs is GroupResult.Failed)
        assertTrue("albums sibling unaffected", r.albums is GroupResult.Fresh)
        assertTrue("artists sibling unaffected", r.artists is GroupResult.Fresh)
        assertTrue("playlists sibling unaffected", r.playlists is GroupResult.Fresh)
    }

    @Test
    fun pagination_usesDifferentCacheKeys() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)

        repo.search("q")
        failWith = SwayError.Offline
        // Page-2 token key was never cached -> Failed (distinct from page-1 Stale).
        val p2 = repo.search("q", pageToken = "q-page2")
        assertTrue(p2.songs is GroupResult.Failed)
        failWith = null
    }

    @Test
    fun detailAlbum_offline_staleServesFromCache() = runBlocking {
        val cache = FallbackCacheStore(tmp.newFolder())
        val repo = CatalogRepository(source(), cache)
        val id = SourceId.parse("alb-1")!!

        repo.album(id) // prime
        failWith = SwayError.Offline
        val stale = repo.album(id)
        assertTrue(stale is SwayResult.Success)
        assertEquals("Album alb-1", (stale as SwayResult.Success).data.title)
        assertTrue(stale.data.tracks.isNotEmpty())
        failWith = null
    }
}

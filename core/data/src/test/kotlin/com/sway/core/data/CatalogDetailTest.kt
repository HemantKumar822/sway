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
 * Story 10.4/10.5-10.7 data substrate — stale-aware detail results: Fresh on
 * success, Stale from the fallback cache ONLY on eligible failures (with
 * tracks intact), typed Failed otherwise. Exercises the new artist/
 * catalogplaylist codec round-trips too.
 */
class CatalogDetailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun song(id: String) = Song.create(id, "Song $id", artistName = "Artist")!!

    private class FakeSource(
        var albumResult: SwayResult<Album> = SwayResult.Failure(SwayError.ContentNotFound),
        var artistResult: SwayResult<Artist> = SwayResult.Failure(SwayError.ContentNotFound),
        var playlistResult: SwayResult<CatalogPlaylist> = SwayResult.Failure(SwayError.ContentNotFound),
    ) : CatalogSource {
        override suspend fun searchSongs(query: String, pageToken: String?) =
            SwayResult.Success(PagedResult.empty<Song>())
        override suspend fun searchAlbums(query: String, pageToken: String?) =
            SwayResult.Success(PagedResult.empty<Album>())
        override suspend fun searchArtists(query: String, pageToken: String?) =
            SwayResult.Success(PagedResult.empty<Artist>())
        override suspend fun searchCatalogPlaylists(query: String, pageToken: String?) =
            SwayResult.Success(PagedResult.empty<CatalogPlaylist>())
        override suspend fun album(id: SourceId) = albumResult
        override suspend fun artist(id: SourceId) = artistResult
        override suspend fun catalogPlaylist(id: SourceId) = playlistResult
    }

    private fun repo(source: FakeSource): Pair<CatalogRepository, File> {
        val dir = File(tmp.root, "cache-${System.nanoTime()}")
        return CatalogRepository(source, FallbackCacheStore(dir)) to dir
    }

    @Test
    fun album_freshThenOffline_staleServesWithTracksIntact() = runTest {
        val source = FakeSource(
            albumResult = SwayResult.Success(
                Album.create("al1", "Album One", artistName = "Luna", year = 2021, tracks = listOf(song("t1"), song("t2")))!!,
            ),
        )
        val (repository, _) = repo(source)
        val id = SourceId.parse("al1")!!

        val fresh = repository.albumDetail(id)
        assertTrue(fresh is DetailResult.Fresh)
        assertEquals(2, (fresh as DetailResult.Fresh).data.tracks.size)

        source.albumResult = SwayResult.Failure(SwayError.Offline)
        val stale = repository.albumDetail(id)
        assertTrue(stale is DetailResult.Stale)
        assertEquals(listOf("Song t1", "Song t2"), (stale as DetailResult.Stale).data.tracks.map { it.title })
        assertEquals(2021, (stale as DetailResult.Stale).data.year)
    }

    @Test
    fun rateLimited_neverStale_typedFailureSurfaces() = runTest {
        val source = FakeSource(
            albumResult = SwayResult.Success(Album.create("al1", "Album One")!!),
        )
        val (repository, _) = repo(source)
        val id = SourceId.parse("al1")!!
        repository.albumDetail(id)

        source.albumResult = SwayResult.Failure(SwayError.RateLimited)
        val result = repository.albumDetail(id)
        assertTrue(result is DetailResult.Failed)
        assertEquals(SwayError.RateLimited::class, (result as DetailResult.Failed).error::class)
    }

    @Test
    fun artist_and_catalogplaylist_codecs_roundTrip_topSongsAndCurator() = runTest {
        val source = FakeSource(
            artistResult = SwayResult.Success(
                Artist.create("ar1", "Luna", topSongs = listOf(song("s1")), albumsAvailable = false)!!,
            ),
            playlistResult = SwayResult.Success(
                CatalogPlaylist.create("pl1", "Chill Mix", curator = "Sway", tracks = listOf(song("s2")))!!,
            ),
        )
        val (repository, _) = repo(source)
        repository.artistDetail(SourceId.parse("ar1")!!)
        repository.catalogPlaylistDetail(SourceId.parse("pl1")!!)

        source.artistResult = SwayResult.Failure(SwayError.UpstreamUnavailable)
        source.playlistResult = SwayResult.Failure(SwayError.Offline)

        val staleArtist = repository.artistDetail(SourceId.parse("ar1")!!)
        assertTrue(staleArtist is DetailResult.Stale)
        assertEquals("Luna", (staleArtist as DetailResult.Stale).data.name)
        assertEquals(listOf("Song s1"), staleArtist.data.topSongs.map { it.title })

        val stalePlaylist = repository.catalogPlaylistDetail(SourceId.parse("pl1")!!)
        assertTrue(stalePlaylist is DetailResult.Stale)
        assertEquals("Sway", (stalePlaylist as DetailResult.Stale).data.curator)
        assertEquals(listOf("Song s2"), stalePlaylist.data.tracks.map { it.title })
    }
}

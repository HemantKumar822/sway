package com.sway.music.screens.search

import com.sway.core.data.CatalogRepository
import com.sway.core.data.FallbackCacheStore
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.CatalogSource
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Story 10.2 — SearchViewModel laws: debounce/submit, group isolation,
 * typed Empty vs Error escalation, recents dedupe/cap/clear, retry preserving
 * the query, stale marking via the real 10.1 repository + fallback cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun song(id: String) = Song.create(id, "Song $id", artistName = "Artist", durationMs = 200_000L)!!
    private fun album(id: String) = Album.create(id, "Album $id", artistName = "Artist")!!
    private fun artist(id: String) = Artist.create(id, "Artist $id")!!
    private fun playlist(id: String) = CatalogPlaylist.create(id, "Playlist $id", curator = "Curator")!!

    private class FakeCatalogSource(
        var songsResult: SwayResult<PagedResult<Song>> =
            SwayResult.Success(PagedResult.singlePage(emptyList())),
        var albumsResult: SwayResult<PagedResult<Album>> =
            SwayResult.Success(PagedResult.singlePage(emptyList())),
        var artistsResult: SwayResult<PagedResult<Artist>> =
            SwayResult.Success(PagedResult.singlePage(emptyList())),
        var playlistsResult: SwayResult<PagedResult<CatalogPlaylist>> =
            SwayResult.Success(PagedResult.singlePage(emptyList())),
    ) : CatalogSource {
        var searchCalls = 0
        override suspend fun searchSongs(query: String, pageToken: String?) =
            songsResult.also { searchCalls++ }
        override suspend fun searchAlbums(query: String, pageToken: String?) = albumsResult
        override suspend fun searchArtists(query: String, pageToken: String?) = artistsResult
        override suspend fun searchCatalogPlaylists(query: String, pageToken: String?) = playlistsResult
        override suspend fun album(id: SourceId): SwayResult<Album> =
            SwayResult.Failure(SwayError.ContentNotFound)
        override suspend fun artist(id: SourceId): SwayResult<Artist> =
            SwayResult.Failure(SwayError.ContentNotFound)
        override suspend fun catalogPlaylist(id: SourceId): SwayResult<CatalogPlaylist> =
            SwayResult.Failure(SwayError.ContentNotFound)
    }

    private fun newVm(
        source: FakeCatalogSource,
        scope: CoroutineScope,
        io: kotlinx.coroutines.CoroutineDispatcher,
        cacheDir: File? = null,
    ): Pair<SearchViewModel, InMemoryRecentSearchStore> {
        val repo = CatalogRepository(
            source,
            FallbackCacheStore(cacheDir ?: File(tmp.root, "cache-${System.nanoTime()}")),
            io,
        )
        val recents = InMemoryRecentSearchStore()
        return SearchViewModel(repo, recents, scope, io) to recents
    }

    @Test
    fun typing_autoSearchesOnlyAfterDebounceWindow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeCatalogSource(
            songsResult = SwayResult.Success(PagedResult.singlePage(listOf(song("s1")))),
        )
        val (vm, _) = newVm(source, CoroutineScope(dispatcher), dispatcher)

        vm.onQueryChanged("neon")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        assertEquals(SearchPhase.Idle, vm.state.value.phase)

        advanceUntilIdle()
        assertTrue(vm.state.value.phase is SearchPhase.Results)
        assertEquals(1, source.searchCalls)
    }

    @Test
    fun submit_bypassesDebounce_immediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeCatalogSource(
            songsResult = SwayResult.Success(PagedResult.singlePage(listOf(song("s1")))),
        )
        val (vm, _) = newVm(source, CoroutineScope(dispatcher), dispatcher)
        vm.onQueryChanged("neon")
        advanceUntilIdle()

        // A NEW keystroke re-arms the debounce; submit must fire without waiting.
        vm.onQueryChanged("neon n")
        vm.onSubmit()
        advanceUntilIdle()
        assertEquals(2, source.searchCalls)
        assertEquals("neon n", vm.state.value.submittedQuery)
    }

    @Test
    fun blankQuery_returnsToIdle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, _) = newVm(FakeCatalogSource(), CoroutineScope(dispatcher), dispatcher)
        vm.onQueryChanged("x")
        advanceUntilIdle()
        vm.onQueryChanged("")
        advanceUntilIdle()
        assertEquals(SearchPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun groupIsolation_failingSongs_neverBlankSiblings() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeCatalogSource(
            songsResult = SwayResult.Failure(SwayError.Offline),
            albumsResult = SwayResult.Success(PagedResult.singlePage(listOf(album("a1")))),
        )
        val (vm, _) = newVm(source, CoroutineScope(dispatcher), dispatcher)
        vm.onQueryChanged("neon")
        advanceUntilIdle()

        val phase = vm.state.value.phase
        assertTrue(phase is SearchPhase.Results)
        val content = (phase as SearchPhase.Results).content
        assertEquals(SwayErrorUiState.Offline, content.songs.error)
        assertEquals(listOf("Album a1"), content.albums.items.map { it.title })
    }

    @Test
    fun zeroMatches_typedEmpty_distinctFromFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, _) = newVm(FakeCatalogSource(), CoroutineScope(dispatcher), dispatcher) // all Success(empty)
        vm.onQueryChanged("chandelier sett")
        advanceUntilIdle()
        assertEquals(SearchPhase.Empty, vm.state.value.phase)
    }

    @Test
    fun allGroupsFail_escalatesToError_retryPreservesQueryAndRecovers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fail = SwayResult.Failure(SwayError.Offline)
        val source = FakeCatalogSource(fail, fail, fail, fail)
        val (vm, _) = newVm(source, CoroutineScope(dispatcher), dispatcher)
        vm.onQueryChanged("neon")
        advanceUntilIdle()
        assertTrue(vm.state.value.phase is SearchPhase.Error)

        // Recovery path: retry preserves the submitted query verbatim.
        source.songsResult = SwayResult.Success(PagedResult.singlePage(listOf(song("s1"))))
        vm.onRetry()
        advanceUntilIdle()
        assertEquals("neon", vm.state.value.submittedQuery)
        assertTrue(vm.state.value.phase is SearchPhase.Results)
    }

    @Test
    fun recents_dedupeMovesToFront_capsAtTen_clearPersists() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, store) = newVm(FakeCatalogSource(), CoroutineScope(dispatcher), dispatcher)

        listOf("a", "b", "a").forEach { q ->
            vm.onQueryChanged(q)
            vm.onSubmit()
            advanceUntilIdle()
        }
        assertEquals(listOf("a", "b"), vm.state.value.recentSearches)

        repeat(12) { i ->
            vm.onQueryChanged("q$i")
            vm.onSubmit()
            advanceUntilIdle()
        }
        assertEquals(SearchViewModel.RECENTS_CAP, vm.state.value.recentSearches.size)

        vm.onClearRecents()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.state.value.recentSearches)
        assertEquals(emptyList<String>(), store.current)
    }

    @Test
    fun staleGroup_servedViaFallbackCache_marksStale() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dir = tmp.newFolder("stale-cache")
        val source = FakeCatalogSource(
            songsResult = SwayResult.Success(PagedResult.singlePage(listOf(song("s1")))),
        )
        val repo = CatalogRepository(source, FallbackCacheStore(dir), dispatcher)
        val vm = SearchViewModel(repo, InMemoryRecentSearchStore(), CoroutineScope(dispatcher), dispatcher)

        vm.onQueryChanged("neon")
        advanceUntilIdle()
        assertTrue((vm.state.value.phase as SearchPhase.Results).content.songs.stale.not())

        // Network dies; the 10.1 fresh-first law serves the cached page STALE.
        source.songsResult = SwayResult.Failure(SwayError.Offline)
        vm.onRetry()
        advanceUntilIdle()
        val content = (vm.state.value.phase as SearchPhase.Results).content
        assertTrue(content.songs.stale)
        assertEquals(listOf("Song s1"), content.songs.items.map { it.title })
    }
}



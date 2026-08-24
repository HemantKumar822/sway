package com.sway.music.screens.detail

import com.sway.core.model.Album
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayErrorUiState
import com.sway.core.data.DetailResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stories 10.5–10.7 — detail quintet laws + the FR-22 queue CONTRACT:
 * Play/tap builds the full ordered collection at the chosen start index;
 * Shuffle is seeded-deterministic; single-element shuffle is identity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailViewModelTest {

    private fun song(id: String) = Song.create(id, "Song $id")!!

    @Test
    fun playback_playFromTapIndex_fullCollectionAtChosenPosition() {
        val tracks = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))
        val request = PlaybackRequests.build(tracks, PlaybackRequests.Mode.FromIndex(3))
        // FR-22 semantics at contract level: all 5 items queued, starting at #4.
        assertEquals(tracks, request.items)
        assertEquals(3, request.startIndex)
        assertEquals(false, request.shuffled)
    }

    @Test
    fun playback_playButton_startsAtTrackOne() {
        val tracks = listOf(song("a"), song("b"))
        val request = PlaybackRequests.build(tracks, PlaybackRequests.Mode.FromIndex(0))
        assertEquals(0, request.startIndex)
    }

    @Test
    fun playback_shuffle_seededDeterministic_permutation() {
        val tracks = (1..8).map { song("s$it") }
        val r1 = PlaybackRequests.build(tracks, PlaybackRequests.Mode.Shuffled(seed = 11L))
        val r2 = PlaybackRequests.build(tracks, PlaybackRequests.Mode.Shuffled(seed = 11L))
        val r3 = PlaybackRequests.build(tracks, PlaybackRequests.Mode.Shuffled(seed = 99L))

        assertEquals(r1.items.map { it.id }, r2.items.map { it.id })
        assertTrue(r1.items.map { it.id } != r3.items.map { it.id })
        // Permutation law: same multiset of ids.
        assertEquals(tracks.map { it.id }.toSet(), r1.items.map { it.id }.toSet())
        assertTrue(r1.shuffled)
        assertEquals(0, r1.startIndex)
    }

    @Test
    fun playback_shuffle_singleElement_identity() {
        val tracks = listOf(song("only"))
        val r = PlaybackRequests.build(tracks, PlaybackRequests.Mode.Shuffled(seed = 7L))
        assertEquals(listOf(tracks.single().id), r.items.map { it.id })
        assertEquals(0, r.startIndex)
    }

    @Test
    fun detailState_stale_carriesBadgeFlag_errorMapsCategory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        var result: DetailResult<Album> =
            DetailResult.Stale(Album.create("al1", "Album One", tracks = listOf(song("t1")))!!)
        val staleVm = object : CatalogDetailViewModel<Album>({ result }, CoroutineScope(dispatcher)) {}
        advanceUntilIdle()
        val state = staleVm.state.value
        assertTrue(state is DetailState.Content && state.stale)

        result = DetailResult.Failed(SwayError.Offline)
        val errorVm = object : CatalogDetailViewModel<Album>({ result }, CoroutineScope(dispatcher)) {}
        advanceUntilIdle()
        assertEquals(DetailState.Error(SwayErrorUiState.Offline), errorVm.state.value)
    }
}

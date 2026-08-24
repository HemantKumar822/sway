package com.sway.music.screens.library

import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 11.3 — editor VM delegation laws over a recording fake: every edit
 * persists IMMEDIATELY (ops invoked as the user acts, no save step), move
 * computes a full permutation payload, delete fires the navigation callback
 * only on success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistEditorViewModelTest {

    private fun song(id: String) = Song.create(id, "Song $id")!!

    private class FakeOps(
        initial: List<Song> = emptyList(),
        var failNext: Boolean = false,
    ) : PlaylistEditorOps {
        val flow = MutableStateFlow(initial)
        val calls = mutableListOf<String>()

        override fun observeSongs(playlistId: String): Flow<List<Song>> = flow
        override suspend fun rename(playlistId: String, rawName: String): SwayResult<Unit> =
            record("rename:$rawName")

        override suspend fun delete(playlistId: String): SwayResult<Unit> = record("delete")
        override suspend fun removeSong(playlistId: String, sourceId: String): SwayResult<Unit> {
            val result = record("remove:$sourceId")
            // Faithful fake: mirror the DB mutation the real ops performs.
            if (result is SwayResult.Success) {
                flow.value = flow.value.filterNot { it.id.value == sourceId }
            }
            return result
        }

        override suspend fun moveSong(playlistId: String, fromIndex: Int, toIndex: Int): SwayResult<Unit> {
            val ids = flow.value.map { it.id.value }
            return record("move:$fromIndex->$toIndex:${ids.joinToString(",")}")
        }

        override suspend fun addBatch(playlistId: String, songs: List<Song>): SwayResult<Unit> =
            record("addBatch:${songs.joinToString("|") { it.id.value }}")

        private fun record(call: String): SwayResult<Unit> =
            if (failNext) SwayResult.Failure(SwayError.Storage)
            else {
                calls.add(call)
                SwayResult.Success(Unit)
            }
    }

    @Test
    fun edits_persistImmediately_viaOps() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ops = FakeOps(listOf(song("a"), song("b"), song("c")))
        val vm = PlaylistEditorViewModel("pl1", ops, CoroutineScope(dispatcher))
        advanceUntilIdle()

        vm.removeAt(0) // removes "Song a"
        vm.move(0, 1)  // remaining [b, c]: b -> index 1
        vm.rename("Gym")
        advanceUntilIdle()

        assertEquals(listOf("remove:a", "move:0->1:b,c", "rename:Gym"), ops.calls)

        vm.toggleEditMode()
        advanceUntilIdle()
        assertEquals(true, vm.editMode.value)
    }

    @Test
    fun delete_onSuccess_firesNavigationCallback_onlyThen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ops = FakeOps(failNext = true)
        val vm = PlaylistEditorViewModel("pl1", ops, CoroutineScope(dispatcher))

        var navigated = false
        vm.delete { navigated = true }
        advanceUntilIdle()
        assertEquals(false, navigated)
        assertEquals("Couldn't delete. Try again.", vm.lastMessage.value)

        ops.failNext = false
        vm.delete { navigated = true }
        advanceUntilIdle()
        assertEquals(true, navigated)
    }

    @Test
    fun batchAdd_passesSongsThrough_andReportsCount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ops = FakeOps()
        val vm = PlaylistEditorViewModel("pl1", ops, CoroutineScope(dispatcher))

        vm.addBatch(listOf(song("x"), song("y")))
        advanceUntilIdle()
        assertEquals(listOf("addBatch:x|y"), ops.calls)
        assertEquals("Added 2 songs", vm.lastMessage.value)
    }
}

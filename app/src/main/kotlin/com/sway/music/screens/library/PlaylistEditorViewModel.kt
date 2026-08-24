package com.sway.music.screens.library

import com.sway.core.data.PlaylistRepository
import com.sway.core.model.PlaylistId
import com.sway.core.model.Song
import com.sway.core.model.SwayResult
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Narrow operations seam for the playlist editor (story 11.3): the real impl
 * wraps [PlaylistRepository] (whose atomicity/contiguity laws are proven in
 * the 8.2 suites); hermetic fakes record calls to prove immediate-persistence.
 */
interface PlaylistEditorOps {
    fun observeSongs(playlistId: String): Flow<List<Song>>
    suspend fun rename(playlistId: String, rawName: String): SwayResult<Unit>
    suspend fun delete(playlistId: String): SwayResult<Unit>
    suspend fun removeSong(playlistId: String, sourceId: String): SwayResult<Unit>
    suspend fun moveSong(playlistId: String, fromIndex: Int, toIndex: Int): SwayResult<Unit>
    suspend fun addBatch(playlistId: String, songs: List<Song>): SwayResult<Unit>
}

class RepositoryPlaylistEditorOps(private val repo: PlaylistRepository) : PlaylistEditorOps {
    override fun observeSongs(playlistId: String): Flow<List<Song>> =
        repo.observeSongs(PlaylistId(playlistId))

    override suspend fun rename(playlistId: String, rawName: String) =
        repo.rename(PlaylistId(playlistId), rawName)

    override suspend fun delete(playlistId: String) = repo.delete(PlaylistId(playlistId))

    override suspend fun removeSong(playlistId: String, sourceId: String) =
        repo.removeSong(PlaylistId(playlistId), sourceId)

    /** Move computes the full desired ordering; repo enforces permutation law. */
    override suspend fun moveSong(playlistId: String, fromIndex: Int, toIndex: Int): SwayResult<Unit> {
        val current = repo.observeSongs(PlaylistId(playlistId)).first()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) {
            return SwayResult.Failure(com.sway.core.model.SwayError.Parse("move out of bounds"))
        }
        val mutable = current.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return repo.reorder(PlaylistId(playlistId), mutable.map { it.id.value })
    }

    override suspend fun addBatch(playlistId: String, songs: List<Song>): SwayResult<Unit> {
        var last: SwayResult<Unit> = SwayResult.Success(Unit)
        for (song in songs) {
            last = repo.addSong(PlaylistId(playlistId), song)
            if (last is SwayResult.Failure) return last
        }
        return last
    }
}

data class PlaylistEditorUiState(
    val songs: List<Song> = emptyList(),
    val editMode: Boolean = false,
)

/**
 * Editor ViewModel (story 11.3, FR-32): every operation persists IMMEDIATELY
 * through [PlaylistEditorOps] (no save button exists); UI state mirrors the
 * flow truth. Results surface via [lastMessage] for snackbar honesty (NFR-2).
 */
class PlaylistEditorViewModel(
    private val playlistId: String,
    private val ops: PlaylistEditorOps,
    private val scope: CoroutineScope,
) {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    /** Last human-readable outcome line ("Added to Gym", "Removed", ...). */
    val lastMessage = MutableStateFlow<String?>(null)

    init {
        scope.launch { ops.observeSongs(playlistId).collect { _songs.value = it } }
    }

    fun toggleEditMode() {
        _editMode.value = !_editMode.value
    }

    fun removeAt(index: Int) {
        scope.launch {
            val song = _songs.value.getOrNull(index) ?: return@launch
            ops.removeSong(playlistId, song.id.value)
            lastMessage.value = "Removed \"${song.title}\""
        }
    }

    fun move(fromIndex: Int, toIndex: Int) {
        scope.launch {
            ops.moveSong(playlistId, fromIndex, toIndex)
            lastMessage.value = "Moved"
        }
    }

    fun rename(rawName: String) {
        scope.launch {
            when (ops.rename(playlistId, rawName)) {
                is SwayResult.Success -> lastMessage.value = "Renamed"
                is SwayResult.Failure -> lastMessage.value = "Couldn't rename. Try another name."
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        scope.launch {
            when (ops.delete(playlistId)) {
                is SwayResult.Success -> onDeleted()
                is SwayResult.Failure -> lastMessage.value = "Couldn't delete. Try again."
            }
        }
    }

    fun addBatch(songs: List<Song>) {
        scope.launch {
            when (ops.addBatch(playlistId, songs)) {
                is SwayResult.Success -> lastMessage.value = "Added ${songs.size} songs"
                is SwayResult.Failure -> lastMessage.value = "Couldn't add songs. Try again."
            }
        }
    }

    // Playback contracts (FR-22 trace; engine wiring E12).
    fun playRequest(index: Int = 0): PlaybackRequest? =
        PlaybackRequests.build(_songs.value, PlaybackRequests.Mode.FromIndex(index)).takeIf { _songs.value.isNotEmpty() }

    fun shuffleRequest(seed: Long): PlaybackRequest? =
        PlaybackRequests.build(_songs.value, PlaybackRequests.Mode.Shuffled(seed)).takeIf { _songs.value.isNotEmpty() }
}


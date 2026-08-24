package com.sway.music.screens.detail

import com.sway.core.data.CatalogRepository
import com.sway.core.data.DetailResult
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.SourceId
import com.sway.core.model.toUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Generic catalog-detail ViewModel (stories 10.5–10.7): fetch once, quintet
 * states, retry preserving the target id. Subclasses expose typed playback
 * request builders (FR-22 contract; engine wiring = E12).
 */
open class CatalogDetailViewModel<T : Any>(
    private val fetch: suspend () -> DetailResult<T>,
    protected val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<DetailState<T>>(DetailState.Loading)
    val state: StateFlow<DetailState<T>> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        job = scope.launch {
            _state.value = DetailState.Loading
            _state.value = when (val result = fetch()) {
                is DetailResult.Fresh -> DetailState.Content(result.data, stale = false)
                is DetailResult.Stale -> DetailState.Content(result.data, stale = true)
                is DetailResult.Failed -> DetailState.Error(result.error.toUiState())
            }
        }
    }

    fun retry() = load()
}

class AlbumDetailViewModel(
    repository: CatalogRepository,
    id: SourceId,
    scope: CoroutineScope,
) : CatalogDetailViewModel<Album>({ repository.albumDetail(id) }, scope) {
    val album: Album? get() = (state.value as? DetailState.Content)?.data

    /** Play from track [index] (FR-22: full album queued at the tapped position). */
    fun playRequest(index: Int): PlaybackRequest? =
        album?.let { PlaybackRequests.build(it.tracks, PlaybackRequests.Mode.FromIndex(index)) }

    fun shuffleRequest(seed: Long): PlaybackRequest? =
        album?.let { PlaybackRequests.build(it.tracks, PlaybackRequests.Mode.Shuffled(seed)) }
}

class ArtistDetailViewModel(
    repository: CatalogRepository,
    id: SourceId,
    scope: CoroutineScope,
) : CatalogDetailViewModel<Artist>({ repository.artistDetail(id) }, scope) {
    val artist: Artist? get() = (state.value as? DetailState.Content)?.data

    fun topSongsPlayRequest(index: Int): PlaybackRequest? =
        artist?.let { PlaybackRequests.build(it.topSongs, PlaybackRequests.Mode.FromIndex(index)) }

    fun topSongsShuffleRequest(seed: Long): PlaybackRequest? =
        artist?.let { PlaybackRequests.build(it.topSongs, PlaybackRequests.Mode.Shuffled(seed)) }
}

class CatalogPlaylistDetailViewModel(
    repository: CatalogRepository,
    id: SourceId,
    scope: CoroutineScope,
) : CatalogDetailViewModel<CatalogPlaylist>({ repository.catalogPlaylistDetail(id) }, scope) {
    val playlist: CatalogPlaylist? get() = (state.value as? DetailState.Content)?.data

    fun playRequest(index: Int): PlaybackRequest? =
        playlist?.let { PlaybackRequests.build(it.tracks, PlaybackRequests.Mode.FromIndex(index)) }

    fun shuffleRequest(seed: Long): PlaybackRequest? =
        playlist?.let { PlaybackRequests.build(it.tracks, PlaybackRequests.Mode.Shuffled(seed)) }
}

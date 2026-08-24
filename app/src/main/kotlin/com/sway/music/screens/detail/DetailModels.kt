package com.sway.music.screens.detail

import com.sway.core.model.Song
import com.sway.core.model.SwayErrorUiState

/**
 * Detail-surface state (stories 10.5–10.7): exactly one branch at any moment;
 * [Content.stale] drives the FR-4 Saved badge (10.4 rules).
 */
sealed interface DetailState<out T> {
    data object Loading : DetailState<Nothing>
    data class Content<T>(val data: T, val stale: Boolean = false) : DetailState<T>
    data class Error(val category: SwayErrorUiState) : DetailState<Nothing>
}

/**
 * Context-queue request (story 10.5–10.7, FR-22 trace): the pure queue
 * CONTRACT the screens emit; E12's cross-surface wiring feeds these into
 * PlayerConnection.setQueue. Engine-level semantics were proven in 7.1.
 */
data class PlaybackRequest(
    val items: List<Song>,
    val startIndex: Int,
    val shuffled: Boolean,
)

/** Pure playback-request builders (FR-22 semantics at contract level). */
object PlaybackRequests {

    sealed interface Mode {
        /** Play from track [index] of the ordered collection (tap or Play). */
        data class FromIndex(val index: Int) : Mode

        /** Shuffle entry: seeded Fisher-Yates over non-start slots, chosen pinned first. */
        data class Shuffled(val seed: Long) : Mode
    }

    fun build(tracks: List<Song>, mode: Mode): PlaybackRequest = when (mode) {
        is Mode.FromIndex -> {
            require(tracks.isNotEmpty()) { "empty collection" }
            val k = mode.index.coerceIn(0, tracks.lastIndex)
            PlaybackRequest(items = tracks, startIndex = k, shuffled = false)
        }
        is Mode.Shuffled -> {
            if (tracks.size <= 1) {
                PlaybackRequest(items = tracks, startIndex = 0, shuffled = true)
            } else {
                val rng = java.util.Random(mode.seed)
                val shuffled = tracks.toMutableList()
                for (i in shuffled.size - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    shuffled[i] = shuffled[j].also { shuffled[j] = shuffled[i] }
                }
                PlaybackRequest(items = shuffled, startIndex = 0, shuffled = true)
            }
        }
    }
}

package com.sway.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.sway.core.data.QueueStateSerializer
import com.sway.core.data.SessionRestoreRepository
import com.sway.core.model.ArtworkRef
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced session saver (story 7.3, FR-25): observes the player and
 * persists the session — queue snapshot, current index, position, modes — so
 * a kill-and-relaunch restores EXACTLY where listening stopped, PAUSED.
 *
 * Capture law: truth is read from the PLAYER timeline (works with zero
 * controllers bound — background advance keeps saving; NFR-4 substrate).
 * Songs are reconstructed from the stamped metadata mirror (6.1 single
 * stamping point), so sanitizer-stable titles round-trip losslessly for
 * restore purposes.
 *
 * Timing laws (P-5-style tunables): meaningful events (transition, seek,
 * play/pause) arm a [SAVE_DEBOUNCE_MS] flush; while audibly playing a
 * [PLAYING_FLUSH_INTERVAL_MS] heartbeat bounds worst-case position loss well
 * inside FR-25's +/-5 s restore tolerance. Saves are fire-and-forget; a
 * failed write degrades silently ([SessionRestoreRepository] contract).
 */
class SessionStateSaver(
    private val player: Player,
    private val scope: CoroutineScope,
    private val store: SessionRestoreRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var pendingJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastFlushAt: Long = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            scheduleSave()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Pause/play are themselves meaningful moments worth an immediate frame.
            scheduleSave(immediate = !playWhenReady)
            syncHeartbeat(playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            scheduleSave()
            syncHeartbeat(player.playWhenReady && playbackState == Player.STATE_READY)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            scheduleSave()
        }
    }

    init {
        try {
            player.addListener(listener)
        } catch (_: Exception) {
        }
        syncHeartbeat(player.playWhenReady && player.playbackState == Player.STATE_READY)
    }

    /** Detach + final flush (best-effort; never throws). */
    fun release() {
        try {
            player.removeListener(listener)
        } catch (_: Exception) {
        }
        pendingJob?.cancel()
        heartbeatJob?.cancel()
        flushNow()
    }

    internal fun scheduleSave(immediate: Boolean = false) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            if (immediate) {
                flushNow()
            } else {
                delay(SAVE_DEBOUNCE_MS - (clock() - lastFlushAt).coerceAtLeast(0L))
                flushNow()
            }
        }
    }

    private fun syncHeartbeat(playing: Boolean) {
        if (playing && heartbeatJob == null) {
            heartbeatJob = scope.launch {
                while (true) {
                    delay(PLAYING_FLUSH_INTERVAL_MS)
                    if (player.isPlaying) flushNow()
                }
            }
        } else if (!playing && heartbeatJob != null) {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    internal fun flushNow() {
        lastFlushAt = clock()
        val session = capture() ?: return
        scope.launch { store.save(session) }
    }

    /** Latest session truth from the player timeline; null when nothing to save. */
    internal fun capture(): QueueStateSerializer.RestoredSession? {
        val count = try {
            player.mediaItemCount
        } catch (_: Exception) {
            return null
        }
        if (count == 0) return null
        val songs = (0 until count).mapNotNull { i ->
            val item = try {
                player.getMediaItemAt(i)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            val meta: MediaMetadata = item.mediaMetadata
            val created = Song.create(
                id = item.mediaId,
                rawTitle = meta.title?.toString() ?: item.mediaId,
                artistName = meta.artist?.toString(),
                durationMs = meta.durationMs?.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L,
                artwork = meta.artworkUri?.toString()?.let { ArtworkRef.of(it) },
            )
            created?.let { QueueItem.of(it) }
        }
        if (songs.isEmpty()) return null
        val idx = try {
            player.currentMediaItemIndex
        } catch (_: Exception) {
            0
        }.coerceIn(0, songs.size - 1)
        return QueueStateSerializer.RestoredSession(
            snapshot = QueueSnapshot.of(songs),
            currentIndex = idx,
            positionMs = try {
                player.currentPosition.coerceAtLeast(0L)
            } catch (_: Exception) {
                0L
            },
            shuffleEnabled = false, // order is saved POST-shuffle; flag semantics live facade-side
            repeatMode = try {
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            } catch (_: Exception) {
                RepeatMode.OFF
            },
        )
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 750L
        const val PLAYING_FLUSH_INTERVAL_MS = 5_000L
    }
}

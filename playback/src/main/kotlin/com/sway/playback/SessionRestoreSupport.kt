package com.sway.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.sway.core.model.RepeatMode
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Story 7.3 restore support - extension surface keeping [PlayerConnection]
 * under its NFR-7 LOC budget while the post-composition session-restore hook
 * (FR-25 / AR-9) stays cohesive. All state touched here is module-internal.
 */

/** Bounded wait for the restored start item to resolve before re-seeking. */
internal const val RESTORE_SEEK_TIMEOUT_MS: Long = 10_000L

/**
 * Story 7.3 (FR-25 / AR-9): the post-composition restore hook. Called once
 * after the surface attaches (9.4/12.1 consume this): loads the saved session
 * and lands it PAUSED - queue + current index + position + modes - NEVER
 * auto-playing (predictability law). A null/corrupt row is the clean
 * first-run path: uiState simply stays Idle (no Mini-Player session marker).
 * Restored shuffle is a quiet flag: the saved order already reflects the
 * user's shuffle, so nothing reorders now.
 */
internal fun PlayerConnection.attachSessionStore(store: com.sway.core.data.SessionRestoreRepository) {
    sessionStore = store
    scope.launch {
        val saved = try {
            store.loadRestoredSession()
        } catch (_: Exception) {
            null
        } ?: return@launch // first run: honest empty state
        setQueue(saved.snapshot, saved.currentIndex)
        // The JIT engine resolves the restored start item asynchronously; its
        // rendition swap resets the window position, so the saved moment is
        // re-landed once the item is READY. Event-driven await (no wall-clock
        // polling); offline placeholders time out and merely defer the seek.
        val ready = try {
            withTimeoutOrNull(RESTORE_SEEK_TIMEOUT_MS) {
                player?.awaitReadyInternal()
            }
        } catch (_: Exception) {
            null
        }
        if (ready != true && player?.playbackState != Player.STATE_READY) return@launch
        try {
            player?.seekTo(saved.positionMs)
        } catch (_: Exception) {
        }
        _uiState.value = _uiState.value.copy(positionMs = saved.positionMs)
        lastRestoredSeekMsForTest = saved.positionMs
        try {
            player?.repeatMode = when (saved.repeatMode) {
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }
        } catch (_: Exception) {
        }
        if (saved.shuffleEnabled != shuffleEnabledInternal) {
            // Quiet mirror: order was saved post-shuffle; flag only.
            shuffleEnabledInternal = saved.shuffleEnabled
        }
        _uiState.value = _uiState.value.copy(
            shuffleEnabled = shuffleEnabledInternal,
            repeatMode = saved.repeatMode,
        )
    }
}

internal fun PlayerConnection.persistModesIfAttached(mode: RepeatMode, shuffle: Boolean) {
    settings?.let { repo ->
        scope.launch {
            try {
                repo.setRepeatMode(mode)
                repo.setShuffleEnabled(shuffle)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Suspends until the player reaches [Player.STATE_READY] (or errors),
 * event-driven via a transient listener - deterministic under Robolectric
 * where delayed main-looper resumptions do not advance with real time.
 * File-private: implementation detail of [attachSessionStore].
 */
private suspend fun Player.awaitReadyInternal(): Boolean =
    suspendCancellableCoroutine { cont ->
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    removeListener(this)
                    if (cont.isActive) cont.resume(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                removeListener(this)
                if (cont.isActive) cont.resume(false)
            }
        }
        addListener(l)
        if (playbackState == Player.STATE_READY) {
            removeListener(l)
            if (cont.isActive) cont.resume(true)
        }
        cont.invokeOnCancellation { removeListener(l) }
    }

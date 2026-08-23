package com.sway.playback

import com.sway.core.model.QueueItem
import com.sway.core.model.RepeatMode
import com.sway.core.model.SwayError

/**
 * Hoisted playback UI state (AD-6 rule 2, AR-5, story 4.2).
 *
 * Single source of truth for every surface (Mini Player, Full Player,
 * notification, lock screen). UI never owns player logic — it collects
 * [PlayerConnection.uiState].
 *
 * Fields:
 * - [isPlaying] — true when the underlying player is playing (isPlaying == true).
 * - [isBuffering] — true when playbackState == STATE_BUFFERING.
 * - [currentItem] — snapshot of the currently playing queue entry, null when idle/empty.
 * - [positionMs] — last known position in milliseconds. Continuously ticking
 *   updates are **scoped** to active scrubber subscribers via [PlayerConnection.positionFlow];
 *   [positionMs] in this state is the snapshot at the last non-tick state change
 *   (play/pause/seek/jump/transition). See [PlayerConnection.positionFlow] docs.
 * - [failedTrack] — reserved slot for E5 typed failure row (FR-14); null when no failure.
 * - [shuffleEnabled] / [repeatMode] — mode mirrors (story 7.1, FR-11 semantics):
 *   the facade's playback-vocabulary values, never raw media3 ints (AR-9).
 *
 * Position ticks are NOT broadcast app-wide — only scrubbers subscribe (AD-6,
 * UX §12.8). Collecting [PlayerConnection.uiState] alone does not cause tick
 * churn; use [PlayerConnection.positionFlow] for the scrubber.
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentItem: QueueItem? = null,
    val positionMs: Long = 0L,
    val failedTrack: FailedTrack? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
) {
    companion object {
        /** Idle/empty state — no playback session. */
        val Idle = PlayerUiState()
    }
}

/**
 * Typed failure slot for E5 (FR-14) — the track that failed with its [SwayError] category.
 *
 * Will be populated by the watchdog / renewal layers (stories 5.3–5.4). Reserved
 * here so [PlayerUiState] shape is stable and UI can code against it early.
 */
data class FailedTrack(
    val item: QueueItem,
    val error: SwayError,
)

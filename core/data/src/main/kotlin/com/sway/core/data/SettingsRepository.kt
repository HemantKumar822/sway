package com.sway.core.data

import com.sway.core.model.Quality
import com.sway.core.model.RepeatMode
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for user settings (FR-15 completes here; FR-39/FR-11 persistence build on
 * this repository later) — one namespaced DataStore preferences file (architecture
 * Consistency Conventions "Settings"), async reads only (AD-10: no synchronous read ever
 * touches the startup path).
 *
 * Audio quality (FR-15): the stored preference is exposed as [audioQuality]; consumers
 * pass it into [com.sway.core.model.AudioRequest] at each Stream Resolution, so a change
 * applies from the next resolution and never disturbs the current track.
 *
 * Playback modes (story 7.2, FR-11 persistence clause): shuffle flag + repeat mode are
 * written on every change and restored at service start BEFORE the first queue build;
 * rapid changes persist last-write-wins.
 *
 * Failure law: corrupt/unreadable stored values degrade to the documented default
 * (AUTO / false / OFF) instead of throwing at collectors — mirrors the
 * strict-validation-on-read, never-crash lesson of the Offline Fallback Cache (C-8).
 */
interface SettingsRepository {

    /** Persisted audio-quality preference; emits AUTO when unset or unreadable. */
    val audioQuality: Flow<Quality>

    /**
     * Persists [quality] (last write wins). Does not invalidate any cached rendition —"
     * the new value takes effect from the next resolution (FR-15).
     */
    suspend fun setAudioQuality(quality: Quality)

    /** Persisted shuffle preference; emits FALSE when unset or unreadable (FR-11). */
    val shuffleEnabled: Flow<Boolean>

    /** Persists [enabled] (last write wins). */
    suspend fun setShuffleEnabled(enabled: Boolean)

    /** Persisted repeat mode; emits OFF when unset or unreadable (FR-11). */
    val repeatMode: Flow<RepeatMode>

    /** Persists [mode] by its strict enum name (last write wins). */
    suspend fun setRepeatMode(mode: RepeatMode)
}

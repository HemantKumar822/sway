package com.sway.core.data

import com.sway.core.model.Quality
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
 * Failure law: corrupt/unreadable stored values degrade to the documented default
 * (AUTO) instead of throwing at collectors — mirrors the strict-validation-on-read,
 * never-crash lesson of the Offline Fallback Cache (C-8).
 */
interface SettingsRepository {

    /** Persisted audio-quality preference; emits AUTO when unset or unreadable. */
    val audioQuality: Flow<Quality>

    /**
     * Persists [quality] (last write wins). Does not invalidate any cached rendition —
     * the new value takes effect from the next resolution (FR-15).
     */
    suspend fun setAudioQuality(quality: Quality)
}

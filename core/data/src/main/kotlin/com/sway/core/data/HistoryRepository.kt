package com.sway.core.data

import com.sway.core.model.Song
import com.sway.core.model.SwayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Diary row for surfaces: the snapshot plus WHEN it was last played (FR-34). */
data class HistoryEntry(val song: Song, val playedAt: Long)

/**
 * Play-history boundary (story 8.3, FR-34/A-5, AR-5 rule 7): THE single write
 * path - recording happens exclusively here (called only from the
 * service-side recorder; check_history_write_path audit enforces this),
 * recency-upsert keyed by songId (no stacking), trim-on-write cap 500.
 */
class HistoryRepository(
    private val store: HistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The cap from AD-8: history holds at most the 500 most recent plays. */
    internal companion object {
        const val CAP = 500
    }

    /**
     * Record a qualifying play: upsert by songId (replays refresh recency)
     * then trim to the most recent [CAP]. Single write path.
     */
    suspend fun record(sourceId: String): SwayResult<Unit> = storageGuarded {
        store.record(sourceId, clock(), CAP)
    }

    /** Live first page of the diary, most recent first. */
    fun observeRecent(limit: Int = CAP): Flow<List<HistoryEntry>> =
        store.observeRecent(limit).map { rows -> rows.mapNotNull { it.toEntry() } }

    /** Paged reverse-chronological read (FR-34 paging). */
    suspend fun page(limit: Int, offset: Int): SwayResult<List<HistoryEntry>> = storageGuarded {
        store.page(limit, offset).mapNotNull { it.toEntry() }
    }
}

private fun StoredHistoryRow.toEntry(): HistoryEntry? =
    Song.create(
        id = sourceId,
        rawTitle = rawTitle,
        artistName = artistName,
        durationMs = durationMs,
        artwork = artworkUrl?.let { com.sway.core.model.ArtworkRef.of(it) },
    )?.let { HistoryEntry(it, playedAt) }

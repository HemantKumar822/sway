package com.sway.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * History boundary (story 8.3, FR-34, AD-8): recency upsert (single write
 * path lives in :core:data's HistoryRepository), paged reverse-chron reads,
 * trim to the most recent 500 on write.
 */
@Dao
interface HistoryDao {

    /** Recency upsert: replays update playedAt instead of stacking. */
    @Upsert
    suspend fun upsert(entity: HistoryEntity)

    /** Most-recent-first live page (first page of the diary). */
    @Query(
        "SELECT se.*, h.playedAt AS playedAt FROM history h " +
            "JOIN song_entities se ON se.sourceId = h.songId " +
            "ORDER BY h.playedAt DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<HistorySongRow>>

    /** Paged reverse-chronological read (FR-34 paging). */
    @Query(
        "SELECT se.*, h.playedAt AS playedAt FROM history h " +
            "JOIN song_entities se ON se.sourceId = h.songId " +
            "ORDER BY h.playedAt DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(limit: Int, offset: Int): List<HistorySongRow>

    /** Trim-on-write: keep only the [cap] most recent rows. */
    @Query(
        "DELETE FROM history WHERE songId NOT IN " +
            "(SELECT songId FROM history ORDER BY playedAt DESC LIMIT :cap)",
    )
    suspend fun trimTo(cap: Int)

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}

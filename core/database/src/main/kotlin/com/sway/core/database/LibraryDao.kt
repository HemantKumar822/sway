package com.sway.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Likes boundary (story 8.1, FR-30, AD-8): liked flow ordered likedAt DESC,
 * point lookups, upsert set/clear, batch liked-id probe. All APIs suspend or
 * Flow; multi-step edits compose at the repository with row-read + upsert in
 * a caller-managed sequence (single-writer per user action).
 */
@Dao
interface LibraryDao {

    /** Liked songs, most recently liked first (FR-33 order law). */
    @Query(
        "SELECT * FROM song_entities WHERE likedAt IS NOT NULL " +
            "ORDER BY likedAt DESC",
    )
    fun likedSongs(): Flow<List<SongEntity>>

    /** Immediate twin of [likedSongs] for snapshot reads (NFR-2 typed path). */
    @Query(
        "SELECT * FROM song_entities WHERE likedAt IS NOT NULL " +
            "ORDER BY likedAt DESC",
    )
    suspend fun likedSongsNow(): List<SongEntity>

    @Query("SELECT * FROM song_entities WHERE sourceId = :sourceId")
    suspend fun byId(sourceId: String): SongEntity?

    @Upsert
    suspend fun upsert(entity: SongEntity)

    /** Batch probe: which of [sourceIds] are currently liked (FR-30 sync). */
    @Query(
        "SELECT sourceId FROM song_entities " +
            "WHERE sourceId IN (:sourceIds) AND likedAt IS NOT NULL",
    )
    suspend fun likedIdsAmong(sourceIds: List<String>): List<String>

    @Query("DELETE FROM song_entities WHERE sourceId = :sourceId")
    suspend fun deleteById(sourceId: String)
}

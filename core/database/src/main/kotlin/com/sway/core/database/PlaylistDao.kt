package com.sway.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Playlists boundary (story 8.2, FR-31/32, AD-8): lists with counts, ordered
 * song reads, and ONE atomic membership-rewrite primitive that powers
 * add/remove/reorder (multi-step edits in @Transaction — failure rolls back
 * fully). All APIs suspend or Flow.
 */
@Dao
abstract class PlaylistDao {

    /** Header rows with song counts, creation order (FR-31 list law). */
    @Query(
        "SELECT p.*, COUNT(ps.songId) AS songCount FROM playlists p " +
            "LEFT JOIN playlist_songs ps ON ps.playlistId = p.playlistId " +
            "GROUP BY p.playlistId ORDER BY p.createdAt ASC",
    )
    abstract fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    abstract suspend fun byId(playlistId: String): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name, rawName = :rawName, updatedAt = :updatedAt WHERE playlistId = :playlistId")
    abstract suspend fun rename(playlistId: String, name: String, rawName: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    abstract suspend fun delete(playlistId: String)

    @Upsert
    abstract suspend fun insertPlaylist(entity: PlaylistEntity)

    /** Ordered songs of one playlist (position ASC), snapshot rows joined in. */
    @Query(
        "SELECT se.* FROM playlist_songs ps " +
            "JOIN song_entities se ON se.sourceId = ps.songId " +
            "WHERE ps.playlistId = :playlistId " +
            "ORDER BY ps.position ASC",
    )
    abstract fun observeSongs(playlistId: String): Flow<List<SongEntity>>

    @Query(
        "SELECT se.* FROM playlist_songs ps " +
            "JOIN song_entities se ON se.sourceId = ps.songId " +
            "WHERE ps.playlistId = :playlistId " +
            "ORDER BY ps.position ASC",
    )
    abstract suspend fun songsNow(playlistId: String): List<SongEntity>

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: String): Int?

    /**
     * THE atomic edit primitive (AC1): replaces the whole membership of one
     * playlist in a single transaction — any failure mid-way rolls back fully
     * (including removal of the last member). Callers pass the complete
     * desired contiguous ordering.
     */
    @Transaction
    open suspend fun rewriteMembership(playlistId: String, members: List<PlaylistSongEntity>) {
        deleteMembership(playlistId)
        members.forEach { addMember(it) }
    }

    /** Snapshot upsert so join FKs always resolve (offline-complete, UJ-3). */
    @Upsert
    abstract suspend fun upsertSnapshot(entity: SongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    protected abstract fun deleteMembership(playlistId: String)

    @Upsert
    protected abstract suspend fun addMember(member: PlaylistSongEntity)
}

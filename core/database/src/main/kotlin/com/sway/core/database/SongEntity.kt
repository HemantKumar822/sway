package com.sway.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catalog song snapshot + like state (story 8.1, FR-30, AD-8 sketch):
 * Source-ID primary key; title and rawTitle preserved separately (AR-14);
 * [likedAt] is NULL when not liked — the timestamp doubles as the recency
 * key for the liked flow (ordered likedAt DESC). Indexed for that ordering.
 *
 * Rows referenced by likes/playlists/history/queue are never auto-deleted in
 * v1 (AD-8 retention law; single-user scale).
 */
@Entity(
    tableName = "song_entities",
    indices = [Index("likedAt")],
)
data class SongEntity(
    @PrimaryKey val sourceId: String,
    val title: String,
    val rawTitle: String,
    val artistName: String? = null,
    val artistId: String? = null,
    val albumName: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    /** Epoch ms of the like moment; NULL = not liked (FR-30). */
    val likedAt: Long? = null,
)

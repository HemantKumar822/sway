package com.sway.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join row: an ordered song inside a playlist (story 8.2, FR-32, AD-8).
 * Composite PK (playlistId, songId) — a song appears at most once per
 * playlist; multi-membership ACROSS playlists is the invariant. Position is
 * contiguous 0..n-1 (repository maintains; index makes ordering reads cheap).
 *
 * Deleting a PLAYLIST cascades these join rows only — song_entities snapshots
 * are never auto-deleted (AD-8 retention law); the songId FK has NO action.
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("playlistId", "position"), Index("songId")],
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val position: Int,
    val addedAt: Long,
)

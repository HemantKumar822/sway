package com.sway.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Play-history row (story 8.3, FR-34/A-5, AD-8): upsert keyed by songId so
 * replays UPDATE recency instead of stacking duplicates; capped at the most
 * recent 500 rows by a trim-on-write. songId references the snapshot table
 * with NO delete action (snapshots are never auto-deleted; AD-8 retention).
 */
@Entity(
    tableName = "history",
    indices = [Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class HistoryEntity(
    @PrimaryKey val songId: String,
    /** Epoch ms of the most recent qualifying play (the "recency" key). */
    val playedAt: Long,
)

/** Joined read row: the snapshot plus WHEN it was last played (FR-34 UI). */
data class HistorySongRow(
    @Embedded val song: SongEntity,
    val playedAt: Long,
)

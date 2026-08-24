package com.sway.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Owned local playlist header (story 8.2, FR-31, AD-8 sketch). The primary
 * key stores the namespaced [com.sway.core.model.PlaylistId] value
 * ("local:<uuid>") — identity coherence with core:model (AR-8/AR-14).
 * Duplicate NAMES are allowed by design; uniqueness comes from the id.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val rawName: String,
    val createdAt: Long,
    val updatedAt: Long,
)

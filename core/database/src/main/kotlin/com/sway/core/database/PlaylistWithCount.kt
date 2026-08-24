package com.sway.core.database

import androidx.room.Embedded

/** Header + count row for the FR-31 playlist list surface. */
data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
)

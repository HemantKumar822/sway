package com.sway.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Playback-session singleton row (story 7.3, FR-25, AD-8): the saved queue as
 * canonical [com.sway.core.model.QueueSnapshot] song snapshots serialized by
 * `:core:data`'s ONE serializer (single-representation law — no other module
 * may (de)serialize queue state), plus restore coordinates and mode flags.
 *
 * Snapshots (not bare ids) mean session restore renders fully offline (UJ-4).
 */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Canonical QueueSnapshot JSON — owned exclusively by :core:data. */
    val songsJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val savedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1

        const val REPEAT_OFF = "OFF"
        const val REPEAT_ALL = "ALL"
        const val REPEAT_ONE = "ONE"
    }
}

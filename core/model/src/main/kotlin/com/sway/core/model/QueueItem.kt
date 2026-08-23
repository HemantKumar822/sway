package com.sway.core.model

/**
 * Single entry in a [QueueSnapshot] — AD-6 (placeholder queue), FR-22 semantics.
 *
 * A queue entry is a snapshot of a catalog Song plus its stable identity key.
 * Stable list keys for virtualization = [SourceId] (AR-14).
 *
 * - [id] is the stable key == [song].id.value; consumers should key lists by [id].
 * - [song] is the full snapshot (title, artist, duration, ArtworkRef) so session
 *   restore renders the Mini Player fully offline without network (AD-8).
 *
 * Pure Kotlin — zero Android imports.
 */
data class QueueItem(
    val id: SourceId,
    val song: Song,
) {
    init {
        // Identity coherence: QueueItem id must equal the snapshot's primary id.
        require(id == song.id) {
            "QueueItem id (${id.value}) must equal song.id (${song.id.value})"
        }
    }

    companion object {
        /** Convenience factory from a [Song] snapshot (id derived from song). */
        fun of(song: Song): QueueItem = QueueItem(song.id, song)
    }
}

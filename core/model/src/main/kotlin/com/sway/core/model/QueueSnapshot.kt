package com.sway.core.model

/**
 * Ordered queue snapshot — AD-6, AD-8, FR-22/FR-25.
 *
 * Built by queue-builder from a play context (song tap / album play / shuffle
 * variants) as Source-ID placeholders (AD-6 rule 3: exactly one up-front resolve
 * for the start item lives service-side; the Queue itself holds snapshots).
 *
 * AD-8: this is the **canonical** queue representation — exactly one serializer
 * (owned by `:core:data`'s QueueState) may (de)serialize it into
 * `QueueStateEntity`. No other module may duplicate the shape (FR-25).
 *
 * Mutation (reorder/remove/insert/play-next) produces a new instance (immutable).
 *
 * Pure Kotlin — zero Android imports.
 */
data class QueueSnapshot(
    val items: List<QueueItem>,
) {
    init {
        // Defensive copy semantics validated by factory; direct ctor also copies on access.
        require(items !== null) { "QueueSnapshot items must not be null" }
    }

    /** Number of items. */
    val size: Int get() = items.size

    /** True when empty (no context). */
    val isEmpty: Boolean get() = items.isEmpty()

    /** Stable snapshot of items (defensive copy semantics via List contract). */
    val orderedItems: List<QueueItem> get() = items

    /** Retrieve item at [index] or `null` if out of bounds. */
    fun itemAt(index: Int): QueueItem? = items.getOrNull(index)

    /** Stable source ids in queue order (for diff keys / placeholder URI mapping). */
    fun sourceIds(): List<SourceId> = items.map { it.id }

    companion object {
        /** Empty queue (no context yet). */
        val Empty: QueueSnapshot = QueueSnapshot(emptyList())

        /** Factory copying input list defensively. */
        fun of(items: List<QueueItem>): QueueSnapshot = QueueSnapshot(items.toList())

        /** Build from raw [Song] snapshots directly. */
        fun fromSongs(songs: List<Song>): QueueSnapshot =
            QueueSnapshot(songs.map { QueueItem.of(it) })

        /**
         * Single-track snapshot — useful for tap-to-play from any surface (FR-8).
         */
        fun single(song: Song): QueueSnapshot = QueueSnapshot(listOf(QueueItem.of(song)))
    }
}

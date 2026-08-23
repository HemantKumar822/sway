package com.sway.playback

import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.Song
import java.util.Random

/**
 * Context -> queue substrate (story 4.3, FR-22 trace, C-4 lazy-resolution).
 *
 * Pure-JVM builder turning play-action contexts (song tap inside a list,
 * collection play, seeded shuffle entry) into a [QueueSnapshot] plus the
 * chosen [BuiltQueue.startIndex]. Every non-start item rides a
 * [PendingUri] placeholder downstream — **zero stream resolution happens
 * here**: this object never calls any resolver and holds no Android
 * imports. Output is metadata-only value types (`core:model`).
 *
 * Variants:
 * - [fromSongTap] — tapped [Song] inside an n-item context: all n items in
 *   original order, startIndex = tapped index (first occurrence wins on
 *   duplicate ids — documented, no throw). Absent tap / empty context
 *   degrades to a single-item snapshot at startIndex 0; never throws.
 * - [fromCollection] — m songs played from index k; `k < 0 || k >= m`
 *   throws [IllegalArgumentException].
 * - [shuffled] — deterministic seeded Fisher-Yates. When [first] is
 *   present in the context it is pinned at index 0 ("this track, then
 *   surprise me") and the remaining items are shuffled via
 *   `java.util.Random(seed)` over positions 1..n-1. When absent/null the
 *   whole list shuffles with the seed, startIndex 0. Same
 *   `(items, first, seed)` triple always yields byte-identical order —
 *   determinism comes solely from `Random(seed)`'s specified LCG, never
 *   wall-clock entropy. Never throws.
 */
object QueueBuilder {

    /** Builder output: the immutable queue snapshot plus the chosen start index. */
    data class BuiltQueue(
        val snapshot: QueueSnapshot,
        val startIndex: Int,
    )

    /**
     * Play the [tapped] [Song] within its listening [context].
     *
     * All n context items appear in original order with startIndex at the
     * tapped item's index (first occurrence wins for duplicate ids).
     * Tapped absent from the context, or an empty context, yields a
     * single-item snapshot of the tapped song at startIndex 0.
     */
    fun fromSongTap(tapped: Song, context: List<Song>): BuiltQueue {
        val index = context.indexOfFirst { it.id == tapped.id }
        if (index < 0) {
            return BuiltQueue(QueueSnapshot.single(tapped), 0)
        }
        return BuiltQueue(QueueSnapshot.fromSongs(context), index)
    }

    /**
     * Play the [songs] collection starting at [startIndex].
     *
     * @throws IllegalArgumentException when `startIndex < 0` or
     * `startIndex >= songs.size` (an empty collection therefore always throws).
     */
    fun fromCollection(songs: List<Song>, startIndex: Int = 0): BuiltQueue {
        require(startIndex >= 0 && startIndex < songs.size) {
            "startIndex $startIndex out of bounds for collection of ${songs.size} song(s)"
        }
        return BuiltQueue(QueueSnapshot.fromSongs(songs), startIndex)
    }

    /**
     * Seeded shuffle of the [context]; the chosen [first] item (when
     * non-null AND present in the context) is pinned at index 0 and the
     * remaining items are Fisher-Yates-shuffled via [seed]. Otherwise the
     * whole list shuffles with the seed and starts at 0.
     *
     * Deterministic: identical `(context, first, seed)` triples always
     * produce identical orderings across machines.
     */
    fun shuffled(context: List<Song>, first: Song?, seed: Long): BuiltQueue {
        if (context.isEmpty()) {
            return BuiltQueue(QueueSnapshot.Empty, 0)
        }
        val chosenIndex = first?.let { chosen -> context.indexOfFirst { it.id == chosen.id } } ?: -1
        if (chosenIndex >= 0) {
            val rest = context.toMutableList()
            val pinned = rest.removeAt(chosenIndex)
            fisherYates(rest, Random(seed))
            return BuiltQueue(QueueSnapshot.fromSongs(listOf(pinned) + rest), 0)
        }
        val all = context.toMutableList()
        fisherYates(all, Random(seed))
        return BuiltQueue(QueueSnapshot.fromSongs(all), 0)
    }

    /**
     * Story 7.1 (FR-11 toggle semantics): deterministic mid-session reshuffle
     * for shuffle-toggle ON — the item at [currentIndex] stays EXACTLY where
     * it is (no interruption, no re-resolve) and the remainder is permuted by
     * Fisher-Yates over [seed]. Identical `(items, currentIndex, seed)` always
     * yields identical order; different seeds differ. Pure function.
     */
    fun reshufflePreservingCurrent(
        items: List<QueueItem>,
        currentIndex: Int,
        seed: Long,
    ): List<QueueItem> {
        if (items.isEmpty()) return emptyList()
        val cur = currentIndex.coerceIn(0, items.size - 1)
        val out = items.toMutableList()
        val others = out.toMutableList().apply { removeAt(cur) }
        fisherYatesQueueItems(others, Random(seed))
        var o = 0
        for (i in out.indices) {
            if (i != cur) out[i] = others[o++]
        }
        return out
    }

    /** Classic Fisher-Yates over [QueueItem]s — same primitive as [fisherYates]. */
    private fun fisherYatesQueueItems(items: MutableList<QueueItem>, random: Random) {
        for (i in items.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swapped = items[i]
            items[i] = items[j]
            items[j] = swapped
        }
    }

    /** Classic Fisher-Yates (Durstenfeld) shuffle — i from n-1 down to 1. */
    private fun fisherYates(items: MutableList<Song>, random: Random) {
        for (i in items.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swapped = items[i]
            items[i] = items[j]
            items[j] = swapped
        }
    }
}

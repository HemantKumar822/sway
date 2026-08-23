package com.sway.playback

import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Story 4.3 — Queue builder pure-JVM tests (FR-22 substrate, C-4).
 *
 * Covers every I/O & Edge-Case Matrix row of the spec plus shuffle
 * determinism: same seed twice -> identical order; two fixed seeds known to
 * differ -> differing order; chosen preserved first. Assertions use literal
 * fixed seeds and a test-local Fisher-Yates oracle so exact orders are
 * stable across machines (`java.util.Random`'s LCG is JVM-specified).
 */
class QueueBuilderTest {

    // --- fixtures ------------------------------------------------------------

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Title $id", durationMs = 180_000)!!

    private fun songs(vararg ids: String): List<Song> = ids.map { song(it) }

    private fun builtIds(built: QueueBuilder.BuiltQueue): List<String> =
        built.snapshot.items.map { it.id.value }

    /** Test-local oracle mirroring the specified Fisher-Yates contract. */
    private fun fisherYatesOracle(input: List<String>, seed: Long): List<String> {
        val out = input.toMutableList()
        val random = Random(seed)
        for (i in out.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }

    // --- Matrix row: song tap in context --------------------------------------

    @Test
    fun fromSongTap_tappedInContext_allItemsOriginalOrder_startIndexAtTapped() {
        val context = songs("a", "b", "c", "d")
        val built = QueueBuilder.fromSongTap(song("c"), context)
        assertEquals(listOf("a", "b", "c", "d"), builtIds(built))
        assertEquals(4, built.snapshot.size)
        assertEquals(2, built.startIndex)
        assertEquals("c", built.snapshot.itemAt(built.startIndex)?.id?.value)
    }

    @Test
    fun fromSongTap_singleItemContext_startIndexZero() {
        val context = songs("only")
        val built = QueueBuilder.fromSongTap(song("only"), context)
        assertEquals(listOf("only"), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    // --- Matrix row: song tap, absent/empty context ---------------------------

    @Test
    fun fromSongTap_tappedAbsentFromContext_singleItemSnapshotAtZero() {
        val context = songs("a", "b")
        val built = QueueBuilder.fromSongTap(song("z"), context)
        assertEquals(listOf("z"), builtIds(built))
        assertEquals(1, built.snapshot.size)
        assertEquals(0, built.startIndex)
    }

    @Test
    fun fromSongTap_tappedAtLastIndex_startIndexAtLastIndex() {
        val context = songs("a", "b", "c")
        val built = QueueBuilder.fromSongTap(song("c"), context)
        assertEquals(listOf("a", "b", "c"), builtIds(built))
        assertEquals(context.size - 1, built.startIndex)
        assertEquals("c", built.snapshot.itemAt(built.startIndex)?.id?.value)
    }

    @Test
    fun fromSongTap_emptyContext_singleItemSnapshotNeverThrows() {
        val built = QueueBuilder.fromSongTap(song("tap"), emptyList())
        assertEquals(listOf("tap"), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    // --- Matrix row: collection play ------------------------------------------

    @Test
    fun fromCollection_mSongsStartIndexK_mItemsAtIndexK() {
        val collection = songs("a", "b", "c", "d", "e", "f")
        val built = QueueBuilder.fromCollection(collection, 3)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), builtIds(built))
        assertEquals(6, built.snapshot.size)
        assertEquals(3, built.startIndex)
    }

    @Test
    fun fromCollection_defaultStartIndexIsZero() {
        val built = QueueBuilder.fromCollection(songs("a", "b"))
        assertEquals(0, built.startIndex)
        assertEquals(2, built.snapshot.size)
    }

    @Test
    fun fromCollection_startIndexAtLastIndex_succeeds() {
        val collection = songs("a", "b", "c", "d")
        val built = QueueBuilder.fromCollection(collection, collection.size - 1)
        assertEquals(listOf("a", "b", "c", "d"), builtIds(built))
        assertEquals(collection.size - 1, built.startIndex)
        assertEquals("d", built.snapshot.itemAt(built.startIndex)?.id?.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromCollection_negativeStartIndex_throwsIllegalArgument() {
        QueueBuilder.fromCollection(songs("a", "b"), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromCollection_startIndexAtSize_throwsIllegalArgument() {
        QueueBuilder.fromCollection(songs("a", "b"), 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromCollection_emptyCollectionDefaultStartIndex_throwsIllegalArgument() {
        // k >= m law: an empty collection cannot be played from any index.
        QueueBuilder.fromCollection(emptyList(), 0)
    }

    // --- Matrix row: shuffle with chosen ---------------------------------------

    @Test
    fun shuffled_chosenPinnedAtZero_restIsFisherYatesWithSeed() {
        val contextIds = listOf("a", "b", "c", "d", "e", "f", "g")
        val context = contextIds.map { song(it) }
        val built = QueueBuilder.shuffled(context, song("d"), seed = 42L)

        assertEquals(0, built.startIndex)
        assertEquals(contextIds.size, built.snapshot.size)
        assertEquals(
            listOf("d") + fisherYatesOracle(contextIds.filter { it != "d" }, 42L),
            builtIds(built),
        )
        assertTrue(built.snapshot.items.first().id == song("d").id)
    }

    @Test
    fun shuffled_sameSeedTwice_byteIdenticalOrder_chosenFirstBothTimes() {
        val context = songs("a", "b", "c", "d", "e", "f", "g", "h")
        val firstRun = QueueBuilder.shuffled(context, song("e"), seed = 7L)
        val secondRun = QueueBuilder.shuffled(context, song("e"), seed = 7L)

        assertEquals(builtIds(firstRun), builtIds(secondRun))
        assertEquals(firstRun.startIndex, secondRun.startIndex)
        assertEquals(0, firstRun.startIndex)
        assertEquals("e", builtIds(firstRun).first())
    }

    @Test
    fun shuffled_twoFixedSeeds_produceDifferingOrders() {
        val context = songs("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val withChosen = song("a")

        val seedA = QueueBuilder.shuffled(context, withChosen, seed = 11L)
        val seedB = QueueBuilder.shuffled(context, withChosen, seed = 99L)

        assertNotEquals(builtIds(seedA), builtIds(seedB))
        // Chosen still pinned first in both.
        assertEquals("a", builtIds(seedA).first())
        assertEquals("a", builtIds(seedB).first())
    }

    @Test
    fun shuffled_isPermutationOfContext() {
        val context = songs("a", "b", "c", "d", "e", "f", "g")
        val built = QueueBuilder.shuffled(context, song("c"), seed = 1234L)
        assertEquals(context.map { it.id.value }.sorted(), builtIds(built).sorted())
    }

    // --- Matrix row: shuffle without chosen ------------------------------------

    @Test
    fun shuffled_nullFirst_wholeListShuffledWithSeed_startZero() {
        val contextIds = listOf("a", "b", "c", "d", "e")
        val context = contextIds.map { song(it) }
        val built = QueueBuilder.shuffled(context, first = null, seed = 5L)

        assertEquals(fisherYatesOracle(contextIds, 5L), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    @Test
    fun shuffled_firstNotInContext_wholeListShuffledNeverThrows() {
        val contextIds = listOf("a", "b", "c", "d", "e")
        val context = contextIds.map { song(it) }
        val outsider = song("z")

        val built = QueueBuilder.shuffled(context, outsider, seed = 6L)
        assertEquals(fisherYatesOracle(contextIds, 6L), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    @Test
    fun shuffled_singleElementContext_chosenFirst() {
        val context = songs("only")
        val built = QueueBuilder.shuffled(context, song("only"), seed = 77L)
        assertEquals(1, built.snapshot.size)
        assertEquals(listOf("only"), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    @Test
    fun shuffled_emptyContext_neverThrows_emptySnapshotAtZero() {
        val noFirst = QueueBuilder.shuffled(emptyList(), null, seed = 9L)
        assertTrue(noFirst.snapshot.isEmpty)
        assertEquals(0, noFirst.startIndex)

        val withOutsider = QueueBuilder.shuffled(emptyList(), song("x"), seed = 9L)
        assertTrue(withOutsider.snapshot.isEmpty)
        assertEquals(0, withOutsider.startIndex)
    }

    // --- Matrix row: duplicate ids in context ----------------------------------

    @Test
    fun fromSongTap_duplicateIds_firstOccurrenceWinsForTapIndex() {
        val context = songs("a", "b", "a", "c")
        val built = QueueBuilder.fromSongTap(song("a"), context)
        // Documented behavior: first occurrence (index 0) wins; all 4 items kept.
        assertEquals(listOf("a", "b", "a", "c"), builtIds(built))
        assertEquals(0, built.startIndex)
    }

    @Test
    fun shuffled_duplicateChosenId_pinsFirstOccurrenceOnly() {
        val context = songs("a", "b", "a", "c", "d")
        val built = QueueBuilder.shuffled(context, song("a"), seed = 3L)
        assertEquals(5, built.snapshot.size)
        assertEquals("a", builtIds(built).first())
        assertEquals(0, built.startIndex)
        // Exactly one 'a' pinned at 0; the duplicate rides the shuffled rest.
        assertEquals(2, builtIds(built).count { it == "a" })
    }
}

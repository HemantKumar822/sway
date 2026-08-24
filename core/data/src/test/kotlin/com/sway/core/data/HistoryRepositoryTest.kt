package com.sway.core.data

import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 8.3 — HistoryRepository laws (NFR-2): storage failures surface as
 * Failure(Storage) on BOTH the write path and paged reads; first page of an
 * empty diary is a legitimate empty Success.
 */
class HistoryRepositoryTest {

    @Test
    fun record_and_page_storageFailures_typed() = runBlocking {
        val failing = HistoryRepository(FailingHistoryStore, clock = { 1L })

        val rec = failing.record("s1")
        assertTrue(rec is SwayResult.Failure && rec.error == SwayError.Storage)

        val page = failing.page(10, 0)
        assertTrue(page is SwayResult.Failure && page.error == SwayError.Storage)

        val observed = failing.observeRecent().first()
        assertTrue(observed.isEmpty()) // flow degrades silently to empty read
    }

    @Test
    fun happyPath_roundTrip_entriesInRecencyOrder() = runBlocking {
        val store = InMemoryStore()
        var t = 0L
        val repo = HistoryRepository(store) { ++t }

        repo.record("a"); repo.record("b"); repo.record("a") // replay refreshes

        val page = repo.page(10, 0) as SwayResult.Success
        assertEquals(listOf("a", "b"), page.data.map { it.song.id.value })
        assertEquals(listOf(3L, 2L), page.data.map { it.playedAt })
    }

    private object FailingHistoryStore : HistoryStore {
        override suspend fun record(songId: String, playedAt: Long, cap: Int) =
            throw java.sql.SQLException("disk full")
        override fun observeRecent(limit: Int) =
            kotlinx.coroutines.flow.flowOf(emptyList<StoredHistoryRow>())
        override suspend fun page(limit: Int, offset: Int): List<StoredHistoryRow> =
            throw java.sql.SQLException("disk full")
    }

    /** Minimal deterministic store mirroring the recorder fake's semantics. */
    private class InMemoryStore : HistoryStore {
        val rows = mutableListOf<StoredHistoryRow>()
        override suspend fun record(songId: String, playedAt: Long, cap: Int) {
            rows.removeAll { it.sourceId == songId }
            rows += StoredHistoryRow(
                sourceId = songId,
                title = "T$songId",
                rawTitle = "R$songId",
                artistName = null,
                durationMs = 60_000,
                artworkUrl = null,
                playedAt = playedAt,
            )
            while (rows.size > cap) rows.remove(rows.minByOrNull { it.playedAt })
        }
        override fun observeRecent(limit: Int) =
            kotlinx.coroutines.flow.flowOf(rows.sortedByDescending { it.playedAt }.take(limit))
        override suspend fun page(limit: Int, offset: Int): List<StoredHistoryRow> =
            rows.sortedByDescending { it.playedAt }.drop(offset).take(limit)
    }
}

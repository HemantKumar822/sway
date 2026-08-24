package com.sway.core.data

import com.sway.core.database.HistoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Storage seam between the history boundary ([HistoryRepository]) and Room
 * (AD-8/AR-1): consumers outside :core:data never touch Room types.
 * Value-form rows only; [RoomHistoryStore] is the production binding.
 */
interface HistoryStore {
    /** Recency upsert + trim-on-write to [cap] most recent (single call). */
    suspend fun record(songId: String, playedAt: Long, cap: Int)

    fun observeRecent(limit: Int): Flow<List<StoredHistoryRow>>

    suspend fun page(limit: Int, offset: Int): List<StoredHistoryRow>
}

/** Diary row value form (snapshot fields + recency). */
data class StoredHistoryRow(
    val sourceId: String,
    val title: String,
    val rawTitle: String,
    val artistName: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    val playedAt: Long,
)

class RoomHistoryStore(private val dao: HistoryDao) : HistoryStore {
    override suspend fun record(songId: String, playedAt: Long, cap: Int) {
        dao.upsert(com.sway.core.database.HistoryEntity(songId = songId, playedAt = playedAt))
        dao.trimTo(cap)
    }

    override fun observeRecent(limit: Int): Flow<List<StoredHistoryRow>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toStored() } }

    override suspend fun page(limit: Int, offset: Int): List<StoredHistoryRow> =
        dao.page(limit, offset).map { it.toStored() }
}

private fun com.sway.core.database.HistorySongRow.toStored(): StoredHistoryRow = StoredHistoryRow(
    sourceId = song.sourceId,
    title = song.title,
    rawTitle = song.rawTitle,
    artistName = song.artistName,
    durationMs = song.durationMs,
    artworkUrl = song.artworkUrl,
    playedAt = playedAt,
)

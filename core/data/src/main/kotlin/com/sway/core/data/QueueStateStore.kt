package com.sway.core.data

import com.sway.core.database.QueueStateDao
import com.sway.core.database.QueueStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Storage seam between the session boundary ([SessionRestoreRepository]) and
 * Room (AD-8/AR-1): consumers outside `:core:data` never touch Room or entity
 * types — the seam speaks [StoredQueueState] values only, so `:playback`
 * stays free of a storage dependency while production wires
 * [RoomQueueStateStore] at graph-assembly time.
 */
interface QueueStateStore {
    suspend fun loadOnce(): StoredQueueState?
    suspend fun save(state: StoredQueueState)
    suspend fun clear()
}

/** Session-row value form (storage-shape-free). */
data class StoredQueueState(
    val songsJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val savedAt: Long,
)

/** Row mapper shared by the store and its tests. */
private fun QueueStateEntity.toStored(): StoredQueueState = StoredQueueState(
    songsJson = songsJson,
    currentIndex = currentIndex,
    positionMs = positionMs,
    shuffleEnabled = shuffleEnabled,
    repeatMode = repeatMode,
    savedAt = savedAt,
)

/** Room-backed [QueueStateStore] — production binding (singleton row law kept by upsert). */
class RoomQueueStateStore(private val dao: QueueStateDao) : QueueStateStore {
    override suspend fun loadOnce(): StoredQueueState? = dao.loadOnce()?.toStored()
    override suspend fun save(state: StoredQueueState) = dao.save(
        QueueStateEntity(
            songsJson = state.songsJson,
            currentIndex = state.currentIndex,
            positionMs = state.positionMs,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            savedAt = state.savedAt,
        ),
    )
    override suspend fun clear() = dao.clear()
}

/** Saved-at observer for diagnostics; row shape stays internal. */
fun QueueStateStore.observeSavedAt(observe: Flow<QueueStateEntity?>): Flow<Long?> =
    observe.map { it?.savedAt }

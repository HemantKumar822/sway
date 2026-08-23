package com.sway.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Queue-state boundary (story 7.3, FR-25, AD-8): load/save of the singleton
 * row. All APIs are suspend or Flow (AD-8 rule); multi-step edits are not
 * needed here (single row upsert).
 */
@Dao
interface QueueStateDao {

    @Query("SELECT * FROM queue_state WHERE id = ${QueueStateEntity.SINGLETON_ID}")
    suspend fun loadOnce(): QueueStateEntity?

    @Query("SELECT * FROM queue_state WHERE id = ${QueueStateEntity.SINGLETON_ID}")
    fun observe(): Flow<QueueStateEntity?>

    @Upsert
    suspend fun save(entity: QueueStateEntity)

    @Query("DELETE FROM queue_state")
    suspend fun clear()
}

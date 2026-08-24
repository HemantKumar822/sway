package com.sway.core.data

import android.content.Context

/**
 * Owned-data graph facade (stories 8.1–8.3 / 9.4–9.5 consumption): builds THE
 * Room database and exposes repositories WITHOUT leaking storage types past
 * :core:data (AD-8/AR-1). Hilt expansion lands with later epics; this facade
 * keeps every consumer call-site stable when DI arrives.
 */
class AppDataGraph private constructor(
    val library: LibraryRepository,
    val playlists: PlaylistRepository,
    val history: HistoryRepository,
    val sessionRestore: SessionRestoreRepository,
) {
    companion object {
        @Volatile private var instance: AppDataGraph? = null

        /** Process-wide singleton over the named production database. */
        fun from(context: Context): AppDataGraph =
            instance ?: synchronized(this) {
                instance ?: from(context, inMemory = false)
            }

        /** Test variant: isolated in-memory database. */
        fun inMemory(context: Context): AppDataGraph = from(context, inMemory = true)

        private fun from(context: Context, inMemory: Boolean): AppDataGraph {
            val db = if (inMemory) {
                com.sway.core.database.SwayDatabaseInMemoryBuilder(context)
            } else {
                com.sway.core.database.SwayDatabaseBuilder(context)
            }
            return AppDataGraph(
                library = LibraryRepository(db.library),
                playlists = PlaylistRepository(db.playlists),
                history = HistoryRepository(RoomHistoryStore(db.history)),
                sessionRestore = SessionRestoreRepository(RoomQueueStateStore(db.queueState)),
            )
        }
    }
}

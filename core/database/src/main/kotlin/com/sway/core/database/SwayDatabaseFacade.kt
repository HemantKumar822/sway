package com.sway.core.database

import android.content.Context

/**
 * Storage handles without Room-type leakage past :core:data (AD-8/AR-1):
 * consumers receive DAOs (core:database vocabulary) and never touch
 * RoomDatabase/Room classes.
 */
class SwayDatabaseHandles internal constructor(private val db: SwayDatabase) {
    val library: LibraryDao = db.libraryDao()
    val playlists: PlaylistDao = db.playlistDao()
    val history: HistoryDao = db.historyDao()
    val queueState: QueueStateDao = db.queueStateDao()

    /** Closes the underlying database (tests). */
    fun close() = db.close()
}

/** Production database over `sway.db` with ALL explicit migrations attached. */
fun SwayDatabaseBuilder(context: Context): SwayDatabaseHandles =
    SwayDatabaseHandles(SwayDatabase.build(context))

/** In-memory variant for tests. */
fun SwayDatabaseInMemoryBuilder(context: Context): SwayDatabaseHandles =
    SwayDatabaseHandles(
        androidx.room.Room.inMemoryDatabaseBuilder(context, SwayDatabase::class.java)
            .allowMainThreadQueries()
            .build(),
    )

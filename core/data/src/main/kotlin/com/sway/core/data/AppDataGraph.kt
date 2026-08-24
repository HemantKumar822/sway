package com.sway.core.data

import android.content.Context

/**
 * Owned-data graph facade (stories 8.1–8.3 / 9.4–9.5 / 10.2 consumption):
 * builds THE Room database and exposes repositories WITHOUT leaking storage
 * types past :core:data (AD-8/AR-1). The catalog boundary speaks only the
 * core:model [com.sway.core.model.CatalogSource] port — the transport
 * implementation is injected by :app (extractor isolation, AR-2). Hilt
 * expansion lands with later epics; this facade keeps every consumer
 * call-site stable when DI arrives.
 */
class AppDataGraph private constructor(
    val library: LibraryRepository,
    val playlists: PlaylistRepository,
    val history: HistoryRepository,
    val sessionRestore: SessionRestoreRepository,
    val catalog: CatalogRepository,
) {
    companion object {
        @Volatile private var instance: AppDataGraph? = null

        /** Process-wide singleton over the named production database. */
        fun from(context: Context, catalogSource: com.sway.core.model.CatalogSource): AppDataGraph =
            instance ?: synchronized(this) {
                instance ?: from(context, catalogSource, inMemory = false)
            }

        /** Test variant: isolated in-memory database. */
        fun inMemory(context: Context, catalogSource: com.sway.core.model.CatalogSource): AppDataGraph =
            from(context, catalogSource, inMemory = true)

        private fun from(
            context: Context,
            catalogSource: com.sway.core.model.CatalogSource,
            inMemory: Boolean,
        ): AppDataGraph {
            val db = if (inMemory) {
                com.sway.core.database.SwayDatabaseInMemoryBuilder(context)
            } else {
                com.sway.core.database.SwayDatabaseBuilder(context)
            }
            // cacheDir is resolved LAZILY on first catalog-cache use (IO-confined
            // inside the repository) — never during construction (AD-10 startup law).
            return AppDataGraph(
                library = LibraryRepository(db.library),
                playlists = PlaylistRepository(db.playlists),
                history = HistoryRepository(RoomHistoryStore(db.history)),
                sessionRestore = SessionRestoreRepository(RoomQueueStateStore(db.queueState)),
                catalog = CatalogRepository(
                    catalogSource,
                    cacheFactory = { FallbackCacheStore(java.io.File(context.cacheDir, "fallback_cache")) },
                ),
            )
        }
    }
}

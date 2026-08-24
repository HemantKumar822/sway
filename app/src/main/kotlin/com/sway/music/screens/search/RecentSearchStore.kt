package com.sway.music.screens.search

import android.content.Context
import org.json.JSONArray

/**
 * Recent search queries (story 10.2, UX-P3 [PROVISIONAL]): locally persisted,
 * clearable. Interface keeps the ViewModel hermetic; the SharedPreferences
 * implementation performs ALL disk work on the caller-provided io dispatcher
 * (StrictMode main-thread law, AD-10). Relocation to DataStore lands with
 * E15 without touching consumers.
 */
interface RecentSearchStore {
    suspend fun load(): List<String>
    suspend fun save(entries: List<String>)
}

class InMemoryRecentSearchStore : RecentSearchStore {
    var current: List<String> = emptyList()
    override suspend fun load(): List<String> = current
    override suspend fun save(entries: List<String>) {
        current = entries.toList()
    }
}

class SharedPrefsRecentSearchStore(
    private val appContext: Context,
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : RecentSearchStore {

    // Lazy + IO-confined: SharedPreferences must never touch the main thread
    // (StrictMode death-penalty law, AD-10).
    private val prefs by lazy { appContext.getSharedPreferences("sway_search_recents", Context.MODE_PRIVATE) }

    override suspend fun load(): List<String> = kotlinx.coroutines.withContext(io) {
        runCatching {
            val raw = prefs.getString(KEY, null) ?: return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun save(entries: List<String>) = kotlinx.coroutines.withContext(io) {
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(it) }
            prefs.edit().putString(KEY, array.toString()).apply()
        }
        Unit
    }

    private companion object {
        const val KEY = "recent_queries"
    }
}

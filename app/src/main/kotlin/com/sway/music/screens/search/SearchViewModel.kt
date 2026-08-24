package com.sway.music.screens.search

import com.sway.core.data.GroupResult
import com.sway.core.data.CatalogRepository
import com.sway.core.model.SwayError
import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.toUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Search ViewModel (story 10.2, FR-1): debounced (350 ms) + submit-on-action
 * grouped search over [CatalogRepository]. Group isolation is preserved all
 * the way to render state — a failing group never blanks its siblings, and
 * the top-level phase escalates to Error ONLY when every group failed.
 * Zero-match success is typed Empty — never an error masquerade (FR-37).
 */
class SearchViewModel(
    private val repository: CatalogRepository,
    private val recents: RecentSearchStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    init {
        scope.launch {
            val capped = recents.load()
                .take(RECENTS_CAP)
                .filter { it.isNotBlank() }
            if (capped.isNotEmpty()) {
                _state.update { it.copy(recentSearches = capped) }
            }
        }
    }

    /** Text change path: (re)arms the 350 ms debounce; typing cancels prior waits. */
    fun onQueryChanged(query: String) {
        debounceJob?.cancel()
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            cancelSearchToIdle()
            return
        }
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            executeSearch(query.trim(), recordRecent = true)
        }
    }

    /** IME search-action path: immediate, bypassing the debounce. */
    fun onSubmit() {
        debounceJob?.cancel()
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            cancelSearchToIdle()
            return
        }
        scope.launch { executeSearch(query, recordRecent = true) }
    }

    fun onChipSelected(filter: SearchFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /** Retry preserves the submitted query verbatim (FR-37 contract). */
    fun onRetry() {
        val query = _state.value.submittedQuery ?: _state.value.query.trim()
        if (query.isEmpty()) return
        scope.launch { executeSearch(query, recordRecent = false) }
    }

    fun onClearQuery() {
        debounceJob?.cancel()
        cancelSearchToIdle()
    }

    fun onRecentSelected(entry: String) {
        debounceJob?.cancel()
        _state.update { it.copy(query = entry) }
        scope.launch { executeSearch(entry.trim(), recordRecent = false) }
    }

    fun onClearRecents() {
        _state.update { it.copy(recentSearches = emptyList()) }
        scope.launch(io) { recents.save(emptyList()) }
    }

    // --- internals ------------------------------------------------------------

    private fun cancelSearchToIdle() {
        searchJob?.cancel()
        _state.update { it.copy(phase = SearchPhase.Idle) }
    }

    private suspend fun executeSearch(query: String, recordRecent: Boolean) {
        searchJob?.cancel()
        searchJob = scope.launch {
            _state.update {
                it.copy(
                    submittedQuery = query,
                    phase = SearchPhase.Loading,
                    recentSearches = if (recordRecent) record(it.recentSearches, query) else it.recentSearches,
                )
            }
            if (recordRecent) persistRecents()

            val results = repository.search(query)
            val songs = mapGroup(results.songs)
            val albums = mapGroup(results.albums)
            val artists = mapGroup(results.artists)
            val playlists = mapGroup(results.playlists)

            val content = SearchContent(songs, albums, artists, playlists)
            val anyItems = listOf(songs, albums, artists, playlists).any { it.items.isNotEmpty() }
            val errors = listOfNotNull(
                songs.errorOrNull(), albums.errorOrNull(),
                artists.errorOrNull(), playlists.errorOrNull(),
            )
            val nextPhase = when {
                anyItems -> SearchPhase.Results(content)
                errors.size == GROUP_COUNT -> SearchPhase.Error(errors.first())
                else -> SearchPhase.Empty
            }
            _state.update { current ->
                // A cleared/newer submission supersedes this response.
                if (current.submittedQuery != query) current else current.copy(phase = nextPhase)
            }
        }
    }

    private suspend fun persistRecents() {
        val snapshot = _state.value.recentSearches
        scope.launch(io) { recents.save(snapshot) }
    }

    private fun <T> mapGroup(group: GroupResult<T>): GroupState<T> = when (group) {
        is GroupResult.Fresh -> GroupState.fresh(group.page.items)
        is GroupResult.Stale -> GroupState.stale(group.page.items)
        is GroupResult.Failed -> GroupState.failed(categoryOf(group.error))
    }

    private fun categoryOf(error: SwayError): SwayErrorUiState = error.toUiState()

    private fun <T> GroupState<T>.errorOrNull(): SwayErrorUiState? = error

    private fun record(existing: List<String>, query: String): List<String> =
        (listOf(query) + existing.filter { it.equals(query, ignoreCase = true).not() })
            .distinctBy { it.lowercase() }
            .take(RECENTS_CAP)

    companion object {
        const val DEBOUNCE_MS = 350L
        const val RECENTS_CAP = 10
        private const val GROUP_COUNT = 4
    }
}

package com.sway.music.screens.search

import com.sway.core.data.GroupResult
import com.sway.core.data.CatalogRepository
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
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
    private val loadMoreJobs = mutableMapOf<SearchGroup, Job>()

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

    /**
     * Per-group load-more (story 10.3, FR-2): ONE guarded entry point shared
     * by the button and the sentinel. In-flight guard collapses rapid repeat
     * triggers to a single request; appends dedupe by Source ID; an exhausted
     * group (null token) never fires again.
     */
    fun onLoadMore(group: SearchGroup) {
        val state = _state.value
        val content = (state.phase as? SearchPhase.Results)?.content ?: return
        val query = state.submittedQuery ?: return
        if (loadMoreJobs[group]?.isActive == true) return
        when (group) {
            SearchGroup.SONGS ->
                beginLoadMore(content.songs, group, query, call = { q, t -> repository.songsPage(q, t) })
            SearchGroup.ALBUMS ->
                beginLoadMore(content.albums, group, query, call = { q, t -> repository.albumsPage(q, t) })
            SearchGroup.ARTISTS ->
                beginLoadMore(content.artists, group, query, call = { q, t -> repository.artistsPage(q, t) })
            SearchGroup.PLAYLISTS ->
                beginLoadMore(content.playlists, group, query, call = { q, t -> repository.playlistsPage(q, t) })
        }
    }

    // --- internals ------------------------------------------------------------

    private fun cancelSearchToIdle() {
        searchJob?.cancel()
        cancelLoadMoreJobs()
        _state.update { it.copy(phase = SearchPhase.Idle) }
    }

    private fun cancelLoadMoreJobs() {
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
    }

    /** Generic typed mutation of one group's state inside the Results phase. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> mutateGroup(group: SearchGroup, transform: (GroupState<T>) -> GroupState<T>) {
        _state.update { st ->
            val content = (st.phase as? SearchPhase.Results)?.content ?: return@update st
            val next = when (group) {
                SearchGroup.SONGS ->
                    content.copy(songs = transform(content.songs as GroupState<T>) as GroupState<Song>)
                SearchGroup.ALBUMS ->
                    content.copy(albums = transform(content.albums as GroupState<T>) as GroupState<Album>)
                SearchGroup.ARTISTS ->
                    content.copy(artists = transform(content.artists as GroupState<T>) as GroupState<Artist>)
                SearchGroup.PLAYLISTS ->
                    content.copy(playlists = transform(content.playlists as GroupState<T>) as GroupState<CatalogPlaylist>)
            }
            st.copy(phase = SearchPhase.Results(next))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> beginLoadMore(
        current: GroupState<T>,
        group: SearchGroup,
        query: String,
        call: suspend (String, String) -> GroupResult<T>,
    ) {
        if (!current.canLoadMore) return
        val token = current.nextPageToken ?: return
        mutateGroup<T>(group) { it.copy(loadingMore = true, appendError = null) }
        loadMoreJobs[group] = scope.launch {
            val result = call(query, token)
            // A newer submission superseded this page request — drop it.
            if (_state.value.submittedQuery != query) {
                loadMoreJobs.remove(group)
                return@launch
            }
            mutateGroup<T>(group) { g ->
                when (result) {
                    is GroupResult.Fresh -> appendPage(g, result.page.items, result.page.normalizedNextPageToken, stale = false)
                    is GroupResult.Stale -> appendPage(g, result.page.items, result.page.normalizedNextPageToken, stale = true)
                    is GroupResult.Failed -> g.copy(loadingMore = false, appendError = categoryOf(result.error))
                }
            }
            loadMoreJobs.remove(group)
        }
    }

    /** Dedupe-by-Source-ID append law (FR-2): a duplicate page adds nothing. */
    private fun <T> appendPage(g: GroupState<T>, items: List<T>, nextToken: String?, stale: Boolean): GroupState<T> {
        val known = g.items.mapTo(mutableSetOf()) { idOf(it) }
        val appended = items.filter { idOf(it) !in known }
        return g.copy(
            items = g.items + appended,
            nextPageToken = nextToken,
            loadingMore = false,
            appendError = null,
            stale = g.stale || stale,
        )
    }

    /** Stable list keys = Source ID (AR-14); dedupe identity rides the same law. */
    private fun <T> idOf(item: T): String = when (item) {
        is Song -> item.id.value
        is Album -> item.id.value
        is Artist -> item.id.value
        is CatalogPlaylist -> item.id.value
        else -> item.toString()
    }

    private suspend fun executeSearch(query: String, recordRecent: Boolean) {
        searchJob?.cancel()
        searchJob = scope.launch {
            cancelLoadMoreJobs()
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
        is GroupResult.Fresh ->
            GroupState.fresh(group.page.items, group.page.normalizedNextPageToken)
        is GroupResult.Stale ->
            GroupState.stale(group.page.items, group.page.normalizedNextPageToken)
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

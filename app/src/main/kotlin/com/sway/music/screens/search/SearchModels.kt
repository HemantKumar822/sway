package com.sway.music.screens.search

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
import com.sway.core.model.SwayErrorUiState

/**
 * Search filter chips (story 10.2, UX-P3 [PROVISIONAL]): which groups render.
 * Doubles as the load-more target identity (story 10.3).
 */
enum class SearchFilter { ALL, SONGS, ALBUMS, ARTISTS, PLAYLISTS }

/** The four catalog result groups (story 10.3 per-group pagination identity). */
enum class SearchGroup { SONGS, ALBUMS, ARTISTS, PLAYLISTS }

/**
 * One search group's render state (group-isolation law from 10.1 mapped to
 * the FR-37 quintet): EXACTLY ONE of [loading] / items / error renders per
 * group; a success-empty group renders its honest no-matches line. [stale]
 * marks fallback-cache-served content (FR-4 badge duty completes 10.4).
 *
 * Pagination (story 10.3, FR-2): [nextPageToken] non-null = more pages exist;
 * [loadingMore] guards the in-flight append; [appendError] surfaces a failed
 * page BELOW the items (never replacing them) with its own retry.
 */
data class GroupState<T>(
    val loading: Boolean = false,
    val items: List<T> = emptyList(),
    val stale: Boolean = false,
    val error: SwayErrorUiState? = null,
    val nextPageToken: String? = null,
    val loadingMore: Boolean = false,
    val appendError: SwayErrorUiState? = null,
) {
    val canLoadMore: Boolean get() = error == null && !loading && items.isNotEmpty() &&
        nextPageToken != null && !loadingMore

    companion object {
        fun <T> loading(): GroupState<T> = GroupState(loading = true)
        fun <T> fresh(items: List<T>, nextPageToken: String? = null): GroupState<T> =
            GroupState(items = items, nextPageToken = nextPageToken)
        fun <T> stale(items: List<T>, nextPageToken: String? = null): GroupState<T> =
            GroupState(items = items, stale = true, nextPageToken = nextPageToken)
        fun <T> failed(category: SwayErrorUiState): GroupState<T> = GroupState(error = category)
    }
}

/** Typed item payload for the Results phase (per-group states ride along). */
data class SearchContent(
    val songs: GroupState<Song>,
    val albums: GroupState<Album>,
    val artists: GroupState<Artist>,
    val playlists: GroupState<CatalogPlaylist>,
)

/**
 * Top-level phase — exactly one at any moment:
 * - [Idle]: blank query; recent searches overlay shows.
 * - [Loading]: first-page fetch in flight (skeletons).
 * - [Results]: at least one group returned data (siblings may be failed/empty).
 * - [Empty]: every group succeeded with zero matches — typed empty, NEVER an
 *   error masquerade (FR-1/FR-37 distinction).
 * - [Error]: every group failed — area retry preserving the query.
 */
sealed interface SearchPhase {
    data object Idle : SearchPhase
    data object Loading : SearchPhase
    data class Results(val content: SearchContent) : SearchPhase
    data object Empty : SearchPhase
    data class Error(val category: SwayErrorUiState) : SearchPhase
}

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String? = null,
    val filter: SearchFilter = SearchFilter.ALL,
    val phase: SearchPhase = SearchPhase.Idle,
    val recentSearches: List<String> = emptyList(),
)

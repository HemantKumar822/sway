package com.sway.music.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
import com.sway.designui.components.AlbumCard
import com.sway.designui.components.EmptyState
import com.sway.designui.components.ErrorPanel
import com.sway.designui.components.SongRow
import com.sway.designui.components.SongRowGhost
import com.sway.designui.components.StaleBadge

/**
 * Search screen core (story 10.2, FR-1): grouped labeled four-type results,
 * Songs first per UX-P7, quintet states exactly-one, group-isolated failure
 * panels, typed zero-match Empty with spelling hint + Clear. Parameterized on
 * state + callbacks only — no repository contact (hermetic tests).
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onChipSelected: (SearchFilter) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: (SearchGroup) -> Unit,
    onClearQuery: () -> Unit,
    onRecentSelected: (String) -> Unit,
    onClearRecents: () -> Unit,
    onSongClick: (Song) -> Unit,
    onSongLongClick: (Song) -> Unit = {},
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (CatalogPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("search_field"),
            singleLine = true,
            placeholder = { Text("Search songs, artists, albums…") },
        )

        FilterChipsRow(state.filter, onChipSelected)

        when (val phase = state.phase) {
            SearchPhase.Idle -> RecentsOverlay(
                entries = state.recentSearches.takeIf { state.query.isBlank() }.orEmpty(),
                onRecentSelected = onRecentSelected,
                onClearRecents = onClearRecents,
            )
            SearchPhase.Loading -> LoadingSkeletons()
            is SearchPhase.Error -> ErrorPanel(
                category = phase.category,
                onRetry = onRetry,
                area = true,
                messageOverride = "Couldn't search for \"${state.submittedQuery ?: state.query}\". Retry?",
            )
            SearchPhase.Empty -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    title = "No results for \"${state.submittedQuery ?: state.query}\"",
                    hint = "Check your spelling or try fewer words.",
                    modifier = Modifier.padding(top = 48.dp),
                )
                TextButton(onClick = onClearQuery, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Clear")
                }
            }
            is SearchPhase.Results -> ResultsList(
                phase = phase.content,
                query = state.submittedQuery.orEmpty(),
                filter = state.filter,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onSongLongClick = onSongLongClick,
                onSongClick = onSongClick,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
            )
        }
    }
}

@Composable
private fun FilterChipsRow(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

private val SearchFilter.label: String
    get() = when (this) {
        SearchFilter.ALL -> "All"
        SearchFilter.SONGS -> "Songs"
        SearchFilter.ALBUMS -> "Albums"
        SearchFilter.ARTISTS -> "Artists"
        SearchFilter.PLAYLISTS -> "Playlists"
    }

@Composable
private fun LoadingSkeletons() {
    Column(Modifier.testTag("skeletons")) {
        repeat(4) { SongRowGhost() }
    }
}

@Composable
private fun RecentsOverlay(
    entries: List<String>,
    onRecentSelected: (String) -> Unit,
    onClearRecents: () -> Unit,
) {
    if (entries.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "recents_header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearRecents) { Text("Clear") }
            }
        }
        items(entries, key = { it }) { entry ->
            Text(
                entry,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRecentSelected(entry) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ResultsList(
    phase: SearchContent,
    query: String,
    filter: SearchFilter,
    onRetry: () -> Unit,
    onLoadMore: (SearchGroup) -> Unit,
    onSongLongClick: (Song) -> Unit,
    onSongClick: (Song) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onPlaylistClick: (CatalogPlaylist) -> Unit,
) {
    // Songs first per UX-P7; each group renders its own honest state.
    LazyColumn(Modifier.fillMaxSize().testTag("results")) {
        if (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) {
            songSection(phase.songs, onRetry, onSongClick, onSongLongClick) { onLoadMore(SearchGroup.SONGS) }
        }
        if (filter == SearchFilter.ALL || filter == SearchFilter.ALBUMS) {
            albumSection(phase.albums, onRetry, onAlbumClick) { onLoadMore(SearchGroup.ALBUMS) }
        }
        if (filter == SearchFilter.ALL || filter == SearchFilter.ARTISTS) {
            artistSection(phase.artists, onRetry, onArtistClick) { onLoadMore(SearchGroup.ARTISTS) }
        }
        if (filter == SearchFilter.ALL || filter == SearchFilter.PLAYLISTS) {
            playlistSection(phase.playlists, onRetry, onPlaylistClick) { onLoadMore(SearchGroup.PLAYLISTS) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.songSection(
    group: GroupState<Song>,
    onRetry: () -> Unit,
    onClick: (Song) -> Unit,
    onLongClick: (Song) -> Unit,
    onLoadMore: () -> Unit,
) {
    sectionHeader("Songs", group.stale)
    when {
        group.error != null -> failedItems("songs", group.error, onRetry)
        group.loading -> item(key = "songs_loading") { SongRowGhost() }
        group.items.isEmpty() -> emptyGroupLine("songs")
        else -> items(group.items, key = { it.id.value }) { song ->
            SongRow(song = song, onClick = { onClick(song) }, onLongClick = { onLongClick(song) })
        }
    }
    sectionFooter("songs", group, onLoadMore)
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumSection(
    group: GroupState<Album>,
    onRetry: () -> Unit,
    onClick: (Album) -> Unit,
    onLoadMore: () -> Unit,
) {
    sectionHeader("Albums", group.stale)
    when {
        group.error != null -> failedItems("albums", group.error, onRetry)
        group.loading -> item(key = "albums_loading") { SongRowGhost() }
        group.items.isEmpty() -> emptyGroupLine("albums")
        else -> items(group.items, key = { it.id.value }) { album ->
            AlbumCard(
                title = album.title,
                subtitle = album.artistName,
                onClick = { onClick(album) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
    sectionFooter("albums", group, onLoadMore)
}

private fun androidx.compose.foundation.lazy.LazyListScope.artistSection(
    group: GroupState<Artist>,
    onRetry: () -> Unit,
    onClick: (Artist) -> Unit,
    onLoadMore: () -> Unit,
) {
    sectionHeader("Artists", group.stale)
    when {
        group.error != null -> failedItems("artists", group.error, onRetry)
        group.loading -> item(key = "artists_loading") { SongRowGhost() }
        group.items.isEmpty() -> emptyGroupLine("artists")
        else -> items(group.items, key = { it.id.value }) { artist ->
            Text(
                artist.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(artist) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    sectionFooter("artists", group, onLoadMore)
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistSection(
    group: GroupState<CatalogPlaylist>,
    onRetry: () -> Unit,
    onClick: (CatalogPlaylist) -> Unit,
    onLoadMore: () -> Unit,
) {
    sectionHeader("Playlists", group.stale)
    when {
        group.error != null -> failedItems("playlists", group.error, onRetry)
        group.loading -> item(key = "playlists_loading") { SongRowGhost() }
        group.items.isEmpty() -> emptyGroupLine("playlists")
        else -> items(group.items, key = { it.id.value }) { playlist ->
            AlbumCard(
                title = playlist.title,
                subtitle = playlist.curator,
                onClick = { onClick(playlist) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
    sectionFooter("playlists", group, onLoadMore)
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionHeader(label: String, stale: Boolean) {
    item(key = "header_$label") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("section_$label"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (stale) StaleBadge()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.emptyGroupLine(key: String) {
    item(key = "empty_$key") {
        Text(
            "No matches",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.sectionFooter(
    key: String,
    group: GroupState<T>,
    onLoadMore: () -> Unit,
) {
    if (group.appendError != null) {
        item(key = "appenderr_$key") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Couldn't load more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Retry")
                }
            }
        }
    }
    if (group.canLoadMore || group.loadingMore) {
        // Infinite-scroll sentinel: fires once on composition; the ViewModel's
        // in-flight guard makes double-triggers harmless (FR-2).
        item(key = "sentinel_$key") {
            androidx.compose.runtime.LaunchedEffect(group.nextPageToken) {
                if (!group.loadingMore) onLoadMore()
            }
        }
        item(key = "loadmore_$key") {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onLoadMore,
                    enabled = !group.loadingMore,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("loadmore_$key"),
                ) {
                    Text(if (group.loadingMore) "Loading…" else "Load more")
                }
            }
        }
    } else if (
        group.items.isNotEmpty() &&
        !group.loading &&
        group.error == null &&
        group.appendError == null
    ) {
        // End-of-results divider (FR-2): exactly once, exhausted groups only.
        item(key = "end_$key") {
            Text(
                "That's everything",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag("end_$key"),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.failedItems(
    key: String,
    category: com.sway.core.model.SwayErrorUiState,
    onRetry: () -> Unit,
) {
    item(key = "failed_$key") {
        ErrorPanel(
            category = category,
            onRetry = onRetry,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}







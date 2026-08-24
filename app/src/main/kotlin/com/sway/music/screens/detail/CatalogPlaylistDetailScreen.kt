package com.sway.music.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
import com.sway.designui.components.ErrorPanel
import com.sway.designui.components.HeroHeader
import com.sway.designui.components.HeroHeaderGhost
import com.sway.designui.components.SongRow
import com.sway.designui.components.StaleBadge

/**
 * Catalog Playlist detail (story 10.7, FR-7): curator + count hero, ordered
 * tracklist, Play/Shuffle semantics identical to album — and ZERO edit
 * affordances anywhere (read-only by definition; grep-auditable).
 */
@Composable
fun CatalogPlaylistDetailScreen(
    state: DetailState<CatalogPlaylist>,
    onRetry: () -> Unit,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DetailState.Loading -> Column(Modifier.fillMaxSize()) { HeroHeaderGhost() }
        is DetailState.Error -> ErrorPanel(category = state.category, onRetry = onRetry, area = true)
        is DetailState.Content -> {
            val playlist = state.data
            val seed = remember { System.nanoTime() }
            val count = playlist.trackCount ?: playlist.tracks.size
            Column(modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append("$count songs")
                            playlist.curator?.let { append(" · by $it") }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.stale) StaleBadge(Modifier.padding(start = 8.dp))
                }
                HeroHeader(title = playlist.title, subtitle = null)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            onPlaybackRequest(
                                PlaybackRequests.build(playlist.tracks, PlaybackRequests.Mode.FromIndex(0)),
                            )
                        },
                        enabled = playlist.tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Play") }
                    OutlinedButton(
                        onClick = {
                            onPlaybackRequest(
                                PlaybackRequests.build(playlist.tracks, PlaybackRequests.Mode.Shuffled(seed)),
                            )
                        },
                        enabled = playlist.tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Shuffle") }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(playlist.tracks, key = { _, s -> s.id.value }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = {
                                onPlaybackRequest(
                                    PlaybackRequests.build(playlist.tracks, PlaybackRequests.Mode.FromIndex(index)),
                                )
                            },
                            indexLabel = "${index + 1}",
                            onLongClick = { onSongLongClick(song) },
                        )
                    }
                }
            }
        }
    }
}

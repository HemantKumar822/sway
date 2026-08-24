package com.sway.music.screens.detail

import androidx.compose.foundation.clickable
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
import com.sway.core.model.Album
import com.sway.core.model.SourceId
import com.sway.core.model.Song
import com.sway.designui.components.ErrorPanel
import com.sway.designui.components.HeroHeader
import com.sway.designui.components.HeroHeaderGhost
import com.sway.designui.components.SongRow
import com.sway.designui.components.StaleBadge

/**
 * Album detail (story 10.5, FR-5): hero header with clean year omission,
 * numbered tracklist, Play/Shuffle building context queues (FR-22 contract),
 * quintet states incl. stale per 10.4 rules.
 */
@Composable
fun AlbumDetailScreen(
    state: DetailState<Album>,
    onRetry: () -> Unit,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onArtistClick: (SourceId) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DetailState.Loading -> Column(Modifier.fillMaxSize()) { HeroHeaderGhost() }
        is DetailState.Error -> ErrorPanel(category = state.category, onRetry = onRetry, area = true)
        is DetailState.Content -> {
            val album = state.data
            val seed = remember { System.nanoTime() }
            Column(modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${album.tracks.size} songs",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.stale) StaleBadge(Modifier.padding(start = 8.dp))
                }
                HeroHeader(
                    title = album.title,
                    subtitle = detailSubtitle(album.artistName, album.year),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            onPlaybackRequest(
                                PlaybackRequests.build(album.tracks, PlaybackRequests.Mode.FromIndex(0)),
                            )
                        },
                        enabled = album.tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Play") }
                    OutlinedButton(
                        onClick = {
                            onPlaybackRequest(
                                PlaybackRequests.build(album.tracks, PlaybackRequests.Mode.Shuffled(seed)),
                            )
                        },
                        enabled = album.tracks.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Shuffle") }
                }
                album.artistId?.let { artistId ->
                    Text(
                        "Go to artist",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { onArtistClick(artistId) },
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(album.tracks, key = { _, s -> s.id.value }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = {
                                onPlaybackRequest(
                                    PlaybackRequests.build(album.tracks, PlaybackRequests.Mode.FromIndex(index)),
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

/** Clean omission law: absent parts never render placeholders (FR-5/10.5). */
internal fun detailSubtitle(artistName: String?, year: Int?): String? =
    listOfNotNull(artistName, year?.toString()).joinToString(" · ").ifEmpty { null }

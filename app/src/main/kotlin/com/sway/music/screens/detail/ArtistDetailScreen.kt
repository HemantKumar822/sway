package com.sway.music.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sway.core.model.Artist
import com.sway.core.model.Song
import com.sway.designui.components.AlbumCard
import com.sway.designui.components.ErrorPanel
import com.sway.designui.components.HeroHeaderGhost
import com.sway.designui.components.SongRow

/**
 * Artist detail (story 10.6, FR-6, OQ-1 degraded tier): circular portrait with
 * initials-avatar fallback, top songs fully playable/shuffleable, Albums and
 * Singles rails render ONLY when the mapper marked them available — absent
 * sections omit entirely (no empty shells).
 */
@Composable
fun ArtistDetailScreen(
    state: DetailState<Artist>,
    onRetry: () -> Unit,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DetailState.Loading -> Column(Modifier.fillMaxSize()) { HeroHeaderGhost() }
        is DetailState.Error -> ErrorPanel(category = state.category, onRetry = onRetry, area = true)
        is DetailState.Content -> {
            val artist = state.data
            val seed = remember { System.nanoTime() }
            LazyColumn(modifier.fillMaxSize()) {
                item(key = "artist_header") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        InitialsAvatar(name = artist.name)
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
                        )
                    }
                }
                if (artist.topSongs.isNotEmpty()) {
                    item(key = "topsongs_header") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Top songs", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onPlaybackRequest(
                                            PlaybackRequests.build(
                                                artist.topSongs,
                                                PlaybackRequests.Mode.FromIndex(0),
                                            ),
                                        )
                                    },
                                ) { Text("Play") }
                                OutlinedButton(
                                    onClick = {
                                        onPlaybackRequest(
                                            PlaybackRequests.build(
                                                artist.topSongs,
                                                PlaybackRequests.Mode.Shuffled(seed),
                                            ),
                                        )
                                    },
                                ) { Text("Shuffle") }
                            }
                        }
                    }
                    itemsIndexed(artist.topSongs, key = { _, s -> s.id.value }) { _, song ->
                        SongRow(
                            song = song,
                            onClick = {
                                onPlaybackRequest(
                                    PlaybackRequests.build(artist.topSongs, PlaybackRequests.Mode.FromIndex(0)),
                                )
                            },
                            onLongClick = { onSongLongClick(song) },
                        )
                    }
                }
                // OQ-1 degraded tier: rails render only when data exists.
                if (artist.albumsAvailable && artist.albums.isNotEmpty()) {
                    item(key = "albums_header") {
                        Text(
                            "Albums",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(artist.albums, key = { _, a -> a.id.value }) { _, album ->
                        AlbumCard(
                            title = album.title,
                            subtitle = album.year?.toString(),
                            onClick = {},
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
                if (artist.singlesAvailable && artist.singles.isNotEmpty()) {
                    item(key = "singles_header") {
                        Text(
                            "Singles",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(artist.singles, key = { _, a -> a.id.value }) { _, single ->
                        AlbumCard(
                            title = single.title,
                            subtitle = single.year?.toString(),
                            onClick = {},
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Initials-avatar fallback (UX-DR7): zero layout shift vs the portrait. */
@Composable
private fun InitialsAvatar(name: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        com.sway.designui.components.ArtworkPlaceholder(Modifier.size(120.dp))
        Text(
            initialsOf(name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")

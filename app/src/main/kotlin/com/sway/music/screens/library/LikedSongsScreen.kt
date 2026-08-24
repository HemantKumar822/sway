package com.sway.music.screens.library

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
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.designui.components.EmptyState
import com.sway.designui.components.SongRow

/**
 * Liked Songs (story 11.1, FR-30 collection surface; SYNC completes 12.2):
 * newest-first from the DB instantly — NEVER skeletons (DR5 local-data law).
 * Play = display order @0, Shuffle = seeded permutation, row tap = queue at
 * the tapped position (FR-22 contract via [PlaybackRequests]).
 */
@Composable
fun LikedSongsScreen(
    songs: List<Song>,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            Text(
                "♥",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.error, // rose semantic role: like
                modifier = Modifier.padding(start = 16.dp, top = 24.dp),
            )
            EmptyState(
                title = "Songs you like will appear here.",
                hint = "Tap the heart anywhere.",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        return
    }

    val seed = remember { System.nanoTime() }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "♥ ${songs.size}",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Liked Songs",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onPlaybackRequest(PlaybackRequests.build(songs, PlaybackRequests.Mode.FromIndex(0)))
                }) { Text("Play") }
                OutlinedButton(onClick = {
                    onPlaybackRequest(PlaybackRequests.build(songs, PlaybackRequests.Mode.Shuffled(seed)))
                }) { Text("Shuffle") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id.value }) { index, song ->
                SongRow(
                    song = song,
                    onClick = {
                        onPlaybackRequest(PlaybackRequests.build(songs, PlaybackRequests.Mode.FromIndex(index)))
                    },
                    onLongClick = { onSongLongClick(song) },
                )
            }
        }
    }
}




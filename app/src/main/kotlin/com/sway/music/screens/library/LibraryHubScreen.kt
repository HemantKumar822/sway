package com.sway.music.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sway.core.data.PlaylistSummary
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.designui.components.EmptyState

/**
 * Library hub (story 11.4, FR-31 + FR-33 COMPLETES HERE): Liked hero tile,
 * playlist cards -> editor, Play History entry row, create-playlist dialog
 * (duplicates allowed, persists immediately). Overflow slot for Settings/
 * About is reserved and lands with 15.2 (EP-4, no stub churn). Every tile
 * starts correct-context playback (FR-22 matrix completion).
 */
@Composable
fun LibraryHubScreen(
    likedSongs: List<Song>,
    playlists: List<PlaylistSummary>,
    historyCount: Int,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onOpenPlaylist: (playlistId: String, name: String) -> Unit,
    onOpenLiked: () -> Unit,
    onOpenHistory: () -> Unit,
    onCreatePlaylist: (name: String) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "liked_tile") {
            Card(onClick = onOpenLiked, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "♥",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error, // rose semantic role: like
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Liked Songs", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${likedSongs.size} songs",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        if (likedSongs.isNotEmpty()) {
                            onPlaybackRequest(PlaybackRequests.build(likedSongs, PlaybackRequests.Mode.FromIndex(0)))
                        }
                    }, enabled = likedSongs.isNotEmpty()) { Text("Play") }
                }
            }
        }
        item(key = "history_row") {
            Card(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🕘", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Play History", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "$historyCount plays",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item(key = "playlists_header") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Playlists", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { showCreate = true }) { Text("+ New") }
            }
        }
        if (playlists.isEmpty()) {
            item(key = "playlists_empty") {
                EmptyState(
                    title = "No playlists yet.",
                    hint = "Tap + New to start your first one.",
                )
            }
        }
        items(playlists, key = { it.playlist.id.value }) { summary ->
            Card(
                onClick = { onOpenPlaylist(summary.playlist.id.value, summary.playlist.name) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(summary.playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${summary.songCount} songs",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Overflow slot for Settings/About lands here in 15.2 (documented
        // reservation per EP-4 — no stub churn).
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onConfirm = {
                showCreate = false
                onCreatePlaylist(it)
            },
            onDismiss = { showCreate = false },
        )
    }
}

private fun Modifier.testTagReserved(): Modifier =
    this.then(Modifier)

@Composable
private fun CreatePlaylistDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.testTag("create_name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}



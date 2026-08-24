package com.sway.music.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sway.core.data.PlaylistSummary
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.designui.components.ArtworkPlaceholder
import com.sway.designui.components.EmptyState
import com.sway.designui.components.SongRow

/**
 * Playlist detail & editor (story 11.3, FR-32): every edit persists
 * IMMEDIATELY (no save button exists anywhere); Edit mode reveals per-row
 * remove X + move up/down (touch-drag deferred to device matrix; the row
 * controls are the accessibility alternative per DR10); Add-songs opens the
 * multi-select batch picker over Liked Songs; rename allows duplicates;
 * delete confirms with "This can't be undone."
 */
@Composable
fun PlaylistEditorScreen(
    name: String,
    state: PlaylistEditorUiState,
    likedSongs: List<Song>,
    playlists: List<PlaylistSummary>,
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onToggleEditMode: () -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onAddBatch: (List<Song>) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    val seed = remember { System.nanoTime() }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPlaceholder(Modifier.weight(0.35f)) // generated duotone art [PROVISIONAL]
            Column(Modifier.weight(0.65f).padding(start = 12.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.songs.size} songs",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlaybackRequest(PlaybackRequests.build(state.songs, PlaybackRequests.Mode.FromIndex(0))) },
                        enabled = state.songs.isNotEmpty(),
                    ) { Text("Play") }
                    OutlinedButton(
                        onClick = { onPlaybackRequest(PlaybackRequests.build(state.songs, PlaybackRequests.Mode.Shuffled(seed))) },
                        enabled = state.songs.isNotEmpty(),
                    ) { Text("Shuffle") }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.editMode) {
                TextButton(onClick = { showAdd = true }) { Text("Add songs") }
                TextButton(onClick = { showRename = true }) { Text("Rename") }
                TextButton(onClick = { showDelete = true }) { Text("Delete") }
            }
            TextButton(onClick = onToggleEditMode) {
                Text(if (state.editMode) "Done" else "Edit")
            }
        }

        if (state.songs.isEmpty()) {
            EmptyState(
                title = "This playlist is empty.",
                hint = if (state.editMode) {
                    "Use Add songs to fill it — everything saves instantly."
                } else {
                    "Tap Edit to add songs. Everything works offline and saves instantly."
                },
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.songs, key = { _, s -> s.id.value }) { index, song ->
                SongRow(
                    song = song,
                    onClick = {
                        onPlaybackRequest(PlaybackRequests.build(state.songs, PlaybackRequests.Mode.FromIndex(index)))
                    },
                    indexLabel = "${index + 1}",
                    onLongClick = { onSongLongClick(song) },
                )
                if (state.editMode) {
                    Row(Modifier.padding(start = 76.dp)) {
                        TextButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.testTag("moveup_$index"),
                        ) { Text("↑") }
                        TextButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < state.songs.size - 1,
                            modifier = Modifier.testTag("movedown_$index"),
                        ) { Text("↓") }
                        TextButton(
                            onClick = { onRemoveAt(index) },
                            modifier = Modifier.testTag("remove_$index"),
                        ) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(currentName = name, onConfirm = { showRename = false; onRename(it) }, onDismiss = { showRename = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete \"$name\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    if (showAdd) {
        BatchAddToPlaylistPicker(
            likedSongs = likedSongs,
            existingIds = state.songs.map { it.id.value }.toSet(),
            onConfirm = { showAdd = false; onAddBatch(it) },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.testTag("rename_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Confirm rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Multi-select batch picker (story 11.3 AC): checkbox rows over Liked Songs,
 * confirm fires ONE batch call. Already-present songs are filtered upstream.
 */
@Composable
fun BatchAddToPlaylistPicker(
    likedSongs: List<Song>,
    existingIds: Set<String>,
    onConfirm: (List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateOf(setOf<String>()) }
    val candidates = likedSongs.filter { it.id.value !in existingIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add songs from Liked") },
        text = {
            if (candidates.isEmpty()) {
                Text("Nothing new to add — every liked song is already here.")
            } else {
                LazyColumn {
                    itemsIndexed(candidates, key = { _, s -> s.id.value }) { _, song ->
                        val checked = song.id.value in selected.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value =
                                        if (checked) selected.value - song.id.value
                                        else selected.value + song.id.value
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (checked) "☑" else "☐", Modifier.padding(end = 12.dp))
                            Text(song.title)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(candidates.filter { it.id.value in selected.value })
                },
                enabled = selected.value.isNotEmpty(),
            ) { Text("Add ${selected.value.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}





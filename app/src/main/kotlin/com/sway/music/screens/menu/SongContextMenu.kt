package com.sway.music.screens.menu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sway.core.data.PlaylistSummary
import com.sway.core.model.Song

/**
 * Song context menu (story 10.8, DR12; FR-24 menu-surface trace / FR-30 toggle
 * trace): long-press anywhere song lists exist. Queue insertions emit commands
 * the E12 cross-surface wiring feeds to PlayerConnection; like/add-to-persist
 * via the owned-data repositories immediately.
 */
enum class SongMenuAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,

    /** Story 12.3: explicit queue-sheet entry from any song row [PROVISIONAL per UX §3.2]. */
    OPEN_QUEUE,
    ADD_TO_PLAYLIST,
    TOGGLE_LIKE,
    GO_TO_ALBUM,
    GO_TO_ARTIST,
    SHARE_URL,
}

/** Raw catalog URL [PROVISIONAL per DR12]: canonical watch URL for a Source ID. */
fun rawCatalogUrl(sourceId: String): String = "https://music.youtube.com/watch?v=$sourceId"

/** Share the raw catalog URL via the platform chooser (DR12 [PROVISIONAL]). */
fun shareRawUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, "Share song link"))
}

/** Pure visibility law (AC: Go-to entries appear only when refs exist). */
fun visibleActions(song: Song, liked: Boolean): List<SongMenuAction> = buildList {
    add(SongMenuAction.PLAY_NEXT)
    add(SongMenuAction.ADD_TO_QUEUE)
    add(SongMenuAction.OPEN_QUEUE)
    add(SongMenuAction.ADD_TO_PLAYLIST)
    add(SongMenuAction.TOGGLE_LIKE)
    if (song.albumId != null) add(SongMenuAction.GO_TO_ALBUM)
    if (song.artistId != null) add(SongMenuAction.GO_TO_ARTIST)
    add(SongMenuAction.SHARE_URL)
}

fun actionLabel(action: SongMenuAction, song: Song, liked: Boolean): String = when (action) {
    SongMenuAction.PLAY_NEXT -> "Play next"
    SongMenuAction.ADD_TO_QUEUE -> "Add to queue"
    SongMenuAction.OPEN_QUEUE -> "Open queue"
    SongMenuAction.ADD_TO_PLAYLIST -> "Add to playlist…"
    SongMenuAction.TOGGLE_LIKE -> if (liked) "Unlike" else "Like"
    SongMenuAction.GO_TO_ALBUM -> "Go to album"
    SongMenuAction.GO_TO_ARTIST -> "Go to artist"
    SongMenuAction.SHARE_URL -> "Share song link"
}

/**
 * The menu surface. [onAction] receives every non-picker action;
 * Add-to-playlist opens the embedded picker ([AddToPlaylistPicker]) which
 * resolves through [onAddToPlaylist] / [onCreatePlaylistAndAdd].
 */
@Composable
fun SongContextMenu(
    song: Song,
    liked: Boolean,
    playlists: List<PlaylistSummary>,
    onAction: (SongMenuAction) -> Unit,
    onAddToPlaylist: (playlistId: String, song: Song) -> Unit,
    onCreatePlaylistAndAdd: (name: String, song: Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AddToPlaylistPicker(
            song = song,
            playlists = playlists,
            onAddToPlaylist = { pid, s ->
                showPicker = false
                onAddToPlaylist(pid, s)
            },
            onCreatePlaylistAndAdd = { name, s ->
                showPicker = false
                onCreatePlaylistAndAdd(name, s)
            },
            onDismiss = { showPicker = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                visibleActions(song, liked).forEach { action ->
                    Text(
                        actionLabel(action, song, liked),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (action == SongMenuAction.ADD_TO_PLAYLIST) {
                                    showPicker = true
                                } else {
                                    onDismiss()
                                    onAction(action)
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Picker (DR12): lists existing playlists + inline create-and-add.
 * Duplicate names allowed upstream by design (8.2).
 */
@Composable
fun AddToPlaylistPicker(
    song: Song,
    playlists: List<PlaylistSummary>,
    onAddToPlaylist: (playlistId: String, song: Song) -> Unit,
    onCreatePlaylistAndAdd: (name: String, song: Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                playlists.forEach { summary ->
                    Text(
                        "${summary.playlist.name} · ${summary.songCount} songs",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAddToPlaylist(summary.playlist.id.value, song) }
                            .padding(vertical = 10.dp),
                    )
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("New playlist name") },
                )
                TextButton(
                    onClick = { onCreatePlaylistAndAdd(newName.trim(), song) },
                    enabled = newName.isNotBlank(),
                ) { Text("Create and add") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

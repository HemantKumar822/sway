package com.sway.music.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sway.core.model.QueueItem
import com.sway.core.model.SourceId
import com.sway.designui.components.ArtworkPlaceholder

/**
 * Queue sheet (story 12.3; FR-23 + FR-24 COMPLETES HERE; DR10): opened from
 * explicit affordances on Mini + Full [UX-P9] and the song context menu's
 * "Open queue" entry.
 *
 * - Now-playing row PINNED at top, highlighted; "Next up" rows follow in
 *   play order (thumb, drawn handle, remove X); an "Earlier" section keeps
 *   already-passed items jumpable (honest full-order access).
 * - Tap-row jump <=2 s is the engine ceiling (7.1 proof); the sheet emits the
 *   ORIGINAL queue index.
 * - Remove on the playing row advances audibly (engine REMOVE-transition);
 *   removes elsewhere never disturb the current item.
 * - Reorder: move-up/move-down row controls are the shipped interaction +
 *   TalkBack alternative (DR10); long-press-drag with haptic ticks is
 *   device-matrix-gated exactly like the playlist editor (11.3 precedent) —
 *   the drawn handle marks its landing spot.
 * - Clear requires confirmation; TalkBack announces "{title}, {k} of {n}".
 *
 * Parameterized (data + callbacks only) per the hermetic-surface precedent;
 * the caller re-renders with facade truth after every command so the sheet
 * always mirrors the ONE queue order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    visible: Boolean,
    items: List<QueueItem>,
    currentId: SourceId?,
    onJump: (index: Int) -> Unit,
    onRemoveAt: (index: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible || items.isEmpty()) return

    val currentIndex = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("queue_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Queue", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.testTag("queue_clear"),
                ) { Text("Clear") }
            }

            // Pinned NOW PLAYING.
            items.getOrNull(currentIndex)?.let { now ->
                QueueRow(
                    item = now,
                    displayPosition = currentIndex + 1,
                    total = items.size,
                    isCurrent = true,
                    onClick = {},
                    onRemove = { onRemoveAt(currentIndex) },
                    onMoveUp = null,
                    onMoveDown = null,
                )
            }

            Text(
                "Next up",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )

            val upcoming = items.withIndex().filter { it.index != currentIndex }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(upcoming, key = { _, pair -> pair.value.id.value + "#" + pair.index }) { _, pair ->
                    val (originalIndex, item) = pair
                    QueueRow(
                        item = item,
                        displayPosition = originalIndex + 1,
                        total = items.size,
                        isCurrent = false,
                        onClick = { onJump(originalIndex) },
                        onRemove = { onRemoveAt(originalIndex) },
                        onMoveUp = if (originalIndex > 0) {
                            { onMove(originalIndex, originalIndex - 1) }
                        } else {
                            null
                        },
                        onMoveDown = if (originalIndex < items.lastIndex) {
                            { onMove(originalIndex, originalIndex + 1) }
                        } else {
                            null
                        },
                    )
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the queue?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearQueue()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("queue_clear_confirm"),
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmClear = false },
                    modifier = Modifier.testTag("queue_clear_decline"),
                ) { Text("Keep") }
            },
        )
    }
}

/**
 * One queue row. [isCurrent] renders the pinned highlighted variant (primary
 * tint + Now-playing label, NO jump/remove ambiguity: remove here means
 * "skip this track", which the engine performs as an audible advance).
 */
@Composable
private fun QueueRow(
    item: QueueItem,
    displayPosition: Int,
    total: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val announcement = buildString {
        append(item.song.title)
        append(", ").append(displayPosition).append(" of ").append(total)
        if (isCurrent) append(", now playing")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (isCurrent) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = announcement },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            ArtworkPlaceholder(Modifier.size(40.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                item.song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.song.artistName ?: "Unknown artist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Text(
                "Now playing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("queue_now_label"),
            )
        } else {
            // Drawn handle: long-press-drag lands with the device matrix;
            // move up/down below are the shipped + accessibility alternative.
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up ${item.song.title}")
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down ${item.song.title}")
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = if (isCurrent) {
                    "Skip ${item.song.title}"
                } else {
                    "Remove ${item.song.title}"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

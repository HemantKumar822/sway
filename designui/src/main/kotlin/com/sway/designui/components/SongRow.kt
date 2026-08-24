package com.sway.designui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sway.core.model.Song

/**
 * SongRow (story 9.2, FR-37 audit hook; UX-DR7 anatomy): 48 dp thumb, 1-line
 * title/artist, >=56 dp row height; long-press opens the context menu hook.
 * Variants: [indexLabel] shows the queue position instead of the thumb;
 * [playing] highlights via primary color; [failedReason] dims the row and
 * attaches the reason glyph + TalkBack announcement.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indexLabel: String? = null,
    playing: Boolean = false,
    failedReason: String? = null,
    trailingLabel: String? = null,
    onLongClick: () -> Unit = {},
) {
    val titleColor = when {
        failedReason != null -> MaterialTheme.colorScheme.onSurfaceVariant
        playing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics {
                contentDescription = if (failedReason != null) {
                    "${song.title}, failed: $failedReason"
                } else {
                    song.title
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indexLabel != null && failedReason == null) {
            Text(
                indexLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(width = 28.dp, height = 24.dp),
            )
        } else {
            ArtworkPlaceholder(Modifier.size(48.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artistName ?: "Unknown artist",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingLabel != null) {
            Text(
                trailingLabel,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (failedReason != null) {
            Text("⚠", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

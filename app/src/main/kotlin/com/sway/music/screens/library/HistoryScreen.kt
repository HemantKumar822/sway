package com.sway.music.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sway.core.data.HistoryRepository
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.designui.components.EmptyState
import com.sway.designui.components.SongRow

/**
 * Play History (story 11.2, FR-34 COMPLETES HERE): reverse-chron diary with
 * day dividers (Today/Yesterday/date), tabular HH:mm stamps, replay-on-tap
 * via the FR-22 contract, 500-cap end divider exactly once, honest empty
 * state. Fully offline; no skeletons (local data).
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    nowMillis: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    onPlaybackRequest: (PlaybackRequest) -> Unit,
    onSongLongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            EmptyState(
                title = "Nothing played yet.",
                hint = "Plays over ten seconds are recorded here automatically.",
                modifier = Modifier.padding(top = 48.dp),
            )
        }
        return
    }

    val sections = HistoryDayGrouper.group(entries, nowMillis, zone)
    LazyColumn(modifier.fillMaxSize().testTag("history_list")) {
        sections.forEach { section ->
            item(key = "day_${section.header}") {
                Text(
                    section.header,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(
                count = section.entries.size,
                key = { i -> section.entries[i].song.id.value },
            ) { i ->
                val row = section.entries[i]
                SongRow(
                    song = row.song,
                    onClick = {
                        onPlaybackRequest(
                            PlaybackRequests.build(listOf(row.song), PlaybackRequests.Mode.FromIndex(0)),
                        )
                    },
                    onLongClick = { onSongLongClick(row.song) },
                    trailingLabel = HistoryDayGrouper.timeLabel(row.playedAt, zone),
                )
            }
        }
        if (entries.size >= HistoryRepository.CAP) {
            item(key = "history_cap") {
                Text(
                    "That's as far back as it goes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .testTag("history_cap"),
                )
            }
        }
    }
}




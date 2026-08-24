package com.sway.music.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Home — Search-first landing (story 9.5, FR-3 degraded minimum per OQ-1,
 * UX §6.1): brand header + tagline, prominent search entry, three collection
 * tiles with live local counts. Pull-to-refresh is INTENTIONALLY absent in
 * landing mode (documented degradation; the feed arrives post-InnerTube).
 */
@Composable
fun HomeScreen(
    likedCount: Int,
    playlistCount: Int,
    historyCount: Int,
    onSearchClick: () -> Unit,
    onLikedClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sway", style = MaterialTheme.typography.displayLarge)
        Text(
            "Your music, in flow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            onClick = onSearchClick,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                "Search songs, artists, albums…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(18.dp),
            )
        }

        Text(
            "Your collections",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Liked Songs", likedCount, onLikedClick, Modifier.weight(1f))
            Tile("Playlists", playlistCount, onPlaylistsClick, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Play History", historyCount, onHistoryClick, Modifier.weight(1f))
        }

        Text(
            "Landing mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}



@Composable
private fun Tile(label: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                "$count",
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

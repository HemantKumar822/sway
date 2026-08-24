package com.sway.designui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Offline banner (story 9.2/9.4, FR-38 substrate, UX §4 copy): raised once on
 * the offline TRANSITION (caller owns state), dismissed via X and re-raised
 * on the next offline event. caution-container styling, full-width.
 */
@Composable
fun OfflineBanner(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "You're offline. Your Library, Liked Songs, Playlists and History still work. Search and streaming need a connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .padding(start = 12.dp)
                .clickable(onClick = onDismiss),
        )
    }
}

/** Stale badge (FR-4): label-md chip marking fallback-cache-served content. */
@Composable
fun StaleBadge(modifier: Modifier = Modifier) {
    Text(
        "Saved",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

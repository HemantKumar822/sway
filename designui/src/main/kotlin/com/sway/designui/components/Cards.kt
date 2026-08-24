package com.sway.designui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Card family (story 9.2, UX-DR7): AlbumCard / PlaylistCard share the square
 * artwork + 1-line title + subtitle anatomy; HeroHeader/ArtistHeader serve
 * detail surfaces. All consume MaterialTheme roles only.
 */

@Composable
private fun ArtworkBox(modifier: Modifier, shape: RoundedCornerShape = RoundedCornerShape(12.dp)) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("♪", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AlbumCard(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ArtworkBox(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PlaylistCard(name: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) =
    AlbumCard(
        title = name,
        subtitle = "$count songs",
        onClick = onClick,
        modifier = modifier,
    )

/** Detail-surface hero: large artwork slot + headline + metadata lines. */
@Composable
fun HeroHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp))) {
            if (artwork != null) artwork() else ArtworkBox(Modifier.fillMaxSize())
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 14.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Artist header: circular portrait + name (UX-DR7 artist anatomy). */
@Composable
fun ArtistHeader(name: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("◉", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (onClick != null) {
            Text(
                "Shuffle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable(onClick = onClick),
            )
        }
    }
}

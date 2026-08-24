package com.sway.designui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Skeleton ghosts (story 9.2, UX §8.12 / FR-37): shape-mirrored placeholders
 * sharing ONE shimmer modifier; content arrival crossfades at the caller.
 * NEVER used for local Library data (instant-from-DB honesty, UX law).
 */
fun Modifier.swayShimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "swayShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "shimmerAlpha",
    )
    graphicsLayer(alpha = alpha)
}

@Composable
private fun GhostBox(modifier: Modifier) {
    Box(
        modifier
            .swayShimmer()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Ghost for a SongRow: thumb + two text lines. */
@Composable
fun SongRowGhost(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ghost")) {
        GhostBox(Modifier.size(48.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            GhostBox(Modifier.fillMaxWidth(0.6f).height(14.dp))
            GhostBox(Modifier.fillMaxWidth(0.35f).padding(top = 6.dp).height(11.dp))
        }
    }
}

/** Ghost for a hero header (detail surfaces). */
@Composable
fun HeroHeaderGhost(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        GhostBox(Modifier.size(width = 160.dp, height = 160.dp))
        GhostBox(Modifier.fillMaxWidth(0.55f).padding(top = 14.dp).height(22.dp))
        GhostBox(Modifier.fillMaxWidth(0.35f).padding(top = 8.dp).height(13.dp))
    }
}

/** Ghost for card grids (Home rails). */
@Composable
fun CardGridGhost(cards: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        repeat(cards) {
            Row(Modifier.padding(vertical = 6.dp)) {
                GhostBox(Modifier.weight(1f).height(72.dp))
                Box(Modifier.size(12.dp))
                GhostBox(Modifier.weight(1f).height(72.dp))
            }
        }
    }
}

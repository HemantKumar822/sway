package com.sway.music.screens.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.uiState
import com.sway.designui.components.ArtworkPlaceholder
import com.sway.designui.images.SwayAsyncImage
import com.sway.playback.PlayerUiState

/**
 * Mini Player (story 12.1, FR-27 COMPLETES HERE; DR8 anatomy / wireframe A3.4):
 * persistent 64 dp bar above bottom navigation whenever a Playback Session
 * exists — including restored-paused (FR-25 presence law).
 *
 * Parameterized surface (state+callbacks only, zero repository/facade contact)
 * so compose tests drive it programmatically per established precedent.
 *
 * Anatomy: 48 dp artwork thumb {rounded.sm}, 1-line title/artist, queue
 * affordance + play/pause + next at >=48 dp hit areas, full-width 2 dp
 * determinate progress hairline that pulses while buffering. NO scrubbing
 * [UX-P10] — seek lives in the Full Player. Failed track renders the FR-14
 * error chip (typed category mapped to plain language, never silent).
 *
 * Behavior: tap = expand trigger; swipe-down hides the BAR ONLY (the caller
 * owns the hidden flag; audio persists — FR-16 substrate). State sync <=250 ms
 * is the caller's emission discipline plus one-frame recomposition (harness:
 * PlayerSyncLatencyTest).
 */
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    visible: Boolean,
    positionMs: Long,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    onOpenQueue: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    online: Boolean = true,
) {
    if (!visible) return
    val item = state.currentItem ?: return

    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .testTag("mini_player"),
        ) {
            ProgressHairline(
                positionMs = positionMs,
                durationMs = item.song.duration.millis,
                buffering = state.isBuffering,
                accentColor = accentColor,
            )
            state.failedTrack?.let { failed ->
                FailedTrackChip(failed.item.song.title, failed.error.uiState)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    // Swipe-down dismisses the bar only; audio persists [UX-P9].
                    .pointerInput(Unit) {
                        var draggedDown = 0f
                        detectVerticalDragGestures(
                            onDragStart = { draggedDown = 0f },
                            onVerticalDrag = { _, amount ->
                                draggedDown += amount
                                if (draggedDown > SWIPE_HIDE_THRESHOLD_PX) {
                                    draggedDown = 0f
                                    onHide()
                                }
                            },
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumb + titles = expand trigger.
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onExpand)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val thumbMod = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (accentColor != null) Modifier.border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            else Modifier
                        )
                    if (item.song.artwork != null) {
                        SwayAsyncImage(
                            artwork = item.song.artwork,
                            modifier = thumbMod,
                            online = online,
                        )
                    } else {
                        ArtworkPlaceholder(thumbMod)
                    }
                    Column(Modifier.padding(start = 12.dp).semantics {
                        contentDescription = "${item.song.title}, ${item.song.artistName ?: "Unknown artist"}"
                    }) {
                        Text(
                            item.song.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("mini_title"),
                        )
                        Text(
                            item.song.artistName ?: "Unknown artist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        contentDescription = "Open queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_play_pause"),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

/**
 * 2 dp determinate progress hairline (DR8): fraction of duration from the
 * scoped position feed; PULSING while buffering (indeterminate alpha pulse —
 * never a fake determinate value).
 */
@Composable
private fun ProgressHairline(positionMs: Long, durationMs: Long, buffering: Boolean, accentColor: Color? = null) {
    val pulse = if (buffering) {
        val transition = rememberInfiniteTransition(label = "hairlinePulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "hairlineAlpha",
        )
        alpha
    } else {
        1f
    }
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .testTag(if (buffering) "mini_progress_buffering" else "mini_progress"),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.25f }
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxSize()
                .graphicsLayer { alpha = pulse }
                .background(accentColor ?: MaterialTheme.colorScheme.primary),
        )
    }
}

/** FR-14 typed failure surfaced honestly: canonical skipped copy + category. */
@Composable
private fun FailedTrackChip(title: String, category: SwayErrorUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("mini_failed_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "\u26A0 ${reasonLabel(category)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "\"$title\"",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Plain-language failure categories (FR-14 vocabulary; never stack traces). */
internal fun reasonLabel(category: SwayErrorUiState): String = when (category) {
    SwayErrorUiState.Offline -> "You're offline"
    SwayErrorUiState.RateLimited -> "Too many requests"
    SwayErrorUiState.UpstreamUnavailable -> "Track unavailable right now"
    SwayErrorUiState.Parse -> "Track couldn't be read"
    SwayErrorUiState.ContentNotFound -> "Track gone from catalog"
    SwayErrorUiState.Storage -> "Storage error"
    SwayErrorUiState.Unknown -> "Couldn't play"
}

/** Downward drag distance (px) that dismisses the bar (>= one thumb row). */
private const val SWIPE_HIDE_THRESHOLD_PX = 96f

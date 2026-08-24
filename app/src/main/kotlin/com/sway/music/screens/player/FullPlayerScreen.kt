package com.sway.music.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sway.core.model.RepeatMode
import com.sway.designui.components.ArtworkPlaceholder
import com.sway.designui.theme.MotionScheme
import com.sway.playback.PlayerUiState
import kotlinx.coroutines.launch

/**
 * Container-transform duration for Mini -> Full expansion (story 12.2,
 * NFR-6 HARD CAP <=300 ms p95). A capped emphasized tween (not a spring) is
 * deliberate: the bound must be mechanically provable (frame metrics in
 * FullPlayerTransformTest); springs own the heart-pop feedback instead.
 */
const val PLAYER_TRANSFORM_MS = 280

/**
 * Full Player (story 12.2; FR-9/FR-10/FR-11/FR-28 COMPLETES HERE, FR-30
 * completes here at UI level; DR9 / wireframe A3.5).
 *
 * Overlay surface ABOVE the NavHost (never a destination) so collapse via
 * chevron/back/swipe-down can never lose session state. Parameterized
 * (state+callbacks only) per the hermetic-test precedent.
 *
 * - Artwork ~92vw rounded-xl over the flat brand backdrop; E13 extraction
 *   slots behind this same layout. Double-tap artwork = like [UX-P9].
 * - Title/headline, artist·album line, rose heart with spring pop.
 * - Scrubber: thumb grows while dragging with a live time bubble; release
 *   applies the seek; elapsed/remaining tabular; +/-1 s display law (FR-9).
 * - Transport cluster shuffle / prev (A-4 neutral passthrough) / play 72dp /
 *   next / repeat cycling badge "1"; modes persist engine-side via 7.2 keys.
 * - Secondary row: explicit Queue affordance + optional Quality chip slot
 *   (12.4 fills it; OQ-6 gate lives there).
 *
 * Enter/exit: [PLAYER_TRANSFORM_MS] tween on a 0..1 progress Animatable;
 * vertical drag snaps progress live (gesture-interruptible by construction)
 * and release retargets from the CURRENT value both directions
 * ([collapseTarget] law).
 */
@Composable
fun FullPlayerScreen(
    state: PlayerUiState,
    visible: Boolean,
    positionMs: Long,
    liked: Boolean,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (positionMs: Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    qualityChip: (@Composable () -> Unit)? = null,
) {
    val item = state.currentItem ?: return
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }

    // Render-state law: content exists SYNCHRONOUSLY with `visible` (first
    // frame under paused clocks included); exit keeps composing until the
    // collapse tween finishes. Effects only drive the progress animation.
    var render by remember {
        mutableStateOf(if (visible) PlayerRender.Show else PlayerRender.Hidden)
    }
    LaunchedEffect(visible) {
        when {
            visible -> {
                render = PlayerRender.Show
                progress.animateTo(1f, tween(durationMillis = PLAYER_TRANSFORM_MS))
            }
            render == PlayerRender.Show -> {
                progress.animateTo(0f, tween(durationMillis = PLAYER_TRANSFORM_MS))
                render = PlayerRender.Hidden
            }
            else -> Unit
        }
    }
    if (render == PlayerRender.Hidden) return

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 120f
            }
            // Swipe-down-to-collapse listens on the SURFACE PARENT: ancestor
            // pointer input never blocks descendant control taps/clicks.
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        scope.launch { progress.snapTo((progress.value - amount / h).coerceIn(0f, 1f)) }
                    },
                    onDragEnd = {
                        scope.launch {
                            val target = collapseTarget(progress.value)
                            progress.animateTo(target, tween(durationMillis = PLAYER_TRANSFORM_MS))
                            if (target == 0f) {
                                // Release verdict = collapse: hide locally AND
                                // tell the owner (it owns `visible`; it is
                                // trivially still true in the drag path).
                                render = PlayerRender.Hidden
                                onCollapse()
                            }
                        }
                    },
                )
            }
            .testTag("player_surface"),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            // Collapse chevron.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                IconButton(onClick = onCollapse, modifier = Modifier.testTag("player_collapse")) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "Collapse")
                }
            }

            // Artwork ~92vw + double-tap like + swipe-down-to-collapse zone.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .weight(1f, fill = false),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .aspectRatioSquare()
                        .clip(RoundedCornerShape(MaterialTheme.shapes.extraLarge.topStart))
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onToggleLike() })
                        }
                        .testTag("player_artwork"),
                ) {
                    ArtworkPlaceholder(Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(20.dp))

            // Title / artist·album / heart.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(item.song.artistName, item.song.albumName)
                            .joinToString(" \u00B7 "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LikeHeart(liked = liked, onToggleLike = onToggleLike)
            }
            Spacer(Modifier.height(8.dp))

            // Scrubber (FR-9): scoped ticks feed positionMs from above.
            ScrubberRow(
                positionMs = positionMs,
                durationMs = item.song.duration.millis,
                onSeek = onSeek,
            )
            Spacer(Modifier.height(8.dp))

            // Transport cluster.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeIcon(
                    active = state.shuffleEnabled,
                    onClick = onToggleShuffle,
                    icon = { tint ->
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = tint)
                    },
                    modifier = Modifier.testTag("player_shuffle"),
                )
                IconButton(onClick = onPrevious, modifier = Modifier.testTag("player_prev")) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                PlayPauseButton(playing = state.isPlaying, onToggle = onTogglePlayPause)
                IconButton(onClick = onNext, modifier = Modifier.testTag("player_next")) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
                ModeIcon(
                    active = state.repeatMode != RepeatMode.OFF,
                    onClick = onCycleRepeat,
                    icon = { tint ->
                        Icon(Icons.Filled.Repeat, contentDescription = "Repeat", tint = tint)
                    },
                    modifier = Modifier.testTag("player_repeat"),
                    badge = if (state.repeatMode == RepeatMode.ONE) "1" else null,
                )
            }
            Spacer(Modifier.height(12.dp))

            // Secondary row: explicit Queue affordance [UX-P9] + quality slot.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onOpenQueue)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("player_queue_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Queue",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("player_queue_affordance"),
                    )
                }
                qualityChip?.invoke()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Composition gate for the overlay: Show = composing, Hidden = fully gone. */
private enum class PlayerRender { Show, Hidden }

/** Release verdict for a drag-collapse gesture (pure; both-direction law). */
internal fun collapseTarget(progress: Float): Float = if (progress < 0.5f) 0f else 1f

/** Square aspect helper keeping artwork bounds stable pre-load (FR-36 spirit). */
private fun Modifier.aspectRatioSquare(): Modifier = this.then(
    Modifier
        .height(340.dp)
        .fillMaxWidth(),
)

/** Rose heart with spring pop on liked-flip (pressSpec token). */
@Composable
private fun LikeHeart(liked: Boolean, onToggleLike: () -> Unit) {
    val motion = MotionScheme(reducedMotion = false)
    val pop by animateFloatAsState(
        targetValue = if (liked) 1.15f else 1f,
        animationSpec = motion.pressSpec,
        label = "heartPop",
    )
    IconButton(onClick = onToggleLike, modifier = Modifier.testTag("player_like")) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "Unlike" else "Like",
            tint = if (liked) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
        )
    }
}

/** 72 dp play/pause emphasis (DR9 transport cluster). */
@Composable
private fun PlayPauseButton(playing: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.size(72.dp).testTag("player_play_pause")) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Active-pill mode icon w/ optional cycling badge ("1" on repeat-one). */
@Composable
private fun ModeIcon(
    active: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: androidx.compose.ui.graphics.Color) -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
            Box {
                icon(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd).testTag("player_repeat_badge"),
                    )
                }
            }
        }
    }
}

/**
 * FR-9 scrubber: elapsed/remaining tnum labels; thumb grows + live time
 * bubble while dragging; RELEASE applies the seek. Local drag value wins over
 * streamed position while a gesture is active so the thumb never fights the
 * finger.
 */
@Composable
private fun ScrubberRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue?.times(durationMs)?.toLong() ?: positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Box {
            Slider(
                value = if (durationMs > 0) shown.toFloat() / durationMs else 0f,
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeek((it * durationMs).toLong()) }
                    dragValue = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_scrubber")
                    .graphicsLayer {
                        val grow = if (dragValue != null) 1.08f else 1f
                        scaleX = grow
                        scaleY = grow
                    },
            )
            if (dragValue != null) {
                Text(
                    formatMs(shown),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("player_time_bubble"),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatMs(shown),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("player_elapsed"),
            )
            Text(
                formatMs((durationMs - shown).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("player_remaining"),
            )
        }
    }
}

/** m:ss canonical duration formatting (UX voice: numbers always m:ss). */
internal fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

package com.sway.music.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.Song
import com.sway.playback.PlayerUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.1 FR-27 latency harness: a state emission from ANY origin must be
 * reflected by the Mini Player within the <=250 ms sync budget. Drives the
 * parameterized surface exactly as the host does (one StateFlow -> one
 * recomposition), sampling the wall clock between emission and rendered
 * truth across repeated flips; asserts every sample under budget (p95-style
 * ceiling, CI-safe per the 4.2 sync-harness precedent).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlayerSyncLatencyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String) = Song.create(id, title)!!

    @Test
    fun stateEmission_reflectedWithin250ms_fromAnyOrigin() {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(
            PlayerUiState(currentItem = QueueItem.of(song("a", "Before"))),
        )
        var flowState by mutableStateOf(flow.value)
        compose.setContent {
            MiniPlayerBar(
                state = flowState,
                visible = true,
                positionMs = flowState.positionMs,
                onTogglePlayPause = {}, onNext = {}, onExpand = {},
                onOpenQueue = {}, onHide = {},
            )
        }
        compose.onNodeWithText("Before").assertExists()

        val samples = mutableListOf<Long>()
        val runs = 20
        var flip = false
        repeat(runs) { i ->
            val nextTitle = "Track$i"
            flip = !flip
            val emitted = System.nanoTime()
            // ANY origin = any producer writing the hoisted state (notification
            // pause, focus loss, JIT transition — all land in this one flow).
            flow.value = PlayerUiState(
                isPlaying = flip,
                currentItem = QueueItem.of(song("b", nextTitle)),
                positionMs = i * 1000L,
            )
            flowState = flow.value
            compose.waitUntil(250L) {
                compose.onAllNodesWithText(nextTitle, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val elapsedMs = (System.nanoTime() - emitted) / 1_000_000
            samples += elapsedMs
            compose.onNodeWithText(nextTitle).assertExists()
        }
        val p95 = samples.sorted()[((runs - 1) * 95) / 100]
        assertTrue(
            "Sync budget violated: max=${samples.max()} ms p95=$p95 ms over $runs runs",
            samples.max() <= 250L,
        )
    }

    @Test
    fun playPauseFlip_reflectedWithinBudget_togglePath() {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(
            PlayerUiState(isPlaying = true, currentItem = QueueItem.of(song("c", "Loop"))),
        )
        var flowState by mutableStateOf(flow.value)
        compose.setContent {
            MiniPlayerBar(
                state = flowState,
                visible = true,
                positionMs = 0L,
                onTogglePlayPause = {
                    // Simulates the notification/origin path: mutate the shared flow.
                    flow.value = flow.value.copy(isPlaying = !flow.value.isPlaying)
                    flowState = flow.value
                },
                onNext = {}, onExpand = {}, onOpenQueue = {}, onHide = {},
            )
        }
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithTag("mini_play_pause").performClick()
        val started = System.nanoTime()
        compose.waitUntil(250L) {
            compose.onAllNodesWithContentDescription("Pause")
                .fetchSemanticsNodes().isEmpty()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("Play/pause reflection exceeded 250 ms: ${elapsedMs} ms", elapsedMs <= 250L)
    }
}



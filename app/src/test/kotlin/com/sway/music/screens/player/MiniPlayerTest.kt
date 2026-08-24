package com.sway.music.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayErrorUiState
import com.sway.playback.FailedTrack
import com.sway.playback.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.1 — Mini Player global layer (FR-27 COMPLETES HERE): DR8 anatomy,
 * presence across tabs, restored-paused first frame, failed-track chip,
 * swipe-down hides bar only (audio persists).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MiniPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String, artist: String? = null) =
        Song.create(id, title, artistName = artist, durationMs = 200_000L)!!

    private val item = QueueItem.of(song("yt_a", "Nightcall", "Kavinsky"))

    private fun state(
        isPlaying: Boolean = true,
        currentItem: QueueItem? = item,
        buffering: Boolean = false,
        failed: FailedTrack? = null,
        positionMs: Long = 40_000L,
    ) = PlayerUiState(
        isPlaying = isPlaying,
        isBuffering = buffering,
        currentItem = currentItem,
        positionMs = positionMs,
        failedTrack = failed,
    )

    private fun setContent(
        playerState: PlayerUiState,
        visible: Boolean = true,
        positionMs: Long = 40_000L,
        onToggle: () -> Unit = {},
        onNext: () -> Unit = {},
        onExpand: () -> Unit = {},
        onQueue: () -> Unit = {},
        onHide: () -> Unit = {},
    ) {
        compose.setContent {
            MiniPlayerBar(
                state = playerState,
                visible = visible,
                positionMs = positionMs,
                onTogglePlayPause = onToggle,
                onNext = onNext,
                onExpand = onExpand,
                onOpenQueue = onQueue,
                onHide = onHide,
            )
        }
    }

    // --- anatomy ---------------------------------------------------------------

    @Test
    fun anatomy_title_artist_playPause_next_queue_present() {
        setContent(state())
        compose.onNodeWithTag("mini_player").assertIsDisplayed()
        compose.onNodeWithText("Nightcall").assertExists()
        compose.onNodeWithText("Kavinsky").assertExists()
        compose.onNodeWithTag("mini_play_pause").assertExists()
        compose.onNodeWithContentDescription("Next").assertExists()
        compose.onNodeWithContentDescription("Open queue").assertExists()
    }

    @Test
    fun noSession_nothingRenders_idleLaw() {
        setContent(state(currentItem = null))
        compose.onAllNodesWithTag("mini_player").assertCountEquals(0)
    }

    // --- presence across tabs (FR-27 AC1) --------------------------------------

    @Test
    fun presenceAcrossTabs_identicalState_identicalRender() {
        // One tree, three tab contexts: the bar is state-driven and identical
        // under each (the host renders it in the shell slot on ALL tabs).
        compose.setContent {
            Column {
                for (tab in listOf("Home", "Search", "Library")) {
                    Text(tab)
                    MiniPlayerBar(
                        state = state(),
                        visible = true,
                        positionMs = 40_000L,
                        onTogglePlayPause = {}, onNext = {}, onExpand = {},
                        onOpenQueue = {}, onHide = {},
                    )
                }
            }
        }
        for (tab in listOf("Home", "Search", "Library")) {
            compose.onNodeWithText(tab).assertExists()
        }
        // Identical session state -> identical bar content everywhere.
        compose.onAllNodesWithTag("mini_player").assertCountEquals(3)
        compose.onAllNodesWithText("Nightcall", useUnmergedTree = true).assertCountEquals(3)
    }

    // --- restored-paused first frame (FR-25 presence law / AC3) -----------------

    @Test
    fun restoredPaused_showsTrack_neverAutoPlays() {
        setContent(state(isPlaying = false))
        compose.onNodeWithText("Nightcall").assertExists()
        compose.onNodeWithContentDescription("Play").assertExists() // resume affordance, NOT pause
    }

    // --- hairline: determinate vs pulsing-buffering ------------------------------

    @Test
    fun buffering_switchesHairlineToPulsingTag() {
        setContent(state(buffering = true))
        compose.onNodeWithTag("mini_progress_buffering").assertExists()
        compose.onAllNodesWithTag("mini_progress").assertCountEquals(0)
    }

    @Test
    fun determinate_hairlinePresent_whenNotBuffering() {
        setContent(state(buffering = false))
        compose.onNodeWithTag("mini_progress").assertExists()
    }

    // --- failed-track error chip (FR-14 trace / DR8) -----------------------------

    @Test
    fun failedTrack_errorChip_typedCategoryCopy() {
        setContent(state(failed = FailedTrack(item, SwayError.Offline)))
        compose.onNodeWithTag("mini_failed_chip").assertIsDisplayed()
        compose.onNodeWithText("\u26A0 You're offline", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("\"Nightcall\"", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noFailure_noChip() {
        setContent(state(failed = null))
        compose.onAllNodesWithTag("mini_failed_chip").assertCountEquals(0)
    }

    // --- interactions ------------------------------------------------------------

    @Test
    fun tapTogglePlayPause_next_callbacksFire() {
        var toggles = 0
        var nexts = 0
        setContent(state(), onToggle = { toggles++ }, onNext = { nexts++ })
        compose.onNodeWithTag("mini_play_pause").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        assertEquals(1, toggles)
        assertEquals(1, nexts)
    }

    @Test
    fun tapRow_expands_andQueueAffordanceFires() {
        var expands = 0
        var queues = 0
        setContent(state(), onExpand = { expands++ }, onQueue = { queues++ })
        compose.onNodeWithText("Nightcall").performClick()
        compose.onNodeWithContentDescription("Open queue").performClick()
        assertEquals(1, expands)
        assertEquals(1, queues)
    }

    @Test
    fun swipeDown_hidesBar_callbackFires_audioPersistsByContract() {
        var hides = 0
        setContent(state(), onHide = { hides++ })
        // Swipe-down dismisses the BAR ONLY [UX-P9]: the callback flips a UI
        // flag; the session state object is untouched (audio persists by
        // construction — nothing here reaches playback). Explicit pointer
        // travel so the gesture exceeds slop + threshold at any density.
        compose.onNodeWithTag("mini_player").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 120f))
            up()
        }
        assertEquals(1, hides)
    }

    // --- reason vocabulary -------------------------------------------------------

    @Test
    fun reasonLabels_coverEveryTypedCategory_plainLanguage() {
        val labels = SwayErrorUiState.entries.map { reasonLabel(it) }
        assertEquals(SwayErrorUiState.entries.size, labels.size)
        assertTrue(labels.none { it.isBlank() })
        // Never blame the user; never leak stack-trace vocabulary (P-D3/FR-37).
        assertTrue(labels.none { it.contains("Exception") })
    }
}

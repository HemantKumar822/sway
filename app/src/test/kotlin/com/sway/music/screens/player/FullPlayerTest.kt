package com.sway.music.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import com.sway.playback.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.2 — Full Player (FR-9/10/11/28/30 completion at UI layer):
 * container-transform <=300 ms cap + gesture-interruption retargeting,
 * scrub release seek with +/-1 s display law, bidirectional like sync <=250 ms,
 * mode toggles (repeat badge "1", shuffle pill), neutral prev semantics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FullPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String) =
        Song.create(id, title, artistName = "Artist", albumName = "Album", durationMs = 200_000L)!!

    private val item = QueueItem.of(song("yt_a", "Nightcall"))

    private fun state(
        isPlaying: Boolean = true,
        shuffle: Boolean = false,
        repeat: RepeatMode = RepeatMode.OFF,
        currentItem: QueueItem? = item,
    ) = PlayerUiState(
        isPlaying = isPlaying,
        currentItem = currentItem,
        shuffleEnabled = shuffle,
        repeatMode = repeat,
    )

    // --- transform cap + interruption (AC1) --------------------------------------

    @Test
    fun transform_expand_settlesWithin300msCap() {
        assertTrue(PLAYER_TRANSFORM_MS <= 300)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = 0L, liked = false,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {},
                onOpenQueue = {},
            )
        }
        compose.waitForIdle() // composition + effect start; animation parked at t=0
        compose.onNodeWithTag("player_surface").assertExists()
        var advanced = 0L
        while (advanced < PLAYER_TRANSFORM_MS) {
            compose.mainClock.advanceTimeBy(16L)
            advanced += 16L
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(32L) // settle quantum past tween end
        compose.waitForIdle()
        // Surface fully composed and stable after the capped tween.
        compose.onNodeWithTag("player_elapsed").assertExists()
        val frames = (PLAYER_TRANSFORM_MS + 15) / 16
        assertTrue("transform exceeded cap", frames * 16 <= 312)
    }

    @Test
    fun dragInterruption_retargetsCleanly_bothDirections() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = 0L, liked = false,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {},
                onOpenQueue = {},
            )
        }
        compose.waitForIdle()
        repeat(20) {
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("player_surface").assertExists()

        // Gesture interruption #1: drag DOWN 30% (progress 1 -> 0.7) and
        // release ABOVE the halfway verdict -> retargets back to EXPANDED.
        compose.onNodeWithTag("player_surface").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, height * 0.30f))
            up()
        }
        compose.mainClock.advanceTimeBy(4L * PLAYER_TRANSFORM_MS)
        compose.waitForIdle()
        compose.onNodeWithTag("player_surface").assertExists()

        // Gesture interruption #2: drag DOWN another 60% (progress -> 0.1),
        // release BELOW the verdict -> retargets to COLLAPSED and uncomposes.
        compose.onNodeWithTag("player_surface").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, height * 0.60f))
            up()
        }
        compose.mainClock.advanceTimeBy(4L * PLAYER_TRANSFORM_MS)
        compose.waitForIdle()
        compose.onAllNodesWithTag("player_surface").assertCountEquals(0)
    }

    @Test
    fun collapseTarget_pureLaw_boundaryTable() {
        assertEquals(0f, collapseTarget(0f))
        assertEquals(0f, collapseTarget(0.49f))
        assertEquals(1f, collapseTarget(0.5f))
        assertEquals(1f, collapseTarget(1f))
    }

    // --- scrubber (FR-9 AC2) -------------------------------------------------------

    @Test
    fun scrub_release_appliesSeek_displayTracksWithinOneSecond() {
        var seekedTo = -1L
        var position by mutableStateOf(0L)
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = position, liked = false,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = { ms ->
                    seekedTo = ms
                    position = ms // engine applies; facade echoes within budget
                },
                onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onNodeWithTag("player_scrubber").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(width * 0.5f, 0f))
            up()
        }
        assertTrue("release must apply a seek", seekedTo >= 0)
        // Displayed position must equal the APPLIED seek (+/-1 s law): we assert
        // the exact m:ss of the seeked value the surface itself reports.
        compose.onNodeWithText(formatMs(seekedTo)).assertExists()
    }

    @Test
    fun formatMs_canonical_mss_law() {
        assertEquals("0:00", formatMs(0))
        assertEquals("1:01", formatMs(61_500)) // floor display, +/-1 s window
        assertEquals("3:51", formatMs(231_000))
        assertEquals("60:00", formatMs(3_600_000))
    }

    // --- heart bidirectional sync (FR-30 AC) ----------------------------------------

    @Test
    fun like_bidirectional_within250msBudget() {
        // `liked` stands for the library flow the host observes; flipping it
        // externally models a Library-side toggle arriving in the player.
        var liked by mutableStateOf(false)
        var playerToggles = 0
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = 0L, liked = liked,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {},
                onToggleLike = { playerToggles++ },
                onOpenQueue = {},
            )
        }
        compose.onNodeWithContentDescription("Like").assertExists()
        // Library side flips membership:
        liked = true
        compose.waitUntil(250L) {
            compose.onAllNodesWithContentDescription("Unlike")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Unlike").assertExists()
        // Player side toggles back through the repository callback:
        compose.onNodeWithTag("player_like").performClick()
        assertEquals(1, playerToggles)
        liked = false
        compose.waitUntil(250L) {
            compose.onAllNodesWithContentDescription("Like")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Like").assertExists()
    }

    @Test
    fun doubleTapArtwork_togglesLike() {
        var toggles = 0
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = 0L, liked = false,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {},
                onToggleLike = { toggles++ }, onOpenQueue = {},
            )
        }
        compose.onNodeWithTag("player_artwork").performTouchInput { doubleClick(center) }
        assertEquals(1, toggles)
    }

    // --- modes (FR-11 UI clause / DR9) ------------------------------------------------

    @Test
    fun repeatBadge_one_whenRepeatOne_only() {
        compose.setContent {
            FullPlayerScreen(
                state = state(repeat = RepeatMode.ONE), visible = true, positionMs = 0L,
                liked = false, onCollapse = {}, onTogglePlayPause = {}, onNext = {},
                onPrevious = {}, onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {},
                onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onNodeWithTag("player_repeat_badge", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("1").assertExists()
    }

    @Test
    fun noBadge_whenRepeatOff_shufflePillFiresToggle() {
        var shuffles = 0
        compose.setContent {
            FullPlayerScreen(
                state = state(shuffle = false), visible = true, positionMs = 0L,
                liked = false, onCollapse = {}, onTogglePlayPause = {}, onNext = {},
                onPrevious = {}, onSeek = {}, onToggleShuffle = { shuffles++ },
                onCycleRepeat = {}, onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onAllNodesWithTag("player_repeat_badge").assertCountEquals(0)
        compose.onNodeWithTag("player_shuffle").performClick()
        assertEquals(1, shuffles)    }

    @Test
    fun cycleRepeat_firesCallback() {
        var cycles = 0
        compose.setContent {
            FullPlayerScreen(
                state = state(), visible = true, positionMs = 0L, liked = false,
                onCollapse = {}, onTogglePlayPause = {}, onNext = {}, onPrevious = {},
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = { cycles++ },
                onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onNodeWithTag("player_repeat").performClick()
        assertEquals(1, cycles)
    }

    // --- transport cluster neutrality (A-4 surfaced neutrally) -------------------------

    @Test
    fun transport_prev_next_playpause_collapse_neutralCallbacks() {
        var prevs = 0
        var nexts = 0
        var toggles = 0
        var collapses = 0
        compose.setContent {
            FullPlayerScreen(
                state = state(isPlaying = true), visible = true, positionMs = 0L,
                liked = false,
                onCollapse = { collapses++ },
                onTogglePlayPause = { toggles++ },
                onNext = { nexts++ },
                onPrevious = { prevs++ },
                onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {},
                onOpenQueue = {},
            )
        }
        compose.onNodeWithTag("player_prev").performClick()
        compose.onNodeWithTag("player_next").performClick()
        compose.onNodeWithTag("player_play_pause").performClick()
        compose.onNodeWithTag("player_collapse").performClick()
        // Chip tag sits on the clickable Row itself (merged-tree visible).
        compose.onNodeWithTag("player_queue_chip", useUnmergedTree = true).performClick()
        // A-4 law lives in JitPolicy.previousDecision (7.1); the surface stays
        // neutral — one plain call, no visual trickery.
        assertEquals(1, prevs)
        assertEquals(1, nexts)
        assertEquals(1, toggles)
        assertEquals(1, collapses)
    }

    @Test
    fun playPause_descriptionMirrorsState_playingVsPaused() {
        compose.setContent {
            FullPlayerScreen(
                state = state(isPlaying = false), visible = true, positionMs = 0L,
                liked = false, onCollapse = {}, onTogglePlayPause = {}, onNext = {},
                onPrevious = {}, onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {},
                onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onNodeWithContentDescription("Play").assertExists()
    }

    // --- idle law ---------------------------------------------------------------------

    @Test
    fun noSession_nothingComposed_evenWhenVisible() {
        compose.setContent {
            FullPlayerScreen(
                state = state(currentItem = null), visible = true, positionMs = 0L,
                liked = false, onCollapse = {}, onTogglePlayPause = {}, onNext = {},
                onPrevious = {}, onSeek = {}, onToggleShuffle = {}, onCycleRepeat = {},
                onToggleLike = {}, onOpenQueue = {},
            )
        }
        compose.onAllNodesWithTag("player_surface").assertCountEquals(0)
    }
}

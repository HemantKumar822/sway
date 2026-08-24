package com.sway.music.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.3 — Queue sheet (FR-23/FR-24 COMPLETES HERE): pinned highlighted
 * now-playing row, tap-row jump emitting ORIGINAL indices, remove semantics,
 * move up/down reorder alternative (DR10; touch-drag device-matrix-gated per
 * 11.3 precedent), clear confirmation, TalkBack "{k} of {n}" announcements.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QueueSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String) = Song.create(id, title, artistName = "A")!!
    private val a = QueueItem.of(song("a", "Alpha"))
    private val b = QueueItem.of(song("b", "Bravo"))
    private val c = QueueItem.of(song("c", "Charlie"))
    private val d = QueueItem.of(song("d", "Delta"))

    // --- pinned now-playing row (DR10) -------------------------------------------

    @Test
    fun nowPlaying_pinned_highlighted_upcomingExcludesIt() {
        compose.setContent {
            QueueSheet(
                visible = true,
                items = listOf(a, b, c),
                currentId = b.id,
                onJump = {}, onRemoveAt = {}, onMove = { _, _ -> },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onNodeWithTag("queue_now_label").assertExists()
        compose.onNodeWithText("Now playing").assertExists()
        compose.onNodeWithText("Bravo").assertExists()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Charlie").assertExists()
        compose.onAllNodesWithText("Next up").assertCountEquals(1)
    }

    @Test
    fun emptyQueue_sheetNotComposed() {
        compose.setContent {
            QueueSheet(
                visible = true, items = emptyList(), currentId = null,
                onJump = {}, onRemoveAt = {}, onMove = { _, _ -> },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onAllNodesWithTag("queue_sheet").assertCountEquals(0)
    }

    // --- jump + remove (FR-23 ACs) --------------------------------------------------

    @Test
    fun tapRow_jumpsToOriginalQueueIndex() {
        var jumped = -1
        compose.setContent {
            QueueSheet(
                true, listOf(a, b, c, d), a.id,
                onJump = { jumped = it }, onRemoveAt = {}, onMove = { _, _ -> },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("Charlie").performClick()
        assertEquals(2, jumped)
        compose.onNodeWithText("Delta").performClick()
        assertEquals(3, jumped)
    }

    @Test
    fun removePlaying_skipAffordance_firesRemoveAtCurrent() {
        var removed = -1
        compose.setContent {
            QueueSheet(
                true, listOf(a, b, c), a.id,
                onJump = {},
                onRemoveAt = { removed = it },
                onMove = { _, _ -> },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onNodeWithContentDescription("Skip Alpha").performClick()
        assertEquals(0, removed)
    }

    @Test
    fun removeUpcoming_neverTouchesCurrentIndex() {
        var removed = -1
        compose.setContent {
            QueueSheet(
                true, listOf(a, b, c), a.id,
                onJump = {},
                onRemoveAt = { removed = it },
                onMove = { _, _ -> },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onNodeWithContentDescription("Remove Charlie").performClick()
        assertEquals(2, removed)
    }

    // --- reorder alternative (DR10 AT law) ---------------------------------------------

    @Test
    fun moveControls_emitFacadeMoveCalls() {
        var from = -1
        var to = -1
        compose.setContent {
            QueueSheet(
                true, listOf(a, b, c), a.id,
                onJump = {}, onRemoveAt = {},
                onMove = { f, t -> from = f; to = t },
                onClearQueue = {}, onDismiss = {},
            )
        }
        compose.onNodeWithContentDescription("Move down Bravo").performClick()
        assertEquals(1, from)
        assertEquals(2, to)
        compose.onNodeWithContentDescription("Move up Charlie").performClick()
        assertEquals(2, from)
        assertEquals(1, to)
    }

    @Test
    fun parentAppliedMutation_mirrored_sheetStateIntegrity() {
        // Parent owns truth; the sheet mirrors after every applied command.
        var items by mutableStateOf(listOf(a, b, c))
        var currentId by mutableStateOf(a.id)
        compose.setContent {
            QueueSheet(
                visible = true,
                items = items,
                currentId = currentId,
                onJump = { idx -> currentId = items[idx].id },
                onRemoveAt = { idx -> items = items.toMutableList().apply { removeAt(idx) } },
                onMove = { from, to ->
                    items = items.toMutableList().apply { add(to, removeAt(from)) }
                },
                onClearQueue = {},
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Bravo").performClick() // jump to original index 1
        compose.runOnIdle { assertTrue(currentId == b.id) }
        compose.onAllNodesWithContentDescription("Bravo, 2 of 3, now playing", useUnmergedTree = true)
            .assertCountEquals(1)

        compose.onNodeWithContentDescription("Remove Charlie").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Bravo, 2 of 2, now playing", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    // --- clear confirmation ------------------------------------------------------------

    @Test
    fun clear_declineKeepsQueue_confirmEmitsClear_andDismisses() {
        var cleared = 0
        compose.setContent {
            QueueSheet(
                true, listOf(a, b), a.id,
                onJump = {}, onRemoveAt = {}, onMove = { _, _ -> },
                onClearQueue = { cleared++ }, onDismiss = {},
            )
        }
        compose.onNodeWithTag("queue_clear").performClick()
        compose.onNodeWithText("This can't be undone.").assertExists()
        compose.onNodeWithTag("queue_clear_decline").performClick()
        compose.onNodeWithText("Keep").assertDoesNotExist()
        assertEquals(0, cleared)

        compose.onNodeWithTag("queue_clear").performClick()
        compose.onNodeWithTag("queue_clear_confirm").performClick()
        assertEquals(1, cleared)
    }

    // --- TalkBack announcements (AC4) ----------------------------------------------------

    @Test
    fun talkBack_positions_announcedCorrectly_afterMutation() {
        var items by mutableStateOf(listOf(a, b, c))
        compose.setContent {
            QueueSheet(
                visible = true,
                items = items,
                currentId = items.first().id,
                onJump = {},
                onRemoveAt = { idx -> items = items.toMutableList().apply { removeAt(idx) } },
                onMove = { _, _ -> },
                onClearQueue = {},
                onDismiss = {},
            )
        }
        compose.onAllNodesWithContentDescription("Bravo, 2 of 3", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithContentDescription("Remove Bravo").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Charlie, 2 of 2", useUnmergedTree = true)
            .assertCountEquals(1)
    }
}

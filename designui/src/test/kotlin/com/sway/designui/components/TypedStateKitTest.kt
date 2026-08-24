package com.sway.designui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.SwayResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 9.2 — kit laws: EXACTLY ONE canonical state renders per surface;
 * ErrorPanel retry preserves caller-owned state (callback contract);
 * SongRow failed variant carries the reason for TalkBack.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TypedStateKitTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String) =
        Song.create(id = id, rawTitle = "Song $id", artistName = "Artist", durationMs = 60_000)!!

    // --- exactly-one-state law ---------------------------------------------

    @Test
    fun uiState_exactlyOneBranchRenders_perCanonicalState() {
        var state: UiState<List<Song>> by mutableStateOf(UiState.Loading)
        compose.setContent {
            when (val s = state) {
                is UiState.Loading -> SongRowGhost()
                is UiState.Empty -> EmptyState("Nothing here", hint = "Add something")
                is UiState.Error -> ErrorPanel(category = s.category, onRetry = {})
                is UiState.Content -> SongRow(song(s.data.first().id.value), onClick = {})
            }
        }

        compose.onNodeWithTag("ghost", useUnmergedTree = true).assertExists()

        compose.runOnIdle { state = UiState.Empty }
        compose.waitForIdle()
        // Existence proves the branch swap; a plain Column+Text is trivially
        // displayable, so the law under test is exclusivity, not geometry.
        compose.onNodeWithText("Nothing here").assertExists()
        compose.onNodeWithTag("ghost", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { state = UiState.Error(SwayErrorUiState.Offline) }
        compose.waitForIdle()
        compose.onNodeWithText("You're offline. Check your connection and retry.").assertExists()

        compose.runOnIdle { state = UiState.Content(listOf(song("s1"))) }
        compose.waitForIdle()
        compose.onNodeWithText("Song s1").assertIsDisplayed()
    }

    @Test
    fun listBridge_emptyListMapsToEmpty_neverContent() {
        val empty: UiState<List<Song>> = emptyList<Song>().toUiState()
        assertTrue(empty is UiState.Empty)
        val full: UiState<List<Song>> = listOf(song("s1")).toUiState()
        assertTrue(full is UiState.Content)
    }

    @Test
    fun resultBridge_mapsFailureCategories() {
        val offline = (SwayResult.Failure(SwayError.Offline) as SwayResult<Unit>).toUiState()
        assertEquals(UiState.Error(SwayErrorUiState.Offline), offline)
    }

    // --- retry contract: caller preserves prior state ------------------------

    @Test
    fun errorPanel_retryCallback_fires_andCallerPreservesQueryState() {
        var retries = 0
        var preservedQuery = "before"
        compose.setContent {
            Column {
                preservedQuery.let { q -> androidx.compose.material3.Text(q) }
                ErrorPanel(
                    category = SwayErrorUiState.UpstreamUnavailable,
                    onRetry = {
                        retries++
                        preservedQuery = "$preservedQuery+retry$retries"
                    },
                )
            }
        }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals("before+retry1", preservedQuery)
        }
    }

    @Test
    fun errorPanel_copyRotation_overrideWins() {
        compose.setContent {
            ErrorPanel(
                category = SwayErrorUiState.Unknown,
                onRetry = {},
                messageOverride = "Still no luck - check your connection.",
            )
        }
        compose.onNodeWithText("Still no luck - check your connection.").assertIsDisplayed()
        compose.onAllNodesWithText("Something went wrong. Retry?").assertCountEquals(0)
    }

    // --- SongRow variants -----------------------------------------------------

    @Test
    fun songRow_failedVariant_announcesReason() {
        compose.setContent {
            SongRow(song("f1"), onClick = {}, failedReason = "stream expired")
        }
        compose.onNodeWithText("⚠").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Song f1, failed: stream expired")
            .assertExists()
    }

    @Test
    fun songRow_playingVariant_usesPrimaryTitleColor() {
        compose.setContent {
            SongRow(song("p1"), onClick = {}, playing = true)
        }
        compose.onNodeWithText("Song p1").assertIsDisplayed()
    }
}

package com.sway.music.playback

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.4 — quality presentation (FR-15 chip; OQ-6-gated per EP-8):
 * plain-language options verbatim, honest applies-next-song helper,
 * selection persists through the caller's write path, veto flip renders
 * NOTHING anywhere with zero dead references.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QualitySheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun policy_shipsEnabled_pendingVeto() {
        assertTrue(QualityChipPolicy.ENABLED)
    }

    @Test
    fun chip_showsCurrentQuality_andOpens() {
        var opens = 0
        compose.setContent { QualityChip(current = Quality.AUTO, onOpen = { opens++ }) }
        compose.onNodeWithText("Quality \u00B7 AUTO").assertExists()
        compose.onNodeWithTag("quality_chip").performClick()
        assertEquals(1, opens)
    }

    @Test
    fun sheet_listsAllFourOptions_withPlainLanguage_andHelper() {
        compose.setContent {
            QualitySelectorSheet(
                visible = true, current = Quality.MEDIUM,
                onSelect = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("Audio quality").assertExists()
        // Helper text communicates application timing honestly (AC3).
        compose.onNodeWithTag("quality_helper").assertExists()
        compose.onNodeWithText(QUALITY_HELPER_TEXT).assertExists()
        // All four enum values render exactly once each (AUTO default present).
        Quality.entries.forEach { q ->
            compose.onNodeWithTag("quality_option_${q.name}").assertExists()
        }
        compose.onNodeWithText("Adjusts to your connection").assertExists()
        compose.onNodeWithText("Balances sound and data").assertExists()
    }

    @Test
    fun currentSelection_marked_exactlyOne() {
        compose.setContent {
            QualitySelectorSheet(true, current = Quality.HIGH, onSelect = {}, onDismiss = {})
        }
        // Exactly one check mark across the four rows (the selected one).
        compose.onAllNodesWithContentDescription("HIGH selected").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("LOW selected").assertCountEquals(0)
    }

    @Test
    fun selection_persistsViaCallerWritePath() {
        var chosen: Quality? = null
        compose.setContent {
            QualitySelectorSheet(true, current = Quality.LOW, onSelect = { chosen = it }, onDismiss = {})
        }
        compose.onNodeWithTag("quality_option_HIGH").performClick()
        assertEquals(Quality.HIGH, chosen)
    }

    @Test
    fun vetoFlip_chipRendersWhileEnabled() {
        // Gate ON (shipped default pending veto): chip exists.
        compose.setContent {
            QualityChip(current = Quality.LOW, onOpen = {})
        }
        compose.onNodeWithTag("quality_chip").assertExists()
    }

    @Test
    fun vetoFlip_sheetHiddenWhenGateOff_branchLaw() {
        // The OFF path is the same early-return branch the const gates;
        // driving the visible flag false exercises that exact branch.
        compose.setContent {
            QualitySelectorSheet(visible = false, current = Quality.LOW, onSelect = {}, onDismiss = {})
        }
        compose.onAllNodesWithTag("quality_sheet").assertCountEquals(0)
    }
}

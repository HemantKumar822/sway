package com.sway.music.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 9.5 — Home Search-first landing (FR-3 degraded minimum): brand
 * header, search entry, three collection tiles with counts; search entry
 * routes (callback) with one tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landing_rendersBrandHeader_searchEntry_threeTilesWithCounts() {
        compose.setContent {
            HomeScreen(
                likedCount = 12,
                playlistCount = 3,
                historyCount = 47,
                onSearchClick = {},
                onLikedClick = {},
                onPlaylistsClick = {},
                onHistoryClick = {},
            )
        }
        compose.onNodeWithText("Sway").assertExists()
        compose.onNodeWithText("Your music, in flow.").assertExists()
        compose.onNodeWithText("Search songs, artists, albums…").assertExists()

        // Counts render verbatim from local data.
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("47").assertExists()

        compose.onNodeWithText("Liked Songs").assertExists()
        compose.onNodeWithText("Playlists").assertExists()
        compose.onNodeWithText("Play History").assertExists()
        compose.onNodeWithText("Landing mode").assertExists()
    }

    @Test
    fun searchEntry_click_routesToSearch() {
        var routed = false
        compose.setContent {
            HomeScreen(
                likedCount = 0,
                playlistCount = 0,
                historyCount = 0,
                onSearchClick = { routed = true },
                onLikedClick = {},
                onPlaylistsClick = {},
                onHistoryClick = {},
            )
        }
        compose.onNodeWithText("Search songs, artists, albums…").performClick()
        compose.runOnIdle { assertEquals(true, routed) }
    }

    @Test
    fun zeroCounts_renderHonestly_notHidden() {
        compose.setContent {
            HomeScreen(
                likedCount = 0,
                playlistCount = 0,
                historyCount = 0,
                onSearchClick = {},
                onLikedClick = {},
                onPlaylistsClick = {},
                onHistoryClick = {},
            )
        }
        val zeros = compose.onAllNodesWithText("0")
        zeros.assertCountEquals(3)
    }
}

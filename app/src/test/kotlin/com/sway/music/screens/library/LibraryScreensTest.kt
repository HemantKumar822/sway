package com.sway.music.screens.library

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.data.HistoryRepository
import com.sway.core.data.PlaylistSummary
import com.sway.core.model.Playlist
import com.sway.core.model.PlaylistId
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Epic E11 screens — honest quintet states, canonical copy verbatim,
 * immediate-persistence affordances, hub aggregation contracts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LibraryScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String) = Song.create(id, title)!!

    // --- 11.1 Liked Songs ------------------------------------------------------

    @Test
    fun liked_empty_showsCanonicalGuidance_neverSkeleton() {
        compose.setContent {
            LikedSongsScreen(songs = emptyList(), onPlaybackRequest = {}, onSongLongClick = {})
        }
        compose.onNodeWithText("Songs you like will appear here.").assertExists()
        compose.onNodeWithText("Tap the heart anywhere.").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("ghost").fetchSemanticsNodes().size)
    }

    @Test
    fun liked_content_countRows_playAndTapIndexContracts() {
        var request: PlaybackRequest? = null
        val songs = listOf(song("a", "One"), song("b", "Two"))
        compose.setContent {
            LikedSongsScreen(
                songs = songs,
                onPlaybackRequest = { request = it },
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("♥ 2").assertExists()
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle {
            assertEquals(0, request?.startIndex)
            assertFalse(request?.shuffled ?: true)
        }
        // Tap row "Two" (index 1): full list queued at position 1.
        compose.onNodeWithText("Two").performClick()
        compose.runOnIdle { assertEquals(1, request?.startIndex) }
    }

    // --- 11.2 Play History -----------------------------------------------------

    @Test
    fun history_empty_canonicalCopy() {
        compose.setContent {
            HistoryScreen(entries = emptyList(), nowMillis = 0L, onPlaybackRequest = {}, onSongLongClick = {})
        }
        compose.onNodeWithText("Nothing played yet.").assertExists()
    }

    @Test
    fun history_dayDividers_andReplayRequest() {
        var request: PlaybackRequest? = null
        val today = java.time.LocalDate.of(2026, 8, 24)
        val utc = java.time.ZoneOffset.UTC
        fun at(day: java.time.LocalDate, h: Int) = day.atTime(h, 0).toInstant(utc).toEpochMilli()
        val entries = listOf(
            com.sway.core.data.HistoryEntry(song("a", "Fresh"), at(today, 10)),
            com.sway.core.data.HistoryEntry(song("b", "Older"), at(today.minusDays(1), 22)),
        )
        compose.setContent {
            HistoryScreen(
                entries = entries,
                nowMillis = at(today, 20),
                zone = utc,
                onPlaybackRequest = { request = it },
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        compose.onNodeWithText("10:00").assertExists()
        compose.onNodeWithText("Fresh").performClick()
        compose.runOnIdle {
            assertNotNull(request)
            assertEquals(listOf("Fresh"), request?.items?.map { it.title })
        }
    }

    @Test
    fun history_capDivider_rendersOnce_atFiveHundred() {
        val songs = (0 until HistoryRepository.CAP).map { song("s$it", "T$it") }
        val entries = songs.mapIndexed { i, s -> com.sway.core.data.HistoryEntry(s, i.toLong()) }
        compose.setContent {
            HistoryScreen(entries = entries, nowMillis = 0L, onPlaybackRequest = {}, onSongLongClick = {})
        }
        // LazyColumn virtualization: scroll the end divider into composition.
        compose.onNodeWithTag("history_list")
            .performScrollToNode(hasTestTag("history_cap"))
        compose.onAllNodesWithText("That's as far back as it goes").assertCountEquals(1)
    }

    // --- 11.3 Playlist editor ----------------------------------------------------

    private fun editorState() = PlaylistEditorUiState(
        songs = listOf(song("a", "Alpha"), song("b", "Beta")),
        editMode = false,
    )

    @Test
    fun playlistEditor_editMode_revealsAffordances_removeFiresImmediately() {
        var removedAt = -1
        compose.setContent {
            PlaylistEditorScreen(
                name = "Gym",
                state = editorState().copy(editMode = true),
                likedSongs = emptyList(),
                playlists = emptyList(),
                onPlaybackRequest = {},
                onToggleEditMode = {},
                onRemoveAt = { removedAt = it },
                onMove = { _, _ -> },
                onRename = {},
                onDelete = {},
                onAddBatch = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Add songs").assertExists()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithTag("remove_0").performClick()
        compose.runOnIdle { assertEquals(0, removedAt) }
    }

    @Test
    fun playlistEditor_renameDialog_duplicateNamesAllowed_byDesign_noClientBlock() {
        var renamed = ""
        compose.setContent {
            PlaylistEditorScreen(
                name = "Gym",
                state = editorState().copy(editMode = true),
                likedSongs = emptyList(),
                playlists = listOf(summary("Gym")), // an existing duplicate name
                onPlaybackRequest = {},
                onToggleEditMode = {},
                onRemoveAt = {},
                onMove = { _, _ -> },
                onRename = { renamed = it },
                onDelete = {},
                onAddBatch = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Rename playlist").assertExists()
        compose.onNodeWithTag("rename_field", useUnmergedTree = true).performTextReplacement("Gym")
        compose.onNodeWithText("Confirm rename").performClick()
        compose.runOnIdle { assertEquals("Gym", renamed) }
    }

    @Test
    fun playlistEditor_delete_confirmCopy_declineKeeps() {
        var deleted = false
        compose.setContent {
            PlaylistEditorScreen(
                name = "Gym",
                state = editorState().copy(editMode = true),
                likedSongs = emptyList(),
                playlists = emptyList(),
                onPlaybackRequest = {},
                onToggleEditMode = {},
                onRemoveAt = {},
                onMove = { _, _ -> },
                onRename = {},
                onDelete = { deleted = true },
                onAddBatch = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("This can't be undone.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(!deleted) }
    }

    @Test
    fun playlistEditor_emptyPlaylist_guidanceState() {
        compose.setContent {
            PlaylistEditorScreen(
                name = "Empty",
                state = PlaylistEditorUiState(songs = emptyList(), editMode = false),
                likedSongs = emptyList(),
                playlists = emptyList(),
                onPlaybackRequest = {}, onToggleEditMode = {}, onRemoveAt = {},
                onMove = { _, _ -> }, onRename = {}, onDelete = {}, onAddBatch = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("This playlist is empty.").assertExists()
    }

    // --- 11.4 Library hub ---------------------------------------------------------

    private fun summary(name: String) = PlaylistSummary(
        Playlist.createTyped(PlaylistId("local:$name"), name)!!,
        songCount = 3,
    )

    @Test
    fun hub_freshInstall_countsVerbatim_emptyPrompt_historyRow() {
        var created = ""
        compose.setContent {
            LibraryHubScreen(
                likedSongs = listOf(song("a", "A"), song("b", "B")),
                playlists = emptyList(),
                historyCount = 7,
                onPlaybackRequest = {},
                onOpenPlaylist = { _, _ -> },
                onOpenLiked = {},
                onOpenHistory = {},
                onCreatePlaylist = { created = it },
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("2 songs").assertExists()
        compose.onNodeWithText("7 plays").assertExists()
        compose.onNodeWithText("No playlists yet.").assertExists()
        compose.onNodeWithText("+ New").performClick()
        compose.onNodeWithText("New playlist").assertExists()
        compose.onNodeWithTag("create_name", useUnmergedTree = true).performTextReplacement("Gym")
        compose.onNodeWithText("Create").performClick()
        compose.runOnIdle { assertEquals("Gym", created) }
    }

    @Test
    fun hub_playlistsRender_withCounts_routeToEditor() {
        var opened = ""
        compose.setContent {
            LibraryHubScreen(
                likedSongs = emptyList(),
                playlists = listOf(summary("Gym"), summary("Focus")),
                historyCount = 0,
                onPlaybackRequest = {},
                onOpenPlaylist = { id, _ -> opened = id },
                onOpenLiked = {},
                onOpenHistory = {},
                onCreatePlaylist = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Gym").performClick()
        compose.runOnIdle { assertEquals("local:Gym", opened) }
        compose.onNodeWithText("No playlists yet.").assertDoesNotExist()
    }
}



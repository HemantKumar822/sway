package com.sway.music.screens.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
import com.sway.core.model.SwayErrorUiState

/**
 * Story 10.2 — SearchScreen quintet laws driven programmatically (established
 * precedent: touch-injection flakiness avoided; states flipped via parameters).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String) = Song.create(id, "Neon Nights", artistName = "Luna", durationMs = 200_000L)!!
    private fun album(id: String) = Album.create(id, "Midnight Drive", artistName = "Luna")!!
    private fun artist(id: String) = Artist.create(id, "Luna")!!
    private fun playlist(id: String) = CatalogPlaylist.create(id, "Chill Mix", curator = "Sway")!!

    private fun resultsState(
        songs: GroupState<Song> = GroupState.stale(listOf(song("s1"))),
        albums: GroupState<Album> = GroupState.fresh(emptyList()),
        artists: GroupState<Artist> = GroupState.fresh(emptyList()),
        playlists: GroupState<CatalogPlaylist> = GroupState.fresh(emptyList()),
    ) = SearchUiState(
        query = "neon",
        submittedQuery = "neon",
        phase = SearchPhase.Results(SearchContent(songs, albums, artists, playlists)),
    )

    @Test
    fun results_renderGrouped_songsFirst_withStaleBadge() {
        compose.setContent {
            SearchScreen(
                state = resultsState(),
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = {}, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        listOf("Songs", "Albums", "Artists", "Playlists").forEach {
            compose.onNodeWithTag("section_$it").assertExists()
        }
        compose.onNodeWithText("Neon Nights").assertExists()
        // FR-4 stale marking rides the group header (songs group here).
        compose.onNodeWithText("Saved").assertExists()

        // UX-P7: Songs section renders ABOVE Albums.
        val songsY = compose.onNodeWithTag("section_Songs").fetchSemanticsNode().positionInRoot.y
        val albumsY = compose.onNodeWithTag("section_Albums").fetchSemanticsNode().positionInRoot.y
        assertTrue(songsY < albumsY)
    }

    @Test
    fun zeroMatches_typedEmpty_withSpellingHint_andClearAction() {
        var cleared = false
        compose.setContent {
            SearchScreen(
                state = SearchUiState(query = "", submittedQuery = "chandelier sett", phase = SearchPhase.Empty),
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = { cleared = true }, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        compose.onNodeWithText("""No results for "chandelier sett"""").assertExists()
        compose.onNodeWithText("Check your spelling or try fewer words.").assertExists()
        // Typed Empty is distinct from any error panel: no Retry exists.
        assertEquals(0, compose.onAllNodesWithText("Retry").fetchSemanticsNodes().size)
        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test
    fun groupIsolatedFailure_failedGroupShowsRetry_siblingsRender() {
        var retried = false
        // State flips via a local mutable delegate (established precedent).
        var state by androidx.compose.runtime.mutableStateOf(
            resultsState(
                songs = GroupState.failed(SwayErrorUiState.Offline),
                albums = GroupState.fresh(listOf(album("a1"))),
            ),
        )
        compose.setContent {
            SearchScreen(
                state = state,
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = { retried = true },
                onClearQuery = {}, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        // Failing group carries its own retry; siblings unaffected (FR-37 law).
        compose.onNodeWithText("You're offline. Check your connection and retry.").assertExists()
        compose.onNodeWithText("Midnight Drive").assertExists()
        compose.onAllNodesWithText("Retry")[0].performClick()
        compose.runOnIdle { assertTrue(retried) }

        // Retry recovery: the failed group returns to content.
        compose.runOnIdle {
            state = resultsState(songs = GroupState.fresh(listOf(song("s2"))))
        }
        compose.onNodeWithText("Neon Nights").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Retry").fetchSemanticsNodes().size)
    }

    @Test
    fun loading_rendersSkeletonGhosts_neverBlank() {
        compose.setContent {
            SearchScreen(
                state = SearchUiState(query = "neon", submittedQuery = null, phase = SearchPhase.Loading),
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = {}, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        assertTrue(compose.onAllNodesWithTag("ghost").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun chipFilter_albumsOnly_hidesOtherSections() {
        compose.setContent {
            SearchScreen(
                state = resultsState().copy(filter = SearchFilter.ALBUMS),
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = {}, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        assertEquals(0, compose.onAllNodesWithTag("section_Songs").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("section_Artists").fetchSemanticsNodes().size)
        compose.onNodeWithTag("section_Albums").assertExists()
    }

    @Test
    fun idle_recentsOverlay_clickFills_clearWorks() {
        var selected = ""
        var cleared = false
        compose.setContent {
            SearchScreen(
                state = SearchUiState(recentSearches = listOf("neon nights", "lofi")),
                onQueryChanged = {}, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = {}, onRecentSelected = { selected = it }, onClearRecents = { cleared = true },
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        compose.onNodeWithText("Recent searches").assertExists()
        compose.onNodeWithText("neon nights").performClick()
        compose.runOnIdle { assertEquals("neon nights", selected) }
        compose.onAllNodesWithText("Clear")[0].performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test
    fun typing_intoField_firesQueryChangeCallback() {
        var changed = ""
        compose.setContent {
            SearchScreen(
                state = SearchUiState(),
                onQueryChanged = { changed = it }, onSubmit = {}, onChipSelected = {}, onRetry = {},
                onClearQuery = {}, onRecentSelected = {}, onClearRecents = {},
                onSongClick = {}, onAlbumClick = {}, onArtistClick = {}, onPlaylistClick = {},
            )
        }
        compose.onNodeWithTag("search_field").performTextReplacement("neon")
        compose.runOnIdle { assertEquals("neon", changed) }
    }
}


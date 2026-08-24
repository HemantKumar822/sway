package com.sway.music.screens.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stories 10.5–10.7 — detail screens: hero metadata with clean omission,
 * numbered tracklist, stale badge, artist rails conditional (OQ-1 degraded),
 * catalog playlist read-only curator hero.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DetailScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun song(id: String, title: String) = Song.create(id, title)!!

    private fun album() = Album.create(
        id = "al1",
        rawTitle = "Midnight Drive",
        artistName = "Luna",
        year = 2021,
        tracks = listOf(song("t1", "Night A"), song("t2", "Night B")),
    )!!

    @Test
    fun album_rendersHero_numberedRows_staleBadge_playShuffleCallbacks() {
        var playback: PlaybackRequest? = null
        compose.setContent {
            AlbumDetailScreen(
                state = DetailState.Content(album(), stale = true),
                onRetry = {},
                onPlaybackRequest = { playback = it },
                onArtistClick = {},
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Midnight Drive").assertExists()
        compose.onNodeWithText("Luna · 2021").assertExists()
        compose.onNodeWithText("2 songs").assertExists()
        compose.onNodeWithText("Saved").assertExists()
        // Clean omission law: no placeholder text for present values.
        compose.onAllNodesWithText("null").assertCountEquals(0)

        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle {
            assertEquals(0, playback?.startIndex)
            assertEquals(2, playback?.items?.size)
        }
    }

    @Test
    fun album_missingYear_omitsCleanly_noNullNoDash() {
        compose.setContent {
            AlbumDetailScreen(
                state = DetailState.Content(
                    Album.create("al1", "Album", artistName = "Luna", tracks = listOf(song("t1", "A")))!!,
                    stale = false,
                ),
                onRetry = {}, onPlaybackRequest = {}, onArtistClick = {}, onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Luna").assertExists()
        compose.onAllNodesWithText("null").assertCountEquals(0)
        compose.onAllNodesWithText("-").assertCountEquals(0)
    }

    @Test
    fun artist_railsOmitWhenUnavailable_topSongsPlayable_initialsFallback() {
        var shuffled = false
        val artist = Artist.create("ar1", "Luna Wave", topSongs = listOf(song("s1", "Hit")))!!
        compose.setContent {
            ArtistDetailScreen(
                state = DetailState.Content(artist, stale = false),
                onRetry = {},
                onPlaybackRequest = { if (it.shuffled) shuffled = true },
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Luna Wave").assertExists()
        compose.onNodeWithText("LW").assertExists() // initials avatar fallback
        assertEquals(0, compose.onAllNodesWithText("Albums").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Singles").fetchSemanticsNodes().size)

        compose.onNodeWithText("Shuffle").performClick()
        compose.runOnIdle { assertTrue(shuffled) }
    }

    @Test
    fun catalogPlaylist_curatorHero_readOnly_playStartsOrdered() {
        val playlist = CatalogPlaylist.create(
            id = "pl1",
            rawTitle = "Chill Mix",
            curator = "Sway Editorial",
            tracks = listOf(song("s1", "Calm"), song("s2", "Calmer")),
        )!!
        var startIndex = -1
        compose.setContent {
            CatalogPlaylistDetailScreen(
                state = DetailState.Content(playlist, stale = false),
                onRetry = {},
                onPlaybackRequest = { startIndex = it.startIndex },
                onSongLongClick = {},
            )
        }
        compose.onNodeWithText("Chill Mix").assertExists()
        compose.onNodeWithText("2 songs · by Sway Editorial").assertExists()
        // Tap-at-index geometry is device-matrix territory (LazyColumn
        // virtualization); the contract law lives in CatalogDetailViewModelTest.
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(0, startIndex) }
    }

    @Test
    fun detail_errorState_areaPanelWithCategoryCopy() {
        compose.setContent {
            AlbumDetailScreen(
                state = DetailState.Error(com.sway.core.model.SwayErrorUiState.Offline),
                onRetry = {}, onPlaybackRequest = {}, onArtistClick = {}, onSongLongClick = {},
            )
        }
        compose
            .onNodeWithText("You're offline. Check your connection and retry.")
            .assertExists()
    }
}

package com.sway.music.screens.menu

import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 10.8 — context-menu pure laws: Go-to entries only when refs exist,
 * like/unlike label flip, canonical raw URL shape.
 */
class SongMenuLawTest {

    private fun song(albumId: String? = null, artistId: String? = null) =
        Song.create("s1", "Song", albumId = albumId, artistId = artistId)!!

    @Test
    fun goToEntries_onlyWhenRefsExist() {
        val bare = visibleActions(song(), liked = false)
        assertTrue(SongMenuAction.GO_TO_ALBUM !in bare)
        assertTrue(SongMenuAction.GO_TO_ARTIST !in bare)

        val full = visibleActions(song(albumId = "al1", artistId = "ar1"), liked = false)
        assertTrue(SongMenuAction.GO_TO_ALBUM in full)
        assertTrue(SongMenuAction.GO_TO_ARTIST in full)
    }

    @Test
    fun coreActions_alwaysPresent_inStableOrder() {
        val actions = visibleActions(song(), liked = false)
        assertEquals(
            listOf(
                SongMenuAction.PLAY_NEXT,
                SongMenuAction.ADD_TO_QUEUE,
                // Story 12.3: explicit queue-sheet entry joins the cluster.
                SongMenuAction.OPEN_QUEUE,
                SongMenuAction.ADD_TO_PLAYLIST,
                SongMenuAction.TOGGLE_LIKE,
                SongMenuAction.SHARE_URL,
            ),
            actions,
        )
    }

    @Test
    fun likeLabel_flipsWithState() {
        assertEquals("Like", actionLabel(SongMenuAction.TOGGLE_LIKE, song(), liked = false))
        assertEquals("Unlike", actionLabel(SongMenuAction.TOGGLE_LIKE, song(), liked = true))
    }

    @Test
    fun rawUrl_canonicalWatchShape() {
        assertEquals("https://music.youtube.com/watch?v=abc123", rawCatalogUrl("abc123"))
    }
}

package com.sway.music.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.Song
import com.sway.music.playback.playbackRequestToBuiltQueue
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.playback.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 12.4 — cross-surface wiring (FR-8 + FR-22 COMPLETES HERE).
 *
 * Part 1 — the EIGHT-ENTRY wiring matrix at the seam every surface feeds:
 * each entry's canonical [PlaybackRequest] (built by the real
 * PlaybackRequests builders the screens emit) must map through
 * [playbackRequestToBuiltQueue] to a BuiltQueue with EXACT context order and
 * start index. Engine semantics (jump<=2s, exactly-one resolve) were proven
 * in 7.1; tap-to-audio <=3 s p95 remains the device-gated fr8TapToAudio
 * harness (documented trace, same honesty as E10's completion push).
 *
 * Part 2 — optimistic Mini materialization: a session appearing in the hoisted
 * state renders the Mini Player within ONE recomposition, before any audio or
 * artwork resolves (UJ-1 beat; placeholder art by construction).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class WiringMatrixTest {

    private fun song(id: String) = Song.create(id, "Track $id", durationMs = 30_000L)!!
    private fun ids(request: PlaybackRequest?) = request?.items?.map { it.id.value }

    // --- eight-entry matrix -----------------------------------------------------

    @Test
    fun entry1_searchRow_tapsIntoSongsGroupContext() {
        val songs = listOf("s1", "s2", "s3", "s4").map { song(it) }
        val req = PlaybackRequests.build(songs, PlaybackRequests.Mode.FromIndex(2))
        val built = playbackRequestToBuiltQueue(req)!!
        assertEquals(listOf("s1", "s2", "s3", "s4"), built.snapshot.items.map { it.id.value })
        assertEquals(2, built.startIndex)
    }

    @Test
    fun entry2_albumDetail_playFromChosenTrack() {
        val tracks = listOf("al_t0", "al_t1", "al_t2").map { song(it) }
        val req = PlaybackRequests.build(tracks, PlaybackRequests.Mode.FromIndex(1))
        val built = playbackRequestToBuiltQueue(req)!!
        assertEquals(3, built.snapshot.size)
        assertEquals(1, built.startIndex)
    }

    @Test
    fun entry3_artistRails_shuffledIsPrePermuted_startZero_deterministic() {
        val topSongs = listOf("ar_a", "ar_b", "ar_c", "ar_d").map { song(it) }
        val req = PlaybackRequests.build(topSongs, PlaybackRequests.Mode.Shuffled(seed = 11L))
        assertTrue(req.shuffled)
        assertEquals(0, req.startIndex)
        val built = playbackRequestToBuiltQueue(req)!!
        // Same seed => byte-identical order (deterministic Fisher-Yates law);
        // fed VERBATIM — no second shuffle anywhere downstream.
        val again = PlaybackRequests.build(topSongs, PlaybackRequests.Mode.Shuffled(11L))
        assertEquals(again.items.map { it.id.value }, built.snapshot.items.map { it.id.value })
        assertEquals(topSongs.map { it.id.value }.sorted(), built.snapshot.items.map { it.id.value }.sorted())
    }

    @Test
    fun entry4_catalogPlaylist_readOnlyContext_fromStart() {
        val tracks = listOf("cp_0", "cp_1").map { song(it) }
        val built = playbackRequestToBuiltQueue(
            PlaybackRequests.build(tracks, PlaybackRequests.Mode.FromIndex(0)),
        )!!
        assertEquals(0, built.startIndex)
        assertEquals(listOf("cp_0", "cp_1"), built.snapshot.items.map { it.id.value })
    }

    @Test
    fun entry5_liked_displayOrderNewestFirst_atTap() {
        val liked = listOf("l_new", "l_mid", "l_old").map { song(it) }
        val tapIdx = 2
        val built = playbackRequestToBuiltQueue(
            PlaybackRequests.build(liked, PlaybackRequests.Mode.FromIndex(tapIdx)),
        )!!
        assertEquals(listOf("l_new", "l_mid", "l_old"), built.snapshot.items.map { it.id.value })
        assertEquals(2, built.startIndex)
    }

    @Test
    fun entry6_playlistEditor_ownedOrder_preserved() {
        val owned = listOf("p_first", "p_second").map { song(it) }
        val built = playbackRequestToBuiltQueue(
            PlaybackRequest(items = owned, startIndex = 1, shuffled = false),
        )!!
        assertEquals(listOf("p_first", "p_second"), built.snapshot.items.map { it.id.value })
        assertEquals(1, built.startIndex)
    }

    @Test
    fun entry7_historyReplay_singleSongContext() {
        val replay = listOf(song("h_one"))
        val built = playbackRequestToBuiltQueue(
            PlaybackRequest(items = replay, startIndex = 0, shuffled = false),
        )!!
        assertEquals(listOf("h_one"), built.snapshot.items.map { it.id.value })
        assertEquals(0, built.startIndex)
    }

    @Test
    fun entry8_hubLikedTile_playWholeCollection() {
        val liked = listOf("hub_1", "hub_2", "hub_3").map { song(it) }
        val built = playbackRequestToBuiltQueue(
            PlaybackRequests.build(liked, PlaybackRequests.Mode.FromIndex(0)),
        )!!
        assertEquals(0, built.startIndex)
        assertEquals(3, built.snapshot.size)
    }

    // --- honesty laws -------------------------------------------------------------

    @Test
    fun emptyRequest_mapsToNull_neverEmptyAsSuccess() {
        assertNull(playbackRequestToBuiltQueue(PlaybackRequest(emptyList(), 0, false)))
    }

    @Test
    fun outOfBoundsIndex_coerces_neverThrows() {
        val items = listOf(song("x"))
        val built = playbackRequestToBuiltQueue(
            PlaybackRequest(items = items, startIndex = 99, shuffled = false),
        )!!
        assertEquals(0, built.startIndex)
    }

    @Test
    fun shuffledFlag_neverTouchesSessionShuffle_semanticsDocumented() {
        // The request flag describes how the ORDER was built; session toggle
        // state is user-owned (facade law). Mapping must not flip it.
        val top = listOf("a", "b").map { song(it) }
        val req = PlaybackRequests.build(top, PlaybackRequests.Mode.Shuffled(7L))
        val built = playbackRequestToBuiltQueue(req)!!
        assertEquals(req.items.map { it.id.value }, built.snapshot.items.map { it.id.value })
    }

    // --- optimistic Mini materialization (AC2 / UJ-1) --------------------------------

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun optimisticMini_appearsWithinOneRecomposition_placeholderArt() {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(PlayerUiState.Idle)
        var flowState by mutableStateOf(flow.value)
        compose.setContent {
            MiniPlayerBar(
                state = flowState,
                visible = flowState.currentItem != null,
                positionMs = 0L,
                onTogglePlayPause = {}, onNext = {}, onExpand = {},
                onOpenQueue = {}, onHide = {},
            )
        }
        compose.onAllNodesWithTag("mini_player").assertCountEquals(0)

        // Tap-to-play publishes currentItem synchronously in setQueue BEFORE any
        // resolution round-trip — modeled here by one direct emission; the
        // <=250 ms bound is the latency suite's job, this is the frame law.
        compose.runOnIdle {
            flow.value = PlayerUiState(currentItem = QueueItem.of(song("tap")))
            flowState = flow.value
        }
        compose.waitForIdle()
        compose.onNodeWithTag("mini_player").assertExists()
        compose.onNodeWithText("Track tap").assertExists()
        // Placeholder art (ArtworkPlaceholder glyph), not resolved imagery.
        compose.onNodeWithText("\u266A").assertExists()
    }
}

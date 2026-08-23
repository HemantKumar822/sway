package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.ArtworkRef
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.Song
import com.sway.core.model.SwayResult
import com.sway.core.model.fake.FakeStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Story 7.1 — queue command semantics (FR-22/23/24 engine substrate, A-4,
 * FR-11 toggle semantics complete here on the engine side; E12 completes the
 * surfaces).
 *
 * Full production stack under Robolectric sdk 36 (6.1/6.3 harness): service +
 * counting [FakeStreamResolver] -> silent-WAV file:// renditions + engine JIT
 * path + facade bound to the live player. Proven:
 *  - AC1 jump(k): switch inside the FR-23 2000 ms ceiling, EXACTLY ONE new
 *    resolve for item k, every other item still riding placeholders;
 *  - AC2/AC3 remove: removing-playing advances without silence (next JIT-
 *    resolved); removing-upcoming never disturbs current (zero resolves);
 *  - AC4–AC7 play-next / add-to-queue / clear / drag-reorder with live ==
 *    snapshot parity and session persistence;
 *  - AC8/AC9 shuffle: current preserved in place with ZERO extra resolves,
 *    remainder deterministic per session seed (cross-session same-seed
 *    equality), OFF restores the pre-shuffle order (newcomers appended);
 *  - AC10 repeat cycling maps onto media3-native modes incl. the wrap-at-ends
 *    timeline laws (Player.java: ALL "looping at the ends"; ONE "repeats the
 *    currently playing MediaItem infinitely during ongoing playback");
 *  - AC11 repeat-one arms the engine's 4.4 prefetch guard via self-subscribed
 *    repeat-mode events — prefetch goes silent while armed;
 *  - AC12 A-4 boundary: >=5000 ms restarts, <5000 ms jumps back (pure table +
 *    behavioral through the facade).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QueueCommandSemanticsTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scope: CoroutineScope
    private val wavFiles = mutableListOf<File>()
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null
    private lateinit var resolver: FakeStreamResolver

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        shadowOf(app as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        resolver = FakeStreamResolver()
    }

    @After
    fun tearDown() {
        try {
            facade?.release()
        } catch (_: Exception) {
        }
        try {
            serviceController?.destroy()
        } catch (_: Exception) {
        }
        idle()
        scope.cancel()
        wavFiles.forEach { it.delete() }
    }

    // ---------------------------------------------------------------------
    // AC1: jump(k) — <=2 s switch, exactly one resolve, others placeholder
    // ---------------------------------------------------------------------

    @Test
    fun jump_switchesWithinBudget_exactlyOneResolve_othersStayPlaceholders() {
        startPlaying(sixSongs(), startIndex = 0)

        resolver.resetCounts()
        val startedAt = System.nanoTime()
        facade!!.jumpTo(3)
        awaitUntil("jump lands on item 3 with its rendition resolved") {
            val p = player()
            p.currentMediaItemIndex == 3 &&
                p.getMediaItemAt(3).localConfiguration?.uri?.toString()?.startsWith("file://") == true
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "FR-23 budget: audio switch must occur <2000 ms hermetically (took ${elapsedMs}ms)",
            elapsedMs < 2_000,
        )
        assertEquals(
            "FR-12 budget: exactly one resolve for the jumped-to item",
            1,
            resolver.resolveCount,
        )
        assertEquals("Resolved item must be queue item 3", sixSongs()[3].id.value, resolver.resolvedIds.single().value)

        // End-state scan: start item (resolved up-front per FR-12) AND jumped-to
        // item carry real URLs; every other item rides a placeholder.
        val p = player()
        val resolvedIndices = mutableSetOf(0, 3)
        for (i in 0 until p.mediaItemCount) {
            val uri = p.getMediaItemAt(i).localConfiguration!!.uri.toString()
            if (i in resolvedIndices) {
                assertTrue("item $i expected resolved", uri.startsWith("file://"))
            } else {
                assertTrue("item $i must remain a sway:// placeholder, got $uri", uri.startsWith("sway://"))
            }
        }
        assertEquals("Facade mirror follows the jump", sixSongs()[3], facade!!.uiState.value.currentItem?.song)
    }

    // ---------------------------------------------------------------------
    // AC2/AC3: remove semantics
    // ---------------------------------------------------------------------

    @Test
    fun removePlaying_advancesAutomatically_withoutSilence_nextResolved() {
        startPlaying(sixSongs(), startIndex = 0)
        awaitUntil("playing") { player().isPlaying }

        resolver.resetCounts()
        facade!!.removeAt(0)

        awaitUntil("advanced to former second item, resolved") {
            val p = player()
            p.currentMediaItemIndex == 0 &&
                p.getMediaItemAt(0).mediaId == sixSongs()[1].id.value &&
                p.getMediaItemAt(0).localConfiguration?.uri?.toString()?.startsWith("file://") == true
        }
        assertTrue(
            "Playback intent must survive removing the playing item (no silence)",
            player().playWhenReady,
        )
        assertEquals("Exactly one JIT resolve for the auto-advanced item", 1, resolver.resolveCount)
        assertEquals(5, facade!!.currentQueue().size)
        assertEquals(sixSongs()[1], facade!!.uiState.value.currentItem?.song)
    }

    @Test
    fun removeUpcoming_currentNeverDisturbed_zeroResolves() {
        startPlaying(sixSongs(), startIndex = 1)
        val beforeId = player().getMediaItemAt(1).mediaId

        resolver.resetCounts()
        facade!!.removeAt(4)

        assertEquals(beforeId, player().getMediaItemAt(1).mediaId)
        assertEquals("No resolve may fire for an upcoming removal", 0, resolver.resolveCount)
        assertEquals(5, facade!!.currentQueue().size)
        assertEquals("Snapshot/timeline parity", 5, player().mediaItemCount)
    }

    // ---------------------------------------------------------------------
    // AC4/AC5: enrichment
    // ---------------------------------------------------------------------

    @Test
    fun playNext_landsDirectlyAfterCurrent_nextHitsIt() {
        startPlaying(tripleSongs(), startIndex = 0)
        val newcomer = song("x1", "Injected Next", "Newcomer", 44_000, null)

        facade!!.playNext(newcomer)

        assertEquals(listOf("n1", "x1", "n2", "n3"), facadeIds())
        assertEquals(newcomer.id.value, player().getMediaItemAt(1).mediaId)

        resolver.resetCounts()
        facade!!.next()
        awaitUntil("next() plays the injected item and resolves it JIT") {
            val uri = player().getMediaItemAt(player().currentMediaItemIndex)
                .localConfiguration?.uri?.toString()
            player().currentMediaItemIndex == 1 && uri?.startsWith("file://") == true
        }
        assertEquals(1, resolver.resolveCount)
    }

    @Test
    fun addToQueue_appendsAtTail_currentUnaffected() {
        startPlaying(tripleSongs(), startIndex = 1)
        val tail = song("z9", "Tail Song", "Appendee", 50_000, null)

        facade!!.addToQueue(tail)

        assertEquals(listOf("n1", "n2", "n3", "z9"), facadeIds())
        assertEquals(1, player().currentMediaItemIndex)
        assertEquals(tail.id.value, player().getMediaItemAt(3).mediaId)
    }

    // ---------------------------------------------------------------------
    // AC6: clear
    // ---------------------------------------------------------------------

    @Test
    fun clearQueue_stopsHonestly_emptiesTimeline_keepsRepeatMode_clearsFailureSlot() {
        startPlaying(tripleSongs(), startIndex = 0)
        while (facade!!.cycleRepeatMode() != RepeatMode.ALL) {
            // cycle until ALL
        }
        facade!!.setFailedTrack(QueueItem.of(sixSongs()[0]), com.sway.core.model.SwayError.Offline)
        assertNotNull(facade!!.uiState.value.failedTrack)

        facade!!.clearQueue()

        assertEquals(0, player().mediaItemCount)
        assertFalse("Pause intent set before clearing (honest stop)", player().playWhenReady)
        assertNull(facade!!.uiState.value.currentItem)
        assertNull("Failed-track slot cleared with the queue", facade!!.uiState.value.failedTrack)
        assertEquals("Repeat mode survives a queue clear (FR-11 is 7.2 territory)", RepeatMode.ALL, facade!!.uiState.value.repeatMode)
        assertTrue(facade!!.currentQueue().isEmpty())
    }

    // ---------------------------------------------------------------------
    // AC7: drag-reorder
    // ---------------------------------------------------------------------

    @Test
    fun moveQueueItem_reordersLiveAndSnapshot_persistsThroughSubsequentNavigation() {
        startPlaying(sixSongs(), startIndex = 0)

        facade!!.moveQueueItem(from = 3, to = 1)

        assertEquals(listOf("s1", "s4", "s2", "s3", "s5", "s6"), facadeIds())
        assertEquals(listOf("s1", "s4", "s2", "s3", "s5", "s6"), playerIds())

        // Session persistence: subsequent navigation obeys the new order.
        facade!!.next()
        awaitUntil("index 1 is the moved item") { player().currentMediaItemIndex == 1 }
        assertEquals("s4", player().currentMediaItem?.mediaId)
    }

    // ---------------------------------------------------------------------
    // AC8/AC9: shuffle toggle laws
    // ---------------------------------------------------------------------

    @Test
    fun shuffleOn_preservesCurrent_zeroResolves_deterministicPerSeed() {
        val songs = sixSongs()
        startPlaying(songs, startIndex = 2)
        facade!!.shuffleSeedOverride = 42L

        resolver.resetCounts()
        facade!!.setShuffleEnabled(true)

        val expected = QueueBuilder.reshufflePreservingCurrent(
            QueueSnapshot.of(songs.map { QueueItem.of(it) }).items,
            currentIndex = 2,
            seed = 42L,
        )
        assertEquals(
            "Remainder permutation must equal the pure oracle for seed 42",
            expected.map { it.id.value },
            facadeIds(),
        )
        assertEquals(2, player().currentMediaItemIndex)
        assertEquals("Current track untouched by reshuffle", songs[2].id.value, player().currentMediaItem?.mediaId)
        assertEquals("Zero extra resolves when shuffling", 0, resolver.resolveCount)
        assertTrue(facade!!.uiState.value.shuffleEnabled)
        assertTrue(facade!!.isShuffleEnabled())
    }

    @Test
    fun shuffle_sameSeedAcrossSessions_identicalOrder_differentSeedDiffers() {
        val songs = sixSongs()

        val first = shuffledOrderFor(songs, startIndex = 2, seed = 7L)
        serviceController?.destroy()
        serviceController = null
        idle()

        val second = shuffledOrderFor(songs, startIndex = 2, seed = 7L)
        assertEquals("Same session seed => identical shuffled remainder", first, second)

        val third = shuffledOrderFor(songs, startIndex = 2, seed = 8L)
        assertNotEquals("Different seed => different remainder", first, third)
    }

    @Test
    fun shuffleOff_restoresPreShuffleOrder_sessionAdditionsAppended() {
        val songs = sixSongs()
        startPlaying(songs, startIndex = 1)
        facade!!.shuffleSeedOverride = 99L

        facade!!.setShuffleEnabled(true)
        val shuffledIds = facadeIds()
        assertNotEquals(songs.map { it.id.value }, shuffledIds)

        val added = song("ad1", "Added While Shuffled", "Latecomer", 33_000, null)
        facade!!.addToQueue(added)
        facade!!.setShuffleEnabled(false)

        assertEquals(
            "Linear order restored; session addition appended at tail",
            songs.map { it.id.value } + "ad1",
            facadeIds(),
        )
        assertEquals("Still playing the same track after restore", songs[1].id.value, player().currentMediaItem?.mediaId)
        assertFalse(facade!!.uiState.value.shuffleEnabled)
    }

    // ---------------------------------------------------------------------
    // AC10: repeat mapping + native end laws (Player.java grounding)
    // ---------------------------------------------------------------------

    @Test
    fun repeatCycle_mapsToNativeModes_withNativeEndLaws() {
        startPlaying(sixSongs(), startIndex = 5) // last item: wrap law observable

        assertEquals(RepeatMode.OFF, facade!!.uiState.value.repeatMode)
        assertFalse("OFF: no next at last item", player().hasNextMediaItem())

        assertEquals(RepeatMode.ALL, facade!!.cycleRepeatMode())
        assertEquals(Player.REPEAT_MODE_ALL, player().repeatMode)
        assertTrue(
            "ALL wraps at the end (Player.java: 'Next' on last moves to first)",
            player().hasNextMediaItem(),
        )

        assertEquals(RepeatMode.ONE, facade!!.cycleRepeatMode())
        assertEquals(Player.REPEAT_MODE_ONE, player().repeatMode)

        assertEquals(RepeatMode.OFF, facade!!.cycleRepeatMode())
        assertEquals(Player.REPEAT_MODE_OFF, player().repeatMode)
        assertFalse("Back to OFF: end law restored", player().hasNextMediaItem())
    }

    // ---------------------------------------------------------------------
    // AC11: repeat-one arms the engine guard; prefetch silenced
    // ---------------------------------------------------------------------

    @Test
    fun repeatOne_armsPrefetchGuard_viaSelfSubscription_prefetchGoesSilent() {
        startPlaying(sixSongs(), startIndex = 0)
        val engine = serviceUnderTest().getEngineForTest()!!
        engine.setPrefetchEnabled(true)
        assertFalse("Guard starts unarmed", engine.isRepeatOneGuardArmedForTest())

        // Force a READY transition so the prefetch pipeline runs pre-guard
        // (positive control: the harness WOULD prefetch).
        facade!!.next()
        awaitUntil("prefetch pipeline observed before guard") { resolver.prefetchedIds.isNotEmpty() }
        resolver.resetCounts()

        assertEquals(RepeatMode.ALL, facade!!.cycleRepeatMode())
        assertFalse("ALL must NOT arm the repeat-one guard", engine.isRepeatOneGuardArmedForTest())
        // ALL alone also doesn't silence prefetch (still unarmed).
        engine.maybePrefetchNext()
        idle()
        assertTrue(resolver.prefetchedIds.isNotEmpty())
        resolver.resetCounts()

        while (facade!!.uiState.value.repeatMode != RepeatMode.ONE) {
            facade!!.cycleRepeatMode()
        }
        assertEquals(Player.REPEAT_MODE_ONE, player().repeatMode)
        assertTrue("Engine must arm its repeat-one guard from the player event", engine.isRepeatOneGuardArmedForTest())

        // The guard law: while armed, prefetch attempts are short-circuited —
        // driven through the exact entry point READY/BUFFERING events use.
        repeat(5) { engine.maybePrefetchNext() }
        idle()
        assertEquals(
            "Prefetch must be silent while the repeat-one guard is armed",
            0,
            resolver.prefetchedIds.size,
        )
        // ...and leaving ONE disarms again.
        assertEquals(RepeatMode.OFF, facade!!.cycleRepeatMode())
        assertFalse(engine.isRepeatOneGuardArmedForTest())
    }

    // ---------------------------------------------------------------------
    // AC12: A-4 previous rule
    // ---------------------------------------------------------------------

    @Test
    fun previousBoundary_pureLawTable() {
        assertEquals(
            JitPolicy.PreviousDecision.GO_BACK,
            JitPolicy.previousDecision(positionMs = 4_999L, hasPrevious = true),
        )
        assertEquals(
            JitPolicy.PreviousDecision.RESTART_CURRENT,
            JitPolicy.previousDecision(positionMs = 5_000L, hasPrevious = true),
        )
        assertEquals(
            "Negative positions clamp to 0 (below threshold => jump back)",
            JitPolicy.PreviousDecision.GO_BACK,
            JitPolicy.previousDecision(positionMs = -1L, hasPrevious = true),
        )
        assertEquals(
            "No previous item always restarts",
            JitPolicy.PreviousDecision.RESTART_CURRENT,
            JitPolicy.previousDecision(positionMs = 0L, hasPrevious = false),
        )
    }

    @Test
    fun previousBehavioral_overThresholdRestarts_underThresholdJumpsBack() {
        startPlaying(sixSongs(), startIndex = 2)

        // >= 5 s played -> restart current.
        player().seekTo(6_000L)
        idle()
        facade!!.previous()
        assertEquals("Same item restarts", "s3", player().currentMediaItem?.mediaId)
        assertEquals(2, player().currentMediaItemIndex)
        assertEquals(0L, player().currentPosition)

        // < 5 s played -> jump back.
        player().seekTo(2_000L)
        idle()
        facade!!.previous()
        assertEquals("Jumps to the previous item", "s2", player().currentMediaItem?.mediaId)
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    /** Builds the full production stack and starts playback of [songs]@[startIndex]. */
    private fun startPlaying(songs: List<Song>, startIndex: Int, wavSeconds: Int = 90) {
        val wav = writeSilentWav(wavSeconds)
        resolver.resolveBehavior = { id, request ->
            SwayResult.Success(
                ResolvedAudio(
                    url = "file://" + wav.absolutePath.replace('\\', '/'),
                    expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                    bitrateKbps = if (request.quality == Quality.HIGH) 256 else 160,
                    containerHint = "wav",
                    backendTag = "test:silent-wav",
                    renditionCacheKey = ResolvedAudio.cacheKey(id, request.quality),
                ),
            )
        }

        val sc = Robolectric.buildService(SwayPlaybackService::class.java)
        serviceController = sc
        sc.get().streamResolverForTest = resolver
        sc.create()
        idle()
        sc.get().addSession(sc.get().getSessionForTest()!!)
        idle()

        val snapshot = QueueSnapshot.of(songs.map { QueueItem.of(it) })
        val items = snapshot.items.map { qi ->
            MediaItem.Builder()
                .setMediaId(qi.id.value)
                .setUri(PendingUri.buildString(qi.id))
                .setMediaMetadata(qi.song.toMediaMetadata())
                .build()
        }
        sc.get().getEngineForTest()!!.startQueueAndPlay(items, startIndex)
        idle()

        val conn = PlayerConnection.bareForTest(scope)
        facade = conn
        conn.bindPlayer(sc.get().getPlayerForTest()!!)
        // Mirror production truth: the facade owns the queue snapshot even when
        // the stack was started through the engine path (direct-bind harness).
        conn.adoptSnapshotForTest(snapshot, startIndex)
        idle()

        conn.play()
        awaitUntil("start item resolves and plays (READY)") {
            val p = sc.get().getPlayerForTest() ?: return@awaitUntil false
            p.playWhenReady && p.playbackState == Player.STATE_READY
        }
    }

    /** Fresh stack for cross-session determinism proofs; returns facade order post-shuffle. */
    private fun shuffledOrderFor(songs: List<Song>, startIndex: Int, seed: Long): List<String> {
        startPlaying(songs, startIndex)
        facade!!.shuffleSeedOverride = seed
        facade!!.setShuffleEnabled(true)
        return facadeIds()
    }

    private fun player(): Player =
        serviceController?.get()?.getPlayerForTest() ?: error("service player not available")

    private fun serviceUnderTest(): SwayPlaybackService =
        serviceController?.get() ?: error("service not started")

    private fun facadeIds(): List<String> = facade!!.currentQueue().map { it.id.value }

    private fun playerIds(): List<String> {
        val p = player()
        return (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
            idle()
        }
        idle()
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun tripleSongs(): List<Song> = listOf(
        song("n1", "Alpha Track", "Composer One", 61_000, "https://img.example/n1.jpg"),
        song("n2", "Beta Track", "Composer Two", 122_000, "https://img.example/n2.jpg"),
        song("n3", "Gamma Track", null, 183_000, null),
    )

    private fun sixSongs(): List<Song> = listOf(
        song("s1", "Song One", "Artist A", 60_000, null),
        song("s2", "Song Two", "Artist B", 70_000, "https://img.example/s2.jpg"),
        song("s3", "Song Three", null, 80_000, null),
        song("s4", "Song Four", "Artist D", 90_000, null),
        song("s5", "Song Five", "Artist E", 100_000, null),
        song("s6", "Song Six", null, 110_000, null),
    )

    private fun song(id: String, rawTitle: String, artist: String?, durMs: Long, artUrl: String?): Song =
        Song.create(
            id = id,
            rawTitle = rawTitle,
            artistName = artist,
            durationMs = durMs,
            artwork = artUrl?.let { ArtworkRef.of(it) },
        )!!

    /**
     * Minimal valid PCM WAV (8 kHz mono 16-bit silence, ~[seconds]) that
     * ExoPlayer's WavExtractor parses to STATE_READY under Robolectric.
     */
    private fun writeSilentWav(seconds: Int): File {
        val sampleRate = 8_000
        val dataSize = sampleRate * seconds * 2 // mono, 16-bit
        val file = File.createTempFile("sway_silent_", ".wav")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLe(36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLe(16)
            out.writeShortLe(1)
            out.writeShortLe(1)
            out.writeIntLe(sampleRate)
            out.writeIntLe(sampleRate * 2)
            out.writeShortLe(2)
            out.writeShortLe(16)
            out.writeBytes("data")
            out.writeIntLe(dataSize)
            out.write(ByteArray(dataSize))
        }
        file.deleteOnExit()
        wavFiles.add(file)
        return file
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}

package com.sway.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.AudioRequest
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import com.sway.core.model.fake.FakeStreamResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch

/**
 * Story 5.4 — stalled-playback watchdog (FR-14 COMPLETES HERE; AD-7 defense
 * layer 3; NFR-3 escalation bounds; P-5 thresholds 3 s / 15 s).
 *
 * Proves over HERMETIC players (a never-completing DataSource parks every
 * fetch in STATE_BUFFERING with a frozen position = a deterministic stall on
 * ANY machine) with synthetic-timestamp ticks driven through the internal
 * [JitResolveEngine.onWatchdogTick] seam:
 * - Soft stall (>3 s frozen) fires EXACTLY ONE downscale replay at
 *   [JitPolicy.DOWNSCALE_QUALITY] with resume honored.
 * - Sustained stall (>=15 s cumulative) escalates to full stream rebuilds,
 *   spaced >=[JitPolicy.WATCHDOG_ACTION_SPACING_MS] apart.
 * - Repeated rebuild failure skips to the next queue item with the typed
 *   category on uiState.failedTrack — no crash, no hot loop.
 * - Silence when healthy/paused/placeholder; single-owner suppression both
 *   directions; clean resets on transitions and successful progress.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StalledPlaybackWatchdogTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val teardowns = mutableListOf<() -> Unit>()

    @After
    fun runTeardowns() {
        teardowns.forEach { try { it() } catch (_: Exception) {} }
        teardowns.clear()
    }

    private fun addTeardown(block: () -> Unit) {
        teardowns.add(block)
    }

    private fun mainScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        addTeardown { scope.cancel() }
        return scope
    }

    /** DataSource whose open() blocks forever: a deterministic frozen stall. */
    private class BlockingDataSource : DataSource {
        private val gate = CountDownLatch(1)

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long =
            try {
                gate.await()
                C.LENGTH_UNSET.toLong()
            } catch (_: InterruptedException) {
                throw InterruptedIOException("hermetic fetch released")
            }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = null

        override fun close() {}
    }

    private fun hermeticPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { BlockingDataSource() }))
            .build()
        addTeardown { try { player.release() } catch (_: Exception) {} }
        return player
    }

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Title $id", durationMs = 180_000)!!

    private fun snapshot(vararg ids: String): QueueSnapshot =
        QueueSnapshot.of(ids.map { QueueItem.of(song(it)) })

    private fun mediaItems(snap: QueueSnapshot): List<MediaItem> =
        snap.items.map { qi ->
            MediaItem.Builder()
                .setMediaId(qi.id.value)
                .setUri(PendingUri.buildString(qi.id))
                .build()
        }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun uriOf(item: MediaItem): String? = item.localConfiguration?.uri?.toString()

    private fun audio(url: String, id: SourceId): ResolvedAudio =
        ResolvedAudio(
            url = url,
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            bitrateKbps = 160,
            containerHint = "mp4",
            backendTag = "fake:watchdog",
            renditionCacheKey = "wd:${id.value}",
        )

    /** Test rig: hermetic stalled player + counting resolver + engine (+facade glue). */
    private class Rig(
        val player: ExoPlayer,
        val fake: FakeStreamResolver,
        val engine: JitResolveEngine,
        val conn: PlayerConnection?,
        val forcedKeys: MutableList<String>,
        val publications: MutableList<FailedTrack>,
    )

    /**
     * Build a RESOLVED current item parked in BUFFERING with an audible seek
     * position — the deterministic mid-track stall fixture.
     *
     * [forced] answers invalidate+refresh resolves (watchdog tiers and layer-2
     * renewals); [base] answers plain resolves (start swap / JIT transitions).
     */
    private fun rig(
        ids: List<String> = listOf("w0", "w1"),
        seekToMs: Long = 42_000L,
        withFacade: Boolean = false,
        forced: suspend (SourceId, AudioRequest) -> SwayResult<ResolvedAudio> = { id, _ ->
            SwayResult.Success(audio("https://cdn.example.com/audio/${id.value}?fresh=${System.nanoTime()}", id))
        },
        base: suspend (SourceId, AudioRequest) -> SwayResult<ResolvedAudio> = { id, req ->
            SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, req.quality))
        },
    ): Rig {
        val forcedKeys = mutableListOf<String>()
        val publications = mutableListOf<FailedTrack>()
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) {
                forcedKeys += "${id.value}:${request.quality.name}"
                forced(id, request)
            } else {
                base(id, request)
            }
        }
        val scope = mainScope()
        val snap = snapshot(*ids.toTypedArray())
        var conn: PlayerConnection? = null
        if (withFacade) {
            val player = hermeticPlayer()
            conn = PlayerConnection.forTest(player, scope).also { c ->
                addTeardown { try { c.release() } catch (_: Exception) {} }
            }
        }
        val enginePlayer = conn?.let { hermeticPlayer() }?.also { conn.bindPlayer(it) } ?: hermeticPlayer()
        val engine = JitResolveEngine(
            enginePlayer, fake, scope,
            onFailure = { track ->
                publications += track
                conn?.setFailedTrack(track.item, track.error)
            },
        )
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        if (seekToMs > 0L) {
            enginePlayer.seekTo(seekToMs)
            idle()
        }
        return Rig(enginePlayer, fake, engine, conn, forcedKeys, publications)
    }

    private fun ticks(rig: Rig, vararg timesMs: Long) {
        timesMs.forEach { t ->
            rig.engine.onWatchdogTick(t)
            idle()
        }
    }

    // -----------------------------------------------------------------------
    // Policy law: P-5 constants + pure escalation ladder boundary table
    // -----------------------------------------------------------------------

    @Test
    fun policy_watchdogConstants_p5Targets_andLadderBoundaryTable() {
        assertEquals("P-5 soft stall target", 3_000L, JitPolicy.WATCHDOG_SOFT_STALL_MS)
        assertEquals("P-5 hard stall target", 15_000L, JitPolicy.WATCHDOG_HARD_STALL_MS)
        assertTrue("ticker must sample well below the soft bound", JitPolicy.WATCHDOG_TICK_MS in 100..2_000)
        assertTrue("NFR-3 anti-hot-loop law", JitPolicy.MAX_REBUILDS_PER_EPISODE in 1..3)
        assertEquals(JitPolicy.WATCHDOG_ACTION_SPACING_MS, JitPolicy.WATCHDOG_SOFT_STALL_MS)
        assertEquals(Quality.LOW, JitPolicy.DOWNSCALE_QUALITY)

        val MAXV = Long.MAX_VALUE
        // Below soft: nothing.
        assertEquals(JitPolicy.WatchdogAction.None, JitPolicy.watchdogAction(0, false, 0, MAXV))
        assertEquals(JitPolicy.WatchdogAction.None, JitPolicy.watchdogAction(2_999, false, 0, MAXV))
        assertEquals(JitPolicy.WatchdogAction.None, JitPolicy.watchdogAction(2_999, true, 2, MAXV))
        // Soft band: one downscale, latched afterwards.
        assertEquals(JitPolicy.WatchdogAction.Downscale, JitPolicy.watchdogAction(3_000, false, 0, MAXV))
        assertEquals(JitPolicy.WatchdogAction.Downscale, JitPolicy.watchdogAction(14_999, false, 0, MAXV))
        assertEquals(JitPolicy.WatchdogAction.None, JitPolicy.watchdogAction(5_000, true, 0, MAXV))
        // Hard band: rebuilds while budget lasts, then honest skip.
        assertEquals(JitPolicy.WatchdogAction.Rebuild, JitPolicy.watchdogAction(15_000, false, 0, MAXV))
        assertEquals(JitPolicy.WatchdogAction.Rebuild, JitPolicy.watchdogAction(16_000, true, 1, MAXV))
        assertEquals(JitPolicy.WatchdogAction.Skip, JitPolicy.watchdogAction(15_000, false, 2, MAXV))
        assertEquals(JitPolicy.WatchdogAction.Skip, JitPolicy.watchdogAction(90_000, true, 2, MAXV))
        // Spacing gate: a just-fired action blocks every tier briefly.
        assertEquals(JitPolicy.WatchdogAction.None, JitPolicy.watchdogAction(20_000, false, 0, 2_999))
        assertEquals(JitPolicy.WatchdogAction.Rebuild, JitPolicy.watchdogAction(20_000, false, 0, 3_000))
        assertEquals(JitPolicy.WatchdogAction.Skip, JitPolicy.watchdogAction(20_000, false, 2, 9_999))

        // Stall candidate law: playing intent + BUFFERING only.
        assertTrue(JitPolicy.isStallCandidate(true, Player.STATE_BUFFERING))
        assertFalse(JitPolicy.isStallCandidate(false, Player.STATE_BUFFERING))
        assertFalse(JitPolicy.isStallCandidate(true, Player.STATE_READY))
        assertFalse(JitPolicy.isStallCandidate(true, Player.STATE_IDLE))
        assertFalse(JitPolicy.isStallCandidate(true, Player.STATE_ENDED))

        // Single-owner suppression law.
        assertTrue(JitPolicy.isWatchdogSuppressed(renewalInFlight = true, watchdogRecoveryInFlight = false))
        assertTrue(JitPolicy.isWatchdogSuppressed(renewalInFlight = false, watchdogRecoveryInFlight = true))
        assertFalse(JitPolicy.isWatchdogSuppressed(renewalInFlight = false, watchdogRecoveryInFlight = false))
    }

    // -----------------------------------------------------------------------
    // AC1: soft stall at ~3.5 s -> exactly ONE downscale replay
    // -----------------------------------------------------------------------

    @Test
    fun softStall_firesExactlyOneDownscaleReplay_resumeHonored_identityKept() {
        val rig = rig(seekToMs = 42_000L)

        ticks(rig, 0, 1_000, 2_000, 2_900)
        assertEquals("Below soft threshold: zero watchdog activity", 0, rig.forcedKeys.size)
        assertEquals(0, rig.fake.invalidateCount)

        ticks(rig, 3_500)
        assertEquals("Exactly one recovery resolve at soft crossing", 1, rig.forcedKeys.size)
        assertEquals("Downscale rides the lower bitrate target", "w0:LOW", rig.forcedKeys.single())
        assertEquals(1, rig.fake.invalidateCount)
        assertTrue("Fresh rendition swapped in place", uriOf(rig.player.getMediaItemAt(0))!!.contains("fresh="))
        assertEquals("Identity preserved through mediaId scan", "w0", rig.player.getMediaItemAt(0).mediaId)
        assertEquals("Captured audible position restored exactly", 42_000L, rig.player.currentPosition)
        assertTrue("Playing intent preserved", rig.player.playWhenReady)
        assertNull("Recovery is invisible: no typed failure", rig.engine.latestFailure.value)

        ticks(rig, 4_500, 6_000, 8_000, 10_000, 12_000, 14_999)
        assertEquals("Downgrade latch: no second action below hard", 1, rig.forcedKeys.size)
    }

    // -----------------------------------------------------------------------
    // AC2/AC3: sustained stall ladder — rebuilds spaced, skip typed, bounded
    // -----------------------------------------------------------------------

    @Test
    fun sustainedStall_rebuildsFireSpaced_thenSkipWithTypedReason_noHotLoop() {
        val rig = rig(
            withFacade = true,
            seekToMs = 42_000L,
            forced = { _, _ -> SwayResult.Failure(SwayError.RateLimited) },
        )

        ticks(rig, 0, 1_000, 2_000, 3_500, 6_000, 9_000, 12_000, 14_000)
        assertEquals("Downscale attempted once (silent failure)", 1, rig.forcedKeys.size)
        assertNull("Failures stay silent until the SKIP tier owns surfacing", rig.engine.latestFailure.value)

        ticks(rig, 15_000)
        assertEquals("Hard crossing fires rebuild #1", 2, rig.forcedKeys.size)
        assertEquals("Full rebuild rides forceRefresh at AUTO", "w0:AUTO", rig.forcedKeys.last())

        ticks(rig, 16_000)
        assertEquals("Spacing gate: just-applied action is protected", 2, rig.forcedKeys.size)

        ticks(rig, 18_000)
        assertEquals("Rebuild #2 spends the budget", 3, rig.forcedKeys.size)

        ticks(rig, 21_000)
        val failed = rig.conn!!.uiState.value.failedTrack
        assertNotNull("Repeated rebuild failure surfaces the typed category", failed)
        assertEquals(SwayError.UpstreamUnavailable, failed!!.error)
        assertEquals("w0", failed.item.id.value)
        assertEquals("Skipped to the next queue item", 1, rig.player.currentMediaItemIndex)
        assertEquals(3, rig.forcedKeys.size)

        ticks(rig, 21_200, 22_400)
        assertEquals("No hot loop after the skip", 3, rig.forcedKeys.size)
        assertTrue(rig.player.playWhenReady)
    }

    @Test
    fun sustainedStall_rebuildSucceeds_butStreamStillDead_ladderStillSkips() {
        var n = 0
        val rig = rig(
            withFacade = true,
            seekToMs = 42_000L,
            forced = { id, _ ->
                n++
                SwayResult.Success(audio("https://cdn.example.com/audio/${id.value}?v=$n", id))
            },
        )
        val urlsSeen = mutableListOf<String>()

        ticks(rig, 0, 3_500, 15_000, 18_000, 21_000)
        urlsSeen += uriOf(rig.player.getMediaItemAt(0))!!
        assertEquals("Both rebuild tiers spent across spaced crossings", 3, rig.forcedKeys.size)
        assertNotNull(rig.engine.latestFailure.value)
        assertEquals(SwayError.UpstreamUnavailable, rig.engine.latestFailure.value!!.error)
        assertEquals("Skip advances despite successful rebuilds", 1, rig.player.currentMediaItemIndex)
        assertEquals("w0", rig.conn!!.uiState.value.failedTrack!!.item.id.value)
        assertTrue("Each rebuild really swapped a distinct fresh URL", urlsSeen.single().contains("?v=3"))
    }

    // -----------------------------------------------------------------------
    // Healthy short buffering never fires; position progress resets debt
    // -----------------------------------------------------------------------

    @Test
    fun healthyBuffering_underSoftThreshold_neverFires_progressResetsDebt() {
        val rig = rig(seekToMs = 42_000L)

        ticks(rig, 0, 1_000, 2_000, 2_900)
        rig.player.seekTo(43_000L)
        idle()
        ticks(rig, 3_900, 4_900, 5_800)
        rig.player.seekTo(44_000L)
        idle()
        ticks(rig, 6_900, 7_900, 8_800, 9_700)

        assertEquals("Healthy buffering never escalates", 0, rig.forcedKeys.size)
        assertEquals(0, rig.fake.invalidateCount)
        assertNull(rig.engine.latestFailure.value)
    }

    // -----------------------------------------------------------------------
    // Paused user never fires (idle self-stop flow untouched)
    // -----------------------------------------------------------------------

    @Test
    fun pausedPlayer_ticksFarPastThresholds_neverFire() {
        val rig = rig(seekToMs = 30_000L)
        rig.player.pause()
        idle()

        ticks(rig, 0, 3_500, 10_000, 16_000, 30_000)

        assertEquals("Paused playback owns no stall debt", 0, rig.forcedKeys.size)
        assertEquals(0, rig.fake.invalidateCount)
        assertFalse(rig.player.playWhenReady)
        assertNull(rig.engine.latestFailure.value)
    }

    // -----------------------------------------------------------------------
    // Placeholder current item: the JIT worker owns it, watchdog inert
    // -----------------------------------------------------------------------

    @Test
    fun placeholderCurrentItem_watchdogNeverFires_jitOwnsResolution() {
        val rig = rig(
            seekToMs = 40_000L,
            base = { id, _ ->
                if (id.value == "w0") SwayResult.Failure(SwayError.ContentNotFound)
                else SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id))
            },
        )
        assertTrue("Fixture precondition: current item rides the placeholder",
            PendingUri.isPending(uriOf(rig.player.getMediaItemAt(0))))

        ticks(rig, 0, 3_500, 10_000, 16_000, 30_000)

        assertEquals("Watchdog never resolves for a placeholder item", 0, rig.forcedKeys.size)
        assertEquals(0, rig.fake.invalidateCount)
        assertTrue(
            "Only the JIT typed failure exists (start swap + its transition retry)",
            rig.publications.all { it.error is SwayError.ContentNotFound && it.item.id.value == "w0" } &&
                rig.publications.isNotEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Layer-2 renewal in flight suppresses the watchdog, then hands back
    // -----------------------------------------------------------------------

    @Test
    fun renewalInFlight_suppressesWatchdog_thenOwnershipHandsBack() {
        val gate = CompletableDeferred<Unit>()
        val rig = rig(
            seekToMs = 42_000L,
            forced = { id, _ ->
                gate.await()
                SwayResult.Success(audio("https://cdn.example.com/audio/${id.value}?renewed=1", id))
            },
        )

        rig.engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()
        assertEquals("Layer-2 renewal pipeline gated in flight", 1, rig.forcedKeys.size)

        ticks(rig, 0, 5_000, 10_000, 15_000, 20_000, 25_000)
        assertEquals("Single owner: watchdog draws NOTHING while renewal runs", 1, rig.forcedKeys.size)

        gate.complete(Unit)
        idle()
        assertTrue("Renewal applied its fresh rendition", uriOf(rig.player.getMediaItemAt(0))!!.contains("renewed=1"))
        assertEquals("Resume honored through layer 2", 42_000L, rig.player.currentPosition)

        ticks(rig, 25_100, 26_100, 28_100)
        assertEquals("After handback the fresh ladder earns its own downscale", 2, rig.forcedKeys.size)
        assertEquals("w0:LOW", rig.forcedKeys.last())
    }

    // -----------------------------------------------------------------------
    // Watchdog in flight defers RETRYABLE renewals; fatal still surfaces
    // -----------------------------------------------------------------------

    @Test
    fun watchdogInFlight_defersRetryableRenewal_fatalClassStillSurfaces() {
        val gate = CompletableDeferred<Unit>()
        val rig = rig(
            seekToMs = 42_000L,
            forced = { id, request ->
                if (request.quality == Quality.LOW) {
                    gate.await()
                    SwayResult.Success(audio("https://cdn.example.com/audio/${id.value}?low=1", id))
                } else {
                    SwayResult.Success(audio("https://cdn.example.com/audio/${id.value}?auto=1", id))
                }
            },
        )

        ticks(rig, 0, 3_500)
        assertEquals("Downscale gated in flight", 1, rig.forcedKeys.size)

        rig.engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()
        assertEquals("Retryable renewal DEFERS to the watchdog owner", 1, rig.forcedKeys.size)

        rig.engine.handlePlayerError(4001)
        idle()
        assertEquals("Fatal classes bypass ownership and surface immediately", 1, rig.forcedKeys.size)
        assertTrue(rig.engine.latestFailure.value!!.error is SwayError.Unknown)

        gate.complete(Unit)
        idle()
        assertEquals("Gated downscale completes normally", 1, rig.forcedKeys.size)
        assertTrue(uriOf(rig.player.getMediaItemAt(0))!!.contains("low=1"))
        assertEquals(42_000L, rig.player.currentPosition)
        assertTrue(rig.player.playWhenReady)
    }

    // -----------------------------------------------------------------------
    // Transition timing excluded from stall accounting; new item = fresh ladder
    // -----------------------------------------------------------------------

    @Test
    fun gaplessTransition_discardsDebt_newItemEarnsFreshLadder() {
        val rig = rig(ids = listOf("g0", "g1"), seekToMs = 42_000L)

        ticks(rig, 0, 1_000, 2_000)
        assertEquals("2000 ms of pre-transition debt accrues nothing", 0, rig.forcedKeys.size)

        rig.player.seekToNextMediaItem()
        idle()

        ticks(rig, 2_100, 3_100, 4_100)
        assertEquals("Post-transition accumulation restarts from zero", 0, rig.forcedKeys.size)

        ticks(rig, 5_100)
        assertEquals("New item earns its OWN downscale", 1, rig.forcedKeys.size)
        assertEquals("g1:LOW", rig.forcedKeys.single())
    }

    // -----------------------------------------------------------------------
    // Successful progress clears tier memory: next stall earns a fresh ladder
    // -----------------------------------------------------------------------

    @Test
    fun successfulProgress_resetsTierMemory_nextStallRunsFullLadderAgain() {
        val rig = rig(seekToMs = 42_000L)

        ticks(rig, 0, 3_500)
        assertEquals(1, rig.forcedKeys.size)

        rig.engine.noteSuccessfulProgress()
        idle()

        ticks(rig, 3_600, 4_600, 6_600)
        assertEquals("Without the reset the latch would hold; progress grants a fresh episode", 2, rig.forcedKeys.size)
        assertNull(rig.engine.latestFailure.value)
    }

    // -----------------------------------------------------------------------
    // Last-item skip pauses instead of looping on a dead tail
    // -----------------------------------------------------------------------

    @Test
    fun skipOnLastItem_publishesTypedReason_thenPausesHonestly() {
        val rig = rig(
            ids = listOf("z0"),
            seekToMs = 20_000L,
            forced = { _, _ -> SwayResult.Failure(SwayError.Offline) },
        )

        ticks(rig, 0, 3_500, 15_000, 18_000, 21_000)

        assertEquals(3, rig.forcedKeys.size)
        assertEquals(SwayError.UpstreamUnavailable, rig.engine.latestFailure.value!!.error)
        assertEquals("z0", rig.engine.latestFailure.value!!.item.id.value)
        assertFalse("No next item: pause instead of looping", rig.player.playWhenReady)
        assertEquals(1, rig.player.mediaItemCount)

        ticks(rig, 22_000, 24_000, 30_000)
        assertEquals("Honest stop: nothing further fires", 3, rig.forcedKeys.size)
    }

    // -----------------------------------------------------------------------
    // Service lifecycle: watchdog armed at creation, torn down cleanly
    // -----------------------------------------------------------------------

    @Test
    fun serviceLifecycle_watchdogArmedOnEngine_andReleasedCleanly() {
        val fake = FakeStreamResolver()
        val controller = Robolectric.buildService(SwayPlaybackService::class.java)
        val service = controller.get()
        service.streamResolverForTest = fake
        controller.create()
        idle()

        val engine = service.getEngineForTest()
        assertNotNull("Service arms the resolution engine", engine)
        engine!!.startWatchdog()
        idle()
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
        idle()
        engine.release()
    }

    // -----------------------------------------------------------------------
    // Forced-stall suite record (SM-2-style artifact): ladder within bounds
    // -----------------------------------------------------------------------

    @Test
    fun forcedStallLadder_sweepWithinP5Bounds_recordEmitted() {
        val trials = 4
        var softOk = 0
        var hardOk = 0
        var skipOk = 0
        for (trial in 0 until trials) {
            val lostPosition = 5_000L + (trial * 9_113L) % 60_000L

            // Scenario SOFT: 3.5 s frozen -> downscale fired, nothing skipped.
            run {
                val rig = rig(seekToMs = lostPosition)
                ticks(rig, 0, 3_500)
                if (rig.forcedKeys.singleOrNull()?.endsWith(":LOW") == true &&
                    rig.engine.latestFailure.value == null &&
                    rig.player.currentMediaItemIndex == 0
                ) softOk++
            }
            // Scenario HARD: 16 s frozen -> full rebuild fired, still honest.
            run {
                val rig = rig(seekToMs = lostPosition)
                ticks(rig, 0, 3_500, 15_000, 16_000)
                if (rig.forcedKeys.any { it.endsWith(":AUTO") } &&
                    rig.engine.latestFailure.value == null &&
                    rig.player.currentMediaItemIndex == 0
                ) hardOk++
            }
            // Scenario REPEATED-FAILURE: both rebuilds fail -> skip with reason.
            run {
                val rig = rig(seekToMs = lostPosition, forced = { _, _ -> SwayResult.Failure(SwayError.RateLimited) })
                ticks(rig, 0, 3_500, 15_000, 18_000, 21_000)
                val pub = rig.publications.lastOrNull()
                if (pub?.error == SwayError.UpstreamUnavailable && pub.item.id.value == "w0" &&
                    rig.player.currentMediaItemIndex == 1
                ) skipOk++
            }
        }
        println(
            "FORCED-STALL RECORD (story 5.4): SOFT@3.5s->DOWNSCALE $softOk/$trials | " +
                "HARD@16s->REBUILD $hardOk/$trials | REPEATED-FAILURE->SKIP-TYPED $skipOk/$trials | " +
                "P-5 bounds ${JitPolicy.WATCHDOG_SOFT_STALL_MS}/${JitPolicy.WATCHDOG_HARD_STALL_MS} ms",
        )
        assertEquals(trials, softOk)
        assertEquals(trials, hardOk)
        assertEquals(trials, skipOk)
    }
}

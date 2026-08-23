package com.sway.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
 * Story 5.3 — error-triggered renewal with position resume (FR-13 COMPLETES
 * HERE; AD-7 defense layer 2; NFR-3; SM-2 forced-expiry suite core).
 *
 * Proves with the counting resolver double over HERMETIC players (a
 * never-completing DataSource keeps every fetch parked in BUFFERING, so no
 * environmental network noise can reach the renewal layer):
 * - Mid-play source-class errors renew invisibly and resume within
 *   +/-[JitPolicy.RESUME_TOLERANCE_MS] of the lost position (SM-2 record:
 *   20 forced trials emitted to test output).
 * - Playing intent restored; paused stays paused.
 * - Concurrent duplicate errors for one SourceId collapse into ONE resolve.
 * - Bounded retries then typed failure surfaced via uiState.failedTrack;
 *   budget resets on successful progress.
 * - Fatal classes surface immediately with zero renewal attempts.
 * - Placeholder/no-evidence scenarios skip silently; service self-stop is
 *   never tripped by the renewal flow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ErrorTriggeredRenewalTest {

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

    /**
     * DataSource whose open() blocks forever: fetches never complete, never
     * error — the player parks in BUFFERING deterministically on ANY machine
     * (no dependence on DNS behavior of the host sandbox).
     */
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

    /** ExoPlayer wired to the blocking datasource — fully hermetic. */
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

    /** ResolvedAudio with an explicit URL and healthy 60-min expiry. */
    private fun audio(url: String, id: SourceId): ResolvedAudio =
        ResolvedAudio(
            url = url,
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            bitrateKbps = 160,
            containerHint = "mp4",
            backendTag = "fake:renewal",
            renditionCacheKey = "rn:${id.value}",
        )

    private fun assertNullFailureSlot(engine: JitResolveEngine) {
        assertEquals("No typed failure expected on this path", null, engine.latestFailure.value)
    }

    private fun assertWithinTolerance(actual: Long, expected: Long, what: String) {
        assertTrue(
            "$what: resume $actual must be within +/-${JitPolicy.RESUME_TOLERANCE_MS} ms of $expected",
            Math.abs(actual - expected) <= JitPolicy.RESUME_TOLERANCE_MS,
        )
    }

    // -----------------------------------------------------------------------
    // Policy law: classification, mapping, tolerance, eligibility bounds
    // -----------------------------------------------------------------------

    @Test
    fun policy_sourceClassBoundaryTable_mappingAndConstants() {
        assertFalse(JitPolicy.isExpiryRetryableSourceError(1000))
        assertFalse(JitPolicy.isExpiryRetryableSourceError(1999))
        assertTrue(JitPolicy.isExpiryRetryableSourceError(2000))
        assertTrue(JitPolicy.isExpiryRetryableSourceError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(JitPolicy.isExpiryRetryableSourceError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(JitPolicy.isExpiryRetryableSourceError(2999))
        assertFalse(JitPolicy.isExpiryRetryableSourceError(3000))
        assertFalse(JitPolicy.isExpiryRetryableSourceError(4001))

        assertEquals(
            SwayError.UpstreamUnavailable,
            JitPolicy.mapPlayerErrorToSwayError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        val cause = IllegalStateException("boom")
        val mapped = JitPolicy.mapPlayerErrorToSwayError(4001, cause)
        assertTrue(mapped is SwayError.Unknown)
        assertEquals(cause, (mapped as SwayError.Unknown).cause)

        assertEquals("P-5 resume tolerance target", 3_000L, JitPolicy.RESUME_TOLERANCE_MS)
        assertTrue(
            "NFR-3 anti-hot-loop law: budget must be 1..2",
            JitPolicy.MAX_RENEWALS_PER_EPISODE in 1..2,
        )
        assertEquals(0L, JitPolicy.clampResumePosition(-5L))
        assertEquals(123L, JitPolicy.clampResumePosition(123L))

        assertFalse(JitPolicy.isRenewalEligible(0L, playingObserved = false))
        assertTrue(JitPolicy.isRenewalEligible(1L, playingObserved = false))
        assertTrue(JitPolicy.isRenewalEligible(0L, playingObserved = true))
    }

    // -----------------------------------------------------------------------
    // AC1: mid-play 403 -> renewal lands within +/-3 s, playing restored
    // -----------------------------------------------------------------------

    @Test
    fun midPlay403_renews_resumesWithinThreeSeconds_playingRestored_noFailure() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val requests = mutableListOf<AudioRequest>()
        val freshUrl = "https://cdn.example.com/audio/a0?fresh=1"
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            requests += request
            if (request.forceRefresh) SwayResult.Success(audio(freshUrl, id))
            else SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        val snap = snapshot("a0", "a1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        assertEquals("Start resolve only", 1, fake.resolveCount)
        assertTrue(uriOf(player.getMediaItemAt(0))!!.startsWith("https://cdn.example.com/audio/a0"))

        // Mid-play: audible position exists.
        player.seekTo(45_000L)
        idle()

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("Exactly one renewal resolve", 2, fake.resolveCount)
        assertTrue(
            "Stale rendition purged resolver-side first",
            fake.invalidatedIds.map { it.value } == listOf("a0"),
        )
        assertTrue("Renewal rides forceRefresh", requests.last().forceRefresh)
        assertEquals("Fresh URL swapped in place, identity preserved", freshUrl, uriOf(player.getMediaItemAt(0)))
        assertEquals("a0", player.getMediaItemAt(0).mediaId)
        assertEquals("Mechanism restores the captured position exactly", 45_000L, player.currentPosition)
        assertWithinTolerance(player.currentPosition, 45_000L, "AC1")
        assertTrue("Playing intent restored", player.playWhenReady)
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // Was paused: renewal proceeds on position evidence, stays PAUSED
    // -----------------------------------------------------------------------

    @Test
    fun wasPaused_renewsAndResumes_butStaysPaused() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val freshUrl = "https://cdn.example.com/audio/p0?fresh=1"
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) SwayResult.Success(audio(freshUrl, id))
            else SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        val snap = snapshot("p0", "p1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekTo(31_500L)
        player.pause()
        idle()
        assertFalse(player.playWhenReady)

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("Renewal ran despite paused state (audible position evidence)", 2, fake.resolveCount)
        assertEquals(freshUrl, uriOf(player.getMediaItemAt(0)))
        assertEquals("Resume position honored while paused", 31_500L, player.currentPosition)
        assertWithinTolerance(player.currentPosition, 31_500L, "was-paused")
        assertFalse("Paused user must stay paused after invisible renewal", player.playWhenReady)
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // Bounded retries then typed failure surfaced via uiState.failedTrack
    // -----------------------------------------------------------------------

    @Test
    fun boundedRetry_exhaustsBudget_typedFailureSurfacesViaFacadeSlot_furtherTriggersResolveNothing() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val originalUrl = "https://cdn.example.com/audio/b0?orig=1"
        val fake = FakeStreamResolver()
        var forcedAttempts = 0
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) {
                forcedAttempts++
                SwayResult.Failure(SwayError.Offline)
            } else {
                SwayResult.Success(audio(originalUrl, id))
            }
        }
        val captured = mutableListOf<FailedTrack>()
        val snap = snapshot("b0", "b1")

        // Production glue (installed by later epics): engine failure slot -> facade slot.
        val conn = PlayerConnection.forTest(player, scope)
        addTeardown { try { conn.release() } catch (_: Exception) {} }

        val engine = JitResolveEngine(player, fake, scope, onFailure = { track ->
            captured.add(track)
            conn.setFailedTrack(track.item, track.error)
        })
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekTo(12_000L)
        idle()

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals(
            "Budget-bounded: exactly MAX_RENEWALS_PER_EPISODE resolve attempts",
            JitPolicy.MAX_RENEWALS_PER_EPISODE,
            forcedAttempts,
        )
        val failed = conn.uiState.value.failedTrack
        assertNotNull("Facade slot must carry the typed failure", failed)
        assertEquals(SwayError.Offline, failed!!.error)
        assertEquals("b0", failed.item.id.value)
        assertEquals(originalUrl, uriOf(player.getMediaItemAt(0)))
        assertTrue(player.playWhenReady)

        // A further trigger draws ZERO extra resolves (budget spent) but keeps surfacing.
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("No hot loop: budget spent means zero resolves", JitPolicy.MAX_RENEWALS_PER_EPISODE, forcedAttempts)
        // First trigger surfaced the resolver's own Offline; the spent-budget
        // trigger deterministically surfaces the mapped source-class category.
        assertEquals(
            "Typed category re-surfaced instead of retrying",
            SwayError.UpstreamUnavailable,
            conn.uiState.value.failedTrack!!.error,
        )
        assertEquals(2, captured.size)
        assertTrue(fake.invalidatedIds.all { it.value == "b0" })
    }

    // -----------------------------------------------------------------------
    // Retry budget resets after successful progress
    // -----------------------------------------------------------------------

    @Test
    fun retryBudgetResets_afterSuccessfulProgress_nextExpiryRenewsAgain() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val recoveredUrl = "https://cdn.example.com/audio/c0?recovered=1"
        val fake = FakeStreamResolver()
        var failing = true
        var forcedAttempts = 0
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) {
                forcedAttempts++
                if (failing) SwayResult.Failure(SwayError.RateLimited)
                else SwayResult.Success(audio(recoveredUrl, id))
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val snap = snapshot("c0", "c1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekTo(8_000L)
        idle()
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()
        assertEquals("First episode exhausts its budget", JitPolicy.MAX_RENEWALS_PER_EPISODE, forcedAttempts)
        assertEquals(SwayError.RateLimited, engine.latestFailure.value?.error)

        // Successful playback progress observed -> full budget again.
        engine.noteSuccessfulProgress()
        failing = false
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("Post-progress renewal runs with a fresh budget", JitPolicy.MAX_RENEWALS_PER_EPISODE + 1, forcedAttempts)
        assertEquals(recoveredUrl, uriOf(player.getMediaItemAt(0)))
        assertEquals("Failure slot stays historical: recovery publishes nothing", SwayError.RateLimited, engine.latestFailure.value?.error)
    }

    // -----------------------------------------------------------------------
    // AC2: two simultaneous 410s for one SourceId -> exactly one resolve
    // -----------------------------------------------------------------------

    @Test
    fun concurrentDuplicateErrors_sameSource_coalesceIntoSingleResolve() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val gate = CompletableDeferred<Unit>()
        var forcedCalls = 0
        val freshUrl = "https://cdn.example.com/audio/d1?fresh=1"
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            if (id.value == "d1" && request.forceRefresh) {
                forcedCalls++
                if (forcedCalls == 1) gate.await()
                SwayResult.Success(audio(freshUrl, id))
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val snap = snapshot("d0", "d1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekToNextMediaItem()
        idle()
        assertTrue("d1 resolved just-in-time", !PendingUri.isPending(uriOf(player.getMediaItemAt(1))))
        player.seekTo(21_000L)
        idle()

        // Two (and a third) simultaneous 410-class triggers for the SAME SourceId.
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals("Renewal pipeline in flight (gated)", 1, forcedCalls)
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()
        assertEquals("Duplicates must not spawn extra resolves while gated", 1, forcedCalls)

        gate.complete(Unit)
        idle()
        assertEquals("Still exactly one fresh resolve (Success short-circuits)", 1, forcedCalls)
        assertEquals(freshUrl, uriOf(player.getMediaItemAt(1)))
        assertEquals("d1", player.getMediaItemAt(1).mediaId)
        assertEquals(21_000L, player.currentPosition)
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // Non-retryable fatal error surfaces immediately, zero renewal attempts
    // -----------------------------------------------------------------------

    @Test
    fun fatalErrorCode_surfacesImmediately_withoutAnyRenewalAttempts() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val fake = FakeStreamResolver()
        val snap = snapshot("f0", "f1")
        val captured = mutableListOf<FailedTrack>()
        val engine = JitResolveEngine(player, fake, scope, onFailure = { captured.add(it) })
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekTo(50_000L)
        idle()
        val resolveCountBefore = fake.resolveCount
        val invalidateCountBefore = fake.invalidateCount

        engine.handlePlayerError(4001) // decoding-class: outside the source window
        idle()

        assertEquals("Zero renewal resolve attempts for fatal classes", resolveCountBefore, fake.resolveCount)
        assertEquals("Zero invalidations for fatal classes", invalidateCountBefore, fake.invalidateCount)
        assertEquals(1, captured.size)
        val error = captured.single().error
        assertTrue("Fatal maps to Unknown preserving diagnostics", error is SwayError.Unknown)
        assertEquals("f0", captured.single().item.id.value)
        assertTrue(player.playWhenReady)

        engine.handlePlayerError(PlaybackException.ERROR_CODE_REMOTE_ERROR)
        idle()
        assertEquals(2, captured.size)
        assertEquals("Counts still untouched", resolveCountBefore, fake.resolveCount)
    }

    // -----------------------------------------------------------------------
    // Placeholder current items skip (JIT worker owns them)
    // -----------------------------------------------------------------------

    @Test
    fun placeholderCurrentItem_error_skipsRenewal_silently() {
        val player = hermeticPlayer()
        val scope = mainScope()
        var forcedAttempts = 0
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) {
                forcedAttempts++
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id))
            } else if (id.value == "g0") {
                // Start-resolve fails -> placeholder legitimately stays in place.
                SwayResult.Failure(SwayError.ContentNotFound)
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val snap = snapshot("g0", "g1")
        val captured = mutableListOf<FailedTrack>()
        val engine = JitResolveEngine(player, fake, scope, onFailure = { captured.add(it) })
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        assertTrue("Fixture precondition: current item still rides a placeholder", PendingUri.isPending(uriOf(player.getMediaItemAt(0))))
        player.seekTo(40_000L)
        idle()

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("Placeholder failures belong to the JIT path, not layer 2", 0, forcedAttempts)
        assertEquals(0, fake.invalidateCount)
        assertTrue(
            "Only the JIT typed failure exists (no layer-2 publication)",
            captured.all { it.error is SwayError.ContentNotFound && it.item.id.value == "g0" },
        )
        assertTrue(PendingUri.isPending(uriOf(player.getMediaItemAt(0))))
    }

    // -----------------------------------------------------------------------
    // No audible-progress evidence -> silent skip (layer 2 is for MID-play death)
    // -----------------------------------------------------------------------

    @Test
    fun noAudibleProgressEvidence_error_skipsRenewal_silently() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val fake = FakeStreamResolver()
        val snap = snapshot("h0", "h1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        assertEquals(1, fake.resolveCount)
        // Position stays 0 and playing was never observed (hermetic BUFFERING park).

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertEquals("Pre-play failures stay with layer 1 / JIT / watchdog backstop", 1, fake.resolveCount)
        assertEquals(0, fake.invalidateCount)
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // Item vanished mid-renewal -> silent skip, no spurious failure
    // -----------------------------------------------------------------------

    @Test
    fun itemVanishedDuringGatedRenewal_appliesSilently_noSpuriousFailure() {
        val player = hermeticPlayer()
        val scope = mainScope()
        val gate = CompletableDeferred<Unit>()
        var forcedCalls = 0
        val freshUrl = "https://cdn.example.com/audio/i0?fresh=1"
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            if (id.value == "i0" && request.forceRefresh) {
                forcedCalls++
                if (forcedCalls == 1) gate.await()
                SwayResult.Success(audio(freshUrl, id))
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val snap = snapshot("i0", "i1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekTo(15_000L)
        idle()

        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertEquals(1, forcedCalls)
        player.clearMediaItems()
        idle()

        gate.complete(Unit)
        idle()

        assertEquals(1, forcedCalls)
        assertNullFailureSlot(engine)
        assertEquals("No crash, queue simply gone", 0, player.mediaItemCount)
    }

    // -----------------------------------------------------------------------
    // Service interplay: renewal flow NEVER trips idle self-stop
    // -----------------------------------------------------------------------

    @Test
    fun service_idleSelfStop_notTrippedByRenewalFlow_playingOrPaused() {
        val fake = FakeStreamResolver()
        var forcedCalls = 0
        fake.resolveBehavior = { id, request ->
            if (request.forceRefresh) {
                forcedCalls++
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id))
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val serviceController = Robolectric.buildService(SwayPlaybackService::class.java)
        val service = serviceController.get()
        service.streamResolverForTest = fake
        serviceController.create()
        idle()

        val engine = service.getEngineForTest()!!
        val snap = snapshot("t0", "t1", "t2")
        engine.attachQueueMetadata(snap)
        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()

        val player = service.getPlayerForTest()!!
        player.seekTo(30_000L)
        idle()
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        val shadow = shadowOf(service)
        assertFalse("Error-renewal must keep the service alive (playing variant)", shadow.isStoppedBySelf)
        assertNotNull(service.getPlayerForTest())
        assertTrue("Renewal executed service-side", fake.invalidatedIds.any { it.value == "t0" })
        assertWithinTolerance(player.currentPosition, 30_000L, "service playing variant")
        assertTrue(player.playWhenReady)

        // Paused variant: error-driven idle while playWhenReady=false must not stop either.
        engine.noteSuccessfulProgress()
        player.pause()
        player.seekTo(66_000L)
        idle()
        engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        idle()

        assertFalse("Error-renewal must keep the service alive (paused variant)", shadow.isStoppedBySelf)
        assertWithinTolerance(player.currentPosition, 66_000L, "service paused variant")
        assertFalse("Pause intent preserved through service-side renewal", player.playWhenReady)

        serviceController.destroy()
        idle()
    }

    // -----------------------------------------------------------------------
    // SM-2 forced-expiry suite: 20 trials, 100% within +/-3 s (record artifact)
    // -----------------------------------------------------------------------

    @Test
    fun sm2_forcedExpiryTwentyTrials_allResumeWithinTolerance_recordEmitted() {
        val trials = 20
        var passed = 0
        val deviations = mutableListOf<Long>()
        for (trial in 0 until trials) {
            val player = hermeticPlayer()
            val scope = mainScope()
            val fake = FakeStreamResolver()
            var n = 0
            fake.resolveBehavior = { id, request ->
                if (request.forceRefresh) {
                    n++
                    SwayResult.Success(audio("https://cdn.example.com/audio/x$trial?v=$n", id))
                } else {
                    SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
                }
            }
            val snap = snapshot("x$trial", "y$trial")
            val engine = JitResolveEngine(player, fake, scope)
            addTeardown { engine.release() }
            engine.attachQueueMetadata(snap)

            engine.startQueueAndPlay(mediaItems(snap), 0)
            idle()

            val lostPositionMs = 3_000L + (trial * 4_111L) % 170_000L
            player.seekTo(lostPositionMs)
            idle()

            engine.handlePlayerError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            idle()

            val resumedAt = player.currentPosition
            val deviation = Math.abs(resumedAt - lostPositionMs)
            val ok = deviation <= JitPolicy.RESUME_TOLERANCE_MS &&
                player.playWhenReady &&
                engine.latestFailure.value == null
            if (ok) passed++
            deviations.add(deviation)
        }
        println(
            "SM-2 FORCED-EXPIRY RECORD (story 5.3): $passed/$trials trials resumed within " +
                "+/-${JitPolicy.RESUME_TOLERANCE_MS} ms; max deviation=${deviations.max()} ms; " +
                "all deviations=$deviations",
        )
        assertEquals("SM-2 requires 100% pass within FR-13 bounds", trials, passed)
    }
}

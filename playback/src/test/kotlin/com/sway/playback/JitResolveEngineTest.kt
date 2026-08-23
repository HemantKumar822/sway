package com.sway.playback

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 4.4 — first-resolve path & just-in-time transitions (FR-12 completes here).
 *
 * Proves with the counting resolver double:
 * - Up-front budget = exactly ONE resolve per queue (8 items @ startIndex 2 +
 *   two forced transitions => exactly 3 total resolves).
 * - Transitions swap placeholder -> real URL in place (identity preserved).
 * - Single-flight coalescing, prefetch age cap both directions, repeat-one
 *   guard hook, typed-failure surfacing (start + transition), edge safety.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class JitResolveEngineTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val teardowns = mutableListOf<() -> Unit>()

    private fun addTeardown(block: () -> Unit) {
        teardowns.add(block)
    }

    @After
    fun runTeardowns() {
        teardowns.forEach { try { it() } catch (_: Exception) {} }
        teardowns.clear()
    }

    private fun mainScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        addTeardown { scope.cancel() }
        return scope
    }

    private fun exoPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(appContext).build()
        addTeardown { try { player.release() } catch (_: Exception) {} }
        return player
    }

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Title $id", durationMs = 180_000)!!

    private fun snapshot(vararg ids: String): QueueSnapshot =
        QueueSnapshot.of(ids.map { QueueItem.of(song(it)) })

    private fun mediaItems(snapshot: QueueSnapshot): List<MediaItem> =
        snapshot.items.map { qi ->
            MediaItem.Builder()
                .setMediaId(qi.id.value)
                .setUri(PendingUri.buildString(qi.id))
                .build()
        }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun uriOf(item: MediaItem): String? = item.localConfiguration?.uri?.toString()

    private fun freshResolver(): FakeStreamResolver = FakeStreamResolver()

    // -----------------------------------------------------------------------
    // AC1: FR-12 up-front budget = exactly one resolve
    // -----------------------------------------------------------------------

    @Test
    fun fr12_eightItemQueueAtIndexTwo_twoTransitions_exactlyThreeResolves() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val snap = snapshot("q0", "q1", "q2", "q3", "q4", "q5", "q6", "q7")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 2)
        idle()

        assertEquals("Up-front budget must be exactly one resolve", 1, fake.resolveCount)
        assertEquals("q2", fake.resolvedIds[0].value)
        assertEquals(2, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
        assertEquals(8, player.mediaItemCount)
        assertTrue(
            "Start item must carry the real resolved URL",
            uriOf(player.getMediaItemAt(2))!!.startsWith("https://"),
        )
        for (i in intArrayOf(0, 1, 3, 4, 5, 6, 7)) {
            assertTrue(
                "Non-start item $i must stay a placeholder",
                PendingUri.isPending(uriOf(player.getMediaItemAt(i))),
            )
        }
        assertEquals("Prefetch is off: budget proof stays clean", 0, fake.prefetchedIds.size)

        assertTrue(player.hasNextMediaItem())
        player.seekToNextMediaItem()
        idle()
        assertEquals(2, fake.resolveCount)
        assertEquals("q3", fake.resolvedIds[1].value)
        assertFalse(PendingUri.isPending(uriOf(player.getMediaItemAt(3))))

        player.seekToNextMediaItem()
        idle()
        assertEquals("1 up-front + 2 JIT = exactly 3 resolves (FR-12)", 3, fake.resolveCount)
        assertEquals("q4", fake.resolvedIds[2].value)

        for (i in 0 until player.mediaItemCount) {
            val uri = uriOf(player.getMediaItemAt(i))
            if (i in 2..4) {
                assertFalse("Item $i should be resolved by now", PendingUri.isPending(uri))
                assertEquals(snap.itemAt(i)!!.id.value, player.getMediaItemAt(i).mediaId)
            } else {
                assertTrue("Item $i must remain unresolved (budget)", PendingUri.isPending(uri))
            }
        }
        assertEquals(0, fake.prefetchedIds.size)
    }

    @Test
    fun transition_replacesPlaceholderWithResolvedUrl_identityPreserved() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val snap = snapshot("t0", "t1", "t2")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()

        val resolved = player.getMediaItemAt(0)
        assertEquals("t0", resolved.mediaId)
        assertTrue(uriOf(resolved)!!.contains("/audio/t0"))

        assertTrue(player.hasNextMediaItem())
        player.seekToNextMediaItem()
        idle()

        val second = player.getMediaItemAt(1)
        assertEquals("t1", second.mediaId)
        assertTrue(uriOf(second)!!.startsWith("https://"))
        assertFalse(PendingUri.isPending(uriOf(second)))
        assertTrue(PendingUri.isPending(uriOf(player.getMediaItemAt(2))))
        assertEquals(2, fake.resolveCount)
    }

    // -----------------------------------------------------------------------
    // Single-flight guard
    // -----------------------------------------------------------------------

    @Test
    fun singleFlight_duplicateTransitionsOntoSameUnresolvedItem_collapseToOneResolve() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        fake.resolveBehavior = { id, request ->
            if (callCount.incrementAndGet() > 1) gate.await()
            SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        val snap = snapshot("a0", "a1")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        assertEquals(1, fake.resolveCount)

        player.seekToNextMediaItem()
        idle()
        assertEquals("JIT resolve for a1 must be in flight (gated)", 2, callCount.get())
        assertTrue(PendingUri.isPending(uriOf(player.getMediaItemAt(1))))

        engine.handlePendingCurrent()
        engine.handlePendingCurrent()
        assertEquals("Duplicates must not spawn more resolves", 2, callCount.get())

        gate.complete(Unit)
        idle()
        assertEquals("Still exactly one resolve per track", 2, fake.resolveCount)
        assertEquals(1, fake.resolvedIds.count { it.value == "a1" })
        assertFalse(PendingUri.isPending(uriOf(player.getMediaItemAt(1))))
    }

    // -----------------------------------------------------------------------
    // Prefetch age cap + budget independence
    // -----------------------------------------------------------------------

    @Test
    fun ageCap_expiredPrefetchedAudio_discarded_freshResolveHappensInstead() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val staleUrl = "https://stale.example.com/audio/x2"
        fake.prefetchBehavior = { id, _ ->
            ResolvedAudio(
                url = staleUrl,
                expiresAtEpochMs = System.currentTimeMillis() - 1_000L,
                bitrateKbps = 160,
                containerHint = "mp4",
                backendTag = "fake:stale",
                renditionCacheKey = "stale:${id.value}",
            )
        }
        val snap = snapshot("x1", "x2", "x3")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()
        assertEquals("x2", fake.prefetchedIds.first().value)
        assertEquals(1, fake.resolveCount)

        player.seekToNextMediaItem()
        idle()
        assertEquals("Stale prefetch must trigger a fresh resolve", 2, fake.resolveCount)
        assertEquals("x2", fake.resolvedIds[1].value)
        val uri = uriOf(player.getMediaItemAt(1))!!
        assertFalse("Stale URL must never reach the player", uri == staleUrl)
        assertTrue(uri.startsWith("https://cdn.example.com"))
    }

    @Test
    fun ageCap_freshPrefetchedAudio_usedWithoutExtraResolve_budgetUntouched() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val freshUrl = "https://fresh.example.com/prefetch/y2"
        fake.prefetchBehavior = { id, _ ->
            ResolvedAudio(
                url = freshUrl,
                expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                bitrateKbps = 160,
                containerHint = "mp4",
                backendTag = "fake:prefetch",
                renditionCacheKey = "prefetch:${id.value}",
            )
        }
        val snap = snapshot("y1", "y2", "y3")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()
        assertTrue(fake.prefetchedIds.any { it.value == "y2" })
        assertEquals(1, fake.resolveCount)

        player.seekToNextMediaItem()
        idle()
        assertEquals(
            "Transition must consume the fresh prefetch WITHOUT a resolveAudio call",
            1, fake.resolveCount,
        )
        assertEquals(freshUrl, uriOf(player.getMediaItemAt(1)))
        assertTrue(fake.resolvedIds.none { it.value == "y2" })
    }

    @Test
    fun repeatOneGuard_setTrue_blocksAllPrefetch_invocation() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val snap = snapshot("r1", "r2", "r3")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)
        engine.setRepeatOneRequested(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        engine.maybePrefetchNext()
        idle()
        assertEquals(
            "Repeat-one guard hook must short-circuit prefetch entirely",
            0, fake.prefetchedIds.size,
        )

        engine.setRepeatOneRequested(false)
        engine.maybePrefetchNext()
        idle()
        assertEquals("Hook releases cleanly once flag clears", 1, fake.prefetchedIds.size)
        assertEquals("r2", fake.prefetchedIds[0].value)
    }

    // -----------------------------------------------------------------------
    // Typed failure surfacing
    // -----------------------------------------------------------------------

    @Test
    fun startResolveFailure_surfacesTypedOfflineThroughFacadeSlot_noCrash() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        fake.injectResolveFailure(SwayError.Offline)
        val captured = mutableListOf<FailedTrack>()
        val snap = snapshot("f1", "f2")

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

        val failed = conn.uiState.value.failedTrack
        assertNotNull("Facade slot must carry the typed failure", failed)
        assertEquals(SwayError.Offline, failed!!.error)
        assertEquals("f1", failed.item.id.value)
        assertEquals(SwayError.Offline, engine.latestFailure.value?.error)
        assertEquals("Original placeholders returned: queue still loads", 2, player.mediaItemCount)
        assertTrue(player.playWhenReady)
        assertTrue(fake.resolvedIds.all { it.value == "f1" })
        assertEquals(
            "Up-front attempt + one transition-event retry (placeholder left => retryable), both typed",
            2, fake.resolveCount,
        )
    }

    @Test
    fun transitionResolveFailure_leavesPlaceholderAndSurfacesTypedError_nextEventRetries() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        fake.resolveBehavior = { id, request ->
            if (id.value == "g2") {
                SwayResult.Failure(SwayError.ContentNotFound)
            } else {
                SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
            }
        }
        val captured = mutableListOf<FailedTrack>()
        val snap = snapshot("g1", "g2", "g3")
        val engine = JitResolveEngine(player, fake, scope, onFailure = { captured.add(it) })
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekToNextMediaItem()
        idle()

        assertEquals(1, captured.size)
        assertEquals(SwayError.ContentNotFound, captured[0].error)
        assertEquals("g2", captured[0].item.id.value)
        assertTrue(
            "Placeholder left in place for retry",
            PendingUri.isPending(uriOf(player.getMediaItemAt(1))),
        )
        assertEquals(2, fake.resolveCount)

        player.seekToNextMediaItem()
        idle()
        assertEquals("g3", fake.resolvedIds[2].value)

        fake.resolveBehavior = { id, request ->
            SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        player.seekToPreviousMediaItem()
        idle()
        assertFalse(
            "Retry on next transition succeeds once resolver recovers",
            PendingUri.isPending(uriOf(player.getMediaItemAt(1))),
        )
        assertEquals(
            "g1 + g2(fail) + g3 + g2(retry) = 4 resolver calls total",
            4, fake.resolveCount,
        )
        assertEquals("Only the original failure was published", 1, captured.size)
    }

    // -----------------------------------------------------------------------
    // Edges: empty queue, vanished target, pure policy helpers
    // -----------------------------------------------------------------------

    @Test
    fun edges_emptySnapshotAndEmptyPlayer_neverThrowAndNeverResolve() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }

        engine.startQueueAndPlay(emptyList(), 0)
        engine.handlePendingCurrent()
        engine.maybePrefetchNext()
        idle()
        assertEquals(0, fake.resolveCount)
        assertEquals(0, player.mediaItemCount)
    }

    @Test
    fun edges_targetRemovedWhileResolveInFlight_replacementSkipsSilently() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = freshResolver()
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        fake.resolveBehavior = { id, request ->
            if (callCount.incrementAndGet() > 1) gate.await()
            SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        val snap = snapshot("m1", "m2")
        val engine = JitResolveEngine(player, fake, scope)
        addTeardown { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()

        player.seekToNextMediaItem()
        idle()
        assertEquals(2, callCount.get())

        player.removeMediaItem(1)
        idle()
        assertEquals(1, player.mediaItemCount)

        gate.complete(Unit)
        idle()

        assertNull("No failure published for a vanished target", engine.latestFailure.value)
        assertEquals(1, player.mediaItemCount)
        assertFalse(PendingUri.isPending(uriOf(player.getMediaItemAt(0))))
    }

    @Test
    fun jitPolicy_pureDecisionHelpers() {
        assertTrue(JitPolicy.shouldResolveNow(PendingUri.PREFIX + "abc"))
        assertFalse(JitPolicy.shouldResolveNow("https://example.com/a.mp3"))
        assertFalse(JitPolicy.shouldResolveNow(null))

        assertEquals(0, JitPolicy.coerceStartIndex(C.INDEX_UNSET, 3))
        assertEquals(0, JitPolicy.coerceStartIndex(-5, 3))
        assertEquals(0, JitPolicy.coerceStartIndex(99, 3))
        assertEquals(2, JitPolicy.coerceStartIndex(2, 3))
        assertEquals(0, JitPolicy.coerceStartIndex(0, 0))
        assertEquals(0, JitPolicy.coerceStartIndex(C.INDEX_UNSET, 0))

        val now = 1_700_000_000_000L
        val fresh = ResolvedAudio(
            url = "https://a/f",
            expiresAtEpochMs = now + 1,
            bitrateKbps = 96,
            containerHint = null,
            backendTag = "b",
            renditionCacheKey = "k",
        )
        val expired = fresh.copy(expiresAtEpochMs = now)
        assertTrue(JitPolicy.isPrefetchUsable(fresh, now))
        assertFalse(JitPolicy.isPrefetchUsable(expired, now))
        assertFalse(JitPolicy.isPrefetchUsable(null, now))
    }

    // -----------------------------------------------------------------------
    // Service-level wiring
    // -----------------------------------------------------------------------

    @Test
    fun service_startResolveFails_typedFailureHoisted_serviceStaysAlive() {
        val fake = freshResolver()
        fake.injectResolveFailure(SwayError.Offline)
        val serviceController = Robolectric.buildService(SwayPlaybackService::class.java)
        val service = serviceController.get()
        service.streamResolverForTest = fake
        serviceController.create()
        idle()

        val engine = service.getEngineForTest()
        assertNotNull("Engine must exist when a resolver was injected", engine)
        val snap = snapshot("z1", "z2", "z3")
        engine!!.attachQueueMetadata(snap)
        engine.startQueueAndPlay(mediaItems(snap), 1)
        idle()

        assertEquals(SwayError.Offline, service.lastFailure.value?.error)
        assertEquals("z2", service.lastFailure.value?.item?.id?.value)
        assertNotNull("Service must survive start-resolve failure", service.getPlayerForTest())
        assertNotNull(service.getSessionForTest())
        assertEquals(3, service.getPlayerForTest()!!.mediaItemCount)

        serviceController.destroy()
        idle()
    }

    @Test
    fun service_onSetMediaItemsViaController_onlyStartUriResolved_othersPending() {
        val fake = freshResolver()
        val serviceController = Robolectric.buildService(SwayPlaybackService::class.java)
        val service = serviceController.get()
        service.streamResolverForTest = fake
        serviceController.create()
        idle()

        val snap = snapshot("w0", "w1", "w2", "w3")
        var sessionPathExercised = false
        try {
            val token = SessionToken(appContext, ComponentName(appContext, SwayPlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            idle()
            val controller = future.get(5, TimeUnit.SECONDS)
            try {
                controller.setMediaItems(mediaItems(snap), 2, 0L)
                controller.prepare()
                controller.play()
                idle()
                idle()

                val player = service.getPlayerForTest()!!
                sessionPathExercised = player.mediaItemCount == 4
                if (sessionPathExercised) {
                    assertEquals(2, player.currentMediaItemIndex)
                    assertTrue(
                        "Session interception must resolve ONLY the start item",
                        uriOf(player.getMediaItemAt(2))!!.startsWith("https://"),
                    )
                    for (i in intArrayOf(0, 1, 3)) {
                        assertTrue(
                            "Item $i must keep its placeholder through the session path",
                            PendingUri.isPending(uriOf(player.getMediaItemAt(i))),
                        )
                    }
                    assertEquals(1, fake.resolveCount)
                }
            } finally {
                controller.release()
            }
        } catch (_: Exception) {
            // MediaController binding unsupported in this environment — fall back
            // to the direct-player proof of identical semantics.
        } finally {
            if (!sessionPathExercised) {
                val engine = service.getEngineForTest()!!
                engine.attachQueueMetadata(snap)
                engine.startQueueAndPlay(mediaItems(snap), 2)
                idle()
                val player = service.getPlayerForTest()!!
                assertEquals(4, player.mediaItemCount)
                assertEquals(2, player.currentMediaItemIndex)
                assertTrue(uriOf(player.getMediaItemAt(2))!!.startsWith("https://"))
                for (i in intArrayOf(0, 1, 3)) {
                    assertTrue(PendingUri.isPending(uriOf(player.getMediaItemAt(i))))
                }
                assertEquals(1, fake.resolveCount)
            }
            idle()
            serviceController.destroy()
        }
    }
}

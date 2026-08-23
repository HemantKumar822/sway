package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Story 5.2 — read-time validation layer (AD-7 defense layer 1, NFR-3, FR-13
 * read-time clause).
 *
 * Boundary-table tests (margin minus/plus/exactly) via doubles: any held or
 * freshly resolved URL with <= [JitPolicy.READ_MARGIN_MS] lifetime left at the
 * moment of use is discarded and renewed (invalidate + forceRefresh resolve)
 * BEFORE play; the former prefetch age cap is the SAME single check — no
 * second mechanism exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReadTimeValidationTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val teardowns = mutableListOf<() -> Unit>()

    @After
    fun runTeardowns() {
        teardowns.forEach { try { it() } catch (_: Exception) {} }
        teardowns.clear()
    }

    private fun mainScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        teardowns.add { scope.cancel() }
        return scope
    }

    private fun exoPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(appContext).build()
        teardowns.add { try { player.release() } catch (_: Exception) {} }
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

    /** ResolvedAudio expiring exactly [msFromNow] from the REAL clock. */
    private fun audioExpiringIn(id: SourceId, msFromNow: Long, url: String): ResolvedAudio =
        ResolvedAudio(
            url = url,
            expiresAtEpochMs = System.currentTimeMillis() + msFromNow,
            bitrateKbps = 160,
            containerHint = "mp4",
            backendTag = "fake:readtime",
            renditionCacheKey = "rt:${id.value}",
        )

    /** Counting per-id resolve double returning the healthy 60-min default. */
    private fun countingResolver(
        requests: MutableList<AudioRequest> = mutableListOf(),
        overrides: (SourceId, Int) -> SwayResult<ResolvedAudio>? = { _, _ -> null },
    ): FakeStreamResolver {
        val calls = mutableMapOf<String, Int>()
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
            val n = (calls[id.value] ?: 0) + 1
            calls[id.value] = n
            requests += request
            overrides(id, n) ?: SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        return fake
    }

    // -----------------------------------------------------------------------
    // AC1: prefetched URL expiring in 4 min -> discarded + fresh resolve BEFORE play
    // -----------------------------------------------------------------------

    @Test
    fun prefetchedUrlExpiringInFourMinutes_discarded_renewedInvalidatePlusForceRefresh_beforePlay() {
        val player = exoPlayer()
        val scope = mainScope()
        val requests = mutableListOf<AudioRequest>()
        val fake = countingResolver(requests)
        val dyingUrl = "https://dying.example.com/audio/v1"
        val fourMinutes = 4L * 60L * 1000L
        fake.prefetchBehavior = { id, _ -> audioExpiringIn(id, fourMinutes, dyingUrl) }
        val snap = snapshot("v0", "v1", "v2")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        assertEquals(1, fake.resolveCount)
        engine.maybePrefetchNext()
        idle()
        assertEquals("v1", fake.prefetchedIds.first().value)

        player.seekToNextMediaItem()
        idle()

        assertEquals("Dying prefetch must be replaced by a fresh resolve", 2, fake.resolveCount)
        assertTrue(
            "Renewal must purge resolver-side state first",
            fake.invalidatedIds.any { it.value == "v1" },
        )
        assertTrue(
            "Renewal must ride the forceRefresh path",
            requests.last().forceRefresh,
        )
        assertFalse("Stale cache entry must NOT trigger a non-forced resolve", requests.dropLast(1).any { it.forceRefresh })
        val uri = uriOf(player.getMediaItemAt(1))!!
        assertFalse("URL expiring in <5 min must never reach the player", uri == dyingUrl)
        assertTrue(uri.startsWith("https://cdn.example.com"))
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // AC2: URL expiring in 10 min -> consumed as-is without re-resolve
    // -----------------------------------------------------------------------

    @Test
    fun prefetchedUrlExpiringInTenMinutes_consumedWithoutAnyReresolve() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = countingResolver()
        val tenMinutes = 10L * 60L * 1000L
        val healthyUrl = "https://healthy.example.com/audio/w1"
        fake.prefetchBehavior = { id, _ -> audioExpiringIn(id, tenMinutes, healthyUrl) }
        val snap = snapshot("w0", "w1", "w2")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()

        player.seekToNextMediaItem()
        idle()

        assertEquals("Margin-plus URL is consumed as-is", 1, fake.resolveCount)
        assertTrue(fake.resolvedIds.none { it.value == "w1" })
        assertEquals(healthyUrl, uriOf(player.getMediaItemAt(1)))
    }

    // -----------------------------------------------------------------------
    // Boundary via doubles: exactly at margin => renewed (inclusive law)
    // -----------------------------------------------------------------------

    @Test
    fun boundary_prefetchedExactlyAtMargin_renewed() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = countingResolver()
        val marginalUrl = "https://marginal.example.com/audio/b1"
        fake.prefetchBehavior = { id, _ ->
            audioExpiringIn(id, JitPolicy.READ_MARGIN_MS, marginalUrl)
        }
        val snap = snapshot("b0", "b1", "b2")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()

        player.seekToNextMediaItem()
        idle()

        assertEquals("Exactly-at-margin entry sits on the expired side of the law", 2, fake.resolveCount)
        assertTrue(fake.invalidatedIds.any { it.value == "b1" })
        assertFalse(uriOf(player.getMediaItemAt(1)) == marginalUrl)
    }

    // -----------------------------------------------------------------------
    // Renewal failure: strict discard, typed failure, placeholder retryable
    // -----------------------------------------------------------------------

    @Test
    fun renewalResolveFails_strictDiscard_typedFailure_placeholderLeftRetryable() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = countingResolver { id, _ ->
            if (id.value == "r1") SwayResult.Failure(SwayError.Offline) else null
        }
        val dyingUrl = "https://dying.example.com/audio/r1"
        fake.prefetchBehavior = { id, _ ->
            audioExpiringIn(id, 4L * 60L * 1000L, dyingUrl)
        }
        val captured = mutableListOf<FailedTrack>()
        val snap = snapshot("r0", "r1", "r2")
        val engine = JitResolveEngine(player, fake, scope, onFailure = { captured.add(it) })
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()
        assertEquals(1, fake.resolveCount)

        player.seekToNextMediaItem()
        idle()

        assertEquals("One renewal attempt was made", 2, fake.resolveCount)
        assertEquals(SwayError.Offline, engine.latestFailure.value?.error)
        assertEquals("r1", captured.last().item.id.value)
        assertTrue("Placeholder left for a later transition to retry", PendingUri.isPending(uriOf(player.getMediaItemAt(1))))
        assertFalse("Rejected rendition must never fall back onto the player", uriOf(player.getMediaItemAt(1)) == dyingUrl)
    }

    // -----------------------------------------------------------------------
    // Single flight survives revalidation
    // -----------------------------------------------------------------------

    @Test
    fun duplicatesDuringGatedRenewal_collapseToOneResolve_singleFlightIntact() {
        val player = exoPlayer()
        val scope = mainScope()
        val gate = CompletableDeferred<Unit>()
        var u1Calls = 0
        val fake = FakeStreamResolver()
        val dyingUrl = "https://dying.example.com/audio/u1"
        fake.prefetchBehavior = { id, _ -> audioExpiringIn(id, -1_000L, dyingUrl) }
        fake.resolveBehavior = { id, request ->
            if (id.value == "u1") {
                u1Calls++
                if (u1Calls == 1) gate.await()
            }
            SwayResult.Success(FakeStreamResolver.fakeResolvedAudio(id, request.quality))
        }
        val snap = snapshot("u0", "u1")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()

        player.seekToNextMediaItem()
        idle()
        assertEquals("Renewal resolve in flight (gated)", 1, u1Calls)
        assertTrue(PendingUri.isPending(uriOf(player.getMediaItemAt(1))))

        engine.handlePendingCurrent()
        engine.handlePendingCurrent()
        assertEquals("Duplicates must not spawn extra revalidations", 1, u1Calls)

        gate.complete(Unit)
        idle()
        assertEquals("Still exactly one renewal resolve", 1, u1Calls)
        assertFalse(PendingUri.isPending(uriOf(player.getMediaItemAt(1))))
        assertFalse(uriOf(player.getMediaItemAt(1)) == dyingUrl)
    }

    // -----------------------------------------------------------------------
    // Fresh-but-marginal results: one bounded forced revalidation
    // -----------------------------------------------------------------------

    @Test
    fun freshMarginalResult_oneBoundedForcedRevalidation_recoversLongLivedUrl() {
        val player = exoPlayer()
        val scope = mainScope()
        val marginalUrl = "https://marginal.example.com/audio/c1?n=1"
        val fake = countingResolver { id, n ->
            if (id.value == "c1" && n == 1) {
                SwayResult.Success(audioExpiringIn(id, 2L * 60L * 1000L, marginalUrl))
            } else {
                null
            }
        }
        val snap = snapshot("c0", "c1")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekToNextMediaItem()
        idle()

        assertEquals("c0 + c1(marginal) + c1(forced refresh)", 3, fake.resolveCount)
        val uri = uriOf(player.getMediaItemAt(1))!!
        assertFalse("Marginal first answer must be superseded", uri == marginalUrl)
        assertTrue(uri.startsWith("https://cdn.example.com"))
        assertNullFailureSlot(engine)
    }

    @Test
    fun alwaysMarginal_boundedAtTwoAttempts_bestEffortSecondAnswerPlays_noHotLoop() {
        val player = exoPlayer()
        val scope = mainScope()
        val fake = countingResolver { id, n ->
            if (id.value == "d1") {
                SwayResult.Success(audioExpiringIn(id, 2L * 60L * 1000L, "https://marginal.example.com/audio/d1?n=$n"))
            } else {
                null
            }
        }
        val snap = snapshot("d0", "d1")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        player.seekToNextMediaItem()
        idle()

        assertEquals("Revalidation is hard-bounded: exactly 2 attempts for d1", 3, fake.resolveCount)
        assertEquals(
            "Freshest audible-now answer plays instead of surfacing a failure",
            "https://marginal.example.com/audio/d1?n=2",
            uriOf(player.getMediaItemAt(1)),
        )
        assertNullFailureSlot(engine)
        assertTrue(player.playWhenReady)
    }

    // -----------------------------------------------------------------------
    // Up-front path shares the same single check
    // -----------------------------------------------------------------------

    @Test
    fun startSwap_marginalUpfrontResult_revalidatedBeforeQueueLoads() {
        val player = exoPlayer()
        val scope = mainScope()
        val marginalUrl = "https://marginal.example.com/audio/e0?n=1"
        val fake = countingResolver { id, n ->
            if (id.value == "e0" && n == 1) {
                SwayResult.Success(audioExpiringIn(id, 90_000L, marginalUrl))
            } else {
                null
            }
        }
        val snap = snapshot("e0", "e1")
        val engine = JitResolveEngine(player, fake, scope)
        teardowns.add { engine.release() }
        engine.attachQueueMetadata(snap)

        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()

        assertEquals("Start item's marginal answer revalidated once", 2, fake.resolveCount)
        val uri = uriOf(player.getMediaItemAt(0))!!
        assertFalse(uri == marginalUrl)
        assertTrue(uri.startsWith("https://cdn.example.com"))
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
        assertNullFailureSlot(engine)
    }

    // -----------------------------------------------------------------------
    // Service-level seam: margin law holds through the wired engine too
    // -----------------------------------------------------------------------

    @Test
    fun service_seam_renewsDyingPrefetchedEntry_throughWiredEngine() {
        val fake = countingResolver()
        val dyingUrl = "https://dying.example.com/audio/s1"
        fake.prefetchBehavior = { id, _ -> audioExpiringIn(id, 4L * 60L * 1000L, dyingUrl) }
        val serviceController = Robolectric.buildService(SwayPlaybackService::class.java)
        val service = serviceController.get()
        service.streamResolverForTest = fake
        serviceController.create()
        idle()

        val engine = service.getEngineForTest()!!
        val snap = snapshot("s0", "s1", "s2")
        engine.attachQueueMetadata(snap)
        engine.setPrefetchEnabled(true)
        engine.startQueueAndPlay(mediaItems(snap), 0)
        idle()
        engine.maybePrefetchNext()
        idle()
        assertEquals(1, fake.resolveCount)

        player_seekNext(service.getPlayerForTest()!!)
        idle()

        assertEquals("Read-time validation governs the production-wired engine too", 2, fake.resolveCount)
        assertTrue(fake.invalidatedIds.any { it.value == "s1" })
        assertFalse(uriOf(service.getPlayerForTest()!!.getMediaItemAt(1)) == dyingUrl)

        serviceController.destroy()
        idle()
    }

    private fun player_seekNext(player: Player) {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    private fun assertNullFailureSlot(engine: JitResolveEngine) {
        assertEquals("No typed failure expected on this path", null, engine.latestFailure.value)
    }
}

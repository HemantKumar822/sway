package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.Song
import com.sway.core.model.fake.FakeStreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.ceil

/**
 * Story 4.4 — first-audio timing harness, Robolectric interim evidence (FR-8).
 *
 * Measures the engine-level command->playing-ready latency across N runs of a
 * full 8-item queue started at index 2 through [JitResolveEngine]. Under
 * Robolectric nothing actually decodes, so the recorded metric is command
 * acceptance latency: from `startQueueAndPlay` issued until the player reports
 * playWhenReady with state >= BUFFERING. Samples and p95 are printed for the
 * evidence log; assertions use a generous CI-safe ceiling only.
 *
 * The REAL device tap-to-audio p95 (<= 3 s on Baseline Device profile) is owned
 * by the instrumented harness placeholder
 * `playback/src/androidTest/.../LiveTapToAudioSmokeTest.kt` (:catalog LiveSmoke
 * precedent) and completes via story 12.4.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FirstAudioTimingHarnessTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Title $id", durationMs = 180_000)!!

    private fun mediaItems(ids: List<String>): List<MediaItem> =
        ids.map { id ->
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(PendingUri.buildString(Song.create(id = id, rawTitle = "T$id")!!.id))
                .build()
        }

    @Test
    fun firstAudio_commandToPlayingReady_p95Recorded_withinCiSafeBound() {
        val runs = 20
        val samples = mutableListOf<Long>()
        repeat(runs) { run ->
            val serviceController = Robolectric.buildService(SwayPlaybackService::class.java)
            val service = serviceController.get()
            val fake = FakeStreamResolver()
            service.streamResolverForTest = fake
            serviceController.create()
            shadowOf(Looper.getMainLooper()).idle()

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val player = service.getPlayerForTest()!!
                val engine = service.getEngineForTest()!!
                val snap = QueueSnapshot.of(
                    (0 until 8).map { QueueItem.of(song("h$it")) },
                )
                engine.attachQueueMetadata(snap)

                shadowOf(Looper.getMainLooper()).idle()
                val startedAt = System.nanoTime()
                engine.startQueueAndPlay(mediaItems((0 until 8).map { "h$it" }), 2)

                val deadline = System.nanoTime() + 3_000_000_000L
                while (System.nanoTime() < deadline) {
                    val ready = try {
                        player.playWhenReady && player.playbackState >= Player.STATE_BUFFERING
                    } catch (_: Exception) {
                        false
                    }
                    if (ready) break
                    shadowOf(Looper.getMainLooper()).idle()
                }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                samples += elapsedMs
                assertTrue(
                    "Run $run exceeded even the generous CI ceiling: ${elapsedMs}ms",
                    elapsedMs <= 3_000,
                )
            } finally {
                scope.cancel()
                serviceController.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

        val sorted = samples.sorted()
        val p95Index = (ceil(0.95 * sorted.size)).toInt().coerceIn(1, sorted.size) - 1
        val p95 = sorted[p95Index]
        println("FR-8 interim harness: runs=$runs samples=$sorted")
        println("FR-8 interim harness: p50=${sorted[sorted.size / 2]}ms p95=${p95}ms (device evidence lands via 12.4 + LiveTapToAudioSmokeTest)")
        assertTrue(
            "p95 command->ready must stay far below the 3s FR-8 budget in CI-safe terms",
            p95 <= 2_500,
        )
    }
}

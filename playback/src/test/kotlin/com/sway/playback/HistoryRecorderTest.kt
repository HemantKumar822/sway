package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.data.HistoryRepository
import com.sway.core.data.QueueStateStore
import com.sway.core.data.StoredHistoryRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Story 8.3 — service-side recording trigger (FR-34/A-5, AR-5 rule 7):
 * 10 s CUMULATIVE played per episode, recorded exactly once, replays upsert
 * (recency refresh), abandoned-at-9 s never records, new episodes reset.
 *
 * Real ExoPlayer + silent-WAV renditions (proven harness): audible state is
 * genuine; the fake CLOCK drives [HistoryRecorder.tick] manually so no
 * wall-clock dependence. Storage is the contract-level in-memory fake — Room
 * machinery is :core:data/:core:database suite territory.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HistoryRecorderTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: InMemoryHistoryStore
    private lateinit var repo: HistoryRepository
    private lateinit var scope: CoroutineScope
    private lateinit var player: Player
    private var nowMs = 1_000L
    private val wavs = mutableMapOf<String, File>()

    @Before
    fun setUp() {
        store = InMemoryHistoryStore()
        repo = HistoryRepository(store) { nowMs }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build()
    }

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
        idle()
        wavs.values.forEach { it.delete() }
    }

    // --- AC3: abandoned at 9 s -> no record ---------------------------------

    @Test
    fun abandonedBeforeThreshold_neverRecords() {
        val r = startRecorder()
        loadAndPlay("s1")
        repeat(9) { r.tick(++nowMs) }   // 9 cumulative seconds of audible play
        player.pause(); idle()

        assertEquals("9 s must NOT record (10 s rule)", 0, recordedCount())
        r.release()
    }

    // --- AC1: three qualifying plays -> ONE entry, latest recency ------------

    @Test
    fun threeQualifyingPlays_oneEntry_latestRecency() {
        val r = startRecorder()

        repeat(3) { attempt ->
            loadAndPlay("s1")
            repeat(11) { r.tick(++nowMs) }   // >= 10 s cumulative => qualifies
            player.pause(); idle()
            if (attempt == 0) {
                assertEquals("first qualifying play records", 1, recordedCount())
            }
        }

        assertEquals("replays upsert, never stack", 1, recordedCount())
        assertTrue(recordedAt("s1")!! > 1_010L)
        r.release()
    }

    // --- cumulative law: pauses don't reset progress -------------------------

    @Test
    fun cumulativeAcrossPause_recordsOnce_notTwice() {
        loadAndPlay("s1")
        val r = startRecorder()

        repeat(6) { r.tick(++nowMs) }   // 6 s
        player.pause(); idle()
        repeat(5) { r.tick(++nowMs) }   // paused ticks: NOT counted
        player.play(); idle()
        repeat(5) { r.tick(++nowMs) }   // +5 s => 11 s cumulative crosses
        player.pause(); idle()

        assertEquals("cumulative 11 s records exactly once", 1, recordedCount())

        player.play(); idle()
        repeat(5) { r.tick(++nowMs) }   // same episode: no re-record
        assertEquals(1, recordedCount())
        r.release()
    }

    // --- new episode resets ---------------------------------------------------

    @Test
    fun newEpisode_resetsAccumulator_eachTrackEarnsItsOwnRecord() {
        val r = startRecorder()
        loadAndPlay("s1")
        repeat(10) { r.tick(++nowMs) }
        player.pause(); idle()
        assertEquals(1, recordedCount())

        loadAndPlay("s2")
        repeat(4) { r.tick(++nowMs) }   // only 4 s on the new track
        player.pause(); idle()

        assertEquals(1, recordedCount())
        assertNull(recordedAt("s2"))
        r.release()
    }

    // --- harness --------------------------------------------------------------

    private fun startRecorder(): HistoryRecorder =
        HistoryRecorder(player, scope, repo) { nowMs }.also { it.start(); idle() }

    /** Loads a REAL playable rendition under [id] and starts playing. */
    private fun loadAndPlay(id: String) {
        seedSnapshot(id)
        player.setMediaItem(
            MediaItem.Builder().setMediaId(id).setUri(wavFor(id)).build(),
        )
        player.prepare()
        player.play()
        idle()
        awaitUntil("READY+playing for $id") { player.isPlaying }
    }

    /** Snapshot seeding is a no-op against the in-memory store: rows carry truth. */
    private fun seedSnapshot(id: String) {
        // The recorder only needs mediaId + playable URI; the store's diary
        // row self-describes (title/rawTitle derived from id in the fake).
    }

    private fun wavFor(id: String): String {
        val file = wavs.getOrPut(id) { writeSilentWav("rec_$id") }
        return "file://" + file.absolutePath.replace('\\', '/')
    }

    private fun recordedCount(): Int = store.rows.size

    private fun recordedAt(id: String): Long? =
        store.rows.firstOrNull { it.sourceId == id }?.playedAt

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle(); Thread.sleep(20); idle()
        }
        idle()
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun writeSilentWav(prefix: String): File {
        val sampleRate = 8_000
        val dataSize = sampleRate * 90 * 2
        val file = File.createTempFile(prefix, ".wav")
        java.io.DataOutputStream(java.io.FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF"); out.writeIntLe(36 + dataSize); out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLe(16); out.writeShortLe(1); out.writeShortLe(1)
            out.writeIntLe(sampleRate); out.writeIntLe(sampleRate * 2); out.writeShortLe(2); out.writeShortLe(16)
            out.writeBytes("data"); out.writeIntLe(dataSize); out.write(ByteArray(dataSize))
        }
        file.deleteOnExit()
        return file
    }

    private fun java.io.DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}

/**
 * Contract-level in-memory history store: trim/cap semantics mirrored so the
 * recorder laws read truthfully; Room machinery stays in :core:data suites.
 */
private class InMemoryHistoryStore : com.sway.core.data.HistoryStore {
    val rows = mutableListOf<StoredHistoryRow>()
    val snapshots = mutableMapOf<String, StoredHistoryRow>()

    override suspend fun record(songId: String, playedAt: Long, cap: Int) {
        rows.removeAll { it.sourceId == songId }
        rows += StoredHistoryRow(
            sourceId = songId,
            title = "T$songId",
            rawTitle = "R$songId",
            artistName = null,
            durationMs = 60_000,
            artworkUrl = null,
            playedAt = playedAt,
        )
        while (rows.size > cap) rows.remove(rows.minByOrNull { it.playedAt })
    }

    override fun observeRecent(limit: Int) =
        kotlinx.coroutines.flow.flowOf(rows.sortedByDescending { it.playedAt }.take(limit))

    override suspend fun page(limit: Int, offset: Int): List<StoredHistoryRow> =
        rows.sortedByDescending { it.playedAt }.drop(offset).take(limit)
}

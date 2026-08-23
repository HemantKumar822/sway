package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.data.QueueStateSerializer
import com.sway.core.data.QueueStateStore
import com.sway.core.data.StoredQueueState
import com.sway.core.data.SessionRestoreRepository
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Story 7.3 — kill-and-relaunch session persistence, hermetically (FR-25):
 *
 *  - SAVE: the [SessionStateSaver] observes the player directly and keeps the
 *    singleton row fresh — including with ZERO controllers bound (background
 *    advance continuity, NFR-4 substrate).
 *  - RESTORE: a fresh stack lands the saved queue/index/position/modes PAUSED
 *    via the facade post-composition hook ([PlayerConnection.attachSessionStore]);
 *    auto-play is FORBIDDEN (predictability law).
 *  - FIRST RUN: absent/corrupt row => honest empty state, no session marker.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SessionPersistenceTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scope: CoroutineScope
    private lateinit var wavFile: File
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null
    private lateinit var store: SessionRestoreRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        shadowOf(app as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        store = SessionRestoreRepository(InMemoryQueueStateStore())
        wavFile = writeSilentWav()
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
        wavFile.delete()
    }

    // ---------------------------------------------------------------------
    // FR-25 core: kill -> relaunch restores paused at the same moment
    // ---------------------------------------------------------------------

    @Test
    fun killAndRelaunch_restoresQueueIndexPositionModes_paused_neverAutoPlays() {
        // Session one: play, scrub to 30 s, pause (the "fell asleep" moment).
        startStack(startIndex = 1)
        val p = player()
        awaitUntil("playing") { p.isPlaying }
        p.seekTo(30_000L)
        idle()
        p.pause()
        idle()
        saver().flushNow()

        val saved = runBlocking { store.loadRestoredSession() }!!
        assertEquals("s2", saved.snapshot.itemAt(saved.currentIndex)!!.id.value)
        assertEquals(30_000L, saved.positionMs)

        // KILL: destroy everything without any graceful handoff.
        facade!!.release()
        facade = null
        serviceController!!.destroy()
        serviceController = null
        idle()

        // RELAUNCH: brand-new stack over the SAME storage.
        startStack(startIndex = 0, loadQueue = false)
        val p2 = player()
        facade!!.attachSessionStore(store)
        awaitUntil("restored session visible in facade") {
            facade!!.currentQueue().size == 6 && facade!!.uiState.value.currentItem != null
        }

        // Queue + index restored exactly.
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), facadeIds())
        assertEquals(1, p2.currentMediaItemIndex)

        awaitUntil("restore coroutine landed the saved moment") { facade!!.lastRestoredSeekMsForTest != null }
        assertTrue(
            "restored position must be within +/-5 s (facade truth)",
            kotlin.math.abs(facade!!.uiState.value.positionMs - 30_000L) <= 5_000L,
        )

        // THE LAW: restoration NEVER auto-plays — paused until explicit resume.
        assertFalse(
            "Restored session must remain PAUSED (FR-25 predictability)",
            p2.playWhenReady,
        )
        assertFalse(p2.isPlaying)

        // One tap resumes the night — audible position lands at the restored
        // moment, not the track start (UJ-4).
        facade!!.play()
        awaitUntil("resumed audibly") { p2.isPlaying }
        assertTrue(
            "resume must land within +/-5 s of the saved moment",
            kotlin.math.abs(p2.currentPosition - 30_000L) <= 5_000L,
        )

        // Modes ride along.
        assertEquals(Player.REPEAT_MODE_OFF, p2.repeatMode)
    }

    @Test
    fun firstRun_noSavedState_cleanEmpty_noSessionMarker() {
        startStack(startIndex = 0, loadQueue = false)
        facade!!.attachSessionStore(store)
        idle()
        idle()

        assertNull(
            "First run must present NO Mini-Player session marker",
            facade!!.uiState.value.currentItem,
        )
        assertEquals(0, player().mediaItemCount)
        assertFalse(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // NFR-4 substrate: saving continues with zero controllers bound
    // ---------------------------------------------------------------------

    @Test
    fun savingContinues_afterEveryClientDetaches() {
        startStack(startIndex = 0)
        facade!!.release()
        facade = null
        idle()

        val p = player()
        p.seekTo(21_000L)
        p.pause()
        idle()
        idle()

        awaitUntil("row flushed despite zero UI clients") {
            val saved = runBlocking { store.loadRestoredSession() }
            saved != null && saved.currentIndex == 0 && saved.positionMs >= 21_000L
        }
        assertTrue(
            "Service still alive with zero clients (continuity substrate)",
            !shadowOf(serviceUnderTest()).isStoppedBySelf,
        )
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    /** Builds service(+engine)+facade; optionally loads & plays the six-song queue. */
    private fun startStack(startIndex: Int, loadQueue: Boolean = true) {
        val sc = Robolectric.buildService(SwayPlaybackService::class.java)
        serviceController = sc
        sc.get().streamResolverForTest = resolverFor()
        sc.get().sessionStoreForTest = store
        sc.create()
        idle()

        if (!loadQueue) {
            facade = PlayerConnection.bareForTest(scope).also { conn ->
                conn.bindPlayer(sc.get().getPlayerForTest()!!)
                idle()
            }
            return
        }
        sc.get().addSession(sc.get().getSessionForTest()!!)
        idle()

        val songs = sixSongs()
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
        conn.adoptSnapshotForTest(snapshot, startIndex)
        idle()

        conn.play()
        awaitUntil("start item resolves and plays") {
            val p = sc.get().getPlayerForTest() ?: return@awaitUntil false
            p.playWhenReady && p.playbackState == Player.STATE_READY
        }
    }

    private fun resolverFor(): FakeStreamResolver =
        FakeStreamResolver().apply {
            resolveBehavior = { id, request ->
                SwayResult.Success(
                    ResolvedAudio(
                        url = "file://" + wavFile.absolutePath.replace('\\', '/'),
                        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                        bitrateKbps = if (request.quality == Quality.HIGH) 256 else 160,
                        containerHint = "wav",
                        backendTag = "test:silent-wav",
                        renditionCacheKey = ResolvedAudio.cacheKey(id, request.quality),
                    ),
                )
            }
        }

    private fun saver(): SessionStateSaver =
        serviceController?.get()?.getSessionSaverForTest() ?: error("session saver missing")

    private fun player(): Player =
        serviceController?.get()?.getPlayerForTest() ?: error("service player not available")

    private fun serviceUnderTest(): SwayPlaybackService =
        serviceController?.get() ?: error("service not started")

    private fun facadeIds(): List<String> = facade!!.currentQueue().map { it.id.value }

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

    private fun sixSongs(): List<Song> = listOf(
        song("s1", "Song One", "Artist A", 60_000),
        song("s2", "Song Two", "Artist B", 70_000),
        song("s3", "Song Three", null, 80_000),
        song("s4", "Song Four", "Artist D", 90_000),
        song("s5", "Song Five", "Artist E", 100_000),
        song("s6", "Song Six", null, 110_000),
    )

    private fun song(id: String, rawTitle: String, artist: String?, durMs: Long): Song =
        Song.create(id = id, rawTitle = rawTitle, artistName = artist, durationMs = durMs, artwork = null)!!

    private fun writeSilentWav(): File {
        val sampleRate = 8_000
        val dataSize = sampleRate * 90 * 2
        val file = File.createTempFile("sway_silent_", ".wav")
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

/** Contract-level in-memory store: Room machinery is :core:data/:core:database territory. */
private class InMemoryQueueStateStore : QueueStateStore {
    private var row: StoredQueueState? = null
    override suspend fun loadOnce(): StoredQueueState? = row
    override suspend fun save(state: StoredQueueState) { row = state }
    override suspend fun clear() { row = null }
}
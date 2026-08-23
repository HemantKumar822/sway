package com.sway.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import org.junit.Assert.assertNotNull
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
 * Story 6.3 — recents-swipe posture & denied-notification degradation
 * (FR-21 completes here; P-3 provisional default honored).
 *
 * Grounded in media3 1.11.0 `MediaSessionService.onTaskRemoved` (extracted
 * sources): `if (!isPlaybackOngoing() || !isAnySessionPlaying()) {
 * pauseAllPlayersAndStopSelf(); }` — while PLAYING the service survives a
 * task removal untouched; paused/idle it pauses all players and self-stops.
 * Our 4.1 skeleton inherits this via `super`, so these tests PROVE the
 * observable contract end-to-end on the full production stack (spec Design
 * Notes 1–3):
 *  - AC1: swipe-away while playing -> service alive, audio continues,
 *    notification remains as the stop affordance, facade mirror honest;
 *  - AC2: swipe-away while paused -> players paused + service self-stopped;
 *  - AC3: swipe-away while idle -> self-stop (no zombie FGS);
 *  - AC4: POST_NOTIFICATIONS DENIED on API 33+ -> playback unaffected and the
 *    media notification still posts (media-session exemption per official
 *    docs; MediaNotificationManager posts regardless) — documented
 *    degradation recorded for R-3 / release checklist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RecentsSwipeComplianceTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scope: CoroutineScope
    private lateinit var wavFile: File
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // NOTE: deliberately NOT granting POST_NOTIFICATIONS here; AC4 needs the
        // denied posture and the other ACs are permission-independent.
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
    // AC1: FR-21 core law — swipe-away during ACTIVE playback keeps music
    // alive with the notification as the only stop affordance
    // ---------------------------------------------------------------------

    @Test
    fun swipeAwayWhilePlaying_playbackContinues_serviceAlive_notificationRemains() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        // Precondition sanity: genuinely playing before the swipe.
        awaitUntil("playing before task removal") { player().isPlaying }
        assertNotNull("Notification posted while playing", findOurNotification())

        // The OS contract: removing the task from Recents dispatches exactly
        // this callback on the foreground service (spec Design Note 2).
        service.onTaskRemoved(Intent(app, SwayPlaybackService::class.java))
        idle()
        idle()

        // FR-21: playback CONTINUES — nothing pauses, nothing stops.
        assertFalse(
            "Service must NOT self-stop when swiped away while playing",
            shadowOf(service).isStoppedBySelf,
        )
        assertTrue(
            "playWhenReady must survive the swipe",
            player().playWhenReady,
        )
        assertTrue(
            "Player must still be audible/playing after the swipe",
            player().isPlaying,
        )

        // The notification is the ONLY stop affordance left — it must exist.
        assertNotNull(
            "Media notification must remain as stop affordance after swipe-away",
            findOurNotification(),
        )

        // Facade mirror honesty at the swipe moment (AD-6 rule 2 parity).
        assertTrue(
            "Facade uiState must still report playing after swipe-away",
            facade!!.uiState.value.isPlaying,
        )
    }

    // ---------------------------------------------------------------------
    // AC2: swipe-away while PAUSED -> platform default pause-all + stop-self
    // ---------------------------------------------------------------------

    @Test
    fun swipeAwayWhilePaused_playersPausedAndServiceStops_platformDefault() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        facade!!.pause()
        idle()

        service.onTaskRemoved(Intent(app, SwayPlaybackService::class.java))
        idle()
        idle()

        assertFalse(
            "playWhenReady must be down after pause-all on swipe-while-paused",
            player().playWhenReady,
        )
        assertTrue(
            "Service must self-stop when swiped away while paused " +
                "(media3 pauseAllPlayersAndStopSelf default; A-10/NFR-10 coherent)",
            shadowOf(service).isStoppedBySelf,
        )
    }

    // ---------------------------------------------------------------------
    // AC3: swipe-away while IDLE (user-intent, no error) -> no zombie FGS
    // ---------------------------------------------------------------------

    @Test
    fun swipeAwayWhileIdle_noError_selfStops_noZombieForeground() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        // Drive to user-intent IDLE without an error (pause first so the
        // self-stop guard sees paused intent; media3 stop() keeps intent).
        player().pause()
        player().stop()
        idle()
        idle()

        assertTrue(
            "Idle self-stop law should already have fired (NFR-10 baseline)",
            shadowOf(service).isStoppedBySelf || !player().playWhenReady,
        )

        service.onTaskRemoved(Intent(app, SwayPlaybackService::class.java))
        idle()

        assertTrue(
            "Swipe-away on idle must leave zero purposeless foreground service",
            shadowOf(service).isStoppedBySelf,
        )
    }

    // ---------------------------------------------------------------------
    // AC4: notifications DENIED on API 33+ — documented degradation:
    // media-session notifications are platform-exempt; playback unaffected
    // ---------------------------------------------------------------------

    @Test
    fun notificationsDenied_mediaNotificationStillPosts_playbackUnaffected() {
        // Permission state: explicitly NOT granted for this whole suite's setUp.
        val granted = app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            "Test premise: POST_NOTIFICATIONS must be denied under Robolectric sdk 36",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            granted,
        )

        startPlaying(tripleSongs(), startIndex = 1)

        // Playback runs normally despite the denial.
        assertTrue(player().playWhenReady)
        awaitUntil("playing despite denied notifications") { player().isPlaying }

        // The media notification posts through media3's manager regardless of
        // the runtime permission (exemption per official docs + source comment).
        val posted = findOurNotification()
        assertNotNull(
            "Media notification must still be available with POST_NOTIFICATIONS denied " +
                "(media-session exemption; R-3 device confirmation pending)",
            posted,
        )
        assertEquals(
            "Posted notification carries the playing track title (control affordance intact)",
            tripleSongs()[1].title,
            posted!!.extras.getString(Notification.EXTRA_TITLE),
        )
    }

    // ---------------------------------------------------------------------
    // Harness (6.1 topology per spec Design Note 7)
    // ---------------------------------------------------------------------

    /** Builds the full production stack and starts playback of [songs]@[startIndex]. */
    private fun startPlaying(songs: List<Song>, startIndex: Int) {
        val fake = FakeStreamResolver()
        fake.resolveBehavior = { id, request ->
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

        val sc = Robolectric.buildService(SwayPlaybackService::class.java)
        serviceController = sc
        sc.get().streamResolverForTest = fake
        sc.create()
        idle()

        // Register the session so Media3's own notification manager attaches
        // its in-process controller (external binder broken under Robolectric).
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
        val engine = sc.get().getEngineForTest()!!
        engine.startQueueAndPlay(items, startIndex)
        idle()

        val conn = PlayerConnection.bareForTest(scope)
        facade = conn
        conn.bindPlayer(sc.get().getPlayerForTest()!!)
        idle()

        conn.play()
        awaitUntil("start item resolves and plays (READY)") {
            val p = serviceUnderTest().getPlayerForTest() ?: return@awaitUntil false
            p.playWhenReady && p.playbackState == Player.STATE_READY
        }
    }

    private fun player() =
        serviceUnderTest().getPlayerForTest()
            ?: error("service player not available")

    private fun serviceUnderTest(): SwayPlaybackService =
        serviceController?.get() ?: error("service not started")

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun findOurNotification(): Notification? =
        shadowOf(notificationManager()).getNotification(SwayNotificationProvider.NOTIFICATION_ID)

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
        song("r1", "Rho Track", "Artist One", 61_000, "https://img.example/r1.jpg"),
        song("r2", "Sigma Track", "Artist Two", 122_000, "https://img.example/r2.jpg"),
        song("r3", "Tau Track", null, 183_000, null),
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
    private fun writeSilentWav(seconds: Int = 90): File {
        val sampleRate = 8_000
        val dataSize = sampleRate * seconds * 2 // mono, 16-bit
        val file = File.createTempFile("sway_silent_", ".wav")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLe(36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLe(16) // PCM chunk size
            out.writeShortLe(1) // audio format = PCM
            out.writeShortLe(1) // channels = mono
            out.writeIntLe(sampleRate)
            out.writeIntLe(sampleRate * 2) // byte rate
            out.writeShortLe(2) // block align
            out.writeShortLe(16) // bits per sample
            out.writeBytes("data")
            out.writeIntLe(dataSize)
            out.write(ByteArray(dataSize)) // silence
        }
        file.deleteOnExit()
        return file
    }

    private fun DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}

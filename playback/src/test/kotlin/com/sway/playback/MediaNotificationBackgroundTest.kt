package com.sway.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Story 6.1 — media notification, lock screen parity & background continuity
 * (FR-16/FR-17/FR-18 complete here).
 *
 * Full production path under Robolectric (sdk 36): service with injected
 * [FakeStreamResolver] pointed at a REAL playable silent-WAV file:// source,
 * queue delivered through the [PlayerConnection] facade (production metadata
 * stamping), session interception resolving ONLY the start item, Media3's own
 * notification manager posting through [SwayNotificationProvider]. Proves:
 *  - notification appears on play on the branded channel with
 *    prev/play-pause/next actions, deleteIntent and exact track metadata;
 *  - pause keeps the notification swipe-dismissable (A-10 platform default);
 *  - idle self-stop + destroy leave ZERO zombie notifications (NFR-10);
 *  - releasing every client mid-play keeps player/session/notification alive;
 *  - session metadata mirrors PlayerUiState truth exactly;
 *  - transport command parity between facade and session vocabulary.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MediaNotificationBackgroundTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scope: CoroutineScope
    private lateinit var wavFile: File
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null
    private var currentSongs: List<Song> = emptyList()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // API 33+ posture: grant what a normal user would have granted.
        shadowOf(app as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
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
    // AC1/AC5: appear-on-play with branded channel, transport actions,
    // deleteIntent, exact metadata
    // ---------------------------------------------------------------------

    @Test
    fun notificationAppearsOnPlay_brandedChannel_actions_deleteIntent_metadata() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Branded channel auto-created by the delegate on first post (Design Note 4).
        assertNotNull(
            "Channel ${SwayNotificationProvider.CHANNEL_ID} must exist after first post",
            nm.getNotificationChannel(SwayNotificationProvider.CHANNEL_ID),
        )

        val posted = findOurNotification()
        assertNotNull("Media notification must be posted while playing", posted)

        // Exact metadata mirror (FR-17/18): contentTitle=title, text=artist.
        val truth = currentSongs[1]
        assertEquals(truth.title, posted!!.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(truth.artistName, posted.extras.getString(Notification.EXTRA_TEXT))

        // Platform dismissal plumbing present (A-10 default kept).
        assertNotNull("deleteIntent (swipe dismissal) must be wired", posted.deleteIntent)

        // Transport actions: prev / play-pause(pause while playing) / next.
        val actions = requireNotNull(posted.actions) { "Transport actions missing" }
        assertEquals("prev/play-pause/next expected", 3, actions.size)
        val titles = actions.map { it.title.toString() }
        assertTrue(
            "previous action missing in $titles",
            titles.contains(stringRes("media3_controls_seek_to_previous_description")),
        )
        assertTrue(
            "pause action missing in $titles",
            titles.contains(stringRes("media3_controls_pause_description")),
        )
        assertTrue(
            "next action missing in $titles",
            titles.contains(stringRes("media3_controls_seek_to_next_description")),
        )

        // Foreground ownership proven (notification lives under FGS).
        assertEquals(
            "Service must be foreground with OUR notification while playing",
            SwayNotificationProvider.NOTIFICATION_ID,
            shadowOf(service).lastForegroundNotificationId,
        )
    }

    // ---------------------------------------------------------------------
    // AC2: pause -> notification remains, non-ongoing/swipeable, foreground
    // drops after the platform user-engaged timeout (A-10 default untouched)
    // ---------------------------------------------------------------------

    @Test
    fun paused_notificationRemainsNonOngoing_foregroundDropsAfterEngagedTimeout() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        assertNotNull(findOurNotification())

        facade!!.pause()
        idle()

        // Platform default: keep the notification but drop out of foreground once
        // the engaged-timeout elapses -> swipeable. Flush the 600 s timer hermetically.
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        idle()

        val stillPosted = findOurNotification()
        assertNotNull("Paused notification must remain visible (platform default)", stillPosted)
        assertFalse(
            "Paused notification must not be ongoing (user-swipeable per A-10)",
            stillPosted!!.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertTrue(
            "Foreground must drop after engaged-timeout once paused",
            shadowOf(service).isForegroundStopped,
        )
    }

    // ---------------------------------------------------------------------
    // AC3/AC4: idle self-stop + destroy => zero zombie notifications
    // ---------------------------------------------------------------------

    @Test
    fun idleSelfStop_serviceStops_destroyPurgesNotification_zeroZombies() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        facade!!.release()
        facade = null
        idle()
        // Drive to user-intent IDLE (no error): 4.1 law stops the service.
        // pause() first — media3 stop() leaves playWhenReady unchanged, and the
        // self-stop guard requires paused intent without an error.
        val player = service.getPlayerForTest()!!
        player.pause()
        player.stop()
        idle()
        idle()

        assertTrue(
            "Idle self-stop law (NFR-10) must fire on paused IDLE",
            shadowOf(service).isStoppedBySelf,
        )

        // Teardown completes: defensive purge must cancel everything we posted.
        serviceController!!.destroy()
        serviceController = null
        idle()

        val shadowNm = shadowOf(notificationManager())
        assertNull(
            "No zombie notification may survive teardown",
            shadowNm.getNotification(SwayNotificationProvider.NOTIFICATION_ID),
        )
        assertTrue(
            "Notification manager must hold zero sway notifications after teardown",
            shadowNm.allNotifications.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------
    // AC6: FR-16/FR-18 substrate PROOF — releasing ALL clients mid-playback
    // keeps player + session + notification alive
    // ---------------------------------------------------------------------

    @Test
    fun backgroundContinuity_releasingAllControllers_keepsPlaybackSessionAndNotification() {
        startPlaying(tripleSongs(), startIndex = 1)
        val service = serviceUnderTest()

        // Sanity: actually playing before detach.
        val player = service.getPlayerForTest()!!
        assertTrue(player.playWhenReady)
        awaitUntil("player reaches PLAYING") { player.isPlaying }

        facade!!.release()
        facade = null
        idle()
        idle()

        // Zero UI clients bound: playback MUST continue (FR-16), session alive,
        // notification alive (the stop affordance), service NOT self-stopped.
        assertTrue("playWhenReady must survive client release", player.playWhenReady)
        assertTrue("Player must still be playing", player.isPlaying)
        assertNotNull("Session must survive client release", service.getSessionForTest())
        assertNotNull("Engine must survive client release", service.getEngineForTest())
        assertFalse("Service must NOT self-stop on client detach", shadowOf(service).isStoppedBySelf)

        assertNotNull(
            "Notification must remain as stop affordance after client release",
            findOurNotification(),
        )
    }

    // ---------------------------------------------------------------------
    // AC7: session metadata mirrors PlayerUiState truth exactly (FR-18)
    // ---------------------------------------------------------------------

    @Test
    fun metadataParity_sessionMirrorsSongTruth_includingDurationAndArtwork() {
        currentSongs = listOf(
            song("m1", "First Song", "Artist A", 111_000, "https://img.example/a1.jpg"),
            song("m2", "Second Song", "Artist B", 222_000, "https://img.example/a2.jpg"),
            song("m3", "Third Song", null, 333_000, null),
        )
        startPlaying(currentSongs, startIndex = 0)

        val player = serviceUnderTest().getPlayerForTest()!!
        val truth = currentSongs[0]

        val meta = player.currentMediaItem!!.mediaMetadata
        assertEquals(truth.title, meta.title?.toString())
        assertEquals(truth.artistName, meta.artist?.toString())
        assertEquals(truth.artwork!!.canonicalUrl, meta.artworkUri?.toString())
        assertEquals(
            "Stamped duration must equal Song truth (lock-screen exactness)",
            truth.duration.millis,
            meta.durationMs,
        )
        assertEquals(truth.id.value, player.currentMediaItem?.mediaId)
    }

    // ---------------------------------------------------------------------
    // AC7b: facade setQueue stamps the SAME mirror (single stamping point)
    // ---------------------------------------------------------------------

    @Test
    fun facadeSetQueue_stampsMetadataMirror_singleStampingPoint() {
        val songs = listOf(
            song("f1", "Facade One", null, 45_000, null),
            song("f2", "Facade Two", "Artist F", 90_000, "https://img.example/f2.jpg"),
        )
        val player = ExoPlayer.Builder(app).build()
        try {
            val conn = PlayerConnection.forTest(player, scope)
            conn.setQueue(QueueSnapshot.of(songs.map { QueueItem.of(it) }), 0)
            idle()

            assertEquals(2, player.mediaItemCount)
            for (i in 0 until player.mediaItemCount) {
                val song = songs[i]
                val meta = player.getMediaItemAt(i).mediaMetadata
                assertEquals(song.title, meta.title?.toString())
                assertEquals(song.artistName, meta.artist?.toString())
                assertEquals(
                    song.artwork?.canonicalUrl,
                    meta.artworkUri?.toString(),
                )
                assertEquals(song.duration.millis, meta.durationMs)
            }
        } finally {
            player.release()
        }
    }

    // ---------------------------------------------------------------------
    // AC8: JIT resolve swap preserves the stamped metadata
    // ---------------------------------------------------------------------

    @Test
    fun jitUriSwap_preservesStampedMetadata() {
        startPlaying(tripleSongs(), startIndex = 1)
        val player = serviceUnderTest().getPlayerForTest()!!

        // Start item was resolved by session interception: URI swapped to file://
        val uri = player.currentMediaItem!!.localConfiguration!!.uri.toString()
        assertTrue("Start item must carry resolved file URL, got: $uri", uri.startsWith("file://"))
        // ...while the metadata mirror survives the buildUpon swap intact.
        assertEquals(currentSongs[1].title, player.currentMediaItem!!.mediaMetadata.title?.toString())
    }

    // ---------------------------------------------------------------------
    // AC9: command parity — session vocabulary == in-app equivalents
    // ---------------------------------------------------------------------

    @Test
    fun commandParity_transportVocabularyPresent_facadeMatchesEffects() {
        startPlaying(tripleSongs(), startIndex = 1)
        val player = serviceUnderTest().getPlayerForTest()!!

        // Vocabulary the stock notification buttons render from (1.11.0 default).
        val cmds = player.availableCommands
        assertTrue(cmds.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(cmds.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(cmds.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertTrue(cmds.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(cmds.contains(Player.COMMAND_SEEK_TO_PREVIOUS))

        // In-app equivalents produce identical player effects (parity law).
        assertEquals(1, player.currentMediaItemIndex)
        facade!!.next()
        awaitUntil("advance to index 2") { player.currentMediaItemIndex == 2 }
        facade!!.previous()
        awaitUntil("back to index 1") { player.currentMediaItemIndex == 1 }
        facade!!.pause()
        assertFalse(player.playWhenReady)
        facade!!.play()
        assertTrue(player.playWhenReady)
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    /** Builds the full production stack and starts playback of [songs]@[startIndex]. */
    private fun startPlaying(songs: List<Song>, startIndex: Int) {
        currentSongs = songs
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

        // Register the session with the service so Media3's own notification
        // manager attaches its in-process media-notification controller
        // (external binder connections are not reproducible under Robolectric —
        // see spec Design Note 9).
        sc.get().addSession(sc.get().getSessionForTest()!!)
        idle()

        // Production queue entry: uniform placeholders + stamped metadata via the
        // SAME internal mapping the facade uses (single stamping point), fed
        // through the engine's start path (resolves ONLY the start item).
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

        // Attach the facade to the live service player (in-process; no binder)
        // so command parity / ui-state reads exercise production code paths.
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

    private fun serviceUnderTest(): SwayPlaybackService =
        serviceController?.get() ?: error("service not started")

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Robolectric records posts keyed by id — our provider always uses [SwayNotificationProvider.NOTIFICATION_ID]. */
    private fun findOurNotification(): Notification? =
        shadowOf(notificationManager()).getNotification(SwayNotificationProvider.NOTIFICATION_ID)

    private fun stringRes(name: String): String {
        val id = app.resources.getIdentifier(name, "string", app.packageName)
        return if (id != 0) app.getString(id) else ""
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
     * ExoPlayer's WavExtractor parses to STATE_READY under Robolectric —
     * handcrafted RIFF bytes, zero javax.sound dependency.
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

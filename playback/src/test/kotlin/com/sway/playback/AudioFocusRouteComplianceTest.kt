package com.sway.playback

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
 * Story 6.2 — audio focus & route-change compliance (FR-19/FR-20 complete here,
 * AD-12 scenario matrix automated, AR-11).
 *
 * Drives the REAL media3 focus machinery end-to-end under Robolectric: ExoPlayer's
 * AudioFocusManager registers its platform listener via
 * AudioManager.requestAudioFocus(AudioFocusRequest); ShadowAudioManager records
 * that exact request, and delivering focus changes to the recorded listener
 * invokes media3's own handlePlatformAudioFocusChange — the same entry point the
 * OS calls on-device. Becoming-noisy rides the production receiver via a real
 * ACTION_AUDIO_BECOMING_NOISY broadcast.
 *
 * Grounded against extracted 1.11.0 sources (spec context): transient loss =
 * playback SUPPRESSION (playWhenReady stays true, audible output stops),
 * permanent loss = forced pause + focus abandonment, can-duck (music content) =
 * keep playing under the platform volume multiplier, regain resumes ONLY a
 * focus-caused transient pause, never an explicit user pause.
 *
 * Proves (each step also asserts the PlayerUiState facade mirror):
 *  - focus-log substrate: gain=AUDIOFOCUS_GAIN, willPauseWhenDucked=false;
 *  - transient-loss pause + regain-resume (user intent persisted);
 *  - regain never overrides an explicit user pause;
 *  - permanent loss stops forever + abandons focus; explicit play() recovers;
 *  - can-duck keeps playing without pausing or suppressing;
 *  - focus-denied refusal (never overlaps other apps' audio);
 *  - becoming-noisy pause measured <1 s; reconnect NEVER auto-resumes;
 *  - rapid focus churn leaves the stack stable and self-consistent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AudioFocusRouteComplianceTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scope: CoroutineScope
    private lateinit var wavFile: File
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null

    /** Probe: last playWhenReady change reason observed on the service player. */
    private var lastPlayWhenReadyChangeReason: Int = Int.MIN_VALUE

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        shadowOf(app as Application).grantPermissions(
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
    // AC1: focus-log substrate — polite request before any sound
    // ---------------------------------------------------------------------

    @Test
    fun focusLog_mediaGainRequested_willPauseWhenDuckedFalse_listenerWired() {
        startPlaying()

        val am = audioManager()
        val request = shadowOf(am).lastAudioFocusRequest
        assertNotNull("Playback must have requested audio focus", request)

        val platformRequest = requireNotNull(request.audioFocusRequest)
        assertEquals(
            "USAGE_MEDIA maps to AUDIOFOCUS_GAIN (no overlap law)",
            AudioManager.AUDIOFOCUS_GAIN,
            platformRequest.focusGain,
        )
        assertFalse(
            "MUSIC content must NOT pause-on-duck: the platform ducks us where it grants may-duck (AD-12)",
            platformRequest.willPauseWhenDucked(),
        )
        assertNotNull("Platform listener must be wired for focus changes", request.listener)

        assertFacadeMirrorsPlayer("playing baseline")
    }

    // ---------------------------------------------------------------------
    // AC2/AC3: transient loss pauses immediately; regain resumes only the
    // focus-caused pause (user intent persisted)
    // ---------------------------------------------------------------------

    @Test
    fun transientLoss_pausesImmediately_regainResumesPersistedIntent() {
        startPlaying()
        val player = playerUnderTest()
        assertTrue(player.isPlaying)

        // Incoming call: another app takes focus with AUDIOFOCUS_GAIN_TRANSIENT.
        deliverFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        awaitUntil("transient suppression engages") {
            player.playbackSuppressionReason ==
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        }

        // media3 transient semantics: playWhenReady persists, output stops.
        assertTrue("playWhenReady survives transient loss (media3 semantics)", player.playWhenReady)
        assertFalse("Audible playback must stop during a call", player.isPlaying)
        assertFalse("Facade must mirror the pause within sync budget", facade!!.uiState.value.isPlaying)

        // Call ends: focus regained -> auto-resume (policy allows: transient pause).
        deliverFocus(AudioManager.AUDIOFOCUS_GAIN)
        awaitUntil("regain clears suppression") {
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }
        awaitUntil("regain resumes playback") { player.isPlaying }
        assertTrue("Facade mirrors the resume", facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC4: regain must never override an explicit user pause taken during a
    // transient loss ("resume only where policy allows")
    // ---------------------------------------------------------------------

    @Test
    fun transientLoss_thenUserPause_regainDoesNotOverrideExplicitIntent() {
        startPlaying()
        val player = playerUnderTest()

        deliverFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        awaitUntil("transient suppression engages") {
            player.playbackSuppressionReason ==
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        }

        // User explicitly pauses while the call is live.
        facade!!.pause()
        awaitUntil("explicit pause lands") { !player.playWhenReady }
        assertFalse(facade!!.uiState.value.isPlaying)

        // Call ends: regain must NOT resurrect playback against user intent.
        deliverFocus(AudioManager.AUDIOFOCUS_GAIN)
        awaitUntil("suppression clears cleanly once focus returns") {
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }

        assertFalse(
            "Regain must not override an explicit user pause",
            player.playWhenReady,
        )
        assertEquals(
            "Suppression must clear cleanly once focus returns",
            Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            player.playbackSuppressionReason,
        )
        assertFalse(facade!!.uiState.value.isPlaying)

        // Explicit user action still replays normally afterwards.
        facade!!.play()
        awaitUntil("explicit play after regain") { player.isPlaying }
        assertTrue(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC5: permanent loss stops playback forever, abandons focus politely;
    // nothing auto-resumes; explicit play() re-acquires focus
    // ---------------------------------------------------------------------

    @Test
    fun permanentLoss_stopsAndAbandons_neverAutoResumes_explicitPlayRecovers() {
        startPlaying()
        val player = playerUnderTest()
        val am = audioManager()

        // Music app takes focus permanently (e.g. user opened another player).
        deliverFocus(AudioManager.AUDIOFOCUS_LOSS)
        awaitUntil("permanent loss forces playWhenReady down") { !player.playWhenReady }

        assertFalse(player.isPlaying)
        assertEquals(
            "No transient suppression remains on permanent loss",
            Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            player.playbackSuppressionReason,
        )
        assertFalse(facade!!.uiState.value.isPlaying)
        assertNotNull(
            "Sway must ABANDON focus on permanent loss (polite citizen)",
            shadowOf(am).lastAbandonedAudioFocusRequest,
        )

        // Nothing in the world auto-resumes: reconnect-flavored broadcasts
        // and idle processing stay silent (timers deliberately not flushed —
        // fast-forwarding the shadow clock would end the 90 s media and prove
        // nothing about focus; 6.1 owns timer hygiene).
        app.sendBroadcast(Intent("android.bluetooth.device.action.ACL_CONNECTED"))
        idle()
        idle()
        assertFalse("No auto-resume after permanent loss", player.playWhenReady)
        assertFalse(facade!!.uiState.value.isPlaying)

        // Only an explicit user action replays — and it re-acquires focus.
        facade!!.play()
        awaitUntil("explicit play recovers after permanent loss") { player.isPlaying }
        assertTrue(facade!!.uiState.value.isPlaying)
        val request = shadowOf(am).lastAudioFocusRequest
        assertEquals(
            "Recovery path re-requests AUDIOFOCUS_GAIN",
            AudioManager.AUDIOFOCUS_GAIN,
            request.audioFocusRequest!!.focusGain,
        )
    }

    // ---------------------------------------------------------------------
    // AC6: platform-granted ducking keeps music playing (AD-12: duck where
    // the platform grants; never fight the system)
    // ---------------------------------------------------------------------

    @Test
    fun canDuck_keepsPlaying_withoutPausingOrSuppressing() {
        startPlaying()
        val player = playerUnderTest()

        // Navigation prompt: another app requests may-duck focus. MUSIC content
        // type means Sway ducks (platform multiplier) rather than pausing.
        deliverFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        idle()
        idle()

        assertTrue("playWhenReady must persist through can-duck", player.playWhenReady)
        assertEquals(
            "Can-duck must not suppress playback",
            Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            player.playbackSuppressionReason,
        )
        assertTrue("Music keeps playing (ducked) — never pauses", player.isPlaying)
        assertTrue("Facade stays playing during ducking", facade!!.uiState.value.isPlaying)

        // Ducking window closes: everything returns to normal, still playing.
        deliverFocus(AudioManager.AUDIOFOCUS_GAIN)
        idle()
        assertTrue(player.playWhenReady)
        assertTrue(player.isPlaying)
        assertTrue(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC7: focus denied -> Sway refuses to make sound (overlap prevention).
    // A permanent loss leaves media3 in NO_FOCUS; the NEXT play() triggers a
    // FRESH platform request — which we deny, then grant for recovery.
    // ---------------------------------------------------------------------

    @Test
    fun focusDenied_playRefusesToStart_recoveryWorksAfterwards() {
        startPlaying()
        val player = playerUnderTest()
        val am = audioManager()

        // Reach the fresh-request cycle through the production permanent-loss
        // path (abandons held focus -> next play must ask the platform again).
        deliverFocus(AudioManager.AUDIOFOCUS_LOSS)
        awaitUntil("paused with focus dropped") { !player.playWhenReady }

        // The platform denies the very next request.
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        facade!!.play()
        idle()
        idle()

        assertFalse(
            "Denied focus must force playWhenReady down (DO_NOT_PLAY)",
            player.playWhenReady,
        )
        assertFalse(player.isPlaying)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, player.playbackSuppressionReason)
        assertFalse("Facade must not phantom-play", facade!!.uiState.value.isPlaying)

        // Once the platform grants again, the same user intent plays.
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        facade!!.play()
        awaitUntil("granted focus lets playback proceed") {
            player.isPlaying && player.playbackState == Player.STATE_READY
        }
        assertTrue(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC8: becoming-noisy pause <1 s (measured, FR-20)
    // ---------------------------------------------------------------------

    @Test
    fun becomingNoisy_pauseMeasured_underOneSecond() {
        startPlaying()
        val player = playerUnderTest()
        awaitUntil("audibly playing before unplug") { player.isPlaying }

        val startedNs = System.nanoTime()
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        while (player.playWhenReady && System.nanoTime() - startedNs < 2_000_000_000L) {
            idle()
            Thread.sleep(5)
            idle()
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L

        assertFalse("Unplug must pause playback", player.playWhenReady)
        assertTrue(
            "FR-20 budget: pause <1000 ms (took ${elapsedMs} ms)",
            elapsedMs < 1_000L,
        )
        assertEquals(
            "Pause cause must be the route change",
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            lastPlayWhenReadyChangeReason,
        )
        assertFalse(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC9: route reconnect NEVER auto-resumes; explicit play does
    // ---------------------------------------------------------------------

    @Test
    fun routeReconnect_neverAutoResumes_explicitPlayResumes() {
        startPlaying()
        val player = playerUnderTest()

        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        awaitUntil("unplug pause") { !player.playWhenReady }

        // Headphones plugged back in: ACL-connected churn + idle processing —
        // the stack must stay silent until the user acts (no clock
        // fast-forward: media ending would prove nothing about reconnect).
        app.sendBroadcast(Intent("android.bluetooth.device.action.ACL_CONNECTED"))
        app.sendBroadcast(Intent("android.bluetooth.device.action.ACL_DISCONNECTED"))
        idle()
        idle()
        idle()

        assertFalse("Reconnect must NOT auto-resume (FR-20)", player.playWhenReady)
        assertFalse(facade!!.uiState.value.isPlaying)

        facade!!.play()
        awaitUntil("explicit play resumes after reconnect") { player.isPlaying }
        assertTrue(facade!!.uiState.value.isPlaying)
    }

    // ---------------------------------------------------------------------
    // AC10: rapid focus churn — stability + terminal-state consistency
    // ---------------------------------------------------------------------

    @Test
    fun rapidFocusChurn_stable_terminalStateConsistentWithLastEvent() {
        startPlaying()
        val player = playerUnderTest()

        repeat(20) { round ->
            when {
                round % 5 == 2 -> {
                    // Round of permanent loss mid-churn; user recovers.
                    deliverFocus(AudioManager.AUDIOFOCUS_LOSS)
                    awaitUntil("round $round: permanent loss pauses") { !player.playWhenReady }
                    facade!!.play()
                }
                round % 2 == 0 -> {
                    deliverFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    awaitUntil("round $round: transient suppresses") {
                        player.playbackSuppressionReason ==
                            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    }
                    deliverFocus(AudioManager.AUDIOFOCUS_GAIN)
                    awaitUntil("round $round: regain resumes") { player.isPlaying }
                }
                else -> {
                    deliverFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                    awaitUntil("round $round: duck keeps playing") { player.isPlaying }
                    deliverFocus(AudioManager.AUDIOFOCUS_GAIN)
                    awaitUntil("round $round: post-duck gain keeps playing") { player.isPlaying }
                }
            }
        }

        // Final event was a GAIN: stack must be alive, focused, playing, mirrored.
        assertNotNull("Service must survive churn", serviceUnderTest().getSessionForTest())
        awaitUntil("churn ends playing after final GAIN") { player.isPlaying }
        assertEquals(
            "No suppression may linger after final GAIN",
            Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            player.playbackSuppressionReason,
        )
        assertFacadeMirrorsPlayer("after rapid focus churn")
    }

    // ---------------------------------------------------------------------
    // Harness (story 6.1 topology: external binder broken under Robolectric;
    // public addSession + engine start path + in-process facade)
    // ---------------------------------------------------------------------

    /** Builds the full production stack WITHOUT starting playback. */
    private fun buildStack() {
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

        sc.get().addSession(sc.get().getSessionForTest()!!)
        idle()

        val song = Song.create(
            id = "f1",
            rawTitle = "Focus Probe",
            artistName = "Sway Test",
            durationMs = 120_000,
            artwork = ArtworkRef.of("https://img.example/f1.jpg"),
        )!!
        val snapshot = QueueSnapshot.of(listOf(QueueItem.of(song)))
        val items = snapshot.items.map { qi ->
            MediaItem.Builder()
                .setMediaId(qi.id.value)
                .setUri(PendingUri.buildString(qi.id))
                .setMediaMetadata(qi.song.toMediaMetadata())
                .build()
        }
        sc.get().getEngineForTest()!!.startQueueAndPlay(items, /* startIndex = */ 0)
        idle()

        val conn = PlayerConnection.bareForTest(scope)
        facade = conn
        conn.bindPlayer(sc.get().getPlayerForTest()!!)
        idle()

        // Reason probe (media3 Player exposes the reason only via the listener).
        val player = sc.get().getPlayerForTest()!!
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                lastPlayWhenReadyChangeReason = reason
            }
        })
        idle()
    }

    private fun startPlaying() {
        buildStack()
        val player = playerUnderTest()
        facade!!.play()
        awaitUntil("start item resolves and plays (READY)") {
            player.playWhenReady && player.playbackState == Player.STATE_READY
        }
    }

    private fun playerUnderTest(): Player =
        serviceController?.get()?.getPlayerForTest() ?: error("service not started")

    private fun serviceUnderTest(): SwayPlaybackService =
        serviceController?.get() ?: error("service not started")

    private fun audioManager(): AudioManager =
        app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Delivers a platform focus change to media3's registered listener. */
    private fun deliverFocus(focusChange: Int) {
        val request = shadowOf(audioManager()).lastAudioFocusRequest
        assertNotNull("No focus request recorded — cannot deliver $focusChange", request)
        request.listener.onAudioFocusChange(focusChange)
    }

    /**
     * Facade-mirror parity: uiState must equal raw player observables at any
     * point (the same computation production syncFromPlayer performs).
     */
    private fun assertFacadeMirrorsPlayer(where: String) {
        val p = playerUnderTest()
        val ui = facade?.uiState?.value ?: error("facade missing at: $where")
        val suppressed = p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val expectedPlaying = !suppressed && (p.isPlaying || p.playWhenReady)
        assertEquals("$where: isPlaying mirror", expectedPlaying, ui.isPlaying)
        assertEquals("$where: buffering mirror", p.playbackState == Player.STATE_BUFFERING, ui.isBuffering)
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

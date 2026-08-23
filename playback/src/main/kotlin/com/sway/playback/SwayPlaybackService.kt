package com.sway.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Story 4.1 skeleton — the ONLY player owner (AD-6 rule 1, AR-5).
 *
 * Owns ExoPlayer built in [onCreate] with music [AudioAttributes], focus +
 * becoming-noisy handling, network wake mode, and exposes a MediaLibrarySession
 * for controller attachment. Idle self-stop is armed: service calls `stopSelf`
 * when the session/player is released and on idle transitions (NFR-10).
 */
class SwayPlaybackService : MediaLibraryService() {

    private var exoPlayer: ExoPlayer? = null
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null

    // Stored config for test assertions — ExoPlayer does not expose
    // handleAudioFocus / becomingNoisy / wakeMode as public getters.
    private var handleAudioFocusEnabled: Boolean = false
    private var handleAudioBecomingNoisyEnabled: Boolean = false
    private var wakeModeApplied: Int = C.WAKE_MODE_NONE

    // --- Test-visible accessors (internal) ---

    internal fun getPlayerForTest(): ExoPlayer? = exoPlayer

    internal fun getSessionForTest(): MediaLibraryService.MediaLibrarySession? = librarySession

    internal fun isHandleAudioFocusEnabled(): Boolean = handleAudioFocusEnabled

    internal fun isHandleAudioBecomingNoisyEnabled(): Boolean = handleAudioBecomingNoisyEnabled

    internal fun getWakeModeForTest(): Int = wakeModeApplied

    internal fun getAudioAttributesForTest(): AudioAttributes? = exoPlayer?.audioAttributes

    override fun onCreate() {
        super.onCreate()

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        handleAudioFocusEnabled = true
        handleAudioBecomingNoisyEnabled = true
        wakeModeApplied = C.WAKE_MODE_NETWORK

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, handleAudioFocusEnabled)
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisyEnabled)
            .setWakeMode(wakeModeApplied)
            .build()

        // Idle self-stop hook: when player goes idle and is not playing,
        // stop the foreground service (NFR-10). The onDestroy path also
        // calls stopSelf after release.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) {
                    if (librarySession == null || player.playWhenReady.not()) {
                        stopSelf()
                    }
                }
            }
        })

        exoPlayer = player
        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("sway-playback-${System.identityHashCode(this)}-${System.nanoTime()}")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        librarySession?.release()
        librarySession = null
        exoPlayer?.release()
        exoPlayer = null
        // Idle self-stop: ensure no zombie service remains when released.
        stopSelf()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // E6 owns recents-swipe semantics (FR-21). Skeleton keeps default:
        // do not auto-stop playback here; super handles controller cleanup.
        super.onTaskRemoved(rootIntent)
    }

    private class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback
}

package com.sway.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.sway.core.data.SettingsRepository
import com.sway.core.model.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Story 4.1 skeleton + story 4.4 lazy-resolution wiring — the ONLY player owner
 * (AD-6 rule 1, AR-5).
 *
 * Owns ExoPlayer built in [onCreate] with music [AudioAttributes], focus +
 * becoming-noisy handling, network wake mode, and exposes a MediaLibrarySession
 * for controller attachment. Idle self-stop is armed: service calls `stopSelf`
 * when the session/player is released and on idle transitions (NFR-10).
 *
 * Story 4.4: when a [StreamResolver] is present ([streamResolverForTest] seam —
 * production graph wiring arrives in a later epic), [onCreate] builds a
 * [JitResolveEngine] and [LibraryCallback.onSetMediaItems] resolves ONLY the
 * start queue item before items land on the player (FR-12 budget = 1); all
 * just-in-time transitions/prefetch run inside the engine via its own player
 * listener, so auto-advance needs zero controllers bound. Resolve failures are
 * hoisted as typed values on [lastFailure] — never crashes.
 *
 * Story 5.3 (FR-13/AD-7 layer 2): the engine's error listener renews expired
 * streams invisibly with position resume; the idle self-stop listener is
 * error-aware so an error-driven STATE_IDLE never stops the service while the
 * renewal layer owns recovery.
 *
 * Story 5.4 (FR-14/AD-7 layer 3): the engine's stalled-playback watchdog is
 * ARMED here ([JitResolveEngine.startWatchdog]) on the engine scope — its
 * ticker lives and dies with this service (no app-wide ticking broadcast).
 *
 * Story 6.1 (FR-16/17/18): media notifications ride Media3 defaults wrapped
 * thin ([SwayNotificationProvider] — branded channel, stable id, stock
 * actions/dismissal semantics per A-10). onDestroy purges our notification id
 * defensively so no zombie notification can outlive the service through any
 * teardown path (NFR-10).
 */
class SwayPlaybackService : MediaLibraryService() {

    private var exoPlayer: ExoPlayer? = null
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null

    // Stored config for test assertions — ExoPlayer does not expose
    // handleAudioFocus / becomingNoisy / wakeMode as public getters.
    private var handleAudioFocusEnabled: Boolean = false
    private var handleAudioBecomingNoisyEnabled: Boolean = false
    private var wakeModeApplied: Int = C.WAKE_MODE_NONE

    // --- story 4.4: lazy resolution -------------------------------------------

    /**
     * Resolver injection seam: set BETWEEN Robolectric construction and
     * `.create()` (or by the future DI graph) BEFORE the service starts; null
     * keeps the pre-4.4 behavior (uniform placeholders, default session
     * routing). Kept Hilt-free per module boundary decision.
     */
    internal var streamResolverForTest: StreamResolver? = null

    private var resolveEngine: JitResolveEngine? = null
    private var engineScope: CoroutineScope? = null

    // --- story 7.2: mode persistence (FR-11 persistence clause) --------------

    /**
     * Settings injection seam — mirrors [streamResolverForTest]: production
     * graph wiring arrives with the app-assembly epic; tests inject here.
     * When present, onCreate launches an ASYNC restore (AD-10: never a
     * synchronous read) that applies the persisted repeat mode onto the
     * player BEFORE the first queue build lands.
     */
    internal var settingsForTest: SettingsRepository? = null

    private var serviceScope: CoroutineScope? = null
    private var modeRestoreJob: Job? = null

    private val _lastFailure = MutableStateFlow<FailedTrack?>(null)

    /** Latest typed resolution failure (start or transition) — hoisted for glue. */
    internal val lastFailure: StateFlow<FailedTrack?> = _lastFailure.asStateFlow()

    // --- Test-visible accessors (internal) ---

    internal fun getPlayerForTest(): ExoPlayer? = exoPlayer

    internal fun getSessionForTest(): MediaLibraryService.MediaLibrarySession? = librarySession

    internal fun isHandleAudioFocusEnabled(): Boolean = handleAudioFocusEnabled

    internal fun isHandleAudioBecomingNoisyEnabled(): Boolean = handleAudioBecomingNoisyEnabled

    internal fun getWakeModeForTest(): Int = wakeModeApplied

    internal fun getAudioAttributesForTest(): AudioAttributes? = exoPlayer?.audioAttributes

    internal fun getEngineForTest(): JitResolveEngine? = resolveEngine

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
        // calls stopSelf after release. Story 5.3: an ERROR-driven STATE_IDLE
        // (playerError != null) is owned by the renewal layer (AD-7 defense
        // layer 2) and must NEVER trip self-stop — the service stays alive so
        // recovery can swap in a fresh URL and resume; only user-intent idle
        // (no session, or playWhenReady=false without an error) stops here.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (
                    playbackState == Player.STATE_IDLE &&
                    player.playerError == null &&
                    (librarySession == null || player.playWhenReady.not())
                ) {
                    stopSelf()
                }
            }
        })

        exoPlayer = player

        streamResolverForTest?.let { resolver ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            engineScope = scope
            resolveEngine = JitResolveEngine(
                player = player,
                resolver = resolver,
                scope = scope,
                onFailure = { _lastFailure.value = it },
            )
            // Story 5.4: arm the stalled-playback watchdog on the engine scope
            // (service lifecycle); ticks gate to no-ops whenever nothing stalls.
            resolveEngine?.startWatchdog()
        }

        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("sway-playback-${System.identityHashCode(this)}-${System.nanoTime()}")
            .build()

        // Story 6.1 (FR-17): media notifications via Media3 defaults wrapped
        // thin — branded channel id/name + stable notification id only; all
        // action/metadata/dismissal behavior stays stock (A-10).
        setMediaNotificationProvider(SwayNotificationProvider(this))

        // Story 7.2 (FR-11): async mode restore BEFORE any queue can be built
        // (session commands arrive later on this same looper, so the restored
        // repeat mode is already on the player when the first queue lands;
        // shuffle is restored facade-side where the timeline order lives).
        settingsForTest?.let { repo ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            serviceScope = scope
            val playerRef = player
            modeRestoreJob = scope.launch {
                val restored = repo.repeatMode.first()
                try {
                    playerRef.repeatMode = when (restored) {
                        com.sway.core.model.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                        com.sway.core.model.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                        com.sway.core.model.RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        modeRestoreJob?.cancel()
        modeRestoreJob = null
        serviceScope?.cancel()
        serviceScope = null
        resolveEngine?.release()
        resolveEngine = null
        engineScope?.cancel()
        engineScope = null
        librarySession?.release()
        librarySession = null
        exoPlayer?.release()
        exoPlayer = null
        // Idle self-stop: ensure no zombie service remains when released.
        // Story 6.1 (NFR-10): also purge our media notification defensively —
        // Media3's manager only cancels through its own update paths, which a
        // stopSelf()/destroy teardown can bypass; cancel by the SAME constant
        // id the provider posts under so no zombie notification outlives us.
        try {
            stopForeground(true)
        } catch (_: Exception) {
            // Best-effort: never let notification hygiene crash teardown.
        }
        try {
            (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                ?.cancel(SwayNotificationProvider.NOTIFICATION_ID)
        } catch (_: Exception) {
            // Best-effort as above.
        }
        // 4.1 contract preserved: explicit self-stop on teardown.
        stopSelf()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Story 6.3 (FR-21, P-3/OQ-5): the recents-swipe posture IS media3's
        // default — MediaSessionService.onTaskRemoved (1.11.0 source):
        // `if (!isPlaybackOngoing() || !isAnySessionPlaying()) {
        // pauseAllPlayersAndStopSelf(); }` — playing survives the swipe with
        // the notification as the stop affordance; paused/idle pauses all and
        // self-stops. Proven hermetically in RecentsSwipeComplianceTest; no
        // hand-rolled stop/continue logic here by design (AD-6 rule 8).
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Session callback with story-4.4 first-resolve interception: a controller's
     * `setMediaItems(placeholders, startIndex)` arrives here and returns the
     * list with ONLY the start item's URI swapped for its resolved stream URL
     * (FR-12 up-front budget = 1). On start-resolve failure the ORIGINAL
     * placeholder list is returned — the queue still loads, the typed failure
     * surfaces on [lastFailure]/facade slot, nothing throws. Without an engine
     * (no resolver injected) the default routing applies unchanged.
     */
    private inner class LibraryCallback : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val engine = resolveEngine ?: return super.onSetMediaItems(
                mediaSession, controller, mediaItems, startIndex, startPositionMs,
            )
            val scope = engineScope
            if (scope == null || !scope.isActive) return super.onSetMediaItems(
                mediaSession, controller, mediaItems, startIndex, startPositionMs,
            )
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val swapped = engine.resolveStartSwap(mediaItems, startIndex)
                    result.set(MediaSession.MediaItemsWithStartPosition(swapped, startIndex, startPositionMs))
                } catch (t: Throwable) {
                    result.setException(t)
                }
            }
            return result
        }
    }
}

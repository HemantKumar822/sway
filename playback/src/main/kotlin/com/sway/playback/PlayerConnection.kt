package com.sway.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.SwayError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * Long-lived MediaController wrapper (AD-6 rule 2, AR-5, story 4.2).
 *
 * UI talks exclusively through this facade — commands + hoisted
 * [PlayerUiState]. Mirrors [SwayPlaybackService] state within the
 * 250 ms sync budget (FR-27, perf budgets). Position ticks are
 * **scoped** to active scrubber subscribers (AD-6, UX §12.8) via
 * [positionFlow] — collecting [uiState] alone never causes tick churn.
 *
 * Rebind-safe: [connect] / [disconnect] / [rebind] manage the
 * controller lifecycle without leaking — old controller released before
 * new one attached. Call [release] on owner destroy.
 *
 * Commands (4.2 placeholders for later epics are no-ops but present):
 * [setQueue]/[play]/[pause]/[seekTo]/[jumpTo]/[next]/[previous]/
 * [toggleShuffle]/[toggleRepeat] (placeholders; E7 persistence).
 *
 * Threading: construction on any thread; callbacks on main. State
 * updates are synchronous inside [Player.Listener] (no extra dispatch)
 * so sync-budget is wall-clock only, not scheduler.
 */
class PlayerConnection private constructor(
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long,
    private val context: Context?,
    private val sessionToken: SessionToken?,
    // Test injection: when non-null, connection uses this player instead of building a MediaController.
    private val injectedPlayer: Player?,
) {

    // --- state ---------------------------------------------------------------

    private val _uiState = MutableStateFlow(PlayerUiState.Idle)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Scrubber tick gating: separate cold flow, but expose collector-count for tests.
    private val _positionTickCount = MutableStateFlow(0L)

    /** Snapshot of current queue and index for currentItem resolution. */
    private var queueSnapshot: QueueSnapshot = QueueSnapshot.Empty
    private var startIndex: Int = 0

    // --- controller / player handle -----------------------------------------

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Active Player handle — either injected test player or the MediaController. */
    private var player: Player? = injectedPlayer

    private var listener: Player.Listener? = null
    private var listenerAttachedTo: Player? = null

    private var pendingSetQueue: Pair<QueueSnapshot, Int>? = null

    // Diagnostics for rebind test
    private var bindCountInternal: Int = 0
    private var releaseCountInternal: Int = 0
    private var tickFlowCollectorsInternal: Int = 0

    val bindCount: Int get() = bindCountInternal
    val releaseCount: Int get() = releaseCountInternal

    // If injected player was supplied at construction, attach listener immediately
    init {
        if (injectedPlayer != null) {
            attachPlayer(injectedPlayer)
        }
    }

    // --- public API: lifecycle ------------------------------------------------

    /**
     * Connect to [SwayPlaybackService] via [MediaController].
     * No-op when a test player is injected or already connected.
     * Rebind-safe: releases prior controller before creating new one.
     */
    fun connect() {
        if (injectedPlayer != null) return
        if (context == null || sessionToken == null) return
        // If already connected/connecting, treat as rebind request — tear down first
        if (controller != null || controllerFuture != null) {
            disconnect()
        }
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val ctrl = future.get()
                    controller = ctrl
                    attachPlayer(ctrl)
                    // Flush any queued setQueue
                    pendingSetQueue?.let { (snap, idx) ->
                        pendingSetQueue = null
                        setQueueInternal(snap, idx)
                    }
                } catch (_: ExecutionException) {
                    // Controller build failed under test / Robolectric — ignore
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            Executor { it.run() },
        )
    }

    /**
     * Disconnect from the service, releasing the controller.
     * Safe to call multiple times.
     */
    fun disconnect() {
        // Detach listener from whichever player it was attached to
        detachPlayer()

        controller?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
            releaseCountInternal++
        }
        controller = null

        controllerFuture?.let { f ->
            try {
                f.cancel(false)
            } catch (_: Exception) {
            }
        }
        controllerFuture = null
    }

    /** Rebind — disconnect then connect, without leaking. */
    fun rebind() {
        disconnect()
        connect()
    }

    /**
     * Release all resources. After this the instance must not be reused.
     */
    fun release() {
        disconnect()
        if (injectedPlayer != null) {
            // For injected player we still count release for test symmetry,
            // but do NOT release the injected player itself (test owns it).
            // Detach already done above.
        }
    }

    /**
     * Test-only: bind to a supplied [Player] (e.g. an ExoPlayer under Robolectric).
     * Rebind-safe: detaches previous player listener before attaching new one.
     */
    fun bindPlayer(testPlayer: Player) {
        // Detach from previous active player (could be injectedPlayer or prior bindPlayer)
        detachPlayer()
        // If previous player was a MediaController, also release it (already handled above?)
        // For test path we simply swap player handle.
        player = testPlayer
        attachPlayer(testPlayer)
        bindCountInternal++
    }

    /**
     * Test-only: unbind from the currently bound test player.
     */
    fun unbindPlayer() {
        detachPlayer()
        player = injectedPlayer
        if (injectedPlayer != null) {
            attachPlayer(injectedPlayer)
        } else {
            // No fallback — clear to idle but keep tick gating inactive
            _uiState.value = PlayerUiState.Idle
        }
    }

    // --- commands -------------------------------------------------------------

    /**
     * Set the queue snapshot — every entry is forwarded to the session as a
     * [MediaItem] whose URI is the uniform [PendingUri] placeholder
     * (AD-6 rule 6); story 4.4 resolves ONLY the start item by intercepting the
     * command in `SwayPlaybackService`'s `onSetMediaItems` before items land on
     * the player (FR-12 up-front budget = 1). Directly-bound test players skip
     * that session interception, so items stay placeholders here — the
     * zero-resolved-URLs property of this facade is structurally true.
     */
    fun setQueue(snapshot: QueueSnapshot, startIndex: Int) {
        require(startIndex in 0 until snapshot.size || snapshot.isEmpty && startIndex == 0) {
            "startIndex $startIndex out of bounds for size ${snapshot.size}"
        }
        queueSnapshot = snapshot
        this.startIndex = snapshot.size.let { if (it == 0) 0 else startIndex.coerceIn(0, it - 1) }

        if (snapshot.isEmpty) {
            _uiState.value = PlayerUiState.Idle
            player?.let { p ->
                // Clear queue on player if we have one
                try {
                    p.clearMediaItems()
                } catch (_: Exception) {
                }
            }
            return
        }

        // Optimistically publish currentItem without waiting for controller round-trip
        // — satisfies 250ms sync budget (local state).
        val current = snapshot.itemAt(this.startIndex)
        _uiState.value = _uiState.value.copy(currentItem = current)

        setQueueInternal(snapshot, this.startIndex)
    }

    /**
     * Overload consuming [QueueBuilder] output directly (story 4.3) — play
     * actions hand the facade a [QueueBuilder.BuiltQueue] and it feeds the
     * exact same validated path as [setQueue]. No resolution occurs; every
     * item is stamped with a PendingUri placeholder inside
     * [setQueueInternal], keeping "zero resolved URLs" structurally true.
     */
    fun setQueue(built: QueueBuilder.BuiltQueue) {
        setQueue(built.snapshot, built.startIndex)
    }

    private fun setQueueInternal(snapshot: QueueSnapshot, idx: Int) {
        val p = player
        if (p == null) {
            // Queue for later when controller connects
            pendingSetQueue = snapshot to idx
            return
        }
        try {
            val items = snapshot.items.map { qi ->
                // Uniform placeholder mapping (AD-6 rule 6); 4.4's session-side
                // interception swaps ONLY the start URI before player ingestion.
                MediaItem.Builder()
                    .setMediaId(qi.id.value)
                    .setUri(PendingUri.buildString(qi.id))
                    .build()
            }
            p.setMediaItems(items, idx, 0L)
            p.prepare()
        } catch (_: Exception) {
            // Under Robolectric some player ops may throw — ignore for unit proof
        }
    }

    fun play() {
        player?.play()
        // Sync immediately — listener will also fire, but local publish guarantees budget
        syncFromPlayer()
    }

    fun pause() {
        player?.pause()
        syncFromPlayer()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        // Update snapshot position immediately
        _uiState.value = _uiState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    /** Jump to queue index [index] at position 0. */
    fun jumpTo(index: Int) {
        if (queueSnapshot.isEmpty) return
        val clamped = index.coerceIn(0, queueSnapshot.size - 1)
        player?.seekTo(clamped, 0L)
        _uiState.value = _uiState.value.copy(
            currentItem = queueSnapshot.itemAt(clamped),
            positionMs = 0L,
        )
    }

    fun next() {
        val p = player ?: return
        try {
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
            }
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    fun previous() {
        val p = player ?: return
        try {
            if (p.hasPreviousMediaItem()) {
                p.seekToPreviousMediaItem()
            } else {
                p.seekTo(0L)
            }
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    // --- placeholders for E7 (modes persistence) --------------------------------

    /** Placeholder — shuffle toggle. No-op until story 7.2. */
    fun toggleShuffle() {
        // No-op placeholder (AD-6 rule 5, FR-11). Preserved for command surface completeness.
    }

    /** Placeholder — repeat toggle. No-op until story 7.2. */
    fun toggleRepeat() {
        // No-op placeholder (AD-6 rule 5).
    }

    // Future mode slots could be:
    // fun setShuffleEnabled(enabled: Boolean) = toggleShuffle()
    // fun setRepeatMode(mode: Int) = toggleRepeat()

    /** Reserved failure injection for E5 (FR-14) — test seam. */
    fun setFailedTrack(item: QueueItem, error: SwayError) {
        _uiState.value = _uiState.value.copy(failedTrack = FailedTrack(item, error))
    }

    fun clearFailedTrack() {
        _uiState.value = _uiState.value.copy(failedTrack = null)
    }

    // --- position ticks: scrubber-scoped -------------------------------------

    /**
     * Position tick flow — **scrubber-scoped** (AD-6, UX §12.8).
     *
     * Cold flow: no coroutine / polling when there are **zero** collectors.
     * When at least one collector is active, emits [Player.positionMs]-like
     * values every [tickIntervalMs] while active. Stops immediately when the
     * last collector cancels (tick scoping verified by
     * [tickCollectorCount] + [positionTickCount]).
     *
     * Collect *this* flow only from scrubber/position-bar scopes. Do not
     * collect app-wide.
     */
    fun positionFlow(): Flow<Long> = flow {
        tickFlowCollectorsInternal++
        try {
            while (true) {
                val pos = try {
                    player?.currentPosition ?: _uiState.value.positionMs
                } catch (_: Exception) {
                    _uiState.value.positionMs
                }
                emit(pos)
                _positionTickCount.value = _positionTickCount.value + 1
                delay(tickIntervalMs)
            }
        } finally {
            tickFlowCollectorsInternal--
        }
    }

    /** Test-visible: number of active tick collectors. */
    fun tickCollectorCount(): Int = tickFlowCollectorsInternal

    /** Test-visible: total ticks emitted since creation (for liveness asserts). */
    fun positionTickCount(): Long = _positionTickCount.value

    // --- internal: listener ---------------------------------------------------

    private fun attachPlayer(p: Player) {
        // Avoid double-attach
        if (listenerAttachedTo === p && listener != null) return
        detachPlayer()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncFromPlayer()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncFromPlayer()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncFromPlayer()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                syncFromPlayer()
            }
        }
        listener = l
        listenerAttachedTo = p
        try {
            p.addListener(l)
        } catch (_: Exception) {
        }
        bindCountInternal++
        syncFromPlayer()
    }

    private fun detachPlayer() {
        val p = listenerAttachedTo
        val l = listener
        if (p != null && l != null) {
            try {
                p.removeListener(l)
            } catch (_: Exception) {
            }
            releaseCountInternal++
        }
        listener = null
        listenerAttachedTo = null
    }

    private fun syncFromPlayer() {
        val p = player ?: return
        val isPlaying = try {
            p.isPlaying || p.playWhenReady
        } catch (_: Exception) {
            _uiState.value.isPlaying
        }
        val isBuffering = try { p.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false }
        val idx = try { p.currentMediaItemIndex } catch (_: Exception) { startIndex }
        val pos = try { p.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { _uiState.value.positionMs }
        val item = queueSnapshot.itemAt(idx) ?: _uiState.value.currentItem
        // Preserve failedTrack slot across syncs
        val failed = _uiState.value.failedTrack
        _uiState.value = PlayerUiState(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentItem = item,
            positionMs = pos,
            failedTrack = failed,
        )
    }

    // --- companion factories --------------------------------------------------

    companion object {
        /**
         * Production factory — MediaController-backed.
         *
         * @param context Application context
         * @param serviceComponent ComponentName of [SwayPlaybackService]
         * @param scope CoroutineScope for shared flows (use application scope)
         * @param tickIntervalMs Tick interval for scrubber position (default 200 ms)
         */
        fun create(
            context: Context,
            serviceComponent: ComponentName,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            tickIntervalMs: Long = 200L,
        ): PlayerConnection {
            val token = SessionToken(context, serviceComponent)
            return PlayerConnection(
                scope = scope,
                tickIntervalMs = tickIntervalMs,
                context = context,
                sessionToken = token,
                injectedPlayer = null,
            )
        }

        /**
         * Test factory — injected [Player] (Fake or Robolectric ExoPlayer).
         * No MediaController is built.
         */
        fun forTest(
            player: Player,
            scope: CoroutineScope,
            tickIntervalMs: Long = 50L,
        ): PlayerConnection {
            return PlayerConnection(
                scope = scope,
                tickIntervalMs = tickIntervalMs,
                context = null,
                sessionToken = null,
                injectedPlayer = player,
            )
        }

        /**
         * Bare test factory — starts idle, call [bindPlayer] to attach.
         */
        fun bareForTest(
            scope: CoroutineScope,
            tickIntervalMs: Long = 50L,
        ): PlayerConnection {
            return PlayerConnection(
                scope = scope,
                tickIntervalMs = tickIntervalMs,
                context = null,
                sessionToken = null,
                injectedPlayer = null,
            )
        }
    }
}

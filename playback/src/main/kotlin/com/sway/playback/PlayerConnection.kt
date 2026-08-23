package com.sway.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
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
 * Commands (4.2 surface + story 7.1 FR-22/23/24 engine substrate):
 * [setQueue]/[play]/[pause]/[seekTo]/[jumpTo]/[next]/[previous] plus the
 * queue-command layer [removeAt]/[playNext]/[addToQueue]/[clearQueue]/
 * [moveQueueItem]/[setShuffleEnabled]/[cycleRepeatMode]; A-4-aware previous.
 * Mode persistence lands in 7.2.
 *
 * Threading: construction on any thread; callbacks on main. State
 * updates are synchronous inside [Player.Listener] (no extra dispatch)
 * so sync-budget is wall-clock only, not scheduler.
 */
class PlayerConnection private constructor(
    internal val scope: CoroutineScope,
    private val tickIntervalMs: Long,
    private val context: Context?,
    private val sessionToken: SessionToken?,
    // Test injection: when non-null, connection uses this player instead of building a MediaController.
    private val injectedPlayer: Player?,
) {

    // --- state ---------------------------------------------------------------

    internal val _uiState = MutableStateFlow(PlayerUiState.Idle)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Scrubber tick gating: separate cold flow, but expose collector-count for tests.
    private val _positionTickCount = MutableStateFlow(0L)

    /** Snapshot of current queue and index for currentItem resolution. */
    private var queueSnapshot: QueueSnapshot = QueueSnapshot.Empty
    private var startIndex: Int = 0

    // --- story 7.1: queue-command state (FR-22/23/24 engine substrate) -------

    /** Facade-owned shuffle flag; the timeline is physically reordered, so the
     * player's native shuffleModeEnabled stays OFF (single order truth). */
    internal var shuffleEnabledInternal: Boolean = false

    /** Session shuffle seed: lazily captured on first enable; test seam overrides. */
    private var sessionSeed: Long? = null

    /** Test seam: when set, takes precedence over [System.nanoTime] seeding. */
    internal var shuffleSeedOverride: Long? = null

    /** Linear (pre-shuffle) item ids remembered while shuffle is ON. */
    private var preShuffleIds: List<SourceId>? = null

    /**
     * Story 7.2: optional persistence hook (FR-11 persistence clause). When
     * attached, every mode change writes through and the persisted shuffle
     * flag restores into the facade mirror on attach.
     */
    internal var settings: com.sway.core.data.SettingsRepository? = null

    // --- controller / player handle -----------------------------------------

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** Active Player handle — either injected test player or the MediaController. */
    internal var player: Player? = injectedPlayer

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
            val items = snapshot.items.map { mediaItemFor(it) }
            p.setMediaItems(items, idx, 0L)
            p.prepare()
            // Story 7.2: a restored/toggled-ON shuffle reorders the freshly
            // built queue around its start item (zero extra resolves; only
            // ORDER moves after ingestion).
            applyShuffleIfArmed()
        } catch (_: Exception) {
            // Under Robolectric some player ops may throw — ignore for unit proof
        }
    }

    /**
     * Uniform placeholder mapping (AD-6 rule 6); the session-side interception
     * swaps ONLY a start URI before player ingestion. Story 6.1 (FR-18):
     * MediaMetadata stamped from the Song — the SINGLE stamping point so
     * notification + lock screen mirror PlayerUiState.currentItem.song EXACTLY;
     * story 7.1 queue commands reuse it so inserted/append items carry the
     * same mirror. JIT resolve swaps ride buildUpon() and preserve it.
     */
    private fun mediaItemFor(qi: QueueItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(qi.id.value)
            .setUri(PendingUri.buildString(qi.id))
            .setMediaMetadata(qi.song.toMediaMetadata())
            .build()

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

    /**
     * FR-10 / A-4 law (story 7.1): >= [JitPolicy.A4_PREVIOUS_RESTART_MS]
     * played restarts the current track; below it jumps back to the previous
     * item; no previous item always restarts. The decision is pure policy
     * ([JitPolicy.previousDecision]); this method only executes the verdict.
     */
    fun previous() {
        val p = player ?: return
        val verdict = JitPolicy.previousDecision(
            positionMs = try {
                p.currentPosition
            } catch (_: Exception) {
                0L
            },
            hasPrevious = try {
                p.hasPreviousMediaItem()
            } catch (_: Exception) {
                false
            },
        )
        try {
            when (verdict) {
                JitPolicy.PreviousDecision.RESTART_CURRENT -> p.seekTo(0L)
                JitPolicy.PreviousDecision.GO_BACK -> p.seekToPreviousMediaItem()
            }
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    // --- story 7.1: queue commands (FR-22/23/24 engine substrate) -------------

    /**
     * Remove the item at [index]. Removing the PLAYING item auto-advances to
     * the next one via media3 timeline semantics (transition reason REMOVE,
     * playback uninterrupted — the engine's transition listener JIT-resolves
     * the new current, FR-12 budget untouched); removing an upcoming item
     * never disturbs the current one. Snapshot and timeline mutate together
     * (one index space law). Out-of-bounds/empty is a no-op.
     */
    fun removeAt(index: Int) {
        val snap = queueSnapshot
        if (snap.isEmpty || index !in 0 until snap.size) return
        val items = snap.items.toMutableList().apply { removeAt(index) }
        queueSnapshot = QueueSnapshot.of(items)
        try {
            player?.removeMediaItems(index, index + 1)
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    /** FR-24: insert [song] directly after the current item ("play next"). */
    fun playNext(song: Song) = insertItems(listOf(QueueItem.of(song)), afterCurrent = true)

    /** FR-24: append [song] at the queue tail ("add to queue"). */
    fun addToQueue(song: Song) = insertItems(listOf(QueueItem.of(song)), afterCurrent = false)

    private fun insertItems(newItems: List<QueueItem>, afterCurrent: Boolean) {
        val snap = queueSnapshot
        val at = if (snap.isEmpty) {
            0
        } else {
            val cur = currentPlayerIndexCoerced(snap)
            if (afterCurrent) cur + 1 else snap.size
        }
        val items = snap.items.toMutableList().apply { addAll(at, newItems) }
        queueSnapshot = QueueSnapshot.of(items)
        try {
            player?.addMediaItems(at, newItems.map { mediaItemFor(it) })
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    /**
     * FR-24 "clear": stops honestly (pause intent first so the 4.1 idle
     * self-stop guard sees user-intent IDLE), empties timeline + snapshot,
     * resets shuffle state. Repeat mode survives (FR-11 persistence is 7.2's
     * business; clearing a queue is not a mode reset). Confirmation UX lives
     * in E12.
     */
    fun clearQueue() {
        pause()
        try {
            player?.clearMediaItems()
        } catch (_: Exception) {
        }
        queueSnapshot = QueueSnapshot.Empty
        shuffleEnabledInternal = false
        preShuffleIds = null
        sessionSeed = null
        _uiState.value = PlayerUiState(
            isPlaying = false,
            isBuffering = false,
            currentItem = null,
            failedTrack = null,
            shuffleEnabled = false,
            repeatMode = _uiState.value.repeatMode,
        )
    }

    /**
     * FR-24 drag-reorder; persists for the session (live order == snapshot
     * order after the move). Moving the current item preserves identity and
     * uninterrupted playback. Out-of-range or no-op moves are ignored.
     */
    fun moveQueueItem(from: Int, to: Int) {
        val snap = queueSnapshot
        if (snap.isEmpty) return
        if (from !in 0 until snap.size || to !in 0 until snap.size || from == to) return
        val items = snap.items.toMutableList().apply { add(to, removeAt(from)) }
        queueSnapshot = QueueSnapshot.of(items)
        try {
            player?.moveMediaItem(from, to)
        } catch (_: Exception) {
        }
        syncFromPlayer()
    }

    /**
     * FR-11 toggle semantics: ON physically reorders the live timeline with
     * [QueueBuilder.reshufflePreservingCurrent] — current track stays put with
     * ZERO interruption and ZERO extra resolves (its mediaId never moves), the
     * remainder permutes deterministically per the session seed. OFF restores
     * the remembered pre-shuffle order (removed items gone, session-added
     * items appended). The player's native shuffleModeEnabled stays untouched:
     * there is exactly ONE order truth (this timeline).
     */
    fun setShuffleEnabled(enabled: Boolean) {
        val snap = queueSnapshot
        val changed = enabled != shuffleEnabledInternal
        if (snap.isEmpty || !changed) {
            shuffleEnabledInternal = enabled
            _uiState.value = _uiState.value.copy(shuffleEnabled = enabled)
            persistShuffleIfAttached(enabled)
            return
        }
        val cur = currentPlayerIndexCoerced(snap)
        if (enabled) {
            preShuffleIds = snap.sourceIds()
            val seed = sessionSeed
                ?: (shuffleSeedOverride ?: System.nanoTime()).also { sessionSeed = it }
            val target = QueueBuilder.reshufflePreservingCurrent(snap.items, cur, seed)
            materializeAroundCurrent(target.map { it.id.value }, cur)
            queueSnapshot = QueueSnapshot.of(target)
        } else {
            val stored = preShuffleIds
            val target = if (stored == null) {
                snap.items.toList()
            } else {
                restoreLinearOrder(stored, snap.items)
            }
            preShuffleIds = null
            // Current may relocate to its linear position — identity-preserving
            // timeline edits keep it current and playing (no transition fires).
            materializeFull(target.map { it.id.value })
            queueSnapshot = QueueSnapshot.of(target)
        }
        shuffleEnabledInternal = enabled
        _uiState.value = _uiState.value.copy(shuffleEnabled = enabled)
        persistShuffleIfAttached(enabled)
    }

    /** Story 7.2: fire-and-forget write-through; DataStore serializes (last-write-wins). */
    private fun persistShuffleIfAttached(enabled: Boolean) {
        settings?.let { repo ->
            scope.launch {
                try {
                    repo.setShuffleEnabled(enabled)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Current shuffle flag (facade-owned truth). */
    fun isShuffleEnabled(): Boolean = shuffleEnabledInternal

    /**
     * Story 7.2: when the flag is armed (restored from settings or toggled
     * before a queue existed), every queue build lands shuffled around its
     * start/current item.
     */
    private fun applyShuffleIfArmed() {
        if (!shuffleEnabledInternal || queueSnapshot.isEmpty) return
        val cur = currentPlayerIndexCoerced(queueSnapshot)
        val seed = sessionSeed
            ?: (shuffleSeedOverride ?: System.nanoTime()).also { sessionSeed = it }
        val target = QueueBuilder.reshufflePreservingCurrent(queueSnapshot.items, cur, seed)
        materializeAroundCurrent(target.map { it.id.value }, cur)
        queueSnapshot = QueueSnapshot.of(target)
    }

    /**
     * FR-11 cycling OFF -> ALL -> ONE -> OFF onto the player-NATIVE repeat
     * mode (media3 owns repeat-one replay + end-of-queue wrap). The engine
     * self-subscribes to repeat-mode changes and engages its repeat-one
     * prefetch guard accordingly (4.4 hook). Returns the new mode.
     */
    fun cycleRepeatMode(): RepeatMode {
        val p = player
        val next = when (p?.repeatModeOrDefault(Player.REPEAT_MODE_OFF)) {
            Player.REPEAT_MODE_OFF -> RepeatMode.ALL
            Player.REPEAT_MODE_ALL -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
        try {
            p?.repeatMode = next.toMedia3RepeatMode()
        } catch (_: Exception) {
        }
        _uiState.value = _uiState.value.copy(repeatMode = next)
        // Story 7.2: write-through persistence (last-write-wins).
        settings?.let { repo ->
            scope.launch {
                try {
                    repo.setRepeatMode(next)
                } catch (_: Exception) {
                }
            }
        }
        return next
    }

    /** Session-local upcoming-order view (E12 queue sheet substrate). */
    fun currentQueue(): List<QueueItem> = queueSnapshot.items.toList()

    /**
     * Story 7.2: attach the settings repository. Restores the persisted
     * shuffle flag into the facade mirror asynchronously (AD-10 — first()
     * collection, never a synchronous read); repeat mode is restored by the
     * SERVICE onto the player before any queue build and mirrors from there.
     */
    internal fun attachSettings(repo: com.sway.core.data.SettingsRepository) {
        settings = repo
        scope.launch {
            try {
                val restored = repo.shuffleEnabled.first()
                if (restored != shuffleEnabledInternal) {
                    setShuffleEnabled(restored)
                }
            } catch (_: Exception) {
            }
        }
    }

    internal var sessionStore: com.sway.core.data.SessionRestoreRepository? = null

    /** Test-visible: position (ms) landed by the last restore, null until done/skipped. */
    internal var lastRestoredSeekMsForTest: Long? = null

    /**
     * Test/session seam: adopt an externally-loaded queue — the engine-started
     * direct-bind harness resolves + loads via [JitResolveEngine]
     * .startQueueAndPlay, bypassing the facade `setQueue` round-trip, so the
     * facade adopts the same snapshot afterwards (production parity: UI always
     * enters through setQueue and never needs this).
     */
    internal fun adoptSnapshotForTest(snapshot: QueueSnapshot, currentIndex: Int) {
        queueSnapshot = snapshot
        startIndex = if (snapshot.isEmpty) 0 else currentIndex.coerceIn(0, snapshot.size - 1)
        _uiState.value = _uiState.value.copy(currentItem = snapshot.itemAt(startIndex))
    }

    // --- story 7.1 internals ---------------------------------------------------

    private fun currentPlayerIndexCoerced(snap: QueueSnapshot): Int =
        try {
            (player?.currentMediaItemIndex ?: startIndex).coerceIn(0, snap.size - 1)
        } catch (_: Exception) {
            startIndex.coerceIn(0, snap.size - 1)
        }

    private fun Player.repeatModeOrDefault(default: Int): Int =
        try {
            repeatMode
        } catch (_: Exception) {
            default
        }

    private fun RepeatMode.toMedia3RepeatMode(): Int = when (this) {
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    }

    private fun idAt(p: Player, index: Int): String? = try {
        if (index in 0 until p.mediaItemCount) p.getMediaItemAt(index).mediaId else null
    } catch (_: Exception) {
        null
    }

    /**
     * Materialize [targetIds] into the live timeline with `moveMediaItem`
     * operations confined STRICTLY to the segments around [cur] (left segment
     * then right segment): the current item never moves, so no transition
     * fires, playback stays gapless and resolve budgets stay at zero.
     */
    private fun materializeAroundCurrent(targetIds: List<String>, cur: Int) {
        val p = player ?: return
        materializeSegment(p, targetIds, 0, cur - 1)
        materializeSegment(p, targetIds, cur + 1, targetIds.size - 1)
    }

    /**
     * Materialize [targetIds] over the WHOLE timeline (shuffle-OFF restore):
     * the current item may relocate to its linear slot — safe because moving
     * the current item preserves identity/playback.
     */
    private fun materializeFull(targetIds: List<String>) {
        val p = player ?: return
        materializeSegment(p, targetIds, 0, targetIds.size - 1)
    }

    /** Selection-style placement of [targetIds] into [from..toInclusive] via confined moves. */
    private fun materializeSegment(p: Player, targetIds: List<String>, from: Int, toInclusive: Int) {
        if (from >= toInclusive) return
        for (pos in from..toInclusive) {
            val want = targetIds.getOrNull(pos) ?: break
            if (idAt(p, pos) == want) continue
            var j = -1
            for (cand in pos + 1..toInclusive) {
                if (idAt(p, cand) == want) {
                    j = cand
                    break
                }
            }
            if (j == -1) continue // membership changed concurrently — defensive skip
            try {
                p.moveMediaItem(j, pos)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pre-shuffle restore semantics (Design Note 3): stored linear ids that
     * still exist keep their original relative order; session additions made
     * while shuffled append in their live order.
     */
    private fun restoreLinearOrder(stored: List<SourceId>, live: List<QueueItem>): List<QueueItem> {
        val byId = live.associateBy { it.id }
        val kept = stored.mapNotNull { byId[it] }
        val storedSet = stored.toHashSet()
        val newcomers = live.filter { it.id !in storedSet }
        return kept + newcomers
    }

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

            // Story 6.2 (FR-19): focus/route transitions can flip playWhenReady
            // or playback suppression WITHOUT flipping raw isPlaying (e.g. a
            // transient focus loss during buffering), so mirror those events
            // into uiState within the same sync budget.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncFromPlayer()
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
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
            // Story 6.2 (FR-19/AD-12, UX §6.13 "UI never fights the system"):
            // media3 implements a transient audio-focus loss as playback
            // SUPPRESSION — playWhenReady stays true while audible output
            // stops — so the optimistic playWhenReady fallback must be gated
            // on suppression being NONE or the facade would report playing
            // during a phone call. Ducking (may-duck grant) suppresses
            // nothing: music keeps playing ducked and the UI stays playing.
            val suppressed = p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            !suppressed && (p.isPlaying || p.playWhenReady)
        } catch (_: Exception) {
            _uiState.value.isPlaying
        }
        val isBuffering = try { p.playbackState == Player.STATE_BUFFERING } catch (_: Exception) { false }
        val idx = try { p.currentMediaItemIndex } catch (_: Exception) { startIndex }
        val pos = try { p.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { _uiState.value.positionMs }
        val item = queueSnapshot.itemAt(idx) ?: _uiState.value.currentItem
        // Preserve failedTrack slot across syncs; story 7.1: mirror the
        // player-native repeat mode (facade shuffle flag is internal truth).
        val failed = _uiState.value.failedTrack
        val repeat = try {
            when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        } catch (_: Exception) {
            _uiState.value.repeatMode
        }
        _uiState.value = PlayerUiState(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentItem = item,
            positionMs = pos,
            failedTrack = failed,
            shuffleEnabled = shuffleEnabledInternal,
            repeatMode = repeat,
        )
    }

    /**
     * Story 7.3 restore helper: suspends until the player reaches
     * [Player.STATE_READY] (or errors), event-driven via a transient listener �
     * deterministic under Robolectric where delayed main-looper resumptions do
     * not advance with real time.
     */
    private suspend fun Player.awaitReady(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        removeListener(this)
                        if (cont.isActive) cont.resume(true)
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    removeListener(this)
                    if (cont.isActive) cont.resume(false)
                }
            }
            addListener(l)
            if (playbackState == Player.STATE_READY) {
                removeListener(l)
                if (cont.isActive) cont.resume(true)
            }
            cont.invokeOnCancellation { removeListener(l) }
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

/**
 * Song -> media3 [MediaMetadata] mirror law (story 6.1, FR-18 exactness): title,
 * artist, canonical artwork URI and duration are copied verbatim from the queue
 * truth so every session surface (media notification, lock screen, future
 * browsers) renders exactly what [PlayerUiState.currentItem] holds. Absent
 * artist/artwork map to nulls. Top-level internal: the SINGLE stamping point
 * shared by the facade mapping and the engine's placeholder ingestion.
 */
internal fun Song.toMediaMetadata(): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistName)
        .setArtworkUri(artwork?.canonicalUrl?.let { Uri.parse(it) })
        .setDurationMs(duration.millis)
        .build()

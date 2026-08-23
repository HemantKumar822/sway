package com.sway.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.sway.core.model.AudioRequest
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.StreamResolver
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lazy-resolution engine (story 4.4, FR-12 completes here; AR-5/AD-6 rules 3/6).
 *
 * Owns the three resolution paths against the ONE service-owned player:
 * - **Up-front (budget = 1):** [resolveStartSwap] resolves ONLY the start item
 *   of a freshly commanded queue; every other entry keeps its [PendingUri]
 *   placeholder. Wired to `MediaLibrarySession.Callback.onSetMediaItems`
 *   (session interception) and exposed as [startQueueAndPlay] for direct/test
 *   drives (resolve -> set -> prepare -> play).
 * - **Just-in-time:** as a [Player.Listener] it detects transitions onto a
 *   pending current item (AUTO advance, SEEK, PLAYLIST_CHANGED) and resolves it
 *   under a single-flight guard, swapping the URL in place via
 *   `Player.replaceMediaItem` targeted by mediaId scan (index-drift proof).
 *   Listener attachment means auto-advance works with ZERO controllers bound
 *   (background continuity substrate).
 * - **Optional prefetch:** default-off opportunistic
 *   [StreamResolver.prefetchNext] for the next entry during playback. Never
 *   counts against the up-front budget (distinct resolver call), silent-null on
 *   failure, results held in a cache validated by the single read-time check
 *   ([JitPolicy.isReadValid]) before ANY use, and skipped entirely while
 *   [setRepeatOneRequested] is true (mode flag arrives with E7; the guard hook
 *   is coded now).
 *
 * Story 5.2 (AD-7 defense layer 1): EVERY consumption of a held or freshly
 * resolved rendition — start swap, JIT transition, prefetched cache hit — goes
 * through [resolveForUse], which applies the one [JitPolicy.isReadValid]
 * margin check at use time; a stale cache entry is discarded, invalidated and
 * re-resolved with `forceRefresh` BEFORE play, so playback never inherits a
 * soon-to-die URL.
 *
 * Story 5.3 (AD-7 defense layer 2, FR-13 COMPLETES HERE): when playback errors
 * anyway — a source-class `PlaybackException` (403/410 expired-URL family,
 * codes 2000..2999) raised through `Player.STATE_IDLE` — [onPlayerError]
 * captures the last audible position (live read preferred over the progress
 * ticker snapshot) and the play intent synchronously, then runs a
 * single-flight-per-source renewal: bounded [JitPolicy.MAX_RENEWALS_PER_EPISODE]
 * attempts of invalidate + forceRefresh resolve, applying Success via
 * `replaceMediaItem` (mediaId scan) + `seekTo(captured)` + `prepare()` +
 * conditional `play()`, so the listener never perceives a restart and resume
 * lands within +/-[JitPolicy.RESUME_TOLERANCE_MS]. Budgets reset on successful
 * playback progress ([noteSuccessfulProgress]); exhausted budgets surface the
 * typed category instead of resolving; fatal error classes surface immediately
 * with zero renewal attempts; placeholder items stay owned by the JIT path.
 *
 * Story 5.4 (AD-7 defense layer 3, FR-14 COMPLETES HERE): streams can die
 * SILENTLY — the player parks in `STATE_BUFFERING` with `playWhenReady=true`
 * and a frozen position, and no error ever fires. [startWatchdog] arms a
 * ticker on the engine scope (service lifecycle); every sample runs
 * [onWatchdogTick], which gates itself to true stalls (playing intent +
 * BUFFERING + RESOLVED current item + frozen position + no competing recovery
 * pipeline) and escalates via the pure [JitPolicy.watchdogAction] ladder:
 * >[JitPolicy.WATCHDOG_SOFT_STALL_MS] ONE downscale replay at
 * [JitPolicy.DOWNSCALE_QUALITY]; >[JitPolicy.WATCHDOG_HARD_STALL_MS] full
 * stream rebuilds up to [JitPolicy.MAX_REBUILDS_PER_EPISODE]; exhausted budget
 * while still stalled skips to the next queue item and surfaces the typed
 * category through the shared slot (last item pauses instead — honest stop).
 * Recovery rides the SAME apply sequence as layer-2 renewal; actions are
 * spaced >=[JitPolicy.WATCHDOG_ACTION_SPACING_MS]; accounting resets on item
 * transition and successful progress, so normal gapless transitions never
 * accrue stall debt.
 *
 * Failures travel as typed values: every resolve failure publishes
 * [FailedTrack] to the injected [onFailure] handler and hoists it on
 * [latestFailure] — never a crash, never a thrown exception across the session
 * boundary (AR-8/AD-9).
 *
 * Threading: constructed and driven on the main thread (session callbacks and
 * player events are main-thread); resolution coroutines run on [scope], which
 * production wires as `Dispatchers.Main.immediate`; player mutations guard onto
 * the main looper anyway.
 *
 * Queue metadata: service-side code sees mediaIds only, so
 * [attachQueueMetadata] lets callers (tests now, E7 glue later) register
 * [QueueItem] snapshots used to populate [FailedTrack.item]; unknown ids
 * degrade to a minimal id-titled item rather than dropping the typed failure.
 */
internal class JitResolveEngine(
    private val player: Player,
    private val resolver: StreamResolver,
    private val scope: CoroutineScope,
    private val onFailure: (FailedTrack) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Player.Listener {

    private val _latestFailure = MutableStateFlow<FailedTrack?>(null)

    /** Latest typed failure (null until a resolve fails). Hoisted for observers/glue. */
    val latestFailure: StateFlow<FailedTrack?> = _latestFailure.asStateFlow()

    private var prefetchEnabled = false

    @Volatile
    private var repeatOneRequested = false

    private val queueItems = mutableMapOf<SourceId, QueueItem>()
    private val prefetchCache = mutableMapOf<SourceId, ResolvedAudio>()
    private val inFlightPrefetches = mutableSetOf<SourceId>()

    private var jitJob: Job? = null
    private var desiredTargetId: SourceId? = null
    private var released = false

    // --- story 5.3: error-triggered renewal state ------------------------------

    /** One renewal pipeline per SourceId at a time (single-flight dedup). */
    private val renewalJobs = mutableMapOf<SourceId, Job>()

    /**
     * Resolve attempts spent per SourceId since the last successful-progress
     * observation (progress-episode budget; NFR-3 anti-hot-loop law).
     */
    private val renewalBudgets = mutableMapOf<SourceId, Int>()

    /** Service-side progress ticker snapshot: last position with audible flow. */
    private var lastAudibleProgressMs: Long = 0L

    /** Sticky per-item evidence that audio actually played (survives pause). */
    private var audiblePlayingObserved: Boolean = false

    // --- story 5.4: stalled-playback watchdog state ------------------------------

    /** Production ticker loop (single-flight; cancelled by [release]). */
    private var watchdogJob: Job? = null

    /** The ONE watchdog recovery pipeline at a time (single-owner law). */
    @Volatile
    private var watchdogRecoveryJob: Job? = null

    /** Epoch-ms baseline: when the current freeze window began. */
    private var stallBaselineMs: Long = 0L

    /** Position observed at the previous sample (progress detector). */
    private var lastTickPositionMs: Long = Long.MIN_VALUE

    /** Epoch-ms of the most recent escalation action (spacing gate). */
    private var lastWatchdogActionAtMs: Long = Long.MIN_VALUE

    /** Per-item latch: the soft-tier downscale replay already fired. */
    private var downgradeAttempted: Boolean = false

    /** Per-item counter: full rebuilds spent this stall episode. */
    private var rebuildAttempts: Int = 0

    /** mediaId owning the open stall episode (identity-scoped memory). */
    private var watchdogEpisodeItemId: String? = null

    init {
        player.addListener(this)
    }

    /** Detach from the player; engine becomes inert afterwards. Idempotent. */
    fun release() {
        if (released) return
        released = true
        watchdogJob?.cancel()
        watchdogJob = null
        watchdogRecoveryJob?.cancel()
        watchdogRecoveryJob = null
        player.removeListener(this)
    }

    /**
     * Register [QueueItem] metadata so failures carry the full snapshot row
     * ([FailedTrack.item]); unregistered ids fall back to a minimal item.
     */
    fun attachQueueMetadata(snapshot: QueueSnapshot) {
        snapshot.items.forEach { queueItems[it.id] = it }
    }

    /** Enable/disable opportunistic prefetch (default OFF; story marks it optional). */
    fun setPrefetchEnabled(enabled: Boolean) {
        prefetchEnabled = enabled
    }

    /**
     * Repeat-one guard hook (E7 arrival point): when true, [maybePrefetchNext]
     * short-circuits — prefetching the "next" entry is meaningless in repeat-one
     * mode. The mode flag/persistence arrives with the queue-management epic;
     * only this boolean hook exists now.
     */
    fun setRepeatOneRequested(requested: Boolean) {
        repeatOneRequested = requested
    }

    // --- up-front path (budget = 1) ---------------------------------------------

    /**
     * Resolve ONLY the start item of [mediaItems] at [startIndex]; all other
     * entries keep their placeholders untouched. On success the returned list
     * carries the real stream URL at the start position; on failure the ORIGINAL
     * placeholder list is returned unchanged (the queue still loads; the typed
     * failure surfaces via [onFailure]/[latestFailure]) — never throws.
     */
    suspend fun resolveStartSwap(mediaItems: List<MediaItem>, startIndex: Int): List<MediaItem> {
        if (mediaItems.isEmpty()) return mediaItems
        val idx = JitPolicy.coerceStartIndex(startIndex, mediaItems.size)
        val uri = JitPolicy.uriStringOf(mediaItems[idx])
        if (!JitPolicy.shouldResolveNow(uri)) return mediaItems
        val sourceId = PendingUri.extractSourceId(uri) ?: return mediaItems
        return when (val result = resolveForUse(sourceId)) {
            is SwayResult.Success -> mediaItems.mapIndexed { i, item ->
                if (i == idx) JitPolicy.withResolvedUri(item, result.data.url) else item
            }
            is SwayResult.Failure -> {
                publishFailure(sourceId, result.error)
                mediaItems
            }
        }
    }

    /**
     * Direct-drive variant (tests + future E7 glue): resolve the start item,
     * land the swapped queue on the player, prepare, play. Production instead
     * intercepts `onSetMediaItems` and lets forwarded prepare/play commands do
     * the last two steps.
     */
    fun startQueueAndPlay(mediaItems: List<MediaItem>, startIndex: Int) {
        scope.launch {
            val swapped: List<MediaItem> = try {
                resolveStartSwap(mediaItems, startIndex)
            } catch (t: Throwable) {
                // Typed-failure discipline (AR-8): even a contract-violating
                // throwable becomes a value, never an escaped exception.
                startSourceId(mediaItems, startIndex)?.let { publishFailure(it, SwayError.Unknown(t)) }
                mediaItems
            }
            onMainLooper {
                try {
                    player.setMediaItems(swapped, JitPolicy.coerceStartIndex(startIndex, swapped.size), 0L)
                    player.prepare()
                    player.play()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Start-item [SourceId] iff the entry at [startIndex] rides a placeholder. */
    private fun startSourceId(mediaItems: List<MediaItem>, startIndex: Int): SourceId? {
        if (mediaItems.isEmpty()) return null
        val uri = JitPolicy.uriStringOf(mediaItems[JitPolicy.coerceStartIndex(startIndex, mediaItems.size)])
        if (!JitPolicy.shouldResolveNow(uri)) return null
        return PendingUri.extractSourceId(uri)
    }

    // --- just-in-time path --------------------------------------------------------

    /**
     * Inspect the CURRENT player item and ensure a JIT resolve is running for
     * it. Exactly ONE worker job ever exists (single-flight): invocations made
     * while it is active merely update the desired target, so rapid duplicate
     * transitions onto the same unresolved item coalesce into one `resolveAudio`,
     * and seek-ahead-during-resolve is absorbed sequentially by the worker's
     * re-check loop. Failed attempts are never auto-retried (that would hot-
     * loop); the next transition event retries naturally since the placeholder
     * is left in place.
     */
    fun handlePendingCurrent() {
        if (released) return
        val sourceId = currentPendingSourceId() ?: return
        desiredTargetId = sourceId
        if (jitJob?.isActive == true) return
        jitJob = scope.launch {
            var lastAttempted: SourceId? = null
            while (!released) {
                val target = desiredTargetId ?: break
                if (target == lastAttempted) break
                lastAttempted = target
                val outcome = resolveForUse(target)
                applyOutcome(target, outcome)
            }
            desiredTargetId = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new item must re-earn audible-progress evidence (story 5.3).
        audiblePlayingObserved = false
        lastAudibleProgressMs = 0L
        val newId = mediaItem?.mediaId
        if (newId == null || newId != watchdogEpisodeItemId) {
            // Genuinely different item: fresh ladder (story 5.4 — transition
            // timing is EXCLUDED from stall accounting, so normal gapless
            // buffering never accrues debt).
            resetWatchdogEpisode()
            watchdogEpisodeItemId = newId
        }
        // Same-mediaId transitions are OUR OWN rendition replacements (layer-2
        // or watchdog swaps): the stall episode CONTINUES — cumulative frozen
        // time plus the action-spacing gate give the fresh rendition its
        // observation window without erasing earned escalation memory.
        handlePendingCurrent()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
            maybePrefetchNext()
        }
        if (playbackState == Player.STATE_READY) noteSuccessfulProgress()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) noteSuccessfulProgress()
    }

    /**
     * Successful playback progress observed (isPlaying=true or STATE_READY):
     * refreshes the ticker snapshot and clears ALL renewal budgets — a healthy
     * episode just ended, so the next expiry gets a full budget again (FR-13).
     * Internal seam: production fires via player listeners, tests may drive it
     * directly (mirrors [handlePendingCurrent]).
     */
    internal fun noteSuccessfulProgress() {
        audiblePlayingObserved = true
        val live = try {
            player.currentPosition
        } catch (_: Exception) {
            0L
        }
        if (live > 0L) lastAudibleProgressMs = live
        renewalBudgets.clear()
        resetWatchdogEpisode()
    }

    // --- error-triggered renewal (story 5.3, AD-7 defense layer 2) -----------------

    /**
     * Player error event (fires on the main looper BEFORE the IDLE state
     * change reaches other listeners). Delegates to [handlePlayerError] with
     * the typed error code; the exception itself is preserved as the cause for
     * fatal-class surfacing (AR-14).
     */
    override fun onPlayerError(error: PlaybackException) {
        handlePlayerError(error.errorCode, error)
    }

    /**
     * Layer-2 entry: classify [errorCode] and either renew invisibly or
     * surface the typed category. Order of guards:
     * 1. Placeholder current items -> skip (the JIT worker owns them).
     * 2. Fatal class (outside the source window) -> immediate typed failure,
     *    ZERO renewal attempts.
     * 3. No audible-progress evidence -> silent skip (layer 2 is for MID-play
     *    death; also keeps environmental prepare noise out of the budget).
     * 4. Budget spent -> typed failure without resolving (no hot loop).
     * 5. Renewal already in flight for this SourceId -> coalesce (single
     *    flight; concurrent duplicate errors share one resolve).
     * Position and play intent are captured synchronously here — before any
     * later player mutation can reset them.
     */
    internal fun handlePlayerError(errorCode: Int, cause: Throwable? = null) {
        if (released) return
        val sourceId = currentResolvedSourceId() ?: return
        if (!JitPolicy.isExpiryRetryableSourceError(errorCode)) {
            publishFailure(sourceId, JitPolicy.mapPlayerErrorToSwayError(errorCode, cause))
            return
        }
        // Story 5.4 single-owner law (reverse direction): a watchdog recovery
        // pipeline owns the current item right now — retryable renewal defers
        // to its ladder (fatal classes above still surface immediately).
        if (watchdogRecoveryJob?.isActive == true) return
        val resumePositionMs = captureResumePositionMs()
        if (!JitPolicy.isRenewalEligible(resumePositionMs, audiblePlayingObserved)) return
        if ((renewalBudgets[sourceId] ?: 0) >= JitPolicy.MAX_RENEWALS_PER_EPISODE) {
            publishFailure(sourceId, JitPolicy.mapPlayerErrorToSwayError(errorCode, cause))
            return
        }
        if (renewalJobs[sourceId]?.isActive == true) return
        val wasPlaying = try {
            player.playWhenReady
        } catch (_: Exception) {
            false
        }
        renewalJobs[sourceId] = scope.launch {
            runRenewal(sourceId, resumePositionMs, wasPlaying, errorCode, cause)
        }
    }

    /**
     * Last audible position at error time: the live read wins when it carries
     * information (ExoPlayer retains the error position until prepare);
     * otherwise the progress-ticker snapshot taken during playback does.
     */
    private fun captureResumePositionMs(): Long {
        val live = try {
            player.currentPosition
        } catch (_: Exception) {
            0L
        }
        return if (live > 0L) live else lastAudibleProgressMs
    }

    /**
     * [SourceId] of the current item iff it holds a RESOLVED rendition.
     * Placeholder items return null — their failures belong to the JIT
     * resolution path, never to layer-2 renewal.
     */
    private fun currentResolvedSourceId(): SourceId? {
        val index = currentIndexOrNull() ?: return null
        val item = itemAtOrNull(index) ?: return null
        val uri = JitPolicy.uriStringOf(item)
        if (uri == null || JitPolicy.shouldResolveNow(uri)) return null
        return SourceId.parse(item.mediaId ?: return null)
    }

    /**
     * Bounded renewal loop for one trigger: spend up to
     * [JitPolicy.MAX_RENEWALS_PER_EPISODE] attempts of invalidate +
     * forceRefresh resolve; the FIRST Success applies the fresh rendition
     * ([applyRenewedRendition]) and stops; exhausting the budget publishes
     * the last resolver failure. Never throws.
     */
    private suspend fun runRenewal(
        sourceId: SourceId,
        resumePositionMs: Long,
        wasPlaying: Boolean,
        errorCode: Int,
        cause: Throwable?,
    ) {
        try {
            var lastError: SwayError = JitPolicy.mapPlayerErrorToSwayError(errorCode, cause)
            while (!released && (renewalBudgets[sourceId] ?: 0) < JitPolicy.MAX_RENEWALS_PER_EPISODE) {
                renewalBudgets[sourceId] = (renewalBudgets[sourceId] ?: 0) + 1
                safeInvalidate(sourceId)
                when (val result = safeResolveAudio(sourceId, AudioRequest.refresh())) {
                    is SwayResult.Success -> {
                        applyRenewedRendition(sourceId, result.data, resumePositionMs, wasPlaying)
                        return
                    }
                    is SwayResult.Failure -> lastError = result.error
                }
            }
            if (!released) publishFailure(sourceId, lastError)
        } finally {
            renewalJobs.remove(sourceId)
        }
    }

    /**
     * Swap the renewed URL into the player by MEDIA-ID scan and restore the
     * listener's world exactly: `replaceMediaItem` (identity preserved) ->
     * `seekTo` the captured position (lands within +/-[JitPolicy.RESUME_TOLERANCE_MS];
     * the mechanism restores it exactly) -> `prepare` (required after an
     * error-driven IDLE) -> `play` only when the user was playing. Missing
     * ids skip silently (item vanished mid-renewal; 4.4 semantics).
     */
    private fun applyRenewedRendition(
        sourceId: SourceId,
        audio: ResolvedAudio,
        resumePositionMs: Long,
        wasPlaying: Boolean,
    ) {
        onMainLooper {
            if (released) return@onMainLooper
            val index = indexOfMediaId(sourceId.value) ?: return@onMainLooper
            val original = itemAtOrNull(index) ?: return@onMainLooper
            try {
                player.replaceMediaItem(index, JitPolicy.withResolvedUri(original, audio.url))
                player.seekTo(JitPolicy.clampResumePosition(resumePositionMs))
                player.prepare()
                if (wasPlaying) player.play()
            } catch (_: Exception) {
            }
        }
    }

    // --- stalled-playback watchdog (story 5.4, AD-7 defense layer 3) ---------------

    /**
     * Arm the production ticker: samples [onWatchdogTick] every
     * [JitPolicy.WATCHDOG_TICK_MS] on the engine scope (service lifecycle —
     * cancelled by [release]; NO app-wide ticking broadcast). Single-flight.
     * Tests drive [onWatchdogTick] directly with synthetic timestamps instead
     * of relying on scheduler/virtual-time coupling.
     */
    fun startWatchdog() {
        if (released || watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (!released) {
                delay(JitPolicy.WATCHDOG_TICK_MS)
                if (released) break
                onMainLooper { onWatchdogTick(clock()) }
            }
        }
    }

    /**
     * One watchdog sample (internal seam; production enters via
     * [startWatchdog]). Gate order:
     * 1. released, own recovery in flight, or any layer-2 renewal in flight
     *    -> nothing to decide (single owner of recovery).
     * 2. Not a stall candidate (paused / READY / IDLE / ENDED) or a player
     *    error is present -> reset timing debt (idle self-stop flow untouched).
     * 3. Placeholder current item -> the JIT worker owns it -> reset debt.
     * 4. Position advanced since the previous sample -> progress law wins ->
     *    reset debt.
     * 5. Genuinely frozen -> escalate via the pure ladder.
     */
    internal fun onWatchdogTick(nowMs: Long) {
        if (released) return
        val renewalInFlight = renewalJobs.values.any { it.isActive }
        if (
            JitPolicy.isWatchdogSuppressed(renewalInFlight, watchdogRecoveryJob?.isActive == true)
        ) {
            return
        }
        val playWhenReady = try {
            player.playWhenReady
        } catch (_: Exception) {
            false
        }
        val state = try {
            player.playbackState
        } catch (_: Exception) {
            Player.STATE_IDLE
        }
        val errorPresent = try {
            player.playerError != null
        } catch (_: Exception) {
            false
        }
        if (!JitPolicy.isStallCandidate(playWhenReady, state) || errorPresent) {
            resetStallClock(nowMs)
            return
        }
        val sourceId = currentResolvedSourceId()
        if (sourceId == null) {
            // Placeholder current items belong to the JIT resolution path.
            resetStallClock(nowMs)
            return
        }
        val positionMs = livePositionMs()
        if (positionMs != lastTickPositionMs) {
            // Buffer/seek progress observed during the window: debt restarts.
            resetStallClock(nowMs)
            return
        }
        when (
            JitPolicy.watchdogAction(
                nowMs - stallBaselineMs,
                downgradeAttempted,
                rebuildAttempts,
                msSinceLastAction(nowMs),
            )
        ) {
            JitPolicy.WatchdogAction.None -> Unit
            JitPolicy.WatchdogAction.Downscale -> {
                downgradeAttempted = true
                markWatchdogAction(nowMs)
                launchWatchdogRecovery(sourceId!!, AudioRequest.refresh(JitPolicy.DOWNSCALE_QUALITY))
            }
            JitPolicy.WatchdogAction.Rebuild -> {
                rebuildAttempts++
                markWatchdogAction(nowMs)
                launchWatchdogRecovery(sourceId!!, AudioRequest.refresh())
            }
            JitPolicy.WatchdogAction.Skip -> skipStalledCurrent(sourceId!!, nowMs)
        }
    }

    /**
     * Single-flight watchdog recovery for the current item: invalidate +
     * typed resolve ([request]), then apply through the SHARED renewal
     * sequence (mediaId-scan swap -> seekTo(captured) -> prepare -> conditional
     * play). Resolver Failure is deliberately silent here — the escalation
     * ladder keeps running and the SKIP tier owns typed surfacing. Never
     * throws; clears its single-flight slot on completion.
     */
    private fun launchWatchdogRecovery(sourceId: SourceId, request: AudioRequest) {
        val resumePositionMs = captureResumePositionMs()
        val wasPlaying = try {
            player.playWhenReady
        } catch (_: Exception) {
            false
        }
        val job = scope.launch {
            safeInvalidate(sourceId)
            when (val result = safeResolveAudio(sourceId, request)) {
                is SwayResult.Success ->
                    applyRenewedRendition(sourceId, result.data, resumePositionMs, wasPlaying)
                is SwayResult.Failure -> Unit
            }
        }
        watchdogRecoveryJob = job
        job.invokeOnCompletion { if (watchdogRecoveryJob === job) watchdogRecoveryJob = null }
    }

    /**
     * Honest escalation end (FR-14): publish the typed category through the
     * shared slot (uiState.failedTrack glue consumes it) BEFORE advancing;
     * prefer `seekToNextMediaItem` (the JIT path resolves the next placeholder
     * naturally); on the last item PAUSE instead of looping on a dead tail.
     * The episode resets so the next item owns a fresh ladder. Never throws.
     */
    private fun skipStalledCurrent(sourceId: SourceId, nowMs: Long) {
        markWatchdogAction(nowMs)
        resetWatchdogEpisode()
        publishFailure(sourceId, SwayError.UpstreamUnavailable)
        onMainLooper {
            if (released) return@onMainLooper
            try {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.pause()
            } catch (_: Exception) {
            }
        }
    }

    /** Reset freeze accounting only; per-item escalation memory is kept. */
    private fun resetStallClock(nowMs: Long) {
        stallBaselineMs = nowMs
        lastTickPositionMs = livePositionMs()
    }

    /**
     * Full episode reset — timing debt AND escalation memory. Fires on item
     * transition (gapless transitions never accrue stall debt) and on
     * successful-progress observation ([noteSuccessfulProgress]).
     */
    private fun resetWatchdogEpisode() {
        downgradeAttempted = false
        rebuildAttempts = 0
        lastWatchdogActionAtMs = Long.MIN_VALUE
        lastTickPositionMs = Long.MIN_VALUE
        stallBaselineMs = clock()
    }

    private fun markWatchdogAction(nowMs: Long) {
        lastWatchdogActionAtMs = nowMs
    }

    private fun msSinceLastAction(nowMs: Long): Long =
        if (lastWatchdogActionAtMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastWatchdogActionAtMs

    private fun livePositionMs(): Long = try {
        player.currentPosition
    } catch (_: Exception) {
        0L
    }

    // --- prefetch -------------------------------------------------------------------

    /**
     * Opportunistic prefetch of the NEXT entry during playback. Conditions:
     * explicitly enabled, repeat-one flag clear, a next entry exists and rides a
     * placeholder, no cache/in-flight duplicate. Uses
     * [StreamResolver.prefetchNext] exclusively (never counts against FR-12's
     * up-front budget), tolerates its silent-null contract, stores results
     * behind the single read-time validity check ([JitPolicy.isReadValid]).
     */
    fun maybePrefetchNext() {
        if (!prefetchEnabled || repeatOneRequested || released) return
        val index = currentIndexOrNull() ?: return
        val next = itemAtOrNull(index + 1) ?: return
        val uri = JitPolicy.uriStringOf(next)
        if (!JitPolicy.shouldResolveNow(uri)) return
        val sourceId = PendingUri.extractSourceId(uri) ?: return
        if (prefetchCache.containsKey(sourceId)) return
        if (sourceId in inFlightPrefetches) return
        inFlightPrefetches += sourceId
        scope.launch {
            try {
                val audio = resolver.prefetchNext(sourceId, AudioRequest.Default)
                if (audio != null) prefetchCache[sourceId] = audio
            } finally {
                inFlightPrefetches -= sourceId
            }
        }
    }

    // --- internals ------------------------------------------------------------------

    /**
     * THE single read path for any rendition about to be used (story 5.2,
     * AD-7 defense layer 1). Order:
     * 1. A prefetched cache entry is consumed only when it passes the one
     *    [JitPolicy.isReadValid] margin check at read time.
     * 2. A stale entry is DISCARDED: `invalidate` purges resolver-side state
     *    and a fresh `resolveAudio` with `forceRefresh = true` runs BEFORE play.
     * 3. A fresh resolve whose URL is itself marginal earns exactly ONE forced
     *    revalidation (bounded — a pathological resolver can neither hot-loop
     *    the worker nor inflate happy-path budgets); the freshest result wins,
     *    best-effort (layer 2 owns genuine mid-play expiry).
     */
    private suspend fun resolveForUse(sourceId: SourceId): SwayResult<ResolvedAudio> {
        val cached = prefetchCache.remove(sourceId)
        if (JitPolicy.isReadValid(cached, clock())) {
            return SwayResult.Success(cached!!)
        }
        if (cached != null) {
            safeInvalidate(sourceId)
            return forcedRefreshOrKeep(sourceId, fallback = null)
        }
        return when (val outcome = safeResolveAudio(sourceId, AudioRequest.Default)) {
            is SwayResult.Failure -> outcome
            is SwayResult.Success ->
                if (JitPolicy.isReadValid(outcome.data, clock())) outcome
                else forcedRefreshOrKeep(sourceId, fallback = outcome.data)
        }
    }

    /**
     * Exactly ONE forced-refresh resolve for a failing read-validation check:
     * its Success replaces the rejected rendition; on Failure the previous
     * best answer wins ([fallback] — null for stale cache entries, whose
     * discard is strict), so a working-now URL beats a typed failure that
     * layer 2 (5.3) exists to handle.
     */
    private suspend fun forcedRefreshOrKeep(
        sourceId: SourceId,
        fallback: ResolvedAudio?,
    ): SwayResult<ResolvedAudio> =
        when (val retried = safeResolveAudio(sourceId, AudioRequest.refresh())) {
            is SwayResult.Success -> retried
            is SwayResult.Failure -> fallback?.let { SwayResult.Success(it) } ?: retried
        }

    /** Purge resolver-side state for a rejected rendition; never throws. */
    private fun safeInvalidate(sourceId: SourceId) {
        try {
            resolver.invalidate(sourceId)
        } catch (_: Exception) {
        }
    }

    /** Resolver call converted to a typed value; contract-violating throws become [SwayError.Unknown]. */
    private suspend fun safeResolveAudio(
        sourceId: SourceId,
        request: AudioRequest,
    ): SwayResult<ResolvedAudio> =
        try {
            resolver.resolveAudio(sourceId, request)
        } catch (t: Throwable) {
            SwayResult.Failure(SwayError.Unknown(t))
        }

    /**
     * Swap a resolved URL into the player by MEDIA-ID scan (immune to index
     * drift between transition event and application); missing ids skip
     * silently. Failures publish typed [FailedTrack] and leave the placeholder
     * in place so a later transition may retry.
     */
    private fun applyOutcome(sourceId: SourceId, result: SwayResult<ResolvedAudio>) {
        when (result) {
            is SwayResult.Success -> onMainLooper {
                if (released) return@onMainLooper
                val index = indexOfMediaId(sourceId.value) ?: return@onMainLooper
                val original = itemAtOrNull(index) ?: return@onMainLooper
                try {
                    player.replaceMediaItem(index, JitPolicy.withResolvedUri(original, result.data.url))
                } catch (_: Exception) {
                }
            }
            is SwayResult.Failure -> publishFailure(sourceId, result.error)
        }
        maybePrefetchNext()
    }

    /** SourceId of the current player item iff it still rides a placeholder. */
    private fun currentPendingSourceId(): SourceId? {
        val index = currentIndexOrNull() ?: return null
        val uri = JitPolicy.uriStringOf(itemAtOrNull(index))
        if (!JitPolicy.shouldResolveNow(uri)) return null
        return PendingUri.extractSourceId(uri)
    }

    private fun currentIndexOrNull(): Int? = try {
        if (released || player.mediaItemCount == 0) null else player.currentMediaItemIndex
    } catch (_: Exception) {
        null
    }

    private fun itemAtOrNull(index: Int): MediaItem? = try {
        if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index) else null
    } catch (_: Exception) {
        null
    }

    /** Index of the item whose mediaId equals [mediaId], or null when absent. */
    private fun indexOfMediaId(mediaId: String): Int? = try {
        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    } catch (_: Exception) {
        null
    }

    /** Publish the typed failure value; never throws. */
    private fun publishFailure(sourceId: SourceId, error: SwayError) {
        val track = FailedTrack(queueItems[sourceId] ?: fallbackItem(sourceId), error)
        _latestFailure.value = track
        try {
            onFailure(track)
        } catch (_: Throwable) {
        }
    }

    /** Minimal honest row for unregistered ids (identity preserved, id as title). */
    private fun fallbackItem(sourceId: SourceId): QueueItem =
        QueueItem.of(Song.createTyped(id = sourceId, rawTitle = sourceId.value))

    private inline fun onMainLooper(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }
}

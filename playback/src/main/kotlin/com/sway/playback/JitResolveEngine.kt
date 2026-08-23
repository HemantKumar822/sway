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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pure decision helpers for lazy resolution (story 4.4, FR-12/AR-6), the
 * story 5.2 read-time validation layer (AD-7 defense layer 1, NFR-3) and the
 * story 5.3 error-triggered renewal layer (AD-7 defense layer 2, FR-13) —
 * trivially unit-testable policy extracted from [JitResolveEngine].
 *
 * - [shouldResolveNow] — a URI earns a just-in-time resolve iff it is a
 *   [PendingUri] placeholder (single-owner scheme law, AD-6 rule 6).
 * - [isReadValid] — THE single read-time validity check: any held or freshly
 *   resolved [ResolvedAudio] may be used only while more than
 *   [READ_MARGIN_MS] of lifetime remains at the moment of use. The former
 *   prefetch age cap folded into this one check (no second mechanism).
 * - [isExpiryRetryableSourceError] / [mapPlayerErrorToSwayError] /
 *   [isRenewalEligible] / [clampResumePosition] — layer-2 renewal law:
 *   classify source-class expiry errors, map surfacing categories, gate
 *   eligibility on audible-progress evidence and clamp resume seeks to
 *   [RESUME_TOLERANCE_MS] of the captured position (mechanism restores it
 *   exactly).
 * - [coerceStartIndex] — normalizes session-provided start indices
 *   ([C.INDEX_UNSET], out-of-bounds, empty playlists) to a safe anchor.
 * - [withResolvedUri] — rebuilds a queue item keeping its identity (mediaId)
 *   while swapping the placeholder for the resolved stream URL.
 */
internal object JitPolicy {

    /**
     * Read-time validity margin, P-5-tunable initial target (NFR-3 / AD-7
     * layer 1): a stream URL must outlive "now" by more than this much at the
     * moment of use, otherwise it is discarded and re-resolved before play.
     */
    const val READ_MARGIN_MS: Long = 5L * 60L * 1000L

    /**
     * Source-class error-code window (story 5.3, FR-13 / AD-7 defense layer 2):
     * `PlaybackException` codes 2000..2999 cover data-loading failures —
     * HTTP status errors (`ERROR_CODE_IO_BAD_HTTP_STATUS` = 2004 carries the
     * 403/410 expired-URL family), file-not-found and network failures — i.e.
     * exactly the "playback errored anyway after layer 1" class that earns an
     * invisible renewal. Codes outside the window are fatal for renewal.
     */
    const val SOURCE_ERROR_CODE_MIN: Int = 2000

    /** Inclusive upper bound of the source-class window; see [SOURCE_ERROR_CODE_MIN]. */
    const val SOURCE_ERROR_CODE_MAX: Int = 2999

    /**
     * Resume tolerance for error-triggered renewal, P-5-tunable initial target
     * (FR-13 / NFR-3 / SM-2): audible resume must land within this window of
     * the last audible position. The renewal mechanism restores the captured
     * position exactly; the bound exists for wall-clock drift in production.
     */
    const val RESUME_TOLERANCE_MS: Long = 3_000L

    /**
     * Renewal budget per SourceId per progress-episode (NFR-3 anti-hot-loop
     * law): at most this many invalidate+forceRefresh resolve attempts may be
     * spent on one item before the typed failure surfaces instead. The budget
     * resets when successful playback progress is observed again.
     */
    const val MAX_RENEWALS_PER_EPISODE: Int = 2

    /** True iff [uriString] is a sway pending placeholder needing JIT resolution. */
    fun shouldResolveNow(uriString: String?): Boolean = PendingUri.isPending(uriString)

    /**
     * Read-time validation (AD-7 layer 1): [audio] may be used at [nowEpochMs]
     * only when present AND its own parsed expiry lies further in the future
     * than [READ_MARGIN_MS]. Entries failing this are discarded and renewed
     * (invalidate + forceRefresh resolve) BEFORE play.
     */
    fun isReadValid(audio: ResolvedAudio?, nowEpochMs: Long): Boolean =
        audio != null && !audio.isExpiredAt(nowEpochMs, READ_MARGIN_MS)

    /** True iff [errorCode] sits in the retryable source-class expiry window. */
    fun isExpiryRetryableSourceError(errorCode: Int): Boolean =
        errorCode in SOURCE_ERROR_CODE_MIN..SOURCE_ERROR_CODE_MAX

    /**
     * Typed category for a player error surfaced after its renewal budget is
     * spent (or immediately, when fatal): source-class codes map to
     * [SwayError.UpstreamUnavailable] (HTTP-status family per AD-9 row 3);
     * everything else is [SwayError.Unknown] preserving the cause (AR-14).
     */
    fun mapPlayerErrorToSwayError(errorCode: Int, cause: Throwable? = null): SwayError =
        if (isExpiryRetryableSourceError(errorCode)) SwayError.UpstreamUnavailable
        else SwayError.Unknown(cause)

    /**
     * Renewal eligibility (story 5.3): renewal is layer 2 for MID-play death,
     * so audible-progress evidence must exist — either a captured position
     * beyond the track start or an observed playing state for the item.
     * Position-0-never-played failures belong to layer 1 / the JIT path /
     * the 5.4 watchdog backstop, and this filter keeps environmental prepare
     * noise out of the renewal budget.
     */
    fun isRenewalEligible(capturedPositionMs: Long, playingObserved: Boolean): Boolean =
        capturedPositionMs > 0L || playingObserved

    /** Resume positions are clamped to the track start; never negative. */
    fun clampResumePosition(positionMs: Long): Long = positionMs.coerceAtLeast(0L)

    /** Safe start anchor: [C.INDEX_UNSET]/out-of-bounds degrade to 0 / last item. */
    fun coerceStartIndex(startIndex: Int, size: Int): Int {
        if (size <= 0) return 0
        if (startIndex < 0 || startIndex >= size) return 0
        return startIndex
    }

    /** Same item identity (mediaId/metadata), real stream [url] instead of placeholder. */
    fun withResolvedUri(original: MediaItem, url: String): MediaItem =
        original.buildUpon().setUri(url).build()

    /** Raw placeholder-or-real URI string of [item], or null when unconfigured. */
    fun uriStringOf(item: MediaItem?): String? = item?.localConfiguration?.uri?.toString()
}

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

    init {
        player.addListener(this)
    }

    /** Detach from the player; engine becomes inert afterwards. Idempotent. */
    fun release() {
        if (released) return
        released = true
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

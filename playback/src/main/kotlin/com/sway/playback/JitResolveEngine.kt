package com.sway.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
 * Pure decision helpers for lazy resolution (story 4.4, FR-12/AR-6) and the
 * story 5.2 read-time validation layer (AD-7 defense layer 1, NFR-3) —
 * trivially unit-testable policy extracted from [JitResolveEngine].
 *
 * - [shouldResolveNow] — a URI earns a just-in-time resolve iff it is a
 *   [PendingUri] placeholder (single-owner scheme law, AD-6 rule 6).
 * - [isReadValid] — THE single read-time validity check: any held or freshly
 *   resolved [ResolvedAudio] may be used only while more than
 *   [READ_MARGIN_MS] of lifetime remains at the moment of use. The former
 *   prefetch age cap folded into this one check (no second mechanism).
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
        handlePendingCurrent()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
            maybePrefetchNext()
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

package com.sway.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.SwayError

/**
 * Pure decision helpers for lazy resolution (story 4.4, FR-12/AR-6), the
 * story 5.2 read-time validation layer (AD-7 defense layer 1, NFR-3), the
 * story 5.3 error-triggered renewal layer (AD-7 defense layer 2, FR-13) and
 * the story 5.4 stalled-playback watchdog (AD-7 defense layer 3) —
 * trivially unit-testable policy extracted from [JitResolveEngine]
 * (NFR-7 LOC budget: the engine facade delegates to this focused sub-object).
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
 * - [watchdogAction] / [isStallCandidate] / [isWatchdogSuppressed] / P-5
 *   constants (`WATCHDOG_SOFT_STALL_MS`/`WATCHDOG_HARD_STALL_MS`) — layer-3
 *   stalled-playback law: pure escalation ladder from frozen-BUFFERING time
 *   to downscale replay, full rebuild and honest skip.
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

    /**
     * Watchdog SOFT threshold (story 5.4, FR-14 / AD-7 layer 3, P-5 initial
     * target): a current item frozen in `STATE_BUFFERING` with
     * `playWhenReady=true` for longer than this earns ONE downscale replay —
     * a re-resolve at [DOWNSCALE_QUALITY] swapped in place.
     */
    const val WATCHDOG_SOFT_STALL_MS: Long = 3_000L

    /**
     * Watchdog HARD threshold (P-5 initial target, cumulative frozen time):
     * escalates to full stream rebuilds (invalidate + forceRefresh) up to
     * [MAX_REBUILDS_PER_EPISODE], then an honest skip with typed reason.
     */
    const val WATCHDOG_HARD_STALL_MS: Long = 15_000L

    /** Production ticker sample interval (engine-scope coroutine loop). */
    const val WATCHDOG_TICK_MS: Long = 1_000L

    /**
     * Minimum wall-clock spacing between successive watchdog recovery actions:
     * each intervention gets one observation window to manifest before the
     * next tier may fire, so a just-applied rendition is never clobbered.
     */
    const val WATCHDOG_ACTION_SPACING_MS: Long = WATCHDOG_SOFT_STALL_MS

    /**
     * Full-rebuild budget per stalled item (NFR-3 anti-hot-loop law); mirrors
     * layer 2's renewal bound. Exceeding it while still stalled skips.
     */
    const val MAX_REBUILDS_PER_EPISODE: Int = 2

    /** Lower bitrate target for the brief-stall downscale replay (L6/AD-7). */
    val DOWNSCALE_QUALITY: Quality = Quality.LOW

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

    /** Escalation ladder output for one watchdog tick (pure decision, story 5.4). */
    enum class WatchdogAction { None, Downscale, Rebuild, Skip }

    /**
     * Pure watchdog escalation ladder (FR-14 / AD-7 layer 3): frozen-BUFFERING
     * time below [WATCHDOG_SOFT_STALL_MS] does nothing; between soft and hard
     * the ONE downscale replay fires (latched by [downgradeAttempted]); at or
     * beyond [WATCHDOG_HARD_STALL_MS] full rebuilds fire while
     * [rebuildAttempts] remains under [MAX_REBUILDS_PER_EPISODE], else the
     * honest SKIP. Every action additionally requires
     * [msSinceLastAction] >= [WATCHDOG_ACTION_SPACING_MS] so a just-applied
     * rendition gets its observation window. Cumulative accounting: the hard
     * tier dominates a missed downscale (a single long freeze escalates
     * directly to rebuild).
     */
    fun watchdogAction(
        stallFrozenMs: Long,
        downgradeAttempted: Boolean,
        rebuildAttempts: Int,
        msSinceLastAction: Long,
    ): WatchdogAction {
        if (stallFrozenMs < WATCHDOG_SOFT_STALL_MS) return WatchdogAction.None
        if (msSinceLastAction < WATCHDOG_ACTION_SPACING_MS) return WatchdogAction.None
        if (stallFrozenMs < WATCHDOG_HARD_STALL_MS) {
            return if (!downgradeAttempted) WatchdogAction.Downscale else WatchdogAction.None
        }
        return if (rebuildAttempts < MAX_REBUILDS_PER_EPISODE) WatchdogAction.Rebuild else WatchdogAction.Skip
    }

    /**
     * Stall candidate iff the user intends playback and the player is
     * buffering — anything else (READY playing, paused, idle/ended, error) is
     * outside stall accounting by definition.
     */
    fun isStallCandidate(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && playbackState == Player.STATE_BUFFERING

    /**
     * Single-owner law (story 5.4): the watchdog acts only when NO other
     * recovery pipeline owns the item.
     */
    fun isWatchdogSuppressed(renewalInFlight: Boolean, watchdogRecoveryInFlight: Boolean): Boolean =
        renewalInFlight || watchdogRecoveryInFlight

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

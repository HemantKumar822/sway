package com.sway.core.model

/**
 * Stream-resolution port — AD-7, AR-6, FR-12–FR-15.
 *
 * Extractor isolation (AD-1/AR-2): only `:catalog` implements this port using
 * NewPipeExtractor; `:playback` consumes it as an opaque boundary. All signatures
 * speak exclusively in `:core:model` types returning [SwayResult] (or nullable
 * for the opportunistic prefetch).
 *
 * AD-7 contract:
 * - [resolveAudio] — only method playback needs; takes [SourceId] + [AudioRequest]
 *   (quality + forceRefresh) → `SwayResult<ResolvedAudio>`.
 *   `ResolvedAudio.expiresAtEpochMs` is parsed from the URL's own expiry param,
 *   never guessed.
 * - [invalidate] — purges cached URL(s) for a track after 403/410 or quality change.
 * - [prefetchNext] — opportunistic, may return `null` silently; callers apply the
 *   age cap before trusting it. Never counts against FR-12's up-front budget.
 * - Mandatory in-flight dedup is the resolver's job, invisible to callers.
 * - Defense layers (AD-7): read-time -5 min margin; error-triggered renewal ±3 s;
 *   watchdog ladder 3 s/15 s. This port exposes only the vocabulary; policy lives
 *   in callers/resolver impl.
 *
 * AudioRequest, ResolvedAudio, and [Quality] live in `:core:model` beside this
 * port — settings (`:core:data`), resolver (`:catalog`), and player (`:playback`)
 * all consume the same declarations; local re-declarations are banned (AD-7).
 *
 * Pure Kotlin — zero Android imports. Returning `SwayResult` in [resolveAudio]
 * enforces NFR-2/AD-9 (never bare lists/strings where typed results are required).
 */
interface StreamResolver {

    /**
     * Resolve a playable URL for [trackId] at the requested [request] quality.
     *
     * Returns [SwayResult.Success] with [ResolvedAudio] on success, or
     * [SwayResult.Failure] with a typed [SwayError] (Offline, RateLimited,
     * UpstreamUnavailable, Parse, ContentNotFound, Storage, Unknown).
     * Identical concurrent requests must share one fetch (in-flight dedup)
     * invisible to callers (AD-7).
     */
    suspend fun resolveAudio(
        trackId: SourceId,
        request: AudioRequest,
    ): SwayResult<ResolvedAudio>

    /**
     * Purge any cached rendition(s) for [trackId].
     *
     * Called after HTTP 403/410 or a quality change. Must be synchronous and
     * idempotent (purging an unknown track is a no-op). Not suspending — cache
     * eviction is in-memory and must not require IO.
     */
    fun invalidate(trackId: SourceId)

    /**
     * Opportunistically prefetch the next track's stream.
     *
     * May return `null` silently on any failure without throwing (AD-7:
     * `prefetchNext(...) returning null silently`). Callers must not treat
     * `null` as an error and must re-validate expiry before use.
     * Prefetch never replaces items mid-shuffle and never counts against FR-12.
     */
    suspend fun prefetchNext(
        trackId: SourceId,
        request: AudioRequest,
    ): ResolvedAudio?
}

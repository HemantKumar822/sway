package com.sway.core.model

/**
 * Resolved stream rendition — AD-7, AR-6.
 *
 * Produced by [StreamResolver.resolveAudio] and consumed by `:playback` to feed
 * ExoPlayer. Each field maps to AD-7's rule:
 *
 * - [url] — short-lived playable URL (string, not host-sniffed elsewhere per AD-11).
 * - [expiresAtEpochMs] — parsed from the URL's own expiry parameter — never a guessed
 *   fixed TTL (AD-7). Checked at read time minus 5 min margin (layer-1 defense).
 * - [bitrateKbps] — average bitrate of the chosen rendition (for diagnostics/target checks).
 * - [containerHint] — e.g. "mp4", "webm", "m4a" (nullable when unknown).
 * - [backendTag] — which transport/format path succeeded (e.g. "newpipe:progressive:mp4").
 * - [renditionCacheKey] — `SourceId + quality discriminator` (cheap insurance against
 *   cross-rendition contamination per AD-7).
 *
 * Pure Kotlin — zero Android imports.
 */
data class ResolvedAudio(
    val url: String,
    val expiresAtEpochMs: Long,
    val bitrateKbps: Int,
    val containerHint: String?,
    val backendTag: String,
    val renditionCacheKey: String,
) {
    init {
        require(url.isNotBlank()) { "ResolvedAudio url must be non-blank" }
        require(expiresAtEpochMs > 0) { "ResolvedAudio expiresAtEpochMs must be > 0 (got $expiresAtEpochMs)" }
        require(bitrateKbps >= 0) { "ResolvedAudio bitrateKbps must be >= 0 (got $bitrateKbps)" }
        require(backendTag.isNotBlank()) { "ResolvedAudio backendTag must be non-blank" }
        require(renditionCacheKey.isNotBlank()) { "ResolvedAudio renditionCacheKey must be non-blank" }
        if (containerHint != null) {
            require(containerHint.isNotBlank()) { "ResolvedAudio containerHint must be non-blank when present" }
        }
    }

    /** True when [expiresAtEpochMs] is at or beyond [epochMs] minus margin. */
    fun isExpiredAt(epochMs: Long, marginMs: Long = 0L): Boolean =
        epochMs + marginMs >= expiresAtEpochMs

    companion object {
        /** Rendition cache key helper: `"<sourceId>:<quality>"` discriminator. */
        fun cacheKey(sourceId: SourceId, quality: Quality): String =
            "${sourceId.value}:${quality.name}"

        /** Overload for raw source id string (trims, validates non-blank). */
        fun cacheKey(rawSourceId: String, quality: Quality): String {
            val id = SourceId.parse(rawSourceId)
                ?: throw IllegalArgumentException("cacheKey rawSourceId must be non-blank (got \"$rawSourceId\")")
            return cacheKey(id, quality)
        }
    }
}

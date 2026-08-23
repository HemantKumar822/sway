package com.sway.core.model

/**
 * Request vocabulary for [StreamResolver.resolveAudio] — AD-7, AR-6.
 *
 * - [quality] — desired rendition class (bitrate target per AD-7; AUTO adapts).
 * - [forceRefresh] — when true, bypass any cached [ResolvedAudio] and fetch fresh
 *   (e.g. after quality change or explicit retry). When false, resolver may serve
 *   a cached rendition if still fresh per read-time -5 min margin validation.
 *
 * Lives in `:core:model` alongside the port (AD-7); settings, resolver, and player
 * all consume this single declaration.
 *
 * Pure Kotlin — zero Android imports.
 */
data class AudioRequest(
    val quality: Quality = Quality.AUTO,
    val forceRefresh: Boolean = false,
) {
    companion object {
        /** Default AUTO, no forced refresh. */
        val Default: AudioRequest = AudioRequest(Quality.AUTO, false)

        /** Convenience for forced refresh at a given quality. */
        fun refresh(quality: Quality = Quality.AUTO): AudioRequest =
            AudioRequest(quality, forceRefresh = true)
    }
}

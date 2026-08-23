package com.sway.core.model

/**
 * Artwork value object computed once at parse time in `:catalog` (AD-11).
 *
 * For story 2.1 this is a minimal placeholder that story 2.3 will expand with
 * full ytimg/googleusercontent normalization and walk-on-failure semantics.
 * The contract already holds:
 * - [canonicalUrl] is the stable cache key ([cacheKey] == [canonicalUrl])
 * - [candidates] is an ordered immutable chain walked on load failure
 * - equality is structural and order-sensitive (data-class)
 *
 * Consumers contain zero host-specific URL logic.
 *
 * AR-10: canonical URL doubles as cache key; candidate chain is data.
 */
data class ArtworkRef(
    val canonicalUrl: String,
    val candidates: List<String> = listOf(canonicalUrl),
) {
    init {
        require(canonicalUrl.isNotBlank()) { "ArtworkRef canonicalUrl must be non-blank" }
        require(candidates.isNotEmpty()) { "ArtworkRef candidates must not be empty" }
    }

    /** Stable cache key — exactly the canonical URL string (AR-10). */
    val cacheKey: String get() = canonicalUrl

    companion object {
        /**
         * Factory: returns `null` if [canonicalUrl] is blank. Trims input and drops blank candidates.
         * Preserves candidate order; ensures canonical is first.
         */
        fun parse(canonicalUrl: String?, candidates: List<String>? = null): ArtworkRef? {
            if (canonicalUrl == null) return null
            val trimmed = canonicalUrl.trim()
            if (trimmed.isEmpty()) return null
            val rawCandidates = candidates ?: listOf(trimmed)
            val cleaned = rawCandidates.mapNotNull { c ->
                val t = c.trim()
                if (t.isEmpty()) null else t
            }.distinct()
            // Ensure canonical is first if not already present
            val ordered = if (cleaned.firstOrNull() == trimmed) cleaned
            else listOf(trimmed) + cleaned.filterNot { it == trimmed }
            if (ordered.isEmpty()) return null
            return ArtworkRef(trimmed, ordered)
        }

        /** Convenience for a single-url ref (no extra candidates). */
        fun of(canonicalUrl: String): ArtworkRef? = parse(canonicalUrl, listOf(canonicalUrl))

        /**
         * Synthetic ref for absent artwork (AD-11: synthesized from video-id pattern).
         * Minimal stub — 2.3 will expand normalization chain (maxresdefault → sddefault → …).
         */
        fun synthetic(videoId: String): ArtworkRef? {
            val id = videoId.trim()
            if (id.isEmpty()) return null
            // Placeholder synthetic URL; real normalization lives in :catalog in 2.3.
            val url = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            return ArtworkRef(url, listOf(url))
        }

        /** Absent sentinel where no id exists — callers should omit artwork instead of using this. */
        fun absent(): ArtworkRef? = null
    }
}

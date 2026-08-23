package com.sway.core.model

/**
 * Artwork value object computed once at parse time in `:catalog` (AD-11, AR-10).
 *
 * Contract (story 2.3 + AD-11):
 * - [canonicalUrl] is the stable cache key: [cacheKey] == [canonicalUrl] (AR-10).
 * - [candidates] is an ordered, immutable fallback chain walked on load failure.
 *   Consumers (notably `:designui`) hold zero host-specific URL logic; they simply
 *   try `candidates[0]`, on failure try `candidates[1]`, etc. Host knowledge lives
 *   only at parse time in `:catalog` (and in the synthetic factory below).
 * - Equality is structural and order-sensitive: two refs with identical canonical
 *   URLs but different chain orders are NOT equal (data-class list equality).
 * - Absent artwork synthesizes from the ytimg video-id pattern
 *   `maxresdefault → sddefault → hqdefault → mqdefault` per AD-11.
 *
 * Normalization rules (implemented at parse time in `:catalog`, documented here):
 * - ytimg/youtube hosts: `maxresdefault.jpg → sddefault.jpg → hqdefault.jpg → mqdefault.jpg`
 * - googleusercontent/ggpht hosts: size params rewritten descending `1080 → 720 → 544`
 *   with original last (chain built by the mapper, stored as data here).
 */
data class ArtworkRef(
    val canonicalUrl: String,
    val candidates: List<String> = listOf(canonicalUrl),
) {
    init {
        require(canonicalUrl.isNotBlank()) { "ArtworkRef canonicalUrl must be non-blank" }
        require(candidates.isNotEmpty()) { "ArtworkRef candidates must not be empty" }
        require(candidates.all { it.isNotBlank() }) { "ArtworkRef candidates must not contain blank entries" }
        require(candidates.first() == canonicalUrl) {
            "ArtworkRef candidates must start with canonicalUrl (got canonical=$canonicalUrl first=${candidates.first()})"
        }
        require(candidates.size == candidates.distinct().size) {
            "ArtworkRef candidates must be distinct (duplicates found)"
        }
    }

    /** Stable cache key — exactly the canonical URL string (AR-10). */
    val cacheKey: String get() = canonicalUrl

    /** True when a fallback exists beyond the canonical. */
    val hasFallbacks: Boolean get() = candidates.size > 1

    /**
     * Walk-on-failure helper — the contract consumed by `:designui`.
     *
     * Given the URL that just failed, return the next candidate to try, or `null`
     * if no fallback remains. Consumers walk the ordered chain sequentially:
     * ```
     * var url = ref.canonicalUrl
     * while (true) { tryLoad(url) ?: run { url = ref.candidateAfter(url) ?: break } }
     * ```
     * No host-specific logic lives in the walker.
     */
    fun candidateAfter(failedUrl: String): String? {
        val idx = candidates.indexOf(failedUrl)
        return if (idx >= 0 && idx + 1 < candidates.size) candidates[idx + 1] else null
    }

    /**
     * Index-based variant: return candidate at [index] or null if out of bounds.
     * Useful for prefetch loops.
     */
    fun candidateAt(index: Int): String? = candidates.getOrNull(index)

    companion object {
        /**
         * Factory: returns `null` if [canonicalUrl] is blank. Trims input, drops blank
         * candidates, de-duplicates while preserving order, and ensures canonical is first.
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
            val ordered = if (cleaned.firstOrNull() == trimmed) cleaned
            else listOf(trimmed) + cleaned.filterNot { it == trimmed }
            if (ordered.isEmpty()) return null
            if (ordered.any { it.isBlank() }) return null
            return ArtworkRef(trimmed, ordered)
        }

        /** Convenience for a single-url ref (no extra candidates). */
        fun of(canonicalUrl: String): ArtworkRef? = parse(canonicalUrl, listOf(canonicalUrl))

        /**
         * Synthetic ref for absent artwork (AD-11: synthesized from video-id pattern).
         *
         * For a non-blank [videoId] produces the ytimg chain:
         * `maxresdefault → sddefault → hqdefault → mqdefault`
         * with canonical = `maxresdefault.jpg`. Cache key remains the canonical (max).
         * Returns `null` on blank input.
         */
        fun synthetic(videoId: String): ArtworkRef? {
            val id = videoId.trim()
            if (id.isEmpty()) return null
            val base = "https://i.ytimg.com/vi/$id"
            val max = "$base/maxresdefault.jpg"
            val sd = "$base/sddefault.jpg"
            val hq = "$base/hqdefault.jpg"
            val mq = "$base/mqdefault.jpg"
            val chain = listOf(max, sd, hq, mq)
            return ArtworkRef(max, chain)
        }

        /** Absent sentinel where no id exists — callers should omit artwork instead of using this. */
        fun absent(): ArtworkRef? = null
    }
}

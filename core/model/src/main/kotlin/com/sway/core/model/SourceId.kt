package com.sway.core.model

/**
 * Catalog identity: non-blank String from the catalog (AR-8, AR-14 Identity law).
 *
 * Blank/whitespace ids never produce a model downstream. Direct construction
 * enforces non-blank via [require]; mappers should use [parse] which returns
 * `null` for blank input (drop + log shape info at the adapter layer). This
 * keeps `core:model` pure-Kotlin with zero Android/log dependencies.
 *
 * Local [Playlist] ids are **namespaced** apart from [SourceId] — see [PlaylistId].
 */
@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must be non-blank" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Parse a raw catalog id string. Returns `null` if blank/whitespace (AR-8).
         * Trims surrounding whitespace so `"  abc "` yields `SourceId("abc")`.
         */
        fun parse(raw: String?): SourceId? {
            if (raw == null) return null
            val trimmed = raw.trim()
            return if (trimmed.isEmpty()) null else SourceId(trimmed)
        }

        /** Variant that trims already-trimmed caller guarantees same semantics. */
        fun parseOrNull(raw: String): SourceId? = parse(raw)
    }
}

/**
 * Local playlist identity — namespaced apart from catalog [SourceId] (AR-8, AR-14).
 *
 * Rule: every local playlist id is app-generated and MUST carry the `"local:"` prefix
 * (e.g. `"local:7f3a..."`). This guarantees no collision with catalog SourceIds which
 * are raw upstream strings (YouTube video/album/playlist ids) and never carry that prefix.
 * The prefix rule is documented here as the single source of truth; all persistence and
 * UI code must treat a `"local:"` id as owned-data and never route it through catalog ports.
 */
@JvmInline
value class PlaylistId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlaylistId must be non-blank" }
        require(value.startsWith(LOCAL_PREFIX)) {
            "PlaylistId must be namespaced with prefix \"$LOCAL_PREFIX\" (got \"$value\")"
        }
        require(value.length > LOCAL_PREFIX.length && value.substring(LOCAL_PREFIX.length).isNotBlank()) {
            "PlaylistId must have non-blank suffix after \"$LOCAL_PREFIX\" (got \"$value\")"
        }
    }

    override fun toString(): String = value

    companion object {
        const val LOCAL_PREFIX = "local:"

        /** Returns `null` if [raw] is blank or lacks the required prefix after trimming. */
        fun parse(raw: String?): PlaylistId? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith(LOCAL_PREFIX)) return null
            return try {
                PlaylistId(trimmed)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Generates a new namespaced id (UUID-based). Pure-Kotlin, no Android imports. */
        fun generate(): PlaylistId = PlaylistId(LOCAL_PREFIX + java.util.UUID.randomUUID().toString())
    }
}

package com.sway.core.model

/**
 * Owned local Playlist (user-created). Distinct from [CatalogPlaylist] (AR-14 vocabulary).
 *
 * **Local id namespacing rule (AR-8 / AR-14 Identity convention):**
 * - Every instance carries a [PlaylistId] whose string value MUST start with `"local:"`.
 * - Catalog ids ([SourceId]) are raw upstream strings and never carry that prefix.
 * - This textual namespacing guarantees local and catalog ids never collide even when
 *   both are stored as strings (e.g. Room FKs, Queue snapshots, logs). All code
 *   must check the prefix to decide whether an id is routable through catalog ports.
 * - New local playlists should be created via [create] with [PlaylistId.generate()].
 *
 * - [name] sanitized display; [rawName] preserved verbatim (allows re-sanitization / diagnostics).
 * - Duplicate names are allowed (FR-31) — uniqueness is via [id], not name.
 */
data class Playlist private constructor(
    val id: PlaylistId,
    val name: String,
    val rawName: String,
) {
    companion object {
        /**
         * Factory: returns `null` if [id] is blank/missing prefix or [rawName] sanitizes to blank.
         * - [id] is a raw string that must parse as [PlaylistId] (requires `"local:"` prefix).
         * - [rawName] is preserved verbatim; [name] is sanitized (trim + collapse whitespace).
         *   If sanitized name is empty, returns `null` to prevent nameless playlists.
         */
        fun create(
            id: String,
            rawName: String,
        ): Playlist? {
            val playlistId = PlaylistId.parse(id) ?: return null
            val displayName = TitleSanitization.sanitize(rawName)
            if (displayName.isEmpty()) return null
            return Playlist(playlistId, displayName, rawName)
        }

        /** Typed overload for callers holding a [PlaylistId]. */
        fun createTyped(
            id: PlaylistId,
            rawName: String,
        ): Playlist? {
            val displayName = TitleSanitization.sanitize(rawName)
            if (displayName.isEmpty()) return null
            return Playlist(id, displayName, rawName)
        }

        /**
         * Creates a new local playlist with a generated namespaced id.
         * Returns `null` only if [rawName] sanitizes to blank.
         */
        fun createNew(rawName: String): Playlist? {
            val displayName = TitleSanitization.sanitize(rawName)
            if (displayName.isEmpty()) return null
            return Playlist(PlaylistId.generate(), displayName, rawName)
        }
    }
}

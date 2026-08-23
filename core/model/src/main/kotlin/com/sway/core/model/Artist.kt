package com.sway.core.model

/**
 * Catalog Artist (AR-8, AR-14).
 *
 * - Identity-lawed on [id]; factory returns `null` on blank id.
 * - [name] sanitized display; [rawName] preserved verbatim.
 * - [artwork] optional (circular portrait; initials fallback handled in UI).
 * - [topSongs] optional list placeholder for FR-6 (top Songs Must); defaults empty for 2.1 minimal.
 */
data class Artist private constructor(
    val id: SourceId,
    val name: String,
    val rawName: String,
    val artwork: ArtworkRef?,
    val topSongs: List<Song> = emptyList(),
) {
    companion object {
        fun create(
            id: String,
            rawName: String,
            artwork: ArtworkRef? = null,
            topSongs: List<Song> = emptyList(),
        ): Artist? {
            val sourceId = SourceId.parse(id) ?: return null
            val displayName = TitleSanitization.sanitize(rawName)
            return Artist(sourceId, displayName, rawName, artwork, topSongs.toList())
        }

        fun createTyped(
            id: SourceId,
            rawName: String,
            artwork: ArtworkRef? = null,
            topSongs: List<Song> = emptyList(),
        ): Artist {
            val displayName = TitleSanitization.sanitize(rawName)
            return Artist(id, displayName, rawName, artwork, topSongs.toList())
        }
    }
}

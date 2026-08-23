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
    val albums: List<Album> = emptyList(),
    val singles: List<Album> = emptyList(),
    val albumsAvailable: Boolean = false,
    val singlesAvailable: Boolean = false,
) {
    companion object {
        fun create(
            id: String,
            rawName: String,
            artwork: ArtworkRef? = null,
            topSongs: List<Song> = emptyList(),
            albums: List<Album> = emptyList(),
            singles: List<Album> = emptyList(),
            albumsAvailable: Boolean = false,
            singlesAvailable: Boolean = false,
        ): Artist? {
            val sourceId = SourceId.parse(id) ?: return null
            val displayName = TitleSanitization.sanitize(rawName)
            return Artist(
                sourceId,
                displayName,
                rawName,
                artwork,
                topSongs.toList(),
                albums.toList(),
                singles.toList(),
                albumsAvailable,
                singlesAvailable,
            )
        }

        fun createTyped(
            id: SourceId,
            rawName: String,
            artwork: ArtworkRef? = null,
            topSongs: List<Song> = emptyList(),
            albums: List<Album> = emptyList(),
            singles: List<Album> = emptyList(),
            albumsAvailable: Boolean = false,
            singlesAvailable: Boolean = false,
        ): Artist {
            val displayName = TitleSanitization.sanitize(rawName)
            return Artist(
                id,
                displayName,
                rawName,
                artwork,
                topSongs.toList(),
                albums.toList(),
                singles.toList(),
                albumsAvailable,
                singlesAvailable,
            )
        }
    }
}

package com.sway.core.model

/**
 * Catalog Album (AR-8, AR-14).
 *
 * - Identity-lawed on [id]; factory returns `null` on blank id.
 * - [title] sanitized, [rawTitle] preserved verbatim.
 * - Artist name/id nullable; blank child ids coerced to null.
 * - [year] optional (null means absent — never empty string).
 * - [artwork] optional hero artwork (canonical + candidate chain per AR-10).
 * - [tracks] ordered tracklist preserving source order; blank-id tracks are dropped at
 *   parse time (AR-8) with logged shape info. Empty when detail not yet loaded (search
 *   context) or when source has no tracks. Story 3.3 introduces this field.
 */
data class Album private constructor(
    val id: SourceId,
    val title: String,
    val rawTitle: String,
    val artistName: String?,
    val artistId: SourceId?,
    val year: Int?,
    val artwork: ArtworkRef?,
    val tracks: List<Song> = emptyList(),
) {
    companion object {
        fun create(
            id: String,
            rawTitle: String,
            artistName: String? = null,
            artistId: String? = null,
            year: Int? = null,
            artwork: ArtworkRef? = null,
            tracks: List<Song> = emptyList(),
        ): Album? {
            val sourceId = SourceId.parse(id) ?: return null
            val displayTitle = TitleSanitization.sanitize(rawTitle)
            val cleanArtistName = artistName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanArtistId = SourceId.parse(artistId)
            // Year sanity: must be plausible (1000..3000) else treated as absent (clean omission).
            val cleanYear = year?.takeIf { it in 1000..3000 }
            return Album(sourceId, displayTitle, rawTitle, cleanArtistName, cleanArtistId, cleanYear, artwork, tracks.toList())
        }

        fun createTyped(
            id: SourceId,
            rawTitle: String,
            artistName: String? = null,
            artistId: SourceId? = null,
            year: Int? = null,
            artwork: ArtworkRef? = null,
            tracks: List<Song> = emptyList(),
        ): Album {
            val displayTitle = TitleSanitization.sanitize(rawTitle)
            val cleanArtistName = artistName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanYear = year?.takeIf { it in 1000..3000 }
            return Album(id, displayTitle, rawTitle, cleanArtistName, artistId, cleanYear, artwork, tracks.toList())
        }
    }
}

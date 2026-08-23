package com.sway.core.model

/**
 * Catalog Song — cannot exist without a [SourceId] (AR-8 blank-id law, AR-14 conventions).
 *
 * Fields mirror the mapper contract:
 * - [title] is the sanitized display title; [rawTitle] is the original preserved verbatim for diagnostics.
 * - Artist/album name/id are nullable; blank child ids are coerced to `null` (only the primary [id] is identity-lawed).
 * - [duration] is ms-typed ([DurationMs]) preventing unit mix-ups.
 * - [artwork] is a minimal [ArtworkRef] placeholder (2.3 expands candidate/normalization logic).
 *
 * Construction is via [create] which returns `null` on blank primary id (factory null-law).
 * Direct constructor is private to prevent keyless models downstream.
 */
data class Song private constructor(
    val id: SourceId,
    val title: String,
    val rawTitle: String,
    val artistName: String?,
    val artistId: SourceId?,
    val albumName: String?,
    val albumId: SourceId?,
    val duration: DurationMs,
    val artwork: ArtworkRef?,
) {
    companion object {
        /**
         * Factory: returns `null` if [id] is blank/whitespace (AR-8).
         * - [rawTitle] is preserved verbatim; [title] is sanitized (trim + collapse whitespace).
         * - Blank [artistId]/[albumId] become `null`; surrounding whitespace is trimmed.
         * - Negative [durationMs] is clamped to [DurationMs.ZERO] (lenient mapper behavior).
         * - Blank [artistName]/[albumName] become `null` after trimming.
         */
        fun create(
            id: String,
            rawTitle: String,
            artistName: String? = null,
            artistId: String? = null,
            albumName: String? = null,
            albumId: String? = null,
            durationMs: Long = 0L,
            artwork: ArtworkRef? = null,
        ): Song? {
            val sourceId = SourceId.parse(id) ?: return null

            // Title sanitization: preserve raw verbatim, derive display.
            val displayTitle = TitleSanitization.sanitize(rawTitle)

            val cleanArtistName = artistName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanArtistId = SourceId.parse(artistId)
            val cleanAlbumName = albumName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanAlbumId = SourceId.parse(albumId)

            val duration = DurationMs.clamp(durationMs)

            return Song(
                id = sourceId,
                title = displayTitle,
                rawTitle = rawTitle,
                artistName = cleanArtistName,
                artistId = cleanArtistId,
                albumName = cleanAlbumName,
                albumId = cleanAlbumId,
                duration = duration,
                artwork = artwork,
            )
        }

        /**
         * Typed overload for callers that already hold parsed ids/duration.
         * Still validates display title derivation.
         */
        fun createTyped(
            id: SourceId,
            rawTitle: String,
            artistName: String? = null,
            artistId: SourceId? = null,
            albumName: String? = null,
            albumId: SourceId? = null,
            duration: DurationMs = DurationMs.ZERO,
            artwork: ArtworkRef? = null,
        ): Song {
            val displayTitle = TitleSanitization.sanitize(rawTitle)
            val cleanArtistName = artistName?.trim()?.takeIf { it.isNotEmpty() }
            val cleanAlbumName = albumName?.trim()?.takeIf { it.isNotEmpty() }
            return Song(id, displayTitle, rawTitle, cleanArtistName, artistId, cleanAlbumName, albumId, duration, artwork)
        }
    }
}

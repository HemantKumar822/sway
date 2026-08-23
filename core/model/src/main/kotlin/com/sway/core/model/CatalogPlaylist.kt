package com.sway.core.model

/**
 * Catalog Playlist (read-only, curated) — distinct from owned local [Playlist] (AR-14 vocabulary).
 *
 * - Identity-lawed on [id]; factory returns `null` on blank id.
 * - [title] sanitized, [rawTitle] preserved.
 * - [curator] nullable; blank coerced to null.
 * - [trackCount] nullable; null means upstream did not provide count (UI derives count).
 * - [artwork] optional.
 * - No mutation surface exists on this model (FR-7 read-only semantics).
 */
data class CatalogPlaylist private constructor(
    val id: SourceId,
    val title: String,
    val rawTitle: String,
    val curator: String?,
    val trackCount: Int?,
    val artwork: ArtworkRef?,
) {
    companion object {
        fun create(
            id: String,
            rawTitle: String,
            curator: String? = null,
            trackCount: Int? = null,
            artwork: ArtworkRef? = null,
        ): CatalogPlaylist? {
            val sourceId = SourceId.parse(id) ?: return null
            val displayTitle = TitleSanitization.sanitize(rawTitle)
            val cleanCurator = curator?.trim()?.takeIf { it.isNotEmpty() }
            val cleanCount = trackCount?.takeIf { it >= 0 }
            return CatalogPlaylist(sourceId, displayTitle, rawTitle, cleanCurator, cleanCount, artwork)
        }

        fun createTyped(
            id: SourceId,
            rawTitle: String,
            curator: String? = null,
            trackCount: Int? = null,
            artwork: ArtworkRef? = null,
        ): CatalogPlaylist {
            val displayTitle = TitleSanitization.sanitize(rawTitle)
            val cleanCurator = curator?.trim()?.takeIf { it.isNotEmpty() }
            val cleanCount = trackCount?.takeIf { it >= 0 }
            return CatalogPlaylist(id, displayTitle, rawTitle, cleanCurator, cleanCount, artwork)
        }
    }
}

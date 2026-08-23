package com.sway.core.model

/**
 * Catalog metadata port — AD-1, AR-2, AD-9, AD-11, FR-1–FR-7.
 *
 * Extractor isolation (AD-1/AR-2): NewPipeExtractor types live exclusively inside
 * `:catalog`; this port is the ONLY seam `:core:data` and `:app` ever see for
 * catalog data. Every public signature speaks exclusively in `:core:model` types
 * and returns [SwayResult] — never bare lists or strings (NFR-2/AD-9, FR-37). Mappers
 * compute [ArtworkRef] at parse time (AD-11) and reject blank Source IDs at the
 * boundary (AR-8).
 *
 * Pagination (FR-2): search methods accept an opaque [pageToken] continuation from
 * a prior [PagedResult.nextPageToken]; `null` means first page. `nextPageToken`
 * inside returned [PagedResult] is `null` at end-of-results.
 *
 * Failure semantics (AD-9): repositories expose `SwayResult` values, never thrown
 * exceptions across modules. `emptyList` inside Success is honest empty (e.g.
 * query matched nothing), distinct from Failure(Offline/RateLimited/...).
 *
 * Pure Kotlin — zero Android imports (CI import-ban enforced in `:core:model`).
 */
interface CatalogSource {

    // -------------------------------------------------------------------------
    // Search — four typed groups, paginated per FR-1/FR-2 (AR-2, AD-9).
    // -------------------------------------------------------------------------

    /**
     * Search catalog songs for [query] at page [pageToken].
     *
     * @param query non-blank search query (caller trims; blank yields empty Success or
     *   typed Failure at impl discretion, never bare empty masquerading as success-of-nothing
     *   when transport is down).
     * @param pageToken opaque continuation from a prior page's [PagedResult.nextPageToken];
     *   `null` or blank requests the first page.
     * @return typed [PagedResult] of [Song] on success, typed [SwayError] on failure.
     */
    suspend fun searchSongs(
        query: String,
        pageToken: String? = null,
    ): SwayResult<PagedResult<Song>>

    /** Search catalog albums — paginated; see [searchSongs] for pagination contract. */
    suspend fun searchAlbums(
        query: String,
        pageToken: String? = null,
    ): SwayResult<PagedResult<Album>>

    /** Search catalog artists — paginated; see [searchSongs] for pagination contract. */
    suspend fun searchArtists(
        query: String,
        pageToken: String? = null,
    ): SwayResult<PagedResult<Artist>>

    /** Search catalog playlists (curated, read-only per FR-7) — paginated. */
    suspend fun searchCatalogPlaylists(
        query: String,
        pageToken: String? = null,
    ): SwayResult<PagedResult<CatalogPlaylist>>

    // -------------------------------------------------------------------------
    // Detail — FR-5/FR-6/FR-7 (album/artist/catalog-playlist).
    // -------------------------------------------------------------------------

    /**
     * Load album detail for [id] (SourceId identity-lawed per AR-8).
     *
     * Missing year is `null` (clean omission); tracklist order is source order.
     */
    suspend fun album(id: SourceId): SwayResult<Album>

    /**
     * Load artist detail for [id].
     *
     * FR-6: top Songs (Must) vs Albums/Singles listings (Should) — extended tiers
     * may be absent when transport does not supply them (OQ-1 degraded).
     */
    suspend fun artist(id: SourceId): SwayResult<Artist>

    /**
     * Load catalog playlist detail for [id] (read-only curated playlist per FR-7/AR-14).
     *
     * Count may be `null` when upstream does not provide it (UI derives count).
     * No mutation surface exists on [CatalogPlaylist].
     */
    suspend fun catalogPlaylist(id: SourceId): SwayResult<CatalogPlaylist>
}

package com.sway.catalog

import com.sway.core.model.Album
import com.sway.core.model.ArtworkRef
import com.sway.core.model.DurationMs
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Album detail mappers — story 3.3 (AR-2, AR-8, AR-10, FR-5 trace).
 *
 * Responsibilities:
 * - Map [org.schabi.newpipe.extractor.playlist.PlaylistInfo]-equivalent raw fields
 *   (name, uploader, description/year, thumbnails, track items) to a typed [Album]
 *   with ordered [Song] tracklist.
 * - Year is optional `Int?` (null means clean omission, never "" per spec).
 * - Track order preserved, blank-id tracks dropped with logged shape info.
 * - Artwork chain per track + hero via [SearchMappers.artworkFromImages] (AD-11).
 *
 * This object is pure except for [CatalogLog] side-effects on dropped items.
 */
internal object AlbumMappers {

    private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

    /**
     * Extract year from raw [text] (e.g. description, subtitle). Returns `Int?` in 1000..3000
     * or null if absent/unparseable. Never returns empty string.
     *
     * Strategy: first 4-digit match in 1900-2099 that also passes [Album.create] sanity (1000..3000).
     * If multiple matches, picks the first plausible year — deterministic for fixtures.
     */
    fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = YEAR_REGEX.find(text) ?: return null
        val year = match.value.toIntOrNull() ?: return null
        return year.takeIf { it in 1000..3000 }
    }

    /**
     * Build a typed [Album] from raw detail fields.
     *
     * @param albumId raw album id (SourceId law enforced; blank -> null return).
     * @param rawTitle raw title (sanitized via Album.create; raw preserved).
     * @param artistName nullable uploader/artist name.
     * @param artistUrl nullable uploader/artist url (parsed to SourceId via SearchMappers).
     * @param rawYearText nullable text to parse year from (description/subtitle); year null means omission.
     * @param heroImages thumbnail Images for hero artwork.
     * @param trackItems ordered StreamInfoItems from [org.schabi.newpipe.extractor.playlist.PlaylistExtractor.getInitialPage].
     * @return Album or null if [albumId] blank (AR-8).
     */
    fun toAlbum(
        albumId: String,
        rawTitle: String,
        artistName: String?,
        artistUrl: String?,
        rawYearText: String?,
        heroImages: List<Image>,
        trackItems: List<StreamInfoItem>,
    ): Album? {
        val sourceId = SourceId.parse(albumId) ?: run {
            CatalogLog.w("AlbumMappers dropped album blank id rawTitle=${rawTitle.take(40)}")
            return null
        }
        val cleanArtistName = artistName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanArtistId = SearchMappers.extractArtistId(artistUrl)?.let { SourceId.parse(it)?.value }

        val year = extractYear(rawYearText)

        val heroArtwork: ArtworkRef? = SearchMappers.artworkFromImages(
            images = heroImages,
            fallbackVideoId = null,
        )

        // Map tracks preserving source order, dropping blank-id items.
        val tracks = mutableListOf<Song>()
        var dropped = 0
        for ((index, item) in trackItems.withIndex()) {
            val song = mapTrackItem(
                item = item,
                index = index,
                albumName = rawTitle,
                albumId = albumId,
            )
            if (song != null) tracks.add(song) else dropped++
        }
        if (dropped > 0) {
            CatalogLog.w("AlbumMappers dropped $dropped blank-id tracks of ${trackItems.size} for album $albumId")
        }

        return Album.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            artistName = cleanArtistName,
            artistId = cleanArtistId,
            year = year,
            artwork = heroArtwork,
            tracks = tracks,
        ).also {
            if (it == null) CatalogLog.w("AlbumMappers Album.create rejected id=$albumId title=$rawTitle")
        }
    }

    /**
     * Map a single [StreamInfoItem] track to a typed [Song].
     * Preserves duration ms conversion and artwork chain per track.
     * Returns null on blank-id (AR-8).
     */
    fun mapTrackItem(
        item: StreamInfoItem,
        index: Int,
        albumName: String?,
        albumId: String?,
    ): Song? {
        val url = try { item.url ?: "" } catch (e: Exception) {
            CatalogLog.w("mapTrackItem safeUrl threw ${e.javaClass.simpleName}")
            ""
        }
        val rawTitle = try { item.name ?: "" } catch (e: Exception) {
            CatalogLog.w("mapTrackItem safeName threw ${e.javaClass.simpleName}")
            ""
        }
        val songId = SearchMappers.extractSongId(url)
        val sourceId = SourceId.parse(songId)
        if (sourceId == null) {
            CatalogLog.w("mapTrackItem dropped blank song id at index $index url=${url.take(80)} title=${rawTitle.take(40)}")
            return null
        }

        val durationMs: Long = try {
            val sec = item.duration
            if (sec < 0) 0L else sec * 1000L
        } catch (_: Exception) { 0L }
        val duration = DurationMs.clamp(durationMs)

        val artistName: String? = try { item.uploaderName?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

        val artistId: String? = try { SearchMappers.extractArtistId(item.uploaderUrl) } catch (_: Exception) { null }

        val thumbs: List<Image> = try {
            @Suppress("UNCHECKED_CAST")
            (item.thumbnails as? List<Image>) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val artwork = SearchMappers.artworkFromImages(
            images = thumbs,
            fallbackVideoId = songId,
        )

        val cleanAlbumName = albumName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAlbumId = SourceId.parse(albumId)?.value

        return Song.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            artistName = artistName,
            artistId = artistId,
            albumName = cleanAlbumName,
            albumId = cleanAlbumId,
            durationMs = duration.millis,
            artwork = artwork,
        ).also {
            if (it == null) CatalogLog.w("mapTrackItem Song.create rejected url=$url title=$rawTitle")
        }
    }
}

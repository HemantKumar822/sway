package com.sway.catalog

import com.sway.core.model.ArtworkRef
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.DurationMs
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Catalog Playlist detail mappers — story 3.5 (AR-2, AR-8, AR-10, FR-7 trace).
 *
 * Responsibilities:
 * - Map [org.schabi.newpipe.extractor.playlist.PlaylistExtractor]-equivalent raw fields
 *   (name, uploader/curator, streamCount, thumbnails, track items) to a typed
 *   [CatalogPlaylist] with ordered [Song] tracklist.
 * - [CatalogPlaylist.trackCount] is nullable: when extractor reports <0 / unavailable
 *   the model carries `null` (clean omission); UI derives count from [CatalogPlaylist.tracks].size.
 *   Never synthesizes a count string; never coerces absent to 0/"".
 * - Track order preserved, blank-id tracks dropped with logged shape info (AR-8).
 * - Artwork chain per track + hero via [SearchMappers.artworkFromImages] (AD-11).
 * - No mutation surface: [CatalogPlaylist] has private constructor; only factories produce instances.
 *
 * Pure except for [CatalogLog] side-effects on dropped items.
 */
internal object PlaylistMappers {

    /**
     * Build a typed [CatalogPlaylist] from raw detail fields.
     *
     * @param playlistId raw playlist id (SourceId law enforced; blank -> null return).
     * @param rawTitle raw title (sanitized via CatalogPlaylist.create; raw preserved).
     * @param curatorName nullable curator/uploader name.
     * @param rawCount nullable upstream count (negative / null -> null in model).
     * @param heroImages thumbnail Images for hero artwork.
     * @param trackItems ordered StreamInfoItems from [org.schabi.newpipe.extractor.playlist.PlaylistExtractor.getInitialPage].
     * @return CatalogPlaylist or null if [playlistId] blank (AR-8).
     */
    fun toCatalogPlaylist(
        playlistId: String,
        rawTitle: String,
        curatorName: String?,
        rawCount: Long?,
        heroImages: List<Image>,
        trackItems: List<StreamInfoItem>,
    ): CatalogPlaylist? {
        val sourceId = SourceId.parse(playlistId) ?: run {
            CatalogLog.w("PlaylistMappers dropped playlist blank id rawTitle=${rawTitle.take(40)}")
            return null
        }
        val cleanCurator = curatorName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanCount: Int? = rawCount?.let { count ->
            if (count < 0) null else count.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }

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
                playlistName = rawTitle,
                playlistId = playlistId,
            )
            if (song != null) tracks.add(song) else dropped++
        }
        if (dropped > 0) {
            CatalogLog.w("PlaylistMappers dropped $dropped blank-id tracks of ${trackItems.size} for playlist $playlistId")
        }

        return CatalogPlaylist.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            curator = cleanCurator,
            trackCount = cleanCount,
            artwork = heroArtwork,
            tracks = tracks,
        ).also {
            if (it == null) CatalogLog.w("PlaylistMappers CatalogPlaylist.create rejected id=$playlistId title=$rawTitle")
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
        playlistName: String?,
        playlistId: String?,
    ): Song? {
        val url = try { item.url ?: "" } catch (e: Exception) {
            CatalogLog.w("PlaylistMappers mapTrackItem safeUrl threw ${e.javaClass.simpleName}")
            ""
        }
        val rawTitle = try { item.name ?: "" } catch (e: Exception) {
            CatalogLog.w("PlaylistMappers mapTrackItem safeName threw ${e.javaClass.simpleName}")
            ""
        }
        val songId = SearchMappers.extractSongId(url)
        val sourceId = SourceId.parse(songId)
        if (sourceId == null) {
            CatalogLog.w("PlaylistMappers mapTrackItem dropped blank song id at index $index url=${url.take(80)} title=${rawTitle.take(40)}")
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

        // Playlist context: album fields left null (curated playlist track not album-bound)
        return Song.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            artistName = artistName,
            artistId = artistId,
            albumName = null,
            albumId = null,
            durationMs = duration.millis,
            artwork = artwork,
        ).also {
            if (it == null) CatalogLog.w("PlaylistMappers mapTrackItem Song.create rejected url=$url title=$rawTitle")
        }
    }
}

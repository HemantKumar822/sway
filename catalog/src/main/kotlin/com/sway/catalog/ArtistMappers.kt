package com.sway.catalog

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.ArtworkRef
import com.sway.core.model.DurationMs
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Artist detail mappers — story 3.4 (AR-2, AR-8, AR-10, FR-6 trace).
 *
 * Responsibilities:
 * - Map raw channel fields (name, avatar Images) + ordered top-song items
 *   + discography sections (albums/singles) to a typed [Artist].
 * - Sections modeled as available/unavailable flags: callers pass
 *   `null` for [albumItems]/[singleItems] to signal unavailable (not
 *   empty-as-success). When non-null the list is mapped (may be empty
 *   but marked available). This satisfies the degraded OQ-1 contract:
 *   "sections absent omit cleanly" and AC "without discography =>
 *   unavailable not empty".
 * - Artwork is the circular portrait — normalized via
 *   [SearchMappers.artworkFromImages] (AD-11: ytimg/ggpht chain, cache
 *   key == canonical). Callers compute it once at parse time.
 * - Top-songs are ordered and playable-typed: source order preserved,
 *   blank-id items dropped with logged shape info (AR-8), duration
 *   ms-typed via [DurationMs].
 *
 * Pure except for [CatalogLog] side-effects on dropped items.
 */
internal object ArtistMappers {

    /**
     * Build a typed [Artist] from raw detail fields.
     *
     * @param artistId raw artist/channel id (SourceId law enforced; blank -> null).
     * @param rawName raw artist name (sanitized via Artist.create; raw preserved).
     * @param avatarImages thumbnail/avatar Images for circular portrait artwork.
     * @param topSongItems ordered StreamInfoItems for top songs (Must — playable).
     * @param albumItems nullable list of PlaylistInfoItems for albums; null = unavailable.
     * @param singleItems nullable list for singles; null = unavailable.
     * @return Artist or null if [artistId] blank (AR-8).
     */
    fun toArtist(
        artistId: String,
        rawName: String,
        avatarImages: List<Image>,
        topSongItems: List<StreamInfoItem>,
        albumItems: List<PlaylistInfoItem>?,
        singleItems: List<PlaylistInfoItem>?,
    ): Artist? {
        val sourceId = SourceId.parse(artistId) ?: run {
            CatalogLog.w("ArtistMappers dropped artist blank id rawName=${rawName.take(40)}")
            return null
        }

        val artwork: ArtworkRef? = SearchMappers.artworkFromImages(
            images = avatarImages,
            fallbackVideoId = null,
        )

        // Map top songs preserving order, dropping blank-id.
        val topSongs = mutableListOf<Song>()
        var droppedSongs = 0
        for ((index, item) in topSongItems.withIndex()) {
            val song = mapTopSongItem(item, index)
            if (song != null) topSongs.add(song) else droppedSongs++
        }
        if (droppedSongs > 0) {
            CatalogLog.w("ArtistMappers dropped $droppedSongs blank-id topSongs of ${topSongItems.size} for artist $artistId")
        }

        // Map albums/singles if available (null => unavailable flag false).
        val albumsAvailable = albumItems != null
        val singlesAvailable = singleItems != null

        val albums: List<Album> = if (albumsAvailable) {
            mapAlbumItems(albumItems!!, artistId)
        } else emptyList()

        val singles: List<Album> = if (singlesAvailable) {
            mapAlbumItems(singleItems!!, artistId)
        } else emptyList()

        return Artist.create(
            id = sourceId.value,
            rawName = rawName,
            artwork = artwork,
            topSongs = topSongs,
            albums = albums,
            singles = singles,
            albumsAvailable = albumsAvailable,
            singlesAvailable = singlesAvailable,
        ).also {
            if (it == null) CatalogLog.w("ArtistMappers Artist.create rejected id=$artistId name=$rawName")
        }
    }

    private fun mapTopSongItem(item: StreamInfoItem, index: Int): Song? {
        val url = try { item.url ?: "" } catch (e: Exception) {
            CatalogLog.w("mapTopSongItem safeUrl threw ${e.javaClass.simpleName} at $index")
            ""
        }
        val rawTitle = try { item.name ?: "" } catch (e: Exception) {
            CatalogLog.w("mapTopSongItem safeName threw ${e.javaClass.simpleName} at $index")
            ""
        }
        val songId = SearchMappers.extractSongId(url)
        val sourceId = SourceId.parse(songId)
        if (sourceId == null) {
            CatalogLog.w("mapTopSongItem dropped blank song id at index $index url=${url.take(80)} title=${rawTitle.take(40)}")
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
            if (it == null) CatalogLog.w("mapTopSongItem Song.create rejected url=$url title=$rawTitle")
        }
    }

    private fun mapAlbumItems(items: List<PlaylistInfoItem>, artistId: String): List<Album> {
        val out = mutableListOf<Album>()
        var dropped = 0
        for (item in items) {
            val url = try { item.url ?: "" } catch (_: Exception) { "" }
            val rawTitle = try { item.name ?: "" } catch (_: Exception) { "" }
            val albumId = SearchMappers.extractAlbumId(url)
            val sourceId = SourceId.parse(albumId)
            if (sourceId == null) {
                CatalogLog.w("mapAlbumItems dropped blank album id url=${url.take(80)} title=${rawTitle.take(40)}")
                dropped++
                continue
            }
            val uploaderName: String? = try { item.uploaderName?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }
            val uploaderId: String? = try { SearchMappers.extractArtistId(item.uploaderUrl) } catch (_: Exception) { null }
            val thumbs: List<Image> = try {
                @Suppress("UNCHECKED_CAST")
                (item.thumbnails as? List<Image>) ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val artwork = SearchMappers.artworkFromImages(
                images = thumbs,
                fallbackVideoId = null,
            )
            val album = Album.create(
                id = sourceId.value,
                rawTitle = rawTitle,
                artistName = uploaderName,
                artistId = uploaderId,
                year = null,
                artwork = artwork,
                tracks = emptyList(),
            )
            if (album != null) out.add(album) else {
                CatalogLog.w("mapAlbumItems Album.create rejected url=$url")
                dropped++
            }
        }
        if (dropped > 0) CatalogLog.w("ArtistMappers dropped $dropped blank album ids of ${items.size} for artist $artistId")
        return out
    }
}

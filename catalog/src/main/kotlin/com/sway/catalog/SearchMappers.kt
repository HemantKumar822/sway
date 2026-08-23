package com.sway.catalog

import com.sway.core.model.Album
import com.sway.core.model.Artist
import com.sway.core.model.ArtworkRef
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.DurationMs
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Four-type search mappers — story 3.2 (AR-2, AR-8, AR-10, FR-1 trace).
 *
 * Parse-time ArtworkRef normalization (AD-11), duration conversion (seconds→ms via DurationMs),
 * blank-id dropped with logged shape info (AR-8). Each mapper is pure except for CatalogLog
 * side-effects on drop; callers run them on Default dispatcher.
 */
internal object SearchMappers {

    // -------------------------------------------------------------------------
    // Song — expects StreamInfoItem (MUSIC_SONGS). Falls back to generic InfoItem name/url.
    // -------------------------------------------------------------------------
    fun toSong(item: InfoItem): Song? {
        val url = safeUrl(item)
        val rawTitle = safeName(item)
        val songId = extractSongId(url)
        val sourceId = SourceId.parse(songId)
        if (sourceId == null) {
            logDropped(item, "blank song id url=${url.take(80)}")
            return null
        }

        // Duration: StreamInfoItem.getDuration() is seconds; -1 means unknown.
        val durationMs: Long = try {
            if (item is StreamInfoItem) {
                val sec = item.duration
                if (sec < 0) 0L else sec * 1000L
            } else 0L
        } catch (_: Exception) {
            0L
        }
        val duration = DurationMs.clamp(durationMs)

        val artistName: String? = try {
            if (item is StreamInfoItem) item.uploaderName?.trim()?.takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }

        val artistId: String? = try {
            if (item is StreamInfoItem) extractArtistId(item.uploaderUrl) else null
        } catch (_: Exception) { null }

        val artwork = artworkFromImages(
            images = safeThumbnails(item),
            fallbackVideoId = songId,
        )

        // Album fields not present in search row; leave null.
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
            if (it == null) {
                logDropped(item, "Song.create rejected (unexpected) url=$url title=$rawTitle")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Album — expects PlaylistInfoItem (MUSIC_ALBUMS). Year omitted (null) in search context.
    // -------------------------------------------------------------------------
    fun toAlbum(item: InfoItem): Album? {
        val url = safeUrl(item)
        val rawTitle = safeName(item)
        val albumId = extractAlbumId(url)
        val sourceId = SourceId.parse(albumId)
        if (sourceId == null) {
            logDropped(item, "blank album id url=${url.take(80)}")
            return null
        }

        val artistName: String? = try {
            if (item is PlaylistInfoItem) item.uploaderName?.trim()?.takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }

        val artistId: String? = try {
            if (item is PlaylistInfoItem) extractArtistId(item.uploaderUrl) else null
        } catch (_: Exception) { null }

        val artwork = artworkFromImages(
            images = safeThumbnails(item),
            fallbackVideoId = null, // albums not video-id based
        )

        return Album.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            artistName = artistName,
            artistId = artistId,
            year = null,
            artwork = artwork,
        ).also {
            if (it == null) logDropped(item, "Album.create rejected url=$url")
        }
    }

    // -------------------------------------------------------------------------
    // Artist — expects ChannelInfoItem (MUSIC_ARTISTS).
    // -------------------------------------------------------------------------
    fun toArtist(item: InfoItem): Artist? {
        val url = safeUrl(item)
        val rawName = safeName(item)
        val artistId = extractArtistId(url)
        val sourceId = SourceId.parse(artistId)
        if (sourceId == null) {
            logDropped(item, "blank artist id url=${url.take(80)}")
            return null
        }

        val artwork = artworkFromImages(
            images = safeThumbnails(item),
            fallbackVideoId = null,
        )

        return Artist.create(
            id = sourceId.value,
            rawName = rawName,
            artwork = artwork,
            topSongs = emptyList(),
        ).also {
            if (it == null) logDropped(item, "Artist.create rejected url=$url")
        }
    }

    // -------------------------------------------------------------------------
    // CatalogPlaylist — expects PlaylistInfoItem (MUSIC_PLAYLISTS) curated, read-only.
    // -------------------------------------------------------------------------
    fun toCatalogPlaylist(item: InfoItem): CatalogPlaylist? {
        val url = safeUrl(item)
        val rawTitle = safeName(item)
        val playlistId = extractAlbumId(url) // same extraction as album (list= or browse/)
        val sourceId = SourceId.parse(playlistId)
        if (sourceId == null) {
            logDropped(item, "blank playlist id url=${url.take(80)}")
            return null
        }

        val curator: String? = try {
            if (item is PlaylistInfoItem) item.uploaderName?.trim()?.takeIf { it.isNotEmpty() } else null
        } catch (_: Exception) { null }

        val trackCount: Int? = try {
            if (item is PlaylistInfoItem) {
                val c = item.streamCount
                if (c < 0) null else c.toInt()
            } else null
        } catch (_: Exception) { null }

        val artwork = artworkFromImages(
            images = safeThumbnails(item),
            fallbackVideoId = null,
        )

        return CatalogPlaylist.create(
            id = sourceId.value,
            rawTitle = rawTitle,
            curator = curator,
            trackCount = trackCount,
            artwork = artwork,
        ).also {
            if (it == null) logDropped(item, "CatalogPlaylist.create rejected url=$url")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun safeName(item: InfoItem): String = try {
        item.name ?: ""
    } catch (e: Exception) {
        CatalogLog.w("safeName threw ${e.javaClass.simpleName} for ${item.javaClass.simpleName}")
        ""
    }

    private fun safeUrl(item: InfoItem): String = try {
        item.url ?: ""
    } catch (e: Exception) {
        CatalogLog.w("safeUrl threw ${e.javaClass.simpleName} for ${item.javaClass.simpleName}")
        ""
    }

    private fun safeThumbnails(item: InfoItem): List<Image> = try {
        @Suppress("UNCHECKED_CAST")
        (item.thumbnails as? List<Image>) ?: emptyList()
    } catch (e: Exception) {
        CatalogLog.w("safeThumbnails threw ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun logDropped(item: InfoItem, reason: String) {
        val shape = try {
            "dropped ${item.javaClass.simpleName} reason=$reason name=${safeName(item).take(40)} url=${safeUrl(item).take(80)} thumbs=${safeThumbnails(item).size} infoType=${item.infoType}"
        } catch (e: Exception) {
            "dropped ${item.javaClass.simpleName} reason=$reason (shape log failed ${e.message})"
        }
        CatalogLog.w(shape)
    }

    // ---------------- Artwork normalization (AD-11) ----------------

    internal fun artworkFromImages(images: List<Image>, fallbackVideoId: String?): ArtworkRef? {
        val urls = images.mapNotNull { it.url?.trim()?.takeIf { u -> u.isNotEmpty() } }.distinct()
        if (urls.isEmpty()) {
            return if (!fallbackVideoId.isNullOrBlank()) ArtworkRef.synthetic(fallbackVideoId) else null
        }

        // Sort by resolution descending for canonical selection before normalization.
        val sortedUrls = images
            .sortedByDescending { img ->
                val w = if (img.width == Image.WIDTH_UNKNOWN) 0 else img.width
                val h = if (img.height == Image.HEIGHT_UNKNOWN) 0 else img.height
                w.toLong() * h.toLong()
            }
            .mapNotNull { it.url?.trim()?.takeIf { u -> u.isNotEmpty() } }
            .distinct()
            .ifEmpty { urls }

        val canonicalCandidate = sortedUrls.firstOrNull() ?: urls.first()
        val videoId = extractVideoIdFromThumbnail(canonicalCandidate) ?: fallbackVideoId?.trim()?.takeIf { it.isNotEmpty() }

        // Build host-specific candidate chain.
        val chain = mutableListOf<String>()

        if (canonicalCandidate.contains("ytimg.com") && videoId != null) {
            val base = "https://i.ytimg.com/vi/$videoId"
            chain.addAll(
                listOf(
                    "$base/maxresdefault.jpg",
                    "$base/sddefault.jpg",
                    "$base/hqdefault.jpg",
                    "$base/mqdefault.jpg",
                ),
            )
        }

        if (canonicalCandidate.contains("ggpht") || canonicalCandidate.contains("googleusercontent")) {
            val sPat = Regex("=s\\d+")
            if (sPat.containsMatchIn(canonicalCandidate)) {
                chain.add(sPat.replace(canonicalCandidate, "=s1080"))
                chain.add(sPat.replace(canonicalCandidate, "=s720"))
                chain.add(sPat.replace(canonicalCandidate, "=s544"))
            } else {
                val whPat = Regex("-w\\d+-h\\d+")
                if (whPat.containsMatchIn(canonicalCandidate)) {
                    chain.add(whPat.replace(canonicalCandidate, "-w1080-h1080"))
                    chain.add(whPat.replace(canonicalCandidate, "-w720-h720"))
                    chain.add(whPat.replace(canonicalCandidate, "-w544-h544"))
                }
            }
        }

        // Merge chain + sorted urls, ensure canonical is first and distinct.
        val finalCanonical = if (chain.isNotEmpty() && canonicalCandidate.contains("ytimg.com")) {
            chain.first()
        } else canonicalCandidate

        val all = mutableListOf<String>()
        if (chain.isNotEmpty()) all.addAll(chain)
        else all.add(finalCanonical)
        for (u in sortedUrls) if (u !in all) all.add(u)
        for (u in urls) if (u !in all) all.add(u)

        val distinct = mutableListOf<String>()
        distinct.add(finalCanonical)
        for (u in all) if (u != finalCanonical && u !in distinct) distinct.add(u)

        return ArtworkRef.parse(finalCanonical, distinct)
            ?: ArtworkRef.parse(canonicalCandidate, sortedUrls)
            ?: if (videoId != null) ArtworkRef.synthetic(videoId) else null
    }

    private fun extractVideoIdFromThumbnail(url: String): String? {
        // i.ytimg.com/vi/VIDEO_ID/
        Regex("/vi/([^/]+)/").find(url)?.let { return it.groupValues[1] }
        Regex("[?&]v=([^&]+)").find(url)?.let { return it.groupValues[1] }
        return null
    }

    // ---------------- ID extraction ----------------

    internal fun extractSongId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("[?&]v=([^&?/]+)").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        Regex("/shorts/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        Regex("/vi/([^/]+)/").find(url)?.let { return it.groupValues[1] }
        val last = url.substringAfterLast("/").substringBefore("?").substringBefore("&").trim()
        return last.takeIf { it.length in 5..64 && it.matches(Regex("[A-Za-z0-9_-]+")) }
    }

    internal fun extractAlbumId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("[?&]list=([^&]+)").find(url)?.let { return it.groupValues[1] }
        Regex("browse/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        Regex("playlist/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        val last = url.substringAfterLast("/").substringBefore("?").trim()
        return last.takeIf { it.isNotBlank() }
    }

    internal fun extractArtistId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        Regex("/channel/([^/?&]+)").find(url)?.let { return it.groupValues[1] }
        Regex("/c/([^/?&]+)").find(url)?.let { return it.groupValues[1] }
        Regex("/user/([^/?&]+)").find(url)?.let { return it.groupValues[1] }
        Regex("browse/([^?&/]+)").find(url)?.let { return it.groupValues[1] }
        // UC... channel ids are 24 chars starting with UC
        Regex("(UC[A-Za-z0-9_-]{22})").find(url)?.let { return it.groupValues[1] }
        val last = url.substringAfterLast("/").substringBefore("?").trim()
        return last.takeIf { it.isNotBlank() }
    }
}

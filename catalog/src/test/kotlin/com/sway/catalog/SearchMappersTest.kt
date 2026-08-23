package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.DurationMs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Story 3.2 — Four-type search mappers: fixture-driven contract tests.
 *
 * Verifies: title/ids/duration/artwork-chain and page tokens; blank-id dropped;
 * ArtworkRef normalization; duration conversion.
 * Pure mapper tests (no MockWebServer) — fixtures are constructed InfoItems mirroring
 * recorded payload shapes per AC "recorded fixtures per type".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SearchMappersTest {

    // -------------------------------------------------------------------------
    // Helpers to build fixture InfoItems without extractor JSON
    // -------------------------------------------------------------------------

    private fun image(url: String, w: Int = 640, h: Int = 640) = Image(url, w, h, Image.ResolutionLevel.MEDIUM)

    private fun streamItem(
        url: String,
        name: String,
        uploaderName: String? = "Test Artist",
        uploaderUrl: String? = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
        durationSec: Long = 218,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 480, 360)),
    ): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, StreamType.VIDEO_STREAM)
        item.uploaderName = uploaderName
        item.uploaderUrl = uploaderUrl
        item.duration = durationSec
        item.thumbnails = thumbs
        return item
    }

    private fun playlistItem(
        url: String,
        name: String,
        uploaderName: String? = "Curator Name",
        uploaderUrl: String? = "https://www.youtube.com/channel/UC9999999999999999999999",
        count: Long = 12,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/abc123/hqdefault.jpg")),
    ): PlaylistInfoItem {
        val item = PlaylistInfoItem(0, url, name)
        item.uploaderName = uploaderName
        item.uploaderUrl = uploaderUrl
        item.streamCount = count
        item.thumbnails = thumbs
        return item
    }

    private fun channelItem(
        url: String,
        name: String,
        thumbs: List<Image> = listOf(image("https://yt3.ggpht.com/ytc/ABC=s88-c-k-c0x00ffffff-no-rj", 88, 88)),
    ): ChannelInfoItem {
        val item = ChannelInfoItem(0, url, name)
        item.thumbnails = thumbs
        return item
    }

    // -------------------------------------------------------------------------
    // Songs
    // -------------------------------------------------------------------------

    @Test
    fun `toSong fixture carries title ids duration artwork-chain`() {
        val item = streamItem(
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            name = "  Never   Gonna Give You Up  ",
            durationSec = 212,
            thumbs = listOf(
                image("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", 1280, 720),
                image("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", 480, 360),
            ),
        )
        val song = SearchMappers.toSong(item)
        assertNotNull(song)
        song!!
        assertEquals("dQw4w9WgXcQ", song.id.value)
        assertEquals("Never Gonna Give You Up", song.title)
        assertEquals("  Never   Gonna Give You Up  ", song.rawTitle)
        assertEquals(DurationMs(212_000L), song.duration)
        assertNotNull(song.artwork)
        // Artwork normalization: ytimg chain should expand to 4 variants, canonical = maxres
        assertTrue(song.artwork!!.canonicalUrl.contains("maxresdefault"))
        assertEquals(song.artwork!!.cacheKey, song.artwork!!.canonicalUrl)
        assertTrue(song.artwork!!.candidates.size >= 4)
        assertTrue(song.artwork!!.candidates.any { it.contains("mqdefault") })
        assertNotNull(song.artistName)
        assertNotNull(song.artistId)
    }

    @Test
    fun `toSong duration conversion seconds to ms and negative clamped to zero`() {
        val itemPos = streamItem("https://www.youtube.com/watch?v=aaaaabbbbb1", "Song", durationSec = 180)
        assertEquals(180_000L, SearchMappers.toSong(itemPos)!!.duration.millis)

        val itemNeg = streamItem("https://www.youtube.com/watch?v=aaaaabbbbb2", "Song", durationSec = -1)
        assertEquals(0L, SearchMappers.toSong(itemNeg)!!.duration.millis)

        val itemZero = streamItem("https://www.youtube.com/watch?v=aaaaabbbbb3", "Song", durationSec = 0)
        assertEquals(0L, SearchMappers.toSong(itemZero)!!.duration.millis)
    }

    @Test
    fun `toSong artwork fallback synthesizes from videoId when thumbs empty`() {
        val item = streamItem(
            url = "https://www.youtube.com/watch?v=synth123456",
            name = "Synth",
            thumbs = emptyList(),
        )
        val song = SearchMappers.toSong(item)
        assertNotNull(song!!.artwork)
        assertTrue(song.artwork!!.canonicalUrl.contains("synth123456"))
        assertEquals(4, song.artwork!!.candidates.size) // synthetic chain
    }

    @Test
    fun `toSong googleusercontent artwork rewrites size params descending`() {
        val gImg = image("https://yt3.ggpht.com/ytc/ABC=s100-c-k-c0x00ffffff-no-rj", 100, 100)
        val item = streamItem("https://www.youtube.com/watch?v=goog1234567", "Goog", thumbs = listOf(gImg))
        val song = SearchMappers.toSong(item)!!
        assertTrue(song.artwork!!.candidates.any { it.contains("=s1080") })
        assertTrue(song.artwork!!.candidates.any { it.contains("=s720") })
        assertTrue(song.artwork!!.candidates.any { it.contains("=s544") })
    }

    // -------------------------------------------------------------------------
    // Albums
    // -------------------------------------------------------------------------

    @Test
    fun `toAlbum fixture carries title ids and artwork-chain year null`() {
        val item = playlistItem(
            url = "https://music.youtube.com/playlist?list=OLAK5uy_n_test_album",
            name = "Test Album",
        )
        val album = SearchMappers.toAlbum(item)
        assertNotNull(album)
        album!!
        assertEquals("OLAK5uy_n_test_album", album.id.value)
        assertEquals("Test Album", album.title)
        assertNull(album.year) // search row never has year
        assertNotNull(album.artwork)
        assertEquals(album.artwork!!.cacheKey, album.artwork!!.canonicalUrl)
    }

    // -------------------------------------------------------------------------
    // Artists
    // -------------------------------------------------------------------------

    @Test
    fun `toArtist fixture carries name ids and artwork`() {
        val item = channelItem(
            url = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
            name = "Test Artist",
        )
        val artist = SearchMappers.toArtist(item)
        assertNotNull(artist)
        artist!!
        assertEquals("UC1234567890ABCDEF123456", artist.id.value)
        assertEquals("Test Artist", artist.name)
        assertNotNull(artist.artwork)
    }

    @Test
    fun `toArtist googleusercontent variants handled`() {
        val thumbs = listOf(
            image("https://yt3.ggpht.com/ytc/ABC=s88-c-k-c0x00ffffff-no-rj", 88, 88),
            image("https://yt3.ggpht.com/ytc/ABC=s176-c-k-c0x00ffffff-no-rj", 176, 176),
        )
        val item = channelItem("https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAAAA", "Artist", thumbs)
        val artist = SearchMappers.toArtist(item)!!
        assertNotNull(artist.artwork)
        // canonical should be largest (176)
        assertTrue(artist.artwork!!.canonicalUrl.contains("s176") || artist.artwork!!.canonicalUrl.contains("s1080"))
    }

    // -------------------------------------------------------------------------
    // CatalogPlaylists
    // -------------------------------------------------------------------------

    @Test
    fun `toCatalogPlaylist fixture carries title curator count artwork`() {
        val item = playlistItem(
            url = "https://www.youtube.com/playlist?list=PLtest1234567890",
            name = "Curated Playlist",
            uploaderName = "Curator",
            count = 42,
        )
        val pl = SearchMappers.toCatalogPlaylist(item)
        assertNotNull(pl)
        pl!!
        assertEquals("PLtest1234567890", pl.id.value)
        assertEquals("Curated Playlist", pl.title)
        assertEquals("Curator", pl.curator)
        assertEquals(42, pl.trackCount)
        assertNotNull(pl.artwork)
    }

    @Test
    fun `toCatalogPlaylist count negative treated as null`() {
        val item = playlistItem("https://www.youtube.com/playlist?list=PLneg", "PL", count = -1)
        val pl = SearchMappers.toCatalogPlaylist(item)!!
        assertNull(pl.trackCount)
    }

    // -------------------------------------------------------------------------
    // Blank-id dropped — siblings survive
    // -------------------------------------------------------------------------

    @Test
    fun `blank-id items dropped while siblings survive for each type`() {
        val goodSong = streamItem("https://www.youtube.com/watch?v=good12345678", "Good")
        val badSong = streamItem("", "Bad Blank URL")
        val blankSong = SearchMappers.toSong(badSong)
        assertNull(blankSong)
        assertNotNull(SearchMappers.toSong(goodSong))

        val goodAlbum = playlistItem("https://music.youtube.com/playlist?list=OLAKgood", "Good Album")
        val badAlbum = playlistItem("", "Bad")
        assertNull(SearchMappers.toAlbum(badAlbum))
        assertNotNull(SearchMappers.toAlbum(goodAlbum))

        val goodArtist = channelItem("https://www.youtube.com/channel/UCgood1234567890123456", "Good")
        val badArtist = channelItem("", "Bad")
        assertNull(SearchMappers.toArtist(badArtist))
        assertNotNull(SearchMappers.toArtist(goodArtist))

        val goodPl = playlistItem("https://www.youtube.com/playlist?list=PLgood", "Good PL")
        val badPl = playlistItem("   ", "Bad")
        assertNull(SearchMappers.toCatalogPlaylist(badPl))
        assertNotNull(SearchMappers.toCatalogPlaylist(goodPl))

        // Bulk: 3 items where middle is blank — 2 survive
        val items = listOf(goodSong, badSong, goodSong.copyUrl("https://www.youtube.com/watch?v=good2_123456"))
        val mapped = items.mapNotNull { SearchMappers.toSong(it) }
        assertEquals(2, mapped.size)
    }

    @Test
    fun `whitespace id is treated as blank and dropped`() {
        val item = streamItem("   ", "Whitespace ID")
        assertNull(SearchMappers.toSong(item))
        val item2 = streamItem("https://www.youtube.com/watch?v=   ", "Whitespace v param")
        // v param is whitespace? our regex requires non-blank char, so last fallback may treat as blank? Let's assert dropped
        // For this URL, extractSongId will try regex and fail to find v= with blank, then last segment is "   " trimmed empty => null
        assertNull(SearchMappers.toSong(item2))
    }

    // -------------------------------------------------------------------------
    // Page token codec
    // -------------------------------------------------------------------------

    @Test
    fun `page token codec round-trip preserves page validity`() {
        val page = org.schabi.newpipe.extractor.Page("https://www.youtube.com/results?search_query=test", "contToken123")
        val token = SearchPageTokenCodec.encode(page)
        assertNotNull(token)
        assertTrue(token.isNotBlank())
        val decoded = SearchPageTokenCodec.decode(token)
        assertNotNull(decoded)
        assertEquals(page.url, decoded!!.url)
        assertEquals(page.id, decoded.id)
    }

    @Test
    fun `page token with ids and cookies round-trips`() {
        val page = org.schabi.newpipe.extractor.Page(
            "https://www.youtube.com/youtubei/v1/search",
            "contXYZ",
            listOf("id1", "id2"),
            mapOf("VISITOR_INFO1_LIVE" to "abc", "GPS" to "1"),
            "bodybytes".toByteArray(),
        )
        val token = SearchPageTokenCodec.encode(page)
        val decoded = SearchPageTokenCodec.decode(token)!!
        assertEquals(page.url, decoded.url)
        assertEquals(page.id, decoded.id)
        assertEquals(page.ids, decoded.ids)
        assertEquals(page.cookies, decoded.cookies)
        assertArrayEquals(page.body, decoded.body)
    }

    @Test
    fun `invalid page token decodes to null`() {
        assertNull(SearchPageTokenCodec.decode(null))
        assertNull(SearchPageTokenCodec.decode(""))
        assertNull(SearchPageTokenCodec.decode("   "))
        assertNull(SearchPageTokenCodec.decode("not-base64!!!"))
        assertNull(SearchPageTokenCodec.decode("aW52YWxpZCBqc29u")) // valid base64 but invalid json shape
    }

    // helper to copy stream item with new url for bulk test
    private fun StreamInfoItem.copyUrl(newUrl: String): StreamInfoItem {
        val c = StreamInfoItem(this.serviceId, newUrl, this.name, this.streamType)
        c.uploaderName = this.uploaderName
        c.uploaderUrl = this.uploaderUrl
        c.duration = this.duration
        c.thumbnails = this.thumbnails
        return c
    }
}

package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Story 3.3 — Album detail mapper: fixture contract tests.
 *
 * Verifies (per ACs):
 * - year present → populated, year absent → null (never ""), invalid year → null
 * - track order preserved, ids unique/non-blank, blank-id dropped siblings survive
 * - artwork chain per track + hero (ArtworkRef normalization, ytimg chain, fallback synthetic)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AlbumMappersTest {

    private fun image(url: String, w: Int = 640, h: Int = 640) =
        Image(url, w, h, Image.ResolutionLevel.MEDIUM)

    private fun trackItem(
        url: String,
        name: String,
        durationSec: Long = 200,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/${extractVid(url)}/hqdefault.jpg", 480, 360)),
        uploaderName: String? = "Album Artist",
        uploaderUrl: String? = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
    ): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, StreamType.VIDEO_STREAM)
        item.uploaderName = uploaderName
        item.uploaderUrl = uploaderUrl
        item.duration = durationSec
        item.thumbnails = thumbs
        return item
    }

    private fun extractVid(url: String): String {
        Regex("[?&]v=([^&]+)").find(url)?.let { return it.groupValues[1] }
        val last = url.substringAfterLast("/").substringBefore("?").trim()
        return last.ifBlank { "testVid1234" }
    }

    // -------------------------------------------------------------------------
    // Year present / absent / invalid
    // -------------------------------------------------------------------------

    @Test
    fun `year present is populated from description`() {
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_test_album_year_present",
            rawTitle = "Album With Year",
            artistName = "Artist A",
            artistUrl = "https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAAAA",
            rawYearText = "Released • 2021 • 10 songs",
            heroImages = listOf(image("https://i.ytimg.com/vi/hero1/hqdefault.jpg")),
            trackItems = emptyList(),
        )
        assertNotNull(album)
        assertEquals(2021, album!!.year)
    }

    @Test
    fun `year absent yields null clean omission`() {
        val albumNoYear = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_test_no_year",
            rawTitle = "Album Without Year",
            artistName = "Artist B",
            artistUrl = null,
            rawYearText = "10 songs • Album",
            heroImages = listOf(image("https://i.ytimg.com/vi/hero2/hqdefault.jpg")),
            trackItems = emptyList(),
        )
        assertNotNull(albumNoYear)
        assertNull(albumNoYear!!.year)

        val albumNullDesc = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_test_null_desc",
            rawTitle = "Album Null Year",
            artistName = null,
            artistUrl = null,
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(albumNullDesc)
        assertNull(albumNullDesc!!.year)
        // Year is Int? never "" — verify type is nullable Int, not string
        assertTrue(albumNullDesc.year == null)
    }

    @Test
    fun `year invalid out of range coerced to null`() {
        val album199 = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_year_invalid_999",
            rawTitle = "Old",
            artistName = null,
            artistUrl = null,
            rawYearText = "Year 999 — ancient",
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(album199)
        assertNull(album199!!.year)

        val album9999 = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_year_invalid_9999",
            rawTitle = "Future",
            artistName = null,
            artistUrl = null,
            rawYearText = "9999 edition",
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(album9999)
        assertNull(album9999!!.year)
    }

    @Test
    fun `extractYear picks first plausible year`() {
        assertEquals(1999, AlbumMappers.extractYear("Recorded 1999 released 2020"))
        assertNull(AlbumMappers.extractYear(""))
        assertNull(AlbumMappers.extractYear(null))
        assertNull(AlbumMappers.extractYear("no year here"))
        assertEquals(2000, AlbumMappers.extractYear("2000"))
    }

    // -------------------------------------------------------------------------
    // Track order, ids unique non-blank, blank-id dropped
    // -------------------------------------------------------------------------

    @Test
    fun `track order matches source order and ids unique non-blank`() {
        val t1 = trackItem("https://www.youtube.com/watch?v=track1111111", "Track One", durationSec = 180)
        val t2 = trackItem("https://www.youtube.com/watch?v=track2222222", "Track Two", durationSec = 200)
        val t3 = trackItem("https://www.youtube.com/watch?v=track3333333", "Track Three", durationSec = 210)
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_order_test",
            rawTitle = "Ordered Album",
            artistName = "Artist",
            artistUrl = null,
            rawYearText = "2022",
            heroImages = emptyList(),
            trackItems = listOf(t1, t2, t3),
        )
        assertNotNull(album)
        album!!
        assertEquals(3, album.tracks.size)
        assertEquals("track1111111", album.tracks[0].id.value)
        assertEquals("track2222222", album.tracks[1].id.value)
        assertEquals("track3333333", album.tracks[2].id.value)
        // IDs unique and non-blank
        val ids = album.tracks.map { it.id.value }
        assertEquals(3, ids.distinct().size)
        assertTrue(ids.all { it.isNotBlank() })
        // Order preserved: titles match input order
        assertEquals("Track One", album.tracks[0].title)
        assertEquals("Track Two", album.tracks[1].title)
        assertEquals("Track Three", album.tracks[2].title)
    }

    @Test
    fun `blank-id tracks dropped siblings survive`() {
        val good1 = trackItem("https://www.youtube.com/watch?v=good11111111", "Good 1")
        val bad = trackItem("", "Bad Blank URL")
        val good2 = trackItem("https://www.youtube.com/watch?v=good22222222", "Good 2")
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_blank_track_test",
            rawTitle = "Blank Dropped",
            artistName = null,
            artistUrl = null,
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = listOf(good1, bad, good2),
        )
        assertNotNull(album)
        album!!
        assertEquals(2, album.tracks.size)
        assertTrue(album.tracks.all { it.id.value.startsWith("good") })
        assertFalse(album.tracks.any { it.title.contains("Bad") })
    }

    @Test
    fun `blank album id returns null`() {
        val result = AlbumMappers.toAlbum(
            albumId = "   ",
            rawTitle = "Should Fail",
            artistName = null,
            artistUrl = null,
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // Artwork chain per track + hero
    // -------------------------------------------------------------------------

    @Test
    fun `hero artwork chain normalized ytimg maxres first`() {
        val heroThumbs = listOf(
            image("https://i.ytimg.com/vi/heroAlbumId/maxresdefault.jpg", 1280, 720),
            image("https://i.ytimg.com/vi/heroAlbumId/hqdefault.jpg", 480, 360),
        )
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_hero_artwork",
            rawTitle = "Hero Artwork",
            artistName = null,
            artistUrl = null,
            rawYearText = null,
            heroImages = heroThumbs,
            trackItems = emptyList(),
        )
        assertNotNull(album!!.artwork)
        assertTrue(album.artwork!!.canonicalUrl.contains("maxresdefault"))
        assertTrue(album.artwork!!.candidates.any { it.contains("mqdefault") })
        assertEquals(album.artwork!!.cacheKey, album.artwork!!.canonicalUrl)
    }

    @Test
    fun `per-track artwork chain normalized and fallback synthetic`() {
        val withThumb = trackItem(
            url = "https://www.youtube.com/watch?v=vidWithThumb1",
            name = "With Thumb",
            thumbs = listOf(image("https://i.ytimg.com/vi/vidWithThumb1/maxresdefault.jpg", 1280, 720)),
        )
        val withoutThumb = trackItem(
            url = "https://www.youtube.com/watch?v=vidNoThumb999",
            name = "No Thumb",
            thumbs = emptyList(),
        )
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_track_artwork",
            rawTitle = "Track Artwork Album",
            artistName = null,
            artistUrl = null,
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = listOf(withThumb, withoutThumb),
        )
        assertNotNull(album)
        album!!
        assertEquals(2, album.tracks.size)
        // First track has ytimg chain starting with maxres
        val t0Art = album.tracks[0].artwork
        assertNotNull(t0Art)
        assertTrue(t0Art!!.candidates.any { it.contains("maxresdefault") })
        assertTrue(t0Art.candidates.any { it.contains("mqdefault") })
        // Second track synthetic from videoId
        val t1Art = album.tracks[1].artwork
        assertNotNull(t1Art)
        assertTrue(t1Art!!.canonicalUrl.contains("vidNoThumb999"))
        assertEquals(4, t1Art.candidates.size)
    }

    @Test
    fun `track duration conversion seconds to ms and album title sanitized raw preserved`() {
        val item = trackItem("https://www.youtube.com/watch?v=dur123456789", "  My   Track  ", durationSec = 212)
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_duration_sanitize",
            rawTitle = "  My   Album  Title  ",
            artistName = "  Artist Name  ",
            artistUrl = null,
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = listOf(item),
        )
        assertNotNull(album)
        album!!
        assertEquals("My Album Title", album.title)
        assertEquals("  My   Album  Title  ", album.rawTitle)
        assertEquals(212_000L, album.tracks.first().duration.millis)
        assertEquals("My Track", album.tracks.first().title)
        assertEquals("  My   Track  ", album.tracks.first().rawTitle)
    }

    @Test
    fun `artist id extracted from uploaderUrl`() {
        val album = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_artist_id",
            rawTitle = "Album",
            artistName = "Artist",
            artistUrl = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(album!!.artistId)
        assertEquals("UC1234567890ABCDEF123456", album.artistId!!.value)

        val album2 = AlbumMappers.toAlbum(
            albumId = "OLAK5uy_artist_mpre",
            rawTitle = "Album2",
            artistName = "Artist2",
            artistUrl = "https://music.youtube.com/browse/MPREb_test123",
            rawYearText = null,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(album2!!.artistId)
        assertEquals("MPREb_test123", album2.artistId!!.value)
    }
}

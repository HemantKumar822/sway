package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Story 3.4 — Artist detail mapper: fixture contract tests.
 *
 * Verifies (per ACs):
 * - without discography payload => albums/singles unavailable (not empty-as-success)
 * - top-songs payload => ordered, playable-typed (SourceId valid, duration ms, artwork chain)
 * - circular-image ArtworkRef (avatar ggpht size chain) + available/unavailable flags
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ArtistMappersTest {

    private fun image(url: String, w: Int = 640, h: Int = 640) =
        Image(url, w, h, Image.ResolutionLevel.MEDIUM)

    private fun topSong(
        url: String,
        name: String,
        durationSec: Long = 200,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/${extractVid(url)}/hqdefault.jpg", 480, 360)),
        uploaderName: String? = "Artist Name",
        uploaderUrl: String? = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
    ): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, StreamType.VIDEO_STREAM)
        item.uploaderName = uploaderName
        item.uploaderUrl = uploaderUrl
        item.duration = durationSec
        item.thumbnails = thumbs
        return item
    }

    private fun albumItem(
        url: String,
        name: String,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/albumThumb/hqdefault.jpg")),
    ): PlaylistInfoItem {
        val item = PlaylistInfoItem(0, url, name)
        item.uploaderName = "Artist"
        item.uploaderUrl = "https://www.youtube.com/channel/UC1234567890ABCDEF123456"
        item.streamCount = 10
        item.thumbnails = thumbs
        return item
    }

    private fun extractVid(url: String): String {
        Regex("[?&]v=([^&]+)").find(url)?.let { return it.groupValues[1] }
        val last = url.substringAfterLast("/").substringBefore("?").trim()
        return last.ifBlank { "testVid1234" }
    }

    // -------------------------------------------------------------------------
    // Without discography => unavailable (not empty-as-success)
    // -------------------------------------------------------------------------

    @Test
    fun `without discography reports unavailable not empty-as-success`() {
        val artist = ArtistMappers.toArtist(
            artistId = "UC1234567890ABCDEF123456",
            rawName = "Test Artist",
            avatarImages = listOf(image("https://yt3.ggpht.com/ytc/ABC=s88-c-k-c0x00ffffff-no-rj", 88, 88)),
            topSongItems = listOf(
                topSong("https://www.youtube.com/watch?v=song11111111", "Top One"),
                topSong("https://www.youtube.com/watch?v=song22222222", "Top Two"),
            ),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertFalse("albums should be unavailable when discography payload absent", artist.albumsAvailable)
        assertFalse(artist.singlesAvailable)
        assertTrue(artist.albums.isEmpty())
        assertTrue(artist.singles.isEmpty())
        // Must be distinguishable from available-with-empty: available flag is false, not true+empty
        assertEquals(false, artist.albumsAvailable)
        assertEquals(0, artist.albums.size)
        // top songs still present and ordered
        assertEquals(2, artist.topSongs.size)
        assertEquals("song11111111", artist.topSongs[0].id.value)
        assertEquals("song22222222", artist.topSongs[1].id.value)
    }

    @Test
    fun `with discography reports available and preserves order`() {
        val albums = listOf(
            albumItem("https://music.youtube.com/playlist?list=OLAK5uy_album1", "Album One"),
            albumItem("https://music.youtube.com/playlist?list=OLAK5uy_album2", "Album Two"),
        )
        val singles = listOf(
            albumItem("https://music.youtube.com/playlist?list=OLAK5uy_single1", "Single One"),
        )
        val artist = ArtistMappers.toArtist(
            artistId = "UCAAAAAAAAAAAAAAAAAAAAAAAA",
            rawName = "Artist With Discography",
            avatarImages = emptyList(),
            topSongItems = listOf(topSong("https://www.youtube.com/watch?v=top99999999", "Hit")),
            albumItems = albums,
            singleItems = singles,
        )
        assertNotNull(artist)
        artist!!
        assertTrue(artist.albumsAvailable)
        assertTrue(artist.singlesAvailable)
        assertEquals(2, artist.albums.size)
        assertEquals("OLAK5uy_album1", artist.albums[0].id.value)
        assertEquals("OLAK5uy_album2", artist.albums[1].id.value)
        assertEquals(1, artist.singles.size)
        assertEquals("OLAK5uy_single1", artist.singles[0].id.value)
        // IDs unique non-blank
        val allIds = (artist.albums + artist.singles).map { it.id.value }
        assertEquals(allIds.size, allIds.distinct().size)
        assertTrue(allIds.all { it.isNotBlank() })
    }

    @Test
    fun `available with empty discography lists still marks available`() {
        val artist = ArtistMappers.toArtist(
            artistId = "UCBBBBBBBBBBBBBBBBBBBBBBBB",
            rawName = "Empty Discography But Available",
            avatarImages = emptyList(),
            topSongItems = emptyList(),
            albumItems = emptyList(),
            singleItems = emptyList(),
        )
        assertNotNull(artist)
        artist!!
        assertTrue(artist.albumsAvailable)
        assertTrue(artist.singlesAvailable)
        assertTrue(artist.albums.isEmpty())
        assertTrue(artist.singles.isEmpty())
        // Distinguishes from null (unavailable) by flag true
    }

    // -------------------------------------------------------------------------
    // Top songs playable-typed and ordered
    // -------------------------------------------------------------------------

    @Test
    fun `topSongs ordered and playable-typed`() {
        val t1 = topSong("https://www.youtube.com/watch?v=track1111111", "Track One", durationSec = 180)
        val t2 = topSong("https://www.youtube.com/watch?v=track2222222", "Track Two", durationSec = 200)
        val t3 = topSong("https://www.youtube.com/watch?v=track3333333", "Track Three", durationSec = 210)
        val artist = ArtistMappers.toArtist(
            artistId = "UC_order_test",
            rawName = "Ordered Artist",
            avatarImages = emptyList(),
            topSongItems = listOf(t1, t2, t3),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertEquals(3, artist.topSongs.size)
        assertEquals("track1111111", artist.topSongs[0].id.value)
        assertEquals("track2222222", artist.topSongs[1].id.value)
        assertEquals("track3333333", artist.topSongs[2].id.value)
        assertEquals("Track One", artist.topSongs[0].title)
        assertEquals("Track Two", artist.topSongs[1].title)
        assertEquals("Track Three", artist.topSongs[2].title)
        // Playable: duration ms conversion and artwork present
        assertEquals(180_000L, artist.topSongs[0].duration.millis)
        assertEquals(200_000L, artist.topSongs[1].duration.millis)
        assertTrue(artist.topSongs.all { it.artwork != null })
        assertTrue(artist.topSongs.all { it.id.value.isNotBlank() })
        // Order preserved
        val ids = artist.topSongs.map { it.id.value }
        assertEquals(listOf("track1111111", "track2222222", "track3333333"), ids)
    }

    @Test
    fun `blank-id topSongs dropped siblings survive`() {
        val good1 = topSong("https://www.youtube.com/watch?v=good11111111", "Good 1")
        val bad = topSong("", "Bad Blank URL")
        val good2 = topSong("https://www.youtube.com/watch?v=good22222222", "Good 2")
        val artist = ArtistMappers.toArtist(
            artistId = "UC_blank_top_test",
            rawName = "Blank Dropped",
            avatarImages = emptyList(),
            topSongItems = listOf(good1, bad, good2),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertEquals(2, artist.topSongs.size)
        assertTrue(artist.topSongs.all { it.id.value.startsWith("good") })
        assertFalse(artist.topSongs.any { it.title.contains("Bad") })
    }

    @Test
    fun `blank album ids dropped within available discography`() {
        val good = albumItem("https://music.youtube.com/playlist?list=OLAKgood123", "Good Album")
        val bad = albumItem("", "Bad Blank")
        val artist = ArtistMappers.toArtist(
            artistId = "UC_blank_album_test",
            rawName = "Blank Album Dropped",
            avatarImages = emptyList(),
            topSongItems = emptyList(),
            albumItems = listOf(good, bad, albumItem("https://music.youtube.com/playlist?list=OLAKgood456", "Good 2")),
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertTrue(artist.albumsAvailable)
        assertEquals(2, artist.albums.size)
        assertTrue(artist.albums.all { it.id.value.startsWith("OLAKgood") })
    }

    // -------------------------------------------------------------------------
    // Circular-image ArtworkRef + title sanitization
    // -------------------------------------------------------------------------

    @Test
    fun `avatar artwork circular chain normalized ggpht size descending`() {
        val avatars = listOf(
            image("https://yt3.ggpht.com/ytc/ABC=s88-c-k-c0x00ffffff-no-rj", 88, 88),
            image("https://yt3.ggpht.com/ytc/ABC=s176-c-k-c0x00ffffff-no-rj", 176, 176),
        )
        val artist = ArtistMappers.toArtist(
            artistId = "UC_avatar_test",
            rawName = "Avatar Artist",
            avatarImages = avatars,
            topSongItems = emptyList(),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist!!.artwork)
        val art = artist.artwork!!
        // Should rewrite to descending 1080/720/544 chain (or at minimum canonical is largest)
        assertTrue(art.candidates.any { it.contains("=s1080") } || art.canonicalUrl.contains("s176"))
        assertEquals(art.cacheKey, art.canonicalUrl)
        assertTrue(art.candidates.first() == art.canonicalUrl)
    }

    @Test
    fun `per-track artwork chain normalized ytimg and fallback synthetic`() {
        val withThumb = topSong(
            url = "https://www.youtube.com/watch?v=vidWithThumb1",
            name = "With Thumb",
            thumbs = listOf(image("https://i.ytimg.com/vi/vidWithThumb1/maxresdefault.jpg", 1280, 720)),
        )
        val withoutThumb = topSong(
            url = "https://www.youtube.com/watch?v=vidNoThumb999",
            name = "No Thumb",
            thumbs = emptyList(),
        )
        val artist = ArtistMappers.toArtist(
            artistId = "UC_track_artwork",
            rawName = "Track Artwork",
            avatarImages = emptyList(),
            topSongItems = listOf(withThumb, withoutThumb),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertEquals(2, artist.topSongs.size)
        val t0Art = artist.topSongs[0].artwork
        assertNotNull(t0Art)
        assertTrue(t0Art!!.candidates.any { it.contains("maxresdefault") })
        val t1Art = artist.topSongs[1].artwork
        assertNotNull(t1Art)
        assertTrue(t1Art!!.canonicalUrl.contains("vidNoThumb999"))
        assertEquals(4, t1Art.candidates.size)
    }

    @Test
    fun `artist title sanitization raw preserved`() {
        val artist = ArtistMappers.toArtist(
            artistId = "UC_sanitize_test",
            rawName = "  My   Artist  Name  ",
            avatarImages = emptyList(),
            topSongItems = emptyList(),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        artist!!
        assertEquals("My Artist Name", artist.name)
        assertEquals("  My   Artist  Name  ", artist.rawName)
    }

    @Test
    fun `blank artist id returns null`() {
        val result = ArtistMappers.toArtist(
            artistId = "   ",
            rawName = "Should Fail",
            avatarImages = emptyList(),
            topSongItems = emptyList(),
            albumItems = null,
            singleItems = null,
        )
        assertNull(result)
    }

    @Test
    fun `topSongs duration conversion seconds to ms`() {
        val item = topSong("https://www.youtube.com/watch?v=dur123456789", "Dur Track", durationSec = 212)
        val artist = ArtistMappers.toArtist(
            artistId = "UC_dur_test",
            rawName = "Dur Artist",
            avatarImages = emptyList(),
            topSongItems = listOf(item),
            albumItems = null,
            singleItems = null,
        )
        assertNotNull(artist)
        assertEquals(212_000L, artist!!.topSongs.first().duration.millis)
    }
}

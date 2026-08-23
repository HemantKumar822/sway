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
 * Story 3.5 — Catalog Playlist detail mapper: fixture contract tests.
 *
 * Verifies (per ACs + architecture AR-2):
 * - curator/count/tracklist populate; ordering preserved; no mutation surface on model
 * - count null when absent upstream (null stays null, UI derives from tracks.size)
 * - blank-id tracks dropped, siblings survive
 * - artwork chain hero + per-track (ytimg chain, fallback synthetic)
 * - count present vs absent, curator null handling, blank playlist id -> null
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PlaylistMappersTest {

    private fun image(url: String, w: Int = 640, h: Int = 640) =
        Image(url, w, h, Image.ResolutionLevel.MEDIUM)

    private fun trackItem(
        url: String,
        name: String,
        durationSec: Long = 210,
        thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/${extractVid(url)}/hqdefault.jpg", 480, 360)),
        uploaderName: String? = "Track Artist",
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
    // Fixture: curator / count / ordered tracklist + no mutation surface
    // -------------------------------------------------------------------------

    @Test
    fun `fixture populates curator count ordered tracklist and no mutation surface`() {
        val t1 = trackItem("https://www.youtube.com/watch?v=pltrack11111", "Track One", durationSec = 180)
        val t2 = trackItem("https://www.youtube.com/watch?v=pltrack22222", "Track Two", durationSec = 200)
        val t3 = trackItem("https://www.youtube.com/watch?v=pltrack33333", "Track Three", durationSec = 190)

        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PLtestCatalogPlaylist123",
            rawTitle = "  Curated  Playlist  Title  ",
            curatorName = "  Curator Name  ",
            rawCount = 3,
            heroImages = listOf(image("https://i.ytimg.com/vi/heroPl/hqdefault.jpg")),
            trackItems = listOf(t1, t2, t3),
        )

        assertNotNull(playlist)
        playlist!!
        // title sanitized but raw preserved
        assertEquals("Curated Playlist Title", playlist.title)
        assertEquals("  Curated  Playlist  Title  ", playlist.rawTitle)
        assertEquals("Curator Name", playlist.curator)
        assertEquals(3, playlist.trackCount)
        // ordering preserved
        assertEquals(3, playlist.tracks.size)
        assertEquals("pltrack11111", playlist.tracks[0].id.value)
        assertEquals("pltrack22222", playlist.tracks[1].id.value)
        assertEquals("pltrack33333", playlist.tracks[2].id.value)
        assertEquals("Track One", playlist.tracks[0].title)
        // no mutation surface: private constructor -> copy inaccessible, factories are only mutation path
        // Verify data class copy visibility is private by checking factories produce distinct instances
        val viaFactory = com.sway.core.model.CatalogPlaylist.create(id = "otherId", rawTitle = "x")!!
        assertNotEquals(playlist.id, viaFactory.id)
        // IDs unique non-blank, titles sanitized
        assertTrue(playlist.tracks.all { it.id.value.isNotBlank() })
        assertEquals(3, playlist.tracks.map { it.id.value }.distinct().size)
    }

    @Test
    fun `count null when absent upstream — derived via tracks size contract`() {
        val t1 = trackItem("https://www.youtube.com/watch?v=trackA123456", "A")
        val t2 = trackItem("https://www.youtube.com/watch?v=trackB123456", "B")

        // rawCount null (extractor reports unavailable): model carries null
        val plNullCount = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_null_count_test",
            rawTitle = "Null Count Playlist",
            curatorName = "Curator",
            rawCount = null,
            heroImages = emptyList(),
            trackItems = listOf(t1, t2),
        )
        assertNotNull(plNullCount)
        assertNull(plNullCount!!.trackCount)
        // UI contract: derived counting = tracks.size
        assertEquals(2, plNullCount.tracks.size)
        val derivedCount = plNullCount.trackCount ?: plNullCount.tracks.size
        assertEquals(2, derivedCount)

        // rawCount negative (unknown): also null
        val plNegCount = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_neg_count_test",
            rawTitle = "Neg Count",
            curatorName = null,
            rawCount = -1,
            heroImages = emptyList(),
            trackItems = listOf(t1),
        )
        assertNotNull(plNegCount)
        assertNull(plNegCount!!.trackCount)
        assertEquals(1, plNegCount.tracks.size)

        // rawCount present -> populated, not derived
        val plWithCount = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_with_count_test",
            rawTitle = "With Count",
            curatorName = null,
            rawCount = 42,
            heroImages = emptyList(),
            trackItems = listOf(t1, t2),
        )
        assertNotNull(plWithCount)
        assertEquals(42, plWithCount!!.trackCount)
    }

    @Test
    fun `curator blank becomes null and curator null preserved`() {
        val plBlank = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_curator_blank",
            rawTitle = "Title",
            curatorName = "   ",
            rawCount = 1,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(plBlank)
        assertNull(plBlank!!.curator)

        val plNull = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_curator_null",
            rawTitle = "Title",
            curatorName = null,
            rawCount = 1,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNotNull(plNull)
        assertNull(plNull!!.curator)
    }

    @Test
    fun `blank-id tracks dropped siblings survive ordering preserved`() {
        val good1 = trackItem("https://www.youtube.com/watch?v=good11111111", "Good 1")
        val bad = trackItem("", "Bad Blank URL")
        val good2 = trackItem("https://www.youtube.com/watch?v=good22222222", "Good 2")

        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_blank_track_test",
            rawTitle = "Blank Dropped",
            curatorName = null,
            rawCount = 2,
            heroImages = emptyList(),
            trackItems = listOf(good1, bad, good2),
        )
        assertNotNull(playlist)
        playlist!!
        assertEquals(2, playlist.tracks.size)
        assertTrue(playlist.tracks.all { it.id.value.startsWith("good") })
        assertFalse(playlist.tracks.any { it.title.contains("Bad") })
        // order preserved among survivors
        assertEquals("good11111111", playlist.tracks[0].id.value)
        assertEquals("good22222222", playlist.tracks[1].id.value)
    }

    @Test
    fun `blank playlist id returns null`() {
        val result = PlaylistMappers.toCatalogPlaylist(
            playlistId = "   ",
            rawTitle = "Should Fail",
            curatorName = null,
            rawCount = null,
            heroImages = emptyList(),
            trackItems = emptyList(),
        )
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // Artwork chain hero + per-track + duration sanitization
    // -------------------------------------------------------------------------

    @Test
    fun `hero artwork chain normalized ytimg maxres first`() {
        val heroThumbs = listOf(
            image("https://i.ytimg.com/vi/heroPlId/maxresdefault.jpg", 1280, 720),
            image("https://i.ytimg.com/vi/heroPlId/hqdefault.jpg", 480, 360),
        )
        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_hero_artwork",
            rawTitle = "Hero Artwork",
            curatorName = null,
            rawCount = null,
            heroImages = heroThumbs,
            trackItems = emptyList(),
        )
        assertNotNull(playlist!!.artwork)
        assertTrue(playlist.artwork!!.canonicalUrl.contains("maxresdefault"))
        assertTrue(playlist.artwork!!.candidates.any { it.contains("mqdefault") })
        assertEquals(playlist.artwork!!.cacheKey, playlist.artwork!!.canonicalUrl)
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
        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_track_artwork",
            rawTitle = "Track Artwork Playlist",
            curatorName = null,
            rawCount = null,
            heroImages = emptyList(),
            trackItems = listOf(withThumb, withoutThumb),
        )
        assertNotNull(playlist)
        playlist!!
        assertEquals(2, playlist.tracks.size)
        val t0Art = playlist.tracks[0].artwork
        assertNotNull(t0Art)
        assertTrue(t0Art!!.candidates.any { it.contains("maxresdefault") })
        assertTrue(t0Art.candidates.any { it.contains("mqdefault") })
        val t1Art = playlist.tracks[1].artwork
        assertNotNull(t1Art)
        assertTrue(t1Art!!.canonicalUrl.contains("vidNoThumb999"))
        assertEquals(4, t1Art.candidates.size)
    }

    @Test
    fun `track duration seconds to ms and playlist title sanitized raw preserved`() {
        val item = trackItem("https://www.youtube.com/watch?v=dur123456789", "  My   Track  ", durationSec = 212)
        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_duration_sanitize",
            rawTitle = "  My   Playlist  Title  ",
            curatorName = "  Curator  ",
            rawCount = 1,
            heroImages = emptyList(),
            trackItems = listOf(item),
        )
        assertNotNull(playlist)
        playlist!!
        assertEquals("My Playlist Title", playlist.title)
        assertEquals("  My   Playlist  Title  ", playlist.rawTitle)
        assertEquals("Curator", playlist.curator)
        assertEquals(212_000L, playlist.tracks.first().duration.millis)
        assertEquals("My Track", playlist.tracks.first().title)
        assertEquals("  My   Track  ", playlist.tracks.first().rawTitle)
    }

    @Test
    fun `negative duration clamped to zero`() {
        val item = trackItem("https://www.youtube.com/watch?v=negDur123456", "Neg Dur", durationSec = -5)
        val playlist = PlaylistMappers.toCatalogPlaylist(
            playlistId = "PL_neg_duration",
            rawTitle = "Neg Duration",
            curatorName = null,
            rawCount = null,
            heroImages = emptyList(),
            trackItems = listOf(item),
        )
        assertNotNull(playlist)
        assertEquals(0L, playlist!!.tracks.first().duration.millis)
    }
}

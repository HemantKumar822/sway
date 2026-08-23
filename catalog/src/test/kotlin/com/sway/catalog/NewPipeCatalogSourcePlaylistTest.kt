package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NewPipeCatalogSourcePlaylistTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CatalogHttpClient.sharedBuilder().build()
        NewPipeInitializer.initForTest(client)
    }

    @After
    fun tearDown() {
        server.close()
        NewPipeInitializer.resetForTest()
    }

    private fun fakePlaylistExtractor(
        name: String,
        uploaderName: String? = "Curator Name",
        thumbs: List<Image> = listOf(Image("https://i.ytimg.com/vi/heroPl/hqdefault.jpg", 480, 360, Image.ResolutionLevel.MEDIUM)),
        streamCount: Long = 3,
        tracks: List<StreamInfoItem> = emptyList(),
        fetchThrows: Exception? = null,
        nameThrows: Exception? = null,
    ): PlaylistExtractor {
        val factory = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory.getInstance()
        val handler = factory.fromUrl("https://www.youtube.com/playlist?list=PLtestPlaylist123")
        return object : PlaylistExtractor(ServiceList.YouTube, handler) {
            override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
                fetchThrows?.let { throw it }
            }
            override fun getName(): String { nameThrows?.let { throw it }; return name }
            override fun getThumbnails(): List<Image> = thumbs
            override fun getUploaderUrl(): String = throw ParsingException("no uploaderUrl for playlist")
            override fun getUploaderName(): String = uploaderName ?: throw ParsingException("no uploaderName")
            override fun getUploaderAvatars(): List<Image> = emptyList()
            override fun isUploaderVerified(): Boolean = false
            override fun getStreamCount(): Long = streamCount
            override fun getDescription(): Description = Description("", Description.PLAIN_TEXT)
            override fun getInitialPage(): org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<StreamInfoItem> {
                return org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage(tracks, null, emptyList())
            }
            override fun getPage(page: org.schabi.newpipe.extractor.Page): org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage<StreamInfoItem> {
                return org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage(emptyList(), null, emptyList())
            }
            override fun getPlaylistType(): org.schabi.newpipe.extractor.playlist.PlaylistInfo.PlaylistType {
                return org.schabi.newpipe.extractor.playlist.PlaylistInfo.PlaylistType.NORMAL
            }
        }
    }

    private fun track(url: String, name: String, durationSec: Long = 200): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, StreamType.VIDEO_STREAM)
        item.uploaderName = "Artist"
        item.uploaderUrl = "https://www.youtube.com/channel/UCtest1234567890123456"
        item.duration = durationSec
        item.thumbnails = listOf(Image("https://i.ytimg.com/vi/${url.substringAfter("v=")}/hqdefault.jpg", 480, 360, Image.ResolutionLevel.MEDIUM))
        return item
    }

    @Test
    fun `playlist success maps curator count ordering hero and per-track artwork`() {
        val t1 = track("https://www.youtube.com/watch?v=pltrack111111", "First")
        val t2 = track("https://www.youtube.com/watch?v=pltrack222222", "Second")
        val t3 = track("https://www.youtube.com/watch?v=pltrack333333", "Third")
        val extractor = fakePlaylistExtractor(
            name = "Curated Playlist Test",
            uploaderName = "Test Curator",
            streamCount = 3,
            tracks = listOf(t1, t2, t3),
        )
        val source = NewPipeCatalogSource(
            playlistExtractorFactory = { extractor },
        )
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PLtestPlaylist123")!!) }
        assertTrue(result is SwayResult.Success)
        val pl = (result as SwayResult.Success).data
        assertEquals("PLtestPlaylist123", pl.id.value)
        assertEquals("Curated Playlist Test", pl.title)
        assertEquals("Test Curator", pl.curator)
        assertEquals(3, pl.trackCount)
        assertNotNull(pl.artwork)
        assertEquals(3, pl.tracks.size)
        // ordering preserved
        assertEquals("pltrack111111", pl.tracks[0].id.value)
        assertEquals("pltrack222222", pl.tracks[1].id.value)
        assertEquals("pltrack333333", pl.tracks[2].id.value)
        assertTrue(pl.tracks.all { it.artwork != null })
        assertTrue(pl.tracks[0].artwork!!.candidates.any { it.contains("pltrack111111") })
        // no mutation surface: copy is private, verify factories are only path
        assertNotNull(com.sway.core.model.CatalogPlaylist.create(id = "other", rawTitle = "x"))
    }

    @Test
    fun `playlist count null when extractor reports negative — derived via tracks`() {
        val t1 = track("https://www.youtube.com/watch?v=trackNullCount1", "A")
        val t2 = track("https://www.youtube.com/watch?v=trackNullCount2", "B")
        val extractor = fakePlaylistExtractor(
            name = "Null Count Playlist",
            streamCount = -1,
            tracks = listOf(t1, t2),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_null_count_123")!!) } as SwayResult.Success
        assertNull(result.data.trackCount)
        // UI derives from tracks.size
        assertEquals(2, result.data.tracks.size)
        val derived = result.data.trackCount ?: result.data.tracks.size
        assertEquals(2, derived)
    }

    @Test
    fun `playlist blank-id tracks dropped`() {
        val good = track("https://www.youtube.com/watch?v=good11111111", "Good")
        val bad = track("", "Bad")
        val extractor = fakePlaylistExtractor(
            name = "Blank Drop Playlist",
            streamCount = 3,
            tracks = listOf(good, bad, good.copyUrl("https://www.youtube.com/watch?v=good22222222")),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_blank_drop_123")!!) } as SwayResult.Success
        assertEquals(2, result.data.tracks.size)
    }

    @Test
    fun `playlist curator absent maps to null clean omission`() {
        val extractor = fakePlaylistExtractor(
            name = "No Curator Playlist",
            uploaderName = null,
            streamCount = 1,
            tracks = listOf(track("https://www.youtube.com/watch?v=nocurator1234", "Solo")),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_no_curator_123")!!) } as SwayResult.Success
        assertNull(result.data.curator)
    }

    @Test
    fun `playlist fetch IOException offline maps to Offline`() {
        val extractor = fakePlaylistExtractor(
            name = "Offline",
            fetchThrows = IOException("Unable to resolve host offline.test"),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_offline_test_123")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Offline)
    }

    @Test
    fun `playlist parsing exception maps to Parse`() {
        val extractor = fakePlaylistExtractor(
            name = "Parse Fail",
            nameThrows = ParsingException("malformed name"),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_parse_test_123")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Parse)
    }

    @Test
    fun `playlist oversized IOException maps to UpstreamUnavailable`() {
        val extractor = fakePlaylistExtractor(
            name = "Oversized",
            fetchThrows = IOException("Response exceeds 10MB limit"),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.catalogPlaylist(SourceId.parse("PL_oversized_123")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.UpstreamUnavailable)
    }

    // helper to copy with new url
    private fun StreamInfoItem.copyUrl(newUrl: String): StreamInfoItem {
        val c = StreamInfoItem(this.serviceId, newUrl, this.name, this.streamType)
        c.uploaderName = this.uploaderName
        c.uploaderUrl = this.uploaderUrl
        c.duration = this.duration
        c.thumbnails = this.thumbnails
        return c
    }
}

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
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NewPipeCatalogSourceAlbumTest {

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
        uploaderName: String? = "Test Artist",
        uploaderUrl: String? = "https://www.youtube.com/channel/UC1234567890ABCDEF123456",
        description: String? = "2021 • Album",
        thumbs: List<Image> = listOf(Image("https://i.ytimg.com/vi/hero123/hqdefault.jpg", 480, 360, Image.ResolutionLevel.MEDIUM)),
        tracks: List<StreamInfoItem> = emptyList(),
        fetchThrows: Exception? = null,
        nameThrows: Exception? = null,
    ): PlaylistExtractor {
        val factory = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory.getInstance()
        val handler = factory.fromUrl("https://www.youtube.com/playlist?list=OLAK5uy_test123")
        return object : PlaylistExtractor(ServiceList.YouTube, handler) {
            override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
                fetchThrows?.let { throw it }
            }
            override fun getName(): String { nameThrows?.let { throw it }; return name }
            override fun getThumbnails(): List<Image> = thumbs
            override fun getUploaderUrl(): String = uploaderUrl ?: throw ParsingException("no uploaderUrl")
            override fun getUploaderName(): String = uploaderName ?: throw ParsingException("no uploaderName")
            override fun getUploaderAvatars(): List<Image> = emptyList()
            override fun isUploaderVerified(): Boolean = false
            override fun getStreamCount(): Long = tracks.size.toLong()
            override fun getDescription(): Description = Description(description ?: "", Description.PLAIN_TEXT)
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
    fun `album success maps year present and track order with hero and per-track artwork`() {
        val t1 = track("https://www.youtube.com/watch?v=track1111111", "First")
        val t2 = track("https://www.youtube.com/watch?v=track2222222", "Second")
        val t3 = track("https://www.youtube.com/watch?v=track3333333", "Third")
        val extractor = fakePlaylistExtractor(
            name = "Test Album",
            description = "2021 • 3 songs",
            tracks = listOf(t1, t2, t3),
        )
        val source = NewPipeCatalogSource(
            playlistExtractorFactory = { extractor },
        )
        val result = kotlinx.coroutines.runBlocking { source.album(SourceId.parse("OLAK5uy_test123")!!) }
        assertTrue(result is SwayResult.Success)
        val album = (result as SwayResult.Success).data
        assertEquals("OLAK5uy_test123", album.id.value)
        assertEquals("Test Album", album.title)
        assertEquals(2021, album.year)
        assertNotNull(album.artwork)
        assertEquals(3, album.tracks.size)
        assertEquals("track1111111", album.tracks[0].id.value)
        assertEquals("track2222222", album.tracks[1].id.value)
        assertEquals("track3333333", album.tracks[2].id.value)
        // per-track artwork present
        assertTrue(album.tracks.all { it.artwork != null })
        assertTrue(album.tracks[0].artwork!!.candidates.any { it.contains("track1111111") })
    }

    @Test
    fun `album year absent maps to null clean omission`() {
        val extractor = fakePlaylistExtractor(
            name = "No Year Album",
            description = "No year here — 10 songs",
            tracks = emptyList(),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.album(SourceId.parse("OLAK5uy_no_year")!!) }
        assertTrue(result is SwayResult.Success)
        assertNull((result as SwayResult.Success).data.year)
    }

    @Test
    fun `album blank-id tracks dropped`() {
        val good = track("https://www.youtube.com/watch?v=good11111111", "Good")
        val bad = track("", "Bad")
        val extractor = fakePlaylistExtractor(
            name = "Blank Drop",
            description = null,
            tracks = listOf(good, bad, good.copyUrl("https://www.youtube.com/watch?v=good22222222")),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.album(SourceId.parse("OLAK5uy_blank_drop")!!) } as SwayResult.Success
        assertEquals(2, result.data.tracks.size)
    }

    @Test
    fun `album fetch IOException offline maps to Offline`() {
        val extractor = fakePlaylistExtractor(
            name = "Offline",
            fetchThrows = IOException("Unable to resolve host offline.test"),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.album(SourceId.parse("OLAK5uy_offline")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Offline)
    }

    @Test
    fun `album parsing exception maps to Parse`() {
        val extractor = fakePlaylistExtractor(
            name = "Parse Fail",
            nameThrows = ParsingException("malformed name"),
        )
        val source = NewPipeCatalogSource(playlistExtractorFactory = { extractor })
        val result = kotlinx.coroutines.runBlocking { source.album(SourceId.parse("OLAK5uy_parse")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Parse)
    }

    // helper to copy with new url for blank-drop test
    private fun StreamInfoItem.copyUrl(newUrl: String): StreamInfoItem {
        val c = StreamInfoItem(this.serviceId, newUrl, this.name, this.streamType)
        c.uploaderName = this.uploaderName
        c.uploaderUrl = this.uploaderUrl
        c.duration = this.duration
        c.thumbnails = this.thumbnails
        return c
    }
}

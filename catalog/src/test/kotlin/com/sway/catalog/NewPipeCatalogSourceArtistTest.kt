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
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NewPipeCatalogSourceArtistTest {

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

    private fun image(url: String, w: Int = 88, h: Int = 88) = Image(url, w, h, Image.ResolutionLevel.MEDIUM)

    private fun streamItem(url: String, name: String, durationSec: Long = 200, thumbs: List<Image> = listOf(image("https://i.ytimg.com/vi/test/hqdefault.jpg", 480, 360))): StreamInfoItem {
        val item = StreamInfoItem(0, url, name, StreamType.VIDEO_STREAM)
        item.uploaderName = "Artist"
        item.uploaderUrl = "https://www.youtube.com/channel/UCtest1234567890123456"
        item.duration = durationSec
        item.thumbnails = thumbs
        return item
    }

    private fun playlistItem(url: String, name: String): PlaylistInfoItem {
        val item = PlaylistInfoItem(0, url, name)
        item.uploaderName = "Artist"
        item.uploaderUrl = "https://www.youtube.com/channel/UC1234567890ABCDEF123456"
        item.streamCount = 8
        item.thumbnails = listOf(image("https://i.ytimg.com/vi/x/hqdefault.jpg", 480, 360))
        return item
    }

    // Helpers to create fake ChannelExtractor with tabs

    private fun fakeChannelExtractor(
        name: String,
        avatarUrl: String = "https://yt3.ggpht.com/ytc/ABC=s88-c-k-c0x00ffffff-no-rj",
        tabsHandlers: List<ListLinkHandler> = emptyList(),
        fetchThrows: Exception? = null,
        nameThrows: Exception? = null,
    ): ChannelExtractor {
        val factory = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory.getInstance()
        val handler = factory.fromUrl("https://www.youtube.com/channel/UC1234567890ABCDEF123456")
        return object : ChannelExtractor(ServiceList.YouTube, handler) {
            override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
                fetchThrows?.let { throw it }
            }
            override fun getName(): String { nameThrows?.let { throw it }; return name }
            override fun getAvatars(): List<Image> = listOf(Image(avatarUrl, 88, 88, Image.ResolutionLevel.MEDIUM))
            override fun getBanners(): List<Image> = emptyList()
            override fun getFeedUrl(): String = ""
            override fun getSubscriberCount(): Long = 12345
            override fun getDescription(): String = "Test description"
            override fun getParentChannelName(): String = ""
            override fun getParentChannelUrl(): String = ""
            override fun getParentChannelAvatars(): List<Image> = emptyList()
            override fun isVerified(): Boolean = false
            override fun getTabs(): List<ListLinkHandler> = tabsHandlers
        }
    }

    private fun listLinkHandler(url: String): ListLinkHandler {
        // Use YoutubeChannelTabLinkHandlerFactory to create tab handlers where possible,
        // but for tests we can craft a generic handler via ChannelTab factory if URL matches expected pattern.
        // Fallback: craft via the factory fromUrl with a URL that it accepts, or build manually via ListLinkHandler.
        return try {
            val factory = org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelTabLinkHandlerFactory.getInstance()
            // Try known tab URLs that factory accepts, e.g., .../videos, .../playlists
            // If url contains "videos" use that suffix, else generic.
            val tabSuffix = when {
                url.contains("videos") -> "videos"
                url.contains("playlists") -> "playlists"
                url.contains("albums") -> "albums"
                url.contains("singles") -> "singles"
                else -> "videos"
            }
            factory.fromUrl("https://www.youtube.com/channel/UC1234567890ABCDEF123456/$tabSuffix")
                .let { h ->
                    // Replace url to our desired url for mapping heuristic (contains singles/albums)
                    // ListLinkHandler is data holder; we can reconstruct with our url
                    ListLinkHandler(h.originalUrl, url, h.id, h.contentFilters, h.sortFilter)
                }
        } catch (e: Exception) {
            // Fallback manual handler
            ListLinkHandler(url, url, "UC1234567890ABCDEF123456", emptyList(), "")
        }
    }

    private fun fakeTabExtractor(
        tabHandler: ListLinkHandler,
        items: List<org.schabi.newpipe.extractor.InfoItem>,
        fetchThrows: Exception? = null,
    ): ChannelTabExtractor {
        return object : ChannelTabExtractor(ServiceList.YouTube, tabHandler) {
            override fun onFetchPage(downloader: org.schabi.newpipe.extractor.downloader.Downloader) {
                fetchThrows?.let { throw it }
            }
            override fun getInitialPage(): ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.InfoItem> {
                return ListExtractor.InfoItemsPage(items, null, emptyList())
            }
            override fun getPage(page: org.schabi.newpipe.extractor.Page): ListExtractor.InfoItemsPage<org.schabi.newpipe.extractor.InfoItem> {
                return ListExtractor.InfoItemsPage(emptyList(), null, emptyList())
            }
        }
    }

    // -------------------------------------------------------------------------
    // Success: with discography unavailable (OQ-1 degraded)
    // -------------------------------------------------------------------------

    @Test
    fun `artist success without discography reports unavailable and topSongs ordered`() {
        val t1 = streamItem("https://www.youtube.com/watch?v=song11111111", "Top One", 180)
        val t2 = streamItem("https://www.youtube.com/watch?v=song22222222", "Top Two", 190)
        val t3 = streamItem("https://www.youtube.com/watch?v=song33333333", "Top Three", 200)
        // Tab handler for videos only; no album/single tabs
        val videosTab = listLinkHandler("https://www.youtube.com/channel/UC1234567890ABCDEF123456/videos")
        val videoTabExtractor = fakeTabExtractor(videosTab, listOf(t1, t2, t3))
        val channelExtractor = fakeChannelExtractor(
            name = "Degraded Artist",
            tabsHandlers = listOf(videosTab),
        )
        val tabMap = mapOf(videosTab.url to videoTabExtractor)
        val source = NewPipeCatalogSource(
            channelExtractorFactory = { channelExtractor },
            channelTabExtractorFactory = { handler ->
                tabMap[handler.url] ?: fakeTabExtractor(handler, emptyList())
            },
        )
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC1234567890ABCDEF123456")!!) }
        assertTrue(result is SwayResult.Success)
        val artist = (result as SwayResult.Success).data
        assertEquals("Degraded Artist", artist.name)
        assertNotNull(artist.artwork)
        assertEquals(3, artist.topSongs.size)
        assertEquals("song11111111", artist.topSongs[0].id.value)
        assertEquals("song22222222", artist.topSongs[1].id.value)
        assertEquals("song33333333", artist.topSongs[2].id.value)
        assertEquals(180_000L, artist.topSongs[0].duration.millis)
        // Degraded: albums/singles unavailable not empty-as-success
        assertFalse(artist.albumsAvailable)
        assertFalse(artist.singlesAvailable)
        assertTrue(artist.albums.isEmpty())
        assertTrue(artist.singles.isEmpty())
    }

    @Test
    fun `artist success with discography reports available and preserves order`() {
        val top1 = streamItem("https://www.youtube.com/watch?v=top11111111", "Hit 1")
        val top2 = streamItem("https://www.youtube.com/watch?v=top22222222", "Hit 2")
        val album1 = playlistItem("https://music.youtube.com/playlist?list=OLAK5uy_albumA", "Album A")
        val album2 = playlistItem("https://music.youtube.com/playlist?list=OLAK5uy_albumB", "Album B")
        val single1 = playlistItem("https://music.youtube.com/playlist?list=OLAK5uy_singleX", "Single X")

        val videosTab = listLinkHandler("https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAAAA/videos")
        val albumsTab = listLinkHandler("https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAAAA/albums")
        val singlesTab = listLinkHandler("https://www.youtube.com/channel/UCAAAAAAAAAAAAAAAAAAAAAAAA/singles")

        val channelExtractor = fakeChannelExtractor(
            name = "Full Artist",
            tabsHandlers = listOf(videosTab, albumsTab, singlesTab),
        )
        val tabMap = mapOf(
            videosTab.url to fakeTabExtractor(videosTab, listOf(top1, top2)),
            albumsTab.url to fakeTabExtractor(albumsTab, listOf(album1, album2)),
            singlesTab.url to fakeTabExtractor(singlesTab, listOf(single1)),
        )
        val source = NewPipeCatalogSource(
            channelExtractorFactory = { channelExtractor },
            channelTabExtractorFactory = { handler -> tabMap[handler.url] ?: fakeTabExtractor(handler, emptyList()) },
        )
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UCAAAAAAAAAAAAAAAAAAAAAAAA")!!) }
        assertTrue(result is SwayResult.Success)
        val artist = (result as SwayResult.Success).data
        assertEquals(2, artist.topSongs.size)
        assertEquals("top11111111", artist.topSongs[0].id.value)
        assertTrue(artist.albumsAvailable)
        assertTrue(artist.singlesAvailable)
        assertEquals(2, artist.albums.size)
        assertEquals("OLAK5uy_albumA", artist.albums[0].id.value)
        assertEquals("OLAK5uy_albumB", artist.albums[1].id.value)
        assertEquals(1, artist.singles.size)
        assertEquals("OLAK5uy_singleX", artist.singles[0].id.value)
    }

    @Test
    fun `artist blank-id tracks dropped via source`() {
        val good = streamItem("https://www.youtube.com/watch?v=good11111111", "Good")
        val bad = streamItem("", "Bad Blank")
        val videosTab = listLinkHandler("https://www.youtube.com/channel/UC1234567890ABCDEF123456/videos")
        val channelExtractor = fakeChannelExtractor(name = "Blank Drop Artist", tabsHandlers = listOf(videosTab))
        val source = NewPipeCatalogSource(
            channelExtractorFactory = { channelExtractor },
            channelTabExtractorFactory = { fakeTabExtractor(videosTab, listOf(good, bad, good.let {
                val c = StreamInfoItem(it.serviceId, "https://www.youtube.com/watch?v=good22222222", it.name, it.streamType)
                c.uploaderName = it.uploaderName; c.uploaderUrl = it.uploaderUrl; c.duration = it.duration; c.thumbnails = it.thumbnails; c
            })) },
        )
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC1234567890ABCDEF123456")!!) } as SwayResult.Success
        assertEquals(2, result.data.topSongs.size)
        assertTrue(result.data.topSongs.none { it.title.contains("Bad") })
    }

    @Test
    fun `artist no tabs reports unavailable degregaded`() {
        val channelExtractor = fakeChannelExtractor(name = "No Tabs Artist", tabsHandlers = emptyList())
        val source = NewPipeCatalogSource(channelExtractorFactory = { channelExtractor })
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_no_tabs_123456789012")!!) }
        assertTrue(result is SwayResult.Success)
        val artist = (result as SwayResult.Success).data
        assertFalse(artist.albumsAvailable)
        assertFalse(artist.singlesAvailable)
        assertTrue(artist.topSongs.isEmpty()) // no songs without tabs
        assertNotNull(artist.artwork)
    }

    @Test
    fun `artist fetch IOException offline maps to Offline`() {
        val channelExtractor = fakeChannelExtractor(name = "Offline Artist", fetchThrows = IOException("Unable to resolve host offline.test"))
        val source = NewPipeCatalogSource(channelExtractorFactory = { channelExtractor })
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_offline_test12345678")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Offline)
    }

    @Test
    fun `artist parsing exception maps to Parse`() {
        val channelExtractor = fakeChannelExtractor(name = "Parse Fail", nameThrows = ParsingException("malformed name"))
        val source = NewPipeCatalogSource(channelExtractorFactory = { channelExtractor })
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_parse_test1234567890")!!) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Parse)
    }

    @Test
    fun `artist ReCaptcha maps to RateLimited`() {
        val channelExtractor = fakeChannelExtractor(name = "RateLimit", fetchThrows = org.schabi.newpipe.extractor.exceptions.ReCaptchaException("429", "https://youtube.com"))
        val source = NewPipeCatalogSource(channelExtractorFactory = { channelExtractor })
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_rate_test123456789012")!!) }
        assertTrue((result as SwayResult.Failure).error is SwayError.RateLimited)
    }

    @Test
    fun `artist tab fetch IOException propagates as Offline`() {
        val videosTab = listLinkHandler("https://www.youtube.com/channel/UC1234567890ABCDEF123456/videos")
        val channelExtractor = fakeChannelExtractor(name = "Tab Offline", tabsHandlers = listOf(videosTab))
        val source = NewPipeCatalogSource(
            channelExtractorFactory = { channelExtractor },
            channelTabExtractorFactory = { fakeTabExtractor(videosTab, emptyList(), fetchThrows = IOException("Unable to resolve host tab")) },
        )
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_tab_offline_1234567890")!!) }
        // Tab IOException currently re-thrown as per artistInternal: throws and maps to Offline at top level
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.Offline)
    }

    @Test
    fun `artist avatar artwork normalized circular`() {
        val avatar = "https://yt3.ggpht.com/ytc/IMG123=s176-c-k-c0x00ffffff-no-rj"
        val channelExtractor = fakeChannelExtractor(name = "Avatar Artist", avatarUrl = avatar, tabsHandlers = emptyList())
        val source = NewPipeCatalogSource(channelExtractorFactory = { channelExtractor })
        val result = kotlinx.coroutines.runBlocking { source.artist(SourceId.parse("UC_avatar_test1234567890")!!) } as SwayResult.Success
        assertNotNull(result.data.artwork)
        val art = result.data.artwork!!
        // circular portrait should still produce chain with ggpht size variants
        assertTrue(art.canonicalUrl.contains("ggpht") || art.canonicalUrl.contains("googleusercontent") || art.candidates.any { it.contains("=s1080") })
        assertEquals(art.cacheKey, art.canonicalUrl)
    }
}

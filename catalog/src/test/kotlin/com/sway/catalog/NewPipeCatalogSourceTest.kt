package com.sway.catalog

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.Album
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchExtractor
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class NewPipeCatalogSourceTest {

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

    private fun fakeExtractor(
        handler: SearchQueryHandler,
        fetchAction: (Downloader) -> Unit,
        initialPage: ListExtractor.InfoItemsPage<InfoItem>,
        nextPageFor: (Page) -> ListExtractor.InfoItemsPage<InfoItem> = { initialPage },
    ): SearchExtractor {
        val svc: StreamingService = ServiceList.YouTube
        return object : SearchExtractor(svc, handler) {
            override fun onFetchPage(downloader: Downloader) { fetchAction(downloader) }
            override fun getSearchSuggestion(): String = ""
            override fun isCorrectedSearch(): Boolean = false
            override fun getMetaInfo(): MutableList<org.schabi.newpipe.extractor.MetaInfo> = mutableListOf()
            override fun getInitialPage(): ListExtractor.InfoItemsPage<InfoItem> = initialPage
            override fun getPage(page: Page): ListExtractor.InfoItemsPage<InfoItem> = nextPageFor(page)
        }
    }

    private fun streamItem(url: String, name: String): InfoItem {
        val item = org.schabi.newpipe.extractor.stream.StreamInfoItem(0, url, name, org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM)
        item.uploaderName = "Artist"
        item.uploaderUrl = "https://www.youtube.com/channel/UCtest1234567890123456"
        item.duration = 200
        item.thumbnails = listOf(org.schabi.newpipe.extractor.Image("https://i.ytimg.com/vi/test123/hqdefault.jpg", 480, 360, org.schabi.newpipe.extractor.Image.ResolutionLevel.MEDIUM))
        return item
    }

    private fun <T> runBlockingTyped(block: suspend () -> SwayResult<PagedResult<T>>): SwayResult<PagedResult<T>> {
        var out: SwayResult<PagedResult<T>>? = null
        kotlinx.coroutines.runBlocking { out = block() }
        return out!!
    }

    @Test
    fun `searchSongs HTTP 429 maps to RateLimited via MockWebServer`() {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val mockUrl = server.url("/search").toString()
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { dl -> dl.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(mockUrl).build()) }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Song> { source.searchSongs("test query", null) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.RateLimited)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `searchAlbums 429 also maps to RateLimited`() {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val mockUrl = server.url("/searchAlbums").toString()
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { dl -> dl.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(mockUrl).build()) }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Album> { source.searchAlbums("album query", null) }
        assertTrue(result is SwayResult.Failure && (result as SwayResult.Failure).error is SwayError.RateLimited)
    }

    @Test
    fun `searchSongs malformed payload maps to Parse with shape info`() {
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { throw ParsingException("malformed json { not valid") }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Song> { source.searchSongs("bad payload", null) }
        assertTrue(result is SwayResult.Failure)
        val err = (result as SwayResult.Failure).error
        assertTrue(err is SwayError.Parse)
        assertNotNull((err as SwayError.Parse).shapeInfo)
        assertTrue(err.shapeInfo!!.contains("Parse"))
    }

    @Test
    fun `searchArtists malformed via MockWebServer body not json maps to Parse`() {
        server.enqueue(MockResponse.Builder().code(200).body("not json {{{{{").build())
        val mockUrl = server.url("/malformed").toString()
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { dl ->
                val resp = dl.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(mockUrl).build())
                if (resp.responseBody().contains("not json")) throw ParsingException("shape: unexpected body ${resp.responseBody().take(20)}")
            }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Artist> { source.searchArtists("malformed", null) }
        assertTrue(result is SwayResult.Failure && (result as SwayResult.Failure).error is SwayError.Parse)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `searchSongs oversized body maps to UpstreamUnavailable via IOException`() {
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { throw IOException("response body exceeds 10MB limit (12345)") }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Song> { source.searchSongs("oversized", null) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.UpstreamUnavailable)
    }

    @Test
    fun `oversized via MockWebServer large body maps to UpstreamUnavailable`() {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(100)).build())
        val mockUrl = server.url("/oversizedViaServer").toString()
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { dl ->
                dl.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(mockUrl).build())
                throw IOException("response body exceeds 10MB limit (simulated after server hit)")
            }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Song> { source.searchSongs("oversized2", null) }
        assertTrue((result as SwayResult.Failure).error is SwayError.UpstreamUnavailable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `blank-id items dropped with siblings survive via search`() {
        val good1 = streamItem("https://www.youtube.com/watch?v=good11111111", "Good 1")
        val bad = streamItem("", "Bad Blank")
        val good2 = streamItem("https://www.youtube.com/watch?v=good22222222", "Good 2")
        val page = ListExtractor.InfoItemsPage<InfoItem>(listOf(good1, bad, good2), null, emptyList())
        val source = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = page) })
        val result = runBlockingTyped<Song> { source.searchSongs("blank test", null) }
        assertTrue(result is SwayResult.Success)
        val paged = (result as SwayResult.Success).data
        assertEquals(2, paged.size)
        assertTrue(paged.items.all { it.id.value.startsWith("good") })
        assertFalse(paged.items.any { it.title.contains("Bad") })
    }

    @Test
    fun `each search type drops blank-id independently`() {
        val goodAlbum = org.schabi.newpipe.extractor.playlist.PlaylistInfoItem(0, "https://music.youtube.com/playlist?list=OLAKgood", "Good Album").apply {
            uploaderName = "Artist"; streamCount = 10; thumbnails = listOf(org.schabi.newpipe.extractor.Image("https://i.ytimg.com/vi/x/hqdefault.jpg", 480, 360, org.schabi.newpipe.extractor.Image.ResolutionLevel.MEDIUM))
        }
        val badAlbum = org.schabi.newpipe.extractor.playlist.PlaylistInfoItem(0, "", "Bad").apply { thumbnails = emptyList() }
        val albumPage = ListExtractor.InfoItemsPage<InfoItem>(listOf(goodAlbum, badAlbum), null, emptyList())
        val albumSource = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = albumPage) })
        val albumRes = runBlockingTyped<Album> { albumSource.searchAlbums("test", null) } as SwayResult.Success
        assertEquals(1, albumRes.data.size)

        val goodArtist = org.schabi.newpipe.extractor.channel.ChannelInfoItem(0, "https://www.youtube.com/channel/UCgood1234567890123456", "Good").apply {
            thumbnails = listOf(org.schabi.newpipe.extractor.Image("https://yt3.ggpht.com/abc=s88", 88, 88, org.schabi.newpipe.extractor.Image.ResolutionLevel.LOW))
        }
        val badArtist = org.schabi.newpipe.extractor.channel.ChannelInfoItem(0, "   ", "Bad").apply { thumbnails = emptyList() }
        val artistPage = ListExtractor.InfoItemsPage<InfoItem>(listOf(goodArtist, badArtist), null, emptyList())
        val artistSource = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = artistPage) })
        val artistRes = runBlockingTyped<Artist> { artistSource.searchArtists("test", null) } as SwayResult.Success
        assertEquals(1, artistRes.data.size)

        val goodPl = org.schabi.newpipe.extractor.playlist.PlaylistInfoItem(0, "https://www.youtube.com/playlist?list=PLgood", "Good PL").apply {
            uploaderName = "Curator"; streamCount = 5; thumbnails = listOf(org.schabi.newpipe.extractor.Image("https://i.ytimg.com/vi/x/hqdefault.jpg", 480, 360, org.schabi.newpipe.extractor.Image.ResolutionLevel.MEDIUM))
        }
        val badPl = org.schabi.newpipe.extractor.playlist.PlaylistInfoItem(0, "   ", "Bad PL").apply { thumbnails = emptyList() }
        val plPage = ListExtractor.InfoItemsPage<InfoItem>(listOf(goodPl, badPl), null, emptyList())
        val plSource = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = plPage) })
        val plRes = runBlockingTyped<CatalogPlaylist> { plSource.searchCatalogPlaylists("test", null) } as SwayResult.Success
        assertEquals(1, plRes.data.size)
    }

    @Test
    fun `page tokens round-trip through search pagination`() {
        val firstPageNext = Page("https://www.youtube.com/results?search_query=test", "contNext123")
        val firstItems = listOf(streamItem("https://www.youtube.com/watch?v=first1111111", "First"))
        val firstInfoPage = ListExtractor.InfoItemsPage<InfoItem>(firstItems, firstPageNext, emptyList())
        val secondItems = listOf(streamItem("https://www.youtube.com/watch?v=second222222", "Second"))
        val secondInfoPage = ListExtractor.InfoItemsPage<InfoItem>(secondItems, null, emptyList())
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = {}, initialPage = firstInfoPage, nextPageFor = { page ->
                assertEquals("contNext123", page.id)
                secondInfoPage
            })
        })
        val firstResult = runBlockingTyped<Song> { source.searchSongs("paginate", null) } as SwayResult.Success
        assertEquals(1, firstResult.data.size)
        assertNotNull(firstResult.data.nextPageToken)
        assertTrue(firstResult.data.hasMore)
        val secondResult = runBlockingTyped<Song> { source.searchSongs("paginate", firstResult.data.nextPageToken) } as SwayResult.Success
        assertEquals(1, secondResult.data.size)
        assertNull(secondResult.data.nextPageToken)
        assertFalse(secondResult.data.hasMore)
        assertEquals("second222222", secondResult.data.items.first().id.value)
    }

    @Test
    fun `invalid page token maps to Parse`() {
        val source = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = ListExtractor.InfoItemsPage.emptyPage()) })
        val result = runBlockingTyped<Song> { source.searchSongs("test", "not-a-valid-token!!!") }
        assertTrue(result is SwayResult.Failure && (result as SwayResult.Failure).error is SwayError.Parse)
    }

    @Test
    fun `never returns empty list as failure - 429 is typed Failure not empty`() {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val mockUrl = server.url("/rateNeverEmpty").toString()
        val source = NewPipeCatalogSource(extractorFactory = { h ->
            fakeExtractor(h, fetchAction = { dl -> dl.execute(org.schabi.newpipe.extractor.downloader.Request.newBuilder().get(mockUrl).build()) }, initialPage = ListExtractor.InfoItemsPage.emptyPage())
        })
        val result = runBlockingTyped<Song> { source.searchSongs("x", null) }
        assertTrue(result is SwayResult.Failure)
        assertTrue((result as SwayResult.Failure).error is SwayError.RateLimited)
        assertFalse(result is SwayResult.Success<*>)
    }

    @Test
    fun `blank query returns Success empty without network`() {
        val source = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = { fail("should not fetch for blank query") }, initialPage = ListExtractor.InfoItemsPage.emptyPage()) })
        val result = runBlockingTyped<Song> { source.searchSongs("   ", null) } as SwayResult.Success
        assertTrue(result.data.isEmpty)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `searchSongs nextPageToken is opaque and non-blank when more pages`() {
        val next = Page("https://www.youtube.com/youtubei/v1/search", "contOpq")
        val page = ListExtractor.InfoItemsPage<InfoItem>(listOf(streamItem("https://www.youtube.com/watch?v=opaque123456", "Op")), next, emptyList())
        val source = NewPipeCatalogSource(extractorFactory = { h -> fakeExtractor(h, fetchAction = {}, initialPage = page) })
        val res = runBlockingTyped<Song> { source.searchSongs("opaque", null) } as SwayResult.Success
        assertNotNull(res.data.nextPageToken)
        assertTrue(res.data.nextPageToken!!.isNotBlank())
        // Ensure token does not leak raw url verbatim? It is base64, so not containing youtube.com directly
        assertFalse(res.data.nextPageToken!!.contains("youtube.com"))
    }
}

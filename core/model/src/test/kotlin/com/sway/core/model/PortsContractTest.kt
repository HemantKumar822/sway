package com.sway.core.model

import com.sway.core.model.fake.FakeCatalogSource
import com.sway.core.model.fake.FakeStreamResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Story 2.4 — Ports & playback vocabulary contract.
 *
 * Verifies ACs:
 * - Fake implementations compile against the ports ([CatalogSource], [StreamResolver])
 *   and consumers exercise them through typed [SwayResult]/value objects only —
 *   no method returns bare lists/strings where SwayResult/value objects are specified.
 * - [AudioRequest](Quality, forceRefresh), [ResolvedAudio] fields,
 *   [Quality] AUTO/LOW/MEDIUM/HIGH, [QueueSnapshot]/[QueueItem] model exist.
 * - [PagedResult] page-token contract and [SwayError] category injection.
 * - `:core:model` zero Android imports (structural; see ImportBanTest for file scan).
 *
 * KDoc on each port cites its governing AD rules (AR-2/AR-6, AD-6/AD-7).
 * Pure Kotlin — zero Android imports.
 */
class PortsContractTest {

    // ---- CatalogSource shape: never bare lists, always SwayResult<PagedResult<T>> ----

    @Test fun `CatalogSource search methods return SwayResult wrapping PagedResult not bare lists`() = runTest {
        val fake = FakeCatalogSource()

        // Stub one song page with continuation.
        val song = Song.create(id = "s1", rawTitle = "Song One")!!
        fake.stubSongsPage(songs = listOf(song), nextToken = "tok2")

        val songsResult: SwayResult<PagedResult<Song>> = fake.searchSongs("hello", null)
        assertTrue(songsResult.isSuccess)
        val page = (songsResult as SwayResult.Success).data
        assertEquals(1, page.size)
        assertEquals("s1", page.items.first().id.value)
        assertEquals("tok2", page.normalizedNextPageToken)
        assertTrue(page.hasMore)
        // End-of-results: nextPageToken null.
        val emptyPage: SwayResult<PagedResult<Song>> = FakeCatalogSource().searchSongs("q", null)
        assertTrue(emptyPage.isSuccess)
        assertNull((emptyPage as SwayResult.Success).data.normalizedNextPageToken)
        assertFalse(emptyPage.getOrNull()!!.hasMore)

        // Each search group compiles to the typed generic — proves no bare List returns.
        val albumsResult: SwayResult<PagedResult<Album>> = fake.searchAlbums("hello", null)
        assertTrue(albumsResult.isSuccess)
        val artistsResult: SwayResult<PagedResult<Artist>> = fake.searchArtists("hello", null)
        assertTrue(artistsResult.isSuccess)
        val cplResult: SwayResult<PagedResult<CatalogPlaylist>> = fake.searchCatalogPlaylists("hello", null)
        assertTrue(cplResult.isSuccess)

        // Pagination token plumbing: second page via token.
        fake.songsBehavior = { _, token ->
            if (token == "tok2") SwayResult.Success(PagedResult.of(listOf(Song.create(id = "s2", rawTitle = "Two")!!), null))
            else SwayResult.Success(PagedResult.empty())
        }
        val second: SwayResult<PagedResult<Song>> = fake.searchSongs("hello", "tok2")
        assertEquals("s2", (second as SwayResult.Success).data.items.first().id.value)
        assertNull(second.data.normalizedNextPageToken)
    }

    @Test fun `CatalogSource detail methods return SwayResult with typed models`() = runTest {
        val album = Album.create(id = "a1", rawTitle = "Album One", year = 2020)!!
        val artist = Artist.create(id = "ar1", rawName = "Artist")!!
        val cpl = CatalogPlaylist.create(id = "pl1", rawTitle = "Chill")!!
        val fake = FakeCatalogSource(
            albumBehavior = { id -> if (id.value == "a1") SwayResult.Success(album) else SwayResult.Failure(SwayError.ContentNotFound) },
            artistBehavior = { id -> if (id.value == "ar1") SwayResult.Success(artist) else SwayResult.Failure(SwayError.ContentNotFound) },
            catalogPlaylistBehavior = { id -> if (id.value == "pl1") SwayResult.Success(cpl) else SwayResult.Failure(SwayError.ContentNotFound) },
        )

        val a: SwayResult<Album> = fake.album(SourceId("a1"))
        assertEquals("Album One", (a as SwayResult.Success).data.title)
        val missingAlbum: SwayResult<Album> = fake.album(SourceId("missing"))
        assertTrue(missingAlbum.isFailure)
        assertEquals(SwayError.ContentNotFound, (missingAlbum as SwayResult.Failure).error)

        val ar: SwayResult<Artist> = fake.artist(SourceId("ar1"))
        assertEquals("Artist", (ar as SwayResult.Success).data.name)
        val pl: SwayResult<CatalogPlaylist> = fake.catalogPlaylist(SourceId("pl1"))
        assertEquals("Chill", (pl as SwayResult.Success).data.title)
    }

    @Test fun `CatalogSource propagates typed SwayError categories without bare empty`() = runTest {
        // NFR-2: failures travel as SwayResult.Failure, never bare emptyList/token tricks.
        val errors = listOf<SwayError>(
            SwayError.Offline,
            SwayError.RateLimited,
            SwayError.UpstreamUnavailable,
            SwayError.Parse("shape"),
            SwayError.ContentNotFound,
            SwayError.Storage,
            SwayError.Unknown(RuntimeException("x")),
        )
        for (error in errors) {
            val fake = FakeCatalogSource()
            fake.injectSearchFailure(error)
            val r: SwayResult<PagedResult<Song>> = fake.searchSongs("q", null)
            val failure = r as SwayResult.Failure
            assertEquals(error::class, failure.error::class)
            // Exhaustive when without else compiles (proof that every category maps).
            val label = when (failure.error) {
                is SwayError.Offline -> "offline"
                is SwayError.RateLimited -> "rate"
                is SwayError.UpstreamUnavailable -> "upstream"
                is SwayError.Parse -> "parse"
                is SwayError.ContentNotFound -> "gone"
                is SwayError.Storage -> "storage"
                is SwayError.Unknown -> "unknown"
            }
            assertNotNull(label)
        }
    }

    // ---- StreamResolver vocabulary: AudioRequest, Quality, ResolvedAudio ----

    @Test fun `Quality enum has exactly AUTO LOW MEDIUM HIGH`() {
        val names = Quality.entries.map { it.name }
        assertEquals(listOf("AUTO", "LOW", "MEDIUM", "HIGH"), names)
        assertEquals(4, Quality.entries.size)
    }

    @Test fun `AudioRequest carries Quality and forceRefresh with AUTO default`() {
        val def = AudioRequest()
        assertEquals(Quality.AUTO, def.quality)
        assertFalse(def.forceRefresh)

        val highRefresh = AudioRequest(Quality.HIGH, forceRefresh = true)
        assertEquals(Quality.HIGH, highRefresh.quality)
        assertTrue(highRefresh.forceRefresh)

        assertEquals(AudioRequest(Quality.AUTO, false), AudioRequest.Default)
        assertEquals(AudioRequest(Quality.LOW, true), AudioRequest.refresh(Quality.LOW))
    }

    @Test fun `ResolvedAudio fields via StreamResolver resolveAudio`() = runTest {
        val fake = FakeStreamResolver()
        val id = SourceId("track1")
        val req = AudioRequest(Quality.MEDIUM, forceRefresh = false)
        val result: SwayResult<ResolvedAudio> = fake.resolveAudio(id, req)
        assertTrue(result.isSuccess)
        val audio = (result as SwayResult.Success).data
        assertTrue(audio.url.isNotBlank())
        assertTrue(audio.expiresAtEpochMs > System.currentTimeMillis())
        assertTrue(audio.bitrateKbps >= 0)
        assertTrue(audio.backendTag.isNotBlank())
        assertTrue(audio.renditionCacheKey.isNotBlank())
        assertEquals(ResolvedAudio.cacheKey(id, Quality.MEDIUM), audio.renditionCacheKey)
        // containerHint may be present.
        assertNotNull(audio.containerHint)
        // In-flight dedup is invisible — counter increments per visible call.
        assertEquals(1, fake.resolveCount)
        assertEquals(id, fake.resolvedIds.first())
    }

    @Test fun `StreamResolver invalidate purges cache`() = runTest {
        val fake = FakeStreamResolver()
        val id = SourceId("t1")
        fake.resolveAudio(id, AudioRequest())
        assertEquals(1, fake.resolveCount)
        fake.invalidate(id)
        assertEquals(1, fake.invalidateCount)
        assertEquals(id, fake.invalidatedIds.first())
        // Re-resolve after invalidate should count again.
        fake.resolveAudio(id, AudioRequest(Quality.HIGH))
        assertEquals(2, fake.resolveCount)
    }

    @Test fun `StreamResolver prefetchNext returns null silently on failure`() = runTest {
        // Success path
        val successFake = FakeStreamResolver(prefetchBehavior = { id, req ->
            FakeStreamResolver.fakeResolvedAudio(id, req.quality)
        })
        val id = SourceId("next1")
        val prefetched: ResolvedAudio? = successFake.prefetchNext(id, AudioRequest(Quality.LOW))
        assertNotNull(prefetched)
        assertEquals("next1", prefetched!!.renditionCacheKey.substringBefore(":"))

        // Failure path (behavior throws/returns null) — must be null silently, never SwayResult.
        val failingFake = FakeStreamResolver(prefetchBehavior = { _, _ -> throw RuntimeException("network") })
        val failed: ResolvedAudio? = failingFake.prefetchNext(id, AudioRequest())
        assertNull(failed)

        val nullFake = FakeStreamResolver(prefetchBehavior = { _, _ -> null })
        assertNull(nullFake.prefetchNext(id, AudioRequest()))

        // Verify the signature returns ResolvedAudio? without wrapping.
        val nullable: ResolvedAudio? = successFake.prefetchNext(id, AudioRequest())
        assertTrue(nullable == null || nullable is ResolvedAudio)
    }

    @Test fun `ResolvedAudio validation and cacheKey helper`() {
        val id = SourceId("abc")
        assertEquals("abc:AUTO", ResolvedAudio.cacheKey(id, Quality.AUTO))
        assertEquals("abc:HIGH", ResolvedAudio.cacheKey("abc", Quality.HIGH))
        // Blank URL or negative expiry must throw.
        try {
            ResolvedAudio("", 1L, 128, "mp4", "tag", "k")
            fail("must reject blank url")
        } catch (_: IllegalArgumentException) { }
        try {
            ResolvedAudio("https://x", -1L, 128, "mp4", "tag", "k")
            fail("must reject negative expiry")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `ResolvedAudio expiry helper respects margin`() {
        val now = 10_000L
        val expires = 15_000L
        val audio = ResolvedAudio("https://cdn/x?expire=99", expires, 128, "mp4", "tag", "k")
        assertFalse(audio.isExpiredAt(now))
        assertFalse(audio.isExpiredAt(now, marginMs = 2_000))
        assertTrue(audio.isExpiredAt(now, marginMs = 5_000))
        assertTrue(audio.isExpiredAt(expires))
    }

    // ---- PagedResult token normalization ----

    @Test fun `PagedResult normalizes blank tokens and exposes hasMore`() {
        val empty = PagedResult.empty<Song>()
        assertTrue(empty.isEmpty)
        assertFalse(empty.hasMore)
        assertNull(empty.normalizedNextPageToken)

        val withBlankToken = PagedResult.of(listOf(Song.create(id = "s1", rawTitle = "x")!!), nextPageToken = "   ")
        assertNull(withBlankToken.normalizedNextPageToken)
        assertFalse(withBlankToken.hasMore)

        val withToken = PagedResult.of(listOf(Song.create(id = "s1", rawTitle = "x")!!), nextPageToken = " nextTok ")
        assertEquals("nextTok", withToken.normalizedNextPageToken)
        assertTrue(withToken.hasMore)

        val single = PagedResult.singlePage(listOf(Song.create(id = "s1", rawTitle = "y")!!))
        assertNull(single.normalizedNextPageToken)
        assertEquals(1, single.size)
    }

    // ---- QueueSnapshot / QueueItem model ----

    @Test fun `QueueSnapshot and QueueItem model basics`() {
        val s1 = Song.create(id = "s1", rawTitle = "One")!!
        val s2 = Song.create(id = "s2", rawTitle = "Two")!!
        val s3 = Song.create(id = "s3", rawTitle = "Three")!!

        val item1 = QueueItem.of(s1)
        assertEquals(SourceId("s1"), item1.id)
        assertEquals(s1, item1.song)

        val snapshot = QueueSnapshot.fromSongs(listOf(s1, s2, s3))
        assertEquals(3, snapshot.size)
        assertFalse(snapshot.isEmpty)
        assertEquals(listOf(SourceId("s1"), SourceId("s2"), SourceId("s3")), snapshot.sourceIds())
        assertEquals(s2, snapshot.itemAt(1)!!.song)
        assertNull(snapshot.itemAt(99))

        val single = QueueSnapshot.single(s1)
        assertEquals(1, single.size)
        assertEquals("s1", single.items.first().id.value)

        assertTrue(QueueSnapshot.Empty.isEmpty)
        assertEquals(0, QueueSnapshot.Empty.size)
    }

    @Test fun `QueueItem id must equal song id`() {
        val s = Song.create(id = "s1", rawTitle = "x")!!
        try {
            QueueItem(SourceId("other"), s)
            fail("must reject mismatched id")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `QueueSnapshot is immutable copy semantics`() {
        val s1 = Song.create(id = "s1", rawTitle = "a")!!
        val list = mutableListOf(s1)
        val snap = QueueSnapshot.fromSongs(list)
        list.clear()
        assertEquals(1, snap.size) // snapshot defensively copied

        val s2 = Song.create(id = "s2", rawTitle = "b")!!
        val snap2 = QueueSnapshot.of(listOf(QueueItem.of(s2)))
        assertEquals(1, snap2.size)
    }

    // ---- Compile-time contract: no bare lists where SwayResult required ----

    @Test fun `consumers can only access catalog data via SwayResult`() = runTest {
        // This test proves that the only way to reach PagedResult is through Success wrapping.
        // Bare `List<Song>` returns do not compile — verified by exercising the fake via its
        // interface type (CatalogSource) where the only search signature returns SwayResult.
        val source: CatalogSource = FakeCatalogSource(
            songsBehavior = { _, _ -> SwayResult.Success(PagedResult.of(listOf(Song.create(id = "s9", rawTitle = "Q")!!))) }
        )
        val result = source.searchSongs("Q", null)
        // Must fold via SwayResult combinators — never direct list access.
        val count = result.fold(onSuccess = { it.size }, onFailure = { -1 })
        assertEquals(1, count)
        // getOrNull shows honest empty distinction.
        val successEmpty: SwayResult<PagedResult<Song>> = SwayResult.Success(PagedResult.empty())
        assertEquals(0, successEmpty.getOrNull()!!.size)
        val failure: SwayResult<PagedResult<Song>> = SwayResult.Failure(SwayError.Offline)
        assertNull(failure.getOrNull())
        assertNotNull(failure.errorOrNull())
    }
}

package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class SongTest {

    @Test fun `factory rejects blank primary id`() {
        assertNull(Song.create(id = "", rawTitle = "Title"))
        assertNull(Song.create(id = "   ", rawTitle = "Title"))
        assertNull(Song.create(id = "\t\n", rawTitle = "Title"))
        assertNull(Song.create(id = "  \t  ", rawTitle = "x"))
    }

    @Test fun `factory valid creates with sanitized title and raw preserved`() {
        val raw = "  My   Song  Title  "
        val song = Song.create(
            id = "  song123  ",
            rawTitle = raw,
            artistName = "  Artist  ",
            artistId = "artist1",
            albumName = " Album ",
            albumId = "album1",
            durationMs = 185_000,
            artwork = ArtworkRef.of("https://c/a.jpg")
        )!!
        assertEquals("song123", song.id.value)
        assertEquals(raw, song.rawTitle)
        assertEquals("My Song Title", song.title)
        assertEquals("Artist", song.artistName)
        assertEquals("artist1", song.artistId!!.value)
        assertEquals("Album", song.albumName)
        assertEquals("album1", song.albumId!!.value)
        assertEquals(185_000L, song.duration.millis)
        assertEquals("https://c/a.jpg", song.artwork!!.canonicalUrl)
    }

    @Test fun `blank child ids become null not failure`() {
        val s1 = Song.create(id = "id1", rawTitle = "t", artistId = "   ", albumId = "")!!
        assertNull(s1.artistId)
        assertNull(s1.albumId)

        val s2 = Song.create(id = "id1", rawTitle = "t", artistId = "  a1  ", albumId = "  ")!!
        assertEquals("a1", s2.artistId!!.value)
        assertNull(s2.albumId)
    }

    @Test fun `blank child names become null`() {
        val s = Song.create(id = "id1", rawTitle = "t", artistName = "   ", albumName = "")!!
        assertNull(s.artistName)
        assertNull(s.albumName)
    }

    @Test fun `negative duration clamped to zero`() {
        val s = Song.create(id = "id1", rawTitle = "t", durationMs = -5)!!
        assertEquals(DurationMs.ZERO, s.duration)
        assertEquals(0L, s.duration.millis)
    }

    @Test fun `duration typed prevents unit mix-up`() {
        val s = Song.create(id = "id1", rawTitle = "t", durationMs = 61_000)!!
        assertEquals("1:01", s.duration.format())
        assertTrue(s.duration is DurationMs)
        // compile safety: s.duration is DurationMs, not Long
    }

    @Test fun `artwork nullable`() {
        val withArt = Song.create(id = "id1", rawTitle = "t", artwork = ArtworkRef.of("https://c/a.jpg"))!!
        assertNotNull(withArt.artwork)
        val withoutArt = Song.create(id = "id1", rawTitle = "t", artwork = null)!!
        assertNull(withoutArt.artwork)
    }

    @Test fun `rawTitle preserved verbatim including blank sanitized result`() {
        val raw = "   "
        val s = Song.create(id = "id1", rawTitle = raw)!!
        assertEquals(raw, s.rawTitle)
        assertEquals("", s.title) // sanitized blank
    }

    @Test fun `typed overload`() {
        val id = SourceId.parse("sid")!!
        val song = Song.createTyped(id, rawTitle = "  hi  there  ", duration = DurationMs(5_000))
        assertEquals("sid", song.id.value)
        assertEquals("hi there", song.title)
        assertEquals("  hi  there  ", song.rawTitle)
        assertEquals(5_000L, song.duration.millis)
    }

    @Test fun `whitespace id trimmed and accepted`() {
        val s = Song.create(id = "  myId  ", rawTitle = "t")!!
        assertEquals("myId", s.id.value)
    }

    @Test fun `all nullable combos still valid when primary id ok`() {
        // only primary id is identity-lawed
        val s = Song.create(id = "id1", rawTitle = "Title", artistName = null, artistId = null, albumName = null, albumId = null, durationMs = 0, artwork = null)!!
        assertNotNull(s)
        assertNull(s.artistName)
        assertNull(s.artistId)
    }
}

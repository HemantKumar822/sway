package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class AlbumTest {

    @Test fun `rejects blank id`() {
        assertNull(Album.create(id = "", rawTitle = "t"))
        assertNull(Album.create(id = "   ", rawTitle = "t"))
        assertNull(Album.create(id = "\n", rawTitle = "t"))
    }

    @Test fun `valid with sanitized title raw preserved`() {
        val raw = "  My Album  "
        val a = Album.create(id = "alb1", rawTitle = raw, artistName = "  Artist  ", artistId = "art1", year = 2023, artwork = ArtworkRef.of("https://c/a.jpg"))!!
        assertEquals("alb1", a.id.value)
        assertEquals(raw, a.rawTitle)
        assertEquals("My Album", a.title)
        assertEquals("Artist", a.artistName)
        assertEquals("art1", a.artistId!!.value)
        assertEquals(2023, a.year)
        assertNotNull(a.artwork)
    }

    @Test fun `year absent and invalid clamped`() {
        val absent = Album.create(id = "id", rawTitle = "t", year = null)!!
        assertNull(absent.year)
        val invalidLow = Album.create(id = "id", rawTitle = "t", year = 999)!!
        assertNull(invalidLow.year) // clean omission
        val invalidHigh = Album.create(id = "id", rawTitle = "t", year = 9999)!!
        assertNull(invalidHigh.year)
        val valid = Album.create(id = "id", rawTitle = "t", year = 2000)!!
        assertEquals(2000, valid.year)
    }

    @Test fun `blank child ids and names become null`() {
        val a = Album.create(id = "id", rawTitle = "t", artistName = "   ", artistId = "  ")!!
        assertNull(a.artistName)
        assertNull(a.artistId)
    }

    @Test fun `typed overload`() {
        val id = SourceId.parse("aid")!!
        val a = Album.createTyped(id, rawTitle = "  hello  world  ", year = 2020)
        assertEquals("aid", a.id.value)
        assertEquals("hello world", a.title)
        assertEquals("  hello  world  ", a.rawTitle)
        assertEquals(2020, a.year)
    }

    @Test fun `whitespace id trimmed`() {
        val a = Album.create(id = "  aid  ", rawTitle = "t")!!
        assertEquals("aid", a.id.value)
    }
}

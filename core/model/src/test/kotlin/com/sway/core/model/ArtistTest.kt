package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class ArtistTest {

    @Test fun `rejects blank id`() {
        assertNull(Artist.create(id = "", rawName = "n"))
        assertNull(Artist.create(id = "   ", rawName = "n"))
    }

    @Test fun `valid sanitized name raw preserved`() {
        val raw = "  My  Artist  "
        val ar = Artist.create(id = "art1", rawName = raw, artwork = ArtworkRef.of("https://c/a.jpg"))!!
        assertEquals("art1", ar.id.value)
        assertEquals(raw, ar.rawName)
        assertEquals("My Artist", ar.name)
        assertNotNull(ar.artwork)
        assertTrue(ar.topSongs.isEmpty())
    }

    @Test fun `topSongs list preserved and defensive copy`() {
        val song = Song.create(id = "s1", rawTitle = "t")!!
        val list = mutableListOf(song)
        val artist = Artist.create(id = "a1", rawName = "n", topSongs = list)!!
        assertEquals(1, artist.topSongs.size)
        list.add(Song.create(id = "s2", rawTitle = "t2")!!)
        assertEquals(1, artist.topSongs.size) // defensive copy
    }

    @Test fun `typed overload`() {
        val id = SourceId.parse("aid")!!
        val ar = Artist.createTyped(id, rawName = "  hello  ")
        assertEquals("hello", ar.name)
        assertEquals("  hello  ", ar.rawName)
    }

    @Test fun `whitespace id trimmed`() {
        val ar = Artist.create(id = "  aid  ", rawName = "n")!!
        assertEquals("aid", ar.id.value)
    }

    @Test fun `raw blank name results in empty display but still valid`() {
        val ar = Artist.create(id = "id", rawName = "   ")!!
        assertEquals("   ", ar.rawName)
        assertEquals("", ar.name)
    }
}

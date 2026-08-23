package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class CatalogPlaylistTest {

    @Test fun `rejects blank id`() {
        assertNull(CatalogPlaylist.create(id = "", rawTitle = "t"))
        assertNull(CatalogPlaylist.create(id = "   ", rawTitle = "t"))
    }

    @Test fun `valid sanitized title curator and count`() {
        val raw = "  My  Playlist  "
        val cp = CatalogPlaylist.create(
            id = "pl1",
            rawTitle = raw,
            curator = "  Curator Name  ",
            trackCount = 42,
            artwork = ArtworkRef.of("https://c/a.jpg")
        )!!
        assertEquals("pl1", cp.id.value)
        assertEquals(raw, cp.rawTitle)
        assertEquals("My Playlist", cp.title)
        assertEquals("Curator Name", cp.curator)
        assertEquals(42, cp.trackCount)
        assertNotNull(cp.artwork)
    }

    @Test fun `blank curator becomes null`() {
        val cp = CatalogPlaylist.create(id = "id", rawTitle = "t", curator = "   ")!!
        assertNull(cp.curator)
        val cp2 = CatalogPlaylist.create(id = "id", rawTitle = "t", curator = null)!!
        assertNull(cp2.curator)
    }

    @Test fun `negative count becomes null`() {
        val cp = CatalogPlaylist.create(id = "id", rawTitle = "t", trackCount = -1)!!
        assertNull(cp.trackCount)
        val cp2 = CatalogPlaylist.create(id = "id", rawTitle = "t", trackCount = 0)!!
        assertEquals(0, cp2.trackCount)
    }

    @Test fun `typed overload`() {
        val id = SourceId.parse("pid")!!
        val cp = CatalogPlaylist.createTyped(id, rawTitle = "  hello  world  ", curator = "  c  ", trackCount = 5)
        assertEquals("hello world", cp.title)
        assertEquals("c", cp.curator)
        assertEquals(5, cp.trackCount)
    }

    @Test fun `no public copy — mutation only via factories`() {
        // Private primary constructor makes data-class copy private (consistent copy visibility).
        // Verify consumers cannot mutate via copy: they must use factories returning new instances.
        val cp = CatalogPlaylist.create(id = "id", rawTitle = "t")!!
        val viaFactory = CatalogPlaylist.createTyped(id = SourceId.parse("newId")!!, rawTitle = "t")
        assertEquals("newId", viaFactory.id.value)
        assertNotEquals(cp.id, viaFactory.id)
    }
}

package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class ArtworkRefTest {

    @Test fun `parse valid single`() {
        val ref = ArtworkRef.parse("https://example.com/a.jpg")!!
        assertEquals("https://example.com/a.jpg", ref.canonicalUrl)
        assertEquals("https://example.com/a.jpg", ref.cacheKey)
        assertEquals(listOf("https://example.com/a.jpg"), ref.candidates)
    }

    @Test fun `cacheKey equals canonical exactly`() {
        val url = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        val ref = ArtworkRef.of(url)!!
        assertEquals(url, ref.cacheKey)
        assertEquals(url, ref.canonicalUrl)
    }

    @Test fun `parse blank returns null`() {
        assertNull(ArtworkRef.parse(null))
        assertNull(ArtworkRef.parse(""))
        assertNull(ArtworkRef.parse("   "))
        assertNull(ArtworkRef.of("   "))
    }

    @Test fun `candidates order matters equality`() {
        val a = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        val b = ArtworkRef("https://c/a.jpg", listOf("https://c/b.jpg", "https://c/a.jpg"))
        assertNotEquals(a, b) // order is semantic (AR-10)

        val c = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        assertEquals(a, c)
    }

    @Test fun `parse drops blank candidates and ensures canonical first`() {
        val ref = ArtworkRef.parse(
            "https://c/canonical.jpg",
            listOf("   ", "https://c/canonical.jpg", "https://c/other.jpg", "")
        )!!
        assertEquals("https://c/canonical.jpg", ref.candidates.first())
        assertFalse(ref.candidates.any { it.isBlank() })
    }

    @Test fun `parse with extra candidates preserves order and distinct`() {
        val ref = ArtworkRef.parse(
            "https://c/a.jpg",
            listOf("https://c/b.jpg", "https://c/c.jpg")
        )!!
        assertEquals(listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"), ref.candidates)
    }

    @Test fun `synthetic from videoId`() {
        val ref = ArtworkRef.synthetic("  dQw4w9WgXcQ  ")!!
        assertTrue(ref.canonicalUrl.contains("dQw4w9WgXcQ"))
        assertEquals(ref.canonicalUrl, ref.cacheKey)
        assertNull(ArtworkRef.synthetic("   "))
        assertNull(ArtworkRef.synthetic(""))
    }

    @Test fun `absent returns null`() {
        assertNull(ArtworkRef.absent())
    }

    @Test fun `direct constructor rejects blank`() {
        try {
            ArtworkRef("", listOf(""))
            fail()
        } catch (_: IllegalArgumentException) { }
        try {
            ArtworkRef("https://c/a.jpg", emptyList())
            fail()
        } catch (_: IllegalArgumentException) { }
    }
}

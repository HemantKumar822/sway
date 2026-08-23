package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class ArtworkRefTest {

    @Test fun `parse valid single`() {
        val ref = ArtworkRef.parse("https://example.com/a.jpg")!!
        assertEquals("https://example.com/a.jpg", ref.canonicalUrl)
        assertEquals("https://example.com/a.jpg", ref.cacheKey)
        assertEquals(listOf("https://example.com/a.jpg"), ref.candidates)
        assertFalse(ref.hasFallbacks)
    }

    @Test fun `cacheKey equals canonical exactly`() {
        val url = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        val ref = ArtworkRef.of(url)!!
        assertEquals(url, ref.cacheKey)
        assertEquals(url, ref.canonicalUrl)
    }

    @Test fun `cacheKey stable across candidate chains`() {
        val canonical = "https://i.ytimg.com/vi/x/maxresdefault.jpg"
        val ref = ArtworkRef.synthetic("x")!!
        assertEquals(canonical, ref.canonicalUrl)
        assertEquals(canonical, ref.cacheKey)
        // cacheKey must be canonical even though chain has 4 entries
        assertEquals(4, ref.candidates.size)
        assertEquals(canonical, ref.candidates.first())
    }

    @Test fun `parse blank returns null`() {
        assertNull(ArtworkRef.parse(null))
        assertNull(ArtworkRef.parse(""))
        assertNull(ArtworkRef.parse("   "))
        assertNull(ArtworkRef.of("   "))
    }

    @Test fun `candidates order matters equality`() {
        // Same canonical, different tail order => not equal (order is semantic AR-10)
        val a = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"))
        val b = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/c.jpg", "https://c/b.jpg"))
        assertNotEquals(a, b)

        val c = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"))
        assertEquals(a, c)
        assertEquals(a.hashCode(), c.hashCode())
    }

    @Test fun `identical canonical but different chain lengths differ`() {
        val single = ArtworkRef.of("https://c/a.jpg")!!
        val multi = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        assertNotEquals(single, multi)
    }

    @Test fun `parse drops blank candidates and ensures canonical first`() {
        val ref = ArtworkRef.parse(
            "https://c/canonical.jpg",
            listOf("   ", "https://c/canonical.jpg", "https://c/other.jpg", "")
        )!!
        assertEquals("https://c/canonical.jpg", ref.candidates.first())
        assertEquals("https://c/canonical.jpg", ref.canonicalUrl)
        assertFalse(ref.candidates.any { it.isBlank() })
        assertEquals(listOf("https://c/canonical.jpg", "https://c/other.jpg"), ref.candidates)
    }

    @Test fun `parse with extra candidates preserves order and distinct`() {
        val ref = ArtworkRef.parse(
            "https://c/a.jpg",
            listOf("https://c/b.jpg", "https://c/c.jpg")
        )!!
        assertEquals(listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"), ref.candidates)
    }

    @Test fun `parse deduplicates candidates`() {
        val ref = ArtworkRef.parse(
            "https://c/a.jpg",
            listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/b.jpg", "https://c/c.jpg")
        )!!
        assertEquals(listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"), ref.candidates)
    }

    @Test fun `synthetic from videoId builds AD-11 chain`() {
        val ref = ArtworkRef.synthetic("  dQw4w9WgXcQ  ")!!
        assertTrue(ref.canonicalUrl.contains("dQw4w9WgXcQ"))
        assertEquals(ref.canonicalUrl, ref.cacheKey)
        // AD-11: maxresdefault → sddefault → hqdefault → mqdefault
        val expected = listOf(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
        )
        assertEquals(expected, ref.candidates)
        assertEquals(expected.first(), ref.canonicalUrl)
        assertTrue(ref.hasFallbacks)
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
        try {
            ArtworkRef("https://c/a.jpg", listOf("https://c/b.jpg", "https://c/a.jpg"))
            fail("must require canonical first")
        } catch (_: IllegalArgumentException) { }
        try {
            ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/a.jpg"))
            fail("must require distinct candidates")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `walk-on-failure contract candidateAfter`() {
        val ref = ArtworkRef.synthetic("abc123")!!
        // Walk sequentially
        assertEquals("https://i.ytimg.com/vi/abc123/sddefault.jpg", ref.candidateAfter(ref.canonicalUrl))
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ref.candidateAfter("https://i.ytimg.com/vi/abc123/sddefault.jpg"))
        assertEquals("https://i.ytimg.com/vi/abc123/mqdefault.jpg", ref.candidateAfter("https://i.ytimg.com/vi/abc123/hqdefault.jpg"))
        assertNull(ref.candidateAfter("https://i.ytimg.com/vi/abc123/mqdefault.jpg")) // last => null
        assertNull(ref.candidateAfter("https://not/in/chain.jpg"))
    }

    @Test fun `candidateAt index helper`() {
        val ref = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg", "https://c/c.jpg"))
        assertEquals("https://c/a.jpg", ref.candidateAt(0))
        assertEquals("https://c/b.jpg", ref.candidateAt(1))
        assertEquals("https://c/c.jpg", ref.candidateAt(2))
        assertNull(ref.candidateAt(3))
        assertNull(ref.candidateAt(-1))
    }

    @Test fun `equality includes order and hashCode differs on reorder`() {
        val a = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        val b = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/c.jpg"))
        assertNotEquals(a, b)
        // same order => equal
        val a2 = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        assertEquals(a, a2)
    }

    @Test fun `candidates immutable view — hasFallbacks reflects size`() {
        val single = ArtworkRef.of("https://c/solo.jpg")!!
        assertFalse(single.hasFallbacks)
        val multi = ArtworkRef("https://c/a.jpg", listOf("https://c/a.jpg", "https://c/b.jpg"))
        assertTrue(multi.hasFallbacks)
    }
}

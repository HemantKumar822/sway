package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class SourceIdTest {

    @Test fun `parse valid trims`() {
        assertEquals("abc", SourceId.parse("  abc  ")!!.value)
        assertEquals("x", SourceId.parse("x")!!.value)
        assertNotNull(SourceId.parse("  a b  "))
        assertEquals("a b", SourceId.parse("  a b  ")!!.value)
    }

    @Test fun `parse blank returns null`() {
        assertNull(SourceId.parse(""))
        assertNull(SourceId.parse("   "))
        assertNull(SourceId.parse("\t\n"))
        assertNull(SourceId.parse(null))
        assertNull(SourceId.parseOrNull("   "))
    }

    @Test fun `direct constructor rejects blank`() {
        try {
            SourceId("   ")
            fail("should throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-blank"))
        }
        try {
            SourceId("")
            fail("should throw")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `toString is value`() {
        assertEquals("myId", SourceId.parse("myId").toString())
    }

    // PlaylistId
    @Test fun `PlaylistId parse requires prefix`() {
        assertNull(PlaylistId.parse(""))
        assertNull(PlaylistId.parse("   "))
        assertNull(PlaylistId.parse(null))
        assertNull(PlaylistId.parse("abc"))
        assertNotNull(PlaylistId.parse(" local:abc ")) // trimmed has prefix -> succeeds
        assertNotNull(PlaylistId.parse("local:abc"))
        assertEquals("local:abc", PlaylistId.parse("local:abc")!!.value)
        assertEquals("local:xyz", PlaylistId.parse("  local:xyz  ")!!.value)
        assertNull(PlaylistId.parse("LOCAL:abc")) // case-sensitive
    }

    @Test fun `PlaylistId direct constructor rejects missing prefix`() {
        try {
            PlaylistId("abc")
            fail()
        } catch (_: IllegalArgumentException) { }
        try {
            PlaylistId("  ")
            fail()
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `PlaylistId generate has prefix and unique`() {
        val a = PlaylistId.generate()
        val b = PlaylistId.generate()
        assertTrue(a.value.startsWith(PlaylistId.LOCAL_PREFIX))
        assertTrue(b.value.startsWith(PlaylistId.LOCAL_PREFIX))
        assertNotEquals(a.value, b.value)
    }

    @Test fun `SourceId and PlaylistId namespaces do not collide`() {
        val catalog = SourceId.parse("abc123")!!
        val local = PlaylistId.generate()
        assertFalse(local.value == catalog.value)
        assertTrue(local.value.startsWith("local:"))
        assertFalse(catalog.value.startsWith("local:"))
    }
}

package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class TitleSanitizationTest {

    @Test fun `raw preserved alongside sanitized`() {
        val raw = "  hello   world  \t\n  foo  "
        val sanitized = sanitizeTitle(raw)
        assertEquals("hello world foo", sanitized)
        // raw itself unchanged when we store
        val song = Song.create(id = "id1", rawTitle = raw)!!
        assertEquals(raw, song.rawTitle)
        assertEquals("hello world foo", song.title)
    }

    @Test fun `sanitize trims and collapses`() {
        assertEquals("", sanitizeTitle(""))
        assertEquals("", sanitizeTitle("   "))
        assertEquals("a", sanitizeTitle("  a  "))
        assertEquals("a b", sanitizeTitle("a    b"))
        assertEquals("a b c", sanitizeTitle("a\tb\nc"))
        assertEquals("hello world", sanitizeTitle("  hello   world  "))
    }

    @Test fun `sanitize preserves case and punctuation`() {
        assertEquals("Hello, World!", sanitizeTitle("  Hello,   World!  "))
        assertEquals("AC/DC - Back in Black", sanitizeTitle("AC/DC   -   Back in Black"))
    }
}

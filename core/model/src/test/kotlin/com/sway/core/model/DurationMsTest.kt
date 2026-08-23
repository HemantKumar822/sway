package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class DurationMsTest {

    @Test fun `zero and positive`() {
        assertEquals(0L, DurationMs.ZERO.millis)
        assertTrue(DurationMs.ZERO.isZero)
        assertFalse(DurationMs.ZERO.isPositive)
        val d = DurationMs(1_000)
        assertTrue(d.isPositive)
        assertEquals(1L, d.seconds)
    }

    @Test fun `negative throws via constructor`() {
        try {
            DurationMs(-1)
            fail("should throw")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `parseOrNull and clamp`() {
        assertNull(DurationMs.parseOrNull(null))
        assertNull(DurationMs.parseOrNull(-5))
        assertNotNull(DurationMs.parseOrNull(0))
        assertEquals(DurationMs.ZERO, DurationMs.clamp(-100))
        assertEquals(500L, DurationMs.clamp(500).millis)
    }

    @Test fun `format m colon ss at edge`() {
        assertEquals("0:00", DurationMs(0).format())
        assertEquals("0:01", DurationMs(1_000).format())
        assertEquals("0:09", DurationMs(9_000).format())
        assertEquals("0:10", DurationMs(10_000).format())
        assertEquals("1:01", DurationMs(61_000).format())
        assertEquals("59:59", DurationMs(3_599_000).format())
        assertEquals("60:00", DurationMs(3_600_000).format())
        assertEquals("10:00", DurationMs(600_000).format())
        // truncates millis
        assertEquals("0:01", DurationMs(1_999).format())
    }

    @Test fun `plus minus`() {
        assertEquals(3_000L, (DurationMs(1_000) + DurationMs(2_000)).millis)
        assertEquals(500L, (DurationMs(1_000) - DurationMs(500)).millis)
        assertEquals(0L, (DurationMs(500) - DurationMs(1_000)).millis) // clamped to zero
    }

    @Test fun `extension helpers`() {
        assertEquals(0L, (-5L).toDurationMs().millis)
        assertEquals(1_000L, 1_000L.toDurationMs().millis)
        assertNull(null.toDurationMsOrNull())
        assertNull((-1L).toDurationMsOrNull())
        assertNotNull(0L.toDurationMsOrNull())
    }

    @Test fun `typed prevents Long mix-up compile check`() {
        // This test documents the type safety: DurationMs is not assignable to Long.
        val d: DurationMs = DurationMs(123)
        assertNotEquals(123L as Any, d as Any) // value class vs Long not equal
        assertEquals(123L, d.millis)
    }
}

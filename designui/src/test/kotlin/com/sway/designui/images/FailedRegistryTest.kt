package com.sway.designui.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Story 13.1 — exhausted-artwork registry (FR-36 retry trigger).
 * Pure in-process state: fresh process re-attempts naturally.
 */
class FailedRegistryTest {

    @Before fun setUp() = FailedArtworkRegistry.resetForTest()

    @Test fun markAndKeys() {
        FailedArtworkRegistry.markFailed("https://a")
        FailedArtworkRegistry.markFailed("https://b")
        assertEquals(setOf("https://a", "https://b"), FailedArtworkRegistry.keys)
    }

    @Test fun retryAll_clearsAndReturnsCount() {
        FailedArtworkRegistry.markFailed("https://a")
        FailedArtworkRegistry.markFailed("https://b")
        assertEquals(2, FailedArtworkRegistry.retryAll())
        assertTrue(FailedArtworkRegistry.keys.isEmpty())
        assertEquals(0, FailedArtworkRegistry.retryAll())
    }

    @Test fun resetForTest_clears() {
        FailedArtworkRegistry.markFailed("https://a")
        FailedArtworkRegistry.resetForTest()
        assertTrue(FailedArtworkRegistry.keys.isEmpty())
    }
}

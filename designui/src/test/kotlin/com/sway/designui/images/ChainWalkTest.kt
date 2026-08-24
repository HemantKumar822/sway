package com.sway.designui.images

import com.sway.core.model.ArtworkRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Story 13.1 — candidate-chain walk law (AR-10, FR-36): pure ArtworkRef contract
 * consumed by SwayAsyncImage. Zero host logic lives in the walker.
 */
class ChainWalkTest {

    @Test fun walk_canonical_thenFallbackInOrder() {
        val ref = ArtworkRef(
            canonicalUrl = "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            candidates = listOf(
                "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
                "https://i.ytimg.com/vi/abc/sddefault.jpg",
                "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            ),
        )
        assertEquals("https://i.ytimg.com/vi/abc/sddefault.jpg", ref.candidateAfter(ref.canonicalUrl))
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", ref.candidateAfter("https://i.ytimg.com/vi/abc/sddefault.jpg"))
        assertNull(ref.candidateAfter("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertNull(ref.candidateAfter("https://unknown"))
    }

    @Test fun synthetic_chain_isFourOrdered_maxFirst() {
        val ref = ArtworkRef.synthetic("dQw4w9WgXcQ")!!
        assertEquals(4, ref.candidates.size)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", ref.canonicalUrl)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg", ref.candidateAfter(ref.canonicalUrl))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", ref.candidateAfter(ref.candidates[1]))
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", ref.candidateAt(3))
        assertNull(ref.candidateAfter(ref.candidates.last()))
        assertEquals(ref.canonicalUrl, ref.cacheKey)
    }

    @Test fun candidateAt_indexBounds() {
        val ref = ArtworkRef.synthetic("vid")!!
        assertEquals(ref.canonicalUrl, ref.candidateAt(0))
        assertNull(ref.candidateAt(99))
        assertNull(ref.candidateAt(-1))
    }
}

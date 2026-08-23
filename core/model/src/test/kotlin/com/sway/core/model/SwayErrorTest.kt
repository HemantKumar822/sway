package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class SwayErrorTest {

    // AD-9 table — each of the seven categories lands on its documented UI state
    // Exhaustive when compiles without else (no else branch present).

    @Test fun `Offline maps to Offline uiState`() {
        val error: SwayError = SwayError.Offline
        val uiState = error.toUiState()
        assertEquals(SwayErrorUiState.Offline, uiState)
        assertEquals(SwayErrorUiState.Offline, error.uiState)
        // exhaustive when without else must compile — proves category coverage
        val label = when (error) {
            is SwayError.Offline -> "offline"
            is SwayError.RateLimited -> "rate"
            is SwayError.UpstreamUnavailable -> "upstream"
            is SwayError.Parse -> "parse"
            is SwayError.ContentNotFound -> "notfound"
            is SwayError.Storage -> "storage"
            is SwayError.Unknown -> "unknown"
        }
        assertEquals("offline", label)
    }

    @Test fun `RateLimited maps to RateLimited`() {
        val error: SwayError = SwayError.RateLimited
        assertEquals(SwayErrorUiState.RateLimited, error.toUiState())
        assertTrue(error.isRetryable())
    }

    @Test fun `UpstreamUnavailable maps to UpstreamUnavailable`() {
        val error: SwayError = SwayError.UpstreamUnavailable
        assertEquals(SwayErrorUiState.UpstreamUnavailable, error.toUiState())
        assertTrue(error.isRetryable())
    }

    @Test fun `Parse maps to Parse and preserves shapeInfo`() {
        val withoutInfo: SwayError = SwayError.Parse()
        assertEquals(SwayErrorUiState.Parse, withoutInfo.toUiState())
        assertNull((withoutInfo as SwayError.Parse).shapeInfo)

        val withInfo = SwayError.Parse(shapeInfo = "missing field 'title' at $.items[2]")
        assertEquals("missing field 'title' at \$.items[2]", withInfo.shapeInfo)
        assertEquals(SwayErrorUiState.Parse, withInfo.toUiState())
        // Parse renders with UpstreamUnavailable copy but mapping is distinct (AD-9 row 4)
        assertNotEquals(SwayErrorUiState.UpstreamUnavailable, withInfo.toUiState())
        assertTrue(withInfo.isRetryable())
    }

    @Test fun `ContentNotFound maps to ContentNotFound and is not retryable`() {
        val error: SwayError = SwayError.ContentNotFound
        assertEquals(SwayErrorUiState.ContentNotFound, error.toUiState())
        assertFalse(error.isRetryable())
    }

    @Test fun `Storage maps to Storage and is not retryable`() {
        val error: SwayError = SwayError.Storage
        assertEquals(SwayErrorUiState.Storage, error.toUiState())
        assertFalse(error.isRetryable())
    }

    @Test fun `Unknown preserves cause and maps to Unknown`() {
        val cause = IllegalStateException("upstream exploded")
        val withCause: SwayError = SwayError.Unknown(cause)
        assertEquals(SwayErrorUiState.Unknown, withCause.toUiState())
        assertSame(cause, (withCause as SwayError.Unknown).cause)
        assertTrue(withCause.isRetryable())

        val withoutCause: SwayError = SwayError.Unknown()
        assertEquals(SwayErrorUiState.Unknown, withoutCause.toUiState())
        assertNull((withoutCause as SwayError.Unknown).cause)

        val nullCause: SwayError = SwayError.Unknown(null)
        assertNull((nullCause as SwayError.Unknown).cause)

        // cause chain preserved (nested)
        val chained = RuntimeException("root", cause)
        val chainedUnknown = SwayError.Unknown(chained)
        assertSame(chained, chainedUnknown.cause)
        assertSame(cause, chainedUnknown.cause!!.cause)
    }

    @Test fun `Unknown exposes no stack trace to UI`() {
        // UI layer only sees UiState, never the cause string — verify mapping hides it
        val error = SwayError.Unknown(RuntimeException("sensitive stack"))
        val uiState = error.toUiState()
        // uiState is just an enum, no throwable inside
        assertEquals(SwayErrorUiState.Unknown, uiState)
        // cause is still available for diagnostics
        assertNotNull((error as SwayError.Unknown).cause)
    }

    @Test fun `seven categories are distinct types`() {
        val errors: List<SwayError> = listOf(
            SwayError.Offline,
            SwayError.RateLimited,
            SwayError.UpstreamUnavailable,
            SwayError.Parse("x"),
            SwayError.ContentNotFound,
            SwayError.Storage,
            SwayError.Unknown(),
        )
        // all map to distinct UiStates — 1:1 per AD-9 (7 distinct)
        val uiStates = errors.map { it.toUiState() }
        assertEquals(7, uiStates.distinct().size)
        // order matches table
        assertEquals(
            listOf(
                SwayErrorUiState.Offline,
                SwayErrorUiState.RateLimited,
                SwayErrorUiState.UpstreamUnavailable,
                SwayErrorUiState.Parse,
                SwayErrorUiState.ContentNotFound,
                SwayErrorUiState.Storage,
                SwayErrorUiState.Unknown,
            ),
            uiStates,
        )
    }

    @Test fun `exhaustive when over SwayError without else compiles`() {
        fun message(error: SwayError): String = when (error) {
            is SwayError.Offline -> "offline banner"
            is SwayError.RateLimited -> "rate limited"
            is SwayError.UpstreamUnavailable -> "upstream"
            is SwayError.Parse -> "parse: ${error.shapeInfo}"
            is SwayError.ContentNotFound -> "not found"
            is SwayError.Storage -> "storage"
            is SwayError.Unknown -> "unknown cause=${error.cause?.message}"
        }
        assertEquals("offline banner", message(SwayError.Offline))
        assertTrue(message(SwayError.Parse("bad json")).startsWith("parse:"))
        assertTrue(message(SwayError.Unknown(RuntimeException("x"))).startsWith("unknown"))
    }

    @Test fun `exhaustive when over UiState without else compiles`() {
        fun isErrorWithRetry(state: SwayErrorUiState): Boolean = when (state) {
            SwayErrorUiState.Offline -> true
            SwayErrorUiState.RateLimited -> true
            SwayErrorUiState.UpstreamUnavailable -> true
            SwayErrorUiState.Parse -> true
            SwayErrorUiState.ContentNotFound -> false
            SwayErrorUiState.Storage -> false
            SwayErrorUiState.Unknown -> true
        }
        assertTrue(isErrorWithRetry(SwayErrorUiState.Offline))
        assertFalse(isErrorWithRetry(SwayErrorUiState.ContentNotFound))
    }

    @Test fun `failures travel as values never thrown`() {
        // Simulate module boundary: a function returns Failure as value, caller handles without catch
        fun failingOperation(): SwayResult<String> = SwayResult.Failure(SwayError.Offline)

        var caught = false
        var handledAsValue = false
        try {
            val result = failingOperation()
            // no throw — handle via exhaustive when without else
            when (result) {
                is SwayResult.Success -> fail("expected failure")
                is SwayResult.Failure -> {
                    assertEquals(SwayError.Offline, result.error)
                    handledAsValue = true
                }
            }
        } catch (_: Throwable) {
            caught = true
        }
        assertFalse("Failure must not be thrown", caught)
        assertTrue(handledAsValue)
    }

    @Test fun `uiState property mirrors toUiState for all categories`() {
        val all: List<SwayError> = listOf(
            SwayError.Offline,
            SwayError.RateLimited,
            SwayError.UpstreamUnavailable,
            SwayError.Parse(),
            SwayError.ContentNotFound,
            SwayError.Storage,
            SwayError.Unknown(RuntimeException()),
        )
        all.forEach { error ->
            assertEquals(error.toUiState(), error.uiState)
        }
    }
}

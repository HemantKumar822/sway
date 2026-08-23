package com.sway.core.model

import org.junit.Assert.*
import org.junit.Test

class SwayResultTest {

    // ---- construction + exhaustive when without else ----

    @Test fun `exhaustive when over SwayResult without else compiles`() {
        val success: SwayResult<Int> = SwayResult.Success(42)
        val failure: SwayResult<Int> = SwayResult.Failure(SwayError.Offline)
        fun describe(r: SwayResult<Int>): String = when (r) {
            is SwayResult.Success -> "success:${r.data}"
            is SwayResult.Failure -> "failure:${r.error.toUiState()}"
        }
        assertEquals("success:42", describe(success))
        assertEquals("failure:Offline", describe(failure))
    }

    @Test fun `isSuccess isFailure getOrNull errorOrNull`() {
        val s: SwayResult<String> = SwayResult.Success("hello")
        assertTrue(s.isSuccess)
        assertFalse(s.isFailure)
        assertEquals("hello", s.getOrNull())
        assertNull(s.errorOrNull())

        val f: SwayResult<String> = SwayResult.Failure(SwayError.Storage)
        assertFalse(f.isSuccess)
        assertTrue(f.isFailure)
        assertNull(f.getOrNull())
        assertEquals(SwayError.Storage, f.errorOrNull())
    }

    @Test fun `Success allows honest emptyList`() {
        // NFR-2: emptyList never a failure signal; typed empty is valid Success
        val empty: SwayResult<List<String>> = SwayResult.Success(emptyList())
        assertTrue(empty.isSuccess)
        assertEquals(0, (empty as SwayResult.Success).data.size)
        // must be distinguishable from Failure
        val failure: SwayResult<List<String>> = SwayResult.Failure(SwayError.UpstreamUnavailable)
        assertTrue(failure.isFailure)
    }

    // ---- map ----

    @Test fun `map transforms Success and propagates Failure unchanged`() {
        val success = SwayResult.Success(2)
        val mapped = success.map { it * 3 }
        assertEquals(SwayResult.Success(6), mapped)

        val failure: SwayResult<Int> = SwayResult.Failure(SwayError.RateLimited)
        val mappedFailure = failure.map { it * 3 }
        assertEquals(failure, mappedFailure)
        assertTrue(mappedFailure is SwayResult.Failure)
        assertEquals(SwayError.RateLimited, (mappedFailure as SwayResult.Failure).error)
    }

    @Test fun `map chain preserves error category`() {
        val start: SwayResult<String> = SwayResult.Failure(SwayError.Parse("bad shape"))
        val result = start
            .map { it.length }
            .map { it * 2 }
        assertTrue(result is SwayResult.Failure)
        assertEquals(SwayError.Parse::class, (result as SwayResult.Failure).error::class)
        assertEquals("bad shape", (result.error as SwayError.Parse).shapeInfo)
    }

    @Test fun `flatMap chains result-producing transforms`() {
        fun parseInt(s: String): SwayResult<Int> =
            s.toIntOrNull()?.let { SwayResult.Success(it) } ?: SwayResult.Failure(SwayError.Parse("not an int: $s"))

        assertEquals(SwayResult.Success(123), SwayResult.Success("123").flatMap(::parseInt))
        assertTrue(SwayResult.Success("abc").flatMap(::parseInt) is SwayResult.Failure)
        // failure short-circuits flatMap
        val fail: SwayResult<String> = SwayResult.Failure(SwayError.Offline)
        assertEquals(fail, fail.flatMap(::parseInt))
    }

    // ---- onSuccess / onFailure ----

    @Test fun `onSuccess called only on Success`() {
        var successCalled = 0
        var successValue: Int? = null
        SwayResult.Success(10).onSuccess { successValue = it; successCalled++ }
        assertEquals(1, successCalled)
        assertEquals(10, successValue)

        successCalled = 0
        SwayResult.Failure(SwayError.Offline).onSuccess { successCalled++ }
        assertEquals(0, successCalled)
    }

    @Test fun `onFailure called only on Failure`() {
        var failureCalled = 0
        var captured: SwayError? = null
        SwayResult.Failure(SwayError.UpstreamUnavailable).onFailure { captured = it; failureCalled++ }
        assertEquals(1, failureCalled)
        assertEquals(SwayError.UpstreamUnavailable, captured)

        failureCalled = 0
        SwayResult.Success("ok").onFailure { failureCalled++ }
        assertEquals(0, failureCalled)
    }

    @Test fun `onSuccess and onFailure are chainable and return same instance`() {
        val success = SwayResult.Success(5)
        val chained = success
            .onSuccess { assertEquals(5, it) }
            .onFailure { fail("should not be called") }
            .map { it + 1 }
        assertEquals(SwayResult.Success(6), chained)

        val failure: SwayResult<Int> = SwayResult.Failure(SwayError.Storage)
        var seen = false
        val chainedFail = failure
            .onSuccess { fail("should not be called") }
            .onFailure { seen = true; assertEquals(SwayError.Storage, it) }
        assertTrue(seen)
        assertTrue(chainedFail is SwayResult.Failure)
    }

    // ---- recover / recoverToState ----

    @Test fun `recover transforms Failure to Success and leaves Success unchanged`() {
        val failure: SwayResult<String> = SwayResult.Failure(SwayError.Offline)
        val recovered = failure.recover { error ->
            when (error) {
                is SwayError.Offline -> "stale-cache"
                is SwayError.RateLimited -> "retry-later"
                is SwayError.UpstreamUnavailable -> "upstream-fallback"
                is SwayError.Parse -> "parse-fallback"
                is SwayError.ContentNotFound -> "not-found-fallback"
                is SwayError.Storage -> "storage-fallback"
                is SwayError.Unknown -> "unknown-fallback"
            }
        }
        assertEquals(SwayResult.Success("stale-cache"), recovered)

        val success = SwayResult.Success("original")
        assertEquals(success, success.recover { "should not be used" })
    }

    @Test fun `recoverToState alias is exhaustive without else and 1-1`() {
        // AC: combinator recoverToState must exist and be exhaustive
        fun fallbackFor(error: SwayError): List<String> = when (error) {
            is SwayError.Offline -> listOf("cached")
            is SwayError.RateLimited -> emptyList()
            is SwayError.UpstreamUnavailable -> emptyList()
            is SwayError.Parse -> emptyList()
            is SwayError.ContentNotFound -> emptyList()
            is SwayError.Storage -> emptyList()
            is SwayError.Unknown -> emptyList()
        }

        val failure: SwayResult<List<String>> = SwayResult.Failure(SwayError.Offline)
        val recovered = failure.recoverToState(::fallbackFor)
        assertEquals(SwayResult.Success(listOf("cached")), recovered)

        // recoverToState leaves Success untouched
        val success: SwayResult<List<String>> = SwayResult.Success(listOf("a"))
        assertEquals(success, success.recoverToState(::fallbackFor))
    }

    @Test fun `recoverToState preserves Unknown cause`() {
        val cause = RuntimeException("root")
        val failure: SwayResult<Int> = SwayResult.Failure(SwayError.Unknown(cause))
        var capturedCause: Throwable? = null
        val recovered = failure.recoverToState { error ->
            when (error) {
                is SwayError.Unknown -> { capturedCause = error.cause; -1 }
                else -> 0
            }
        }
        assertEquals(SwayResult.Success(-1), recovered)
        assertSame(cause, capturedCause)
    }

    @Test fun `recoverToState exhaustive mapping per category`() {
        // Each category maps to a distinct fallback value — proves exhaustive handling
        val cases = listOf(
            SwayError.Offline to "offline",
            SwayError.RateLimited to "rate",
            SwayError.UpstreamUnavailable to "upstream",
            SwayError.Parse("x") to "parse",
            SwayError.ContentNotFound to "notfound",
            SwayError.Storage to "storage",
            SwayError.Unknown() to "unknown",
        )
        for ((error, expected) in cases) {
            val result: SwayResult<String> = SwayResult.Failure(error)
            val recovered = result.recoverToState { e ->
                when (e) {
                    is SwayError.Offline -> "offline"
                    is SwayError.RateLimited -> "rate"
                    is SwayError.UpstreamUnavailable -> "upstream"
                    is SwayError.Parse -> "parse"
                    is SwayError.ContentNotFound -> "notfound"
                    is SwayError.Storage -> "storage"
                    is SwayError.Unknown -> "unknown"
                }
            }
            assertEquals(SwayResult.Success(expected), recovered)
        }
    }

    // ---- fold ----

    @Test fun `fold maps both branches exhaustively without else`() {
        fun toDisplay(result: SwayResult<String>): String = result.fold(
            onSuccess = { "content:$it" },
            onFailure = { error ->
                when (error) {
                    is SwayError.Offline -> "offline"
                    is SwayError.RateLimited -> "rate"
                    is SwayError.UpstreamUnavailable -> "upstream"
                    is SwayError.Parse -> "parse"
                    is SwayError.ContentNotFound -> "empty"
                    is SwayError.Storage -> "storage"
                    is SwayError.Unknown -> "unknown"
                }
            },
        )
        assertEquals("content:hello", toDisplay(SwayResult.Success("hello")))
        assertEquals("offline", toDisplay(SwayResult.Failure(SwayError.Offline)))
        assertEquals("empty", toDisplay(SwayResult.Failure(SwayError.ContentNotFound)))
    }

    @Test fun `getOrDefault`() {
        assertEquals("a", SwayResult.Success("a").getOrDefault("default"))
        val failure: SwayResult<String> = SwayResult.Failure(SwayError.Unknown())
        assertEquals("default", failure.getOrDefault("default"))
    }

    @Test fun `Failure propagates across module boundary as value not throw`() {
        // Pure domain function — never throws, returns typed Failure
        fun repoCall(shouldFail: Boolean): SwayResult<Int> =
            if (shouldFail) SwayResult.Failure(SwayError.ContentNotFound) else SwayResult.Success(1)

        // Caller never needs try/catch — exhaustive when handles both branches without else
        val result = repoCall(true)
        var handled = false
        when (result) {
            is SwayResult.Success -> fail("expected failure")
            is SwayResult.Failure -> {
                when (result.error) {
                    is SwayError.Offline -> fail("wrong")
                    is SwayError.RateLimited -> fail("wrong")
                    is SwayError.UpstreamUnavailable -> fail("wrong")
                    is SwayError.Parse -> fail("wrong")
                    is SwayError.ContentNotFound -> handled = true
                    is SwayError.Storage -> fail("wrong")
                    is SwayError.Unknown -> fail("wrong")
                }
            }
        }
        assertTrue(handled)
        // Verify no exception was thrown (test would have failed)
    }

    @Test fun `Unknown cause preserved through map and recover chain`() {
        val cause = IllegalArgumentException("bad arg")
        val initial: SwayResult<Int> = SwayResult.Failure(SwayError.Unknown(cause))
        // map must not drop cause
        val afterMap = initial.map { it * 2 }
        assertTrue(afterMap is SwayResult.Failure)
        assertSame(cause, ((afterMap as SwayResult.Failure).error as SwayError.Unknown).cause)
        // recover sees intact cause
        val afterRecover = afterMap.recoverToState { e ->
            when (e) {
                is SwayError.Unknown -> if (e.cause === cause) 99 else -1
                else -> -1
            }
        }
        assertEquals(SwayResult.Success(99), afterRecover)
    }
}

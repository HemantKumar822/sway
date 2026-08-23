package com.sway.core.model

/**
 * Typed error taxonomy — AD-9, AR-8, NFR-2, FR-37 substrate.
 *
 * Seven categories, 1:1 onto UI states per AD-9 table (architecture.md:AD-9).
 * Failures travel as values (SwayResult.Failure), **never thrown across modules** (AR-14).
 * Unknown preserves its [cause] for diagnostics while exposing no stack trace to UI.
 *
 * Exhaustive `when` without `else` must compile:
 * ```
 * when (error) {
 *   is SwayError.Offline -> ...
 *   is SwayError.RateLimited -> ...
 *   is SwayError.UpstreamUnavailable -> ...
 *   is SwayError.Parse -> ...
 *   is SwayError.ContentNotFound -> ...
 *   is SwayError.Storage -> ...
 *   is SwayError.Unknown -> ...
 * }
 * ```
 * Adding a new category fails compilation at every call site — intentional.
 */
sealed class SwayError {

    /** No connectivity at call time — AD-9 row 1. */
    data object Offline : SwayError()

    /** Upstream throttling / challenge (HTTP 429 family) — AD-9 row 2. */
    data object RateLimited : SwayError()

    /** Extractor breakage / schema drift / non-2xx source — AD-9 row 3. */
    data object UpstreamUnavailable : SwayError()

    /**
     * Payload malformed — logged with [shapeInfo] for diagnostics (never shown verbatim to users).
     * UI surfaces render the UpstreamUnavailable copy for Parse (AD-9 row 4).
     */
    data class Parse(val shapeInfo: String? = null) : SwayError()

    /** Item gone / permanently unavailable — AD-9 row 5. */
    data object ContentNotFound : SwayError()

    /** Local DB / preferences IO failure — AD-9 row 6. */
    data object Storage : SwayError()

    /**
     * Unexpected error — preserves [cause] chain for diagnostics (AR-14: no stack trace reaches UI).
     * The cause is for logging only; UI mapping never surfaces it.
     */
    data class Unknown(val cause: Throwable? = null) : SwayError()
}

/**
 * 1:1 UI-state mapping for AD-9 (FR-37 every data surface renders exactly one state).
 *
 * Values mirror the AD-9 table's UI-state column:
 * | SwayError              | UiState                | Copy / behavior                          |
 * | Offline                | Offline                | banner + stale cache or error+retry      |
 * | RateLimited            | RateLimited            | error+retry, copy rotates after 2nd fail |
 * | UpstreamUnavailable    | UpstreamUnavailable    | error+retry "couldn't load"              |
 * | Parse                  | Parse                  | error+retry (user sees Upstream copy)    |
 * | ContentNotFound        | ContentNotFound        | empty "no longer available" / skip       |
 * | Storage                | Storage                | typed error panel                        |
 * | Unknown                | Unknown                | error+retry                              |
 *
 * Sealed/enum is intentionally isomorphic to [SwayError] so mapping is exhaustive
 * and a new error category cannot be added without updating the mapper.
 */
enum class SwayErrorUiState {
    Offline,
    RateLimited,
    UpstreamUnavailable,
    Parse,
    ContentNotFound,
    Storage,
    Unknown,
}

/**
 * Canonical AD-9 mapper — exhaustive `when` without `else`.
 *
 * Each [SwayError] lands on its documented [SwayErrorUiState]. This function is the
 * single source of truth; UI layers must route through it rather than re-deriving state.
 */
fun SwayError.toUiState(): SwayErrorUiState = when (this) {
    is SwayError.Offline -> SwayErrorUiState.Offline
    is SwayError.RateLimited -> SwayErrorUiState.RateLimited
    is SwayError.UpstreamUnavailable -> SwayErrorUiState.UpstreamUnavailable
    is SwayError.Parse -> SwayErrorUiState.Parse
    is SwayError.ContentNotFound -> SwayErrorUiState.ContentNotFound
    is SwayError.Storage -> SwayErrorUiState.Storage
    is SwayError.Unknown -> SwayErrorUiState.Unknown
}

/** Convenience alias — `error.uiState == error.toUiState()`. */
val SwayError.uiState: SwayErrorUiState get() = toUiState()

/**
 * Whether this error is retryable via user action (FR-37 error-with-retry).
 * ContentNotFound is *not* retryable (empty variant), Storage is a typed panel.
 */
fun SwayError.isRetryable(): Boolean = when (this) {
    is SwayError.Offline -> true
    is SwayError.RateLimited -> true
    is SwayError.UpstreamUnavailable -> true
    is SwayError.Parse -> true
    is SwayError.ContentNotFound -> false
    is SwayError.Storage -> false
    is SwayError.Unknown -> true
}

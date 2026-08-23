package com.sway.core.model

/**
 * Typed result union — AD-9, AR-8, NFR-2 (swallow-and-return-empty impossible).
 *
 * Every repository and resolver returns `SwayResult<T>`:
 * - [Success] carries typed data (including honest `emptyList()` as valid success).
 * - [Failure] carries a typed [SwayError] whose category maps 1:1 onto FR-37 UI states
 *   via [SwayError.toUiState] / [SwayError.uiState].
 *
 * Failures travel as **values**, never thrown across module boundaries (AR-8, AD-9,
 * AR-14: "Failures travel as SwayResult values, never thrown across module boundaries").
 * Exhaustive `when` without `else` must compile:
 * ```
 * when (result) {
 *   is SwayResult.Success -> render(result.data)
 *   is SwayResult.Failure -> renderError(result.error.toUiState())
 * }
 * ```
 *
 * Pure Kotlin — zero Android imports (CI import-ban enforced in :core:model).
 */
sealed class SwayResult<out T> {

    /** Typed success — `data` may be an empty collection (honest empty ≠ failure, NFR-2). */
    data class Success<T>(val data: T) : SwayResult<T>()

    /** Typed failure — never thrown, always a value. */
    data class Failure(val error: SwayError) : SwayResult<Nothing>()

    /** Convenience — true iff this is Success. */
    val isSuccess: Boolean get() = this is Success

    /** Convenience — true iff this is Failure. */
    val isFailure: Boolean get() = this is Failure

    /** Returns data or null (Failure → null). Does not throw. */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    /** Returns error or null (Success → null). Does not throw. */
    fun errorOrNull(): SwayError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    /** Returns data or [defaultValue] (Failure → default). Does not throw. */
    fun getOrDefault(defaultValue: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> defaultValue
    }
}

// ---------------------------------------------------------------------------
// Combinators — pure, never throw across modules.
// Each is implemented via exhaustive `when` without `else` so new subtypes
// break compilation at the combinator site intentionally.
// ---------------------------------------------------------------------------

/**
 * Transform success data; propagate failures unchanged.
 * Failure stays typed — no exception, no emptyList fallback.
 */
inline fun <T, R> SwayResult<T>.map(transform: (T) -> R): SwayResult<R> = when (this) {
    is SwayResult.Success -> SwayResult.Success(transform(data))
    is SwayResult.Failure -> this
}

/**
 * Flat-map / andThen — chain a result-producing transform.
 */
inline fun <T, R> SwayResult<T>.flatMap(transform: (T) -> SwayResult<R>): SwayResult<R> = when (this) {
    is SwayResult.Success -> transform(data)
    is SwayResult.Failure -> this
}

/**
 * Execute [action] on Success, return this unchanged for chaining.
 */
inline fun <T> SwayResult<T>.onSuccess(action: (T) -> Unit): SwayResult<T> {
    if (this is SwayResult.Success) action(data)
    return this
}

/**
 * Execute [action] on Failure, return this unchanged for chaining.
 */
inline fun <T> SwayResult<T>.onFailure(action: (SwayError) -> Unit): SwayResult<T> {
    if (this is SwayResult.Failure) action(error)
    return this
}

/**
 * Recover a Failure to a Success by mapping the error to a value.
 * Success passes through unchanged.
 */
inline fun <T> SwayResult<T>.recover(transform: (SwayError) -> T): SwayResult<T> = when (this) {
    is SwayResult.Success -> this
    is SwayResult.Failure -> SwayResult.Success(transform(error))
}

/**
 * Named alias required by story 2.2 AC: `recoverToState`.
 *
 * Recovers a Failure to a Success whose data represents a fallback UI/state value.
 * Signature intentionally mirrors [recover] — the "state" is a domain value (e.g. emptyList,
 * cached snapshot, or a UiState object) produced from the error category.
 *
 * Example:
 * ```
 * repo.songs(query)               // SwayResult<List<Song>>
 *   .recoverToState { error ->
 *     when (error) {              // exhaustive, no else
 *       is SwayError.Offline -> staleCache
 *       else -> emptyList()
 *     }
 *   }
 * ```
 */
inline fun <T> SwayResult<T>.recoverToState(transform: (SwayError) -> T): SwayResult<T> = when (this) {
    is SwayResult.Success -> this
    is SwayResult.Failure -> SwayResult.Success(transform(error))
}

/**
 * Fold to a single value — exhaustive, no else.
 */
inline fun <T, R> SwayResult<T>.fold(onSuccess: (T) -> R, onFailure: (SwayError) -> R): R = when (this) {
    is SwayResult.Success -> onSuccess(data)
    is SwayResult.Failure -> onFailure(error)
}

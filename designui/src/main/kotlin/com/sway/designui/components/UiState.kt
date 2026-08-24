package com.sway.designui.components

import com.sway.core.model.SwayErrorUiState
import com.sway.core.model.SwayResult
import com.sway.core.model.toUiState

/**
 * THE typed-state discipline (story 9.2, FR-37 kit substrate, P-D3): every
 * data-driven surface renders exactly ONE of these at any moment - blank
 * screens and silent failures are structurally impossible.
 *
 * [Error] carries the mapped [SwayErrorUiState] category (never a stack
 * trace) per NFR-2/FR-14 vocabulary. [fromResult] bridges repository
 * SwayResults: Success(empty) maps to Empty - but ONLY for network-shaped
 * reads; local Library flows arrive as Content directly (instant-from-DB
 * honesty law).
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Error(val category: SwayErrorUiState) : UiState<Nothing>
    data class Content<T>(val data: T) : UiState<T>
}

/** Repository-result bridge (list-shaped payloads). */
fun <T> List<T>.toUiState(): UiState<List<T>> =
    if (isEmpty()) UiState.Empty else UiState.Content(this)

/** Single-value bridge. */
fun <T> SwayResult<T>.toUiState(): UiState<T> = when (this) {
    is SwayResult.Success -> UiState.Content(data)
    is SwayResult.Failure -> UiState.Error(error.toUiState())
}

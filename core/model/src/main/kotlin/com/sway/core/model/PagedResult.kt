package com.sway.core.model

/**
 * Paged result for paginated catalog queries — AD-1/AR-2, AD-9, FR-1/FR-2.
 *
 * All [CatalogSource] search operations return [SwayResult] wrapping [PagedResult] rather
 * than bare lists (NFR-2: `emptyList` is never a failure signal; pagination metadata must
 * travel with data). This type carries:
 * - [items] — ordered, immutable snapshot of the page's typed models.
 * - [nextPageToken] — opaque continuation token from the transport; `null` means
 *   end-of-results (FR-2). Blank tokens are normalized to `null`.
 *
 * Equality is order-sensitive (list order = source order).
 * Pure Kotlin — zero Android imports (CI import-ban enforced in `:core:model`).
 */
data class PagedResult<T>(
    val items: List<T>,
    val nextPageToken: String? = null,
) {
    init {
        // Defensive: tokens that are blank/whitespace are end-of-results.
        // We do not throw here — callers normalize via factory; direct ctor with blank
        // is tolerated but discouraged. Validation helper exposes normalized view.
        require(items !== null) { "PagedResult items must not be null" }
    }

    /** Normalized continuation (blank → null). */
    val normalizedNextPageToken: String?
        get() = nextPageToken?.trim()?.takeIf { it.isNotEmpty() }

    /** True when another page exists. */
    val hasMore: Boolean get() = normalizedNextPageToken != null

    /** Number of items in this page. */
    val size: Int get() = items.size

    /** True when [items] is empty (honest empty ≠ failure per NFR-2/FR-37). */
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        /** Factory normalizing [nextPageToken] blanks to `null` and copying [items]. */
        fun <T> of(items: List<T>, nextPageToken: String? = null): PagedResult<T> {
            val token = nextPageToken?.trim()?.takeIf { it.isNotEmpty() }
            return PagedResult(items.toList(), token)
        }

        /** Empty page with no continuation — typed honest empty (Success(empty)). */
        fun <T> empty(): PagedResult<T> = PagedResult(emptyList(), null)

        /** Single-page helper. */
        fun <T> singlePage(items: List<T>): PagedResult<T> = of(items, null)
    }
}

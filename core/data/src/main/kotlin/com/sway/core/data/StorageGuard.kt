package com.sway.core.data

import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult

/**
 * Shared NFR-2 boundary guard for owned-data repositories: storage-class
 * failures (IO / SQL / closed-db misuse) surface as [SwayError.Storage] —
 * never an empty list masquerading as success. Anything else propagates as
 * a bug.
 */
internal inline fun <T> storageGuarded(block: () -> T): SwayResult<T> = try {
    SwayResult.Success(block())
} catch (e: java.io.IOException) {
    SwayResult.Failure(SwayError.Storage)
} catch (e: android.database.SQLException) {
    SwayResult.Failure(SwayError.Storage)
} catch (e: java.sql.SQLException) {
    SwayResult.Failure(SwayError.Storage)
} catch (e: IllegalStateException) {
    SwayResult.Failure(SwayError.Storage)
}

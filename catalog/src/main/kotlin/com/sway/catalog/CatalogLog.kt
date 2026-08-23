package com.sway.catalog

import android.util.Log

/**
 * Tag-consistent logging for `:catalog` — AR-14.
 *
 * Conventions:
 * - Tag prefix `Sway/Catalog` so all catalog lines filter together (`Sway/` family).
 * - Content law: never log user content beyond titles/artists needed for diagnostics;
 *   never log full response bodies; stack traces are for `w`/`e` with throwable only,
 *   never surfaced to UI (AD-9).
 * - Request/response logging (story 3.1) logs method + truncated URL + status code +
 *   latency, never bodies or sensitive query values verbatim beyond truncation.
 */
internal object CatalogLog {

    private const val TAG = "Sway/Catalog"

    fun d(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // Unit-test JVM without Android runtime (MockWebServer tests) — fall back to stdout.
            println("D/$TAG: $message")
        }
    }

    fun w(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
        } catch (_: RuntimeException) {
            val suffix = throwable?.let { " ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            println("W/$TAG: $message$suffix")
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        try {
            if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
        } catch (_: RuntimeException) {
            val suffix = throwable?.let { " ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            println("E/$TAG: $message$suffix")
        }
    }
}

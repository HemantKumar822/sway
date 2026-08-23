package com.sway.music.log

import android.util.Log

/**
 * Tag-consistent logging facade (AR-14). Tags are prefixed so all Sway lines filter
 * together ("Sway/Catalog", "Sway/Playback", ...).
 *
 * Content law: never log user content beyond titles/artists needed for diagnostics;
 * never let stack traces reach the UI. Release verbosity gating arrives with the
 * E15 hardening pass.
 */
object SwayLog {

    private const val TAG_PREFIX = "Sway/"

    fun d(tag: String, message: String) = Log.d(TAG_PREFIX + tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        if (throwable == null) Log.w(TAG_PREFIX + tag, message)
        else Log.w(TAG_PREFIX + tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        if (throwable == null) Log.e(TAG_PREFIX + tag, message)
        else Log.e(TAG_PREFIX + tag, message, throwable)
}

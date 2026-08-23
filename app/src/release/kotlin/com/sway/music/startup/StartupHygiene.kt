package com.sway.music.startup

import android.os.StrictMode

/**
 * Release variant of the startup-hygiene installer (AD-10): structurally inert, so no
 * StrictMode policy can ever be installed outside debug builds (story 1.2 AC). Any
 * violation is triaged to zero before release instead of suppressed (NFR-1) — see
 * app/config/strictmode-baseline-suppressions.txt.
 */
object StartupHygiene {

    val armed: Boolean = false

    val installedThreadPolicy: StrictMode.ThreadPolicy? = null

    fun install() {
        // Intentionally empty: StrictMode is debug-only by design (AD-10).
    }
}

package com.sway.music.startup

import android.os.StrictMode

/**
 * Debug variant of the startup-hygiene installer (AD-10/NFR-1): arms StrictMode with
 * the death penalty for main-thread disk reads/writes and network access, so blocking
 * work crashes loudly during development instead of shipping.
 *
 * The release variant of this symbol is a structural no-op — no StrictMode installation
 * can ever occur outside debug builds (story 1.2 AC). Suppressions are refused by policy:
 * violations are triaged to zero, never baselined (app/config/strictmode-baseline-suppressions.txt).
 */
object StartupHygiene {

    /** True once policies are armed; asserted by the Robolectric startup suite. */
    var armed: Boolean = false
        private set

    /**
     * Snapshot of the thread policy handed to [StrictMode.setThreadPolicy]. Exposed so
     * tests can prove the LIVE framework policy is exactly this death-penalty policy —
     * API-37 stubs hide ThreadPolicy's flag fields, making direct readback impossible
     * at compile time.
     */
    var installedThreadPolicy: StrictMode.ThreadPolicy? = null
        private set

    fun install() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyDeath()
            .build()
        StrictMode.setThreadPolicy(threadPolicy)
        installedThreadPolicy = threadPolicy
        // VM policy stays log-only: leak detection without process death keeps
        // diagnostics available while thread violations remain fatal (AD-10 triage).
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
        armed = true
    }
}

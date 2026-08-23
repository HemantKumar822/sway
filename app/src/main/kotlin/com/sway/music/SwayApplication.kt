package com.sway.music

import android.app.Application
import com.sway.music.startup.StartupHygiene
import dagger.hilt.android.HiltAndroidApp

/**
 * Process entry point and Hilt graph root (AD-2).
 *
 * Startup law (AD-10): onCreate performs no disk, network, or preferences work.
 * The sole non-framework statement arms debug StrictMode via the variant-split
 * installer; the release variant of [StartupHygiene] is a structural no-op.
 */
@HiltAndroidApp
class SwayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupHygiene.install()
    }
}
